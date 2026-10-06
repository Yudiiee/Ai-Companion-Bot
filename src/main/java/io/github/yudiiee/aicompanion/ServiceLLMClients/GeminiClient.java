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

public class GeminiClient implements LLMClient {
    private final String apiKey;
    private final String modelName;
    private final HttpClient client;
    public static final Logger LOGGER = LoggerFactory.getLogger("Gemini-Client");

    public GeminiClient(String apiKey, String modelName) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public String sendPrompt(String systemPrompt, String userPrompt) {
        try {
            JsonObject requestBody = new JsonObject();
            JsonArray contents = new JsonArray();

            // 1. Combine the system and user prompts into a single user message
            JsonObject userPart = new JsonObject();
            userPart.addProperty("role", "user");

            JsonArray partsArray = new JsonArray();
            JsonObject combinedTextPart = new JsonObject();
            // Combining them is one of the ways to send system and user prompts
            combinedTextPart.addProperty("text", systemPrompt + "\n\n" + userPrompt);
            partsArray.add(combinedTextPart);

            userPart.add("parts", partsArray);
            contents.add(userPart);
            requestBody.add("contents", contents);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":generateContent?key=" + apiKey))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "Error: " + response.statusCode() + " - " + response.body();
            }

            JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
            return jsonResponse.getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();

        } catch (Exception e) {
            LOGGER.error("Error in Gemini Client: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Checks if the Gemini API is reachable and the key is valid by making a
     * quick, lightweight request to the models endpoint.
     *
     * @return true if the API returns a 200 status code, false otherwise.
     */
    @Override
    public boolean isReachable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models?key=" + apiKey))
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
        return "Google Gemini";
    }
}
