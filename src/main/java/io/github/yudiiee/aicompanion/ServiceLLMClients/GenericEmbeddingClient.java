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
 * Generic embedding client for custom OpenAI-compatible endpoints.
 * Works with any provider that follows the OpenAI embeddings API format.
 */
public class GenericEmbeddingClient implements EmbeddingClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("Generic-Embedding-Client");
    private static final String DEFAULT_MODEL = "text-embedding-3-small";

    private final String apiKey;
    private final String modelName;
    private final String apiUrl;
    private final HttpClient client;

    public GenericEmbeddingClient(String apiKey, String modelName, String apiUrl) {
        this.apiKey = apiKey;
        this.modelName = modelName != null && !modelName.isEmpty() ? modelName : DEFAULT_MODEL;
        // Ensure the URL ends with /embeddings
        this.apiUrl = apiUrl.endsWith("/embeddings") ? apiUrl : apiUrl + "/embeddings";
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public List<Double> generateEmbedding(String text) throws Exception {
        try {
            // Build request body (OpenAI-compatible format)
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);
            requestBody.addProperty("input", text);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()));

            // Add authorization header if API key is provided
            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new Exception("Custom Embedding API error: " + response.statusCode() + " - " + response.body());
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
            LOGGER.error("Error generating custom embedding: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public String getEmbeddingModel() {
        return modelName;
    }

    @Override
    public int getEmbeddingDimension() {
        // Default to 1536 for OpenAI-compatible endpoints
        // This may vary by provider - could be made configurable in the future
        return 1536;
    }

    @Override
    public String getProvider() {
        return "Custom (" + apiUrl + ")";
    }

    @Override
    public boolean isReachable() {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody());

            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }
}

