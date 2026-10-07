package io.github.yudiiee.aicompanion.GameAI.human;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.onServer;

/**
 * The community depot: one block of double chests everybody puts their spare stuff into and
 * takes building material from. The first companion that has something to put away sets it up
 * (near the first base); the others use it, and it grows pair by pair when it's full.
 *
 * <p>Companions also ask each other for things here: while one gathers for a build it posts a
 * need, and an idle companion that carries some of it drops it in the depot, or goes and
 * gets it.
 *
 * <p>Saved in {@code <world>/ai-companion/depot.txt}: {@code D dim x y z} then {@code C x y z} per chest.
 */
public final class Depot {

    private Depot() {}

    /** Chest pairs along a row, and rows: 8 chests of room per row. */
    private static final int PAIRS_PER_ROW = 4;
    private static final int ROWS = 3;

    private static volatile String dim;
    private static volatile BlockPos center;
    private static final List<BlockPos> CHESTS = new CopyOnWriteArrayList<>();
    private static volatile boolean loaded;
    private static volatile java.nio.file.Path loadedFrom;

    /** After the depot couldn't be reached or set up: leave it alone for a while (millis). */
    static volatile long failUntil = 0;
    private static volatile String founder;
    private static volatile long founderAt;

    // ------------------------------------------------------------------------
    // Where it is
    // ------------------------------------------------------------------------

    private static java.nio.file.Path file() {
        return Home.worldFile("depot.txt");
    }

    private static synchronized void load() {
        java.nio.file.Path f0 = file();
        if (loaded && f0.equals(loadedFrom)) return;
        dim = null;
        center = null;
        CHESTS.clear();
        loaded = true;
        loadedFrom = f0;
        try {
            if (!Files.exists(f0)) return;
            for (String line : Files.readAllLines(f0, StandardCharsets.UTF_8)) {
                try {
                    String[] p = line.trim().split(" ");
                    if (p.length == 5 && p[0].equals("D")) {
                        dim = p[1];
                        center = new BlockPos(Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]));
                    } else if (p.length == 4 && p[0].equals("C")) {
                        CHESTS.add(new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    private static synchronized void save() {
        try {
            StringBuilder sb = new StringBuilder();
            if (center != null) sb.append("D ").append(dim).append(' ').append(center.getX()).append(' ')
                    .append(center.getY()).append(' ').append(center.getZ()).append('\n');
            for (BlockPos p : CHESTS) sb.append("C ").append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ()).append('\n');
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
    }

    private static String dimOf(ServerLevel level) {
        return String.valueOf(level.dimension()).replaceAll("[^A-Za-z0-9_:/.]", "");
    }

    /** Is there a depot in this dimension? */
    static boolean exists(ServerLevel level) {
        load();
        return center != null && !CHESTS.isEmpty() && dimOf(level).equals(dim);
    }

    static BlockPos center(ServerLevel level) {
        load();
        return exists(level) ? center : null;
    }

    /** The depot's chests that are still there (a chest that's gone is forgotten once its chunk is loaded). Server thread. */
    static List<BlockPos> chests(ServerLevel level) {
        load();
        List<BlockPos> out = new ArrayList<>();
        if (!dimOf(level).equals(dim)) return out;
        boolean changed = false;
        for (BlockPos p : CHESTS) {
            if (!level.isLoaded(p)) { out.add(p); continue; }
            if (Storage.isStorageBlock(SurvivalBrain.blockPath(level.getBlockState(p)))) out.add(p);
            else { CHESTS.remove(p); changed = true; }
        }
        if (changed) save();
        return out;
    }

    private static boolean isSecondHalf(ServerLevel level, BlockPos p) {
        return Storage.secondHalf(level, p);
    }

    /** What's in the depot, for the companions to talk about: "340 oak log, 128 cobblestone...". Server thread. */
    static String summary(ServerLevel level) {
        if (!exists(level)) return "";
        Map<String, Integer> all = new HashMap<>();
        for (BlockPos p : chests(level)) {
            if (isSecondHalf(level, p)) continue;
            Map<String, Integer> m = level.isLoaded(p) ? contents(level, p) : Storage.seen(level, p);
            if (m != null) m.forEach((k, v) -> all.merge(k, v, Integer::sum));
        }
        if (all.isEmpty()) return "the depot is empty";
        List<Map.Entry<String, Integer>> e = new ArrayList<>(all.entrySet());
        e.sort((x, y) -> y.getValue() - x.getValue());
        StringBuilder sb = new StringBuilder("the depot has ");
        int n = Math.min(8, e.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(e.get(i).getValue()).append(' ').append(e.get(i).getKey().replace('_', ' '));
        }
        if (e.size() > n) sb.append(" and more");
        return sb.toString();
    }

    private static Map<String, Integer> contents(ServerLevel level, BlockPos p) {
        Container c = HopperBlockEntity.getContainerAt(level, p);
        if (c == null) return null;
        Storage.note(level, p, c);
        return Storage.seen(level, p);
    }

    // ------------------------------------------------------------------------
    // Setting it up
    // ------------------------------------------------------------------------

    private static final int SEARCH = 20;
    private static final int BAD = Integer.MIN_VALUE;

    /** Height of the walkable ground in the column (feet height), or BAD. Server thread. */
    private static int ground(ServerLevel level, int x, int z, int top, int bottom) {
        for (int y = top; y >= bottom; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (!level.isLoaded(p)) return BAD;
            FluidState fs = level.getFluidState(p);
            if (fs != null && (fs.is(FluidTags.WATER) || fs.is(FluidTags.LAVA))) return BAD;
            if (!Building.isSolid(level, p)) continue;
            String path = SurvivalBrain.blockPath(level.getBlockState(p));
            if (Protection.isManMade(path) || path.endsWith("_leaves") || SurvivalBrain.isLog(level.getBlockState(p))
                    || path.contains("magma") || path.contains("ice") || path.contains("powder_snow")) return BAD;
            if (!Building.isFree(level, p.above()) || !Building.isFree(level, p.above().above())) return BAD;
            return y + 1;
        }
        return BAD;
    }

    /** A flat strip near {@code anchor} for the first row (feet height + x/z of its low corner), or null. Server thread. */
    private static int[] findSpot(ServerPlayer bot, BlockPos anchor) {
        ServerLevel level = bot.level();
        Protection.Context ctx = Protection.scan(bot, SEARCH + 12);
        int w = 2 * PAIRS_PER_ROW, d = 3 * ROWS;
        int span = 2 * SEARCH + w;
        int[][] h = new int[span][span + d];
        int ay = anchor.getY();
        for (int i = 0; i < span; i++) for (int j = 0; j < span + d; j++) {
            h[i][j] = ground(level, anchor.getX() - SEARCH + i, anchor.getZ() - SEARCH + j, ay + 6, ay - 6);
        }
        int[] best = null;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i + w <= span; i++) {
            for (int j = 0; j + d <= span + d; j++) {
                int floor = h[i][j];
                if (floor == BAD) continue;
                boolean ok = true;
                int uneven = 0;
                for (int a = 0; a < w && ok; a++) for (int c = 0; c < 2 && ok; c++) { // the first row
                    int g = h[i + a][j + c];
                    if (g == BAD || g != floor) ok = false;
                }
                if (!ok) continue;
                for (int a = 0; a < w; a++) for (int c = 0; c < d; c++) {
                    int g = h[i + a][j + c];
                    if (g == BAD || Math.abs(g - floor) > 1) uneven++;
                }
                if (uneven > d * w / 3) continue;
                int x0 = anchor.getX() - SEARCH + i, z0 = anchor.getZ() - SEARCH + j;
                double dist = Math.hypot(x0 + w / 2.0 - anchor.getX(), z0 + 1 - anchor.getZ());
                if (dist < 5) continue; // not right on top of the person/house
                double score = dist + uneven * 1.5 + Math.abs(floor - ay) * 2;
                if (score >= bd) continue;
                if (ctx.isVillage(new BlockPos(x0, floor, z0))) continue;
                bd = score;
                best = new int[]{x0, floor, z0};
            }
        }
        return best;
    }

    /** The next two free cells for a double chest (west one first), or null when the depot has no room left. Server thread. */
    private static BlockPos[] nextPair(ServerLevel level) {
        BlockPos origin = center;
        if (origin == null) return null;
        for (int r = 0; r < ROWS; r++) {
            for (int k = 0; k < PAIRS_PER_ROW; k++) {
                BlockPos a = origin.offset(2 * k, 0, 3 * r);
                BlockPos b = a.offset(1, 0, 0);
                if (cellUsable(level, a) && cellUsable(level, b)) return new BlockPos[]{a, b};
            }
        }
        return null;
    }

    private static boolean cellUsable(ServerLevel level, BlockPos p) {
        if (!level.isLoaded(p)) return false;
        if (CHESTS.contains(p)) return false;
        if (!Building.isFree(level, p) || !Building.isFree(level, p.above())) return false;
        if (!Building.isSolid(level, p.below())) return false;
        FluidState fs = level.getFluidState(p);
        return fs == null || !(fs.is(FluidTags.WATER) || fs.is(FluidTags.LAVA));
    }

    /** Puts a double chest down at {@code pair}. Job thread. */
    private static boolean placePair(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, BlockPos[] pair)
            throws InterruptedException {
        for (BlockPos c : pair) {
            if (!SurvivalBrain.canContinue(b)) return false;
            if (!BlueprintBuilder.approach(server, bot, c, 4.3)) return false;
            boolean ok = onServer(server, () -> Building.placeFacing(bot, c, "chest", Direction.SOUTH)
                    && Storage.isStorageBlock(SurvivalBrain.blockPath(bot.level().getBlockState(c))), false);
            if (!ok) return false;
            onServer(server, () -> { Storage.remember(bot.level(), c); return null; }, null);
            if (!CHESTS.contains(c)) CHESTS.add(c.immutable());
            SurvivalBrain.sleep(250);
        }
        save();
        return true;
    }

    /** Makes sure the bot carries {@code n} chests (crafts them from wood). Job thread. */
    private static boolean haveChests(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, int n)
            throws InterruptedException {
        int have = onServer(server, () -> Gathering.countOf(bot, "chest"::equals), 0);
        if (have >= n) return true;
        Gathering.Craftable chest = Gathering.craftable("chest");
        return chest != null && Gathering.makeSure(server, bot, b, chest, n);
    }

    /**
     * Sets the depot up if it isn't there: first double chest near the first base (or where the
     * bot stands). Only one companion does it; the others wait for it. Job thread.
     */
    static boolean found(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        if (onServer(server, () -> exists(bot.level()), false)) return true;
        long now = System.currentTimeMillis();
        synchronized (Depot.class) {
            if (founder != null && !founder.equals(b.name) && now - founderAt < 6 * 60_000L) {
                // somebody else is on it: wait for them
            } else {
                founder = b.name;
                founderAt = now;
            }
        }
        if (!b.name.equals(founder)) {
            long until = now + 4 * 60_000L;
            while (System.currentTimeMillis() < until && SurvivalBrain.canContinue(b)) {
                if (onServer(server, () -> exists(bot.level()), false)) return true;
                if (founder == null) break; // they gave up
                SurvivalBrain.sleep(3000);
            }
            return onServer(server, () -> exists(bot.level()), false);
        }
        try {
            SurvivalBrain.maybeSay(server, b, HumanChat.pick("gonna set up a community chest area for all of us",
                    "setting up a depot, one set of chests for everyone's stuff"), 1.0);
            if (!haveChests(server, bot, b, 2)) { failUntil = System.currentTimeMillis() + 4 * 60_000L; return false; }
            founderAt = System.currentTimeMillis();
            BlockPos anchor = onServer(server, () -> {
                Home.Base h = Home.get(bot);
                return h != null && h.dim().equals(Home.dim(bot.level())) ? h.middle() : bot.blockPosition();
            }, null);
            if (anchor == null) return false;
            int[] spot = onServer(server, () -> findSpot(bot, anchor), null);
            BlockPos origin;
            if (spot != null) {
                origin = new BlockPos(spot[0], spot[1], spot[2]);
            } else {
                // nowhere flat: right where it stands
                origin = onServer(server, () -> {
                    BlockPos f = BotPathing.feet(bot);
                    return f.offset(2, 0, 0);
                }, null);
            }
            if (origin == null) return false;
            final BlockPos o0 = origin;
            synchronized (Depot.class) {
                dim = onServer(server, () -> dimOf(bot.level()), "");
                center = o0.immutable();
                CHESTS.clear();
            }
            BlockPos[] pair = onServer(server, () -> nextPair(bot.level()), null);
            if (pair == null || !placePair(server, bot, b, pair)) {
                synchronized (Depot.class) { if (CHESTS.isEmpty()) center = null; } // keep it if a chest is already down
                failUntil = System.currentTimeMillis() + 5 * 60_000L;
                return false;
            }
            save();
            lightUp(server, bot, b, origin);
            HumanChat.say(server, b.name, "the depot's up at " + Stock.where(origin)
                    + ". put your spare stuff in there and take what you need for builds");
            return true;
        } finally {
            synchronized (Depot.class) { if (b.name.equals(founder)) founder = null; }
        }
    }

    /** A torch at each end of the first row, if it has some. */
    private static void lightUp(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, BlockPos origin)
            throws InterruptedException {
        for (BlockPos t : new BlockPos[]{origin.offset(-1, 0, 0), origin.offset(2 * PAIRS_PER_ROW, 0, 0)}) {
            if (!SurvivalBrain.canContinue(b)) return;
            if (onServer(server, () -> Building.firstItem(bot, "torch"::equals) == null, true)) return;
            if (!onServer(server, () -> Building.isFree(bot.level(), t) && Building.isSolid(bot.level(), t.below()), false)) continue;
            if (!BlueprintBuilder.approach(server, bot, t, 4.3)) continue;
            onServer(server, () -> Building.placeAgainst(bot, t, "torch", Direction.DOWN), false);
        }
    }

    // ------------------------------------------------------------------------
    // Putting things in
    // ------------------------------------------------------------------------

    /**
     * Takes everything the bot doesn't need to the depot (setting it up, or adding a double chest,
     * as needed). Returns how many items went in, or -1 if there's no depot to use right now
     * (then the older storage logic takes over). Job thread.
     */
    static int unload(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, boolean talk) throws InterruptedException {
        if (!HumanConfig.get().autoStore) return -1;
        if (System.currentTimeMillis() < failUntil) return -1;
        if (onServer(server, () -> Storage.storable(bot), 0) == 0) return 0;
        if (!onServer(server, () -> exists(bot.level()) || Home.overworld(bot.level()), false)) return -1;
        if (!found(server, bot, b)) return -1;
        BlockPos c = onServer(server, () -> center(bot.level()), null);
        if (c == null) return -1;
        int total = 0;
        boolean grew = false;
        for (int round = 0; round < 6 && SurvivalBrain.canContinue(b); round++) {
            // walk over to the depot
            if (onServer(server, () -> bot.position().distanceTo(Vec3.atCenterOf(c)) > 8.0, true)) {
                BotPathing.Options o = BotPathing.Options.full();
                o.timeoutTicks = 20 * 150;
                BotPathing.goToBlocking(bot, ActionPathfinder.near(c.getX(), c.getY(), c.getZ(), 3.0), o, 155_000L);
                if (onServer(server, () -> bot.position().distanceTo(Vec3.atCenterOf(c)) > 14.0, true)) {
                    failUntil = System.currentTimeMillis() + 3 * 60_000L;
                    if (talk) HumanChat.say(server, b.name, "can't get to the depot right now");
                    return total > 0 ? total : -1;
                }
            }
            List<BlockPos> chests = onServer(server, () -> {
                List<BlockPos> l = new ArrayList<>();
                for (BlockPos p : chests(bot.level())) if (!isSecondHalf(bot.level(), p)) l.add(p);
                l.sort(Comparator.comparingDouble(p -> p.distSqr(bot.blockPosition())));
                return l;
            }, List.of());
            int left = onServer(server, () -> Storage.storable(bot), 0);
            boolean reached = false;
            for (BlockPos p : chests) {
                if (left == 0 || !SurvivalBrain.canContinue(b)) break;
                if (onServer(server, () -> bot.position().distanceTo(Vec3.atCenterOf(p)) > 4.8, true)) {
                    BotPathing.Options o = BotPathing.Options.walkOnly();
                    o.timeoutTicks = 20 * 30;
                    BotPathing.goToBlocking(bot, ActionPathfinder.near(p.getX(), p.getY(), p.getZ(), 2.5), o, 32_000L);
                }
                int[] r = onServer(server, () -> {
                    ServerLevel level = bot.level();
                    Container ct = HopperBlockEntity.getContainerAt(level, p);
                    if (ct == null || bot.position().distanceTo(Vec3.atCenterOf(p)) > 5.6) return new int[]{-1, 0};
                    io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, Vec3.atCenterOf(p));
                    int[] d = Storage.deposit(bot, ct);
                    Storage.note(level, p, ct);
                    if (d[0] > 0) Motions.swingArm(bot);
                    return d;
                }, new int[]{-1, 0});
                if (r[0] < 0) continue;
                reached = true;
                total += r[0];
                left = r[1];
            }
            if (left == 0) break;
            if (!reached) { // couldn't open any chest: not full, just out of reach right now
                failUntil = System.currentTimeMillis() + 3 * 60_000L;
                break;
            }
            // full: another double chest
            BlockPos[] pair = onServer(server, () -> nextPair(bot.level()), null);
            if (pair == null) {
                failUntil = System.currentTimeMillis() + 10 * 60_000L;
                if (talk || HumanReactions.cooldown("depot-full", 15 * 60_000L)) {
                    HumanChat.say(server, b.name, "the depot's full, we need another one");
                }
                break;
            }
            if (!haveChests(server, bot, b, 2)) { failUntil = System.currentTimeMillis() + 4 * 60_000L; break; }
            if (!placePair(server, bot, b, pair)) { failUntil = System.currentTimeMillis() + 4 * 60_000L; break; }
            grew = true;
        }
        onServer(server, () -> Stock.record(bot), null);
        Storage.flushSeen();
        if (total > 0) {
            final int t = total;
            if (talk || HumanReactions.cooldown(b.name + ":depot-say", 90_000L)) {
                HumanChat.say(server, b.name, HumanChat.pick("put " + t + " items in the depot", "dropped " + t + " things in the community chests",
                        "depot's got " + t + " more items now"));
            }
        }
        if (grew) HumanChat.say(server, b.name, "added more chests to the depot");
        return total;
    }

    // ------------------------------------------------------------------------
    // Asking for things, and helping
    // ------------------------------------------------------------------------

    /** What somebody is out gathering for a build. */
    record Need(String who, String item, String label, int qty, long at, String claimedBy) {}

    private static final Map<String, Need> NEEDS = new ConcurrentHashMap<>();

    private static String needKey(String who, String item) {
        return who.toLowerCase(Locale.ROOT) + ":" + item;
    }

    /** {@code who} is out getting {@code qty} of {@code item} (for a build): the others may help. */
    static void postNeed(String who, String item, String label, int qty) {
        if (qty < 8) return;
        NEEDS.put(needKey(who, item), new Need(who, item, label, qty, System.currentTimeMillis(), null));
    }

    static void clearNeed(String who, String item) {
        NEEDS.remove(needKey(who, item));
    }

    /** Atomic: only one companion takes a need. */
    private static boolean tryClaim(Need n, String by) {
        boolean[] won = {false};
        NEEDS.computeIfPresent(needKey(n.who(), n.item()), (k, v) -> {
            if (v.claimedBy() != null && System.currentTimeMillis() - v.at() < 5 * 60_000L) return v;
            won[0] = true;
            return new Need(v.who(), v.item(), v.label(), v.qty(), System.currentTimeMillis() - 25_000L, by);
        });
        return won[0];
    }

    private static void release(Need n) {
        NEEDS.computeIfPresent(needKey(n.who(), n.item()), (k, v) -> new Need(v.who(), v.item(), v.label(), v.qty(), v.at(), null));
    }

    private static final Map<String, Long> NEXT_HELP = new ConcurrentHashMap<>();

    /**
     * An idle companion looks at what the others are gathering for: if it carries some it puts it in
     * the depot at once; otherwise it goes and gets some for the depot. True if it started doing so.
     * Brain thread (not inside a job).
     */
    static boolean helpTick(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) {
        if (!HumanConfig.get().teamwork || NEEDS.isEmpty()) return false;
        long now = System.currentTimeMillis();
        if (now < NEXT_HELP.getOrDefault(b.name, 0L) || now < failUntil) return false;
        NEXT_HELP.put(b.name, now + 8_000L); // look at most every few seconds
        for (Need n : new ArrayList<>(NEEDS.values())) {
            if (now - n.at() > 20 * 60_000L) { NEEDS.remove(needKey(n.who(), n.item())); continue; }
            if (n.who().equalsIgnoreCase(b.name) || now - n.at() < 20_000L) continue;
            Predicate<String> test = onServer(server, () -> BlueprintBuilder.testFor(bot, n.item()), null);
            if (test == null) continue;
            int spare = onServer(server, () -> Stock.spareOf(bot, test), 0);
            int inDepot = onServer(server, () -> Stock.inChests(bot.level(), test), 0);
            if (inDepot >= n.qty()) continue; // it's already in the chests: they'll find it
            if (spare >= 4) {
                NEXT_HELP.put(b.name, now + 90_000L);
                final int amount = spare;
                SurvivalBrain.startJob(bot, "drop things off for " + n.who(), false, (s, bt, bb) -> {
                    HumanChat.say(s, bb.name, n.who() + ", putting " + amount + " " + n.label() + " in the depot for you");
                    unload(s, bt, bb, false);
                });
                return true;
            }
            MiningSkills.Target t = BlueprintBuilder.gatherTarget(n.item());
            if (t == null || n.claimedBy() != null || n.qty() < 8 || !exists(bot.level())) continue;
            int tier = MiningSkills.pickTier(server, bot);
            if (t.tier() > tier) continue;
            NEXT_HELP.put(b.name, now + 3 * 60_000L);
            if (!tryClaim(n, b.name)) continue;
            final int want = Math.min(n.qty(), 48);
            SurvivalBrain.startJob(bot, "get " + n.label() + " for " + n.who(), false, (s, bt, bb) -> {
                try {
                    HumanChat.say(s, bb.name, HumanChat.pick("on it " + n.who() + ", getting " + want + " " + n.label() + " for the depot",
                            n.who() + " i'll get some " + n.label() + " too, leaving it in the depot"));
                    MiningSkills.collect(s, bt, bb, t, want, true);
                    int moved = unload(s, bt, bb, false);
                    if (moved > 0) HumanChat.say(s, bb.name, n.who() + ", " + n.label() + " is in the depot");
                } finally {
                    release(n);
                }
            });
            return true;
        }
        return false;
    }
}
