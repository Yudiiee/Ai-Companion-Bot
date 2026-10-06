package io.github.yudiiee.aicompanion.ChatUtils.PreProcessing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

public class NLPModelSetup {

    private static final Logger LOGGER = LoggerFactory.getLogger("NLPModelSetup");
    private static final int MAX_RETRIES = 2;

    public static void ensureModelDownloaded(Path targetFile, String modelURL, String expectedSha512) throws IOException {


        int retryCount = 0;

        while (retryCount <= MAX_RETRIES) {
            // Download
            try (InputStream in = URI.create(modelURL).toURL().openStream()) {
                Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }

            // Validate
            String actualHash = sha512Hex(targetFile);
            if (actualHash.equalsIgnoreCase(expectedSha512)) {
                LOGGER.info("✅ File {} downloaded and verified successfully.", targetFile);
                return;
            } else {
                LOGGER.warn("⚠️ Hash mismatch for {} (attempt {}/{}). Retrying...", targetFile.getFileName(), retryCount + 1, MAX_RETRIES);
                Files.deleteIfExists(targetFile);
                retryCount++;
            }
        }

        throw new IOException("Max retries reached. Could not verify integrity of: " + targetFile);
    }

    // SHA-512 checksum calculator
    public static String sha512Hex(Path file) throws IOException {
        try (InputStream fis = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("Failed to calculate SHA-512: " + e.getMessage(), e);
        }
    }

}