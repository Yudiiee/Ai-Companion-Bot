package io.github.yudiiee.aicompanion.GameAI.human;

import net.minecraft.server.MinecraftServer;
import io.github.yudiiee.aicompanion.GameAI.mood.MoodEngine;
import io.github.yudiiee.aicompanion.GameAI.mood.MoodLabel;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quick, casual reactions a real player would type without thinking — "rip",
 * "gg", "hey!", "ow wtf" — so the bot feels present even when no language model
 * is involved. Everything is rate-limited and a little random: real people don't
 * comment on every single event.
 */
public final class HumanReactions {

    private static final Random RNG = new Random();
    /** key -> last time used (ms). */
    private static final Map<String, Long> COOLDOWNS = new ConcurrentHashMap<>();

    private HumanReactions() {}

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static boolean enabled() {
        return HumanConfig.get().reactions;
    }

    /** True (and starts the cooldown) if {@code key} hasn't fired within {@code ms}. */
    static boolean cooldown(String key, long ms) {
        long now = System.currentTimeMillis();
        Long last = COOLDOWNS.get(key);
        if (last != null && now - last < ms) return false;
        COOLDOWNS.put(key, now);
        return true;
    }

    private static boolean chance(double p) {
        return RNG.nextDouble() < p;
    }

    private static boolean lowMood(String botName) {
        try {
            MoodLabel m = MoodLabel.from(MoodEngine.get(botName));
            return m == MoodLabel.BORED || m == MoodLabel.AGITATED || m == MoodLabel.DEPRESSED;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean highMood(String botName) {
        try {
            MoodLabel m = MoodLabel.from(MoodEngine.get(botName));
            return m == MoodLabel.ELATED || m == MoodLabel.EXCITED;
        } catch (Exception e) {
            return false;
        }
    }

    private static String fmt(String template, String player) {
        return template.replace("%p", player == null ? "" : player).replaceAll("\\s+", " ").trim();
    }

    private static void say(MinecraftServer server, String botName, String line) {
        HumanChat.say(server, botName, line);
    }

    // ------------------------------------------------------------------------
    // Social events
    // ------------------------------------------------------------------------

    public static void playerJoined(MinecraftServer server, String botName, String player) {
        if (!enabled() || !cooldown(botName + ":join:" + player, 5 * 60_000L)) return;
        String line = lowMood(botName)
                ? HumanChat.pick("oh hey %p", "hi %p", "%p o/")
                : highMood(botName)
                ? HumanChat.pick("%p!! hey", "yooo %p", "ayy %p's here", "hey %p! perfect timing")
                : HumanChat.pick("hey %p", "wb %p", "o/ %p", "yo %p", "hi %p");
        say(server, botName, fmt(line, player));
    }

    public static void playerLeft(MinecraftServer server, String botName, String player) {
        if (!enabled() || !chance(0.7) || !cooldown(botName + ":leave:" + player, 60_000L)) return;
        say(server, botName, fmt(HumanChat.pick("cya %p", "bye %p", "later %p", "o/", "cya"), player));
    }

    public static void playerDied(MinecraftServer server, String botName, String player, String how) {
        if (!enabled() || !cooldown(botName + ":death:" + player, 20_000L)) return;
        String h = how == null ? "" : how.toLowerCase(Locale.ROOT);
        String line;
        if (h.contains("lava")) line = HumanChat.pick("rip %p, lava again", "noo not the lava", "rip ur stuff %p");
        else if (h.contains("fell") || h.contains("fall") || h.contains("hit the ground")) line = HumanChat.pick("rip lol, gravity", "%p forgot they can't fly", "oof that fall");
        else if (h.contains("creeper") || h.contains("blew up") || h.contains("blown up")) line = HumanChat.pick("creepers man...", "rip, stupid creeper", "aw man");
        else if (h.contains("drown")) line = HumanChat.pick("rip, need air lol", "noo %p");
        else if (h.contains("skeleton") || h.contains("shot")) line = HumanChat.pick("skeletons are so annoying", "rip %p");
        else if (h.contains("zombie")) line = HumanChat.pick("lost to a zombie lol", "rip %p");
        else line = HumanChat.pick("rip %p", "rip", "noo %p", "F", "oof");
        say(server, botName, fmt(line, player));
        if (chance(0.45)) {
            say(server, botName, HumanChat.pick("want me to help get your stuff back?", "where'd you die? i can come", "you ok?"));
        }
    }

    public static void advancement(MinecraftServer server, String botName, String player, String adv) {
        if (!enabled() || !chance(0.75) || !cooldown(botName + ":adv:" + player, 45_000L)) return;
        String line = highMood(botName)
                ? HumanChat.pick("gg %p!", "lets goo %p", "nice one %p!!")
                : HumanChat.pick("gg", "gg %p", "nice", "ayy nice", "gg ez");
        say(server, botName, fmt(line, player));
    }

    public static void enteredNether(MinecraftServer server, String botName, String player) {
        if (!enabled() || !cooldown(botName + ":nether:" + player, 10 * 60_000L)) return;
        say(server, botName, fmt(HumanChat.pick("careful in the nether %p", "gl in the nether, watch for ghasts", "bring back some quartz lol"), player));
    }

    public static void foundValuable(MinecraftServer server, String botName, String player, String what) {
        if (!enabled() || !cooldown(botName + ":loot:" + player, 60_000L)) return;
        say(server, botName, fmt(HumanChat.pick("wait %p found " + what + "??", "no way, " + what + "!", "share some " + what + " lol"), player));
    }

    // ------------------------------------------------------------------------
    // Things happening to the bot
    // ------------------------------------------------------------------------

    public static void hurtByPlayer(MinecraftServer server, String botName, String attacker) {
        if (!enabled() || !cooldown(botName + ":hurtby:" + attacker, 12_000L)) return;
        say(server, botName, fmt(HumanChat.pick("ow", "hey!", "ow wtf %p", "stoppp", "bro why", "ok rude"), attacker));
    }

    public static void lowHealth(MinecraftServer server, String botName) {
        if (!enabled() || !cooldown(botName + ":lowhp", 90_000L)) return;
        say(server, botName, HumanChat.pick("i'm almost dead", "low hp, gotta be careful", "need to heal up"));
    }

    public static void hungry(MinecraftServer server, String botName) {
        if (!enabled() || !cooldown(botName + ":hungry", 4 * 60_000L)) return;
        say(server, botName, HumanChat.pick("i'm starving", "anyone got food?", "need to eat something"));
    }

    public static void nightFalling(MinecraftServer server, String botName) {
        if (!enabled() || !chance(0.6) || !cooldown(botName + ":night", 15 * 60_000L)) return;
        say(server, botName, HumanChat.pick("getting dark", "night's coming, mobs soon", "should we find a bed?", "it's getting late"));
    }

    public static void morning(MinecraftServer server, String botName) {
        if (!enabled() || !chance(0.35) || !cooldown(botName + ":morning", 15 * 60_000L)) return;
        say(server, botName, HumanChat.pick("morning", "finally day", "ok it's day, let's go"));
    }

    public static void startedRaining(MinecraftServer server, String botName) {
        if (!enabled() || !chance(0.5) || !cooldown(botName + ":rain", 15 * 60_000L)) return;
        say(server, botName, HumanChat.pick("aw rain", "it's raining lol", "rain again..."));
    }

    public static void botDied(MinecraftServer server, String botName) {
        if (!enabled() || !cooldown(botName + ":botdied", 20_000L)) return;
        say(server, botName, HumanChat.pick("noo i died", "bruh", "ugh, i died", "well that went badly"));
    }

    public static void gotItem(MinecraftServer server, String botName, String player) {
        if (!enabled() || !cooldown(botName + ":gift:" + player, 10_000L)) return;
        say(server, botName, fmt(HumanChat.pick("ty %p", "oh thanks", "thx!"), player));
    }

    // ------------------------------------------------------------------------
    // Stance acknowledgements (follow / stay / wander / come)
    // ------------------------------------------------------------------------

    public static String followAck(String player) {
        return fmt(HumanChat.pick("ok coming", "right behind you", "sure, lead the way", "ok ok following", "omw"), player);
    }

    public static String stayAck() {
        return HumanChat.pick("ok i'll wait here", "k, staying", "sure, i'll stay put", "alright, waiting here");
    }

    public static String wanderAck() {
        return HumanChat.pick("ok i'll go do my own thing", "cool, gonna go explore", "alright, see ya around", "ok, i'll look around");
    }

    public static String comeAck() {
        return HumanChat.pick("coming", "omw", "ok one sec", "on my way");
    }

    // ------------------------------------------------------------------------
    // Quick replies to simple social chat (no LLM needed)
    // ------------------------------------------------------------------------

    private record Quick(Pattern pattern, List<String> lines, double chance) {}

    private static final List<Quick> QUICK = List.of(
            new Quick(Pattern.compile("^(hi+|hey+|hello+|yo+|sup|wassup|hiya|heya|o/|\\\\o)[!.\\s]*$"),
                    List.of("hey", "hey %p", "yo", "hi!", "o/", "sup"), 1.0),
            new Quick(Pattern.compile("^(ty|thx|thanks|thank you|tysm|thank u)( so much)?[!.\\s]*$"),
                    List.of("np", "no problem", "np!", "anytime", "ofc"), 1.0),
            new Quick(Pattern.compile("^(gg|ggs|gg wp)[!.\\s]*$"),
                    List.of("gg", "ggs", "gg!"), 0.9),
            new Quick(Pattern.compile("^(lol|lmao|lmfao|haha+|xd|rofl)[!.\\s]*$"),
                    List.of("lol", "haha", "lmao"), 0.35),
            new Quick(Pattern.compile("^(bye|cya|see ya|gn|good night|goodnight|later|brb)[!.\\s]*$"),
                    List.of("cya", "bye!", "later", "gn", "o/"), 1.0),
            new Quick(Pattern.compile("^(nice|good job|gj|well done|pog|poggers)[!.\\s]*$"),
                    List.of("ty", "thanks lol", "ikr"), 0.8),
            new Quick(Pattern.compile("^(sorry|sry|my bad|mb|oops)[!.\\s]*$"),
                    List.of("all good", "np", "it's fine lol", "no worries"), 0.9),
            new Quick(Pattern.compile("^(ok|okay|k|kk|alright|sure|cool|bet)[!.\\s]*$"),
                    List.of("cool", "ok"), 0.0) // acknowledged silently
    );

    /**
     * If {@code message} is small talk that a person would answer on reflex,
     * returns the reply (possibly empty = say nothing), otherwise {@code null}.
     */
    public static String quickReply(String botName, String player, String message) {
        String m = normalise(message, botName);
        if (m.isEmpty()) return null;
        for (Quick q : QUICK) {
            Matcher matcher = q.pattern().matcher(m);
            if (matcher.matches()) {
                if (!chance(q.chance())) return "";
                return fmt(HumanChat.pick(q.lines()), player);
            }
        }
        return null;
    }

    /** Lower-cases and removes the bot's name / leading "@" so "hey Steve!" == "hey". */
    static String normalise(String message, String botName) {
        String m = HumanChat.stripColor(message).toLowerCase(Locale.ROOT).trim();
        if (botName != null && !botName.isEmpty()) {
            String n = botName.toLowerCase(Locale.ROOT);
            m = m.replaceAll("@?\\b" + Pattern.quote(n) + "\\b[,:!]?", " ");
        }
        return m.replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------------
    // Idle small talk
    // ------------------------------------------------------------------------

    public static String idleRemark(String botName, IdleContext ctx) {
        List<String> pool = new java.util.ArrayList<>();
        if (ctx.night()) pool.addAll(List.of("kinda spooky out tonight", "hope no creepers sneak up on us", "we should probably build a proper base soon"));
        if (ctx.raining()) pool.addAll(List.of("this rain won't stop lol", "rain makes everything so gloomy"));
        if (ctx.hungry()) pool.addAll(List.of("i really need food", "anyone have bread or something?"));
        if (ctx.lowHealth()) pool.addAll(List.of("i should heal up tbh", "not feeling great, low hp"));
        if (ctx.holdingNothing()) pool.addAll(List.of("i need better tools ngl", "wish i had a pickaxe rn"));
        pool.addAll(List.of(
                "what are we doing next?",
                "we should go mining at some point",
                "this area's kinda nice actually",
                "i wanna find diamonds today",
                "you building anything?",
                "anyone wanna go exploring?",
                "ngl i could use some iron",
                "how's it going %p?",
                "wyd %p"
        ));
        return fmt(HumanChat.pick(pool), ctx.nearestPlayer());
    }

    public record IdleContext(boolean night, boolean raining, boolean hungry, boolean lowHealth,
                              boolean holdingNothing, String nearestPlayer) {}
}
