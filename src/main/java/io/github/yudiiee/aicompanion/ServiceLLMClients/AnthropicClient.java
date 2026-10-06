package io.github.yudiiee.aicompanion.ServiceLLMClients;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

public class AnthropicClient implements LLMClient {
    private final String apiKey;
    private final String modelName;
    private final HttpClient client;
    public static final Logger LOGGER = LoggerFactory.getLogger("Anthropic-Client");

    public AnthropicClient(String apiKey, String modelName) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public String sendPrompt(String systemPrompt, String userPrompt) {
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", this.modelName);

            JsonArray messages = new JsonArray();

            // 1. Create the system message object and add it to the array
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", systemPrompt);
            messages.add(systemMessage);

            // 2. Create the user message object and add it to the array
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");
            userMessage.addProperty("content", userPrompt);
            messages.add(userMessage);

            requestBody.add("messages", messages);
            requestBody.addProperty("max_tokens", 1024);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01") // Required header
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "Error: " + response.statusCode() + " - " + response.body();
            }

            JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
            return jsonResponse.getAsJsonArray("content")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();

        } catch (Exception e) {
            LOGGER.error("Error in Anthropic Client: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Checks if the Claude API is reachable and the key is valid by making a
     * quick, lightweight request to the models endpoint.
     *
     * @return true if the API returns a 200 status code, false otherwise.
     */
    @Override
    public boolean isReachable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/models"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getProvider() {
        return "Anthropic";
    }


}
