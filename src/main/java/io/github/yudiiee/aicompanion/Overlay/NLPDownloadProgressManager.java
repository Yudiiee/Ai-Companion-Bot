package io.github.yudiiee.aicompanion.Overlay;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the state and progress of NLP model downloads for UI display.
 * Thread-safe implementation for async download operations.
 */
public class NLPDownloadProgressManager {
    private static final AtomicReference<String> currentTask = new AtomicReference<>("Initializing...");
    private static final AtomicInteger totalSteps = new AtomicInteger(0);
    private static final AtomicInteger currentStep = new AtomicInteger(0);
    private static volatile boolean downloading = false;
    private static volatile boolean completed = false;
    private static volatile boolean error = false;
    private static final AtomicReference<String> errorMessage = new AtomicReference<>("");
    private static final AtomicLong completionTime = new AtomicLong(0);
    private static final long AUTO_DISMISS_DELAY_MS = 3000; // 3 seconds

    /**
     * Start the download process
     * @param steps Total number of download/extraction steps
     */
    public static void startDownload(int steps) {
        downloading = true;
        completed = false;
        error = false;
        currentStep.set(0);
        totalSteps.set(steps);
        currentTask.set("Starting NLP model setup...");
        errorMessage.set("");
        completionTime.set(0);
    }

    /**
     * Update current task and progress
     * @param taskName Name of the current task
     * @param step Current step number
     */
    public static void updateProgress(String taskName, int step) {
        currentTask.set(taskName);
        currentStep.set(step);
    }

    /**
     * Mark download as completed
     */
    public static void completeDownload() {
        downloading = false;
        completed = true;
        currentTask.set("All NLP models ready!");
        currentStep.set(totalSteps.get());
        completionTime.set(System.currentTimeMillis());
    }

    /**
     * Mark download as failed
     * @param message Error message
     */
    public static void setError(String message) {
        downloading = false;
        error = true;
        errorMessage.set(message);
        currentTask.set("Error: " + message);
    }

    /**
     * Reset the download state (for retries)
     */
    public static void reset() {
        downloading = false;
        completed = false;
        error = false;
        currentStep.set(0);
        totalSteps.set(0);
        currentTask.set("");
        errorMessage.set("");
    }

    // Getters
    public static boolean isDownloading() {
        return downloading;
    }

    public static boolean isCompleted() {
        return completed;
    }

    public static boolean hasError() {
        return error;
    }

    public static String getCurrentTask() {
        return currentTask.get();
    }

    public static int getCurrentStep() {
        return currentStep.get();
    }

    public static int getTotalSteps() {
        return totalSteps.get();
    }

    public static String getErrorMessage() {
        return errorMessage.get();
    }

    /**
     * Get progress percentage (0-100)
     */
    public static float getProgressPercentage() {
        int total = totalSteps.get();
        if (total == 0) return 0f;
        return ((float) currentStep.get() / total) * 100f;
    }

    /**
     * Check if any download activity is happening or has happened
     * Auto-dismisses after 3 seconds of completion
     */
    public static boolean hasActivity() {
        // If completed, check if auto-dismiss delay has passed
        if (completed && completionTime.get() > 0) {
            long elapsedTime = System.currentTimeMillis() - completionTime.get();
            if (elapsedTime > AUTO_DISMISS_DELAY_MS) {
                return false; // Auto-dismiss
            }
        }

        return downloading || completed || error;
    }

    /**
     * Check if should show completion message (within dismiss window)
     */
    public static boolean shouldShowCompletion() {
        if (!completed || completionTime.get() == 0) return false;
        long elapsedTime = System.currentTimeMillis() - completionTime.get();
        return elapsedTime <= AUTO_DISMISS_DELAY_MS;
    }
}

