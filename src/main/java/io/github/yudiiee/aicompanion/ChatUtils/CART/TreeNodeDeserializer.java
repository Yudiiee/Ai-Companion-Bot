package io.github.yudiiee.aicompanion.ChatUtils.CART;

import com.google.gson.*;

import java.lang.reflect.Type;

public class TreeNodeDeserializer implements JsonDeserializer<CartClassifier.TreeNode> {

    @Override
    public CartClassifier.TreeNode deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        JsonObject obj = json.getAsJsonObject();
        CartClassifier.TreeNode node = new CartClassifier.TreeNode();

        JsonElement typeElem = obj.get("type");
        if (typeElem == null || typeElem.isJsonNull()) {
            throw new JsonParseException("Missing or invalid 'type' field in node: " + obj);
        }

        String nodeType = typeElem.getAsString();

        if ("leaf".equals(nodeType)) {
            node.type = "leaf";

            // Handle both "class" and "label" field names
            JsonElement classElem = obj.get("class");
            if (classElem != null && !classElem.isJsonNull()) {
                node.label = classElem.getAsInt();
            } else {
                JsonElement labelElem = obj.get("label");
                if (labelElem != null && !labelElem.isJsonNull()) {
                    node.label = labelElem.getAsInt();
                } else {
                    throw new JsonParseException("Missing 'class' or 'label' field in leaf node");
                }
            }

            JsonElement confElem = obj.get("confidence");
            if (confElem != null && !confElem.isJsonNull()) {
                node.confidence = confElem.getAsDouble();
            } else {
                throw new JsonParseException("Missing 'confidence' field in leaf node");
            }

        } else if ("split".equals(nodeType)) {
            node.type = "split";

            JsonElement featureElem = obj.get("feature");
            if (featureElem != null && !featureElem.isJsonNull()) {
                node.feature = featureElem.getAsString();
            } else {
                throw new JsonParseException("Missing 'feature' field in split node");
            }

            JsonElement thresholdElem = obj.get("threshold");
            if (thresholdElem != null && !thresholdElem.isJsonNull()) {
                node.threshold = thresholdElem.getAsDouble();
            } else {
                throw new JsonParseException("Missing 'threshold' field in split node");
            }

            JsonElement leftElem = obj.get("left");
            JsonElement rightElem = obj.get("right");

            if (leftElem != null && !leftElem.isJsonNull()) {
                node.left = deserialize(leftElem, typeOfT, context);
            } else {
                throw new JsonParseException("Missing 'left' child in split node");
            }

            if (rightElem != null && !rightElem.isJsonNull()) {
                node.right = deserialize(rightElem, typeOfT, context);
            } else {
                throw new JsonParseException("Missing 'right' child in split node");
            }

        } else {
            throw new JsonParseException("Unknown node type: " + nodeType);
        }

        return node;
    }
}