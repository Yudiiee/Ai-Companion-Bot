package io.github.yudiiee.aicompanion.GameAI.human;

import io.github.yudiiee.aicompanion.FilingSystem.LLMClientFactory;
import io.github.yudiiee.aicompanion.ServiceLLMClients.LLMClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The companions talking to each other through the language model: when one says something
 * (what it's after, what it found, a question), another may answer in its own voice, with the
 * team's stock and the depot in mind; and now and then a quiet one starts a conversation.
 * It's rationed (cooldowns, at most a few replies per original line) so the chat doesn't turn
 * into bots chattering forever or hammering a local model.
 */
public final class BotTalk {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-bottalk");
    private static final Random RNG = new Random();

    private BotTalk() {}

    private static final java.util.concurrent.ThreadPoolExecutor POOL = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(2),
            r -> {
                Thread t = new Thread(r, "ai-companion-bot-talk");
                t.setDaemon(true);
                return t;
            }, new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
    /** Runs the model call with a time limit so one hung request can't silence everybody. */
    private static final ExecutorService CALLS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-companion-bot-talk-call");
        t.setDaemon(true);
        return t;
    });

    private static String ask(LLMClient c, String system, String user) throws Exception {
        java.util.concurrent.Future<String> f = CALLS.submit(() -> c.sendPrompt(system, user));
        try {
            return f.get(40, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            return null;
        }
    }

    /** Replies in a row to one original line; resets with any fresh line. */
    static final int MAX_CHAIN = 3;
    private static volatile int chain;
    private static volatile long chainAt;
    private static volatile long nextAny;
    private static volatile long lastAnyLine;
    private static final Map<String, Long> NEXT_REPLY = new ConcurrentHashMap<>();
    private static final Map<String, Long> NEXT_START = new ConcurrentHashMap<>();
    /** Bots with a reply on the way: the line they say next is a reply, not a fresh remark. */
    private static final Map<String, Long> REPLYING = new ConcurrentHashMap<>();

    private static final Pattern TRIVIAL = Pattern.compile(
            "(?i)^\\W*(o/|gg\\b.*|nice\\b.*|ok\\b.*|lol\\b.*|omw|hey\\b.{0,12}|yo\\b.{0,12}|wb\\b.{0,12}|cya\\b.*|sup\\b.*|ayy\\b.*|\\W*)$");
    private static final Pattern WANTS = Pattern.compile(
            "(?i)\\?|\\b(need|missing|out of|don'?t have|can'?t find|inventory'?s full|anyone|someone|who has|got any|spare|"
            + "found|just got|finished|built|stuck|help|depot|chest)\\b");

    // ------------------------------------------------------------------------
    // Answering
    // ------------------------------------------------------------------------

    /** A companion said {@code text}. Server thread. */
    static void heard(MinecraftServer server, String speaker, String text) {
        try {
            if (!HumanConfig.get().botChat || server == null || text == null) return;
            long now = System.currentTimeMillis();
            lastAnyLine = now;
            Long replying = REPLYING.remove(speaker);
            if (replying != null && now - replying < 90_000L) chain++;
            else chain = 0;
            if (now - chainAt > 120_000L) chain = replying != null ? 1 : 0;
            chainAt = now;
            if (chain >= MAX_CHAIN || now < nextAny) return;
            if (text.length() < 6 || TRIVIAL.matcher(text).matches()) return;

            List<ServerPlayer> others = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.isAlive() && HumanBehavior.isAiBot(p) && !p.getName().getString().equalsIgnoreCase(speaker)) others.add(p);
            }
            if (others.isEmpty()) return;
            boolean wants = WANTS.matcher(text).find();
            ServerPlayer pick = null;
            double best = 0;
            String lower = text.toLowerCase(Locale.ROOT);
            for (ServerPlayer p : others) {
                String name = p.getName().getString();
                if (now < NEXT_REPLY.getOrDefault(name, 0L) || HumanChat.isTyping(name)) continue;
                boolean named = Pattern.compile("\\b" + Pattern.quote(name.toLowerCase(Locale.ROOT)) + "\\b").matcher(lower).find();
                double chance = named ? 1.0 : wants ? 0.5 : 0.1;
                double roll = RNG.nextDouble();
                if (roll >= chance) continue;
                double score = (named ? 2 : 0) + (1 - roll);
                if (score > best) { best = score; pick = p; }
            }
            if (pick == null) return;
            final String listener = pick.getName().getString();
            NEXT_REPLY.put(listener, now + 25_000L + RNG.nextInt(15_000));
            nextAny = now + 7_000L;
            REPLYING.put(listener, now);
            if (POOL.getQueue().size() >= 2) { REPLYING.remove(listener); return; }
            final String team = teamContext(pick);
            POOL.submit(() -> reply(server, listener, speaker, text, team));
        } catch (Exception e) {
            LOGGER.debug("[bottalk] heard failed: {}", e.toString());
        }
    }

    /** What the team has, as the speaker would know it (server thread). */
    private static String teamContext(ServerPlayer bot) {
        StringBuilder sb = new StringBuilder();
        String stock = Stock.teamSummary(bot.getName().getString());
        if (!stock.isEmpty()) sb.append("What everybody is carrying: ").append(stock).append('\n');
        String depot = Depot.summary(bot.level());
        if (!depot.isEmpty()) sb.append("The community depot (shared chests): ").append(depot).append(".\n");
        return sb.toString();
    }

    private static LLMClient client;
    private static long clientAt;

    private static synchronized LLMClient client() {
        long now = System.currentTimeMillis();
        if (client == null || now - clientAt > 5 * 60_000L) {
            String provider = System.getProperty("aicompanion.llmMode", "custom");
            if ("player2".equals(provider)) return null; // needs the client-side callback
            try {
                client = LLMClientFactory.createClient(provider);
            } catch (Exception e) {
                client = null;
            }
            clientAt = now;
        }
        return client;
    }

    private static void reply(MinecraftServer server, String listener, String speaker, String text, String team) {
        try {
            LLMClient c = client();
            if (c == null) { REPLYING.remove(listener); return; }
            String system = HumanPersona.systemPrompt(listener) + "\n" + teamRules(team);
            String user = ConversationMemory.transcript(8) + "\n\n" + speaker + " (a teammate, another player) just said in chat: \""
                    + text + "\"\nAnswer " + speaker + " with ONE short chat line in your own voice, about the game (what you're doing, "
                    + "what you have, what you can spare or need). If you have nothing to add, reply exactly [silent].";
            say(server, listener, speaker, ask(c, system, user));
        } catch (Throwable t) {
            REPLYING.remove(listener);
            LOGGER.debug("[bottalk] reply failed: {}", t.toString());
        }
    }

    private static String teamRules(String team) {
        return "TEAMMATES: the other players around you are your teammates (companions like you). You work together: share "
                + "materials through the community depot (chests everybody puts spare stuff in and takes build material from), "
                + "tell each other what you're doing, ask when you need something and offer what you have spare. "
                + "Only claim to have items that the stock below says you carry or the depot holds. Keep it to one short line.\n"
                + team;
    }

    private static void say(MinecraftServer server, String listener, String speaker, String raw) {
        String line = clean(raw);
        if (line == null) { REPLYING.remove(listener); return; }
        HumanChat.sayAfter(server, listener, speaker, line);
    }

    static String clean(String raw) {
        return BotTalkText.clean(raw);
    }

    // ------------------------------------------------------------------------
    // Starting a conversation
    // ------------------------------------------------------------------------

    /** Now and then, a companion with something going on says it to the others. Brain thread. */
    static void maybeStart(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) {
        try {
            if (!HumanConfig.get().botChat) return;
            long now = System.currentTimeMillis();
            long gap = Math.max(1, HumanConfig.get().botChatMinMinutes) * 60_000L;
            Long due = NEXT_START.get(b.name);
            if (due == null) { NEXT_START.put(b.name, now + gap / 2 + RNG.nextInt((int) gap)); return; }
            if (now < due) return;
            NEXT_START.put(b.name, now + gap + RNG.nextInt((int) gap));
            if (now - lastAnyLine < 45_000L || HumanChat.isTyping(b.name)) return;
            String team = SurvivalBrain.onServer(server, () -> {
                int ai = 0;
                for (ServerPlayer p : server.getPlayerList().getPlayers()) if (HumanBehavior.isAiBot(p)) ai++;
                return ai < 2 ? null : teamContext(bot);
            }, null);
            if (team == null || RNG.nextDouble() > 0.6) return;
            REPLYING.remove(b.name);
            final String name = b.name;
            final String job = b.jobName == null ? "" : b.jobName;
            POOL.submit(() -> start(server, name, job, team));
        } catch (Exception e) {
            LOGGER.debug("[bottalk] start failed: {}", e.toString());
        }
    }

    private static void start(MinecraftServer server, String name, String job, String team) {
        try {
            LLMClient c = client();
            if (c == null) return;
            String system = HumanPersona.systemPrompt(name) + "\n" + teamRules(team);
            String user = ConversationMemory.transcript(8) + "\n\nSay ONE short thing to your teammates right now: what you're up to"
                    + (job.isEmpty() ? "" : " (" + job + ")") + ", something you noticed, a plan, or ask for something you need. "
                    + "Don't greet everyone again. If there's really nothing to say, reply exactly [silent].";
            String line = clean(ask(c, system, user));
            if (line != null) HumanChat.say(server, name, line);
        } catch (Throwable t) {
            LOGGER.debug("[bottalk] start failed: {}", t.toString());
        }
    }
}
