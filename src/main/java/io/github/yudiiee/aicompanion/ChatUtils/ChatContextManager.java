package io.github.yudiiee.aicompanion.ChatUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChatContextManager {
    private static final Map<UUID, ClarificationState> pendingClarifications = new HashMap<>();

    public static void setPendingClarification(UUID playerUUID, String originalMessage, String clarifyingQuestion, String botName) {
        pendingClarifications.put(playerUUID, new ClarificationState(originalMessage, clarifyingQuestion, botName));
    }

    public static ClarificationState getPendingClarification(UUID playerUUID) {
        return pendingClarifications.get(playerUUID);
    }

    public static void clearPendingClarification(UUID playerUUID) {
        pendingClarifications.remove(playerUUID);
    }

    public static boolean isAwaitingClarification(UUID playerUUID) {
        return pendingClarifications.containsKey(playerUUID);
    }
}

