package io.github.yudiiee.aicompanion.GameAI.human;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.helpers.EntityPlayerActionPack.Action;
import carpet.helpers.EntityPlayerActionPack.ActionType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import io.github.yudiiee.aicompanion.GameAI.human.ActionPathfinder.Goal;
import io.github.yudiiee.aicompanion.GameAI.human.ActionPathfinder.Kind;
import io.github.yudiiee.aicompanion.GameAI.human.ActionPathfinder.Move;
import io.github.yudiiee.aicompanion.PathFinding.NavigationService;
import io.github.yudiiee.aicompanion.PlayerUtils.MiningResult;
import io.github.yudiiee.aicompanion.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Moves a bot along paths from {@link ActionPathfinder}, one movement primitive at a
 * time, the way a player would: it presses the movement keys (Carpet action pack),
 * jumps, digs the blocks in the way with the right tool, places blocks to pillar up or
 * bridge over gaps, and swims. It re-plans when the world changes or a move fails, and
 * searches in segments when the goal is far away.
 *
 * <p>Everything here runs on the server thread, once per tick.
 */
public final class BotPathing {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-pathing");

    public enum Result { REACHED, NO_PATH, STUCK, CANCELLED, TIMEOUT }

    public static final class Options {
        public boolean allowBreak = true;
        public boolean allowPlace = true;
        public boolean sprint = true;
        public int maxReplans = 15;
        public int timeoutTicks = 20 * 180;
        /** Used by the fight controller itself; other trips pause while the bot is fighting. */
        public boolean fight = false;

        public static Options walkOnly() {
            Options o = new Options();
            o.allowBreak = false;
            o.allowPlace = false;
            return o;
        }

        public static Options full() { return new Options(); }
    }

    private enum Phase { SEARCH, BREAK, PLACE, MOVE }

    private static final class Task {
        final UUID botId;
        final Goal goal;
        final Options opts;
        final CompletableFuture<Result> future = new CompletableFuture<>();
        final Set<Long> avoid = new HashSet<>();
        ActionPathfinder.Search search;
        List<Move> path;
        int index;
        int replans;
        long started;
        Phase phase = Phase.SEARCH;
        long phaseStart;
        CompletableFuture<MiningResult> mining;
        int breakAttempts;
        int placeTries;
        boolean jumped;
        long jumpTick;
        Vec3 lastPos;
        long lastMoveTick;
        double bestH = Double.MAX_VALUE;
        int noProgress;
        BlockPos openedDoor;
        boolean paused;
        boolean surfacing;

        Task(UUID botId, Goal goal, Options opts) { this.botId = botId; this.goal = goal; this.opts = opts; }
    }

    private static final Map<UUID, Task> TASKS = new ConcurrentHashMap<>();
    /** A job's trip put aside while the bot chases a mob; it carries on when the fight trip ends. */
    private static final Map<UUID, Task> SUSPENDED = new ConcurrentHashMap<>();
    private static long tick;

    private BotPathing() {}

    // ------------------------------------------------------------------------
    // API (any thread)
    // ------------------------------------------------------------------------

    public static boolean isActive(UUID botId) {
        return botId != null && TASKS.containsKey(botId);
    }

    /** Starts walking towards the goal; completes with the result. */
    public static CompletableFuture<Result> goTo(ServerPlayer bot, Goal goal, Options opts) {
        Task t = new Task(bot.getUUID(), goal, opts == null ? Options.full() : opts);
        MinecraftServer server = bot.level().getServer();
        Runnable start = () -> {
            Task old = TASKS.put(t.botId, t);
            if (old != null && old != t) {
                if (t.opts.fight && !old.opts.fight) {
                    Task prev = SUSPENDED.put(t.botId, old); // resume it after the fight
                    if (prev != null && prev != old) prev.future.complete(Result.CANCELLED);
                } else {
                    finish(server, old, Result.CANCELLED);
                }
            }
            NavigationService.cancel(server, t.botId, "Pathing");
            t.started = tick;
            startSearch(bot, t);
        };
        if (server.isSameThread()) start.run();
        else server.execute(start);
        return t.future;
    }

    /** Walks there and waits (brain / job threads only). */
    public static Result goToBlocking(ServerPlayer bot, Goal goal, Options opts, long timeoutMillis) throws InterruptedException {
        CompletableFuture<Result> f = goTo(bot, goal, opts);
        try {
            return f.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancel(bot);
            return Result.TIMEOUT;
        } catch (InterruptedException e) {
            cancel(bot);
            throw e;
        } catch (Exception e) {
            cancel(bot);
            return Result.STUCK;
        }
    }

    public static void cancel(ServerPlayer bot) {
        if (bot == null) return;
        MinecraftServer server = bot.level().getServer();
        UUID id = bot.getUUID();
        Runnable r = () -> {
            Task s = SUSPENDED.remove(id);
            if (s != null) s.future.complete(Result.CANCELLED);
            Task t = TASKS.get(id);
            if (t != null) finish(server, t, Result.CANCELLED);
        };
        if (server.isSameThread()) r.run();
        else server.execute(r);
    }

    /** Ends the fight controller's own chase, if that's what it's doing (the job's trip carries on). */
    public static void cancelFightTrip(ServerPlayer bot) {
        if (bot == null) return;
        MinecraftServer server = bot.level().getServer();
        UUID id = bot.getUUID();
        Runnable r = () -> {
            Task t = TASKS.get(id);
            if (t != null && t.opts.fight) finish(server, t, Result.CANCELLED);
        };
        if (server.isSameThread()) r.run();
        else server.execute(r);
    }

    // ------------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        tick++;
        if (TASKS.isEmpty()) return;
        for (Task t : TASKS.values()) {
            try {
                tickTask(server, t);
            } catch (Throwable e) {
                LOGGER.warn("[pathing] step failed: {}", e.toString());
                finish(server, t, Result.STUCK);
            }
        }
    }

    private static void finish(MinecraftServer server, Task t, Result r) {
        boolean removed = TASKS.remove(t.botId, t);
        ServerPlayer bot = server.getPlayerList().getPlayer(t.botId);
        if (bot != null) releaseKeys(bot);
        if (t.mining != null && !t.mining.isDone()) MiningTool.cancelFor(server, t.botId, "Path finished");
        t.future.complete(r);
        if (removed && t.opts.fight) {
            // the chase is over: pick the job's trip back up (it re-plans from where the bot is now)
            Task s = SUSPENDED.remove(t.botId);
            if (s != null && !s.future.isDone()) {
                s.paused = true;
                if (TASKS.putIfAbsent(s.botId, s) != null) s.future.complete(Result.CANCELLED);
            }
        }
        LOGGER.debug("[pathing] {} -> {}", t.goal, r);
    }

    private static void releaseKeys(ServerPlayer bot) {
        if (bot instanceof ServerPlayerInterface spi) {
            EntityPlayerActionPack ap = spi.getActionPack();
            ap.setForward(0f);
            ap.setStrafing(0f);
            ap.setSprinting(false);
            ap.setSneaking(false);
        }
    }

    static BlockPos feet(ServerPlayer bot) {
        Vec3 p = bot.position();
        return BlockPos.containing(p.x, p.y + 0.5, p.z);
    }

    private static void startSearch(ServerPlayer bot, Task t) {
        LevelPathWorld world = new LevelPathWorld(bot);
        ActionPathfinder.Options o = new ActionPathfinder.Options();
        o.allowBreak = t.opts.allowBreak;
        o.allowPlace = t.opts.allowPlace;
        o.throwaway = t.opts.allowPlace ? LevelPathWorld.countThrowaway(bot) : 0;
        o.avoid = t.avoid;
        BlockPos f = feet(bot);
        t.search = new ActionPathfinder.Search(world, f.getX(), f.getY(), f.getZ(), t.goal, o);
        t.phase = Phase.SEARCH;
        t.path = null;
        t.index = 0;
    }

    private static void replan(ServerPlayer bot, Task t, String why) {
        releaseKeys(bot);
        if (++t.replans > t.opts.maxReplans) {
            LOGGER.debug("[pathing] giving up ({}): {}", why, t.goal);
            finish(bot.level().getServer(), t, Result.STUCK);
            return;
        }
        LOGGER.debug("[pathing] re-planning: {}", why);
        startSearch(bot, t);
    }

    private static void tickTask(MinecraftServer server, Task t) {
        ServerPlayer bot = server.getPlayerList().getPlayer(t.botId);
        if (bot == null || !bot.isAlive() || bot.hasDisconnected()) { finish(server, t, Result.CANCELLED); return; }
        if (tick - t.started > t.opts.timeoutTicks) { finish(server, t, Result.TIMEOUT); return; }
        if (!(bot instanceof ServerPlayerInterface spi)) { finish(server, t, Result.STUCK); return; }
        EntityPlayerActionPack ap = spi.getActionPack();

        // running out of air: stop whatever the path says and swim straight up (players do this
        // without thinking; the bot used to follow the path to the bottom of a lake and drown)
        if (bot.isUnderWater() && bot.getAirSupply() < 180) {
            ap.setForward(0f);
            ap.setStrafing(0f);
            ap.setSprinting(false);
            ap.look(bot.getYRot(), -60f);
            ap.start(ActionType.JUMP, Action.once());
            t.surfacing = true;
            t.started++;
            return;
        }
        if (t.surfacing && !bot.isUnderWater()) {
            t.surfacing = false;
            replan(bot, t, "came up for air");
            return;
        }

        // attacked: the fight has the controls; carry on afterwards
        if (!t.opts.fight && PvpController.isBusy(t.botId)) {
            if (!t.paused) { releaseKeys(bot); t.paused = true; }
            t.started++;
            return;
        }
        if (t.paused) {
            t.paused = false;
            if (t.phase != Phase.SEARCH) replan(bot, t, "after a fight");
            return;
        }

        if (t.phase == Phase.SEARCH) {
            ActionPathfinder.Status st = t.search.step(6_000_000L);
            if (st == ActionPathfinder.Status.RUNNING) return;
            List<Move> p = t.search.path();
            t.search = null;
            BlockPos f = feet(bot);
            if (p.isEmpty()) {
                finish(server, t, t.goal.reached(f.getX(), f.getY(), f.getZ()) ? Result.REACHED : Result.NO_PATH);
                return;
            }
            // partial paths must get us closer, or we're going round in circles
            if (st == ActionPathfinder.Status.PARTIAL) {
                Move last = p.get(p.size() - 1);
                double h = t.goal.heuristic(last.dx, last.dy, last.dz);
                if (h >= t.bestH - 1) {
                    if (++t.noProgress >= 3) { finish(server, t, Result.NO_PATH); return; }
                } else {
                    t.noProgress = 0;
                }
                t.bestH = Math.min(t.bestH, h);
            }
            t.path = p;
            t.index = 0;
            beginMove(t);
            return;
        }

        if (t.index >= t.path.size()) {
            BlockPos f = feet(bot);
            if (t.goal.reached(f.getX(), f.getY(), f.getZ())) { finish(server, t, Result.REACHED); return; }
            replan(bot, t, "end of segment");
            return;
        }

        Move m = t.path.get(t.index);
        BlockPos f = feet(bot);
        double off = Math.min(dist(bot, m.sx, m.sy, m.sz), dist(bot, m.dx, m.dy, m.dz));
        double allowed = 2.2 + Math.max(0, m.sy - m.dy);
        if (off > allowed && t.phase != Phase.BREAK) {
            replan(bot, t, "knocked off the path");
            return;
        }

        switch (t.phase) {
            case BREAK -> doBreak(server, bot, ap, t, m);
            case PLACE -> doPlace(bot, ap, t, m);
            case MOVE -> doMove(bot, ap, t, m, f);
            default -> { }
        }
    }

    private static double dist(ServerPlayer bot, int x, int y, int z) {
        Vec3 p = bot.position();
        double dx = p.x - (x + 0.5), dy = p.y - y, dz = p.z - (z + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void beginMove(Task t) {
        Move m = t.path.get(t.index);
        t.phase = m.breaks.length > 0 ? Phase.BREAK : (m.place != ActionPathfinder.NONE ? Phase.PLACE : Phase.MOVE);
        t.phaseStart = tick;
        t.breakAttempts = 0;
        t.placeTries = 0;
        t.jumped = false;
        t.lastMoveTick = tick;
    }

    private static void nextMove(ServerPlayer bot, Task t) {
        t.index++;
        if (t.index < t.path.size()) beginMove(t);
    }

    // ---------------- breaking ----------------

    private static boolean clearNow(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.isAir() || s.getCollisionShape(level, p).isEmpty();
    }

    private static boolean fluidNext(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) {
            FluidState fs = level.getFluidState(p.relative(d));
            if (fs != null && (fs.is(FluidTags.LAVA) || fs.is(FluidTags.WATER))) return true;
        }
        return false;
    }

    private static void doBreak(MinecraftServer server, ServerPlayer bot, EntityPlayerActionPack ap, Task t, Move m) {
        ap.setForward(0f);
        ap.setSprinting(false);
        if (t.mining != null) {
            if (!t.mining.isDone()) return;
            MiningResult r = t.mining.getNow(null);
            t.mining = null;
            if (r == null || !r.succeeded()) {
                if (++t.breakAttempts > 4) {
                    if (r != null) t.avoid.add(ActionPathfinder.pack(r.target().getX(), r.target().getY(), r.target().getZ()));
                    replan(bot, t, "couldn't dig " + (r == null ? "?" : r.status()));
                }
                return;
            }
        }
        ServerLevel level = bot.level();
        for (long k : m.breaks) {
            BlockPos p = new BlockPos(ActionPathfinder.unpackX(k), ActionPathfinder.unpackY(k), ActionPathfinder.unpackZ(k));
            if (clearNow(level, p)) continue;
            if (fluidNext(level, p) || level.getFluidState(p).is(FluidTags.LAVA)) {
                t.avoid.add(k);
                replan(bot, t, "water/lava next to " + p.getX() + "," + p.getY() + "," + p.getZ());
                return;
            }
            if (++t.breakAttempts > 16) { // endless gravel?
                t.avoid.add(k);
                replan(bot, t, "block keeps coming back");
                return;
            }
            t.mining = MiningTool.mineBlock(bot, p);
            return;
        }
        t.phase = m.place != ActionPathfinder.NONE ? Phase.PLACE : Phase.MOVE;
        t.phaseStart = tick;
    }

    // ---------------- placing ----------------

    private static boolean place(ServerPlayer bot, BlockPos support, Direction face, BlockPos into) {
        if (!LevelPathWorld.holdThrowaway(bot)) return false;
        Vec3 hitAt = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, hitAt);
        BlockHitResult hit = new BlockHitResult(hitAt, face, support, false);
        try {
            bot.gameMode.useItemOn(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            Motions.swingArm(bot);
        } catch (Throwable e) {
            return false;
        }
        return !clearNow(bot.level(), into);
    }

    private static void doPlace(ServerPlayer bot, EntityPlayerActionPack ap, Task t, Move m) {
        int px = ActionPathfinder.unpackX(m.place), py = ActionPathfinder.unpackY(m.place), pz = ActionPathfinder.unpackZ(m.place);
        BlockPos into = new BlockPos(px, py, pz);
        ServerLevel level = bot.level();
        if (!clearNow(level, into)) { t.phase = Phase.MOVE; t.phaseStart = tick; return; }
        if (m.kind == Kind.BRIDGE) {
            ap.setForward(0f);
            ap.setSneaking(true);
            Direction face = dirOf(m.dx - m.sx, m.dz - m.sz);
            if (face == null || !place(bot, new BlockPos(m.sx, m.sy - 1, m.sz), face, into)) {
                if (++t.placeTries > 3) { t.avoid.add(m.place); replan(bot, t, "couldn't bridge"); }
                return;
            }
            t.phase = Phase.MOVE;
            t.phaseStart = tick;
            return;
        }
        // PILLAR: jump, then put a block where our feet were
        ap.setForward(0f);
        ap.setSprinting(false);
        ap.look(bot.getYRot(), 90f);
        if (!t.jumped) {
            if (bot.onGround()) {
                ap.start(ActionType.JUMP, Action.once());
                t.jumped = true;
                t.jumpTick = tick;
            }
            return;
        }
        if (bot.getY() >= m.sy + 1.0) {
            if (place(bot, new BlockPos(m.sx, m.sy - 1, m.sz), Direction.UP, into)) {
                t.phase = Phase.MOVE;
                t.phaseStart = tick;
                return;
            }
        }
        if (tick - t.jumpTick > 14 && bot.onGround()) { // landed without placing: try again
            t.jumped = false;
            if (++t.placeTries > 4) { t.avoid.add(m.place); replan(bot, t, "couldn't pillar"); }
        }
    }

    private static Direction dirOf(int dx, int dz) {
        if (dx > 0) return Direction.EAST;
        if (dx < 0) return Direction.WEST;
        if (dz > 0) return Direction.SOUTH;
        if (dz < 0) return Direction.NORTH;
        return null;
    }

    // ---------------- moving ----------------

    private static void doMove(ServerPlayer bot, EntityPlayerActionPack ap, Task t, Move m, BlockPos f) {
        ap.setSneaking(false);
        doors(bot, t, m, f);
        Vec3 pos = bot.position();
        double cx = m.dx + 0.5, cz = m.dz + 0.5;
        double hx = cx - pos.x, hz = cz - pos.z;
        double horiz = Math.sqrt(hx * hx + hz * hz);
        boolean inWater = bot.isInWater();
        boolean atDest = f.getX() == m.dx && f.getY() == m.dy && f.getZ() == m.dz;

        Move next = t.index + 1 < t.path.size() ? t.path.get(t.index + 1) : null;
        boolean straightOn = next != null && (next.kind == Kind.WALK || next.kind == Kind.DIAGONAL)
                && next.breaks.length == 0 && next.place == ActionPathfinder.NONE
                && Integer.signum(next.dx - next.sx) == Integer.signum(m.dx - m.sx)
                && Integer.signum(next.dz - next.sz) == Integer.signum(m.dz - m.sz)
                && (m.kind == Kind.WALK || m.kind == Kind.DIAGONAL);
        double tolerance = straightOn ? 0.6 : 0.3;
        if (atDest && horiz < tolerance && (bot.onGround() || inWater || m.kind == Kind.SWIM_UP || m.kind == Kind.SWIM_DOWN)) {
            if (!straightOn) ap.setForward(0f);
            nextMove(bot, t);
            return;
        }

        // timeouts: stuck against something
        double expected = m.cost;
        if (tick - t.phaseStart > expected * 3 + 60) {
            t.avoid.add(ActionPathfinder.pack(m.dx, m.dy, m.dz));
            replan(bot, t, "move took too long: " + m);
            return;
        }
        if (t.lastPos != null && pos.distanceTo(t.lastPos) > 0.05) t.lastMoveTick = tick;
        t.lastPos = pos;
        if (tick - t.lastMoveTick > 40) {
            t.lastMoveTick = tick;
            replan(bot, t, "not moving");
            return;
        }

        // steer
        boolean vertical = m.kind == Kind.PILLAR || m.kind == Kind.DIG_DOWN || m.kind == Kind.SWIM_UP || m.kind == Kind.SWIM_DOWN;
        if (horiz > 0.08) {
            float yaw = (float) Math.toDegrees(Math.atan2(-hx, hz));
            ap.look(yaw, 10f);
        }
        float forward;
        if (vertical && horiz < 0.25) forward = 0f;
        else if (horiz < 0.35 && !straightOn) forward = 0.35f;
        else forward = 1f;
        ap.setForward(forward);
        ap.setStrafing(0f);
        boolean sprint = t.opts.sprint && !inWater && m.breaks.length == 0
                && (m.kind == Kind.WALK || m.kind == Kind.DIAGONAL) && (straightOn || horiz > 2.5);
        ap.setSprinting(sprint);

        // jumping
        boolean needUp = m.dy > f.getY() || (m.dy == f.getY() && pos.y < m.dy - 0.05 && !inWater);
        if (m.kind == Kind.ASCEND && bot.onGround() && horiz < 1.4 && pos.y < m.dy - 0.4) {
            ap.start(ActionType.JUMP, Action.once());
        } else if (inWater && (m.dy >= f.getY() || m.kind == Kind.SWIM_UP) && m.kind != Kind.SWIM_DOWN) {
            ap.start(ActionType.JUMP, Action.once()); // keep swimming up
        } else if (bot.horizontalCollision && bot.onGround() && needUp) {
            ap.start(ActionType.JUMP, Action.once());
        }
    }

    // ---------------- doors ----------------

    static boolean isOpenableDoor(String path) {
        return path.endsWith("_door") && !path.equals("iron_door");
    }

    /** Null if it isn't a door. */
    private static Boolean doorOpen(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (!isOpenableDoor(SurvivalBrain.blockPath(s))) return null;
        try {
            return s.getValue(net.minecraft.world.level.block.DoorBlock.OPEN);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Right-clicks the door like a player would (either half works). */
    private static void useDoor(ServerPlayer bot, BlockPos p) {
        Vec3 c = Vec3.atCenterOf(p);
        io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, c);
        Vec3 e = bot.getEyePosition();
        double dx = e.x - c.x, dz = e.z - c.z;
        Direction face = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        try {
            bot.gameMode.useItemOn(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(c, face, p, false));
            Motions.swingArm(bot);
        } catch (Throwable ignored) { }
    }

    /** Opens a closed door in the way, and shuts it again once through, like people do. */
    private static void doors(ServerPlayer bot, Task t, Move m, BlockPos f) {
        ServerLevel level = bot.level();
        if (t.openedDoor != null) {
            BlockPos d = t.openedDoor;
            Vec3 pos = bot.position();
            double hx = pos.x - (d.getX() + 0.5), hz = pos.z - (d.getZ() + 0.5);
            boolean inside = f.getX() == d.getX() && f.getZ() == d.getZ();
            if (!inside && hx * hx + hz * hz > 2.0 * 2.0) {
                if (Boolean.TRUE.equals(doorOpen(level, d)) && bot.position().distanceTo(Vec3.atCenterOf(d)) < 4.0) useDoor(bot, d);
                t.openedDoor = null;
            }
        }
        for (BlockPos p : new BlockPos[]{new BlockPos(m.dx, m.dy, m.dz), new BlockPos(m.dx, m.dy + 1, m.dz)}) {
            if (Boolean.FALSE.equals(doorOpen(level, p)) && bot.position().distanceTo(Vec3.atCenterOf(p)) < 3.5) {
                useDoor(bot, p);
                BlockPos lower = SurvivalBrain.blockPath(level.getBlockState(p.below())).equals(SurvivalBrain.blockPath(level.getBlockState(p)))
                        ? p.below() : p;
                t.openedDoor = lower;
                break;
            }
        }
    }

    // ------------------------------------------------------------------------
    // /bot path <bot> <pos>   (testing / manual use)
    // ------------------------------------------------------------------------

    public static void registerCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, ctx, sel) -> dispatcher.register(
                Commands.literal("bot").then(Commands.literal("path")
                        .then(Commands.argument("bot", EntityArgument.player())
                                .then(Commands.literal("stop").executes(c -> {
                                    ServerPlayer bot = EntityArgument.getPlayer(c, "bot");
                                    cancel(bot);
                                    reply(c, "Stopped.");
                                    return 1;
                                }))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(c -> {
                                            ServerPlayer bot = EntityArgument.getPlayer(c, "bot");
                                            BlockPos p = BlockPosArgument.getBlockPos(c, "pos");
                                            String name = bot.getName().getString();
                                            reply(c, name + " is heading to " + p.getX() + " " + p.getY() + " " + p.getZ());
                                            goTo(bot, ActionPathfinder.near(p.getX(), p.getY(), p.getZ(), 1.0), Options.full())
                                                    .thenAccept(r -> c.getSource().getServer().execute(() ->
                                                            c.getSource().sendSuccess(() -> Component.literal(name + ": " + r), false)));
                                            return 1;
                                        }))))));
    }

    private static void reply(CommandContext<CommandSourceStack> c, String text) {
        c.getSource().sendSuccess(() -> Component.literal(text), false);
    }
}
