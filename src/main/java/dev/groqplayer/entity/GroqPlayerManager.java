package dev.groqplayer.entity;

import com.mojang.authlib.GameProfile;
import dev.groqplayer.GroqPlayerMod;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class GroqPlayerManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("groqplayer-manager");
    private final Map<String, GroqFakePlayer> activePlayers = new HashMap<>();

    /**
     * Spawns a new AI player at the given position.
     *
     * The spawn process uses the server's standard onPlayerConnect flow
     * with a FakeClientConnection that discards all outgoing packets.
     * We wrap the connection calls in try-catch because the fake
     * connection cannot fully emulate a real Netty channel — some
     * internal server methods may throw NPE when writing packets.
     * These errors are non-fatal: the player still gets added to the
     * world and functions correctly.
     */
    public boolean spawnPlayer(MinecraftServer server, String name, String personality, Vec3d pos) {
        if (activePlayers.containsKey(name)) {
            LOGGER.warn("[GroqPlayer] Player '{}' already exists!", name);
            return false;
        }

        try {
            ServerWorld world = server.getOverworld();

            // Create unique UUID based on name (consistent across restarts)
            UUID uuid = UUID.nameUUIDFromBytes(("GroqPlayer:" + name).getBytes());
            GameProfile profile = new GameProfile(uuid, name);

            GroqFakePlayer fakePlayer = new GroqFakePlayer(server, world, profile, personality);
            fakePlayer.setPos(pos.x, pos.y, pos.z);
            fakePlayer.setHealth(20.0f);
            fakePlayer.getHungerManager().setFoodLevel(20);

            // Connect the fake player using the standard server flow.
            // onPlayerConnect expects (ClientConnection, ServerPlayerEntity) and will:
            //   1. Create a real ServerPlayNetworkHandler for the player
            //   2. Send initial game state packets (which our FakeClientConnection discards)
            //   3. Add the player to the player list
            //
            // Some internal packet sends may fail with NPE because our fake connection
            // has no real Netty channel. We catch those errors below.
            ClientConnection fakeConnection = fakePlayer.getFakeConnection();

            try {
                server.getPlayerManager().onPlayerConnect(fakeConnection, fakePlayer);
            } catch (NullPointerException e) {
                // Expected: internal packet send tries to access null Netty channel.
                // The player is still added to the world — just some sync packets were lost.
                LOGGER.debug("[GroqPlayer] Ignored NPE during player connect (expected with fake connection): {}", e.getMessage());
            } catch (Exception e) {
                // Other errors during connection — log but continue
                LOGGER.warn("[GroqPlayer] Non-critical error during player connect: {}", e.getMessage());
            }

            // Notify the world that the player has connected
            try {
                world.onPlayerConnected(fakePlayer);
            } catch (Exception e) {
                LOGGER.debug("[GroqPlayer] Ignored error in onPlayerConnected: {}", e.getMessage());
            }

            // Broadcast join message
            server.getPlayerManager().broadcast(
                Text.literal("§a" + name + " §7joined the game (AI Player)"),
                false
            );

            activePlayers.put(name, fakePlayer);
            LOGGER.info("[GroqPlayer] Spawned AI player '{}' at {}", name, pos);
            return true;

        } catch (Exception e) {
            LOGGER.error("[GroqPlayer] Failed to spawn AI player '{}': {}", name, e.getMessage(), e);
            // Clean up partial state
            activePlayers.remove(name);
            return false;
        }
    }

    /**
     * Removes an AI player by name.
     */
    public boolean removePlayer(MinecraftServer server, String name) {
        GroqFakePlayer player = activePlayers.remove(name);
        if (player == null) {
            return false;
        }

        try {
            server.getPlayerManager().remove(player);
        } catch (Exception e) {
            LOGGER.warn("[GroqPlayer] Error removing player '{}': {}", name, e.getMessage());
        }

        server.getPlayerManager().broadcast(
            Text.literal("§c" + name + " §7left the game (AI Player)"),
            false
        );

        LOGGER.info("[GroqPlayer] Removed AI player '{}'", name);
        return true;
    }

    /**
     * Removes all AI players.
     */
    public void removeAllPlayers(MinecraftServer server) {
        List<String> names = new ArrayList<>(activePlayers.keySet());
        for (String name : names) {
            removePlayer(server, name);
        }
    }

    /**
     * Called every server tick.
     */
    public void tick(MinecraftServer server) {
        for (GroqFakePlayer player : activePlayers.values()) {
            try {
                player.tick();
            } catch (Exception e) {
                LOGGER.error("[GroqPlayer] Error ticking AI player '{}': {}", player.getName().getString(), e.getMessage());
            }
        }
    }

    /**
     * Called when a player sends a chat message.
     */
    public void onPlayerChat(String senderName, String message) {
        for (GroqFakePlayer aiPlayer : activePlayers.values()) {
            try {
                aiPlayer.getAIBrain().onChatReceived(senderName, message, aiPlayer);
            } catch (Exception e) {
                LOGGER.error("[GroqPlayer] Error passing chat to AI player: {}", e.getMessage());
            }
        }
    }

    public Collection<GroqFakePlayer> getActivePlayers() {
        return Collections.unmodifiableCollection(activePlayers.values());
    }

    public boolean hasPlayer(String name) {
        return activePlayers.containsKey(name);
    }

    public int getCount() {
        return activePlayers.size();
    }
}
