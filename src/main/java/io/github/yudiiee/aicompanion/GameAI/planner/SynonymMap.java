package io.github.yudiiee.aicompanion.GameAI.planner;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Maps informal / alternative verbs and phrases to canonical GoalMapper keywords.
 * Pre-processing with this map before scoring ensures that unusual player
 * phrasing ("chop some trees", "grab iron") still resolves to the right goal.
 *
 * Add new synonyms here freely — the map is pure data, no logic.
 */
public class SynonymMap {

    /**
     * Canonical keyword → set of surface-form synonyms.
     * Keys MUST match words already registered in {@link GoalMapper}.
     */
    private static final Map<String, String> SYNONYM_TO_CANONICAL = new HashMap<>();

    static {
        // ── mine / dig ────────────────────────────────────────────────────────
        put("chop",         "mine");
        put("cut",          "mine");
        put("cut down",     "mine");
        put("break",        "mine");
        put("smash",        "mine");
        put("destroy",      "mine");
        put("harvest",      "mine");
        put("pick",         "mine");
        put("quarry",       "mine");
        put("tunnel",       "mine");
        put("bore",         "mine");

        // ── navigate / go ─────────────────────────────────────────────────────
        put("head to",      "go");
        put("head towards", "go");
        put("run to",       "go");
        put("walk to",      "go");
        put("sprint to",    "go");
        put("teleport",     "go");
        put("tp",           "go");
        put("follow",       "go");
        put("come",         "go");
        put("come here",    "go");
        put("warp",         "go");

        // ── gather / collect ──────────────────────────────────────────────────
        put("grab",         "gather");
        put("pick up",      "gather");
        put("loot",         "gather");
        put("scavenge",     "gather");
        put("retrieve",     "gather");
        put("bring me",     "gather");
        put("fetch me",     "gather");
        put("collect",      "gather");

        // ── build / place ─────────────────────────────────────────────────────
        put("put",          "place");
        put("lay",          "place");
        put("set down",     "place");
        put("set up",       "build");
        put("erect",        "build");
        put("make a",       "build");
        put("assemble",     "build");

        // ── craft ─────────────────────────────────────────────────────────────
        put("make",         "craft");
        put("fabricate",    "craft");
        put("create",       "craft");
        put("forge",        "craft");
        put("smelt",        "craft");
        put("cook",         "craft");

        // ── explore / find ────────────────────────────────────────────────────
        put("look for",     "find");
        put("scan for",     "find");
        put("locate",       "find");
        put("search for",   "find");
        put("where is",     "find");
        put("scout",        "explore");
        put("survey",       "explore");
        put("roam",         "explore");
        put("wander",       "explore");

        // ── combat ────────────────────────────────────────────────────────────
        put("hit",          "fight");
        put("slay",         "kill");
        put("murder",       "kill");
        put("eliminate",    "kill");
        put("attack",       "fight");
        put("shoot",        "fight");
        put("pvp",          "combat");

        // ── farm ─────────────────────────────────────────────────────────────
        put("till",         "farm");
        put("plow",         "farm");
        put("sow",          "plant");
        put("reap",         "harvest");

        // ── trade ─────────────────────────────────────────────────────────────
        put("barter",       "trade");
        put("swap",         "trade");
        put("purchase",     "buy");
    }

    private static void put(String synonym, String canonical) {
        SYNONYM_TO_CANONICAL.put(synonym.toLowerCase(), canonical.toLowerCase());
    }

    /**
     * Normalise a raw input string by replacing known synonyms with their
     * canonical equivalents.  Multi-word synonyms are replaced before
     * single-word ones so that "cut down" beats "cut".
     *
     * @param input Raw player message
     * @return Input with synonyms replaced by canonical keywords
     */
    public static String normalize(String input) {
        String result = input.toLowerCase();

        // Sort by key length descending so longer phrases match first
        SYNONYM_TO_CANONICAL.entrySet()
            .stream()
            .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
            .forEach(entry -> {
                // Only replace whole-word occurrences
                String replaced = result.replaceAll(
                    "(?i)\\b" + Pattern.quote(entry.getKey()) + "\\b",
                    entry.getValue()
                );
                // We can't modify 'result' inside a lambda in Java; use a holder trick
                // The stream is sequential so we use a field trick — see below
            });

        // Non-lambda version (lambda can't assign outer local variable)
        String normalized = result;
        for (Map.Entry<String, String> entry : SYNONYM_TO_CANONICAL.entrySet()) {
            normalized = normalized.replaceAll(
                "(?i)\\b" + Pattern.quote(entry.getKey()) + "\\b",
                entry.getValue()
            );
        }

        return normalized;
    }

    /** Expose the raw map for external inspection / testing. */
    public static Map<String, String> getRawMap() {
        return java.util.Collections.unmodifiableMap(SYNONYM_TO_CANONICAL);
    }
}
