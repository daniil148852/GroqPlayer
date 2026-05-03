package dev.groqplayer;

import dev.groqplayer.command.GroqPlayerCommand;
import dev.groqplayer.config.GroqConfig;
import dev.groqplayer.entity.GroqPlayerManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GroqPlayerMod implements ModInitializer {

    public static final String MOD_ID = "groqplayer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static GroqPlayerManager playerManager;

    @Override
    public void onInitialize() {
        LOGGER.info("[GroqPlayer] Initializing GroqPlayer Mod...");

        // Load config
        GroqConfig.load();

        // Initialize player manager
        playerManager = new GroqPlayerManager();

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            GroqPlayerCommand.register(dispatcher);
        });

        // Tick event for AI logic
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            playerManager.tick(server);
        });

        // Cleanup on server stop
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("[GroqPlayer] Server stopping, removing all AI players...");
            playerManager.removeAllPlayers(server);
        });

        LOGGER.info("[GroqPlayer] GroqPlayer Mod initialized!");
    }

    public static GroqPlayerManager getPlayerManager() {
        return playerManager;
    }
}
