package io.github.yudiiee.aicompanion.ServiceLLMClients;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.yudiiee.aicompanion.LauncherDetection.LauncherEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Player2 OAuth2 Device Authorization Flow tokens (p2Keys).
 *
 * <p>Player2 is a per-user auth system — each Minecraft player authenticates
 * with their own Player2 account. This class:
 * <ul>
 *   <li>Kicks off the Device Flow (shows a code + URL in chat)</li>
 *   <li>Polls for approval and caches the resulting p2Key in memory</li>
 *   <li>Persists tokens to disk (one file per player UUID) so re-auth
 *       is not needed across server restarts</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   // On first use, or if token missing:
 *   Player2Auth.startDeviceFlow(playerUUID, chatCallback);
 *
 *   // In Player2Client.sendPrompt():
 *   String p2Key = Player2Auth.getToken(playerUUID);
 * </pre>
 */
public class Player2Auth {

    private static final Logger LOGGER = LoggerFactory.getLogger("player2-auth");

    /** Public game identifier registered on the Player2 developer dashboard. Not a secret. */
    public static final String GAME_CLIENT_ID = "019e736f-1b44-7741-83de-aeb76f09c958";

    private static final String API_BASE         = "https://api.player2.game/v1";
    private static final String DEVICE_NEW_URL   = API_BASE + "/login/device/new";
    private static final String DEVICE_TOKEN_URL = API_BASE + "/login/device/token";

    /** Polling interval in milliseconds while waiting for user approval. */
    private static final long POLL_INTERVAL_MS = 5_000;
    /** Maximum time to wait for the user to approve the device (5 minutes). */
    private static final long POLL_TIMEOUT_MS  = 5 * 60 * 1_000;

    private static final String TOKEN_DIR =
            LauncherEnvironment.getStorageDirectory("player2_tokens");

    /** In-memory cache: playerUUID → p2Key */
    private static final ConcurrentHashMap<UUID, String> TOKEN_CACHE = new ConcurrentHashMap<>();

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns a cached or persisted p2Key for the given player, or {@code null}
     * if the player has not authenticated yet.
     *
     * @param playerUUID the Minecraft player's UUID
     * @return the p2Key string, or null
     */
    public static String getToken(UUID playerUUID) {
        // 1. Check in-memory cache first
        String cached = TOKEN_CACHE.get(playerUUID);
        if (cached != null && !cached.isBlank()) return cached;

        // 2. Try loading from disk
        String persisted = loadFromDisk(playerUUID);
        if (persisted != null && !persisted.isBlank()) {
            TOKEN_CACHE.put(playerUUID, persisted);
            return persisted;
        }

        return null;
    }

    /**
     * Returns true if a valid token already exists for this player.
     */
    public static boolean hasToken(UUID playerUUID) {
        return getToken(playerUUID) != null;
    }

    /**
     * Revoke and delete a player's stored token (e.g. on logout).
     */
    public static void clearToken(UUID playerUUID) {
        TOKEN_CACHE.remove(playerUUID);
        Path file = tokenFilePath(playerUUID);
        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
    }

    /**
     * Starts the OAuth2 Device Authorization Flow for a player.
     *
     * <p>This method is non-blocking — it spawns a virtual thread to poll
     * for approval. The {@code chatCallback} is called at each step so
     * the calling code can relay messages to the Minecraft chat.
     *
     * @param playerUUID    the player who needs to authenticate
     * @param chatCallback  receives status/instruction strings to show the player
     */
    public static void startDeviceFlow(UUID playerUUID,
                                       java.util.function.Consumer<String> chatCallback) {
        Thread.ofVirtual().name("p2-device-flow-" + playerUUID).start(() -> {
            try {
                runDeviceFlow(playerUUID, chatCallback);
            } catch (Exception e) {
                LOGGER.error("[player2-auth] Device flow error for {}", playerUUID, e);
                chatCallback.accept("§c[Player2] Authentication error: " + e.getMessage());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Internal: Device Flow
    // -------------------------------------------------------------------------

    private static void runDeviceFlow(UUID playerUUID,
                                      java.util.function.Consumer<String> chatCallback) throws Exception {
        // Step 1 — Request a device code
        JsonObject initBody = new JsonObject();
        initBody.addProperty("game_client_id", GAME_CLIENT_ID);

        HttpRequest initReq = HttpRequest.newBuilder()
                .uri(URI.create(DEVICE_NEW_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(initBody.toString()))
                .build();

        HttpResponse<String> initResp = HTTP.send(initReq, HttpResponse.BodyHandlers.ofString());

        if (initResp.statusCode() != 200) {
            chatCallback.accept("§c[Player2] Could not start auth (HTTP " + initResp.statusCode() + ")");
            LOGGER.error("[player2-auth] /login/device/new returned {}: {}", initResp.statusCode(), initResp.body());
            return;
        }

        JsonObject initJson = JsonParser.parseString(initResp.body()).getAsJsonObject();
        String deviceCode      = initJson.get("device_code").getAsString();
        String userCode        = initJson.get("user_code").getAsString();
        String verificationUri = initJson.get("verification_uri").getAsString();

        // Step 2 — Tell the player what to do
        chatCallback.accept("§e[Player2] §fTo connect your Player2 account, visit:");
        chatCallback.accept("§b" + verificationUri);
        chatCallback.accept("§e[Player2] §fand enter code: §a§l" + userCode);
        chatCallback.accept("§7(Waiting up to 5 minutes for approval...)");

        // Step 3 — Poll for approval
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS);

            JsonObject pollBody = new JsonObject();
            pollBody.addProperty("device_code", deviceCode);
            pollBody.addProperty("game_client_id", GAME_CLIENT_ID);

            HttpRequest pollReq = HttpRequest.newBuilder()
                    .uri(URI.create(DEVICE_TOKEN_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(pollBody.toString()))
                    .build();

            HttpResponse<String> pollResp = HTTP.send(pollReq, HttpResponse.BodyHandlers.ofString());

            if (pollResp.statusCode() == 200) {
                JsonObject tokenJson = JsonParser.parseString(pollResp.body()).getAsJsonObject();
                if (tokenJson.has("p2Key")) {
                    String p2Key = tokenJson.get("p2Key").getAsString();
                    TOKEN_CACHE.put(playerUUID, p2Key);
                    saveToDisk(playerUUID, p2Key);
                    chatCallback.accept("§a[Player2] ✓ Authentication successful! You can now use Player2 AI.");
                    LOGGER.info("[player2-auth] Token acquired for player {}", playerUUID);
                    return;
                }
            } else if (pollResp.statusCode() == 428) {
                // "authorization_pending" — keep polling
                LOGGER.debug("[player2-auth] Still waiting for approval from {}", playerUUID);
            } else if (pollResp.statusCode() == 400) {
                chatCallback.accept("§c[Player2] Auth expired or denied. Please try again.");
                LOGGER.warn("[player2-auth] Device flow denied/expired for {}: {}", playerUUID, pollResp.body());
                return;
            }
        }

        chatCallback.accept("§c[Player2] Authentication timed out. Please try /player2auth to retry.");
        LOGGER.warn("[player2-auth] Device flow timed out for {}", playerUUID);
    }

    // -------------------------------------------------------------------------
    // Token persistence
    // -------------------------------------------------------------------------

    private static void saveToDisk(UUID playerUUID, String p2Key) {
        try {
            Path dir = Paths.get(TOKEN_DIR);
            Files.createDirectories(dir);
            Files.writeString(tokenFilePath(playerUUID), p2Key,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("[player2-auth] Failed to persist token for {}", playerUUID, e);
        }
    }

    private static String loadFromDisk(UUID playerUUID) {
        try {
            Path file = tokenFilePath(playerUUID);
            if (Files.exists(file)) {
                return Files.readString(file).trim();
            }
        } catch (IOException e) {
            LOGGER.warn("[player2-auth] Could not read token file for {}", playerUUID);
        }
        return null;
    }

    private static Path tokenFilePath(UUID playerUUID) {
        return Paths.get(TOKEN_DIR, playerUUID.toString() + ".p2token");
    }
}
