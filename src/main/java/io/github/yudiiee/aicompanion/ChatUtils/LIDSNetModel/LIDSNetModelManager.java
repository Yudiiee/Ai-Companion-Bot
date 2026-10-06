package io.github.yudiiee.aicompanion.ChatUtils.LIDSNetModel;

import ai.djl.MalformedModelException;
import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;


public class LIDSNetModelManager {

    private static LIDSNetModelManager instance;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private ZooModel<float[], Classifications> lidsnetModel;
    private Predictor<float[], Classifications> predictor;
    private boolean isModelLoaded = false;
    private volatile boolean isLoadingInProgress = false;
    private static Path modelDir = null;

    private LIDSNetModelManager() {}

    public static LIDSNetModelManager getInstance(Path modelDir) {
        if (instance == null) {
            synchronized (LIDSNetModelManager.class) {
                if (instance == null) {
                    instance = new LIDSNetModelManager();
                }
            }
        }

        LIDSNetModelManager.modelDir = modelDir;

        return instance;
    }

    /**
     * Load the LIDSNet model into memory. Threadsafe and only loads once.
     */
    public void loadModel(List<String> classNames) throws IOException, ModelException, MalformedModelException {
        // Quick check without lock
        if (isModelLoaded) return;

        lock.writeLock().lock();
        try {
            if (isModelLoaded || isLoadingInProgress) return;
            isLoadingInProgress = true;

            Path torchModelDir = modelDir.resolve("LIDSNet_intent_detect.pt"); // adjust path as needed

            LIDSNetTranslator translator = new LIDSNetTranslator(classNames);

            Criteria<float[], Classifications> criteria = Criteria.builder()
                    .setTypes(float[].class, Classifications.class)
                    .optModelPath(torchModelDir)
                    .optEngine("PyTorch")
                    .optTranslator(translator)
                    .optProgress(new ProgressBar())
                    .build();

            lidsnetModel = criteria.loadModel();
            predictor = lidsnetModel.newPredictor();

            isModelLoaded = true;
            System.out.println("LIDSNet model loaded successfully and ready for inference.");

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            isLoadingInProgress = false;
            lock.writeLock().unlock();
        }
    }

    public boolean isModelLoaded() {
        lock.readLock().lock();
        try {
            return isModelLoaded;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Predictor<float[], Classifications> getPredictor(List<String> classNames) throws IOException, ModelException, MalformedModelException {
        lock.readLock().lock();
        try {
            if (!isModelLoaded) {
                lock.readLock().unlock();
                loadModel(classNames);
                lock.readLock().lock();
            }
            return predictor;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Classifications predict(float[] featureVector, List<String> classNames) throws Exception {
        lock.readLock().lock();
        try {
            if (!isModelLoaded) {
                lock.readLock().unlock();
                loadModel(classNames);
                lock.readLock().lock();
            }
            return predictor.predict(featureVector);
        } finally {
            lock.readLock().unlock();
        }
    }

    // Optional: result wrapper
    public PredictionResult predictWithConfidence(float[] featureVector, List<String> classNames) throws Exception {
        Classifications classifications = predict(featureVector, classNames);
        Classifications.Classification bestResult = classifications.best();
        return new PredictionResult(
                bestResult.getClassName(),
                bestResult.getProbability(),
                classifications
        );
    }

    public void unloadModel() throws IOException {
        lock.writeLock().lock();
        try {
            if (!isModelLoaded) {
                System.out.println("Model is not loaded.");
                return;
            }
            System.out.println("Unloading LIDSNet model from memory...");
            if (predictor != null) {
                predictor.close();
                predictor = null;
            }
            if (lidsnetModel != null) {
                lidsnetModel.close();
                lidsnetModel = null;
            }
            isModelLoaded = false;
            System.gc();
            System.out.println("Model unloaded successfully.");
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Simple class to hold prediction results with confidence
    public static class PredictionResult {
        private final String className;
        private final double confidence;
        private final Classifications fullClassifications;

        public PredictionResult(String className, double confidence, Classifications fullClassifications) {
            this.className = className;
            this.confidence = confidence;
            this.fullClassifications = fullClassifications;
        }
        public String getClassName() { return className; }
        public double getConfidence() { return confidence; }
        public double getConfidencePercentage() { return confidence * 100.0; }
        public Classifications getFullClassifications() { return fullClassifications; }
        public boolean isHighConfidence(double threshold) { return confidence >= threshold; }
        @Override
        public String toString() {
            return String.format("PredictionResult{class='%s', confidence=%.4f (%.2f%%)}",
                    className, confidence, getConfidencePercentage());
        }
    }
}
