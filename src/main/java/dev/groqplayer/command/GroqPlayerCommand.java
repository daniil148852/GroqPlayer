package dev.groqplayer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.groqplayer.GroqPlayerMod;
import dev.groqplayer.entity.GroqFakePlayer;
import dev.groqplayer.config.GroqConfig;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

public class GroqPlayerCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("groqplayer")
                .requires(source -> source.hasPermissionLevel(2))

                // /groqplayer spawn <name> [personality]
                .then(CommandManager.literal("spawn")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> spawnPlayer(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name"),
                            "friendly, helpful, curious Minecraft player"))
                        .then(CommandManager.argument("personality", StringArgumentType.greedyString())
                            .executes(ctx -> spawnPlayer(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"),
                                StringArgumentType.getString(ctx, "personality"))))
                    )
                )

                // /groqplayer remove <name>
                .then(CommandManager.literal("remove")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> removePlayer(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name")))
                    )
                )

                // /groqplayer removeall
                .then(CommandManager.literal("removeall")
                    .executes(ctx -> removeAllPlayers(ctx.getSource()))
                )

                // /groqplayer list
                .then(CommandManager.literal("list")
                    .executes(ctx -> listPlayers(ctx.getSource()))
                )

                // /groqplayer talk <name> <message> — force AI response
                .then(CommandManager.literal("talk")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .then(CommandManager.argument("message", StringArgumentType.greedyString())
                            .executes(ctx -> talkToPlayer(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"),
                                StringArgumentType.getString(ctx, "message")))
                        )
                    )
                )

                // /groqplayer inv <name> — show AI inventory
                .then(CommandManager.literal("inv")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> showInventory(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name")))
                    )
                )

                // /groqplayer setkey <api_key>
                .then(CommandManager.literal("setkey")
                    .then(CommandManager.argument("key", StringArgumentType.greedyString())
                        .executes(ctx -> setApiKey(ctx.getSource(),
                            StringArgumentType.getString(ctx, "key")))
                    )
                )

                // /groqplayer setmodel <model>
                .then(CommandManager.literal("setmodel")
                    .then(CommandManager.argument("model", StringArgumentType.word())
                        .executes(ctx -> setModel(ctx.getSource(),
                            StringArgumentType.getString(ctx, "model")))
                    )
                )

                // /groqplayer debug
                .then(CommandManager.literal("debug")
                    .executes(ctx -> toggleDebug(ctx.getSource()))
                )

                // /groqplayer help
                .then(CommandManager.literal("help")
                    .executes(ctx -> showHelp(ctx.getSource()))
                )
        );
    }

    private static int spawnPlayer(ServerCommandSource source, String name, String personality) {
        if (GroqConfig.getApiKey() == null || GroqConfig.getApiKey().isBlank()) {
            source.sendError(Text.literal("§cNo Groq API key set! Use /groqplayer setkey <key> first."));
            return 0;
        }

        Vec3d pos = source.getPosition();
        boolean success = GroqPlayerMod.getPlayerManager().spawnPlayer(
            source.getServer(), name, personality, pos
        );

        if (success) {
            source.sendFeedback(() -> Text.literal(
                "§a\u2714 Spawned AI player §e" + name + "§a with personality: §7" + personality
            ), true);
            return 1;
        } else {
            source.sendError(Text.literal("§cAI player '" + name + "' already exists or failed to spawn."));
            return 0;
        }
    }

    private static int removePlayer(ServerCommandSource source, String name) {
        boolean success = GroqPlayerMod.getPlayerManager().removePlayer(source.getServer(), name);
        if (success) {
            source.sendFeedback(() -> Text.literal("§aRemoved AI player: §e" + name), true);
            return 1;
        } else {
            source.sendError(Text.literal("§cNo AI player named '" + name + "' found."));
            return 0;
        }
    }

    private static int removeAllPlayers(ServerCommandSource source) {
        int count = GroqPlayerMod.getPlayerManager().getCount();
        GroqPlayerMod.getPlayerManager().removeAllPlayers(source.getServer());
        source.sendFeedback(() -> Text.literal("§aRemoved §e" + count + "§a AI player(s)."), true);
        return count;
    }

    private static int listPlayers(ServerCommandSource source) {
        var players = GroqPlayerMod.getPlayerManager().getActivePlayers();
        if (players.isEmpty()) {
            source.sendFeedback(() -> Text.literal("§7No active AI players."), false);
        } else {
            source.sendFeedback(() -> Text.literal("§eActive AI Players §7(" + players.size() + "):"), false);
            for (var p : players) {
                source.sendFeedback(() -> Text.literal(
                    "  §a\u2022 §f" + p.getName().getString() +
                    " §7| HP: " + String.format("%.1f", p.getHealth()) +
                    " | Hunger: " + p.getHungerManager().getFoodLevel() +
                    " | Pos: " + (int)p.getX() + "," + (int)p.getY() + "," + (int)p.getZ() +
                    " | " + (p.getAIBrain().isThinking() ? "§eThinking..." : "§aReady")
                ), false);
            }
        }
        return players.size();
    }

    private static int talkToPlayer(ServerCommandSource source, String name, String message) {
        GroqFakePlayer player = GroqPlayerMod.getPlayerManager().getPlayer(name);
        if (player == null) {
            source.sendError(Text.literal("§cNo AI player named '" + name + "' found."));
            return 0;
        }

        player.getAIBrain().forceRespond(source.getName(), message, player);
        source.sendFeedback(() -> Text.literal("§7Sent message to §e" + name + "§7: §f" + message), false);
        return 1;
    }

    private static int showInventory(ServerCommandSource source, String name) {
        GroqFakePlayer player = GroqPlayerMod.getPlayerManager().getPlayer(name);
        if (player == null) {
            source.sendError(Text.literal("§cNo AI player named '" + name + "' found."));
            return 0;
        }

        source.sendFeedback(() -> Text.literal("§e=== " + name + "'s Inventory ==="), false);

        ItemStack mainHand = player.getMainHandStack();
        ItemStack offHand = player.getOffHandStack();
        source.sendFeedback(() -> Text.literal(
            "§7Main hand: §f" + (mainHand.isEmpty() ? "empty" : mainHand.getName().getString() + " x" + mainHand.getCount())
        ), false);
        source.sendFeedback(() -> Text.literal(
            "§7Off hand: §f" + (offHand.isEmpty() ? "empty" : offHand.getName().getString() + " x" + offHand.getCount())
        ), false);

        boolean hasItems = false;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                hasItems = true;
                String slotType = i < 9 ? "§aHotbar " + i : i < 36 ? "§7Slot " + i : "§dArmor " + (i - 36);
                source.sendFeedback(() -> Text.literal(
                    "  " + slotType + ": §f" + stack.getName().getString() + " x" + stack.getCount()
                ), false);
            }
        }
        if (!hasItems) {
            source.sendFeedback(() -> Text.literal("  §7Inventory is empty"), false);
        }

        return 1;
    }

    private static int setApiKey(ServerCommandSource source, String key) {
        GroqConfig.setApiKey(key);
        source.sendFeedback(() -> Text.literal("§a\u2714 Groq API key saved! Model: §e" + GroqConfig.getModel()), false);
        GroqPlayerMod.LOGGER.info("[GroqPlayer] API key updated by {}", source.getName());
        return 1;
    }

    private static int setModel(ServerCommandSource source, String model) {
        GroqConfig.setModel(model);
        source.sendFeedback(() -> Text.literal("§a\u2714 Model set to: §e" + model), false);
        return 1;
    }

    private static int toggleDebug(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal(
            "§7Debug mode is: §e" + GroqConfig.isDebugMode() + " §7(edit groqplayer.json to change)"
        ), false);
        return 1;
    }

    private static int showHelp(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("""
            \u00a76\u00a7l\u2501\u2501\u2501 GroqPlayer Commands \u2501\u2501\u2501
            \u00a7e/groqplayer setkey \u00a77<key> \u00a7f- Set Groq API key
            \u00a7e/groqplayer setmodel \u00a77<model> \u00a7f- Set AI model (default: llama3-70b-8192)
            \u00a7e/groqplayer spawn \u00a77<name> [personality] \u00a7f- Spawn AI player at your position
            \u00a7e/groqplayer remove \u00a77<name> \u00a7f- Remove AI player
            \u00a7e/groqplayer removeall \u00a7f- Remove all AI players
            \u00a7e/groqplayer list \u00a7f- List active AI players
            \u00a7e/groqplayer talk \u00a77<name> <message> \u00a7f- Force AI player to respond
            \u00a7e/groqplayer inv \u00a77<name> \u00a7f- Show AI player's inventory
            \u00a7e/groqplayer debug \u00a7f- Show debug info
            \u00a76\u00a7l\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501
            \u00a77Models: llama3-70b-8192, mixtral-8x7b-32768, gemma2-9b-it
            """), false);
        return 1;
    }
}
