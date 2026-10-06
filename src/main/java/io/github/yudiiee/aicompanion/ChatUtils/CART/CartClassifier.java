package io.github.yudiiee.aicompanion.ChatUtils.CART;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;

public class CartClassifier {
    private final TreeNode root;
    private final Map<Integer, String> labelMap;
    private final Set<String> vocabulary;

    // Original constructor - make sure files are in correct order!
    public CartClassifier(File treeFile, File labelsFile, File vocabFile) throws IOException {
        System.out.println("Loading tree from: " + treeFile.getName());
        System.out.println("Loading labels from: " + labelsFile.getName());
        System.out.println("Loading vocab from: " + vocabFile.getName());

        this.root = loadTree(treeFile);
        this.labelMap = loadLabelMap(labelsFile);
        this.vocabulary = loadVocab(vocabFile);
    }

    // Alternative constructor with named parameters (safer)
    public static CartClassifier create(String treeFilePath, String labelsFilePath, String vocabFilePath) throws IOException {
        return new CartClassifier(
                new File(treeFilePath),
                new File(labelsFilePath),
                new File(vocabFilePath)
        );
    }

    // Vectorizes the input using the training vocabulary (term frequency)
    public Map<String, Double> vectorize(String inputText) {
        Map<String, Double> vector = new HashMap<>();

        for (String word : vocabulary) {
            vector.put(word, 0.0);
        }

        String[] tokens = inputText.toLowerCase().replaceAll("[^a-z0-9 ]", "").split("\\s+");

        for (String token : tokens) {
            if (vocabulary.contains(token)) {
                vector.put(token, vector.get(token) + 1.0);
            }
        }

        return vector;
    }

    // Main classification method
    public ClassificationResult classify(String input) {
        Map<String, Double> features = vectorize(input);
        Leaf leaf = traverseTree(root, features);

        String label = labelMap.getOrDefault(leaf.label, "unknown");
        return new ClassificationResult(label, leaf.confidence);
    }

    // Tree traversal based on input features
    private Leaf traverseTree(TreeNode node, Map<String, Double> features) {
        while (!node.isLeaf()) {
            double featureValue = features.getOrDefault(node.feature, 0.0);
            node = (featureValue <= node.threshold) ? node.left : node.right;
        }
        return node.getLeaf();
    }

    // Tree loading from JSON - this should ONLY be called on the tree file!
    private TreeNode loadTree(File treeFile) throws IOException {
        // Validate that this looks like a tree file
        try (Reader reader = new FileReader(treeFile)) {
            // Read first few characters to check if it starts with tree structure
            char[] buffer = new char[100];
            int charsRead = reader.read(buffer);
            String start = new String(buffer, 0, charsRead);

            if (!start.contains("\"type\":") || !start.contains("\"split\"")) {
                throw new IOException("File " + treeFile.getName() + " does not appear to be a tree file. Expected to find 'type' and 'split' fields.");
            }
        }

        Gson gson = new GsonBuilder()
                .registerTypeAdapter(TreeNode.class, new TreeNodeDeserializer())
                .create();

        try (Reader reader = new FileReader(treeFile)) {
            return gson.fromJson(reader, TreeNode.class);
        }
    }

    // Load labels as a list, then convert to map with indices
    private Map<Integer, String> loadLabelMap(File labelsFile) throws IOException {
        Gson gson = new Gson();
        try (Reader reader = new FileReader(labelsFile)) {
            Type listType = new TypeToken<List<String>>() {}.getType();
            List<String> labelsList = gson.fromJson(reader, listType);

            Map<Integer, String> labelMap = new HashMap<>();
            for (int i = 0; i < labelsList.size(); i++) {
                labelMap.put(i, labelsList.get(i));
            }
            return labelMap;
        }
    }

    // Load vocabulary properly
    private Set<String> loadVocab(File vocabFile) throws IOException {
        Gson gson = new Gson();
        try (Reader reader = new FileReader(vocabFile)) {
            Type type = new TypeToken<Map<String, Integer>>() {}.getType();
            Map<String, Integer> vocab = gson.fromJson(reader, type);
            return vocab.keySet();
        }
    }

    // Polymorphic TreeNode supporting both split and leaf nodes
    public static class TreeNode {
        String type;               // "split" or "leaf"
        String feature;            // only if type == "split"
        double threshold;          // only if type == "split"
        TreeNode left;             // only if type == "split"
        TreeNode right;            // only if type == "split"
        Integer label;             // only if type == "leaf"
        Double confidence;         // only if type == "leaf"

        boolean isLeaf() {
            return "leaf".equalsIgnoreCase(type);
        }

        Leaf getLeaf() {
            return new Leaf(label != null ? label : -1, confidence != null ? confidence : 0.0);
        }
    }

    public static class Leaf {
        int label;
        double confidence;

        Leaf(int label, double confidence) {
            this.label = label;
            this.confidence = confidence;
        }
    }

    public static class ClassificationResult {
        public final String label;
        public final double confidence;

        public ClassificationResult(String label, double confidence) {
            this.label = label;
            this.confidence = confidence;
        }
    }
}