package io.github.yudiiee.aicompanion.Network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.yudiiee.aicompanion.AICompanion;

import java.util.List;
import java.util.Map;

public class ConfigJsonUtil {

    public static String configToJson() {
        // Retrieve config values from the generated CONFIG
        List<String> modelList = AICompanion.CONFIG.getModelList();
        String selectedLanguageModel = AICompanion.CONFIG.getSelectedLanguageModel();
        Map<String, String> botGameProfile = AICompanion.CONFIG.getBotGameProfile();

        // Build JSON using Gson's JsonObject and JsonArray
        JsonObject root = new JsonObject();

        // Add modelList as a JSON array
        JsonArray modelsArray = new JsonArray();
        for (String model : modelList) {
            modelsArray.add(model);
        }
        root.add("modelList", modelsArray);

        // Add selectedLanguageModel as a property
        root.addProperty("selectedLanguageModel", selectedLanguageModel);

        // Add BotGameProfile as a JSON object
        JsonObject profileObject = new JsonObject();
        for (Map.Entry<String, String> entry : botGameProfile.entrySet()) {
            profileObject.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("BotGameProfile", profileObject);

        // Return the JSON string (pretty printing optional)
        return root.toString();
    }
}
