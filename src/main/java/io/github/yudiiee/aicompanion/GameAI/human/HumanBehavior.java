package io.github.yudiiee.aicompanion.GameAI.human;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;
import io.github.yudiiee.aicompanion.Entity.AutoFaceEntity;
import io.github.yudiiee.aicompanion.GameAI.autonomous.AutonomousManager;
import io.github.yudiiee.aicompanion.PathFinding.NavigationService;
import io.github.yudiiee.aicompanion.PlayerUtils.FoodConsumptionTool;
import io.github.yudiiee.aicompanion.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Body language for the bot, run on the server thread every tick.
 *
 * <p>When the bot isn't busy (not walking a path, mining, fighting or eating) it:
 * <ul>
 *   <li>looks at people with a smooth head turn instead of snapping, meets the eyes
 *       of whoever is talking to it, and glances away now and then;</li>
 *   <li>looks around at moving mobs and the scenery when nobody is near;</li>
 *   <li>crouches back when a player crouch-spams at it (the classic hello);</li>
 *   <li>does the occasional small fidget (hop, arm swing, look down);</li>
 *   <li>comments on nightfall, rain, low health or hunger, and makes a bit of
 *       small talk when it's been quiet for a while.</li>
 * </ul>
 */
public final class HumanBehavior {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-humanlike");
    private static final Random RNG = new Random();

    private static final class BotState {
        Entity focus;                 // entity being looked at, or null
        Vec3 focusPoint;              // fixed point being looked at, or null
        long focusUntil;              // tick when a new focus is chosen
        long nextFidget;
        int waterTicks;               // how long it has been in water
        long nextChatterCheck;
        long nextEnvCheck;
        boolean wasNight;
        boolean wasRaining;
        boolean initialised;
        final Deque<long[]> sneakPlan = new ArrayDeque<>(); // {tick, 1=sneak/0=stand}
        long lastCrouchReply;
        float swayPhase = RNG.nextFloat() * 10f;
    }

    private static final class WatchState {
        boolean wasSneaking;
        final Deque<Long> sneakPresses = new ArrayDeque<>();
    }

    private static final Map<UUID, BotState> BOTS = new ConcurrentHashMap<>();
    private static final Map<UUID, WatchState> WATCH = new ConcurrentHashMap<>();
    /** player UUID -> tick they last spoke (to anyone). */
    private static final Map<UUID, Long> LAST_SPOKE_TICK = new ConcurrentHashMap<>();
    /** bot UUID -> (player UUID, until tick) for "look at me, I'm talking to you". */
    private static final Map<UUID, Object[]> ATTENTION = new ConcurrentHashMap<>();

    private static long tick = 0;

    private HumanBehavior() {}

    /** Bots that just died, and the tick at which they leave the game. */
    private static final Map<UUID, Long> LEAVING = new ConcurrentHashMap<>();

    private static volatile long lastTickNanos = 0L;
    private static volatile boolean watchdogStarted = false;

    /**
     * Freeze detector: if the server thread stops ticking for 15 s, write what it is doing to
     * the log (a frozen integrated server otherwise leaves no trace before you kill the game).
     */
    private static void startWatchdog() {
        if (watchdogStarted) return;
        watchdogStarted = true;
        // If Java is being shut down normally (System.exit from somewhere, closing the window),
        // say so and show who asked. A hard kill (crash of native code, task manager, launcher)
        // runs no hooks, so a log that just stops means exactly that.
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    net.minecraft.server.MinecraftServer server = io.github.yudiiee.aicompanion.AICompanion.serverInstance;
                    boolean worldOpen = server != null && server.isRunning();
                    LOGGER.warn("[ai-companion watchdog] Java is shutting down{}", worldOpen ? " while a world is still open" : "");
                    if (!worldOpen) return;
                    for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                        StringBuilder sb = new StringBuilder();
                        boolean exiting = false;
                        for (StackTraceElement el : e.getValue()) {
                            sb.append("\n    at ").append(el);
                            if (el.getClassName().equals("java.lang.Shutdown") || el.getClassName().equals("java.lang.Runtime")) exiting = true;
                        }
                        String n = e.getKey().getName();
                        if (exiting || n.equals("Server thread") || n.equals("Render thread")) {
                            LOGGER.warn("[ai-companion watchdog] thread '{}' ({}):{}", n, e.getKey().getState(), sb);
                        }
                    }
                } catch (Throwable ignored) { }
            }, "ai-companion-shutdown-report"));
        } catch (Throwable ignored) { }
        Thread t = new Thread(() -> {
            long reportedFor = 0L;
            String renderSig = null;
            int renderSame = 0;
            while (true) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                // the game window itself stuck on the same spot for 20 s?
                try {
                    for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                        if (!e.getKey().getName().equals("Render thread")) continue;
                        StackTraceElement[] st = e.getValue();
                        StringBuilder sig = new StringBuilder();
                        for (int i = 0; i < Math.min(8, st.length); i++) sig.append(st[i]).append('|');
                        if (sig.toString().equals(renderSig) && e.getKey().getState() != Thread.State.TIMED_WAITING) {
                            if (++renderSame == 4) {
                                StringBuilder sb = new StringBuilder();
                                for (StackTraceElement el : st) sb.append("\n    at ").append(el);
                                LOGGER.error("[ai-companion watchdog] The game window (render thread) looks frozen for 20 s ({}):{}", e.getKey().getState(), sb);
                                CrashDiagnostics.write("render thread frozen for 20 s (" + e.getKey().getState() + "):" + sb);
                            }
                        } else {
                            renderSig = sig.toString();
                            renderSame = 0;
                        }
                    }
                } catch (Throwable ignored) { }
                long last = lastTickNanos;
                if (last == 0L) continue;
                long stalled = (System.nanoTime() - last) / 1_000_000L;
                if (stalled < 15_000L || last == reportedFor) continue;
                reportedFor = last;
                try {
                    net.minecraft.server.MinecraftServer server = io.github.yudiiee.aicompanion.AICompanion.serverInstance;
                    if (server == null || !server.isRunning()) continue;
                    for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                        if (!e.getKey().getName().equals("Server thread")) continue;
                        StringBuilder sb = new StringBuilder();
                        boolean ours = false;
                        for (StackTraceElement el : e.getValue()) {
                            sb.append("\n    at ").append(el);
                            if (el.getClassName().startsWith("io.github.yudiiee.aicompanion")) ours = true;
                        }
                        // a paused single-player world just sleeps: only report a busy or blocked-in-mod thread
                        if (e.getKey().getState() != Thread.State.RUNNABLE && !ours) continue;
                        LOGGER.error("[ai-companion watchdog] The server thread has been stuck for {} s. It is doing:{}", stalled / 1000, sb);
                        CrashDiagnostics.write("server thread stuck for " + stalled / 1000 + " s:" + sb);
                    }
                } catch (Throwable ignored) { }
            }
        }, "ai-companion-watchdog");
        t.setDaemon(true);
        t.start();
    }

    public static void register() {
        CrashDiagnostics.start();
        startWatchdog();
        ServerTickEvents.END_SERVER_TICK.register(HumanBehavior::onTick);
        ServerTickEvents.END_SERVER_TICK.register(PvpController::tick);
        ServerTickEvents.END_SERVER_TICK.register(BotPathing::tick);
        // A mob hit one of our bots: it fights back (mobs that leave it alone are ignored).
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            try {
                if (entity instanceof ServerPlayer p && isAiBot(p)
                        && source.getEntity() instanceof net.minecraft.world.entity.LivingEntity mob
                        && !(mob instanceof net.minecraft.world.entity.player.Player)) {
                    PvpController.defend(p, mob);
                }
            } catch (Throwable t) {
                LOGGER.debug("[humanlike] defend failed: {}", t.toString());
            }
            return true;
        });
        // A bot that dies leaves the game (like Carpet bots). Its own die() only asks the fake
        // network connection to close, which never happens on 26.3, so finish the job here.
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer p && isAiBot(p)) {
                LEAVING.put(p.getUUID(), tick + 10);
                PvpController.onDeath(p);
            }
        });
        LOGGER.info("[humanlike] body-language controller registered");
    }

    // ------------------------------------------------------------------------
    // Identification
    // ------------------------------------------------------------------------

    /** Any automated player: this mod's bots or Carpet fake players. */
    public static boolean isBot(ServerPlayer p) {
        if (p == null) return false;
        if (isAiBot(p)) return true;
        return p.getClass().getName().endsWith("EntityPlayerMPFake");
    }

    /**
     * Whether a hostile entity is a threat a player would react to: close, not far above or
     * below, and either very near or actually visible (not behind walls / underground).
     */
    public static boolean isRealThreat(ServerPlayer bot, Entity e) {
        // Mobs are left alone until they attack; then PvpController.defend fights back.
        // Only players flagged hostile by retaliation tracking count here.
        try {
            return e instanceof ServerPlayer && e.distanceToSqr(bot) <= 24 * 24;
        } catch (Exception ex) {
            return false;
        }
    }

    /** Players driven by this mod. */
    public static boolean isAiBot(ServerPlayer p) {
        if (p == null) return false;
        if (p instanceof io.github.yudiiee.aicompanion.Entity.createFakePlayer) return true;
        try {
            return AutonomousManager.getInstance().isRunning(p.getName().getString());
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------
    // Hooks called from elsewhere
    // ------------------------------------------------------------------------

    /** A real player said something in chat. */
    public static void onPlayerChat(ServerPlayer speaker) {
        LAST_SPOKE_TICK.put(speaker.getUUID(), tick);
    }

    /** Make {@code bot} look at {@code player} for a few seconds (being talked to, got hit...). */
    public static void payAttention(ServerPlayer bot, ServerPlayer player, int ticks) {
        if (bot == null || player == null) return;
        ATTENTION.put(bot.getUUID(), new Object[]{player.getUUID(), tick + ticks});
        BotState s = BOTS.get(bot.getUUID());
        if (s != null) s.focusUntil = 0; // re-pick focus next tick
    }

    public static void onBotHurtByPlayer(ServerPlayer bot, ServerPlayer attacker) {
        if (bot == null || attacker == null || isBot(attacker)) return;
        if (PvpController.isFighting(bot.getUUID())) return; // it's a fight, getting hit is expected
        payAttention(bot, attacker, 60);
        HumanReactions.hurtByPlayer(bot.level().getServer(), bot.getName().getString(), attacker.getName().getString());
    }

    /**
     * Under water and running out of air with nothing steering it (mining, a job step, idle):
     * swim up like anyone would. Trips have their own version of this in BotPathing.
     */
    private static void keepBreathing(ServerPlayer bot, BotState st) {
        try {
            if (!bot.isInWater()) { st.waterTicks = 0; return; }
            st.waterTicks++;
            if (!(bot instanceof carpet.fakes.ServerPlayerInterface spi)) return;
            var ap = spi.getActionPack();
            if (bot.isUnderWater() && bot.getAirSupply() < 150) {
                ap.look(bot.getYRot(), -60f);
                ap.start(carpet.helpers.EntityPlayerActionPack.ActionType.JUMP, carpet.helpers.EntityPlayerActionPack.Action.once());
            }
            // treading water with nowhere to go (a job ended mid-lake, knocked in...): swim to the nearest shore
            if (st.waterTicks > 20 * 8 && st.waterTicks % 40 == 0 && !BotPathing.isActive(bot.getUUID())
                    && !PvpController.isFighting(bot.getUUID())) {
                BlockPos land = nearestLand(bot);
                if (land != null) {
                    BotPathing.Options o = BotPathing.Options.walkOnly();
                    o.timeoutTicks = 20 * 40;
                    BotPathing.goTo(bot, ActionPathfinder.near(land.getX(), land.getY(), land.getZ(), 1.0), o);
                }
            }
        } catch (Throwable ignored) { }
    }

    /** Closest dry spot to stand on (solid ground, two free blocks above, no water), within 32 blocks. */
    static BlockPos nearestLand(ServerPlayer bot) {
        var level = bot.level();
        BlockPos f = bot.blockPosition();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int r = 1; r <= 32 && best == null; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // this ring only
                    for (int dy = -3; dy <= 4; dy++) {
                        BlockPos p = f.offset(dx, dy, dz);
                        if (!level.isLoaded(p)) continue;
                        if (!level.getFluidState(p).isEmpty() || !level.getFluidState(p.above()).isEmpty()) continue;
                        if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) continue;
                        if (!level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) continue;
                        BlockPos under = p.below();
                        if (level.getBlockState(under).getCollisionShape(level, under).isEmpty() || !level.getFluidState(under).isEmpty()) continue;
                        double d = p.distSqr(f);
                        if (d < bd) { bd = d; best = p; }
                    }
                }
            }
        }
        return best;
    }

    public static void forget(UUID botId) {
        BOTS.remove(botId);
        Reflexes.forget(botId);
        ATTENTION.remove(botId);
    }

    // ------------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------------

    private static void onTick(MinecraftServer server) {
        lastTickNanos = System.nanoTime();
        if (!LEAVING.isEmpty()) {
            for (Map.Entry<UUID, Long> e : LEAVING.entrySet()) {
                if (e.getValue() > tick + 1) continue;
                LEAVING.remove(e.getKey());
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p == null) continue;
                try {
                    p.connection.onDisconnect(new net.minecraft.network.DisconnectionDetails(
                            net.minecraft.network.chat.Component.literal("Died")));
                } catch (Throwable t) {
                    LOGGER.warn("[humanlike] could not remove dead bot {}: {}", p.getName().getString(), t.toString());
                }
            }
        }
        tick++;
        try {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) return;
            List<ServerPlayer> humans = new ArrayList<>();
            List<ServerPlayer> bots = new ArrayList<>();
            for (ServerPlayer p : players) {
                if (isAiBot(p)) bots.add(p);
                else if (!isBot(p)) humans.add(p);
            }
            if (bots.isEmpty()) return;

            trackCrouching(humans);

            for (ServerPlayer bot : bots) {
                if (!bot.isAlive() || bot.hasDisconnected()) continue;
                BotState st = BOTS.computeIfAbsent(bot.getUUID(), k -> new BotState());
                if (!st.initialised) {
                    st.initialised = true;
                    st.nextFidget = tick + 20L * (40 + RNG.nextInt(80));
                    st.nextChatterCheck = tick + 20L * 60;
                    st.nextEnvCheck = tick + 100;
                    st.wasNight = isNight(bot);
                    st.wasRaining = bot.level().isRaining();
                }
                SurvivalBrain.ensureRunning(bot);
                // lava, fire, suffocation first: nothing else matters until that's sorted
                if (Reflexes.tick(bot)) continue;
                keepBreathing(bot, st);
                // creepers and skeletons get dealt with on sight; other mobs only once they hit
                if (tick % 10 == 0) PvpController.engageNearby(bot);
                runSneakPlan(bot, st);
                if (HumanConfig.get().bodyLanguage && isIdle(bot)) {
                    updateGaze(bot, st, humans);
                    maybeCrouchBack(bot, st, humans);
                    maybeFidget(bot, st, humans);
                }
                if (tick >= st.nextEnvCheck) {
                    st.nextEnvCheck = tick + 100; // every 5 s
                    environmentReactions(server, bot, st, humans);
                }
                if (tick >= st.nextChatterCheck && !PvpController.isFighting(bot.getUUID())) {
                    st.nextChatterCheck = tick + 20L * 20; // every 20 s
                    maybeSmallTalk(server, bot, humans);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[humanlike] tick error: {}", e.toString());
        }
    }

    /** Busy = doing something where the task needs control of the head/body. */
    private static boolean isIdle(ServerPlayer bot) {
        UUID id = bot.getUUID();
        if (bot.isSleeping()) return false;
        if (NavigationService.isNavigating(id)) return false;
        if (MiningTool.isMining(id)) return false;
        if (SurvivalBrain.isWorking(bot) || PvpController.isFighting(id) || BotPathing.isActive(id)) return false;
        if (FoodConsumptionTool.isConsumptionInProgress(id)) return false;
        if (AutoFaceEntity.hostileEntityInFront || AutoFaceEntity.isShooting
                || AutoFaceEntity.isActivelyBlocking || AutoFaceEntity.isDefendingFromProjectile
                || AutoFaceEntity.isBotExecutingTask()) return false;
        return true;
    }

    // ------------------------------------------------------------------------
    // Gaze
    // ------------------------------------------------------------------------

    private static void updateGaze(ServerPlayer bot, BotState st, List<ServerPlayer> humans) {
        if (tick >= st.focusUntil || (st.focus != null && (!st.focus.isAlive() || st.focus.distanceToSqr(bot) > 32 * 32))) {
            pickFocus(bot, st, humans);
        }
        Vec3 target = null;
        if (st.focus != null) target = st.focus.getEyePosition();
        else if (st.focusPoint != null) target = st.focusPoint;
        if (target == null) return;

        Vec3 eye = bot.getEyePosition();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 0.05 && Math.abs(dy) < 0.05) return;
        float wantYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float wantPitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));

        // tiny idle sway so the head is never perfectly frozen
        st.swayPhase += 0.05f;
        wantYaw += (float) Math.sin(st.swayPhase) * 1.2f;
        wantPitch += (float) Math.sin(st.swayPhase * 0.7f) * 0.8f;

        float curYaw = bot.getYRot();
        float curPitch = bot.getXRot();
        float dYaw = wrap(wantYaw - curYaw);
        float dPitch = wantPitch - curPitch;

        // Fast start, slow settle: a fraction of the remaining angle, capped per tick
        float yawStep = clamp(dYaw * 0.28f, -16f, 16f);
        float pitchStep = clamp(dPitch * 0.25f, -10f, 10f);
        if (Math.abs(dYaw) < 0.4f) yawStep = dYaw;
        if (Math.abs(dPitch) < 0.4f) pitchStep = dPitch;

        float newYaw = curYaw + yawStep;
        float newPitch = clamp(curPitch + pitchStep, -80f, 80f);
        bot.setYRot(newYaw);
        bot.setXRot(newPitch);
        bot.setYHeadRot(newYaw);
    }

    private static void pickFocus(ServerPlayer bot, BotState st, List<ServerPlayer> humans) {
        st.focus = null;
        st.focusPoint = null;

        // 1. Someone we're paying attention to (talking with us, just hit us...)
        Object[] att = ATTENTION.get(bot.getUUID());
        if (att != null) {
            if ((long) att[1] > tick) {
                for (ServerPlayer h : humans) {
                    if (h.getUUID().equals(att[0]) && h.distanceToSqr(bot) < 24 * 24) {
                        st.focus = h;
                        st.focusUntil = tick + 30 + RNG.nextInt(40);
                        return;
                    }
                }
            } else {
                ATTENTION.remove(bot.getUUID());
            }
        }

        // 2. Nearby people: whoever spoke most recently, else the closest
        ServerPlayer best = null;
        double bestScore = Double.MAX_VALUE;
        for (ServerPlayer h : humans) {
            double d2 = h.distanceToSqr(bot);
            if (d2 > 14 * 14 || h.isSpectator()) continue;
            long spoke = LAST_SPOKE_TICK.getOrDefault(h.getUUID(), -10_000L);
            double score = d2 - (tick - spoke < 200 ? 400 : 0);
            if (score < bestScore) { bestScore = score; best = h; }
        }
        if (best != null && RNG.nextDouble() < 0.72) {
            st.focus = best;
            st.focusUntil = tick + 40 + RNG.nextInt(100); // 2-7 s
            return;
        }

        // 3. Something moving nearby (animals, mobs walking past, items flying)
        List<Entity> candidates = new ArrayList<>();
        for (Entity e : bot.level().getEntities(bot, bot.getBoundingBox().inflate(12.0))) {
            if (e instanceof LivingEntity && !(e instanceof ServerPlayer) && e.getDeltaMovement().lengthSqr() > 0.002) {
                candidates.add(e);
            }
        }
        if (!candidates.isEmpty() && RNG.nextDouble() < 0.45) {
            st.focus = candidates.get(RNG.nextInt(candidates.size()));
            st.focusUntil = tick + 25 + RNG.nextInt(50);
            return;
        }

        // 4. Look around: somewhere near the horizon, sometimes at the ground or sky
        double yawOffset = (RNG.nextDouble() - 0.5) * (best != null ? 110 : 160);
        double yaw = Math.toRadians(bot.getYRot() + yawOffset);
        double pitch = RNG.nextDouble() < 0.2 ? 35 + RNG.nextDouble() * 25   // at the ground
                : RNG.nextDouble() < 0.1 ? -30 - RNG.nextDouble() * 20       // at the sky
                : -5 + RNG.nextDouble() * 15;
        double dist = 8.0;
        double px = -Math.sin(yaw) * Math.cos(Math.toRadians(pitch)) * dist;
        double pz = Math.cos(yaw) * Math.cos(Math.toRadians(pitch)) * dist;
        double py = -Math.sin(Math.toRadians(pitch)) * dist;
        Vec3 eye = bot.getEyePosition();
        st.focusPoint = new Vec3(eye.x + px, eye.y + py, eye.z + pz);
        st.focusUntil = tick + 30 + RNG.nextInt(70);
    }

    // ------------------------------------------------------------------------
    // Crouch greeting
    // ------------------------------------------------------------------------

    private static void trackCrouching(List<ServerPlayer> humans) {
        for (ServerPlayer h : humans) {
            WatchState w = WATCH.computeIfAbsent(h.getUUID(), k -> new WatchState());
            boolean sneaking = h.isShiftKeyDown();
            if (sneaking && !w.wasSneaking) w.sneakPresses.addLast(tick);
            w.wasSneaking = sneaking;
            while (!w.sneakPresses.isEmpty() && tick - w.sneakPresses.peekFirst() > 50) w.sneakPresses.removeFirst();
        }
    }

    private static void maybeCrouchBack(ServerPlayer bot, BotState st, List<ServerPlayer> humans) {
        if (!st.sneakPlan.isEmpty() || tick - st.lastCrouchReply < 20 * 15) return;
        for (ServerPlayer h : humans) {
            if (h.distanceToSqr(bot) > 8 * 8) continue;
            WatchState w = WATCH.get(h.getUUID());
            if (w == null || w.sneakPresses.size() < 2) continue;
            // crouch back 2-3 times after a short reaction delay
            st.lastCrouchReply = tick;
            w.sneakPresses.clear();
            payAttention(bot, h, 80);
            long t = tick + 6 + RNG.nextInt(8);
            int times = 2 + RNG.nextInt(2);
            for (int i = 0; i < times; i++) {
                st.sneakPlan.addLast(new long[]{t, 1});
                st.sneakPlan.addLast(new long[]{t + 3 + RNG.nextInt(2), 0});
                t += 6 + RNG.nextInt(3);
            }
            return;
        }
    }

    private static void runSneakPlan(ServerPlayer bot, BotState st) {
        Iterator<long[]> it = st.sneakPlan.iterator();
        while (it.hasNext()) {
            long[] step = it.next();
            if (step[0] > tick) break;
            setSneaking(bot, step[1] == 1);
            it.remove();
        }
    }

    private static void setSneaking(ServerPlayer bot, boolean sneak) {
        if (bot instanceof ServerPlayerInterface spi) {
            spi.getActionPack().setSneaking(sneak);
        }
    }

    // ------------------------------------------------------------------------
    // Fidgets
    // ------------------------------------------------------------------------

    private static void maybeFidget(ServerPlayer bot, BotState st, List<ServerPlayer> humans) {
        if (tick < st.nextFidget) return;
        st.nextFidget = tick + 20L * (35 + RNG.nextInt(100));
        boolean someoneNear = false;
        for (ServerPlayer h : humans) if (h.distanceToSqr(bot) < 20 * 20) { someoneNear = true; break; }
        if (!someoneNear) return; // nobody to see it
        int roll = RNG.nextInt(10);
        if (roll < 3 && bot.onGround() && bot instanceof ServerPlayerInterface spi) {
            spi.getActionPack().start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
        } else if (roll < 6) {
            Motions.swingArm(bot);
        } else {
            // glance down at hands / feet for a moment
            Vec3 eye = bot.getEyePosition();
            double yaw = Math.toRadians(bot.getYRot());
            st.focus = null;
            st.focusPoint = new Vec3(eye.x - Math.sin(yaw) * 1.5, eye.y - 1.6, eye.z + Math.cos(yaw) * 1.5);
            st.focusUntil = tick + 25 + RNG.nextInt(20);
        }
    }

    // ------------------------------------------------------------------------
    // Talking about what's going on
    // ------------------------------------------------------------------------

    private static boolean isNight(ServerPlayer bot) {
        long t = bot.level().getDefaultClockTime() % 24000L;
        return t >= 12800 && t < 23200;
    }

    private static ServerPlayer nearestHuman(ServerPlayer bot, List<ServerPlayer> humans, double range) {
        ServerPlayer best = null;
        double bd = range * range;
        for (ServerPlayer h : humans) {
            if (h.level() != bot.level()) continue;
            double d = h.distanceToSqr(bot);
            if (d < bd) { bd = d; best = h; }
        }
        return best;
    }

    private static void environmentReactions(MinecraftServer server, ServerPlayer bot, BotState st, List<ServerPlayer> humans) {
        String name = bot.getName().getString();
        boolean night = isNight(bot);
        boolean raining = bot.level().isRaining();
        boolean company = nearestHuman(bot, humans, 48) != null;
        if (company) {
            if (night && !st.wasNight) HumanReactions.nightFalling(server, name);
            if (!night && st.wasNight) HumanReactions.morning(server, name);
            if (raining && !st.wasRaining) HumanReactions.startedRaining(server, name);
            if (bot.getHealth() <= 6.0f) HumanReactions.lowHealth(server, name);
            if (bot.getFoodData().getFoodLevel() <= 6) HumanReactions.hungry(server, name);
        }
        st.wasNight = night;
        st.wasRaining = raining;
    }

    private static void maybeSmallTalk(MinecraftServer server, ServerPlayer bot, List<ServerPlayer> humans) {
        if (!HumanConfig.get().idleChatter || !isIdle(bot)) return;
        String name = bot.getName().getString();
        ServerPlayer near = nearestHuman(bot, humans, 20);
        if (near == null) return;
        long minGap = Math.max(1, HumanConfig.get().idleChatterMinMinutes) * 60_000L;
        if (ConversationMemory.sinceAnyPlayerChat() < 2 * 60_000L) return; // people are already talking
        if (ConversationMemory.sinceBotSpoke(name) < minGap) return;
        if (RNG.nextDouble() > 0.25) return;
        if (!HumanReactions.cooldown(name + ":smalltalk", minGap)) return;
        for (Entity e : bot.level().getEntities(bot, bot.getBoundingBox().inflate(16.0))) {
            if (e instanceof Monster) return; // not the moment for chit-chat
        }
        var ctx = new HumanReactions.IdleContext(isNight(bot), bot.level().isRaining(),
                bot.getFoodData().getFoodLevel() <= 8, bot.getHealth() <= 8.0f,
                bot.getMainHandItem().isEmpty(), near.getName().getString());
        payAttention(bot, near, 60);
        HumanChat.say(server, name, HumanReactions.idleRemark(name, ctx));
    }

    // ------------------------------------------------------------------------
    // math
    // ------------------------------------------------------------------------

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
