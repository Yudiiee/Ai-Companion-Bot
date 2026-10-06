package io.github.yudiiee.aicompanion.ServiceLLMClients;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import io.github.yudiiee.aicompanion.AICompanion;
import io.github.yudiiee.aicompanion.ChatUtils.ChatUtils;
import io.github.yudiiee.aicompanion.ChatUtils.Helper.RAG2;
import io.github.yudiiee.aicompanion.ChatUtils.NLPProcessor;
import io.github.yudiiee.aicompanion.Database.SQLiteDB;
import io.github.yudiiee.aicompanion.FunctionCaller.FunctionCallerV2;
import io.github.yudiiee.aicompanion.Overlay.ThinkingStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LLMServiceHandler {
    public static final Logger LOGGER = LoggerFactory.getLogger("LLMServiceHandler");
    private static final ExecutorService BOT_TASK_POOL = Executors.newCachedThreadPool(io.github.yudiiee.aicompanion.GameAI.human.DaemonThreads.named("ai-companion-llm"));
    private static final Pattern THINK_BLOCK = Pattern.compile("<think>([\\s\\S]*?)</think>");
    public static String initialResponse = "";
    public static boolean isInitialized = false;

    private static String generateSystemPrompt(String botName) {
        // Talk like another player in the world, not like an in-game help desk.
        return io.github.yudiiee.aicompanion.GameAI.human.HumanPersona.systemPrompt(botName);
    }

    public static void processLLMOutput(String fullResponse, String botName, CommandSourceStack botSource) {
        LOGGER.info("processLLMOutput called with response: '{}', botName: '{}'", fullResponse, botName);

        if (fullResponse == null || fullResponse.trim().isEmpty()) {
            LOGGER.warn("fullResponse is null or empty");
            return;
        }

        Matcher matcher = THINK_BLOCK.matcher(fullResponse);

        if (matcher.find()) {
            LOGGER.info("Think block found");
            String thinking = matcher.group(1).trim();
            String remainder = fullResponse.replace(matcher.group(0), "").trim();

            ThinkingStateManager.start(botName);
            ChatUtils.sendChatMessages(botSource, botName + " is thinking...");

            for (String line : thinking.split("\\n")) {
                ThinkingStateManager.appendThoughtLine(line);
            }

            ThinkingStateManager.end();
            ChatUtils.sendChatMessages(botSource, botName + " is done thinking!");

            if (!remainder.isEmpty()) {
                LOGGER.info("Sending remainder: '{}'", remainder);
                ChatUtils.sendChatMessages(botSource, botName + ": " + remainder);
            } else {
                LOGGER.info("Remainder is empty");
            }
        } else {
            LOGGER.info("No think block found, sending full response: '{}'", fullResponse);
            ChatUtils.sendChatMessages(botSource, fullResponse);
        }
    }


    public static void sendInitialResponse(CommandSourceStack botSource, LLMClient client) {
        MinecraftServer server = botSource.getServer();
        String botName = botSource.getPlayer().getName().getString();

        CompletableFuture<String> initFuture = CompletableFuture.supplyAsync(() -> {
            try {
                if (client.isReachable()) {
                    isInitialized = true;
                    LOGGER.info("{} client initialized.", client.getProvider());
                    ChatUtils.sendChatMessages(botSource, "Established connection to " + client.getProvider() + "'s servers. Using " + AICompanion.CONFIG.getSelectedLanguageModel());

                    // Fetch and return the initial response
                    String response = client.sendPrompt(generateSystemPrompt(botName), io.github.yudiiee.aicompanion.GameAI.human.HumanPersona.greetingInstruction());
                    LOGGER.info("Initial response received: '{}'", response);
                    LOGGER.info("Response length: {}", response != null ? response.length() : "null");
                    initialResponse = response;
                    return response;
                } else {
                    LOGGER.error("Error! Could not reach {} client. Please try again!", client.getProvider());
                    ChatUtils.sendChatMessages(botSource, "Error! Could not reach " + client.getProvider() + "'s servers. Please check your internet connection or try again after sometime!");
                    return null;
                }
            } catch (Exception e) {
                LOGGER.error("Exception in initFuture: {}", e.getMessage(), e);
                return null;
            }
        });

        initFuture.thenAccept(response -> {
            try {
                LOGGER.info("thenAccept called with response: '{}'", response);
                if (response != null && !response.trim().isEmpty()) {
                    // Process the response on the main thread

                    LOGGER.info("Scheduling processLLMOutput on main thread for bot: {}", botName);
                    server.execute(() -> {
                        try {
                            LOGGER.info("About to call processLLMOutput with: '{}'", response);
                            processLLMOutput(response, botName, botSource);
                            LOGGER.info("processLLMOutput completed");
                        } catch (Exception e) {
                            LOGGER.error("Exception in processLLMOutput: {}", e.getMessage(), e);
                        }
                    });

                    // Handle database operations
                    CompletableFuture.runAsync(() -> {
                        // ... your database code
                    });
                } else {
                    LOGGER.warn("Response is null or empty, not processing");
                }
            } catch (Exception e) {
                LOGGER.error("Exception in thenAccept: {}", e.getMessage(), e);
            }
        }).exceptionally(throwable -> {
            LOGGER.error("CompletableFuture failed: {}", throwable.getMessage(), throwable);
            return null;
        });
    }

    /**
     * Entry point for running the bot's logic from a chat message.
     * This method triggers the intent routing and is called by the main game thread.
     *
     * @param message The chat message from the player.
     * @param botName The name of the bot.
     * @param playerUUID The UUID of the player.
     */
    public static void runFromChat(String message, String botName, UUID playerUUID, LLMClient client) {
        MinecraftServer server = AICompanion.serverInstance;
        ServerPlayer bot = server.getPlayerList().getPlayerByName(botName);
        if (bot == null) {
            LOGGER.error("Bot {} not online.", botName);
            return;
        }
        CommandSourceStack botSource = bot.createCommandSourceStack().withSuppressedOutput().withMaximumPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);

        // Intent detection can fall back to the language model, which may take a
        // long time (especially local "thinking" models). Doing that on the server
        // thread froze the world until the client timed out, so run it in the pool.
        BOT_TASK_POOL.submit(() -> {
            try {
                routeIntent(normaliseForIntent(message), botSource, playerUUID, client);
            } catch (Exception e) {
                LOGGER.error("Chat processing error: ", e);
                ChatUtils.sendChatMessages(botSource, "⚠️ I'm confused! Please report this.");
            }
        });
    }

    /**
     * Routes the user's intent to the appropriate function (RAG, FunctionCaller, etc.).
     *
     * @param message The user's message.
     * @param botSource The bot's command source.
     * @param playerUUID The player's UUID.
     * @throws Exception if an error occurs during intent routing.
     */
    /** Casual chat ("how are you", "wyd") is conversation, not a web-search question. */
    private static final java.util.regex.Pattern SMALL_TALK = java.util.regex.Pattern.compile(
            "(?i)^\\W*(how are you|how r u|how's it going|hows it going|what'?s up|wassup|sup|wyd|what are you doing|"
          + "what r u doing|who are you|are you (a )?(bot|ai|real|human)|you good|u good|how was your day|"
          + "what do you think|do you like|i like|i love|lol|lmao|haha|nice|cool|ok|okay|thanks|thank you)\\b.*");
    private static final java.util.regex.Pattern INFO_WORDS = java.util.regex.Pattern.compile(
            "(?i)\\b(how (do|to|can|does|many|much)|recipe|craft|what is|what are|where (is|are|do|can)|which|why|when|wiki|explain)\\b");

    private static String normaliseForIntent(String message) {
        return message == null ? "" : message.trim();
    }

    private static boolean isSmallTalk(String message) {
        String m = message.trim();
        if (INFO_WORDS.matcher(m).find()) return false;
        return SMALL_TALK.matcher(m).matches() || m.split("\\s+").length <= 3;
    }

    private static void routeIntent(String message, CommandSourceStack botSource, UUID playerUUID, LLMClient client) throws Exception {
        NLPProcessor.Intent detected = NLPProcessor.getIntention(message);
        final NLPProcessor.Intent intent = (detected == NLPProcessor.Intent.ASK_INFORMATION && isSmallTalk(message))
                ? NLPProcessor.Intent.GENERAL_CONVERSATION : detected;

        LOGGER.info("📨 Received intent: {}", intent);


        switch (intent) {
            case GENERAL_CONVERSATION, ASK_INFORMATION -> {
                BOT_TASK_POOL.submit(() -> {
                    Thread.currentThread().setName("LLM-RAG2-Worker");
                    LOGGER.info("🧵 Started RAG2 worker thread");
                    RAG2.run(message, botSource, intent, client);
                    LOGGER.info("✅ Finished RAG2 worker thread");
                });
            }

            case REQUEST_ACTION -> {
                BOT_TASK_POOL.submit(() -> {
                    Thread.currentThread().setName("LLM-Function-Caller-Worker");
                    LOGGER.info("🧵 Started FunctionCallerV2 worker thread");
                    new FunctionCallerV2(botSource, playerUUID);
                    FunctionCallerV2.run(message, client);
                    LOGGER.info("✅ Finished FunctionCallerV2 worker thread");
                });
            }

            default -> {
                LOGGER.warn("⚠️ Intent unclear, retrying with LLM classification...");
                ChatUtils.sendChatMessages(botSource, "🔍 Reanalyzing...");

                NLPProcessor.Intent retry = retryIntentLLM(message, client);

                LOGGER.info("📨 Retry intent: {}", retry);

                if (retry == NLPProcessor.Intent.GENERAL_CONVERSATION || retry == NLPProcessor.Intent.ASK_INFORMATION) {
                    BOT_TASK_POOL.submit(() -> {
                        Thread.currentThread().setName("LLM-RAG2-Retry-Worker");
                        LOGGER.info("🧵 Started RAG2 retry worker thread");
                        RAG2.run(message, botSource, retry, client);
                        LOGGER.info("✅ Finished RAG2 retry worker thread");
                    });
                } else if (retry == NLPProcessor.Intent.REQUEST_ACTION) {
                    BOT_TASK_POOL.submit(() -> {
                        Thread.currentThread().setName("LLM-Function-Caller-Retry-Worker");
                        LOGGER.info("🧵 Started FunctionCallerV2 retry worker thread");
                        new FunctionCallerV2(botSource, playerUUID);
                        FunctionCallerV2.run(message, client);
                        LOGGER.info("✅ Finished FunctionCallerV2 retry worker thread");
                    });
                } else {
                    LOGGER.warn("⚠️ Intent remained unclear after retry.");
                    ChatUtils.sendChatMessages(botSource, "I couldn't understand that clearly. Please try rephrasing.");
                }
            }
        }
    }


    private static NLPProcessor.Intent retryIntentLLM(String message, LLMClient client) {
        return NLPProcessor.getIntentionFromLLM(message, client);
    }
}
