package io.github.yudiiee.aicompanion.GameAI.human;

import java.util.Locale;

/** Cleaning up what the language model answers a companion in chat (kept apart from {@link BotTalk}: no game classes). */
final class BotTalkText {

    private BotTalkText() {}

    /** The first usable line of a model reply (no thinking blocks, no names), or null for nothing to say. */
    static String clean(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("(?s)<think>.*?</think>", "").replaceAll("(?s)<think>.*$", "").trim();
        for (String l : s.split("\\r?\\n")) {
            String t = l.trim();
            if (t.isEmpty()) continue;
            t = t.replaceAll("^[<\\[][A-Za-z0-9_]{1,16}[>\\]]:?\\s*", "").replaceAll("^\"|\"$", "").trim();
            if (t.isEmpty() || t.toLowerCase(Locale.ROOT).startsWith("[silent")) return null;
            if (t.length() > 170) {
                int cut = t.lastIndexOf(' ', 170);
                t = t.substring(0, cut > 40 ? cut : 170);
            }
            return t;
        }
        return null;
    }
}
