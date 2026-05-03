package dev.groqplayer.entity;

import com.mojang.authlib.GameProfile;
import dev.groqplayer.GroqPlayerMod;
import dev.groqplayer.ai.GroqAIBrain;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.*;
import net.minecraft.world.GameMode;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.*;

public class GroqFakePlayer extends ServerPlayerEntity {

    private final GroqAIBrain brain;
    private final FakeClientConnection fakeConnection;
    private int thinkTimer = 0;
    private static final int THINK_INTERVAL = 60; // 3 seconds

    // Action queue — replaces single pendingAction
    private final LinkedList<GroqAIBrain.AIAction> actionQueue = new LinkedList<>();

    // Movement state
    private Vec3d moveTarget = null;
    private boolean isSprinting = false;
    private int lookTimer = 0;
    private float lookYaw = 0;
    private float lookPitch = 0;

    // Mining state — progress-based
    private BlockPos miningTarget = null;
    private float miningProgress = 0;
    private float miningTotalRequired = 0;
    private int miningSwingTimer = 0;

    // Crafting cooldown
    private int craftCooldown = 0;

    // Attack cooldown
    private int attackCooldown = 0;

    // Walking speed constants
    private static final double WALK_SPEED = 0.12;  // blocks per tick (~4.3 m/s)
    private static final double SPRINT_SPEED = 0.18; // blocks per tick (~5.6 m/s)

    public GroqFakePlayer(MinecraftServer server, ServerWorld world, GameProfile profile, String personality) {
        super(server, world, profile);
        this.brain = new GroqAIBrain(profile.getName(), personality);

        // Create fake connection that safely discards all packets
        this.fakeConnection = new FakeClientConnection();

        // Create a fake network handler
        this.networkHandler = new FakeNetworkHandler(server, this, fakeConnection);

        // Set game mode via ServerPlayerEntity method
        this.changeGameMode(GameMode.SURVIVAL);
        this.setNoGravity(false);
    }

    /**
     * Returns the fake ClientConnection used by this AI player.
     */
    public FakeClientConnection getFakeConnection() {
        return fakeConnection;
    }

    /**
     * Enqueue an action to the queue.
     */
    public void enqueueAction(GroqAIBrain.AIAction action) {
        actionQueue.addLast(action);
    }

    /**
     * Enqueue multiple actions.
     */
    public void enqueueActions(List<GroqAIBrain.AIAction> actions) {
        actionQueue.addAll(actions);
    }

    @Override
    public void tick() {
        // Prevent super.tick() from crashing
        try {
            super.tick();
        } catch (Exception e) {
            GroqPlayerMod.LOGGER.debug("[GroqPlayer] Ignored tick error for {}: {}", this.getName().getString(), e.getMessage());
        }

        if (this.getWorld().isClient()) return;

        ServerWorld world = (ServerWorld) this.getWorld();

        // Handle think timer
        thinkTimer++;
        if (thinkTimer >= THINK_INTERVAL) {
            thinkTimer = 0;
            brain.think(this, world);
        }

        // Poll actions from brain into queue
        GroqAIBrain.AIAction polledAction = brain.pollAction();
        if (polledAction != null) {
            actionQueue.addLast(polledAction);
        }

        // Process pending chat
        String chat = brain.pollChat();
        if (chat != null && !chat.isBlank()) {
            this.server.getPlayerManager().broadcast(
                Text.literal("<" + this.getName().getString() + "> " + chat),
                false
            );
        }

        // Process action queue — execute one action per tick from the queue
        if (!actionQueue.isEmpty()) {
            GroqAIBrain.AIAction action = actionQueue.pollFirst();
            if (action != null) {
                executeAction(action, world);
            }
        }

        // Continue movement toward target (every tick)
        if (moveTarget != null) {
            tickMovement(world);
        }

        // Continue mining progress (every tick)
        if (miningTarget != null) {
            tickMining(world);
        }

        // Cooldowns
        if (craftCooldown > 0) craftCooldown--;
        if (attackCooldown > 0) attackCooldown--;

        // Smooth look rotation
        if (lookTimer > 0) {
            this.setYaw(MathHelper.lerp(0.1f, this.getYaw(), lookYaw));
            this.setPitch(MathHelper.lerp(0.1f, this.getPitch(), lookPitch));
            lookTimer--;
        }

        // Auto-collect nearby items
        autoCollectItems(world);

        // Auto-eat when hungry
        autoEat();

        // Respawn if dead
        if (this.isDead() || this.getHealth() <= 0) {
            this.setHealth(this.getMaxHealth());
            BlockPos spawnPos = this.getSpawnPointPosition();
            if (spawnPos != null) {
                this.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
            } else {
                BlockPos worldSpawn = world.getSpawnPos();
                this.setPos(worldSpawn.getX() + 0.5, worldSpawn.getY(), worldSpawn.getZ() + 0.5);
            }
            this.server.getPlayerManager().broadcast(
                Text.literal("§e[GroqPlayer] §f" + this.getName().getString() + " respawned."),
                false
            );
        }
    }

    // ======================== MOVEMENT ========================

    /**
     * Movement using direct position updates instead of setVelocity().
     * setVelocity() doesn't work properly for ServerPlayerEntity on the server side,
     * so we teleport toward the target each tick by a small increment.
     */
    private void tickMovement(ServerWorld world) {
        Vec3d pos = this.getPos();
        Vec3d diff = moveTarget.subtract(pos);
        double horizDist = diff.horizontalLength();

        if (horizDist < 1.0) {
            // Arrived at target
            moveTarget = null;
            this.setSprinting(false);
            isSprinting = false;
            return;
        }

        double speed = isSprinting ? SPRINT_SPEED : WALK_SPEED;
        double step = Math.min(speed, horizDist);

        // Calculate direction
        double dx = (diff.x / horizDist) * step;
        double dz = (diff.z / horizDist) * step;

        double newX = pos.x + dx;
        double newZ = pos.z + dz;

        // Handle vertical: step up blocks or fall
        double newY = pos.y;
        BlockPos feetPos = BlockPos.ofFloored(newX, pos.y, newZ);
        BlockState feetBlock = world.getBlockState(feetPos);
        BlockState belowFeet = world.getBlockState(feetPos.down());

        // If the target block at feet level is solid, try stepping up 1 block
        if (!feetBlock.isAir() && feetBlock.isSolidBlock(world, feetPos)) {
            BlockState aboveFeet = world.getBlockState(feetPos.up());
            if (aboveFeet.isAir() || !aboveFeet.isSolidBlock(world, feetPos.up())) {
                newY = pos.y + 1.0; // Step up
            } else {
                // Can't step up, block the way
                newX = pos.x;
                newZ = pos.z;
            }
        }

        // Face toward target
        float targetYaw = (float) (Math.toDegrees(Math.atan2(-diff.x, diff.z)));
        this.setYaw(targetYaw);

        // Apply gravity — if not on ground, fall
        if (!this.isOnGround()) {
            newY = pos.y - 0.08; // Simple gravity
        } else if (newY <= pos.y) {
            // Stay on ground level
            newY = Math.max(newY, pos.y);
        }

        // Use setPos for direct position update
        this.setPos(newX, newY, newZ);

        // Jump if there's a block in front and we're on ground
        BlockPos frontPos = BlockPos.ofFloored(pos.add(new Vec3d(dx, 0, dz).normalize().multiply(0.6)));
        if (!world.getBlockState(frontPos).isAir() && this.isOnGround()) {
            this.jump();
        }
    }

    // ======================== MINING ========================

    /**
     * Start mining a block. Progress accumulates each tick.
     * When progress >= required, the block breaks.
     */
    private void startMining(Vec3d targetPos, ServerWorld world) {
        BlockPos blockPos = BlockPos.ofFloored(targetPos);
        BlockState state = world.getBlockState(blockPos);

        if (state.isAir() || state.getBlock() == net.minecraft.block.Blocks.BEDROCK) {
            return; // Can't mine air or bedrock
        }

        // Cancel previous mining if targeting a different block
        if (miningTarget != null && !miningTarget.equals(blockPos)) {
            miningProgress = 0;
        }

        miningTarget = blockPos;
        // Calculate total mining time based on block hardness and tool
        float hardness = state.getHardness(world, blockPos);
        miningTotalRequired = Math.max(1, hardness * 20); // Scale hardness to ticks
        miningProgress = 0;

        // Look at the block being mined
        this.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(blockPos));
    }

    /**
     * Tick the mining progress. Called every tick while miningTarget is set.
     */
    private void tickMining(ServerWorld world) {
        if (miningTarget == null) return;

        BlockState state = world.getBlockState(miningTarget);

        // Block was already broken or changed
        if (state.isAir()) {
            miningTarget = null;
            miningProgress = 0;
            return;
        }

        // Increment progress — tool speed affects this
        float speedMultiplier = getToolSpeedMultiplier(state);
        miningProgress += speedMultiplier;

        // Swing arm periodically while mining
        miningSwingTimer++;
        if (miningSwingTimer >= 4) {
            this.swingHand(Hand.MAIN_HAND);
            miningSwingTimer = 0;
        }

        // Check if mining complete
        if (miningProgress >= miningTotalRequired) {
            // Break the block with proper drops
            world.breakBlock(miningTarget, true, this);
            this.swingHand(Hand.MAIN_HAND);

            // Reset mining state
            miningTarget = null;
            miningProgress = 0;
            miningTotalRequired = 0;
        }
    }

    /**
     * Get mining speed multiplier based on equipped tool vs block type.
     */
    private float getToolSpeedMultiplier(BlockState state) {
        ItemStack tool = this.getMainHandStack();
        if (tool.isEmpty()) return 1.0f;

        float speed = tool.getMiningSpeedMultiplier(state);
        // If tool is effective against this block, use tool speed; otherwise base speed
        if (speed > 1.0f) {
            return speed;
        }
        return 1.0f;
    }

    // ======================== CRAFTING ========================

    /**
     * Simple crafting system with hardcoded recipes.
     * Checks if player has required materials, removes them, and adds the result.
     */
    private void craftItem(String itemId, ServerWorld world) {
        if (craftCooldown > 0) return;
        craftCooldown = 40; // 2 second cooldown between crafts

        CraftingRecipe recipe = CraftingRecipe.get(itemId);
        if (recipe == null) {
            chatMessage("I don't know how to craft " + itemId + "...");
            return;
        }

        // Check if we have required materials
        if (!hasIngredients(recipe)) {
            chatMessage("I don't have the materials for " + recipe.resultName + "!");
            return;
        }

        // Remove ingredients
        for (Ingredient ing : recipe.ingredients) {
            removeItems(ing.item, ing.count);
        }

        // Add result
        ItemStack result = new ItemStack(recipe.resultItem, recipe.resultCount);
        if (!this.getInventory().insertStack(result)) {
            // Drop if inventory full
            this.dropItem(result, false);
        }

        chatMessage("Crafted " + recipe.resultCount + "x " + recipe.resultName + "!");
    }

    private boolean hasIngredients(CraftingRecipe recipe) {
        for (Ingredient ing : recipe.ingredients) {
            if (countItem(ing.item) < ing.count) return false;
        }
        return true;
    }

    private int countItem(Item item) {
        int count = 0;
        for (int i = 0; i < this.getInventory().size(); i++) {
            ItemStack stack = this.getInventory().getStack(i);
            if (stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void removeItems(Item item, int count) {
        int remaining = count;
        for (int i = 0; i < this.getInventory().size() && remaining > 0; i++) {
            ItemStack stack = this.getInventory().getStack(i);
            if (stack.getItem() == item) {
                int toRemove = Math.min(stack.getCount(), remaining);
                stack.decrement(toRemove);
                remaining -= toRemove;
            }
        }
    }

    private void chatMessage(String msg) {
        this.server.getPlayerManager().broadcast(
            Text.literal("<" + this.getName().getString() + "> " + msg),
            false
        );
    }

    // ======================== INVENTORY ========================

    /**
     * Equip the best available tool.
     * Since getMiningSpeedMultiplier requires a BlockState, we use a heuristic:
     * prioritize tool items (pickaxe > axe > shovel > sword) in the hotbar.
     */
    private void equipBestTool() {
        int bestSlot = -1;
        int bestPriority = 0;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = this.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            int priority = getToolPriority(stack);
            if (priority > bestPriority) {
                bestPriority = priority;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0) {
            this.getInventory().selectedSlot = bestSlot;
        }
    }

    private int getToolPriority(ItemStack stack) {
        Item item = stack.getItem();
        if (item == Items.NETHERITE_PICKAXE) return 9;
        if (item == Items.DIAMOND_PICKAXE) return 8;
        if (item == Items.IRON_PICKAXE) return 7;
        if (item == Items.STONE_PICKAXE) return 6;
        if (item == Items.GOLDEN_PICKAXE) return 5;
        if (item == Items.WOODEN_PICKAXE) return 4;
        if (item == Items.NETHERITE_AXE) return 3;
        if (item == Items.DIAMOND_AXE) return 3;
        if (item == Items.IRON_AXE) return 3;
        if (item == Items.STONE_AXE) return 3;
        if (item == Items.WOODEN_AXE) return 3;
        if (item == Items.NETHERITE_SWORD) return 2;
        if (item == Items.DIAMOND_SWORD) return 2;
        if (item == Items.IRON_SWORD) return 2;
        if (item == Items.STONE_SWORD) return 2;
        if (item == Items.WOODEN_SWORD) return 2;
        if (item == Items.NETHERITE_SHOVEL) return 1;
        if (item == Items.DIAMOND_SHOVEL) return 1;
        if (item == Items.IRON_SHOVEL) return 1;
        if (item == Items.STONE_SHOVEL) return 1;
        if (item == Items.WOODEN_SHOVEL) return 1;
        return 0;
    }

    /**
     * Equip a specific item by name to the main hand.
     */
    private void equipItem(String itemId) {
        for (int i = 0; i < this.getInventory().size(); i++) {
            ItemStack stack = this.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem().toString().equals(itemId)) {
                // Move to hotbar slot if not already
                if (i >= 9) {
                    int hotbarSlot = findEmptyHotbarSlot();
                    if (hotbarSlot >= 0) {
                        this.getInventory().setStack(hotbarSlot, stack.copy());
                        this.getInventory().setStack(i, ItemStack.EMPTY);
                        this.getInventory().selectedSlot = hotbarSlot;
                    }
                } else {
                    this.getInventory().selectedSlot = i;
                }
                return;
            }
        }
    }

    private int findEmptyHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            if (this.getInventory().getStack(i).isEmpty()) return i;
        }
        return 0; // Default to slot 0 if full
    }

    // ======================== ACTION EXECUTION ========================

    private void executeAction(GroqAIBrain.AIAction action, ServerWorld world) {
        switch (action.type()) {
            case "walk" -> {
                moveTarget = action.target();
                this.setSprinting(false);
                isSprinting = false;
            }
            case "sprint" -> {
                moveTarget = action.target();
                this.setSprinting(true);
                isSprinting = true;
            }
            case "jump" -> {
                if (this.isOnGround()) {
                    this.jump();
                }
            }
            case "attack" -> attackNearestTarget(world);
            case "mine" -> {
                equipBestTool();
                startMining(action.target(), world);
            }
            case "place" -> placeBlock(action.target(), world);
            case "craft" -> {
                if (action.item() != null) {
                    craftItem(action.item(), world);
                }
            }
            case "equip" -> {
                if (action.item() != null) {
                    equipItem(action.item());
                }
            }
            case "eat" -> autoEat();
            case "use_item" -> {
                this.swingHand(Hand.MAIN_HAND);
                ItemStack held = this.getMainHandStack();
                if (!held.isEmpty()) {
                    this.setCurrentHand(Hand.MAIN_HAND);
                }
            }
            case "look_around" -> startLookAround();
            case "crouch" -> this.setSneaking(!this.isSneaking());
            case "follow_player" -> followNearestPlayer(world);
            case "explore" -> exploreRandom(world);
            case "collect_items" -> autoCollectItems(world);
            case "flee" -> fleeFrom(world);
            case "chat_only" -> {
                // Just chat, no action — message is handled via pollChat()
                if (action.message() != null) {
                    chatMessage(action.message());
                }
            }
            default -> {} // idle — do nothing
        }
    }

    // ======================== COMBAT ========================

    private void attackNearestTarget(ServerWorld world) {
        if (attackCooldown > 0) return;
        attackCooldown = 20;

        LivingEntity target = null;
        double nearestDist = Double.MAX_VALUE;

        for (Entity entity : world.iterateEntities()) {
            if (entity instanceof HostileEntity hostile && entity != this) {
                double dist = this.squaredDistanceTo(hostile);
                if (dist < nearestDist && dist < 16) {
                    nearestDist = dist;
                    target = hostile;
                }
            }
        }

        if (target != null) {
            this.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target.getPos());
            equipBestTool(); // Equip weapon
            this.attack(target);
            this.swingHand(Hand.MAIN_HAND);
        }
    }

    // ======================== PLACING ========================

    private void placeBlock(Vec3d pos, ServerWorld world) {
        ItemStack held = this.getMainHandStack();
        if (held.isEmpty()) return;

        BlockPos targetPos = BlockPos.ofFloored(pos);
        if (!world.getBlockState(targetPos).isAir()) return;

        // Try to place using the item's use logic
        this.swingHand(Hand.MAIN_HAND);
    }

    // ======================== AUTO SYSTEMS ========================

    private void autoCollectItems(ServerWorld world) {
        List<ItemEntity> items = world.getEntitiesByClass(ItemEntity.class,
            this.getBoundingBox().expand(2.0), item -> true);

        for (ItemEntity itemEntity : items) {
            Vec3d toItem = itemEntity.getPos().subtract(this.getPos()).normalize().multiply(0.3);
            itemEntity.setVelocity(toItem.negate());
        }
    }

    private void autoEat() {
        if (this.getHungerManager().getFoodLevel() > 14) return;

        for (int i = 0; i < this.getInventory().size(); i++) {
            ItemStack stack = this.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isFood()) {
                FoodComponent food = stack.getItem().getFoodComponent();
                if (food != null) {
                    if (i < 9) {
                        this.getInventory().selectedSlot = i;
                    } else {
                        // Move to hotbar
                        int hotbarSlot = findEmptyHotbarSlot();
                        this.getInventory().setStack(hotbarSlot, stack.copy());
                        this.getInventory().setStack(i, ItemStack.EMPTY);
                        this.getInventory().selectedSlot = hotbarSlot;
                    }
                    this.setCurrentHand(Hand.MAIN_HAND);
                    break;
                }
            }
        }
    }

    private void startLookAround() {
        lookYaw = this.getYaw() + (float)(Math.random() * 180 - 90);
        lookPitch = (float)(Math.random() * 40 - 20);
        lookTimer = 20;
    }

    private void followNearestPlayer(ServerWorld world) {
        PlayerEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (PlayerEntity player : world.getPlayers()) {
            if (player == this) continue;
            double dist = this.squaredDistanceTo(player);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = player;
            }
        }

        if (nearest != null && nearestDist > 4) {
            moveTarget = nearest.getPos();
            this.setSprinting(nearestDist > 100);
            isSprinting = nearestDist > 100;
        }
    }

    private void exploreRandom(ServerWorld world) {
        double angle = Math.random() * Math.PI * 2;
        double dist = 10 + Math.random() * 20;
        Vec3d explore = this.getPos().add(
            Math.sin(angle) * dist,
            0,
            Math.cos(angle) * dist
        );
        moveTarget = explore;
    }

    private void fleeFrom(ServerWorld world) {
        // Run away from nearest hostile
        LivingEntity threat = null;
        double nearestDist = Double.MAX_VALUE;

        for (Entity entity : world.iterateEntities()) {
            if (entity instanceof HostileEntity hostile && entity != this) {
                double dist = this.squaredDistanceTo(hostile);
                if (dist < nearestDist && dist < 256) {
                    nearestDist = dist;
                    threat = hostile;
                }
            }
        }

        if (threat != null) {
            Vec3d awayDir = this.getPos().subtract(threat.getPos()).normalize().multiply(15);
            moveTarget = this.getPos().add(awayDir);
            isSprinting = true;
            this.setSprinting(true);
        } else {
            // No threat found, just wander
            exploreRandom(world);
        }
    }

    // ======================== OVERRIDES ========================

    @Override
    public boolean isSpectator() {
        return false;
    }

    @Override
    public boolean isCreative() {
        return false;
    }

    public GroqAIBrain getAIBrain() {
        return brain;
    }

    // ======================== CRAFTING RECIPES ========================

    /**
     * Simple hardcoded recipe system for common Minecraft items.
     */
    private static class Ingredient {
        final Item item;
        final int count;

        Ingredient(Item item, int count) {
            this.item = item;
            this.count = count;
        }
    }

    private static class CraftingRecipe {
        final Item resultItem;
        final String resultName;
        final int resultCount;
        final Ingredient[] ingredients;

        CraftingRecipe(Item resultItem, String resultName, int resultCount, Ingredient... ingredients) {
            this.resultItem = resultItem;
            this.resultName = resultName;
            this.resultCount = resultCount;
            this.ingredients = ingredients;
        }

        private static final Map<String, CraftingRecipe> RECIPES = new HashMap<>();

        static {
            // Planks from logs
            RECIPES.put("oak_planks", new CraftingRecipe(Items.OAK_PLANKS, "Oak Planks", 4,
                new Ingredient(Items.OAK_LOG, 1)));
            RECIPES.put("birch_planks", new CraftingRecipe(Items.BIRCH_PLANKS, "Birch Planks", 4,
                new Ingredient(Items.BIRCH_LOG, 1)));
            RECIPES.put("spruce_planks", new CraftingRecipe(Items.SPRUCE_PLANKS, "Spruce Planks", 4,
                new Ingredient(Items.SPRUCE_LOG, 1)));
            RECIPES.put("dark_oak_planks", new CraftingRecipe(Items.DARK_OAK_PLANKS, "Dark Oak Planks", 4,
                new Ingredient(Items.DARK_OAK_LOG, 1)));

            // Sticks from planks
            RECIPES.put("sticks", new CraftingRecipe(Items.STICK, "Sticks", 4,
                new Ingredient(Items.OAK_PLANKS, 2)));
            RECIPES.put("oak_sticks", new CraftingRecipe(Items.STICK, "Sticks", 4,
                new Ingredient(Items.OAK_PLANKS, 2)));

            // Crafting table
            RECIPES.put("crafting_table", new CraftingRecipe(Items.CRAFTING_TABLE, "Crafting Table", 1,
                new Ingredient(Items.OAK_PLANKS, 4)));

            // Wooden tools
            RECIPES.put("wooden_pickaxe", new CraftingRecipe(Items.WOODEN_PICKAXE, "Wooden Pickaxe", 1,
                new Ingredient(Items.OAK_PLANKS, 3),
                new Ingredient(Items.STICK, 2)));
            RECIPES.put("wooden_axe", new CraftingRecipe(Items.WOODEN_AXE, "Wooden Axe", 1,
                new Ingredient(Items.OAK_PLANKS, 3),
                new Ingredient(Items.STICK, 2)));
            RECIPES.put("wooden_sword", new CraftingRecipe(Items.WOODEN_SWORD, "Wooden Sword", 1,
                new Ingredient(Items.OAK_PLANKS, 2),
                new Ingredient(Items.STICK, 1)));
            RECIPES.put("wooden_shovel", new CraftingRecipe(Items.WOODEN_SHOVEL, "Wooden Shovel", 1,
                new Ingredient(Items.OAK_PLANKS, 1),
                new Ingredient(Items.STICK, 2)));
            RECIPES.put("wooden_hoe", new CraftingRecipe(Items.WOODEN_HOE, "Wooden Hoe", 1,
                new Ingredient(Items.OAK_PLANKS, 2),
                new Ingredient(Items.STICK, 2)));

            // Stone tools
            RECIPES.put("stone_pickaxe", new CraftingRecipe(Items.STONE_PICKAXE, "Stone Pickaxe", 1,
                new Ingredient(Items.COBBLESTONE, 3),
                new Ingredient(Items.STICK, 2)));
            RECIPES.put("stone_axe", new CraftingRecipe(Items.STONE_AXE, "Stone Axe", 1,
                new Ingredient(Items.COBBLESTONE, 3),
                new Ingredient(Items.STICK, 2)));
            RECIPES.put("stone_sword", new CraftingRecipe(Items.STONE_SWORD, "Stone Sword", 1,
                new Ingredient(Items.COBBLESTONE, 2),
                new Ingredient(Items.STICK, 1)));
            RECIPES.put("stone_shovel", new CraftingRecipe(Items.STONE_SHOVEL, "Stone Shovel", 1,
                new Ingredient(Items.COBBLESTONE, 1),
                new Ingredient(Items.STICK, 2)));

            // Furnace
            RECIPES.put("furnace", new CraftingRecipe(Items.FURNACE, "Furnace", 1,
                new Ingredient(Items.COBBLESTONE, 8)));

            // Chest
            RECIPES.put("chest", new CraftingRecipe(Items.CHEST, "Chest", 1,
                new Ingredient(Items.OAK_PLANKS, 8)));

            // Torches
            RECIPES.put("torches", new CraftingRecipe(Items.TORCH, "Torches", 4,
                new Ingredient(Items.STICK, 1),
                new Ingredient(Items.COAL, 1)));
        }

        static CraftingRecipe get(String id) {
            return RECIPES.get(id);
        }

        static Set<String> availableRecipes() {
            return RECIPES.keySet();
        }
    }

    // ======================== FAKE NETWORK ========================

    private static class FakeNetworkHandler extends ServerPlayNetworkHandler {

        public FakeNetworkHandler(MinecraftServer server, ServerPlayerEntity player, ClientConnection connection) {
            super(server, connection, player);
        }

        @Override
        public void disconnect(Text reason) {
            // Prevent disconnect — bot should stay online
        }

        @Override
        public boolean isConnectionOpen() {
            return true;
        }

        @Override
        public void sendPacket(Packet<?> packet) {
            // Discard — bot has no real client
        }
    }

    private static class FakeClientConnection extends ClientConnection {

        public FakeClientConnection() {
            super(NetworkSide.CLIENTBOUND);
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public boolean isLocal() {
            return true;
        }

        @Override
        public SocketAddress getAddress() {
            return new InetSocketAddress("localhost", 0);
        }

        @Override
        public void send(Packet<?> packet) {
            // Silently discard
        }

        @Override
        public void send(Packet<?> packet, PacketCallbacks callbacks) {
            // Silently discard both the packet and any callbacks
            if (callbacks != null) {
                try {
                    callbacks.onSuccess();
                } catch (Exception ignored) {}
            }
        }

        @Override
        public void tick() {
            // Prevent the connection from trying to flush the null Netty channel
        }

        @Override
        public void handleDisconnection() {
            // Prevent the connection from being cleaned up
        }
    }
}
