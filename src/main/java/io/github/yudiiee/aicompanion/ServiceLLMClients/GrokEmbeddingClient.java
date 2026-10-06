package io.github.yudiiee.aicompanion.ServiceLLMClients;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Grok (xAI) embedding client using xAI's embedding API.
 * Uses OpenAI-compatible API format.
 */
public class GrokEmbeddingClient implements EmbeddingClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("Grok-Embedding-Client");
    private static final String EMBEDDING_ENDPOINT = "https://api.x.ai/v1/embeddings";
    private static final String DEFAULT_MODEL = "embedding-large-1"; // xAI's embedding model

    private final String apiKey;
    private final String modelName;
    private final HttpClient client;

    public GrokEmbeddingClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public GrokEmbeddingClient(String apiKey, String modelName) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public List<Double> generateEmbedding(String text) throws Exception {
        try {
            // Build request body (OpenAI-compatible)
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);
            requestBody.addProperty("input", text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(EMBEDDING_ENDPOINT))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new Exception("xAI Embedding API error: " + response.statusCode() + " - " + response.body());
            }

            // Parse response (OpenAI-compatible format)
            JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray embeddingArray = jsonResponse.getAsJsonArray("data")
                    .get(0).getAsJsonObject()
                    .getAsJsonArray("embedding");

            List<Double> embedding = new ArrayList<>();
            for (int i = 0; i < embeddingArray.size(); i++) {
                embedding.add(embeddingArray.get(i).getAsDouble());
            }

            return embedding;

        } catch (Exception e) {
            LOGGER.error("Error generating xAI embedding: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public String getEmbeddingModel() {
        return modelName;
    }

    @Override
    public int getEmbeddingDimension() {
        // embedding-large-1 produces 1536-dimensional embeddings (similar to OpenAI)
        return 1536;
    }

    @Override
    public String getProvider() {
        return "xAI (Grok)";
    }

    @Override
    public boolean isReachable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(EMBEDDING_ENDPOINT))
                    .header("Authorization", "Bearer " + apiKey)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }
}

