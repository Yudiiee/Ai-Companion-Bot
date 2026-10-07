package io.github.yudiiee.aicompanion.GameAI.human;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Makes the bot talk like a person in chat.
 *
 * <ul>
 *   <li>Messages show up as ordinary player chat ({@code <Steve> hey}) instead of a
 *       rainbow-coloured {@code [Steve]} server broadcast.</li>
 *   <li>Each message waits a believable amount of time: a short "reading" pause, then
 *       roughly the time it takes to type it. Multi-line replies arrive one line at a
 *       time, in order, per bot.</li>
 *   <li>Markdown, emoji the Minecraft font can't draw, "Steve:" prefixes and robotic
 *       status lines ("Running web search....") are cleaned up or reworded.</li>
 * </ul>
 */
public final class HumanChat {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-humanlike");
    private static final Random RNG = new Random();
    private static final int MAX_LINE = 180;
    private static final int MAX_LINES_PER_REPLY = 5;

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ai-companion-humanlike-chat");
        t.setDaemon(true);
        return t;
    });

    /** botName -> earliest time (ms) the next line may be delivered, keeps lines in order. */
    private static final Map<String, Long> NEXT_FREE = new ConcurrentHashMap<>();

    /** A marker result meaning "show as a grey system notice, not as the bot talking". */
    private static final String SYSTEM_PREFIX = "\u0000SYSTEM:";

    private HumanChat() {}

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    /** Say something as {@code botName}, humanised and paced. Safe from any thread. */
    public static void say(MinecraftServer server, String botName, String rawText) {
        say(server, botName, rawText, true);
    }

    public static void say(MinecraftServer server, String botName, String rawText, boolean paced) {
        if (server == null || botName == null || rawText == null) return;
        String cleaned = humanize(botName, rawText);
        if (cleaned == null) {
            LOGGER.debug("[humanlike] suppressed status line from {}: {}", botName, rawText);
            return;
        }
        if (cleaned.startsWith(SYSTEM_PREFIX)) {
            systemNotice(server, cleaned.substring(SYSTEM_PREFIX.length()));
            return;
        }
        List<String> lines = split(cleaned);
        boolean typing = paced && HumanConfig.get().typingDelay;
        long now = System.currentTimeMillis();
        long at = Math.max(now, NEXT_FREE.getOrDefault(botName, 0L));
        boolean first = at <= now + 50; // starting fresh (not continuing a burst)
        for (String line : lines) {
            long delay = typing ? typingDelayMs(line, first) : 0L;
            first = false;
            at += delay;
            final String text = line;
            long wait = Math.max(0L, at - System.currentTimeMillis());
            SCHEDULER.schedule(() -> server.execute(() -> deliver(server, botName, text)), wait, TimeUnit.MILLISECONDS);
        }
        NEXT_FREE.put(botName, at);
    }

    /** Says something once {@code afterBot} has finished typing what it's saying (a reply to it). Safe from any thread. */
    public static void sayAfter(MinecraftServer server, String botName, String afterBot, String rawText) {
        if (afterBot != null && botName != null) {
            long after = NEXT_FREE.getOrDefault(afterBot, 0L) + 700L + RNG.nextInt(1200);
            NEXT_FREE.merge(botName, after, Math::max);
        }
        say(server, botName, rawText, true);
    }

    /** Grey, clearly-not-a-player notice (connection status, errors for the owner). */
    public static void systemNotice(MinecraftServer server, String text) {
        if (server == null || text == null || text.isBlank()) return;
        server.execute(() -> {
            try {
                Component notice = Component.literal("[AI Companion] " + stripColor(text)).withStyle(ChatFormatting.GRAY);
                broadcast(server, notice);
            } catch (Exception e) {
                LOGGER.warn("[humanlike] could not send notice: {}", e.getMessage());
            }
        });
    }

    /** True while this bot still has queued lines it hasn't "typed" yet. */
    public static boolean isTyping(String botName) {
        return NEXT_FREE.getOrDefault(botName, 0L) > System.currentTimeMillis();
    }

    // ------------------------------------------------------------------------
    // Delivery
    // ------------------------------------------------------------------------

    private static void deliver(MinecraftServer server, String botName, String text) {
        try {
            if (server.getPlayerList().getPlayerByName(botName) == null) return; // bot left meanwhile
            if (HumanConfig.get().playerStyleChat) {
                broadcast(server, Component.literal("<" + botName + "> " + text));
            } else {
                var bot = server.getPlayerList().getPlayerByName(botName);
                server.getCommands().performPrefixedCommand(
                        bot.createCommandSourceStack().withSuppressedOutput()
                                .withMaximumPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS),
                        "/say " + text);
            }
            ConversationMemory.recordBot(botName, text);
            BotTalk.heard(server, botName, text);
        } catch (Exception e) {
            LOGGER.warn("[humanlike] failed to deliver chat line: {}", e.getMessage());
        }
    }

    /** Sends a line to everyone's chat (and the server log) the same way vanilla system chat is sent. */
    private static void broadcast(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, player -> message, false);
    }

    private static long typingDelayMs(String line, boolean firstOfBurst) {
        double cps = Math.max(3.0, HumanConfig.get().typingCharsPerSecond);
        long typing = (long) (line.length() / cps * 1000.0);
        typing = Math.min(typing, 5500L);
        long think = firstOfBurst ? 450 + RNG.nextInt(900) : 200 + RNG.nextInt(450);
        return think + typing;
    }

    // ------------------------------------------------------------------------
    // Text clean-up
    // ------------------------------------------------------------------------

    private record Rewrite(Pattern pattern, String[] replacements) {}

    private static final List<Rewrite> STATUS_REWRITES = List.of(
            new Rewrite(Pattern.compile("(?i)^\\S*\\s*is (done )?thinking.*$"), null),
            new Rewrite(Pattern.compile("(?i)^running web search.*$"),
                    new String[]{"hmm lemme look that up", "one sec, checking", "hold on, let me check real quick"}),
            new Rewrite(Pattern.compile("(?i)^web search complete.*$"), null),
            new Rewrite(Pattern.compile("(?i)^no info found.*$"),
                    new String[]{"hm, couldn't find anything on that tbh", "no idea honestly, couldn't find it"}),
            new Rewrite(Pattern.compile("(?i)^.{0,3}reanalyzing.*$"), null),
            new Rewrite(Pattern.compile("(?i)^i couldn'?t understand that clearly.*$"),
                    new String[]{"wait what do you mean?", "huh? say that again", "not sure what you mean"}),
            // it doesn't announce mobs any more: it just fights back when one attacks
            new Rewrite(Pattern.compile("(?i)^terminating all current tasks due to threat.*$"), null),
            new Rewrite(Pattern.compile("(?i)^.{0,3}i'?m confused! please report this.*$"),
                    new String[]{"uhh my brain just glitched, say that again?"}),
            new Rewrite(Pattern.compile("(?i)^sorry, i couldn'?t find enough context.*$"),
                    new String[]{"sorry, lost my train of thought. what was that?", "wait what? say that again"}),
            new Rewrite(Pattern.compile("(?i)^processing your message, please wait.*$"), null),
            new Rewrite(Pattern.compile("(?i)^\\[?(silent|no reply|no response|stay silent)\\]?\\.?$"), null)
    );

    private static final Pattern SYSTEM_LINES = Pattern.compile(
            "(?i)^(established connection to|error! could not reach|llm client|error: ai system not ready|error: |error - |openai-compatible|provider returned).*");

    private static final Pattern COLOR = Pattern.compile("§.");
    private static final Pattern MD_BOLD = Pattern.compile("(\\*\\*|__)(.+?)\\1");
    private static final Pattern MD_ITALIC = Pattern.compile("(?<![\\w*])[*_](\\S[^*_]*?)[*_](?![\\w*])");
    private static final Pattern MD_CODE = Pattern.compile("`{1,3}([^`]*)`{1,3}");
    private static final Pattern MD_HEADER = Pattern.compile("(?m)^\\s*#{1,6}\\s*");
    private static final Pattern MD_BULLET = Pattern.compile("(?m)^\\s*[-*•]\\s+");

    static String stripColor(String s) {
        return COLOR.matcher(s).replaceAll("");
    }

    /**
     * Cleans up a line the bot is about to say. Returns {@code null} if the line
     * should not be said at all, or a string starting with the system marker if it
     * should be shown as a grey system notice instead.
     */
    public static String humanize(String botName, String raw) {
        String s = stripColor(raw).replace("\r", "").trim();
        if (s.isEmpty()) return null;

        // Drop "Steve: " / "<Steve> " / "[Steve] " self-prefixes the LLM or old code adds
        String[] prefixes = {botName + ": ", "<" + botName + "> ", "[" + botName + "] ", botName + " says: "};
        boolean again = true;
        while (again) {
            again = false;
            for (String p : prefixes) {
                if (s.regionMatches(true, 0, p, 0, p.length())) {
                    s = s.substring(p.length()).trim();
                    again = true;
                }
            }
        }

        if (HumanConfig.get().hideRobotStatusLines) {
            if (SYSTEM_LINES.matcher(s).matches()) return SYSTEM_PREFIX + s;
            for (Rewrite rw : STATUS_REWRITES) {
                if (rw.pattern().matcher(s).matches()) {
                    if (rw.replacements() == null) return null;
                    return rw.replacements()[RNG.nextInt(rw.replacements().length)];
                }
            }
        }

        // Markdown -> plain chat
        s = MD_CODE.matcher(s).replaceAll("$1");
        s = MD_BOLD.matcher(s).replaceAll("$2");
        s = MD_ITALIC.matcher(s).replaceAll("$1");
        s = MD_HEADER.matcher(s).replaceAll("");
        s = MD_BULLET.matcher(s).replaceAll("");

        s = stripUnrenderable(s);

        // Strip wrapping quotes the model sometimes adds around the whole reply
        if (s.length() > 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("“") && s.endsWith("”")))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        s = s.replaceAll("[ \\t]+", " ").replaceAll("\\n{2,}", "\n").trim();
        return s.isEmpty() ? null : s;
    }

    /** Removes emoji and pictographs that Minecraft's font renders as boxes. */
    static String stripUnrenderable(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            boolean drop = (cp >= 0x1F000 && cp <= 0x1FAFF)   // emoji & pictographs
                    || (cp >= 0x2600 && cp <= 0x27BF)            // misc symbols & dingbats (incl. check marks)
                    || (cp >= 0x2B00 && cp <= 0x2BFF)            // arrows/stars used as emoji
                    || cp == 0xFE0F || cp == 0x200D || cp == 0x20E3
                    || (cp >= 0xE000 && cp <= 0xF8FF);           // private use
            if (!drop) sb.appendCodePoint(cp);
        });
        return sb.toString().replaceAll(" {2,}", " ").trim();
    }

    /** Splits a reply into chat-sized lines the way a person would send several messages. */
    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\\n")) {
            para = para.trim();
            if (para.isEmpty()) continue;
            if (para.length() <= MAX_LINE) { out.add(para); continue; }
            StringBuilder cur = new StringBuilder();
            for (String sentence : para.split("(?<=[.!?])\\s+")) {
                if (cur.length() > 0 && cur.length() + sentence.length() + 1 > MAX_LINE) {
                    out.add(cur.toString().trim());
                    cur.setLength(0);
                }
                if (sentence.length() > MAX_LINE) {
                    for (String w : sentence.split("\\s+")) {
                        if (cur.length() + w.length() + 1 > MAX_LINE) {
                            out.add(cur.toString().trim());
                            cur.setLength(0);
                        }
                        cur.append(w).append(' ');
                    }
                } else {
                    cur.append(sentence).append(' ');
                }
            }
            if (cur.length() > 0) out.add(cur.toString().trim());
        }
        if (out.size() > MAX_LINES_PER_REPLY) {
            List<String> trimmed = new ArrayList<>(out.subList(0, MAX_LINES_PER_REPLY));
            return trimmed;
        }
        return out;
    }

    public static String pick(String... options) {
        return options[RNG.nextInt(options.length)];
    }

    public static String pick(List<String> options) {
        return options.get(RNG.nextInt(options.size()));
    }
}
