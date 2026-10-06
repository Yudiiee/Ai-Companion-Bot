package io.github.yudiiee.aicompanion.FilingSystem;

import io.github.yudiiee.aicompanion.AICompanion;
import io.github.yudiiee.aicompanion.ServiceLLMClients.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.Consumer;

public class LLMClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger("llm-client-factory");

    /**
     * Creates an LLMClient for providers that only need an API key.
     * For Player2, use {@link #createClient(String, UUID, Consumer)} instead.
     */
    public static LLMClient createClient(String mode) {
        return createClient(mode, null, null);
    }

    /**
     * Creates an LLMClient for the given provider mode.
     *
     * <p>{@code playerUUID} and {@code chatCallback} are only used by the
     * {@code "player2"} case — they are ignored for every other provider.
     *
     * @param mode         provider string from config / JVM flag
     * @param playerUUID   the Minecraft player UUID (required for Player2)
     * @param chatCallback receives in-game chat messages (required for Player2)
     */
    public static LLMClient createClient(String mode, UUID playerUUID, Consumer<String> chatCallback) {
        return switch (mode) {
            case "openai", "gpt" -> {
                if (AICompanion.CONFIG.getOpenAIKey().isEmpty()) {
                    LOGGER.error("OpenAI API key not set in config!");
                    yield null;
                }
                yield new OpenAIClient(AICompanion.CONFIG.getOpenAIKey(), AICompanion.CONFIG.getSelectedLanguageModel());
            }
            case "anthropic", "claude" -> {
                if (AICompanion.CONFIG.getClaudeKey().isEmpty()) {
                    LOGGER.error("Claude API key not set in config!");
                    yield null;
                }
                yield new AnthropicClient(AICompanion.CONFIG.getClaudeKey(), AICompanion.CONFIG.getSelectedLanguageModel());
            }
            case "google", "gemini" -> {
                if (AICompanion.CONFIG.getGeminiKey().isEmpty()) {
                    LOGGER.error("Gemini API key not set in config!");
                    yield null;
                }
                yield new GeminiClient(AICompanion.CONFIG.getGeminiKey(), AICompanion.CONFIG.getSelectedLanguageModel());
            }
            case "xAI", "xai", "grok" -> {
                if (AICompanion.CONFIG.getGrokKey().isEmpty()) {
                    LOGGER.error("Grok API key not set in config!");
                    yield null;
                }
                yield new GrokClient(AICompanion.CONFIG.getGrokKey(), AICompanion.CONFIG.getSelectedLanguageModel());
            }
            case "custom" -> {
                if (AICompanion.CONFIG.getCustomApiUrl().isEmpty()) {
                    LOGGER.error("Custom API URL not set in config!");
                    yield null;
                }
                yield new GenericOpenAIClient(AICompanion.CONFIG.getCustomApiKey(), AICompanion.CONFIG.getSelectedLanguageModel(), AICompanion.CONFIG.getCustomApiUrl());
            }
            case "player2" -> {
                if (playerUUID == null || chatCallback == null) {
                    LOGGER.error("Player2 requires playerUUID and chatCallback — use createClient(mode, playerUUID, chatCallback)!");
                    yield null;
                }
                yield new Player2Client(playerUUID, AICompanion.CONFIG.getSelectedLanguageModel(), chatCallback);
            }
            default -> {
                LOGGER.error("Unsupported LLM provider: {}. Set aicompanion.llmMode=custom for an OpenAI-compatible endpoint.", mode);
                yield null;
            }
        };
    }
}
