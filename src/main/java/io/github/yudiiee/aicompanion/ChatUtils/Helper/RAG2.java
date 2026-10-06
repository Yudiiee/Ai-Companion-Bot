package io.github.yudiiee.aicompanion.ChatUtils.Helper;

import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;
import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatMessageRole;
import net.minecraft.commands.CommandSourceStack;
import io.github.yudiiee.aicompanion.AIProviders.EmbeddingProvider;
import io.github.yudiiee.aicompanion.AIProviders.EmbeddingProviderFactory;
import io.github.yudiiee.aicompanion.ChatUtils.ChatUtils;
import io.github.yudiiee.aicompanion.ChatUtils.NLPProcessor;
import io.github.yudiiee.aicompanion.Database.SQLiteDB;
import io.github.yudiiee.aicompanion.OllamaClient.ollamaClient;
import io.github.yudiiee.aicompanion.Overlay.ThinkingStateManager;
import io.github.yudiiee.aicompanion.ServiceLLMClients.LLMClient;
import io.github.yudiiee.aicompanion.WebSearch.WebSearchTool;
import io.github.yudiiee.aicompanion.Commands.modCommandRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RAG2 {

    private static final Logger logger = LoggerFactory.getLogger("ai-companion");
    private static final OllamaAPI ollamaAPI = new OllamaAPI("http://localhost:11434");
    private static final Pattern THINK_BLOCK = Pattern.compile("<think>([\\s\\S]*?)</think>");
    private static final int TOP_K = 5;
    private static EmbeddingProvider embeddingProvider;

    /**
     * Initialize embedding provider if not already initialized
     */
    private static void ensureEmbeddingProvider() {
        if (embeddingProvider == null) {
            try {
                embeddingProvider = EmbeddingProviderFactory.createEmbeddingProvider(ollamaAPI);
                logger.info("✅ Embedding provider initialized successfully");
            } catch (Exception e) {
                logger.error("❌ Failed to initialize embedding provider: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to initialize embedding provider", e);
            }
        }
    }

    private static String buildPrompt() {
        return buildPrompt(modCommandRegistry.botName);
    }

    private static String buildPrompt(String botName) {
        return io.github.yudiiee.aicompanion.GameAI.human.HumanPersona.systemPrompt(botName) + """

            MEMORY AND FACTS:
            - You may be given old conversations, past events and web/wiki results as context. Use them only if they actually help.
            - Blend memories in naturally, in the past tense ("we found that cave yesterday"), never say you "looked up your memory".
            - For game facts (recipes, mob stats, mechanics), trust provided web/wiki results over your own guesses, and don't make things up.
            - If you don't know, say so casually ("no clue tbh").
            - Answer the latest message from the player; everything else is background.
            """;
    }

    public static void processLLMOutput(String fullResponse, String botName, CommandSourceStack botSource) {
        Matcher matcher = THINK_BLOCK.matcher(fullResponse);

        if (matcher.find()) {
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
                ChatUtils.sendChatMessages(botSource, botName + ": " + remainder);
            }
        } else {
            ChatUtils.sendChatMessages(botSource, botName + ": " + fullResponse);
        }
    }

    private static String getBestContextAnswer(String userPrompt, List<Double> queryEmbedding) {
        String webAnswer = WebSearchTool.search(userPrompt).trim();
        logger.info("🌐 Web search result: {}", webAnswer);

        List<SQLiteDB.Memory> localMemories = queryEmbedding == null ? List.of()
                : SQLiteDB.findRelevantMemories(queryEmbedding, "conversation", 1);
        boolean hasLocal = !localMemories.isEmpty();
        String localAnswer = hasLocal ? localMemories.get(0).response() : "";
        double localSimilarity = hasLocal ? localMemories.get(0).similarity() : 0.0;

        logger.info("🔍 Local similarity: {}", localSimilarity);

        // Decide which to trust
        String bestAnswer;
        if (!webAnswer.isBlank()) {
            if (!webAnswer.equalsIgnoreCase(localAnswer)) {
                bestAnswer = webAnswer;
                logger.info("✅ Using web answer, overwriting local DB");
                if (queryEmbedding != null) SQLiteDB.storeMemory("conversation", userPrompt, bestAnswer, queryEmbedding);
            } else {
                bestAnswer = localAnswer;
                logger.info("✅ Local and web match, using local");
            }
        } else if (hasLocal && localSimilarity >= 0.8) {
            bestAnswer = localAnswer;
            logger.info("✅ Using local answer, web empty");
        } else {
            bestAnswer = "❌ No relevant info found.";
            logger.warn("⚠️ Both web and local empty or not confident");
        }

        return bestAnswer;
    }


    public static void run(String userPrompt, CommandSourceStack botSource, NLPProcessor.Intent intent, LLMClient client) {
        ollamaAPI.setRequestTimeoutSeconds(120);
        logger.info("⚡ RAG v2: Running with intent = {} and using provider: {}", intent, client);

        try {
            // Memories need an embedding model. If none is available (e.g. only a chat
            // model is loaded in LM Studio), just answer without long-term memory.
            List<Double> queryEmbedding = null;
            try {
                ensureEmbeddingProvider();
                queryEmbedding = embeddingProvider.generateEmbeddings(userPrompt);
            } catch (Exception embeddingError) {
                logger.warn("Embeddings unavailable ({}); answering without long-term memory.", embeddingError.getMessage());
            }

            StringBuilder contextBuilder = new StringBuilder();

            if (intent == NLPProcessor.Intent.ASK_INFORMATION) {
                ChatUtils.sendChatMessages(botSource, "Running web search....");
                String bestAnswer = getBestContextAnswer(userPrompt, queryEmbedding);

                if (bestAnswer.equalsIgnoreCase("❌ No relevant info found.")) {
                    ChatUtils.sendChatMessages(botSource, "No info found. Either there is no info on this topic or my web search tool is not working properly. Please report this to developer!");
                }
                else {
                    ChatUtils.sendChatMessages(botSource, "Web search complete.");
                }

                contextBuilder.append("Web/Local best answer:\n").append(bestAnswer).append("\n\n");

            } else if (queryEmbedding != null) {
                // 🤝 Just normal local vector recall
                List<SQLiteDB.Memory> localMemories = SQLiteDB.findRelevantMemories(queryEmbedding, "conversation", TOP_K);
                contextBuilder.append("Relevant conversations:\n");
                for (SQLiteDB.Memory m : localMemories) {
                    contextBuilder.append("- Prompt: ").append(m.prompt()).append("\n");
                    contextBuilder.append("  Response: ").append(m.response()).append("\n");
                    contextBuilder.append("  Similarity: ").append(m.similarity()).append("\n\n");
                }
            }

            // 🗃️ Add relevant events in all cases
            List<SQLiteDB.Memory> events = queryEmbedding == null ? List.of()
                    : SQLiteDB.findRelevantMemories(queryEmbedding, "event", TOP_K);
            if (!events.isEmpty()) contextBuilder.append("Relevant events:\n");
            for (SQLiteDB.Memory m : events) {
                contextBuilder.append("- Prompt: ").append(m.prompt()).append("\n");
                contextBuilder.append("  Response: ").append(m.response()).append("\n");
                contextBuilder.append("  Similarity: ").append(m.similarity()).append("\n\n");
            }

            // ✨ Final LLM prompt
            String systemPrompt = buildPrompt(botSource.getTextName());
            String finalUserPrompt = "Background context (may be irrelevant):\n" + contextBuilder.toString().trim() + "\n\nThe player just said in chat:\n" + userPrompt + "\n\nWrite your chat reply.";

            String finalResponse = client.sendPrompt(systemPrompt, finalUserPrompt);

            processLLMOutput(finalResponse, botSource.getTextName(), botSource);

            // 🔒 Store the exchange for long-term memory (when embeddings work)
            if (queryEmbedding != null) SQLiteDB.storeMemory("conversation", userPrompt, finalResponse, queryEmbedding);

            logger.info("✅ RAG v2 finished with intent-aware strategy.");

        } catch (Exception e) {
            logger.error("❌ RAG v2 failed: {}", e.getMessage(), e);
            ChatUtils.sendChatMessages(botSource, "Sorry, I couldn't find enough context. Please try again!");
        }
    }

    // overloaded method for the existing ollama client to work with.

    public static void run(String userPrompt, CommandSourceStack botSource, NLPProcessor.Intent intent) {

        ollamaAPI.setRequestTimeoutSeconds(120);

        logger.info("⚡ RAG v2: Running with intent = {}", intent);


        try {
            // Initialize embedding provider if not already done
            if (embeddingProvider == null) {
                embeddingProvider = EmbeddingProviderFactory.createEmbeddingProvider(ollamaAPI);
            }

            List<Double> queryEmbedding = embeddingProvider.generateEmbeddings(userPrompt);



            StringBuilder contextBuilder = new StringBuilder();



            if (intent == NLPProcessor.Intent.ASK_INFORMATION) {

                ChatUtils.sendChatMessages(botSource, "Running web search....");

                String bestAnswer = getBestContextAnswer(userPrompt, queryEmbedding);



                if (bestAnswer.equalsIgnoreCase("❌ No relevant info found.")) {

                    ChatUtils.sendChatMessages(botSource, "No info found. Either there is no info on this topic or my web search tool is not working properly. Please report this to developer!");

                }

                else {

                    ChatUtils.sendChatMessages(botSource, "Web search complete.");

                }



                contextBuilder.append("Web/Local best answer:\n").append(bestAnswer).append("\n\n");



            } else {

            // 🤝 Just normal local vector recall

                List<SQLiteDB.Memory> localMemories = SQLiteDB.findRelevantMemories(queryEmbedding, "conversation", TOP_K);

                contextBuilder.append("Relevant conversations:\n");

                for (SQLiteDB.Memory m : localMemories) {

                    contextBuilder.append("- Prompt: ").append(m.prompt()).append("\n");

                    contextBuilder.append(" Response: ").append(m.response()).append("\n");

                    contextBuilder.append(" Similarity: ").append(m.similarity()).append("\n\n");

                }

            }



             // 🗃️ Add relevant events in all cases

            List<SQLiteDB.Memory> events = SQLiteDB.findRelevantMemories(queryEmbedding, "event", TOP_K);

            contextBuilder.append("Relevant events:\n");

            for (SQLiteDB.Memory m : events) {

                contextBuilder.append("- Prompt: ").append(m.prompt()).append("\n");

                contextBuilder.append(" Response: ").append(m.response()).append("\n");

                contextBuilder.append(" Similarity: ").append(m.similarity()).append("\n\n");

            }



            // ✨ Final LLM prompt

            // Use new API helper for thinking mode support
            List<io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatMessage> messages = new java.util.ArrayList<>();
            messages.add(new io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatMessage(
                    OllamaChatMessageRole.SYSTEM, buildPrompt(botSource.getTextName())));
            messages.add(new io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatMessage(
                    OllamaChatMessageRole.USER, "Context:\n" + contextBuilder));
            messages.add(new io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatMessage(
                    OllamaChatMessageRole.USER, "User prompt:\n" + userPrompt));

            io.github.yudiiee.aicompanion.OllamaClient.OllamaThinkingResponse response =
                    io.github.yudiiee.aicompanion.OllamaClient.OllamaAPIHelper.smartChat(
                            ollamaAPI,
                            "http://localhost:11434",
                            io.github.yudiiee.aicompanion.AICompanion.CONFIG.getSelectedLanguageModel(),
                            messages
                    );

            String finalResponse = response.getFullResponse();

            ollamaClient.processLLMOutput(finalResponse, botSource.getTextName(), botSource);


            // 🔒 Always store final response

            SQLiteDB.storeMemory("conversation", userPrompt, finalResponse, queryEmbedding);



            logger.info("✅ RAG v2 finished with intent-aware strategy.");



        } catch (Exception e) {

            logger.error("❌ RAG v2 failed: {}", e.getMessage(), e);

            ChatUtils.sendChatMessages(botSource, "Sorry, I couldn't find enough context. Please try again!");

        }

    }
}
