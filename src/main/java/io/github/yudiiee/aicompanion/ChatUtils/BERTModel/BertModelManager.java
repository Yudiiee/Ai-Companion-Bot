package io.github.yudiiee.aicompanion.ChatUtils.BERTModel;

import ai.djl.MalformedModelException;
import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class BertModelManager {

    private static BertModelManager instance;

    // Thread-safe locks for concurrent access
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private ZooModel<String, Classifications> bertModel;
    private Predictor<String, Classifications> predictor;
    private boolean isModelLoaded = false;
    private volatile boolean isLoadingInProgress = false;  // Add this flag

    private BertModelManager() {}

    public static BertModelManager getInstance() {
        if (instance == null) {
            synchronized (BertModelManager.class) {
                if (instance == null) {
                    instance = new BertModelManager();
                }
            }
        }
        return instance;
    }

    /**
     * Load the model into memory. This method is thread-safe and will only load once.
     * The model stays in memory until explicitly unloaded.
     */
    /** When loading last failed (e.g. model files not downloaded yet); avoids retrying on every chat line. */
    private static volatile long lastLoadFailure = 0L;

    public void loadModel() throws IOException, ModelException, MalformedModelException {
        // Quick check without locking
        if (isModelLoaded) {
            return;
        }

        lock.writeLock().lock();
        try {
            // Double-check with lock
            if (isModelLoaded || isLoadingInProgress) {
                return;
            }

            isLoadingInProgress = true;


            Path configDir = FabricLoader.getInstance().getConfigDir();
            Path modelDir = configDir.resolve("ai-companion/NLPModels");
            Path torchModelDir = modelDir.resolve("distilbert-finetuned-intent-torchscript/");

            // Create the translator with your class labels
            BertTranslator translator = new BertTranslator(
                    List.of("ASK_INFORMATION", "GENERAL_CONVERSATION", "REQUEST_ACTION")
            );

            Criteria<String, Classifications> criteria = Criteria.builder()
                    .setTypes(String.class, Classifications.class)
                    .optModelPath(torchModelDir)
                    .optEngine("PyTorch") // PyTorch, not "Pytorch"
                    .optTranslator(translator)
                    .optProgress(new ProgressBar())
                    .build();

            bertModel = criteria.loadModel();
            predictor = bertModel.newPredictor();

            // Initialize the translator with the model
            translator.prepare(bertModel.getNDManager(), bertModel);

            isModelLoaded = true;
            System.out.println("Model loaded successfully and ready for inference.");

        } catch (Exception e) {
            lastLoadFailure = System.currentTimeMillis();
            throw new RuntimeException(e);
        } finally {
            isLoadingInProgress = false;
            lock.writeLock().unlock();
        }
    }

    /**
     * Check if the model is currently loaded in memory
     */
    public boolean isModelLoaded() {
        lock.readLock().lock();
        try {
            return isModelLoaded;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Check if model is currently being loaded
     */
    public boolean isLoadingInProgress() {
        return isLoadingInProgress;
    }

    /**
     * Get the predictor for making predictions.
     * Automatically loads the model if not already loaded.
     */
    public Predictor<String, Classifications> getPredictor() throws IOException, ModelException, MalformedModelException {
        if (!isModelLoaded) {
            loadModel(); // acquires the write lock itself
        }
        lock.readLock().lock();
        try {
            return predictor;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Make a prediction using the loaded model
     */
    public Classifications predict(String text) throws Exception {
        if (!isModelLoaded && System.currentTimeMillis() - lastLoadFailure < 60_000L) {
            throw new IllegalStateException("BERT model not available yet");
        }
        if (!isModelLoaded) {
            loadModel(); // acquires the write lock itself; throws if the model isn't available
        }
        lock.readLock().lock();
        try {
            if (predictor == null) throw new IllegalStateException("BERT model is not loaded");
            return predictor.predict(text);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Make a prediction and return a simple result with class name and confidence
     */
    public PredictionResult predictWithConfidence(String text) throws Exception {
        Classifications classifications = predict(text);
        Classifications.Classification bestResult = classifications.best();

        return new PredictionResult(
                bestResult.getClassName(),
                bestResult.getProbability(),
                classifications
        );
    }

    /**
     * Get top N predictions with their confidence scores
     */
    public List<PredictionResult> getTopPredictions(String text, int topN) throws Exception {
        Classifications classifications = predict(text);
        List<Classifications.Classification> topResults = classifications.topK(topN);

        return topResults.stream()
                .map(result -> new PredictionResult(
                        result.getClassName(),
                        result.getProbability(),
                        classifications
                ))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Unload the model from memory to free up resources.
     * Call this when you're completely done with all NLP tasks.
     */
    public void unloadModel() throws IOException {
        lock.writeLock().lock();
        try {
            if (!isModelLoaded) {
                System.out.println("Model is not loaded.");
                return;
            }

            System.out.println("Unloading model from memory...");

            if (predictor != null) {
                predictor.close();
                predictor = null;
            }

            if (bertModel != null) {
                bertModel.close();
                bertModel = null;
            }

            isModelLoaded = false;

            // Suggest garbage collection (JVM will decide)
            System.gc();

            System.out.println("Model unloaded successfully.");

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Legacy method for backward compatibility
     */
    @Deprecated
    public void closeModel() throws IOException {
        unloadModel();
    }

    /**
     * Simple class to hold prediction results with confidence
     */
    public static class PredictionResult {
        private final String className;
        private final double confidence;
        private final Classifications fullClassifications;

        public PredictionResult(String className, double confidence, Classifications fullClassifications) {
            this.className = className;
            this.confidence = confidence;
            this.fullClassifications = fullClassifications;
        }

        public String getClassName() {
            return className;
        }

        public double getConfidence() {
            return confidence;
        }

        public double getConfidencePercentage() {
            return confidence * 100.0;
        }

        public Classifications getFullClassifications() {
            return fullClassifications;
        }

        public boolean isHighConfidence(double threshold) {
            return confidence >= threshold;
        }

        @Override
        public String toString() {
            return String.format("PredictionResult{class='%s', confidence=%.4f (%.2f%%)}",
                    className, confidence, getConfidencePercentage());
        }
    }
}