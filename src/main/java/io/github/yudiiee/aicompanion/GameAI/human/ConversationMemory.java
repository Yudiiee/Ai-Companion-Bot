package io.github.yudiiee.aicompanion.GameAI.human;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-term memory of what was said in chat, so the bot can keep a conversation
 * going the way a person would ("wait, what did you say about the cave?").
 *
 * <p>One shared log is kept (chat is public), plus per-player bookkeeping of who
 * the bot has recently been talking with.
 */
public final class ConversationMemory {

    public record Line(long timeMs, String speaker, String text, boolean fromBot) {}

    private static final int MAX_LINES = 40;
    private static final Deque<Line> LOG = new ArrayDeque<>();

    /** playerUUID -> last time that player and a bot exchanged messages. */
    private static final Map<UUID, Long> LAST_EXCHANGE = new ConcurrentHashMap<>();
    /** playerUUID -> bot name they were last talking with. */
    private static final Map<UUID, String> LAST_PARTNER = new ConcurrentHashMap<>();
    /** botName -> last time the bot said anything. */
    private static final Map<String, Long> LAST_BOT_SPOKE = new ConcurrentHashMap<>();
    /** last time any real player chatted. */
    private static volatile long lastPlayerChat = 0L;

    private ConversationMemory() {}

    public static synchronized void recordPlayer(String name, String text) {
        add(new Line(System.currentTimeMillis(), name, text, false));
        lastPlayerChat = System.currentTimeMillis();
    }

    public static synchronized void recordBot(String botName, String text) {
        add(new Line(System.currentTimeMillis(), botName, text, true));
        LAST_BOT_SPOKE.put(botName, System.currentTimeMillis());
    }

    private static void add(Line line) {
        LOG.addLast(line);
        while (LOG.size() > MAX_LINES) LOG.removeFirst();
    }

    public static void markExchange(UUID player, String botName) {
        LAST_EXCHANGE.put(player, System.currentTimeMillis());
        LAST_PARTNER.put(player, botName);
    }

    /** Milliseconds since this player and a bot last talked, or Long.MAX_VALUE. */
    public static long sinceExchange(UUID player) {
        Long t = LAST_EXCHANGE.get(player);
        return t == null ? Long.MAX_VALUE : System.currentTimeMillis() - t;
    }

    public static String lastPartner(UUID player) {
        return LAST_PARTNER.get(player);
    }

    public static long sinceBotSpoke(String botName) {
        Long t = LAST_BOT_SPOKE.get(botName);
        return t == null ? Long.MAX_VALUE : System.currentTimeMillis() - t;
    }

    public static long sinceAnyPlayerChat() {
        return lastPlayerChat == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastPlayerChat;
    }

    /** The last {@code max} lines from the past 15 minutes, oldest first. */
    public static synchronized List<Line> recent(int max) {
        long cutoff = System.currentTimeMillis() - 15 * 60_000L;
        List<Line> out = new ArrayList<>();
        for (Line l : LOG) if (l.timeMs() >= cutoff) out.add(l);
        return out.size() <= max ? out : out.subList(out.size() - max, out.size());
    }

    /** Chat transcript formatted for an LLM prompt; empty string if nothing recent. */
    public static String transcript(int max) {
        List<Line> lines = recent(max);
        if (lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        for (Line l : lines) {
            long ago = (now - l.timeMs()) / 1000;
            sb.append('[').append(ago < 60 ? ago + "s" : (ago / 60) + "m").append(" ago] <")
              .append(l.speaker()).append("> ").append(l.text()).append('\n');
        }
        return sb.toString().trim();
    }

    public static synchronized void clear() {
        LOG.clear();
        LAST_EXCHANGE.clear();
        LAST_PARTNER.clear();
        LAST_BOT_SPOKE.clear();
        lastPlayerChat = 0L;
    }
}
