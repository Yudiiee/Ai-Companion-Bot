package io.github.yudiiee.aicompanion.ServiceLLMClients;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

public class GeminiModelFetcher implements ModelFetcher {

    private final HttpClient client;

    public GeminiModelFetcher() {
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public List<String> fetchModels(String apiKey) {
        List<String> modelList = new ArrayList<>();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return modelList;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models?key=" + apiKey))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray models = jsonResponse.getAsJsonArray("models");

                for (JsonElement modelElement : models) {
                    JsonObject modelObject = modelElement.getAsJsonObject();
                    String modelName = modelObject.get("name").getAsString();
                    // Gemini models start with "models/"
                    String modelId = modelName.startsWith("models/") ? modelName.substring(7) : modelName;

                    // Filter for generative models
                    if (modelObject.has("supportedGenerationMethods")) {
                        JsonArray methods = modelObject.getAsJsonArray("supportedGenerationMethods");
                        for (JsonElement method : methods) {
                            if (method.getAsString().equals("generateContent")) {
                                modelList.add(modelId);
                                break;
                            }
                        }
                    }
                }
            } else {
                // Log the error
                System.err.println("Error fetching Gemini models: " + response.statusCode() + " - " + response.body());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return modelList;
    }
}
