package dev.groqplayer.entity;

import com.mojang.authlib.GameProfile;
import dev.groqplayer.GroqPlayerMod;
import dev.groqplayer.ai.GroqAIBrain;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.FoodComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.PlayerSpawnS2CPacket;
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
import java.util.List;
import java.util.UUID;

public class GroqFakePlayer extends ServerPlayerEntity {

    private final GroqAIBrain brain;
    private int thinkTimer = 0;
    private static final int THINK_INTERVAL = 60; // 3 seconds

    // Movement state
    private Vec3d moveTarget = null;
    private boolean isSprinting = false;
    private int actionCooldown = 0;
    private int lookTimer = 0;
    private float lookYaw = 0;
    private float lookPitch = 0;

    public GroqFakePlayer(MinecraftServer server, ServerWorld world, GameProfile profile, String personality) {
        super(server, world, profile, null);
        this.brain = new GroqAIBrain(profile.getName(), personality);

        // Make it look like a normal player
        this.networkHandler = new FakeNetworkHandler(server, this);
        this.setGameMode(GameMode.SURVIVAL);
        this.setNoGravity(false);
    }

    @Override
    public void tick() {
        super.tick();

        if (this.getWorld().isClient()) return;

        ServerWorld world = (ServerWorld) this.getWorld();

        // Handle think timer
        thinkTimer++;
        if (thinkTimer >= THINK_INTERVAL) {
            thinkTimer = 0;
            brain.think(this, world);
        }

        // Process pending action from AI
        GroqAIBrain.AIAction action = brain.pollAction();
        if (action != null) {
            executeAction(action, world);
        }

        // Process pending chat
        String chat = brain.pollChat();
        if (chat != null && !chat.isBlank()) {
            this.server.getPlayerManager().broadcast(
                Text.literal("<" + this.getName().getString() + "> " + chat),
                false
            );
        }

        // Apply movement
        if (moveTarget != null) {
            moveToward(moveTarget);
            if (this.getPos().squaredDistanceTo(moveTarget) < 1.0) {
                moveTarget = null;
                this.setSprinting(false);
            }
        }

        // Action cooldown
        if (actionCooldown > 0) actionCooldown--;

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

        // Gravity / physics - handled by super.tick()
        // Respawn if dead
        if (this.isDead() || this.getHealth() <= 0) {
            this.setHealth(this.getMaxHealth());
            this.setPos(this.getSpawnPointPosition() != null ?
                Vec3d.ofCenter(this.getSpawnPointPosition()) : Vec3d.ofCenter(world.getSpawnPos()));
            this.server.getPlayerManager().broadcast(
                Text.literal("§e[GroqPlayer] §f" + this.getName().getString() + " respawned."),
                false
            );
        }
    }

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
            case "mine" -> mineBlock(action.target(), world);
            case "place" -> placeBlock(action.target(), world);
            case "use_item" -> {
                this.swingHand(Hand.MAIN_HAND);
                this.useItem(Hand.MAIN_HAND);
            }
            case "eat" -> autoEat();
            case "look_around" -> startLookAround();
            case "crouch" -> this.setSneaking(!this.isSneaking());
            case "follow_player" -> followNearestPlayer(world);
            case "explore" -> exploreRandom(world);
            case "collect_items" -> autoCollectItems(world);
            default -> {} // idle - do nothing
        }
    }

    private void moveToward(Vec3d target) {
        Vec3d pos = this.getPos();
        Vec3d diff = target.subtract(pos);
        double dist = diff.horizontalLength();

        if (dist < 0.5) return;

        // Calculate yaw to face target
        float targetYaw = (float) (Math.toDegrees(Math.atan2(-diff.x, diff.z)));
        this.setYaw(targetYaw);

        // Calculate velocity
        double speed = isSprinting ? 0.2 : 0.15;
        Vec3d normalized = new Vec3d(diff.x / dist, 0, diff.z / dist);
        this.setVelocity(normalized.multiply(speed).add(0, this.getVelocity().y, 0));

        // Jump over obstacles
        BlockPos frontPos = BlockPos.ofFloored(pos.add(normalized.multiply(0.6)));
        if (!this.getWorld().getBlockState(frontPos).isAir() && this.isOnGround()) {
            this.jump();
        }
    }

    private void attackNearestTarget(ServerWorld world) {
        if (actionCooldown > 0) return;
        actionCooldown = 20;

        // Find nearest hostile mob first
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
            this.lookAt(net.minecraft.entity.EntityAnchorArgument.EntityAnchor.EYES, target.getPos());
            this.attack(target);
            this.swingHand(Hand.MAIN_HAND);
        }
    }

    private void mineBlock(Vec3d pos, ServerWorld world) {
        if (actionCooldown > 0) return;
        actionCooldown = 20;

        BlockPos blockPos = BlockPos.ofFloored(pos);
        BlockState state = world.getBlockState(blockPos);
        if (!state.isAir() && state.getBlock() != net.minecraft.block.Blocks.BEDROCK) {
            world.breakBlock(blockPos, true, this);
            this.swingHand(Hand.MAIN_HAND);
        }
    }

    private void placeBlock(Vec3d pos, ServerWorld world) {
        if (actionCooldown > 0) return;
        actionCooldown = 10;

        ItemStack held = this.getMainHandStack();
        if (held.isEmpty()) return;

        BlockPos targetPos = BlockPos.ofFloored(pos);
        if (!world.getBlockState(targetPos).isAir()) return;

        // Simple block placement
        this.swingHand(Hand.MAIN_HAND);
    }

    private void autoCollectItems(ServerWorld world) {
        List<ItemEntity> items = world.getEntitiesByClass(ItemEntity.class,
            this.getBoundingBox().expand(2.0), item -> true);

        for (ItemEntity itemEntity : items) {
            Vec3d toItem = itemEntity.getPos().subtract(this.getPos()).normalize().multiply(0.3);
            itemEntity.setVelocity(toItem.negate());
            // Actual collection handled by vanilla pickup logic
        }
    }

    private void autoEat() {
        if (this.getHungerManager().getFoodLevel() > 16) return;

        for (int i = 0; i < this.getInventory().size(); i++) {
            ItemStack stack = this.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isFood()) {
                FoodComponent food = stack.getItem().getFoodComponent();
                if (food != null) {
                    this.getInventory().selectedSlot = i < 9 ? i : this.getInventory().selectedSlot;
                    this.useItem(Hand.MAIN_HAND);
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

    // =========================================================
    // Fake network handler - required for ServerPlayerEntity
    // =========================================================
    private static class FakeNetworkHandler extends ServerPlayNetworkHandler {

        public FakeNetworkHandler(MinecraftServer server, ServerPlayerEntity player) {
            super(server, new FakeClientConnection(), player);
        }

        @Override
        public void disconnect(Text reason) {
            // Prevent disconnect
        }

        @Override
        public boolean isConnectionOpen() {
            return true;
        }

        @Override
        public void sendPacket(net.minecraft.network.packet.Packet<?> packet) {
            // Discard packets — bot doesn't have a real client
        }
    }

    private static class FakeClientConnection extends net.minecraft.network.ClientConnection {

        public FakeClientConnection() {
            super(net.minecraft.network.NetworkSide.CLIENTBOUND);
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public SocketAddress getAddress() {
            return new InetSocketAddress("localhost", 0);
        }

        @Override
        public void sendImmediately(net.minecraft.network.packet.Packet<?> packet, io.netty.util.concurrent.GenericFutureListener<?> listener, net.minecraft.network.PacketCallbacks callbacks) {
            // Discard
        }
    }
}
