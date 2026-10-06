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
 * Google Gemini embedding client using Google's text-embedding models.
 */
public class GeminiEmbeddingClient implements EmbeddingClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("Gemini-Embedding-Client");
    private static final String EMBEDDING_ENDPOINT_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/%s:embedContent";
    private static final String DEFAULT_MODEL = "text-embedding-004"; // Latest embedding model

    private final String apiKey;
    private final String modelName;
    private final HttpClient client;

    public GeminiEmbeddingClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public GeminiEmbeddingClient(String apiKey, String modelName) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public List<Double> generateEmbedding(String text) throws Exception {
        try {
            // Build request body for Gemini
            JsonObject content = new JsonObject();
            JsonObject parts = new JsonObject();
            parts.addProperty("text", text);

            JsonArray partsArray = new JsonArray();
            partsArray.add(parts);

            content.add("parts", partsArray);

            JsonObject requestBody = new JsonObject();
            requestBody.add("content", content);

            String endpoint = String.format(EMBEDDING_ENDPOINT_TEMPLATE, modelName) + "?key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new Exception("Gemini Embedding API error: " + response.statusCode() + " - " + response.body());
            }

            // Parse response
            JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray embeddingArray = jsonResponse.getAsJsonObject("embedding")
                    .getAsJsonArray("values");

            List<Double> embedding = new ArrayList<>();
            for (int i = 0; i < embeddingArray.size(); i++) {
                embedding.add(embeddingArray.get(i).getAsDouble());
            }

            return embedding;

        } catch (Exception e) {
            LOGGER.error("Error generating Gemini embedding: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public String getEmbeddingModel() {
        return modelName;
    }

    @Override
    public int getEmbeddingDimension() {
        // text-embedding-004 produces 768-dimensional embeddings
        return 768;
    }

    @Override
    public String getProvider() {
        return "Google Gemini";
    }

    @Override
    public boolean isReachable() {
        try {
            // Simple test with minimal text
            generateEmbedding("test");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

