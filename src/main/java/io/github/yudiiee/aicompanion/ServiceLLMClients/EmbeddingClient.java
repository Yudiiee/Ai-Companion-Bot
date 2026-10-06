package io.github.yudiiee.aicompanion.ServiceLLMClients;

import java.util.List;

/**
 * Base interface for all embedding clients.
 * Provides methods to generate embeddings from text using various LLM providers.
 */
public interface EmbeddingClient {
    /**
     * Generate embeddings for the given text.
     *
     * @param text The text to generate embeddings for
     * @return A list of doubles representing the embedding vector
     * @throws Exception if the API call fails
     */
    List<Double> generateEmbedding(String text) throws Exception;

    /**
     * Get the embedding model name being used by this client.
     *
     * @return The model name (e.g., "text-embedding-3-small", "nomic-embed-text")
     */
    String getEmbeddingModel();

    /**
     * Get the dimension size of the embeddings produced by this model.
     *
     * @return The embedding dimension (e.g., 1536 for OpenAI, 768 for nomic-embed-text)
     */
    int getEmbeddingDimension();

    /**
     * Get the provider name for this embedding client.
     *
     * @return The provider name (e.g., "OpenAI", "Ollama", "Gemini")
     */
    String getProvider();

    /**
     * Check if the embedding service is reachable and functional.
     *
     * @return true if the service is available, false otherwise
     */
    boolean isReachable();
}

