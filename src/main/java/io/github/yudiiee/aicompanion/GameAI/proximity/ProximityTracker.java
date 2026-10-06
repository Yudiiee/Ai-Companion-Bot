package io.github.yudiiee.aicompanion.GameAI.proximity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import io.github.yudiiee.aicompanion.GameAI.mood.AffectiveState;
import io.github.yudiiee.aicompanion.GameAI.mood.MoodEngine;
import io.github.yudiiee.aicompanion.GameAI.mood.MoodLabel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature 5 — Player Proximity Awareness.
 */
public final class ProximityTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("proximity-tracker");
    private static final Random RNG = new Random();

    public static final double GREET_RADIUS = 6.0;
    public static final double LINGER_RADIUS = 4.0;
    public static final int LINGER_THRESHOLD_TICKS = 150;
    private static final int LINGER_THRESHOLD_SECONDS = 5;
    public static final long LINGER_COOLDOWN_MS = 6 * 60_000L;

    private static final float GREET_VALENCE_BOOST = 0.05f;
    private static final float GREET_AROUSAL_BOOST = 0.03f;

    private static final ConcurrentHashMap<UUID, Long>    lingerCooldowns = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> lingerTicks     = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Reaction pools  — every MoodLabel constant covered
    // -------------------------------------------------------------------------

    private static final Map<MoodLabel, List<String>> GREET_REACTIONS = Map.ofEntries(
        Map.entry(MoodLabel.ELATED,   List.of("%s!! hey", "yooo %s", "ayy %s")),
        Map.entry(MoodLabel.EXCITED,  List.of("hey %s!", "oh hey %s", "%s! what's up")),
        Map.entry(MoodLabel.CONTENT,  List.of("hey %s", "sup %s", "o/")),
        Map.entry(MoodLabel.SERENE,   List.of("hey %s", "oh hi %s")),
        Map.entry(MoodLabel.NEUTRAL,  List.of("hey", "hi %s", "o/")),
        Map.entry(MoodLabel.CALM,     List.of("hey %s", "hi")),
        Map.entry(MoodLabel.BORED,    List.of("oh hey %s", "hi %s... i'm so bored")),
        Map.entry(MoodLabel.AGITATED, List.of("oh. hi %s", "what's up %s")),
        Map.entry(MoodLabel.DEPRESSED,List.of("hey %s...", "hi"))
    );

    private static final Map<MoodLabel, List<String>> LINGER_REACTIONS = Map.ofEntries(
        Map.entry(MoodLabel.ELATED,   List.of("so what's the plan %s?", "we should do something fun")),
        Map.entry(MoodLabel.EXCITED,  List.of("what are we doing?", "you need anything %s?")),
        Map.entry(MoodLabel.CONTENT,  List.of("you good %s?", "what's up?")),
        Map.entry(MoodLabel.SERENE,   List.of("nice here huh", "chillin")),
        Map.entry(MoodLabel.NEUTRAL,  List.of("need something?", "what's up %s")),
        Map.entry(MoodLabel.CALM,     List.of("all good?", "you ok %s?")),
        Map.entry(MoodLabel.BORED,    List.of("wanna do something? i'm bored", "sooo... what now")),
        Map.entry(MoodLabel.AGITATED, List.of("you need something?", "uh, hi?")),
        Map.entry(MoodLabel.DEPRESSED,List.of("hey... what's up", "sorry, kinda out of it"))
    );

    private static final List<String> FALLBACK_GREET  = List.of("hey %s", "hi %s");
    private static final List<String> FALLBACK_LINGER = List.of("what's up %s?", "need something?");

    private ProximityTracker() {}

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    public static void tick(ServerPlayer bot, List<Entity> nearbyEntities) {
        if (io.github.yudiiee.aicompanion.GameAI.human.PvpController.isFighting(bot.getUUID())) return; // no "what's up" mid-fight
        if (bot == null || !bot.isAlive()) return;

        String botName = bot.getName().getString();
        long now = System.currentTimeMillis();

        for (Entity entity : nearbyEntities) {
            if (!(entity instanceof ServerPlayer player)) continue;
            if (player.getUUID().equals(bot.getUUID())) continue;
            if (!player.connection.isAcceptingMessages()) continue;

            String playerName = player.getName().getString();
            UUID pid = player.getUUID();
            double dist = Math.sqrt(player.distanceToSqr(bot));

            // 1. Approach greeting
            if (dist <= GREET_RADIUS) {
                if (GreetingCooldownTracker.tryAcquire(botName, playerName)) {
                    fireReaction(bot, botName, playerName, GREET_REACTIONS, FALLBACK_GREET);
                    MoodEngine.applyDelta(botName, GREET_VALENCE_BOOST, GREET_AROUSAL_BOOST);
                    LOGGER.debug("[proximity] Greeted {} (dist={})", playerName, String.format("%.1f", dist));
                }
            }

            // 2. Linger tick / comment
            if (dist <= LINGER_RADIUS) {
                int ticks = lingerTicks.merge(pid, 1, Integer::sum);
                if (ticks >= LINGER_THRESHOLD_TICKS) {
                    long lastLinger = lingerCooldowns.getOrDefault(pid, 0L);
                    if (now - lastLinger >= LINGER_COOLDOWN_MS) {
                        lingerCooldowns.put(pid, now);
                        lingerTicks.put(pid, 0);
                        fireReaction(bot, botName, playerName, LINGER_REACTIONS, FALLBACK_LINGER);
                        LOGGER.debug("[proximity] Linger comment for {} ({}+ ticks)", playerName, LINGER_THRESHOLD_TICKS);
                    } else {
                        lingerTicks.put(pid, 0);
                    }
                }
            } else {
                lingerTicks.remove(pid);
            }
        }
    }

    public static void evict(UUID playerUuid) {
        lingerCooldowns.remove(playerUuid);
        lingerTicks.remove(playerUuid);
    }

    public static void clear() {
        GreetingCooldownTracker.clear();
        lingerCooldowns.clear();
        lingerTicks.clear();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void fireReaction(
            ServerPlayer bot,
            String botName,
            String playerName,
            Map<MoodLabel, List<String>> pool,
            List<String> fallback) {

        AffectiveState state = MoodEngine.get(botName);
        MoodLabel label = MoodLabel.from(state);

        List<String> reactions = pool.getOrDefault(label, fallback);
        String template = reactions.get(RNG.nextInt(reactions.size()));

        String message;
        try {
            message = String.format(template, playerName, botName);
        } catch (Exception e) {
            message = playerName + "!";
        }

        if (!io.github.yudiiee.aicompanion.GameAI.human.HumanConfig.get().reactions) return;
        io.github.yudiiee.aicompanion.GameAI.human.HumanChat.say(bot.level().getServer(), botName, message);
    }
}
