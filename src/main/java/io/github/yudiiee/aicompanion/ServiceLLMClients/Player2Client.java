package io.github.yudiiee.aicompanion.ServiceLLMClients;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.yudiiee.aicompanion.AICompanion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * LLMClient implementation for the Player2 AI platform.
 *
 * <p>Key differences from other service clients:
 * <ul>
 *   <li><b>Per-user auth</b> — each Minecraft player authenticates with their
 *       own Player2 account via OAuth2 Device Flow ({@link Player2Auth}).
 *       There is no single server-side API key.</li>
 *   <li><b>Joules currency</b> — requests are charged against the player's
 *       Player2 balance. A 402 response means they have run out.</li>
 *   <li><b>Mandatory health ping</b> — Player2 ToS requires
 *       {@code GET /health} every 60 s while the bot is active to attribute
 *       usage to your game.</li>
 * </ul>
 *
 * <p>The chat completions endpoint is OpenAI-compatible, so the request/
 * response shape mirrors {@link GenericOpenAIClient}.
 *
 * <p>The {@code game_client_id} is hardcoded in {@link Player2Auth#GAME_CLIENT_ID}
 * and is read from there — it does not need to be passed in or configured by
 * the end user.
 */
public class Player2Client implements LLMClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("player2-client");

    private static final String BASE_URL         = "https://api.player2.game/v1/";
    private static final String COMPLETIONS_PATH = "chat/completions";
    private static final String HEALTH_PATH      = "health";

    private final String   modelName;
    private final UUID     playerUUID;
    private final Consumer<String> chatCallback;

    private final HttpClient http;
    private final ScheduledExecutorService pinger;

    /**
     * @param playerUUID   Minecraft player UUID — used to look up their p2Key
     * @param modelName    Model to request (e.g. "gpt-4o", or whatever Player2 exposes)
     * @param chatCallback Consumer that sends a string into Minecraft chat
     */
    public Player2Client(UUID playerUUID, String modelName, Consumer<String> chatCallback) {
        this.playerUUID   = playerUUID;
        this.modelName    = modelName;
        this.chatCallback = chatCallback;
        this.http         = HttpClient.newHttpClient();
        this.pinger       = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofVirtual().name("p2-health-ping").unstarted(r));

        startHealthPing();
    }

    // -------------------------------------------------------------------------
    // LLMClient interface
    // -------------------------------------------------------------------------

    @Override
    public String sendPrompt(String systemPrompt, String userPrompt) {
        String p2Key = resolveToken();
        if (p2Key == null) return "";

        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);
            requestBody.addProperty("stream", false);

            JsonArray messages = new JsonArray();

            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", systemPrompt);
            messages.add(sys);

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", userPrompt);
            messages.add(user);

            requestBody.add("messages", messages);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + COMPLETIONS_PATH))
                    .header("Authorization", "Bearer " + p2Key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            return switch (resp.statusCode()) {
                case 200 -> parseContent(resp.body());
                case 401 -> {
                    LOGGER.warn("[player2] Token expired for {}. Clearing and re-authenticating.", playerUUID);
                    Player2Auth.clearToken(playerUUID);
                    triggerReAuth();
                    yield "";
                }
                case 402 -> {
                    LOGGER.warn("[player2] Player {} has insufficient Joules.", playerUUID);
                    chatCallback.accept("§c[Player2] You've run out of Joules! Top up at player2.game to continue.");
                    yield "";
                }
                default -> {
                    LOGGER.error("[player2] Unexpected HTTP {} — {}", resp.statusCode(), resp.body());
                    yield "Error from Player2: HTTP " + resp.statusCode();
                }
            };

        } catch (Exception e) {
            LOGGER.error("[player2] sendPrompt error", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Checks reachability via {@code GET /health} and validates the player's
     * token exists. Triggers Device Flow auth if not yet authenticated.
     */
    @Override
    public boolean isReachable() {
        try {
            HttpRequest ping = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + HEALTH_PATH))
                    .GET().build();
            HttpResponse<String> resp = http.send(ping, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOGGER.warn("[player2] /health returned {}", resp.statusCode());
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("[player2] Health check failed", e);
            return false;
        }

        // Ensure player has a token — kick off Device Flow if not
        if (!Player2Auth.hasToken(playerUUID)) {
            LOGGER.info("[player2] No token for player {} — starting Device Flow", playerUUID);
            Player2Auth.startDeviceFlow(playerUUID, chatCallback);
            return false; // not ready yet; LLMServiceHandler will retry after auth completes
        }

        return true;
    }

    @Override
    public String getProvider() {
        return "Player2";
    }

    // -------------------------------------------------------------------------
    // Health ping (required by Player2 ToS — every 60 seconds)
    // -------------------------------------------------------------------------

    private void startHealthPing() {
        pinger.scheduleAtFixedRate(() -> {
            String p2Key = Player2Auth.getToken(playerUUID);
            if (p2Key == null) return;
            try {
                HttpRequest ping = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + HEALTH_PATH))
                        .header("Authorization", "Bearer " + p2Key)
                        .GET().build();
                HttpResponse<String> resp = http.send(ping, HttpResponse.BodyHandlers.ofString());
                LOGGER.debug("[player2] Health ping → HTTP {}", resp.statusCode());
            } catch (Exception e) {
                LOGGER.warn("[player2] Health ping failed: {}", e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * Shuts down the health pinger. Call this when the bot disconnects.
     */
    public void shutdown() {
        pinger.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String resolveToken() {
        String token = Player2Auth.getToken(playerUUID);
        if (token == null) {
            LOGGER.info("[player2] No token for {}. Triggering Device Flow.", playerUUID);
            triggerReAuth();
        }
        return token;
    }

    private void triggerReAuth() {
        chatCallback.accept("§e[Player2] Re-authentication needed. Starting login...");
        Player2Auth.startDeviceFlow(playerUUID, chatCallback);
    }

    private static String parseContent(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return obj.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        } catch (Exception e) {
            LOGGER.error("[player2] Failed to parse response JSON: {}", json, e);
            return "";
        }
    }
}
