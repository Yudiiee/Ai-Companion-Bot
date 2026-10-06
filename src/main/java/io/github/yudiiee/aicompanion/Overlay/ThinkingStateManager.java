package io.github.yudiiee.aicompanion.Overlay;

import java.util.ArrayList;
import java.util.List;

public class ThinkingStateManager {
    private static boolean active = false;
    private static final List<String> reasoningLines = new ArrayList<>();
    private static String botName = "";
    private static boolean collapsed = true;

    public static void start(String name) {
        active = true;
        reasoningLines.clear();
        botName = name;
        collapsed = true;
    }

    public static void appendThoughtLine(String line) {
        reasoningLines.add(line.trim());
    }

    public static void end() {
        active = false;
    }

    public static boolean isThinking() {
        return active;
    }

    public static List<String> getReasoningLines() {
        return reasoningLines;
    }

    public static String getBotName() {
        return botName;
    }

    public static boolean isCollapsed() {
        return collapsed;
    }

    public static void toggleCollapsed() {
        collapsed = !collapsed;
    }
}

