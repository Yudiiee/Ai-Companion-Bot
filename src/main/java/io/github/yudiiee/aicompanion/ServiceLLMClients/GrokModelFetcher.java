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

public class GrokModelFetcher implements ModelFetcher {

    private final HttpClient client;

    public GrokModelFetcher() {
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
                    .uri(URI.create("https://api.x.ai/v1/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray models = jsonResponse.getAsJsonArray("data");

                for (JsonElement modelElement : models) {
                    JsonObject modelObject = modelElement.getAsJsonObject();
                    modelList.add(modelObject.get("id").getAsString());
                }
            } else {
                System.err.println("Error fetching Grok models: " + response.statusCode() + " - " + response.body());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return modelList;
    }
}
