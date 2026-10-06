package io.github.yudiiee.aicompanion.Database;


import net.fabricmc.loader.api.FabricLoader;
import io.github.yudiiee.aicompanion.LauncherDetection.LauncherEnvironment;
import io.github.yudiiee.aicompanion.GameAI.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;


public class QTableStorage {
    private static final String gameDir = FabricLoader.getInstance().getGameDir().toString();
    private static final Logger LOGGER = LoggerFactory.getLogger("QTableStorage");

    /**
     * Setup Q-table storage with launcher detection
     */
    public static void setupQTableStorage() {
        String[] fallbackDirs = LauncherEnvironment.getFallbackDirectories("qtable_storage");

        LOGGER.info("Setting up Q-table storage...");
        LOGGER.info(LauncherEnvironment.getLauncherInfo());

        for (int i = 0; i < fallbackDirs.length; i++) {
            try {
                File tableDir = new File(fallbackDirs[i]);

                if (!tableDir.exists()) {
                    if (tableDir.mkdirs()) {
                        LOGGER.info("✅ Q-table storage directory created: {}", fallbackDirs[i]);
                        return;
                    } else {
                        LOGGER.warn("❌ Failed to create directory: {}", fallbackDirs[i]);
                    }
                } else {
                    LOGGER.info("✅ Q-table storage directory exists: {}", fallbackDirs[i]);
                    return;
                }
            } catch (Exception e) {
                LOGGER.error("Error setting up directory {}: {}", fallbackDirs[i], e.getMessage());
                if (i == fallbackDirs.length - 1) {
                    LOGGER.error("❌ Failed to setup Q-table storage in any location!");
                }
            }
        }
    }

    /**
     * Get the working Q-table directory
     */
    public static String getQTableDirectory() {
        String[] fallbackDirs = LauncherEnvironment.getFallbackDirectories("qtable_storage");

        for (String dir : fallbackDirs) {
            File directory = new File(dir);
            if (directory.exists() && directory.canWrite()) {
                return dir;
            }
        }

        // If none exist, try to create the first one
        try {
            Files.createDirectories(Paths.get(fallbackDirs[0]));
            return fallbackDirs[0];
        } catch (Exception e) {
            LOGGER.error("Failed to create primary directory, using temp: {}", e.getMessage());
            return System.getProperty("java.io.tmpdir") + File.separator + "qtable_storage";
        }
    }

    /**
     * Enhanced Q-table loading with multiple location support
     */
    public static QTable loadQTable() {
        String[] possiblePaths = LauncherEnvironment.getFallbackDirectories("qtable_storage");

        for (String dir : possiblePaths) {
            String filePath = dir + File.separator + "qtable.bin";
            File file = new File(filePath);

            if (file.exists()) {
                try {
                    QTable loadedTable = load(filePath);
                    LOGGER.info("✅ Q-table loaded from: {}", filePath);
                    return loadedTable;
                } catch (Exception e) {
                    LOGGER.warn("❌ Failed to load Q-table from {}: {}", filePath, e.getMessage());
                }
            }
        }

        LOGGER.info("📝 No existing Q-table found, creating new one");
        return new QTable();
    }

    /**
     * Enhanced Q-table saving with fallback support
     */
    public static void saveQTable(QTable qTable, String fileName) {
        if (fileName == null) {
            fileName = "qtable.bin";
        }

        String workingDir = getQTableDirectory();
        String filePath = workingDir + File.separator + fileName;

        try {
            // Ensure directory exists
            Files.createDirectories(Paths.get(workingDir));

            try (FileOutputStream fos = new FileOutputStream(filePath);
                 ObjectOutputStream oos = new ObjectOutputStream(fos)) {

                oos.writeObject(qTable);
                LOGGER.info("✅ Q-table saved to: {}", filePath);

            }
        } catch (IOException e) {
            LOGGER.error("❌ Failed to save Q-table to {}: {}", filePath, e.getMessage());

            // Try fallback locations
            String[] fallbackDirs = LauncherEnvironment.getFallbackDirectories("qtable_storage");
            for (int i = 1; i < fallbackDirs.length; i++) {
                String fallbackPath = fallbackDirs[i] + File.separator + fileName;
                try {
                    Files.createDirectories(Paths.get(fallbackDirs[i]));
                    try (FileOutputStream fos = new FileOutputStream(fallbackPath);
                         ObjectOutputStream oos = new ObjectOutputStream(fos)) {
                        oos.writeObject(qTable);
                        LOGGER.info("✅ Q-table saved to fallback location: {}", fallbackPath);
                        return;
                    }
                } catch (Exception e2) {
                    LOGGER.warn("❌ Fallback save failed for {}: {}", fallbackPath, e2.getMessage());
                }
            }
        }
    }



    /**
     * Saves the global epsilon value to a file.
     */
    public static void saveEpsilon(double epsilon, String filePath) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(filePath))) {
            dos.writeDouble(epsilon);
        }
    }


    /**
     *  Saves the last known state of the rlAgent during training to a file, to be used across training sessions and bot respawns.
     */
    public static void saveLastKnownState(State lastKnownState, String filePath) {
        if (lastKnownState == null) {
            System.out.println("No lastKnownState to save.");
            return;
        }

        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filePath))) {
            oos.writeObject(lastKnownState);
            System.out.println("lastKnownState saved to " + filePath);
        } catch (IOException e) {
            System.err.println("Failed to save lastKnownState: " + e.getMessage());
            System.out.println(e.getMessage());
        }
    }

    /**
     * Loads the last known state of the rlAgent from a file, to be used across training sessions and bot respawns
     */
    public static State loadLastKnownState(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("No saved lastKnownState found.");
            return null;
        }

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            State lastKnownState = (State) ois.readObject();
            System.out.println("lastKnownState loaded from " + filePath);
            return lastKnownState;
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Failed to load lastKnownState: " + e.getMessage());
            System.out.println(e.getMessage());
            return null;
        }
    }



    /**
     * Loads the global epsilon value from a file.
     */
    public static double loadEpsilon(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            return 1.0; // Default epsilon
        }

        try (DataInputStream dis = new DataInputStream(new FileInputStream(filePath))) {
            return dis.readDouble();
        }
    }



    /**
     * Load the QTable from a binary file.
     *
     * @param filePath The path to the file where the QTable is stored.
     * @return The loaded QTable object.
     * @throws IOException If an I/O error occurs.
     * @throws ClassNotFoundException If the class for the serialized object cannot be found.
     */
    public static QTable load(String filePath) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(filePath))) {
            QTable loadedQTable = (QTable) ois.readObject();
            LOGGER.info("✅ Q-table loaded successfully from: {}", filePath);
            return loadedQTable;
        } catch (IOException | ClassNotFoundException e) {
            LOGGER.error("❌ Error loading Q-table from {}: {}", filePath, e.getMessage());
            throw e;
        }
    }

}


