package io.github.yudiiee.aicompanion.GameAI.human;

import carpet.fakes.ServerPlayerInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import io.github.yudiiee.aicompanion.AICompanion;
import io.github.yudiiee.aicompanion.Entity.AutoFaceEntity;
import io.github.yudiiee.aicompanion.Entity.LookController;
import io.github.yudiiee.aicompanion.GameAI.autonomous.AutonomousGoalEngine;
import io.github.yudiiee.aicompanion.GameAI.autonomous.AutonomousManager;
import io.github.yudiiee.aicompanion.GameAI.companion.BotStance;
import io.github.yudiiee.aicompanion.GameAI.companion.CompanionController;
import io.github.yudiiee.aicompanion.PathFinding.NavigationOptions;
import io.github.yudiiee.aicompanion.PathFinding.NavigationResult;
import io.github.yudiiee.aicompanion.PathFinding.NavigationService;
import io.github.yudiiee.aicompanion.PlayerUtils.FoodConsumptionTool;
import io.github.yudiiee.aicompanion.PlayerUtils.MiningResult;
import io.github.yudiiee.aicompanion.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A built-in "player brain" that makes the bot actually play Minecraft, with or
 * without a language model.
 *
 * <p>It works like a new player on a fresh world: pick up stuff lying around, punch
 * trees, craft planks/sticks/a crafting table and a wooden pickaxe, mine stone for a
 * stone pickaxe, go after ores it can see, eat when hungry, explore a little but stay
 * in the same area as its friends, and take breaks. Everything is done with the same
 * movement / mining tools the rest of the mod uses, so it walks, swings and mines like
 * a player. Crafting is done straight in the inventory (no crafting-table GUI), using
 * the vanilla recipe amounts.
 *
 * <p>The brain only acts when the bot is free: not following/staying on command, not
 * fighting, not busy with a plan from the language model.
 */
public final class SurvivalBrain {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-brain");
    private static final Random RNG = new Random();

    public enum Task { AUTO, WOOD, STONE, ORE, EXPLORE }

    /** A longer job a player asked for (collect N blocks, strip mine...). Runs on the brain thread. */
    interface Job {
        void run(MinecraftServer server, ServerPlayer bot, Brain b) throws InterruptedException;
    }

    static final class Brain {
        final UUID id;
        final String name;
        volatile boolean stop;
        volatile Task commanded = null;
        volatile String wantedOre = null;
        volatile boolean fromPlayer = false;
        volatile long commandUntil = 0;
        volatile long restUntil = 0;
        long lastSeen = System.currentTimeMillis();
        volatile Job job = null;
        volatile String jobName = null;
        volatile boolean jobFromPlayer = false;
        volatile long jobSeq = 0;
        volatile long runningSeq = -1;
        volatile long lastStore = 0;
        int stockTick = 0;
        final Map<BlockPos, Long> blacklist = new ConcurrentHashMap<>();
        Thread thread;
        Brain(UUID id, String name) { this.id = id; this.name = name; }
    }

    private static final Map<UUID, Brain> BRAINS = new ConcurrentHashMap<>();

    private SurvivalBrain() {}

    // ------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------

    /** Called every tick for AI bots; starts a brain thread the first time. */
    public static void ensureRunning(ServerPlayer bot) {
        Brain b = BRAINS.get(bot.getUUID());
        if (b != null && !b.stop) { b.lastSeen = System.currentTimeMillis(); return; }
        Brain nb = new Brain(bot.getUUID(), bot.getName().getString());
        BRAINS.put(nb.id, nb);
        Thread t = new Thread(() -> loop(nb), "ai-companion-brain-" + nb.name);
        t.setDaemon(true);
        nb.thread = t;
        t.start();
        LOGGER.info("[brain] started for {}", nb.name);
    }

    public static void stopAll() {
        for (Brain b : BRAINS.values()) {
            b.stop = true;
            if (b.thread != null) b.thread.interrupt();
        }
        BRAINS.clear();
    }

    // ------------------------------------------------------------------------
    // Commands from chat
    // ------------------------------------------------------------------------

    public static void command(ServerPlayer bot, Task task, String ore) {
        Brain b = BRAINS.get(bot.getUUID());
        if (b == null) { ensureRunning(bot); b = BRAINS.get(bot.getUUID()); }
        b.commanded = task;
        b.fromPlayer = true;
        b.wantedOre = ore;
        b.commandUntil = System.currentTimeMillis() + 6 * 60_000L;
        b.restUntil = 0;
        // Doing a job means leaving follow/stay mode, like a person would.
        CompanionController companion = CompanionController.getInstance();
        if (companion.getStance(b.name) != BotStance.WANDER) companion.setStanceQuiet(b.name, BotStance.WANDER, null);
    }

    /** "play", "do something": back to deciding for itself. */
    public static void play(ServerPlayer bot) {
        Brain b = BRAINS.get(bot.getUUID());
        if (b == null) { ensureRunning(bot); b = BRAINS.get(bot.getUUID()); }
        b.commanded = null;
        b.restUntil = 0;
        CompanionController companion = CompanionController.getInstance();
        if (companion.getStance(b.name) != BotStance.WANDER) companion.setStanceQuiet(b.name, BotStance.WANDER, null);
    }

    /** Stay idle (no own initiative) for a while, e.g. after walking over to someone. */
    public static void pause(ServerPlayer bot, long millis) {
        Brain b = BRAINS.get(bot.getUUID());
        if (b != null) {
            b.commanded = null;
            b.job = null;
            b.jobSeq++;
            b.restUntil = System.currentTimeMillis() + millis;
        }
    }

    /**
     * Runs a plan step coming from the language model ("break a log", "mine 16 stone",
     * "find iron", "craft a crafting table", "explore") with the built-in abilities and
     * waits for it (up to {@code maxMillis}). Returns false if the text isn't understood.
     */
    public static boolean runGoalText(ServerPlayer bot, String goal, long maxMillis) {
        return runGoalText(bot, goal, maxMillis, false);
    }

    /** True when the model's plan must not start something now (a command, rest, job or walk is on). */
    static boolean planBlocked(ServerPlayer bot) {
        if (CompanionController.getInstance().getStance(bot.getName().getString()) != BotStance.WANDER) return true;
        if (BotPathing.isActive(bot.getUUID())) return true;
        Brain b = BRAINS.get(bot.getUUID());
        if (b == null) return false;
        return b.job != null || b.commanded != null || b.runningSeq != -1
                || System.currentTimeMillis() < b.restUntil;
    }

    public static boolean runGoalText(ServerPlayer bot, String goal, long maxMillis, boolean fromPlan) {
        String g = goal == null ? "" : goal.toLowerCase(Locale.ROOT);
        if (fromPlan && planBlocked(bot)) {
            // The model's plan never cancels a command, a rest or the bot's own job; wait, then ask again.
            try { Thread.sleep(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return true;
        }

        // "build a shelter", "store items in the chest": real jobs
        MiningSkills.Request special = House.request(g, null);
        if (special == null) special = Storage.request(g, null);
        if (special == null) {
            Blueprints.Ask ask = Blueprints.parse(g, null, bot);
            if (ask != null && ask.job() != null) special = ask.job();
        }
        if (special == null) special = MineHub.parseDigDown(g, null);
        if (special == null) special = Farm.request(g);
        if (special != null) return runPlanJob(bot, special, maxMillis);

        // "mine 3 iron ore", "collect 16 oak logs", "strip mine for diamonds": real jobs
        MiningSkills.Request req = MiningSkills.parseStrip(g);
        if (req == null) req = MiningSkills.parseCollect(g.replaceAll("^\\s*(\\d+[.)]\\s*|-\\s*|step \\d+:?\\s*)", ""));
        if (req != null) return runPlanJob(bot, req, maxMillis);

        // A job a player asked for (or the bot's own base) isn't interrupted by the model's plan.
        Brain busy = BRAINS.get(bot.getUUID());
        if (busy != null && busy.job != null && busy.jobFromPlayer) return true;

        Task task;
        String ore = null;
        java.util.regex.Matcher om = java.util.regex.Pattern
                .compile("\\b(coal|iron|copper|diamond|gold|redstone|lapis|emerald)").matcher(g);
        if (om.find()) { task = Task.ORE; ore = om.group(1); }
        else if (g.matches(".*\\b(log|logs|wood|tree|trees|lumber|planks)\\b.*")) task = Task.WOOD;
        else if (g.matches(".*\\b(stone|cobble|cobblestone|mine|mining|dig)\\b.*")) task = Task.STONE;
        else if (g.matches(".*\\b(craft|make|build a (pickaxe|sword|table))\\b.*")) {
            MinecraftServer server = bot.level().getServer();
            onServer(server, () -> craftNow(bot), null);
            return true;
        }
        else if (g.matches(".*\\b(explore|wander|scout|look around)\\b.*")) task = Task.EXPLORE;
        else return false;

        // Following / staying / doing what a player asked always wins over the model's own plan.
        Brain b = BRAINS.get(bot.getUUID());
        if (CompanionController.getInstance().getStance(bot.getName().getString()) != BotStance.WANDER) return true;
        if (b != null && b.commanded != null && b.fromPlayer) return true;
        command(bot, task, ore);
        if (b == null) b = BRAINS.get(bot.getUUID());
        if (b != null) b.fromPlayer = false;
        long end = System.currentTimeMillis() + maxMillis;
        try {
            while (b != null && !b.stop && b.commanded == task && System.currentTimeMillis() < end) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (b != null && b.commanded == task) b.commanded = null;
        return true;
    }

    private static boolean runPlanJob(ServerPlayer bot, MiningSkills.Request req, long maxMillis) {
        // Following / staying / doing what a player asked always wins over the model's own plan.
        if (CompanionController.getInstance().getStance(bot.getName().getString()) != BotStance.WANDER) return true;
        Brain b = BRAINS.get(bot.getUUID());
        if (b != null && ((b.commanded != null && b.fromPlayer) || (b.job != null && b.jobFromPlayer))) return true;
        startJob(bot, req.label(), false, req.job());
        b = BRAINS.get(bot.getUUID());
        if (b == null) return true;
        long seq = b.jobSeq;
        long end = System.currentTimeMillis() + Math.max(maxMillis, 5 * 60_000L);
        try {
            while (!b.stop && b.jobSeq == seq && b.job != null && System.currentTimeMillis() < end) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (b.jobSeq == seq && b.job != null) { b.job = null; b.jobSeq++; }
        return true;
    }

    /** "stop": drop the current job and hang around for a bit. */
    public static void stopTask(ServerPlayer bot) {
        Brain b = BRAINS.get(bot.getUUID());
        if (b != null) {
            b.commanded = null;
            b.job = null;
            b.jobSeq++;
            b.restUntil = System.currentTimeMillis() + 3 * 60_000L;
        }
        UUID id = bot.getUUID();
        MinecraftServer server = bot.level().getServer();
        NavigationService.cancel(server, id, "Told to stop");
        BotPathing.cancel(bot);
        MiningTool.cancelFor(server, id, "Told to stop");
    }

    /** Starts a job (replacing whatever the bot was doing). */
    public static void startJob(ServerPlayer bot, String name, boolean fromPlayer, Job job) {
        Brain b = BRAINS.get(bot.getUUID());
        if (b == null) { ensureRunning(bot); b = BRAINS.get(bot.getUUID()); }
        b.commanded = null;
        b.restUntil = 0;
        b.jobSeq++;
        b.jobName = name;
        b.jobFromPlayer = fromPlayer;
        b.job = job;
        MinecraftServer server = bot.level().getServer();
        NavigationService.cancel(server, bot.getUUID(), "New job");
        BotPathing.cancel(bot);
        MiningTool.cancelFor(server, bot.getUUID(), "New job");
        CompanionController companion = CompanionController.getInstance();
        if (companion.getStance(b.name) != BotStance.WANDER) companion.setStanceQuiet(b.name, BotStance.WANDER, null);
    }

    /** Is the job that is currently running still wanted? */
    static boolean jobAlive(Brain b) {
        return !b.stop && b.job != null && b.jobSeq == b.runningSeq;
    }

    /** Keep going? In a job: the job is still wanted. On its own: nobody asked for anything else or said stop. */
    static boolean canContinue(Brain b) {
        if (b.stop) return false;
        if (b.runningSeq != -1) return b.job != null && b.jobSeq == b.runningSeq;
        return b.job == null && System.currentTimeMillis() >= b.restUntil;
    }

    /** Name of the job the bot is doing, or "-" (diagnostics). */
    static String jobName(ServerPlayer bot) {
        Brain b = BRAINS.get(bot.getUUID());
        if (b == null) return "-";
        String n = b.jobName;
        return b.job != null && n != null ? n : (b.commanded != null ? b.commanded.name().toLowerCase(Locale.ROOT) : "-");
    }

    /** Busy with a job right now (the brain thread is running it). */
    public static boolean isWorking(ServerPlayer bot) {
        Brain b = BRAINS.get(bot.getUUID());
        return b != null && b.runningSeq != -1;
    }

    static boolean hasPlayerJob(ServerPlayer bot) {
        Brain b = BRAINS.get(bot.getUUID());
        return b != null && b.job != null && b.jobFromPlayer;
    }

    // ------------------------------------------------------------------------
    // Main loop (own daemon thread; world access goes through the server thread)
    // ------------------------------------------------------------------------

    private static void loop(Brain b) {
        try { sleep(3000); } catch (InterruptedException e) { return; }
        while (!b.stop) {
            try {
                sleep(900 + RNG.nextInt(700));
                MinecraftServer server = AICompanion.serverInstance;
                if (server == null || !server.isRunning()) continue;
                ServerPlayer bot = onServer(server, () -> server.getPlayerList().getPlayer(b.id), null);
                if (bot == null) {
                    if (System.currentTimeMillis() - b.lastSeen > 60_000L) { b.stop = true; BRAINS.remove(b.id); }
                    continue;
                }
                if (!HumanConfig.get().autoPlay && b.job == null && b.commanded == null) continue;
                if (!onServer(server, () -> canAct(bot, b), false)) continue;

                if (b.commanded != null && System.currentTimeMillis() > b.commandUntil) b.commanded = null;
                if (b.commanded == null && b.job == null && System.currentTimeMillis() < b.restUntil) continue;

                // 1. Look after yourself
                if (onServer(server, () -> shouldEat(bot), false) && onServer(server, () -> FoodConsumptionTool.hasSafeFood(bot), false)) {
                    FoodConsumptionTool.consumeBestFood(bot);
                    continue;
                }

                // 1b. Pockets completely full: toss the junk (seeds, flowers, diorite...) like a player would
                if (onServer(server, () -> Storage.usedSlots(bot) >= 36, false)) {
                    onServer(server, () -> Storage.makeRoom(bot, 3), 0);
                }

                // 2. Craft whatever upgrades are possible right now
                String crafted = onServer(server, () -> craftUpgrades(bot), null);
                if (crafted != null) {
                    maybeSay(server, b, crafted, 0.8);
                    continue;
                }

                // 3. A job somebody asked for
                Job job = b.job;
                if (job != null) {
                    long seq = b.jobSeq;
                    b.runningSeq = seq;
                    try {
                        job.run(server, bot, b);
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        LOGGER.warn("[brain] {} job '{}' failed: {}", b.name, b.jobName, e.toString());
                    } finally {
                        b.runningSeq = -1;
                        if (b.jobSeq == seq) { b.job = null; b.restUntil = System.currentTimeMillis() + 20_000L; }
                    }
                    continue;
                }

                // 3a. Life at the base: build one, go home at night / when hurt, take the loot home
                if (b.commanded == null && Home.tick(server, bot, b)) continue;

                // 3b. Pockets filling up: put the loot in the depot (the community chests)
                if (HumanConfig.get().autoStore && System.currentTimeMillis() - b.lastStore > 120_000L
                        && System.currentTimeMillis() >= Storage.backoffUntil
                        && onServer(server, () -> Storage.usedSlots(bot) >= 26 && Storage.storableSlots(bot) >= 6
                                && Storage.canStoreSoon(bot), false)) {
                    b.lastStore = System.currentTimeMillis();
                    Storage.storeAll(server, bot, b, false);
                    continue;
                }

                // 3c. Count what it carries (for the others and for later), and help with what they're gathering
                if (++b.stockTick % 3 == 0) onServer(server, () -> Stock.record(bot), null);
                if (b.commanded == null && Depot.helpTick(server, bot, b)) continue;
                BotTalk.maybeStart(server, bot, b);

                // 4. Stay in the same area as the people you're playing with
                BlockPos regroup = onServer(server, () -> regroupTarget(bot), null);
                if (regroup != null && b.commanded == null) {
                    // deep underground: walking there through rock goes nowhere, dig back up first
                    if (Surface.backUp(server, bot, b, null)) continue;
                    goTo(bot, regroup, 25);
                    continue;
                }

                // 5. Do something
                Task task = b.commanded != null ? b.commanded : chooseTask(server, bot, b);
                switch (task) {
                    case WOOD -> doWood(server, bot, b);
                    case STONE -> doStone(server, bot, b);
                    case ORE -> doOre(server, bot, b);
                    case EXPLORE -> doExplore(server, bot, b);
                    default -> { }
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                LOGGER.debug("[brain] {} step failed: {}", b.name, e.toString());
            }
        }
        LOGGER.info("[brain] stopped for {}", b.name);
    }

    /** Hungry (food 14 or less), or hurt and not full (so health comes back). Server thread. */
    static boolean shouldEat(ServerPlayer bot) {
        int food = bot.getFoodData().getFoodLevel();
        return food <= 14 || (bot.getHealth() < 20f && food < 20);
    }

    /** Free to act on its own? (server thread) */
    private static boolean canAct(ServerPlayer bot, Brain b) {
        if (!bot.isAlive() || bot.hasDisconnected() || bot.isSleeping()) return false;
        if (PvpController.isFighting(bot.getUUID())) return false;
        UUID id = bot.getUUID();
        BotStance stance = CompanionController.getInstance().getStance(b.name);
        if (stance == BotStance.STAY) return false;
        if (stance == BotStance.FOLLOW && b.commanded == null && b.job == null) return false;
        if (NavigationService.isNavigating(id) || BotPathing.isActive(id) || MiningTool.isMining(id)
                || FoodConsumptionTool.isConsumptionInProgress(id)) return false;
        if (AutoFaceEntity.botBusy || AutoFaceEntity.hostileEntityInFront || AutoFaceEntity.isShooting
                || AutoFaceEntity.isActivelyBlocking || AutoFaceEntity.isDefendingFromProjectile) return false;
        AutonomousGoalEngine engine = AutonomousManager.getInstance().getEngine(b.name);
        if (b.commanded == null && b.job == null && engine != null && engine.isExecutingGoal()) return false;
        return true;
    }

    private static Task chooseTask(MinecraftServer server, ServerPlayer bot, Brain b) {
        Inv inv = onServer(server, () -> Inv.of(bot), null);
        if (inv == null) return Task.EXPLORE;
        int pick = inv.pickaxeTier;
        boolean night = onServer(server, () -> (bot.level().getDefaultClockTime() % 24000L) >= 13000L
                && (bot.level().getDefaultClockTime() % 24000L) < 23000L, false);

        if (pick == 0 && inv.logs + inv.planks / 4 < 3) return Task.WOOD;
        // ore lying around only tempts it outside the Overworld: there, ore comes out of the mine
        if (pick > 0 && !onServer(server, () -> Home.overworld(bot.level()), true)) {
            BlockPos ore = onServer(server, () -> findOre(bot, b, null, pick, 10), null);
            if (ore != null) return Task.ORE;
        }
        if (pick == 1 && inv.cobble < 3) return Task.STONE;
        if (inv.logs + inv.planks / 4 < 6 && !night) return Task.WOOD;
        if (pick >= 2 && inv.cobble < 16) return Task.STONE;

        // Take a break now and then, like a person chatting or looking around
        double r = RNG.nextDouble();
        if (r < 0.25) {
            b.restUntil = System.currentTimeMillis() + 10_000L + RNG.nextInt(25_000);
            return Task.AUTO;
        }
        if (r < 0.45 && pick >= 1) return Task.STONE;
        if (r < 0.6 && !night) return Task.WOOD;
        return Task.EXPLORE;
    }

    // ------------------------------------------------------------------------
    // Activities
    // ------------------------------------------------------------------------

    private static void doWood(MinecraftServer server, ServerPlayer bot, Brain b) throws InterruptedException {
        pickUpNearbyItems(server, bot, 8);
        BlockPos log = onServer(server, () -> {
            Protection.Context ctx = Protection.scan(bot, 24);
            return findBlock(bot, b, SurvivalBrain::isLog, 24, 10,
                    p -> !ctx.isProtected(bot.level(), p) && Protection.isNaturalTreeLog(bot.level(), p));
        }, null);
        if (log == null) {
            if (b.commanded == Task.WOOD) maybeSay(server, b, HumanChat.pick("no trees around here, gonna look further", "can't see any trees, one sec"), 0.6);
            doExplore(server, bot, b);
            return;
        }
        maybeSay(server, b, HumanChat.pick("gonna grab some wood", "getting some logs", "ima punch this tree"), 0.25);
        // once there's a pickaxe, an axe is next on a player's list
        String axe = onServer(server, () -> {
            Inv inv = Inv.of(bot);
            return inv.pickaxeTier >= 1 && inv.axeTier == 0 && inv.logs + inv.planks / 4 >= 3 ? craftTool(bot, "axe") : null;
        }, null);
        if (axe != null) maybeSay(server, b, axe, 0.6);
        // like a player: the whole tree, bottom to top, then replant
        int chopped = MiningSkills.fellTree(server, bot, b, log);
        if (chopped == 0) b.blacklist.put(log, System.currentTimeMillis() + 5 * 60_000L);
    }

    /** When it last went down the mine on its own (it doesn't spend all day down there). */
    private static final Map<UUID, Long> LAST_MINE_TRIP = new ConcurrentHashMap<>();

    /** In the Overworld, stone and ore only come out of the mine: a trip down it (now and then, unless told to). */
    private static boolean mineTrip(MinecraftServer server, ServerPlayer bot, Brain b, String what, int count) throws InterruptedException {
        if (!onServer(server, () -> Home.overworld(bot.level()), false)) return false;
        long now = System.currentTimeMillis();
        boolean told = b.commanded != null;
        if (!told && now - LAST_MINE_TRIP.getOrDefault(bot.getUUID(), 0L) < 10 * 60_000L) {
            doExplore(server, bot, b);
            return true;
        }
        LAST_MINE_TRIP.put(bot.getUUID(), now);
        MiningSkills.Target t = MiningSkills.resolve(what);
        if (t == null) return false;
        MineHub.mineFor(server, bot, b, t, count, false);
        Storage.afterMining(server, bot, b);
        return true;
    }

    private static void doStone(MinecraftServer server, ServerPlayer bot, Brain b) throws InterruptedException {
        int pick = onServer(server, () -> Inv.of(bot).pickaxeTier, 0);
        if (pick == 0) { doWood(server, bot, b); return; }
        if (mineTrip(server, bot, b, "stone", 32)) return;
        BlockPos stone = onServer(server, () -> {
            Protection.Context ctx = Protection.scan(bot, 12);
            return findBlock(bot, b, SurvivalBrain::isStone, 12, 6, p -> !ctx.isProtected(bot.level(), p));
        }, null);
        if (stone == null) {
            if (b.commanded == Task.STONE) maybeSay(server, b, HumanChat.pick("no stone nearby, looking around", "need to find a cave or something"), 0.6);
            doExplore(server, bot, b);
            return;
        }
        maybeSay(server, b, HumanChat.pick("gonna mine a bit", "need some cobble", "mining time"), 0.2);
        for (int i = 0; i < 6 && !b.stop; i++) {
            if (!mineAt(server, bot, b, stone)) break;
            // continue on connected stone next to the one just mined
            BlockPos last = stone;
            stone = onServer(server, () -> {
                BlockPos n = neighbourMatching(bot, b, last, SurvivalBrain::isStone);
                return n != null && Protection.nearManMade(bot.level(), n, 3) ? null : n;
            }, null);
            if (stone == null) break;
            BlockPos ore = onServer(server, () -> findOre(bot, b, null, Inv.of(bot).pickaxeTier, 5), null);
            if (ore != null) { mineOre(server, bot, b, ore); break; }
        }
        pickUpNearbyItems(server, bot, 6);
    }

    private static void doOre(MinecraftServer server, ServerPlayer bot, Brain b) throws InterruptedException {
        int pick = onServer(server, () -> Inv.of(bot).pickaxeTier, 0);
        if (pick == 0) { doWood(server, bot, b); return; }
        String wanted = b.wantedOre;
        if (mineTrip(server, bot, b, wanted == null || wanted.equals("ore") ? "ores" : wanted, wanted == null ? 8 : 6)) return;
        BlockPos ore = onServer(server, () -> findOre(bot, b, wanted, pick, 16), null);
        if (ore == null) {
            if (wanted != null && b.commanded == Task.ORE) {
                String need = requiredPickFor(wanted) > pick ? "need a better pickaxe for " + wanted + " tbh" : "can't see any " + wanted + " here, gonna dig around";
                maybeSay(server, b, need, 0.5);
            }
            doStone(server, bot, b);
            return;
        }
        mineOre(server, bot, b, ore);
    }

    private static void mineOre(MinecraftServer server, ServerPlayer bot, Brain b, BlockPos firstOre) throws InterruptedException {
        BlockPos ore = firstOre;
        String name = onServer(server, () -> blockPath(bot.level().getBlockState(firstOre)), "ore");
        String what = name.replace("deepslate_", "").replace("_ore", "");
        if (what.contains("diamond")) maybeSay(server, b, HumanChat.pick("DIAMONDS!!", "yo diamonds!!", "no way, diamonds"), 1.0);
        else if (what.contains("iron")) maybeSay(server, b, HumanChat.pick("ooh iron", "found some iron", "iron!"), 0.6);
        else maybeSay(server, b, HumanChat.pick("ooh " + what, "found some " + what), 0.35);
        mineAt(server, bot, b, ore);
        // ores come in veins
        for (int i = 0; i < 6 && !b.stop; i++) {
            BlockPos last = ore;
            BlockPos next = onServer(server, () -> neighbourMatching(bot, b, last, st -> blockPath(st).equals(name)), null);
            if (next == null) break;
            ore = next;
            if (!mineAt(server, bot, b, ore)) break;
        }
        pickUpNearbyItems(server, bot, 6);
    }

    private static void doExplore(MinecraftServer server, ServerPlayer bot, Brain b) throws InterruptedException {
        BlockPos target = onServer(server, () -> exploreTarget(bot), null);
        if (target == null) return;
        goTo(bot, target, 30);
        pickUpNearbyItems(server, bot, 8);
        // stop and look around a moment, like a person getting their bearings
        sleep(1500 + RNG.nextInt(2500));
    }

    // ------------------------------------------------------------------------
    // Movement and mining helpers
    // ------------------------------------------------------------------------

    /** Walks (jumping, dropping, swimming; no digging or building) to within reach of {@code pos}. */
    static boolean goTo(ServerPlayer bot, BlockPos pos, int timeoutSeconds) throws InterruptedException {
        return goTo(bot, pos, timeoutSeconds, false);
    }

    /**
     * Goes to {@code pos} with the action-based A* pathfinder. {@code dig} also lets it tunnel,
     * dig down, pillar up and bridge (for explicit requests like "come here").
     */
    static boolean goTo(ServerPlayer bot, BlockPos pos, int timeoutSeconds, boolean dig) throws InterruptedException {
        BotPathing.Options o = dig ? BotPathing.Options.full() : BotPathing.Options.walkOnly();
        o.timeoutTicks = timeoutSeconds * 20 + 40;
        BotPathing.Result r = BotPathing.goToBlocking(bot,
                ActionPathfinder.near(pos.getX(), pos.getY(), pos.getZ(), 1.5), o, timeoutSeconds * 1000L + 3000L);
        return r == BotPathing.Result.REACHED;
    }

    /** Walk into reach of {@code pos} and mine it. Unreachable targets are skipped for a while. */
    static boolean mineAt(MinecraftServer server, ServerPlayer bot, Brain b, BlockPos pos) throws InterruptedException {
        double dist = onServer(server, () -> bot.getEyePosition().distanceTo(Vec3.atCenterOf(pos)), 99.0);
        if (dist > 4.3) {
            goTo(bot, pos, 30);
            dist = onServer(server, () -> bot.getEyePosition().distanceTo(Vec3.atCenterOf(pos)), 99.0);
            if (dist > 4.8) {
                b.blacklist.put(pos, System.currentTimeMillis() + 5 * 60_000L);
                return false;
            }
        }
        onServer(server, () -> { LookController.faceBlock(bot, pos); return null; }, null);
        try {
            MiningResult r = MiningTool.mineBlock(bot, pos).get(45, TimeUnit.SECONDS);
            if (r == null || r.status() != MiningResult.Status.SUCCESS) {
                b.blacklist.put(pos, System.currentTimeMillis() + 5 * 60_000L);
                return false;
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            MiningTool.cancelFor(server, bot.getUUID(), "Took too long");
            b.blacklist.put(pos, System.currentTimeMillis() + 5 * 60_000L);
            return false;
        }
        sleep(250);
        // step onto the spot so the drop gets picked up
        double d = onServer(server, () -> bot.position().distanceTo(Vec3.atCenterOf(pos)), 0.0);
        if (d > 2.0) goTo(bot, pos, 8);
        return true;
    }

    static void pickUpNearbyItems(MinecraftServer server, ServerPlayer bot, double radius) throws InterruptedException {
        pickUpNearbyItems(server, bot, radius, false);
    }

    /**
     * Picks up the drops lying around, like a player walking over them. Items right next to
     * the bot get picked up by themselves after their pickup delay, so it waits for those;
     * for the rest it walks onto them. {@code dig}: may dig a block or two to reach a drop
     * that fell into a pocket (the hole an ore left in the wall).
     */
    static void pickUpNearbyItems(MinecraftServer server, ServerPlayer bot, double radius, boolean dig) throws InterruptedException {
        sleep(450); // fresh drops can't be picked up for half a second
        java.util.Set<BlockPos> tried = new java.util.HashSet<>();
        for (int i = 0; i < 10; i++) {
            BlockPos item = onServer(server, () -> {
                List<ItemEntity> items = bot.level().getEntitiesOfClass(ItemEntity.class,
                        bot.getBoundingBox().inflate(radius), e -> true);
                ItemEntity best = null;
                double bd = Double.MAX_VALUE;
                Vec3 me = bot.position();
                for (ItemEntity e : items) {
                    Vec3 p = e.position();
                    double horiz = Math.hypot(p.x - me.x, p.z - me.z);
                    double dy = p.y - me.y;
                    if (horiz < 1.25 && dy > -0.6 && dy < 2.2) continue; // in pickup range already
                    if (tried.contains(BlockPos.containing(p))) continue;
                    double dd = p.distanceTo(me);
                    if (dd < bd) { bd = dd; best = e; }
                }
                return best == null ? null : BlockPos.containing(best.position());
            }, null);
            if (item == null) break;
            tried.add(item);
            BotPathing.Options o = BotPathing.Options.walkOnly();
            o.timeoutTicks = 20 * 10;
            BotPathing.Result r = BotPathing.goToBlocking(bot, ActionPathfinder.near(item.getX(), item.getY(), item.getZ(), 1.1), o, 11_000L);
            if (r != BotPathing.Result.REACHED && dig) {
                BotPathing.Options d = BotPathing.Options.full();
                d.allowPlace = false;
                d.timeoutTicks = 20 * 15;
                BotPathing.goToBlocking(bot, ActionPathfinder.near(item.getX(), item.getY(), item.getZ(), 1.1), d, 16_000L);
            }
            sleep(300);
        }
    }

    // ------------------------------------------------------------------------
    // World scanning (server thread)
    // ------------------------------------------------------------------------

    static String blockPath(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? "" : id.getPath();
    }

    static boolean isLog(BlockState s) {
        String p = blockPath(s);
        return p.endsWith("_log") || p.endsWith("_stem") || p.endsWith("_wood") || p.endsWith("_hyphae");
    }

    static boolean isStone(BlockState s) {
        String p = blockPath(s);
        // natural stone only: cobblestone is almost always somebody's build
        return p.equals("stone") || p.equals("deepslate") || p.equals("andesite") || p.equals("diorite")
                || p.equals("granite") || p.equals("tuff");
    }

    static int requiredPickFor(String orePath) {
        int known = OreBook.tierFor(orePath); // the ore index every companion remembers
        if (known > 0) return known;
        if (orePath.contains("diamond") || orePath.contains("gold") || orePath.contains("redstone") || orePath.contains("emerald")) return 3;
        if (orePath.contains("iron") || orePath.contains("copper") || orePath.contains("lapis")) return 2;
        return 1;
    }

    static boolean exposed(ServerLevel level, BlockPos p) {
        return level.getBlockState(p.above()).isAir() || level.getBlockState(p.below()).isAir()
                || level.getBlockState(p.offset(1, 0, 0)).isAir() || level.getBlockState(p.offset(-1, 0, 0)).isAir()
                || level.getBlockState(p.offset(0, 0, 1)).isAir() || level.getBlockState(p.offset(0, 0, -1)).isAir();
    }

    static boolean blacklisted(Brain b, BlockPos p) {
        Long until = b.blacklist.get(p);
        if (until == null) return false;
        if (until < System.currentTimeMillis()) { b.blacklist.remove(p); return false; }
        return true;
    }

    /** Nearest exposed block matching {@code test}, preferring ones that aren't far above or below. */
    private static BlockPos findBlock(ServerPlayer bot, Brain b, Predicate<BlockState> test, int radius, int vertical) {
        return findBlock(bot, b, test, radius, vertical, p -> true);
    }

    private static BlockPos findBlock(ServerPlayer bot, Brain b, Predicate<BlockState> test, int radius, int vertical,
                                      Predicate<BlockPos> allowed) {
        ServerLevel level = bot.level();
        BlockPos origin = bot.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -vertical; dy <= vertical; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    BlockState s = level.getBlockState(p);
                    if (dx == 0 && dz == 0 && dy < 0) continue; // never dig straight down
                    if (s.isAir() || !test.test(s)) continue;
                    double score = dx * dx + dz * dz + dy * dy * 3.0 + (dy < -1 ? 6 : 0);
                    if (score >= bestScore) continue;
                    if (blacklisted(b, p) || !exposed(level, p) || !allowed.test(p)) continue;
                    best = p;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private static BlockPos findOre(ServerPlayer bot, Brain b, String wanted, int pickTier, int radius) {
        return findBlock(bot, b, s -> {
            String p = blockPath(s);
            if (!p.endsWith("_ore")) return false;
            if (p.contains("nether")) return false;
            if (wanted != null && !wanted.equals("ore") && !p.contains(wanted)) return false;
            return requiredPickFor(p) <= pickTier;
        }, radius, 8, p -> !Protection.nearManMade(bot.level(), p, 2));
    }

    private static BlockPos neighbourMatching(ServerPlayer bot, Brain b, BlockPos from, Predicate<BlockState> test) {
        ServerLevel level = bot.level();
        BlockPos best = null;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            BlockPos p = from.offset(dx, dy, dz);
            if (p.equals(bot.blockPosition().below())) continue; // don't dig out the floor you're standing on
            if (blacklisted(b, p)) continue;
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && test.test(s) && exposed(level, p)) {
                if (best == null || p.getY() > best.getY()) best = p; // prefer staying level / going up
            }
        }
        return best;
    }

    /** If the bot has drifted far from the nearest human, a spot near them to walk back to. */
    private static BlockPos regroupTarget(ServerPlayer bot) {
        if (Home.center(bot) != null) return null; // it has a base: that's where it stays around (Home.tick)
        ServerPlayer nearest = nearestHuman(bot);
        if (nearest == null) return null;
        double d = Math.sqrt(nearest.distanceToSqr(bot));
        if (d < 48) return null;
        Vec3 dir = nearest.position().subtract(bot.position()).normalize();
        Vec3 goal = bot.position().add(dir.scale(Math.min(40, d - 12)));
        return BlockPos.containing(goal);
    }

    private static BlockPos exploreTarget(ServerPlayer bot) {
        Vec3 center = bot.position();
        ServerPlayer nearest = nearestHuman(bot);
        Vec3 home = Home.center(bot);
        if (home != null) {
            if (home.subtract(center).lengthSqr() > 40 * 40) center = home; // gather around the base
        } else if (nearest != null && nearest.distanceToSqr(bot) > 24 * 24) center = nearest.position();
        double angle = RNG.nextDouble() * Math.PI * 2;
        double dist = 10 + RNG.nextDouble() * 14;
        return BlockPos.containing(center.x + Math.cos(angle) * dist, bot.getY(), center.z + Math.sin(angle) * dist);
    }

    static ServerPlayer nearestHuman(ServerPlayer bot) {
        ServerPlayer best = null;
        double bd = Double.MAX_VALUE;
        for (ServerPlayer p : bot.level().getServer().getPlayerList().getPlayers()) {
            if (HumanBehavior.isBot(p) || p.level() != bot.level() || p.isSpectator()) continue;
            double d = p.distanceToSqr(bot);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    // ------------------------------------------------------------------------
    // Inventory & crafting (server thread)
    // ------------------------------------------------------------------------

    static String itemPath(ItemStack s) {
        if (s == null || s.isEmpty()) return "";
        Identifier id = BuiltInRegistries.ITEM.getKey(s.getItem());
        return id == null ? "" : id.getPath();
    }

    static Item item(String path) {
        return (Item) BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("minecraft", path));
    }

    static boolean isLogItem(String p) {
        return p.endsWith("_log") || p.endsWith("_stem") || p.endsWith("_wood") || p.endsWith("_hyphae");
    }

    static boolean isCobbleItem(String p) {
        return p.equals("cobblestone") || p.equals("cobbled_deepslate") || p.equals("blackstone");
    }

    /** Material tier of any tool: wood/gold 1, stone/copper 2, iron 3, diamond 4, netherite 5. */
    public static int toolTier(String p) {
        if (p.startsWith("wooden_") || p.startsWith("golden_")) return 1;
        if (p.startsWith("stone_") || p.startsWith("copper_")) return 2;
        if (p.startsWith("iron_")) return 3;
        if (p.startsWith("diamond_")) return 4;
        if (p.startsWith("netherite_")) return 5;
        return 0;
    }

    static int pickTier(String p) {
        return switch (p) {
            case "wooden_pickaxe", "golden_pickaxe" -> 1;
            case "stone_pickaxe", "copper_pickaxe" -> 2;
            case "iron_pickaxe" -> 3;
            case "diamond_pickaxe" -> 4;
            case "netherite_pickaxe" -> 5;
            default -> 0;
        };
    }

    /** Snapshot of the counts the brain cares about. */
    record Inv(int logs, int planks, int sticks, int cobble, int coal, int torches, boolean table,
               int pickaxeTier, boolean sword, int axeTier, int shovelTier) {
        static Inv of(ServerPlayer bot) {
            int logs = 0, planks = 0, sticks = 0, cobble = 0, coal = 0, torches = 0, pick = 0, axe = 0, shovel = 0;
            boolean table = false, sword = false;
            Inventory inv = bot.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty()) continue;
                String p = itemPath(s);
                int c = s.getCount();
                if (isLogItem(p)) logs += c;
                else if (p.endsWith("_planks")) planks += c;
                else if (p.equals("stick")) sticks += c;
                else if (isCobbleItem(p)) cobble += c;
                else if (p.equals("coal") || p.equals("charcoal")) coal += c;
                else if (p.equals("torch")) torches += c;
                else if (p.equals("crafting_table")) table = true;
                else if (p.endsWith("_sword")) sword = true;
                pick = Math.max(pick, pickTier(p));
                if (p.endsWith("_axe")) axe = Math.max(axe, toolTier(p));
                if (p.endsWith("_shovel")) shovel = Math.max(shovel, toolTier(p));
            }
            return new Inv(logs, planks, sticks, cobble, coal, torches, table, pick, sword, axe, shovel);
        }
    }

    /** Removes {@code count} items matching {@code test}; returns the path of the first one taken. */
    static String take(ServerPlayer bot, Predicate<String> test, int count) {
        Inventory inv = bot.getInventory();
        String first = null;
        for (int i = 0; i < inv.getContainerSize() && count > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            String p = itemPath(s);
            if (!test.test(p)) continue;
            if (first == null) first = p;
            int n = Math.min(count, s.getCount());
            s.shrink(n);
            count -= n;
        }
        inv.setChanged();
        return first;
    }

    static void give(ServerPlayer bot, String path, int count) {
        Item it = item(path);
        if (it == null || "air".equals(BuiltInRegistries.ITEM.getKey(it).getPath())) return;
        ItemStack stack = new ItemStack(it, count);
        if (!bot.getInventory().add(stack) && !stack.isEmpty()) {
            // inventory full: drop it at our feet like a player would
            ItemEntity drop = new ItemEntity(bot.level(), bot.getX(), bot.getY() + 0.5, bot.getZ(), stack);
            bot.level().addFreshEntity(drop);
        }
    }

    /**
     * Crafts the next useful thing if the materials are there. Returns a casual line
     * describing it (for chat), or null if nothing was crafted.
     */
    /** Items a job is collecting for someone: own crafting must not use them up. */
    static final Map<UUID, Predicate<String>> KEEP = new ConcurrentHashMap<>();

    static void keep(ServerPlayer bot, Predicate<String> items) {
        if (items == null) KEEP.remove(bot.getUUID());
        else KEEP.put(bot.getUUID(), items);
    }

    static String craftUpgrades(ServerPlayer bot) {
        Predicate<String> keep = KEEP.getOrDefault(bot.getUUID(), p -> false);
        Inv inv = Inv.of(bot);
        // wood is for someone - but never at the price of having no pickaxe at all
        if (inv.pickaxeTier > 0 && (keep.test("oak_log") || keep.test("oak_planks") || keep.test("stick"))) return null;
        boolean wantsTools = inv.pickaxeTier < 2 || !inv.sword
                || (inv.pickaxeTier >= 2 && (inv.axeTier < 2 || inv.shovelTier < 2));

        // logs -> planks (keep a small stock)
        if (inv.planks < 8 && inv.logs > 0 && (wantsTools || inv.planks < 4)) {
            String log = take(bot, SurvivalBrain::isLogItem, 1);
            String planks = log.replace("stripped_", "").replaceAll("_(log|wood|stem|hyphae)$", "_planks");
            if ("air".equals(BuiltInRegistries.ITEM.getKey(item(planks)).getPath())) planks = "oak_planks";
            give(bot, planks, 4);
            Motions.swingArm(bot);
            return null; // too small to talk about
        }
        if (inv.sticks < 4 && inv.planks >= 2 && wantsTools) {
            take(bot, p -> p.endsWith("_planks"), 2);
            give(bot, "stick", 4);
            return null;
        }
        if (!inv.table && inv.planks >= 4 && wantsTools) {
            take(bot, p -> p.endsWith("_planks"), 4);
            give(bot, "crafting_table", 1);
            Motions.swingArm(bot);
            return HumanChat.pick("made a crafting table", "ok crafting table done");
        }
        if (!inv.table) return null;
        if (inv.pickaxeTier == 0 && inv.planks >= 3 && inv.sticks >= 2) {
            take(bot, p -> p.endsWith("_planks"), 3);
            take(bot, "stick"::equals, 2);
            give(bot, "wooden_pickaxe", 1);
            Motions.swingArm(bot);
            return HumanChat.pick("got a wooden pick", "made a wooden pickaxe, time to mine");
        }
        boolean keepCobble = keep.test("cobblestone") || keep.test("cobbled_deepslate");
        if (inv.pickaxeTier < 2 && inv.cobble >= 3 && inv.sticks >= 2 && !keepCobble) {
            take(bot, SurvivalBrain::isCobbleItem, 3);
            take(bot, "stick"::equals, 2);
            give(bot, "stone_pickaxe", 1);
            Motions.swingArm(bot);
            return HumanChat.pick("stone pick, nice", "upgraded to a stone pickaxe");
        }
        if (!inv.sword && inv.cobble >= 2 && inv.sticks >= 1 && inv.pickaxeTier >= 2 && !keepCobble) {
            take(bot, SurvivalBrain::isCobbleItem, 2);
            take(bot, "stick"::equals, 1);
            give(bot, "stone_sword", 1);
            Motions.swingArm(bot);
            return HumanChat.pick("made a sword too", "got a stone sword now");
        }
        if (inv.pickaxeTier >= 2 && inv.axeTier < 2 && inv.cobble >= 3 && inv.sticks >= 2 && !keepCobble) {
            take(bot, SurvivalBrain::isCobbleItem, 3);
            take(bot, "stick"::equals, 2);
            give(bot, "stone_axe", 1);
            Motions.swingArm(bot);
            return HumanChat.pick("made a stone axe", "stone axe done, trees go faster now");
        }
        if (inv.pickaxeTier >= 2 && inv.shovelTier < 2 && inv.cobble >= 1 && inv.sticks >= 2 && !keepCobble) {
            take(bot, SurvivalBrain::isCobbleItem, 1);
            take(bot, "stick"::equals, 2);
            give(bot, "stone_shovel", 1);
            return null;
        }
        if (inv.coal >= 1 && inv.sticks >= 1 && inv.torches < 16 && !keep.test("coal")) {
            take(bot, p -> p.equals("coal") || p.equals("charcoal"), 1);
            take(bot, "stick"::equals, 1);
            give(bot, "torch", 4);
            return null;
        }
        return null;
    }

    /**
     * Crafts a tool of {@code kind} ("axe", "shovel", "pickaxe") if the bot has none that is
     * at least as good as what it can make: stone if there's cobble, otherwise wood. Turns
     * logs into planks, sticks and a crafting table first when needed. Returns a chat line
     * or null. Server thread.
     */
    static String craftTool(ServerPlayer bot, String kind) {
        int head = kind.equals("shovel") ? 1 : 3;
        for (int guard = 0; guard < 8; guard++) {
            Inv inv = Inv.of(bot);
            int have = switch (kind) {
                case "axe" -> inv.axeTier;
                case "shovel" -> inv.shovelTier;
                default -> inv.pickaxeTier;
            };
            boolean stone = inv.cobble >= head;
            int canMake = stone ? 2 : 1;
            if (have >= canMake) return null;
            // materials: planks for sticks/table/wooden head, then sticks, then a table
            int planksNeeded = (inv.sticks >= 2 ? 0 : 2) + (inv.table ? 0 : 4) + (stone ? 0 : head);
            if (inv.planks < planksNeeded) {
                if (inv.logs == 0) return null;
                String log = take(bot, SurvivalBrain::isLogItem, 1);
                String planks = log.replace("stripped_", "").replaceAll("_(log|wood|stem|hyphae)$", "_planks");
                if ("air".equals(BuiltInRegistries.ITEM.getKey(item(planks)).getPath())) planks = "oak_planks";
                give(bot, planks, 4);
                continue;
            }
            if (inv.sticks < 2) {
                take(bot, p -> p.endsWith("_planks"), 2);
                give(bot, "stick", 4);
                continue;
            }
            if (!inv.table) {
                take(bot, p -> p.endsWith("_planks"), 4);
                give(bot, "crafting_table", 1);
                continue;
            }
            if (stone) take(bot, SurvivalBrain::isCobbleItem, head);
            else take(bot, p -> p.endsWith("_planks"), head);
            take(bot, "stick"::equals, 2);
            String made = (stone ? "stone_" : "wooden_") + kind;
            give(bot, made, 1);
            Motions.swingArm(bot);
            return HumanChat.pick("made a " + made.replace('_', ' '), "quick " + made.replace('_', ' ') + ", one sec");
        }
        return null;
    }

    /** "what do you have?" */
    public static String inventorySummary(ServerPlayer bot) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Inventory inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            counts.merge(s.getHoverName().getString().toLowerCase(Locale.ROOT), s.getCount(), Integer::sum);
        }
        if (counts.isEmpty()) return HumanChat.pick("nothing lol, my inventory's empty", "literally nothing yet");
        List<Map.Entry<String, Integer>> e = new ArrayList<>(counts.entrySet());
        e.sort((a, c) -> c.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder("i've got ");
        for (int i = 0; i < Math.min(7, e.size()); i++) {
            if (i > 0) sb.append(i == Math.min(7, e.size()) - 1 ? " and " : ", ");
            sb.append(e.get(i).getValue()).append(' ').append(e.get(i).getKey());
        }
        if (e.size() > 7) sb.append(" and some other stuff");
        return sb.toString();
    }

    /**
     * "give me cobblestone": turns to the player and throws them the matching stack(s).
     * Returns a chat reply.
     */
    public static String giveTo(ServerPlayer bot, ServerPlayer player, String query) {
        String raw = query == null ? "" : query.toLowerCase(Locale.ROOT)
                .replaceAll("\\bcobble ?stone\\b", "cobblestone")
                .replaceAll("\\b(that |which )?(you|u) (have|got|own|carry|carrying|are carrying)\\b", " ")
                .replaceAll("\\b(in|from) (your|ur) (inventory|inv|pockets?)\\b", " ");
        int count = 0;
        java.util.regex.Matcher cm = java.util.regex.Pattern.compile("\\b(\\d+|a stack of|a stack|one|two|three|four|five|a few)\\b").matcher(raw);
        if (cm.find()) {
            String w = cm.group(1);
            count = w.matches("\\d+") ? Integer.parseInt(w) : switch (w) {
                case "one" -> 1; case "two" -> 2; case "three", "a few" -> 3; case "four" -> 4; case "five" -> 5; default -> 64; };
            raw = raw.replace(w, " ");
        }
        String q = raw.replaceAll("\\b(some|the|a|an|your|all|of|me|pls|please|those|that|it|them|can|you|could|would)\\b", " ")
                .replaceAll("[^a-z0-9 _]", " ").trim().replaceAll("\\s+", " ");
        boolean everything = query != null && query.toLowerCase(Locale.ROOT).matches(".*\\b(everything|all your stuff|all of it|all)\\b.*") && q.isEmpty();
        if (q.endsWith("s") && q.length() > 3) q = q.substring(0, q.length() - 1); // logs -> log
        if (q.isEmpty() && !everything) return "give you what?";
        final String name = q;
        // "iron" means the metal (ingots if there are any, else raw), not iron tools or armour
        boolean wantsGear = name.matches(".*\\b(pick|pickaxe|sword|axe|shovel|hoe|helmet|chestplate|leggings|boots|armor|armour|tool|tools)\\b.*");
        if (!everything && name.matches("iron|gold|copper")) {
            String ingot = name + "_ingot";
            boolean hasIngot = false;
            for (int i = 0; i < Math.min(36, bot.getInventory().getContainerSize()); i++) {
                if (itemPath(bot.getInventory().getItem(i)).equals(ingot)) { hasIngot = true; break; }
            }
            q = hasIngot ? name + " ingot" : "raw " + name;
        }
        final String name2 = q;
        Predicate<String> match0 = everything
                ? p -> pickTier(p) == 0 && !p.endsWith("_sword") && !p.endsWith("_axe") && !p.endsWith("_shovel")
                : p -> p.replace('_', ' ').contains(name2) || (name2.equals("wood") && isLogItem(p))
                        || (name2.equals("plank") && p.endsWith("_planks")) || (name2.equals("cobble") && isCobbleItem(p));
        Predicate<String> match = everything || wantsGear ? match0 : p -> match0.test(p) && !isGear(p);
        // what do we actually have? (by id, or by the name shown in the inventory)
        Inventory inv = bot.getInventory();
        int have = 0;
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            String hover = s.getHoverName().getString().toLowerCase(Locale.ROOT);
            if (match.test(itemPath(s)) || (!everything && !wantsGear && !isGear(itemPath(s)) && hover.contains(name2))) have += s.getCount();
        }
        if (have == 0) return HumanChat.pick("i don't have any " + q, "don't have " + q + " sorry");
        Predicate<String> byPathOrName = everything ? match : p -> {
            if (match.test(p)) return true;
            if (!wantsGear && isGear(p)) return false;
            Item it = item(p);
            return it != null && new ItemStack(it, 1).getHoverName().getString().toLowerCase(Locale.ROOT).contains(name2);
        };
        final int want = count;
        double d = Math.sqrt(bot.distanceToSqr(player));
        if (d <= 5.0) {
            int given = Gathering.handOver(bot, player, byPathOrName, want);
            return given > 0 ? HumanChat.pick("here", "catch", "there you go") + (RNG.nextBoolean() ? ", " + given + " " + q : "")
                    : "can't reach you";
        }
        UUID to = player.getUUID();
        String label = q.isEmpty() ? "stuff" : q;
        startJob(bot, "give " + label, true, (server, b2, br) -> Gathering.deliver(server, b2, br, to, byPathOrName, label, want));
        return HumanChat.pick("sure, coming over", "ok, bringing it", "omw with the " + label);
    }

    static boolean isGear(String p) {
        return p.matches(".*_(pickaxe|sword|axe|shovel|hoe|helmet|chestplate|leggings|boots)$");
    }

    /** Crafts as far as possible right now (chat "craft a pickaxe"). */
    public static String craftNow(ServerPlayer bot) {
        String said = null;
        for (int i = 0; i < 12; i++) {
            String line = craftUpgrades(bot);
            if (line != null) said = line;
        }
        if (said != null) return said;
        Inv inv = Inv.of(bot);
        if (inv.logs == 0 && inv.planks < 3) return "need wood first";
        if (inv.pickaxeTier == 1 && inv.cobble < 3) return "need a bit of cobble for a stone pick";
        return "nothing new i can craft rn";
    }

    // ------------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------------

    private static final Map<String, Long> LAST_SAID = new ConcurrentHashMap<>();

    static void maybeSay(MinecraftServer server, Brain b, String line, double chance) {
        if (line == null || RNG.nextDouble() > chance) return;
        long now = System.currentTimeMillis();
        Long last = LAST_SAID.get(b.name);
        if (last != null && now - last < 45_000L && chance < 1.0) return;
        LAST_SAID.put(b.name, now);
        HumanChat.say(server, b.name, line);
    }

    /** Waits (up to a minute) while the bot fights off a mob or runs from one. True if it had to wait. */
    static boolean waitWhileFighting(ServerPlayer bot) throws InterruptedException {
        boolean waited = false;
        for (int i = 0; i < 120 && PvpController.isBusy(bot.getUUID()); i++) {
            waited = true;
            Thread.sleep(500);
        }
        if (waited) Thread.sleep(300);
        return waited;
    }

    static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    /** Runs {@code call} on the server thread and waits (max 2 s) for the result. */
    static <T> T onServer(MinecraftServer server, Supplier<T> call, T fallback) {
        if (server.isSameThread()) {
            try { return call.get(); } catch (Exception e) { return fallback; }
        }
        CompletableFuture<T> f = new CompletableFuture<>();
        server.execute(() -> {
            try { f.complete(call.get()); } catch (Throwable t) { f.complete(fallback); }
        });
        try {
            T v = f.get(2, TimeUnit.SECONDS);
            return v == null ? fallback : v;
        } catch (Exception e) {
            return fallback;
        }
    }
}
