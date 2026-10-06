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
 * OpenAI embedding client that generates embeddings using OpenAI's embedding API.
 * Supports models like text-embedding-3-small, text-embedding-3-large, text-embedding-ada-002
 */
public class OpenAIEmbeddingClient implements EmbeddingClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("OpenAI-Embedding-Client");
    private static final String EMBEDDING_ENDPOINT = "https://api.openai.com/v1/embeddings";
    private static final String DEFAULT_MODEL = "text-embedding-3-small"; // Cheapest and fastest

    private final String apiKey;
    private final String modelName;
    private final HttpClient client;

    public OpenAIEmbeddingClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public OpenAIEmbeddingClient(String apiKey, String modelName) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public List<Double> generateEmbedding(String text) throws Exception {
        try {
            // Build request body
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
                throw new Exception("OpenAI Embedding API error: " + response.statusCode() + " - " + response.body());
            }

            // Parse response
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
            LOGGER.error("Error generating OpenAI embedding: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public String getEmbeddingModel() {
        return modelName;
    }

    @Override
    public int getEmbeddingDimension() {
        // Return dimensions based on model
        switch (modelName) {
            case "text-embedding-3-small":
                return 1536;
            case "text-embedding-3-large":
                return 3072;
            case "text-embedding-ada-002":
                return 1536;
            default:
                return 1536; // Default fallback
        }
    }

    @Override
    public String getProvider() {
        return "OpenAI";
    }

    @Override
    public boolean isReachable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}

