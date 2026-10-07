package io.github.yudiiee.aicompanion.GameAI.human;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.onServer;

/**
 * A food farm next to the base, like a player's first one: a flat 9x9 of farmland round a
 * water block in the middle (a slab on top so nobody falls in), torches all round so crops
 * grow at night and nothing spawns on it, planted with carrots or potatoes (no crafting
 * needed), else wheat (for bread), else beetroot. The bot harvests what's ripe, replants,
 * bakes bread, keeps about 32 food on it and puts the rest in the chests.
 * Saved per bot, per world ({@code <world>/ai-companion/farms.txt}).
 */
final class Farm {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-farm");

    static final int SIZE = 9;
    static final int MID = SIZE / 2;
    static final int KEEP_FOOD = 32;

    private Farm() {}

    /** A plot: {@code y} is the farmland level (crops grow at y+1). */
    record Plot(String dim, int x0, int y, int z0) {
        BlockPos cell(int lx, int lz) { return new BlockPos(x0 + lx, y, z0 + lz); }
        BlockPos water() { return cell(MID, MID); }
        BlockPos middle() { return cell(MID, MID + 1).above(); }
        boolean contains(BlockPos p) {
            return p.getX() >= x0 && p.getX() < x0 + SIZE && p.getZ() >= z0 && p.getZ() < z0 + SIZE;
        }
        List<BlockPos> soil() {
            List<BlockPos> out = new ArrayList<>();
            for (int lx = 0; lx < SIZE; lx++) for (int lz = 0; lz < SIZE; lz++) {
                if (lx == MID && lz == MID) continue;
                out.add(cell(lx, lz));
            }
            return out;
        }
    }

    // ------------------------------------------------------------------------
    // Saved farms
    // ------------------------------------------------------------------------

    private static final Map<String, Plot> FARMS = new ConcurrentHashMap<>();
    private static final Map<String, Long> NEXT_BUILD_TRY = new ConcurrentHashMap<>();
    private static final Map<String, Integer> BUILD_FAILURES = new ConcurrentHashMap<>();
    private static final Map<String, Long> NEXT_TEND = new ConcurrentHashMap<>();
    private static final Map<String, Long> NEXT_FEED = new ConcurrentHashMap<>();
    private static volatile Path loadedFrom;

    private static String who(ServerPlayer bot) { return bot.getName().getString().toLowerCase(Locale.ROOT); }

    private static String key(ServerPlayer bot) { return who(bot) + "|" + Home.dim(bot.level()); }

    private static synchronized void load() {
        Path f = Home.worldFile("farms.txt");
        if (f.equals(loadedFrom)) return;
        FARMS.clear();
        NEXT_BUILD_TRY.clear();
        BUILD_FAILURES.clear();
        NEXT_TEND.clear();
        loadedFrom = f;
        try {
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = line.trim().split(" ");
                if (p.length != 5) continue;
                FARMS.put(p[0] + "|" + p[1], new Plot(p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4])));
            }
        } catch (Exception e) {
            LOGGER.warn("[farm] couldn't read {}: {}", f, e.toString());
        }
    }

    private static synchronized void save() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Plot> e : FARMS.entrySet()) {
                Plot p = e.getValue();
                sb.append(e.getKey(), 0, e.getKey().indexOf('|')).append(' ').append(p.dim()).append(' ')
                        .append(p.x0()).append(' ').append(p.y()).append(' ').append(p.z0()).append('\n');
            }
            Files.writeString(Home.worldFile("farms.txt"), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[farm] couldn't save: {}", e.toString());
        }
    }

    static Plot get(ServerPlayer bot) {
        load();
        return FARMS.get(key(bot));
    }

    /** Every bot's food farm in this world. */
    static List<Plot> all() {
        load();
        return new ArrayList<>(FARMS.values());
    }

    static boolean hasFarm(ServerPlayer bot) {
        return get(bot) != null;
    }

    // ------------------------------------------------------------------------
    // Crops and seeds
    // ------------------------------------------------------------------------

    /** Seed item -> the crop block it grows, best first (carrots/potatoes need no crafting). */
    static final String[][] SEEDS = {{"carrot", "carrots"}, {"potato", "potatoes"}, {"wheat_seeds", "wheat"}, {"beetroot_seeds", "beetroots"}};

    static boolean isSeed(String p) {
        for (String[] s : SEEDS) if (s[0].equals(p)) return true;
        return false;
    }

    static boolean isCrop(String p) {
        return p.equals("wheat") || p.equals("carrots") || p.equals("potatoes") || p.equals("beetroots");
    }

    private static final Pattern AGE = Pattern.compile("\\bage=(\\d+)");

    /** Crop age from the block state (e.g. "Block{minecraft:wheat}[age=7]"), or -1. */
    static int age(String stateText) {
        Matcher m = AGE.matcher(stateText == null ? "" : stateText);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Fully grown: wheat, carrots, potatoes at age 7; beetroot at 3. */
    static boolean ripe(String cropPath, int age) {
        if (!isCrop(cropPath)) return false;
        return cropPath.equals("beetroots") ? age >= 3 : age >= 7;
    }

    static boolean ripe(BlockState s) {
        return ripe(SurvivalBrain.blockPath(s), age(String.valueOf(s)));
    }

    private static boolean tillable(String p) {
        return p.equals("grass_block") || p.equals("dirt") || p.equals("dirt_path") || p.equals("coarse_dirt")
                || p.equals("rooted_dirt") || p.equals("farmland");
    }

    /** Best seed in the pockets, or null. */
    private static String bestSeed(ServerPlayer bot) {
        for (String[] s : SEEDS) if (Building.firstItem(bot, s[0]::equals) != null) return s[0];
        return null;
    }

    private static int seedCount(ServerPlayer bot) {
        return Gathering.countOf(bot, Farm::isSeed);
    }

    // ------------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------------

    private static final Pattern BUILD = Pattern.compile("\\b((build|make|start|set up|setup|plant|dig) (a |us a |me a |the |our )?(crop |food |wheat |carrot |potato )?(farm|garden|field))\\b");
    private static final Pattern TEND = Pattern.compile("\\b((tend|harvest|check|water|replant|work) (on )?(the |our |your |some |all the )?(farm|crops|field|garden|wheat|carrots|potatoes)"
            + "|(plant|sow) (some |the |more )?(seeds|crops|wheat|carrots|potatoes))\\b");

    /** "build a farm" / "harvest the crops", or null. */
    static MiningSkills.Request request(String text) {
        String m = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
        if (m.isEmpty() || m.length() > 80) return null;
        boolean build = BUILD.matcher(m).find();
        if (!build && !TEND.matcher(m).find()) return null;
        return new MiningSkills.Request(build ? "build a farm" : "tend the farm",
                build ? HumanChat.pick("ok, gonna make a farm", "sure, a farm next to the base, on it", "farm time")
                        : HumanChat.pick("ok, checking the crops", "sure, gonna tend the farm"),
                (server, bot, b) -> {
                    Plot p = onServer(server, () -> get(bot), null);
                    if (p == null) build(server, bot, b);
                    else tend(server, bot, b, p, true);
                });
    }

    // ------------------------------------------------------------------------
    // Living with a farm (Home.tick calls this in the daytime)
    // ------------------------------------------------------------------------

    /** Starts building or tending the farm when it's time. True if it did something. Brain thread. */
    static boolean tick(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        long now = System.currentTimeMillis();
        Plot p = onServer(server, () -> get(bot), null);
        if (p == null) {
            if (now < NEXT_BUILD_TRY.getOrDefault(who(bot), 0L)) return false;
            if (onServer(server, () -> {
                Home.Base h = Home.get(bot);
                return h == null || !Home.overworld(bot.level()) || h.middle().distSqr(bot.blockPosition()) > 48 * 48;
            }, true)) return false;
            int fails = BUILD_FAILURES.getOrDefault(who(bot), 0);
            NEXT_BUILD_TRY.put(who(bot), now + Math.min(90, 15L << Math.min(3, fails)) * 60_000L);
            String name = who(bot);
            SurvivalBrain.maybeSay(server, b, HumanChat.pick("gonna set up a little farm so we don't starve", "time for a crop farm"), 0.8);
            SurvivalBrain.startJob(bot, "build a farm", true, (s, bt, bb) -> {
                build(s, bt, bb);
                if (onServer(s, () -> get(bt), null) == null) BUILD_FAILURES.merge(name, 1, Integer::sum);
            });
            return true;
        }
        if (now < NEXT_TEND.getOrDefault(who(bot), 0L)) return false;
        NEXT_TEND.put(who(bot), now + 4 * 60_000L);
        boolean worth = onServer(server, () -> {
            if (!p.dim().equals(Home.dim(bot.level()))) return false;
            if (bot.blockPosition().distSqr(p.middle()) > 160 * 160) return false;
            ServerLevel level = bot.level();
            int ripe = 0, empty = 0;
            for (BlockPos c : p.soil()) {
                BlockState above = level.getBlockState(c.above());
                if (ripe(above)) ripe++;
                else if (above.isAir() && SurvivalBrain.blockPath(level.getBlockState(c)).equals("farmland")) empty++;
            }
            boolean hungry = bot.getFoodData().getFoodLevel() <= 14 && !io.github.yudiiee.aicompanion.PlayerUtils.FoodConsumptionTool.hasSafeFood(bot);
            return ripe >= 8 || (hungry && ripe > 0) || (empty >= 6 && seedCount(bot) + Storage.stockOf(level, Farm::isSeed) > 0);
        }, false);
        if (!worth) return false;
        tend(server, bot, b, p, false);
        return true;
    }

    /** Starving and there's something ripe: go harvest now (skips the timer). Brain thread. */
    static boolean feedYourself(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        long now = System.currentTimeMillis();
        if (now < NEXT_FEED.getOrDefault(who(bot), 0L)) return false;
        Plot p = onServer(server, () -> get(bot), null);
        if (p == null) return false;
        if (onServer(server, () -> !p.dim().equals(Home.dim(bot.level())) || bot.blockPosition().distSqr(p.middle()) > 160 * 160, true)) return false;
        NEXT_FEED.put(who(bot), now + 3 * 60_000L);
        boolean any = onServer(server, () -> {
            for (BlockPos c : p.soil()) if (ripe(bot.level().getBlockState(c.above()))) return true;
            return false;
        }, false);
        if (!any) return false;
        SurvivalBrain.maybeSay(server, b, HumanChat.pick("starving, gonna grab some food from the farm", "need food, heading to the farm"), 0.8);
        tend(server, bot, b, p, false);
        return true;
    }

    // ------------------------------------------------------------------------
    // Where
    // ------------------------------------------------------------------------

    private static final int BAD = Integer.MAX_VALUE;

    /** How much work making a farm at (x0, y, z0) takes, or BAD (water, builds, trees, a cliff). Server thread. */
    private static int cost(ServerLevel level, int x0, int y, int z0, List<Home.Base> homes, Protection.Context ctx) {
        int work = 0;
        for (int lx = -1; lx <= SIZE; lx++) {
            for (int lz = -1; lz <= SIZE; lz++) {
                boolean ring = lx < 0 || lz < 0 || lx == SIZE || lz == SIZE;
                BlockPos top = new BlockPos(x0 + lx, y, z0 + lz);
                if (!level.isLoaded(top)) return BAD;
                for (Home.Base h : homes) {
                    House.Site s = h.site();
                    if (top.getX() >= s.x0() - 1 && top.getX() <= s.x0() + s.size() && top.getZ() >= s.z0() - 1
                            && top.getZ() <= s.z0() + s.size()) return BAD; // (keeps a path round the house)
                }
                if (ctx.isVillage(top)) return BAD;
                if (ring) continue;
                for (int dy = -1; dy <= 3; dy++) {
                    BlockPos p = top.offset(0, dy, 0);
                    BlockState s = level.getBlockState(p);
                    String path = SurvivalBrain.blockPath(s);
                    if (!level.getFluidState(p).isEmpty() && dy >= 0) return BAD;
                    if (Protection.isManMade(path) || SurvivalBrain.isLog(s)) return BAD;
                    if (dy >= 1 && !s.isAir()) work += Building.isFree(level, p) ? 1 : 3; // plants, then rock
                }
                String t = SurvivalBrain.blockPath(level.getBlockState(top));
                if (tillable(t)) continue;
                if (Building.isFree(level, top)) work += Building.isFree(level, top.below()) ? 4 : 2;
                else work += 3;
            }
        }
        return work > 60 ? BAD : work;
    }

    /** Beside the house (not in front of the door), else around the bot. Server thread. */
    static Plot pickSite(ServerPlayer bot) {
        ServerLevel level = bot.level();
        Home.Base h = Home.get(bot);
        List<Home.Base> homes = Home.allIn(level);
        Protection.Context ctx = Protection.scan(bot, 32);
        List<int[]> cands = new ArrayList<>();
        int y;
        if (h != null) {
            House.Site s = h.site();
            y = s.y();
            int n = s.size(), c = (n - SIZE) / 2;
            for (int gap = 2; gap <= 4; gap++) {
                for (int shift = -4; shift <= 4; shift += 4) {
                    int[][] sides = {
                            {s.x0() + n + gap, s.z0() + c + shift, 'E'}, {s.x0() - gap - SIZE, s.z0() + c + shift, 'W'},
                            {s.x0() + c + shift, s.z0() + n + gap, 'S'}, {s.x0() + c + shift, s.z0() - gap - SIZE, 'N'}};
                    for (int[] sd : sides) {
                        char side = (char) sd[2];
                        Direction d = side == 'E' ? Direction.EAST : side == 'W' ? Direction.WEST : side == 'S' ? Direction.SOUTH : Direction.NORTH;
                        if (d == s.door()) continue; // keep the front of the house clear
                        cands.add(new int[]{sd[0], sd[1]});
                    }
                }
            }
        } else {
            BlockPos f = BotPathing.feet(bot);
            y = f.getY() - 1;
            for (int r = 2; r <= 14; r += 3) {
                for (int a = 0; a < 8; a++) {
                    double ang = a * Math.PI / 4;
                    cands.add(new int[]{f.getX() + (int) Math.round(Math.cos(ang) * r) - MID, f.getZ() + (int) Math.round(Math.sin(ang) * r) - MID});
                }
            }
        }
        Plot best = null;
        int bestCost = BAD;
        for (int[] c : cands) {
            for (int dy : h != null ? new int[]{0} : new int[]{0, -1}) {
                int cc = cost(level, c[0], y + dy, c[1], homes, ctx);
                if (cc < bestCost) { bestCost = cc; best = new Plot(Home.dim(level), c[0], y + dy, c[1]); }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------
    // Building it
    // ------------------------------------------------------------------------

    private static final Predicate<String> FARM_STUFF = p -> p.endsWith("_hoe") || isSeed(p) || p.equals("bucket")
            || p.equals("water_bucket") || p.endsWith("_slab") || p.equals("dirt") || p.equals("torch") || p.equals("wheat")
            || p.equals("iron_ingot") || p.equals("raw_iron");

    /** Job: build the farm (and plant it). */
    static void build(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        SurvivalBrain.keep(bot, FARM_STUFF);
        try {
            Plot p = onServer(server, () -> pickSite(bot), null);
            if (p == null) {
                HumanChat.say(server, b.name, HumanChat.pick("can't find a flat spot for a farm near the base",
                        "no good spot for a farm around here"));
                return;
            }
            if (!ensureHoe(server, bot, b)) {
                HumanChat.say(server, b.name, "can't make a hoe right now, the farm will have to wait");
                return;
            }
            boolean water = ensureWaterBucket(server, bot, b);
            Lighting.ensureTorches(server, bot, b, 10, 4, true);
            ensureSeeds(server, bot, b, 10);
            if (!SurvivalBrain.jobAlive(b)) return;
            SurvivalBrain.maybeSay(server, b, HumanChat.pick("farm's going right here", "ok, building the farm here"), 0.8);

            if (!prepare(server, bot, b, p)) {
                if (SurvivalBrain.jobAlive(b)) HumanChat.say(server, b.name, "couldn't level the ground for the farm, sorry");
                return;
            }
            if (water) water = placeWater(server, bot, b, p);
            if (!SurvivalBrain.jobAlive(b)) return;
            int tilled = till(server, bot, b, p);
            torches(server, bot, b, p);
            int planted = plant(server, bot, b, p);
            onServer(server, () -> {
                load();
                FARMS.put(key(bot), p);
                BUILD_FAILURES.remove(who(bot));
                save();
                return null;
            }, null);
            String note = water ? "" : " (no water yet, need a bucket and some iron for that)";
            if (planted == 0) note += " nothing to plant yet though, gotta find seeds";
            HumanChat.say(server, b.name, HumanChat.pick("farm's done! " + tilled + " farmland, " + planted + " planted", "ok the farm is ready")
                    + note);
        } finally {
            SurvivalBrain.keep(bot, null);
        }
    }

    private static boolean ensureHoe(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        if (onServer(server, () -> Building.firstItem(bot, p -> p.endsWith("_hoe")) != null, false)) return true;
        if (Storage.withdraw(server, bot, b, p -> p.endsWith("_hoe"), 1, "hoe") > 0) return true;
        for (String kind : new String[]{"stone hoe", "wooden hoe"}) {
            Gathering.Craftable c = Gathering.craftable(kind);
            if (c != null && Gathering.makeSure(server, bot, b, c, 1)) return true;
            if (!SurvivalBrain.jobAlive(b)) return false;
        }
        return false;
    }

    /**
     * A bucket of water: one it has, or an empty bucket (made from 3 iron: ingots from the
     * pockets/chests, or raw iron smelted), filled at the nearest water. Job thread.
     */
    static boolean ensureWaterBucket(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        if (onServer(server, () -> Building.firstItem(bot, "water_bucket"::equals) != null, false)) return true;
        if (Storage.withdraw(server, bot, b, "water_bucket"::equals, 1, null) > 0) return true;
        boolean bucket = onServer(server, () -> Building.firstItem(bot, "bucket"::equals) != null, false)
                || Storage.withdraw(server, bot, b, "bucket"::equals, 1, null) > 0;
        if (!bucket) {
            int ingots = onServer(server, () -> Gathering.countOf(bot, "iron_ingot"::equals), 0);
            if (ingots < 3) ingots += Storage.withdraw(server, bot, b, "iron_ingot"::equals, 3 - ingots, null);
            if (ingots < 3) {
                int raw = onServer(server, () -> Gathering.countOf(bot, "raw_iron"::equals)
                        + Storage.stockOf(bot.level(), "raw_iron"::equals), 0);
                if (raw >= 3 - ingots) {
                    SurvivalBrain.maybeSay(server, b, "smelting some iron for a bucket", 0.8);
                    Smelting.Recipe r = Smelting.recipeFor("iron");
                    if (r != null) Smelting.smeltFor(server, bot, b, r, 3 - ingots, null);
                    ingots = onServer(server, () -> Gathering.countOf(bot, "iron_ingot"::equals), 0);
                }
            }
            if (ingots < 3) return false;
            bucket = onServer(server, () -> {
                if (Gathering.countOf(bot, "iron_ingot"::equals) < 3) return false;
                SurvivalBrain.take(bot, "iron_ingot"::equals, 3);
                SurvivalBrain.give(bot, "bucket", 1);
                Motions.swingArm(bot);
                return true;
            }, false);
            if (!bucket) return false;
        }
        return fillBucket(server, bot, b);
    }

    /** Walks to the nearest still water and scoops a bucket of it. Job thread. */
    private static boolean fillBucket(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        BlockPos src = onServer(server, () -> nearestWaterSource(bot, 48), null);
        if (src == null) return false;
        for (int attempt = 0; attempt < 3 && SurvivalBrain.jobAlive(b); attempt++) {
            if (onServer(server, () -> bot.getEyePosition().distanceTo(Vec3.atCenterOf(src)) > 3.8, true)) {
                BotPathing.Options o = BotPathing.Options.walkOnly();
                o.timeoutTicks = 20 * 60;
                BotPathing.goToBlocking(bot, ActionPathfinder.reach(src.getX(), src.getY(), src.getZ(), 3.0), o, 65_000L);
            }
            boolean ok = onServer(server, () -> {
                if (!Smelting.holdItem(bot, "bucket")) return false;
                io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, new Vec3(src.getX() + 0.5, src.getY() + 0.6, src.getZ() + 0.5));
                try {
                    bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
                } catch (Throwable ignored) { }
                Motions.swingArm(bot);
                return Building.firstItem(bot, "water_bucket"::equals) != null;
            }, false);
            if (ok) return true;
            SurvivalBrain.sleep(400);
        }
        return false;
    }

    /** A water source block open to the sky above it, within {@code r}. Server thread. */
    private static BlockPos nearestWaterSource(ServerPlayer bot, int r) {
        ServerLevel level = bot.level();
        BlockPos f = BotPathing.feet(bot);
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -6; dy <= 4; dy++) {
            BlockPos p = f.offset(dx, dy, dz);
            double d = dx * dx + dz * dz + dy * dy * 2;
            if (d >= bd || !level.isLoaded(p)) continue;
            BlockState s = level.getBlockState(p);
            if (!SurvivalBrain.blockPath(s).equals("water")) continue;
            if (!String.valueOf(s).contains("level=0")) continue; // a source, not flowing water
            if (!level.getBlockState(p.above()).isAir()) continue;
            best = p;
            bd = d;
        }
        return best;
    }

    private static final int[][] STANDS = {{2, 2}, {2, 6}, {6, 2}, {6, 6}, {4, 2}, {2, 4}, {6, 4}, {4, 6}};

    /** Gets within arm's reach of {@code target}, standing on the plot. Job thread. */
    private static void approach(MinecraftServer server, ServerPlayer bot, Plot p, BlockPos target) throws InterruptedException {
        if (onServer(server, () -> bot.getEyePosition().distanceTo(Vec3.atCenterOf(target)) <= 4.3, false)) return;
        SurvivalBrain.waitWhileFighting(bot);
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int[] s : STANDS) {
            BlockPos c = p.cell(s[0], s[1]).above();
            double d = c.distSqr(target);
            if (d < bd) { bd = d; best = c; }
        }
        final BlockPos stand = best;
        BotPathing.Options o = BotPathing.Options.walkOnly();
        o.timeoutTicks = 20 * 30;
        BotPathing.goToBlocking(bot, ActionPathfinder.near(stand.getX(), stand.getY(), stand.getZ(), 0.6), o, 32_000L);
        if (onServer(server, () -> bot.getEyePosition().distanceTo(Vec3.atCenterOf(target)) > 4.5, false)) {
            BotPathing.Options f = BotPathing.Options.full();
            f.timeoutTicks = 20 * 30;
            BotPathing.goToBlocking(bot, ActionPathfinder.reach(target.getX(), target.getY(), target.getZ(), 4.0), f, 32_000L);
        }
    }

    /** Flattens the plot: clears plants and rock above, fills holes and swaps odd blocks for dirt. */
    private static boolean prepare(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Plot p) throws InterruptedException {
        // dirt for the holes first (dug outside the plot)
        int holes = onServer(server, () -> {
            ServerLevel level = bot.level();
            int n = 0;
            for (BlockPos c : p.soil()) if (!tillable(SurvivalBrain.blockPath(level.getBlockState(c)))) n++;
            return n;
        }, 0);
        int dirt = onServer(server, () -> Gathering.countOf(bot, "dirt"::equals), 0);
        if (holes > dirt) digDirt(server, bot, b, p, holes - dirt + 2);

        for (BlockPos c : p.soil()) {
            if (!SurvivalBrain.jobAlive(b)) return false;
            // anything above: grass, flowers, a boulder
            for (int dy = 3; dy >= 1; dy--) {
                BlockPos a = c.offset(0, dy, 0);
                if (onServer(server, () -> bot.level().getBlockState(a).isAir(), true)) continue;
                approach(server, bot, p, a);
                MiningSkills.dig(server, bot, b, a, false, 0);
            }
            String top = onServer(server, () -> SurvivalBrain.blockPath(bot.level().getBlockState(c)), "");
            if (tillable(top)) continue;
            approach(server, bot, p, c);
            onServer(server, () -> {
                ServerLevel level = bot.level();
                if (!Building.isFree(level, c)) return null;
                if (Building.isFree(level, c.below())) {
                    String blk = Building.firstItem(bot, LevelPathWorld::isThrowaway);
                    if (blk != null) Building.placeAt(bot, c.below(), blk);
                }
                return null;
            }, null);
            if (!onServer(server, () -> Building.isFree(bot.level(), c), true)) {
                // stone, sand...: dig it out, dirt goes in
                if (!MiningSkills.dig(server, bot, b, c, false, 0)) continue;
            }
            onServer(server, () -> Building.placeAt(bot, c, Building.firstItem(bot, "dirt"::equals)), false);
            SurvivalBrain.sleep(120);
        }
        SurvivalBrain.pickUpNearbyItems(server, bot, 7, false);
        int ok = onServer(server, () -> {
            int n = 0;
            for (BlockPos c : p.soil()) if (tillable(SurvivalBrain.blockPath(bot.level().getBlockState(c)))) n++;
            return n;
        }, 0);
        return ok >= 40;
    }

    /** Digs {@code n} dirt/grass from the ground around the plot (not in it). */
    private static void digDirt(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Plot p, int n) throws InterruptedException {
        int got = 0;
        for (int tries = 0; got < n && tries < n * 3 && SurvivalBrain.jobAlive(b); tries++) {
            BlockPos d = onServer(server, () -> {
                ServerLevel level = bot.level();
                BlockPos f = BotPathing.feet(bot);
                Protection.Context ctx = Protection.scan(bot, 16);
                BlockPos best = null;
                double bd = Double.MAX_VALUE;
                for (int dx = -14; dx <= 14; dx++) for (int dz = -14; dz <= 14; dz++) for (int dy = -3; dy <= 2; dy++) {
                    BlockPos c = f.offset(dx, dy, dz);
                    if (p.contains(c) && Math.abs(c.getY() - p.y()) <= 4) continue;
                    if (Math.abs(c.getX() - (p.x0() + MID)) <= MID + 1 && Math.abs(c.getZ() - (p.z0() + MID)) <= MID + 1) continue; // torch ring
                    String path = SurvivalBrain.blockPath(level.getBlockState(c));
                    if (!path.equals("dirt") && !path.equals("grass_block")) continue;
                    if (!level.getBlockState(c.above()).isAir() && !Building.isFree(level, c.above())) continue;
                    if (ctx.isProtected(level, c) || Protection.nearManMade(level, c, 2) || b.blacklist.containsKey(c)) continue;
                    double dd = c.distSqr(f);
                    if (dd < bd) { bd = dd; best = c; }
                }
                return best;
            }, null);
            if (d == null) return;
            if (MiningSkills.reach(server, bot, b, d, false) && MiningSkills.dig(server, bot, b, d, false, 0)) {
                got++;
                SurvivalBrain.pickUpNearbyItems(server, bot, 4, false);
            } else {
                b.blacklist.put(d, System.currentTimeMillis() + 5 * 60_000L);
            }
        }
    }

    /** Water in the middle (dug one down), with a slab on it. */
    private static boolean placeWater(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Plot p) throws InterruptedException {
        BlockPos w = p.water();
        // stand right next to the hole: from further away the aim clips the ground in front of it
        BlockPos stand = p.middle();
        BotPathing.Options wo = BotPathing.Options.walkOnly();
        wo.timeoutTicks = 20 * 20;
        BotPathing.goToBlocking(bot, ActionPathfinder.near(stand.getX(), stand.getY(), stand.getZ(), 0.4), wo, 22_000L);
        approach(server, bot, p, w);
        if (!onServer(server, () -> Building.isFree(bot.level(), w), true)) MiningSkills.dig(server, bot, b, w, false, 0);
        boolean placed = onServer(server, () -> {
            ServerLevel level = bot.level();
            if (Building.isFree(level, w.below())) {
                String blk = Building.firstItem(bot, LevelPathWorld::isThrowaway);
                if (blk == null || !Building.placeAt(bot, w.below(), blk)) return false;
            }
            if (!Smelting.holdItem(bot, "water_bucket")) return false;
            // aim at the bottom of the hole: the water goes in the hole
            Vec3 aim = new Vec3(w.getX() + 0.5, w.getY() + 0.02, w.getZ() + 0.5);
            Vec3 eye = bot.getEyePosition();
            try {
                var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, aim.add(aim.subtract(eye).scale(0.2)),
                        net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, bot));
                if (hit == null || !w.below().equals(hit.getBlockPos())) return false; // would land on the farm instead
            } catch (Throwable ignored) { }
            io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, aim);
            try {
                bot.gameMode.useItem(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND);
            } catch (Throwable ignored) { }
            Motions.swingArm(bot);
            return !level.getFluidState(w).isEmpty();
        }, false);
        if (!placed) return false;
        // a slab over it so nobody (bot included) steps in; crops still get the water
        if (onServer(server, () -> Building.firstItem(bot, x -> x.endsWith("_slab")) == null, true)) {
            Gathering.Craftable slab = Gathering.craftable("slabs");
            if (slab != null) Gathering.makeSure(server, bot, b, slab, 1);
        }
        onServer(server, () -> {
            String slab = Building.firstItem(bot, x -> x.endsWith("_slab"));
            if (slab != null) Building.placeAgainst(bot, w, slab, Direction.DOWN);
            return null;
        }, null);
        return true;
    }

    /** Right-clicks the top of every dirt/grass block with the hoe. Returns farmland count. */
    private static int till(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Plot p) throws InterruptedException {
        for (BlockPos c : p.soil()) {
            if (!SurvivalBrain.jobAlive(b)) break;
            if (!onServer(server, () -> needsTilling(bot.level(), c), false)) continue;
            approach(server, bot, p, c);
            onServer(server, () -> hoe(bot, c), false);
            SurvivalBrain.sleep(90);
        }
        return onServer(server, () -> {
            int n = 0;
            for (BlockPos c : p.soil()) if (SurvivalBrain.blockPath(bot.level().getBlockState(c)).equals("farmland")) n++;
            return n;
        }, 0);
    }

    private static boolean needsTilling(ServerLevel level, BlockPos c) {
        String t = SurvivalBrain.blockPath(level.getBlockState(c));
        return tillable(t) && !t.equals("farmland") && level.getBlockState(c.above()).isAir();
    }

    /** Hoe on the top face of {@code c}. Server thread. */
    private static boolean hoe(ServerPlayer bot, BlockPos c) {
        ServerLevel level = bot.level();
        if (!needsTilling(level, c)) return false;
        String hoe = Building.firstItem(bot, x -> x.endsWith("_hoe"));
        if (hoe == null || !Smelting.holdItem(bot, hoe)) return false;
        Vec3 hit = new Vec3(c.getX() + 0.5, c.getY() + 1.0, c.getZ() + 0.5);
        io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, hit);
        try {
            bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, c, false));
        } catch (Throwable ignored) { }
        Motions.swingArm(bot);
        return true;
    }

    /** Torches on the ground all round the plot: the corners and the middle of each side. */
    private static void torches(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Plot p) throws InterruptedException {
        int[] at = {-1, MID, SIZE};
        for (int ax : at) for (int az : at) {
            if (ax == MID && az == MID) continue;
            if (!SurvivalBrain.jobAlive(b)) return;
            BlockPos ground = new BlockPos(p.x0() + ax, p.y(), p.z0() + az);
            BlockPos spot = onServer(server, () -> {
                ServerLevel level = bot.level();
                for (int dy = 1; dy >= -1; dy--) {
                    BlockPos g = ground.offset(0, dy, 0);
                    if (Building.isSolid(level, g) && Building.isFree(level, g.above()) && level.getFluidState(g.above()).isEmpty()) return g.above();
                }
                return null;
            }, null);
            if (spot == null) continue;
            approach(server, bot, p, spot);
            onServer(server, () -> Lighting.place(bot, spot, Direction.DOWN), false);
            SurvivalBrain.sleep(120);
        }
    }

    /** Seeds into every empty farmland: carrots/potatoes first, then wheat, then beetroot. Returns how many planted. */
    private static int plant(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Plot p) throws InterruptedException {
        if (onServer(server, () -> bestSeed(bot), null) == null) {
            for (String[] s : SEEDS) {
                if (Storage.withdraw(server, bot, b, s[0]::equals, 40, null) > 0) break;
            }
        }
        int planted = 0;
        for (BlockPos c : p.soil()) {
            if (!SurvivalBrain.jobAlive(b) && !SurvivalBrain.canContinue(b)) break;
            if (onServer(server, () -> bestSeed(bot) == null, true)) break;
            if (onServer(server, () -> needsTilling(bot.level(), c), false)) {
                approach(server, bot, p, c);
                onServer(server, () -> hoe(bot, c), false);
            }
            boolean empty = onServer(server, () -> SurvivalBrain.blockPath(bot.level().getBlockState(c)).equals("farmland")
                    && bot.level().getBlockState(c.above()).isAir(), false);
            if (!empty) continue;
            approach(server, bot, p, c);
            if (onServer(server, () -> sow(bot, c), false)) planted++;
            SurvivalBrain.sleep(80);
        }
        return planted;
    }

    /** Plants the best seed on the farmland at {@code c}. Server thread. */
    private static boolean sow(ServerPlayer bot, BlockPos c) {
        String seed = bestSeed(bot);
        if (seed == null || !Smelting.holdItem(bot, seed)) return false;
        ServerLevel level = bot.level();
        Vec3 hit = new Vec3(c.getX() + 0.5, c.getY() + 0.94, c.getZ() + 0.5);
        io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, hit);
        try {
            bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, c, false));
        } catch (Throwable ignored) { }
        Motions.swingArm(bot);
        return isCrop(SurvivalBrain.blockPath(level.getBlockState(c.above())));
    }

    /** Seeds: pockets, chests, then breaking grass (wheat seeds drop from it now and then). */
    static void ensureSeeds(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, int want) throws InterruptedException {
        int have = onServer(server, () -> seedCount(bot), 0);
        for (String[] s : SEEDS) {
            if (have >= want) return;
            have += Storage.withdraw(server, bot, b, s[0]::equals, want - have, null);
        }
        if (have >= want) return;
        SurvivalBrain.maybeSay(server, b, HumanChat.pick("need seeds, gonna pull up some grass", "looking for seeds in the grass"), 0.7);
        for (int broken = 0; broken < 40 && have < want && SurvivalBrain.canContinue(b); broken++) {
            BlockPos g = onServer(server, () -> {
                ServerLevel level = bot.level();
                BlockPos f = BotPathing.feet(bot);
                BlockPos best = null;
                double bd = Double.MAX_VALUE;
                for (int dx = -20; dx <= 20; dx++) for (int dz = -20; dz <= 20; dz++) for (int dy = -4; dy <= 4; dy++) {
                    BlockPos c = f.offset(dx, dy, dz);
                    String path = SurvivalBrain.blockPath(level.getBlockState(c));
                    if (!path.equals("short_grass") && !path.equals("grass") && !path.equals("tall_grass") && !path.equals("fern")) continue;
                    if (b.blacklist.containsKey(c)) continue;
                    double d = c.distSqr(f);
                    if (d < bd) { bd = d; best = c; }
                }
                return best;
            }, null);
            if (g == null) break;
            if (!onServer(server, () -> bot.getEyePosition().distanceTo(Vec3.atCenterOf(g)) <= 4.3, false)) {
                SurvivalBrain.goTo(bot, g, 12);
            }
            if (!MiningSkills.dig(server, bot, b, g, false, 0)) b.blacklist.put(g, System.currentTimeMillis() + 5 * 60_000L);
            if (broken % 5 == 4) SurvivalBrain.pickUpNearbyItems(server, bot, 6, false);
            have = onServer(server, () -> seedCount(bot), have);
        }
        SurvivalBrain.pickUpNearbyItems(server, bot, 6, false);
    }

    // ------------------------------------------------------------------------
    // Tending
    // ------------------------------------------------------------------------

    /** Harvest what's ripe, pick it up, replant, bake bread, put the extra food away. */
    static void tend(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Plot p, boolean asked) throws InterruptedException {
        SurvivalBrain.keep(bot, FARM_STUFF);
        try {
            if (onServer(server, () -> Math.sqrt(bot.blockPosition().distSqr(p.middle())) > 12, true)) {
                Surface.backUp(server, bot, b, null);
                BotPathing.Options o = BotPathing.Options.full();
                o.timeoutTicks = 20 * 120;
                BlockPos mid = p.middle();
                BotPathing.goToBlocking(bot, ActionPathfinder.near(mid.getX(), mid.getY(), mid.getZ(), 1.5), o, 125_000L);
                if (onServer(server, () -> Math.sqrt(bot.blockPosition().distSqr(p.middle())) > 16, true)) {
                    if (asked) HumanChat.say(server, b.name, "can't get to the farm from here");
                    return;
                }
            }
            int harvested = 0;
            for (BlockPos c : p.soil()) {
                if (!SurvivalBrain.jobAlive(b) && !SurvivalBrain.canContinue(b)) break;
                BlockPos crop = c.above();
                if (!onServer(server, () -> ripe(bot.level().getBlockState(crop)), false)) continue;
                approach(server, bot, p, crop);
                if (MiningSkills.dig(server, bot, b, crop, false, 0)) harvested++;
                if (harvested % 8 == 7) SurvivalBrain.pickUpNearbyItems(server, bot, 6, false);
            }
            SurvivalBrain.pickUpNearbyItems(server, bot, 8, false);
            int planted = plant(server, bot, b, p);
            int bread = onServer(server, () -> bakeBread(bot), 0);
            if (asked || harvested > 0) {
                String line = harvested == 0 ? (planted > 0 ? "nothing ripe yet, planted " + planted + " more" : "nothing's ripe yet")
                        : "harvested " + harvested + (planted > 0 ? ", replanted " + planted : "") + (bread > 0 ? ", made " + bread + " bread" : "");
                if (asked) HumanChat.say(server, b.name, line);
                else SurvivalBrain.maybeSay(server, b, line, 0.4);
            }
            // keep a stack or so of food on hand, the rest goes in the chests
            if (HumanConfig.get().autoStore && onServer(server, () -> Home.get(bot) != null && foodCount(bot) > KEEP_FOOD + 16, false)) {
                Storage.storeAll(server, bot, b, false);
            }
        } finally {
            SurvivalBrain.keep(bot, null);
        }
    }

    /** 3 wheat -> 1 bread, when there's a crafting table to hand (carried or at home). Server thread. */
    static int bakeBread(ServerPlayer bot) {
        int wheat = Gathering.countOf(bot, "wheat"::equals);
        if (wheat < 3) return 0;
        boolean table = Building.firstItem(bot, "crafting_table"::equals) != null;
        Home.Base h = Home.get(bot);
        if (!table && (h == null || h.middle().distSqr(bot.blockPosition()) > 48 * 48)) return 0;
        int n = 0;
        while (Gathering.countOf(bot, "wheat"::equals) >= 3 && Gathering.roomFor(bot, "bread"::equals, 1) && n < 21) {
            SurvivalBrain.take(bot, "wheat"::equals, 3);
            SurvivalBrain.give(bot, "bread", 1);
            n++;
        }
        if (n > 0) Motions.swingArm(bot);
        return n;
    }

    static int foodCount(ServerPlayer bot) {
        var inv = bot.getInventory();
        int n = 0;
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            var s = inv.getItem(i);
            if (!s.isEmpty() && Storage.isFood(s)) n += s.getCount();
        }
        return n;
    }
}
