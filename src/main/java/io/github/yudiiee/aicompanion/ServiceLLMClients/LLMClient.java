package io.github.yudiiee.aicompanion.ServiceLLMClients;

// Base interface for all LLM clients
public interface LLMClient {
    /**
     * Send a prompt to the LLM and receive the response.
     * @param systemPrompt The system prompt
     * @param userPrompt The input prompt
     * @return The LLM's response as a string
     */
    String sendPrompt(String systemPrompt, String userPrompt);

    /**
     * Checks if the LLM service is reachable and the API key is valid.
     * This is a lightweight check and does not involve a full chat completion.
     * @return true if the service is reachable, false otherwise.
     */
    boolean isReachable();

    /**
     * Fetches the name of the LLM client's provider.
     */
    String getProvider();

    /**
     * Optional: If the provider supports streaming responses.
     * @param systemPrompt The system prompt
     * @param userPrompt The input prompt
     * @param callback Function to handle streaming chunks
     */
    default void sendPromptStreaming(String systemPrompt, String userPrompt, java.util.function.Consumer<String> callback) {
        // Default: not supported
        callback.accept(sendPrompt(systemPrompt, userPrompt));
    }
}

