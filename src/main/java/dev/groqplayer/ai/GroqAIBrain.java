package dev.groqplayer.ai;

import dev.groqplayer.config.GroqConfig;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class GroqAIBrain {

    private static final Logger LOGGER = LoggerFactory.getLogger("groqplayer-brain");

    private final String botName;
    private final String personality;
    private final LinkedList<GroqApiClient.ChatMessage> conversationHistory = new LinkedList<>();
    private final AtomicBoolean isThinking = new AtomicBoolean(false);

    // Pending actions and chat from last AI response
    private final LinkedList<AIAction> pendingActions = new LinkedList<>();
    private volatile String pendingChat = null;

    // ======================== AIAction Record ========================

    public record AIAction(String type, Vec3d target, String item, String message) {
        public static AIAction of(String type) { return new AIAction(type, Vec3d.ZERO, null, null); }
        public static AIAction of(String type, Vec3d target) { return new AIAction(type, target, null, null); }
        public static AIAction of(String type, String item) { return new AIAction(type, Vec3d.ZERO, item, null); }
    }

    public GroqAIBrain(String botName, String personality) {
        this.botName = botName;
        this.personality = personality;
    }

    /**
     * Called periodically to make the AI think about what to do.
     */
    public void think(ServerPlayerEntity aiPlayer, ServerWorld world) {
        if (isThinking.get()) return;

        // Build rich world state context
        String worldContext = buildWorldContext(aiPlayer, world);
        String userMessage = "Current game state:\n" + worldContext +
            "\n\nDecide your next actions. You can plan multiple steps (up to 5)." +
            "\nRespond ONLY with the JSON format specified.";

        addToHistory("user", userMessage);

        isThinking.set(true);

        List<GroqApiClient.ChatMessage> messages = buildMessages();

        GroqApiClient.sendAsync(messages, response -> {
            if (response != null) {
                parseAndSetActions(response);
            }
            isThinking.set(false);
        });
    }

    /**
     * Called when a nearby player says something in chat.
     * Always responds when name is mentioned, otherwise responds based on proximity.
     */
    public void onChatReceived(String senderName, String message, ServerPlayerEntity aiPlayer) {
        if (senderName.equals(botName)) return;

        addToHistory("user", senderName + " said: \"" + message + "\"");

        // Always respond if directly addressed
        boolean addressed = message.toLowerCase().contains(botName.toLowerCase());
        // Also respond if message is directed at us (e.g. "hey bot" or short messages nearby)
        if (!addressed) return;

        if (isThinking.get()) return;
        isThinking.set(true);

        String worldContext = buildWorldContext(aiPlayer, (ServerWorld) aiPlayer.getWorld());
        addToHistory("user", "Current state: " + worldContext + "\n\n" +
            senderName + " is talking to you. Respond with actions and chat.");

        List<GroqApiClient.ChatMessage> messages = buildMessages();

        GroqApiClient.sendAsync(messages, response -> {
            if (response != null) {
                parseAndSetActions(response);
            }
            isThinking.set(false);
        });
    }

    // ======================== Rich World Context ========================

    private String buildWorldContext(ServerPlayerEntity player, ServerWorld world) {
        StringBuilder sb = new StringBuilder();
        BlockPos pos = player.getBlockPos();

        sb.append("=== POSITION ===\n");
        sb.append("Position: ").append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ()).append("\n");
        sb.append("Dimension: ").append(world.getRegistryKey().getValue().toString()).append("\n");

        sb.append("\n=== VITALS ===\n");
        sb.append("Health: ").append(String.format("%.1f", player.getHealth())).append("/").append(player.getMaxHealth()).append("\n");
        sb.append("Hunger: ").append(player.getHungerManager().getFoodLevel()).append("/20\n");
        sb.append("On ground: ").append(player.isOnGround()).append("\n");
        sb.append("In water: ").append(player.isTouchingWater()).append("\n");

        sb.append("\n=== TIME & WEATHER ===\n");
        long timeOfDay = world.getTimeOfDay() % 24000;
        sb.append("Time: ").append(timeOfDay < 13000 ? "Day" : "Night").append(" (").append(timeOfDay).append(")\n");
        sb.append("Weather: ").append(world.isRaining() ? "Raining" : "Clear").append("\n");

        // Full inventory
        sb.append("\n=== INVENTORY ===\n");
        ItemStack mainHand = player.getMainHandStack();
        ItemStack offHand = player.getOffHandStack();
        sb.append("Main hand: ").append(mainHand.isEmpty() ? "nothing" : mainHand.getName().getString() + " x" + mainHand.getCount()).append("\n");
        sb.append("Off hand: ").append(offHand.isEmpty() ? "nothing" : offHand.getName().getString() + " x" + offHand.getCount()).append("\n");

        Map<String, Integer> inventoryMap = new LinkedHashMap<>();
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                String name = stack.getName().getString();
                inventoryMap.merge(name, stack.getCount(), Integer::sum);
            }
        }
        if (inventoryMap.isEmpty()) {
            sb.append("Inventory: empty\n");
        } else {
            sb.append("Inventory:\n");
            for (Map.Entry<String, Integer> entry : inventoryMap.entrySet()) {
                sb.append("  - ").append(entry.getKey()).append(" x").append(entry.getValue()).append("\n");
            }
        }

        // Nearby blocks scan (5x5x5 around player)
        sb.append("\n=== NEARBY BLOCKS ===\n");
        Map<String, Integer> blockCounts = new LinkedHashMap<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos checkPos = pos.add(dx, dy, dz);
                    BlockState state = world.getBlockState(checkPos);
                    if (!state.isAir()) {
                        String blockName = state.getBlock().getName().getString();
                        blockCounts.merge(blockName, 1, Integer::sum);
                    }
                }
            }
        }
        // Show top 10 most common nearby blocks
        blockCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(10)
            .forEach(e -> sb.append("  ").append(e.getKey()).append(" (").append(e.getValue()).append(")\n"));

        // Block below
        BlockPos below = pos.down();
        sb.append("Standing on: ").append(world.getBlockState(below).getBlock().getName().getString()).append("\n");

        // Specific block positions nearby (for mining targets)
        sb.append("\n=== MINEABLE BLOCKS NEARBY ===\n");
        List<String> mineablePositions = new ArrayList<>();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -3; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    BlockPos checkPos = pos.add(dx, dy, dz);
                    BlockState state = world.getBlockState(checkPos);
                    if (!state.isAir() && isMineable(state)) {
                        String blockName = state.getBlock().getName().getString();
                        mineablePositions.add(blockName + " at " + checkPos.getX() + "," + checkPos.getY() + "," + checkPos.getZ());
                        if (mineablePositions.size() >= 8) break;
                    }
                }
                if (mineablePositions.size() >= 8) break;
            }
            if (mineablePositions.size() >= 8) break;
        }
        if (mineablePositions.isEmpty()) {
            sb.append("  None nearby\n");
        } else {
            for (String s : mineablePositions) {
                sb.append("  ").append(s).append("\n");
            }
        }

        // Nearby entities
        sb.append("\n=== NEARBY ENTITIES ===\n");
        List<Entity> nearbyEntities = world.getEntitiesByClass(Entity.class,
            player.getBoundingBox().expand(16), e -> e != player);
        Map<String, Integer> entityCounts = new LinkedHashMap<>();
        for (Entity entity : nearbyEntities) {
            String name = entity.getName().getString();
            entityCounts.merge(name, 1, Integer::sum);
        }
        if (entityCounts.isEmpty()) {
            sb.append("  None nearby\n");
        } else {
            for (Map.Entry<String, Integer> entry : entityCounts.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(" x").append(entry.getValue()).append("\n");
            }
        }

        // Nearby players with positions
        sb.append("\n=== NEARBY PLAYERS ===\n");
        boolean foundPlayers = false;
        for (PlayerEntity nearby : world.getPlayers()) {
            if (!nearby.getName().getString().equals(botName) && nearby.squaredDistanceTo(player) < 1024) {
                sb.append("  ").append(nearby.getName().getString())
                    .append(" at ").append((int)nearby.getX()).append(",").append((int)nearby.getY()).append(",").append((int)nearby.getZ())
                    .append(" (dist: ").append((int)Math.sqrt(nearby.squaredDistanceTo(player))).append("m)\n");
                foundPlayers = true;
            }
        }
        if (!foundPlayers) {
            sb.append("  No players nearby\n");
        }

        // Ground items
        sb.append("\n=== GROUND ITEMS ===\n");
        List<ItemEntity> groundItems = world.getEntitiesByClass(ItemEntity.class,
            player.getBoundingBox().expand(8), e -> true);
        if (groundItems.isEmpty()) {
            sb.append("  None nearby\n");
        } else {
            for (ItemEntity item : groundItems) {
                sb.append("  ").append(item.getStack().getName().getString())
                    .append(" x").append(item.getStack().getCount())
                    .append(" at ").append((int)item.getX()).append(",").append((int)item.getY()).append(",").append((int)item.getZ()).append("\n");
            }
        }

        // Danger check
        sb.append("\n=== DANGER ===\n");
        List<HostileEntity> hostiles = world.getEntitiesByClass(HostileEntity.class,
            player.getBoundingBox().expand(16), e -> true);
        if (hostiles.isEmpty()) {
            sb.append("  Safe\n");
        } else {
            for (HostileEntity hostile : hostiles) {
                sb.append("  ").append(hostile.getName().getString())
                    .append(" at ").append((int)hostile.getX()).append(",").append((int)hostile.getY()).append(",").append((int)hostile.getZ()).append("\n");
            }
        }

        return sb.toString();
    }

    private boolean isMineable(BlockState state) {
        // Common mineable blocks that AI would want to mine
        String name = state.getBlock().getTranslationKey();
        return name.contains("log") || name.contains("ore") || name.contains("stone") ||
               name.contains("dirt") || name.contains("sand") || name.contains("gravel") ||
               name.contains("coal") || name.contains("iron") || name.contains("diamond") ||
               name.contains("gold") || name.contains("copper") || name.contains("leaves");
    }

    // ======================== JSON Parsing ========================

    private void parseAndSetActions(String response) {
        addToHistory("assistant", response);

        try {
            int jsonStart = response.indexOf('{');
            int jsonEnd = response.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String json = response.substring(jsonStart, jsonEnd + 1);
                com.google.gson.JsonObject obj = new com.google.gson.Gson().fromJson(json, com.google.gson.JsonObject.class);

                // Parse chat
                String chat = obj.has("chat") && !obj.get("chat").isJsonNull() ? obj.get("chat").getAsString() : null;
                pendingChat = chat;

                // Parse actions array
                if (obj.has("actions") && obj.get("actions").isJsonArray()) {
                    com.google.gson.JsonArray actionsArray = obj.getAsJsonArray("actions");
                    for (int i = 0; i < actionsArray.size() && i < 5; i++) {
                        try {
                            com.google.gson.JsonObject actionObj = actionsArray.get(i).getAsJsonObject();
                            AIAction action = parseSingleAction(actionObj);
                            if (action != null) {
                                pendingActions.addLast(action);
                            }
                        } catch (Exception e) {
                            LOGGER.warn("[GroqPlayer] Could not parse action at index {}: {}", i, e.getMessage());
                        }
                    }
                } else if (obj.has("action")) {
                    // Fallback: single action format (backward compatible)
                    AIAction action = parseSingleAction(obj);
                    if (action != null) {
                        pendingActions.addLast(action);
                    }
                }

                // If no actions were parsed, default to idle
                if (pendingActions.isEmpty() && (chat == null || chat.isBlank())) {
                    pendingActions.addLast(AIAction.of("idle"));
                }
            } else {
                // No JSON found, treat entire response as chat
                pendingChat = extractChatText(response);
                pendingActions.addLast(AIAction.of("idle"));
            }
        } catch (Exception e) {
            LOGGER.warn("[GroqPlayer] Could not parse AI response as JSON, using as chat: {}", response);
            pendingChat = extractChatText(response);
            pendingActions.addLast(AIAction.of("idle"));
        }
    }

    private AIAction parseSingleAction(com.google.gson.JsonObject actionObj) {
        String type = actionObj.has("type") ? actionObj.get("type").getAsString() : "idle";

        double targetX = actionObj.has("x") ? actionObj.get("x").getAsDouble() : 0;
        double targetY = actionObj.has("y") ? actionObj.get("y").getAsDouble() : 0;
        double targetZ = actionObj.has("z") ? actionObj.get("z").getAsDouble() : 0;

        String item = actionObj.has("item") && !actionObj.get("item").isJsonNull()
            ? actionObj.get("item").getAsString() : null;
        String message = actionObj.has("message") && !actionObj.get("message").isJsonNull()
            ? actionObj.get("message").getAsString() : null;

        Vec3d target = new Vec3d(targetX, targetY, targetZ);
        return new AIAction(type, target, item, message);
    }

    private String extractChatText(String response) {
        String clean = response.replaceAll("[{}\"\\[\\]]", "").trim();
        if (clean.length() > 100) clean = clean.substring(0, 100);
        return clean.isEmpty() ? null : clean;
    }

    // ======================== Message Building ========================

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

                You are a REAL player — you mine, build, craft, fight, explore, and talk.
                You must respond ONLY with a JSON object in this EXACT format:
                {
                  "actions": [
                    {"type": "<action_type>", "x": <number>, "y": <number>, "z": <number>, "item": "<item_id>", "message": "<text>"},
                    ...
                  ],
                  "chat": "<optional message to say in chat, or null>"
                }

                You can plan up to 5 actions in sequence. Think step by step.

                Available action types:
                - "walk" — walk toward x,y,z coordinates
                - "sprint" — sprint toward x,y,z coordinates (faster but uses hunger)
                - "mine" — mine block at x,y,z (takes time based on block hardness)
                - "place" — place held block at x,y,z
                - "craft" — craft an item (specify "item" field). Available recipes:
                  oak_planks(1 log→4 planks), birch_planks, spruce_planks, dark_oak_planks,
                  sticks(2 planks→4 sticks), crafting_table(4 planks→1 table),
                  wooden_pickaxe(3 planks+2 sticks), wooden_axe(3 planks+2 sticks),
                  wooden_sword(2 planks+1 stick), wooden_shovel(1 plank+2 sticks),
                  wooden_hoe(2 planks+2 sticks), stone_pickaxe(3 cobblestone+2 sticks),
                  stone_axe(3 cobblestone+2 sticks), stone_sword(2 cobblestone+1 stick),
                  stone_shovel(1 cobblestone+2 sticks), furnace(8 cobblestone),
                  chest(8 planks), torches(1 stick+1 coal→4 torches)
                - "equip" — equip item to main hand (specify "item" field with item name)
                - "eat" — eat food from inventory
                - "attack" — attack nearest hostile mob
                - "follow_player" — follow the nearest player
                - "explore" — explore in a random direction
                - "idle" — stand still
                - "flee" — run away from danger
                - "chat_only" — just say something (use "message" field)

                SURVIVAL PRIORITIES:
                1. If health < 10: eat food or flee from danger
                2. If hunger < 8: eat food immediately
                3. If hostiles nearby and no weapon: flee
                4. If no tools: craft wooden tools first (mine logs → planks → sticks → tools)
                5. At night without shelter: flee or find safety
                6. Always collect dropped items when nearby

                Be a natural Minecraft player. React to the environment. Survive. Have fun.
                Keep chat messages short (under 80 chars) and natural. Use Russian or English based on what others speak.
                Don't always chat — sometimes just act silently.
                """.formatted(botName, personality);
    }

    private void addToHistory(String role, String content) {
        conversationHistory.add(new GroqApiClient.ChatMessage(role, content));
        // Keep history bounded
        while (conversationHistory.size() > GroqConfig.getMaxContextMessages() * 2) {
            conversationHistory.removeFirst();
        }
    }

    // ======================== Public API ========================

    /**
     * Poll a single action from the pending queue.
     */
    public AIAction pollAction() {
        return pendingActions.pollFirst();
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

    /**
     * Force a response for the given message (used by /groqplayer talk command).
     */
    public void forceRespond(String senderName, String message, ServerPlayerEntity aiPlayer) {
        addToHistory("user", senderName + " said to you: \"" + message + "\"");

        if (isThinking.get()) return;
        isThinking.set(true);

        String worldContext = buildWorldContext(aiPlayer, (ServerWorld) aiPlayer.getWorld());
        addToHistory("user", "Current state: " + worldContext + "\n\n" +
            senderName + " is talking to you. Respond with actions and chat.");

        List<GroqApiClient.ChatMessage> messages = buildMessages();

        GroqApiClient.sendAsync(messages, response -> {
            if (response != null) {
                parseAndSetActions(response);
            }
            isThinking.set(false);
        });
    }
}
