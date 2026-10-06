package io.github.yudiiee.aicompanion.ServiceLLMClients;

import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;
import io.github.amithkoujalgi.ollama4j.core.types.OllamaModelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Ollama embedding client that wraps the ollama4j library.
 * Uses nomic-embed-text model by default for embeddings.
 */
public class OllamaEmbeddingClient implements EmbeddingClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("Ollama-Embedding-Client");
    private static final String DEFAULT_MODEL = "nomic-embed-text";

    private final OllamaAPI ollamaAPI;
    private final String modelName;

    public OllamaEmbeddingClient(OllamaAPI ollamaAPI) {
        this(ollamaAPI, DEFAULT_MODEL);
    }

    public OllamaEmbeddingClient(OllamaAPI ollamaAPI, String modelName) {
        this.ollamaAPI = ollamaAPI;
        this.modelName = modelName;
    }

    @Override
    public List<Double> generateEmbedding(String text) throws Exception {
        try {
            // Use the Ollama4j library to generate embeddings
            // OllamaModelType is a String constant, not an enum
            String model = modelName != null && !modelName.isEmpty() ? modelName : OllamaModelType.NOMIC_EMBED_TEXT;
            return ollamaAPI.generateEmbeddings(model, text);
        } catch (Exception e) {
            LOGGER.error("Error generating Ollama embedding: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public String getEmbeddingModel() {
        return modelName;
    }

    @Override
    public int getEmbeddingDimension() {
        // nomic-embed-text produces 768-dimensional embeddings
        return 768;
    }

    @Override
    public String getProvider() {
        return "Ollama";
    }

    @Override
    public boolean isReachable() {
        try {
            ollamaAPI.listModels();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

