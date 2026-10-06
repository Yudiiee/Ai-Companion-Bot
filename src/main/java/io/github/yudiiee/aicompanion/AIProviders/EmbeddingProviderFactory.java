package io.github.yudiiee.aicompanion.AIProviders;

import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;
import io.github.yudiiee.aicompanion.AICompanion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating EmbeddingProvider instances based on current configuration.
 * Automatically selects the appropriate embedding model based on the LLM provider.
 */
public class EmbeddingProviderFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-embedding-factory");

    /**
     * Create an embedding provider based on the current AI provider configuration.
     * This method automatically determines the correct embedding endpoint and model
     * based on the selected LLM provider from JVM arguments.
     *
     * @param ollamaAPI Ollama API instance (used only when the ollama provider is explicitly selected)
     * @return Configured EmbeddingProvider
     */
    public static EmbeddingProvider createEmbeddingProvider(OllamaAPI ollamaAPI) {
        try {
            // Get provider from JVM argument
            String provider = System.getProperty("aicompanion.llmMode", "custom");
            LOGGER.info("🔍 Creating embedding provider for: {}", provider);

            String embeddingModel = getDefaultEmbeddingModel(provider);
            String apiKey;
            String endpoint;

            switch (provider.toLowerCase()) {
                case "ollama":
                    LOGGER.info("✅ Using Ollama embedding model: {}", embeddingModel);
                    return new EmbeddingProvider(ollamaAPI, embeddingModel);

                case "openai":
                    apiKey = AICompanion.CONFIG.getOpenAIKey();
                    if (apiKey == null || apiKey.isEmpty()) {
                        throw new IllegalStateException("OpenAI API key is not configured");
                    }
                    LOGGER.info("✅ Using OpenAI embedding model: {}", embeddingModel);
                    return new EmbeddingProvider(
                            "https://api.openai.com",
                            apiKey,
                            embeddingModel,
                            EmbeddingProvider.AIProviderType.OPENAI_COMPATIBLE
                    );

                case "gemini":
                    apiKey = AICompanion.CONFIG.getGeminiKey();
                    if (apiKey == null || apiKey.isEmpty()) {
                        throw new IllegalStateException("Gemini API key is not configured");
                    }
                    LOGGER.info("✅ Using Gemini embedding model: {}", embeddingModel);
                    return new EmbeddingProvider(
                            "https://generativelanguage.googleapis.com",
                            apiKey,
                            embeddingModel,
                            EmbeddingProvider.AIProviderType.GEMINI
                    );

                case "grok":
                    apiKey = AICompanion.CONFIG.getGrokKey();
                    if (apiKey == null || apiKey.isEmpty()) {
                        throw new IllegalStateException("Grok API key is not configured");
                    }
                    LOGGER.info("✅ Using Grok (OpenAI-compatible) embedding model: {}", embeddingModel);
                    return new EmbeddingProvider(
                            "https://api.x.ai",
                            apiKey,
                            embeddingModel,
                            EmbeddingProvider.AIProviderType.OPENAI_COMPATIBLE
                    );

                case "custom":
                    endpoint = AICompanion.CONFIG.getCustomApiUrl();
                    apiKey = AICompanion.CONFIG.getCustomApiKey();

                    if (endpoint == null || endpoint.isEmpty()) {
                        throw new IllegalStateException("Custom OpenAI-compatible endpoint is not configured");
                    }

                    // If no API key is required for custom endpoint (e.g., LM Studio, local VLLM)
                    if (apiKey == null) {
                        apiKey = "";
                    }

                    // Pick an embedding model the server actually has (LM Studio ships
                    // "text-embedding-nomic-embed-text-v1.5" and loads it on demand).
                    embeddingModel = detectCustomEmbeddingModel(endpoint, apiKey, embeddingModel);

                    LOGGER.info("✅ Using custom OpenAI-compatible embedding endpoint: {}", endpoint);
                    LOGGER.info("✅ Using embedding model: {}", embeddingModel);
                    return new EmbeddingProvider(
                            endpoint,
                            apiKey,
                            embeddingModel,
                            EmbeddingProvider.AIProviderType.OPENAI_COMPATIBLE
                    );

                case "claude":
                case "anthropic":
                    throw new IllegalStateException("Anthropic/Claude does not provide embedding endpoints. Configure a custom OpenAI-compatible embedding endpoint.");

                default:
                    throw new IllegalStateException("Unknown embedding provider: " + provider);
            }
        } catch (Exception e) {
            LOGGER.error("❌ Failed to create embedding provider", e);
            throw new IllegalStateException("Failed to create embedding provider", e);
        }
    }

    /**
     * Get the default embedding model for a given provider.
     * These are industry-standard defaults that work with most providers.
     */
    private static volatile String detectedCustomModel = null;

    /**
     * For OpenAI-compatible servers: use -Daicompanion.embeddingModel if given, otherwise the
     * first model whose id contains "embed" from GET {endpoint}/models, otherwise the default.
     */
    private static String detectCustomEmbeddingModel(String endpoint, String apiKey, String fallback) {
        String override = System.getProperty("aicompanion.embeddingModel");
        if (override != null && !override.isBlank()) return override.trim();
        if (detectedCustomModel != null) return detectedCustomModel;
        try {
            String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
            java.net.http.HttpRequest.Builder req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/models"))
                    .timeout(java.time.Duration.ofSeconds(5)).GET();
            if (apiKey != null && !apiKey.isBlank()) req.header("Authorization", "Bearer " + apiKey);
            java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3)).build()
                    .send(req.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
                com.google.gson.JsonArray data = root.has("data") ? root.getAsJsonArray("data") : new com.google.gson.JsonArray();
                for (com.google.gson.JsonElement el : data) {
                    String id = el.getAsJsonObject().has("id") ? el.getAsJsonObject().get("id").getAsString() : "";
                    if (id.toLowerCase().contains("embed")) {
                        detectedCustomModel = id;
                        LOGGER.info("✅ Detected embedding model on the custom server: {}", id);
                        return id;
                    }
                }
                LOGGER.warn("No embedding model found on the custom server; long-term memory needs one (e.g. nomic-embed-text).");
            }
        } catch (Exception e) {
            LOGGER.warn("Could not list models for embedding detection: {}", e.getMessage());
        }
        return fallback;
    }

    private static String getDefaultEmbeddingModel(String provider) {
        return switch (provider.toLowerCase()) {
            case "ollama" -> "nomic-embed-text";
            case "openai" -> "text-embedding-3-small"; // Latest OpenAI embedding model
            case "gemini" -> "text-embedding-004"; // Latest Gemini embedding model
            case "grok", "custom" ->
                // For OpenAI-compatible endpoints (Grok, LM Studio, VLLM, etc.)
                // Use a common embedding model name that most providers support
                    "text-embedding-ada-002";
            default -> "text-embedding-3-small";
        };
    }
}
