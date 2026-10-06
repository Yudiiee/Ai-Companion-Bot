package io.github.yudiiee.aicompanion.ServiceLLMClients;

import java.util.List;

/**
 * Interface for fetching a list of available models from a language model provider.
 */
public interface ModelFetcher {

    /**
     * Fetches a list of available model identifiers from the provider's API.
     * @param apiKey The API key required to authenticate with the service.
     * @return A list of model identifiers as strings.
     */
    List<String> fetchModels(String apiKey);
}

