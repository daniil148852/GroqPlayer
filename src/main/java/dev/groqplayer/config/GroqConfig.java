package dev.groqplayer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class GroqConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("groqplayer-config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("groqplayer.json");

    private static GroqConfigData data = new GroqConfigData();

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                data = GSON.fromJson(reader, GroqConfigData.class);
                if (data == null) data = new GroqConfigData();
                LOGGER.info("[GroqPlayer] Config loaded from {}", CONFIG_PATH);
            } catch (IOException e) {
                LOGGER.error("[GroqPlayer] Failed to load config", e);
                data = new GroqConfigData();
            }
        } else {
            LOGGER.info("[GroqPlayer] No config found, creating default at {}", CONFIG_PATH);
            save();
        }
    }

    public static void save() {
        try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
            GSON.toJson(data, writer);
        } catch (IOException e) {
            LOGGER.error("[GroqPlayer] Failed to save config", e);
        }
    }

    public static String getApiKey() {
        return data.apiKey;
    }

    public static void setApiKey(String key) {
        data.apiKey = key;
        save();
    }

    public static String getModel() {
        return data.model;
    }

    public static void setModel(String model) {
        data.model = model;
        save();
    }

    public static int getThinkIntervalTicks() {
        return data.thinkIntervalTicks;
    }

    public static int getMaxContextMessages() {
        return data.maxContextMessages;
    }

    public static boolean isDebugMode() {
        return data.debugMode;
    }

    public static double getChatDistance() {
        return data.chatResponseDistance;
    }

    public static class GroqConfigData {
        public String apiKey = "";
        public String model = "llama3-70b-8192";
        public int thinkIntervalTicks = 60; // Think every 3 seconds (20 ticks/sec)
        public int maxContextMessages = 20;
        public boolean debugMode = false;
        public double chatResponseDistance = 32.0;
    }
}
