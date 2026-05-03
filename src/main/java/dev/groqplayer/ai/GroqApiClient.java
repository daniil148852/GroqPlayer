package dev.groqplayer.ai;

import com.google.gson.*;
import dev.groqplayer.config.GroqConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;

public class GroqApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("groqplayer-api");
    private static final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final Gson GSON = new GsonBuilder().create();
    private static final int MAX_TOKENS = 512;
    private static final int MAX_RETRIES = 1;

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "GroqPlayer-API");
        t.setDaemon(true);
        return t;
    });

    /**
     * Sends a request to Groq API asynchronously.
     * @param messages List of message objects [{role, content}]
     * @param callback Called with the response text, or null on error
     */
    public static void sendAsync(List<ChatMessage> messages, java.util.function.Consumer<String> callback) {
        String apiKey = GroqConfig.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            LOGGER.warn("[GroqPlayer] No API key set! Use /groqplayer setkey <key>");
            callback.accept(null);
            return;
        }

        EXECUTOR.submit(() -> {
            try {
                String response = sendRequestWithRetry(apiKey, messages);
                callback.accept(response);
            } catch (Exception e) {
                LOGGER.error("[GroqPlayer] API error after retries: {}", e.getMessage());
                if (GroqConfig.isDebugMode()) {
                    e.printStackTrace();
                }
                callback.accept(null);
            }
        });
    }

    /**
     * Sends the request with one retry on failure.
     */
    private static String sendRequestWithRetry(String apiKey, List<ChatMessage> messages) throws IOException {
        IOException lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return sendRequest(apiKey, messages);
            } catch (IOException e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    LOGGER.warn("[GroqPlayer] API request failed (attempt {}), retrying...: {}", attempt + 1, e.getMessage());
                    try {
                        Thread.sleep(1000); // Wait 1 second before retry
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw lastException;
    }

    private static String sendRequest(String apiKey, List<ChatMessage> messages) throws IOException {
        URL url = new URL(GROQ_API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        // Build request body
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", GroqConfig.getModel());
        requestBody.addProperty("max_tokens", MAX_TOKENS);
        requestBody.addProperty("temperature", 0.8f);

        JsonArray messagesArray = new JsonArray();
        for (ChatMessage msg : messages) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.role());
            msgObj.addProperty("content", msg.content());
            messagesArray.add(msgObj);
        }
        requestBody.add("messages", messagesArray);

        String jsonInput = GSON.toJson(requestBody);

        if (GroqConfig.isDebugMode()) {
            LOGGER.info("[GroqPlayer] Request: {}", jsonInput);
        }

        // Write request
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonInput.getBytes(StandardCharsets.UTF_8));
        }

        // Read response
        int responseCode = conn.getResponseCode();
        InputStream inputStream = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }

        String responseStr = sb.toString();

        if (GroqConfig.isDebugMode()) {
            LOGGER.info("[GroqPlayer] Response ({}): {}", responseCode, responseStr);
        }

        if (responseCode != 200) {
            throw new IOException("HTTP " + responseCode + ": " + responseStr);
        }

        // Parse response
        JsonObject responseObj = GSON.fromJson(responseStr, JsonObject.class);
        JsonArray choices = responseObj.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IOException("Empty choices in response");
        }

        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        JsonObject message = firstChoice.getAsJsonObject("message");
        return message.get("content").getAsString().trim();
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }

    public record ChatMessage(String role, String content) {}
}
