package dev.groqplayer;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GroqPlayerClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("groqplayer-client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[GroqPlayer] Client initialized.");
    }
}
