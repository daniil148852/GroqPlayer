package dev.groqplayer.ai;

import dev.groqplayer.config.GroqConfig;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class GroqAIBrain {

    private static final Logger LOGGER = LoggerFactory.getLogger("groqplayer-brain");

    private final String botName;
    private final String personality;
    private final LinkedList<GroqApiClient.ChatMessage> conversationHistory = new LinkedList<>();
    private final AtomicBoolean isThinking = new AtomicBoolean(false);

    // Pending action from last AI response
    private volatile AIAction pendingAction = null;
    private volatile String pendingChat = null;

    public GroqAIBrain(String botName, String personality) {
        this.botName = botName;
        this.personality = personality;
    }

    /**
     * Called periodically to make the AI think about what to do.
     */
    public void think(ServerPlayerEntity aiPlayer, ServerWorld world) {
        if (isThinking.get()) return;

        // Build world state context
        String worldContext = buildWorldContext(aiPlayer, world);
        String userMessage = "Current game state:\n" + worldContext + "\n\nWhat do you do next? Respond with a JSON action.";

        addToHistory("user", userMessage);

        isThinking.set(true);

        List<GroqApiClient.ChatMessage> messages = buildMessages();

        GroqApiClient.sendAsync(messages, response -> {
            if (response != null) {
                parseAndSetAction(response);
            }
            isThinking.set(false);
        });
    }

    /**
     * Called when a nearby player says something in chat.
     */
    public void onChatReceived(String senderName, String message, ServerPlayerEntity aiPlayer) {
        if (senderName.equals(botName)) return;

        addToHistory("user", senderName + " said: \"" + message + "\"");

        // Only respond if directly addressed or randomly
        boolean addressed = message.toLowerCase().contains(botName.toLowerCase());
        boolean randomChance = Math.random() < 0.3;

        if (!addressed && !randomChance) return;

        if (isThinking.get()) return;
        isThinking.set(true);

        String worldContext = buildWorldContext(aiPlayer, (ServerWorld) aiPlayer.getWorld());
        addToHistory("user", "Current state: " + worldContext);

        List<GroqApiClient.ChatMessage> messages = buildMessages();

        GroqApiClient.sendAsync(messages, response -> {
            if (response != null) {
                parseAndSetAction(response);
            }
            isThinking.set(false);
        });
    }

    private String buildWorldContext(ServerPlayerEntity player, ServerWorld world) {
        StringBuilder sb = new StringBuilder();
        BlockPos pos = player.getBlockPos();

        sb.append("Position: ").append(pos.getX()).append(",").append(pos.getY()).append(",").append(pos.getZ()).append("\n");
        sb.append("Health: ").append(String.format("%.1f", player.getHealth())).append("/").append(player.getMaxHealth()).append("\n");
        sb.append("Hunger: ").append(player.getHungerManager().getFoodLevel()).append("/20\n");
        sb.append("Time: ").append(world.getTimeOfDay() % 24000 < 13000 ? "Day" : "Night").append("\n");
        sb.append("Weather: ").append(world.isRaining() ? "Raining" : "Clear").append("\n");

        // Inventory summary
        ItemStack mainHand = player.getMainHandStack();
        sb.append("Holding: ").append(mainHand.isEmpty() ? "nothing" : mainHand.getItem().toString()).append("\n");

        // Nearby players
        List<String> nearbyPlayers = new ArrayList<>();
        for (PlayerEntity nearby : world.getPlayers()) {
            if (!nearby.getName().getString().equals(botName) && nearby.squaredDistanceTo(player) < 256) {
                nearbyPlayers.add(nearby.getName().getString());
            }
        }
        if (!nearbyPlayers.isEmpty()) {
            sb.append("Nearby players: ").append(String.join(", ", nearbyPlayers)).append("\n");
        }

        // Block below
        BlockPos below = pos.down();
        sb.append("Standing on: ").append(world.getBlockState(below).getBlock().toString()).append("\n");

        // Danger check
        boolean onGround = player.isOnGround();
        boolean inWater = player.isTouchingWater();
        sb.append("On ground: ").append(onGround).append(", In water: ").append(inWater).append("\n");

        return sb.toString();
    }

    private void parseAndSetAction(String response) {
        addToHistory("assistant", response);

        // Try to extract JSON action
        try {
            int jsonStart = response.indexOf('{');
            int jsonEnd = response.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String json = response.substring(jsonStart, jsonEnd + 1);
                com.google.gson.JsonObject obj = new com.google.gson.Gson().fromJson(json, com.google.gson.JsonObject.class);

                String actionType = obj.has("action") ? obj.get("action").getAsString() : "idle";
                String chat = obj.has("chat") ? obj.get("chat").getAsString() : null;
                double targetX = obj.has("x") ? obj.get("x").getAsDouble() : 0;
                double targetY = obj.has("y") ? obj.get("y").getAsDouble() : 0;
                double targetZ = obj.has("z") ? obj.get("z").getAsDouble() : 0;

                pendingAction = new AIAction(actionType, new Vec3d(targetX, targetY, targetZ));
                pendingChat = chat;
            } else {
                // No JSON found, treat entire response as chat
                pendingChat = extractChatText(response);
                pendingAction = new AIAction("idle", Vec3d.ZERO);
            }
        } catch (Exception e) {
            LOGGER.warn("[GroqPlayer] Could not parse AI response as JSON, using as chat: {}", response);
            pendingChat = extractChatText(response);
            pendingAction = new AIAction("idle", Vec3d.ZERO);
        }
    }

    private String extractChatText(String response) {
        // Remove JSON-like parts, return clean text for chat (limit length)
        String clean = response.replaceAll("[{}\"\\[\\]]", "").trim();
        if (clean.length() > 100) clean = clean.substring(0, 100);
        return clean.isEmpty() ? null : clean;
    }

    private List<GroqApiClient.ChatMessage> buildMessages() {
        List<GroqApiClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new GroqApiClient.ChatMessage("system", buildSystemPrompt()));

        // Add history (limited)
        int maxMessages = GroqConfig.getMaxContextMessages();
        List<GroqApiClient.ChatMessage> historyList = new ArrayList<>(conversationHistory);
        int start = Math.max(0, historyList.size() - maxMessages);
        messages.addAll(historyList.subList(start, historyList.size()));

        return messages;
    }

    private String buildSystemPrompt() {
        return """
                You are %s, an AI player in Minecraft 1.20.1.
                Personality: %s
                
                You are a REAL player - you mine, build, craft, fight, explore, and talk.
                You must respond ONLY with a JSON object in this format:
                {
                  "action": "<action_type>",
                  "chat": "<optional message to say in chat, null if silent>",
                  "x": <optional target X coordinate>,
                  "y": <optional target Y coordinate>,
                  "z": <optional target Z coordinate>
                }
                
                Available action types:
                - "idle" - stand still
                - "walk" - walk toward x,y,z
                - "sprint" - sprint toward x,y,z
                - "jump" - jump
                - "attack" - attack nearest mob/player
                - "mine" - mine block at x,y,z
                - "place" - place held block at x,y,z
                - "use_item" - use held item
                - "eat" - eat food if hungry
                - "look_around" - look around curiously
                - "crouch" - crouch/sneak
                - "follow_player" - follow nearest player
                - "explore" - explore in a random direction
                - "collect_items" - collect nearby dropped items
                
                Be a natural player. React to your environment. Survive. Have fun.
                Keep chat messages short (under 80 chars) and natural. Don't always chat.
                Prioritize survival: eat when hungry, flee dangerous situations at night.
                """.formatted(botName, personality);
    }

    private void addToHistory(String role, String content) {
        conversationHistory.add(new GroqApiClient.ChatMessage(role, content));
        // Keep history bounded
        while (conversationHistory.size() > GroqConfig.getMaxContextMessages() * 2) {
            conversationHistory.removeFirst();
        }
    }

    public AIAction pollAction() {
        AIAction action = pendingAction;
        pendingAction = null;
        return action;
    }

    public String pollChat() {
        String chat = pendingChat;
        pendingChat = null;
        return chat;
    }

    public boolean isThinking() {
        return isThinking.get();
    }

    public void clearHistory() {
        conversationHistory.clear();
    }

    public record AIAction(String type, Vec3d target) {}
}
