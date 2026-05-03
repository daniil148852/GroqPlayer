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
import net.minecraft.item.ItemStack;
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

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.DefaultChannelConfig;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;

public class GroqFakePlayer extends ServerPlayerEntity {

    private final GroqAIBrain brain;
    private final FakeClientConnection fakeConnection;
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
        super(server, world, profile);
        this.brain = new GroqAIBrain(profile.getName(), personality);

        // Create fake connection that safely discards all packets
        this.fakeConnection = new FakeClientConnection();

        // Create a fake network handler — note: onPlayerConnect will replace this
        // with a real ServerPlayNetworkHandler, but our FakeClientConnection
        // will still be used for packet sending
        this.networkHandler = new FakeNetworkHandler(server, this, fakeConnection);

        // Set game mode via ServerPlayerEntity method
        this.changeGameMode(GameMode.SURVIVAL);
        this.setNoGravity(false);
    }

    /**
     * Returns the fake ClientConnection used by this AI player.
     * Needed by GroqPlayerManager to call onPlayerConnect.
     */
    public FakeClientConnection getFakeConnection() {
        return fakeConnection;
    }

    @Override
    public void tick() {
        // Prevent super.tick() from crashing — the real ServerPlayNetworkHandler
        // created by onPlayerConnect may try to tick the connection
        try {
            super.tick();
        } catch (Exception e) {
            // Silently catch network-related errors from the handler tick
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
                ItemStack held = this.getMainHandStack();
                if (!held.isEmpty()) {
                    this.setCurrentHand(Hand.MAIN_HAND);
                }
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

        float targetYaw = (float) (Math.toDegrees(Math.atan2(-diff.x, diff.z)));
        this.setYaw(targetYaw);

        double speed = isSprinting ? 0.2 : 0.15;
        Vec3d normalized = new Vec3d(diff.x / dist, 0, diff.z / dist);
        this.setVelocity(normalized.multiply(speed).add(0, this.getVelocity().y, 0));

        BlockPos frontPos = BlockPos.ofFloored(pos.add(normalized.multiply(0.6)));
        if (!this.getWorld().getBlockState(frontPos).isAir() && this.isOnGround()) {
            this.jump();
        }
    }

    private void attackNearestTarget(ServerWorld world) {
        if (actionCooldown > 0) return;
        actionCooldown = 20;

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

        this.swingHand(Hand.MAIN_HAND);
    }

    private void autoCollectItems(ServerWorld world) {
        List<ItemEntity> items = world.getEntitiesByClass(ItemEntity.class,
            this.getBoundingBox().expand(2.0), item -> true);

        for (ItemEntity itemEntity : items) {
            Vec3d toItem = itemEntity.getPos().subtract(this.getPos()).normalize().multiply(0.3);
            itemEntity.setVelocity(toItem.negate());
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
    // Fake network handler — initially set, then replaced by
    // onPlayerConnect with a real handler using our fake connection
    // =========================================================
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

    // =========================================================
    // Fake ClientConnection — safely discards all packets without
    // touching the Netty channel (which doesn't exist).
    //
    // Key: we override BOTH send() variants because the server
    // calls the 2-arg version internally. The original 2-arg
    // send() tries to write to a null Netty Channel → NPE.
    // =========================================================
    private static class FakeClientConnection extends ClientConnection {

        public FakeClientConnection() {
            super(NetworkSide.CLIENTBOUND);
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
        public void send(Packet<?> packet) {
            // Discard — bot has no real client
        }

        /**
         * Override the 2-arg send() that the server calls internally
         * (e.g. from onPlayerConnect). Without this, the default
         * implementation tries to write to a null Netty Channel.
         */
        @Override
        public void send(Packet<?> packet, GenericFutureListener<? extends Future<? super Void>> listener) {
            // Discard packet, but notify the listener of success so
            // the server doesn't think the send failed
            if (listener != null) {
                try {
                    // Create a placeholder promise and succeed it immediately
                    io.netty.channel.DefaultChannelPromise promise =
                        new io.netty.channel.DefaultChannelPromise(null);
                    promise.trySuccess();
                    listener.operationComplete(promise);
                } catch (Exception e) {
                    // Ignore listener errors
                }
            }
        }

        @Override
        public void handleDisconnection() {
            // Prevent the connection from being cleaned up
        }
    }
}
