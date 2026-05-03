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
            ClientConnection fakeConnection = fakePlayer.getFakeConnection();

            try {
                server.getPlayerManager().onPlayerConnect(fakeConnection, fakePlayer);
            } catch (NullPointerException e) {
                // Expected: internal packet send tries to access null Netty channel.
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
     * Always triggers AI response when the bot's name is mentioned.
     */
    public void onPlayerChat(String senderName, String message) {
        for (GroqFakePlayer aiPlayer : activePlayers.values()) {
            try {
                String botName = aiPlayer.getName().getString();
                // Always respond when name is mentioned (not 30% random)
                boolean addressed = message.toLowerCase().contains(botName.toLowerCase());
                if (addressed) {
                    aiPlayer.getAIBrain().onChatReceived(senderName, message, aiPlayer);
                }
            } catch (Exception e) {
                LOGGER.error("[GroqPlayer] Error passing chat to AI player: {}", e.getMessage());
            }
        }
    }

    /**
     * Get a specific AI player by name.
     * @return The GroqFakePlayer, or null if not found
     */
    public GroqFakePlayer getPlayer(String name) {
        return activePlayers.get(name);
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
