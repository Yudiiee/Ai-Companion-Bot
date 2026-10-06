package io.github.yudiiee.aicompanion.GameAI.human;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.onServer;

/**
 * The bot's mine, like a player's: one staircase down from a fixed spot (1 wide, 3 high, one
 * block down per step, a torch every 6 steps), a lit 3x3x3 landing at the bottom (y -58), and
 * at each ore's level a straight trunk off the staircase with branches every 3 blocks, 24
 * long, both sides, lit every 6 blocks. Caves it breaks into get lit, or sealed off when
 * there's water or lava in them. It walks the same stairs down and back up every trip, and
 * each trip carries on where the tunnel ended last time. Saved per bot, per world
 * ({@code <world>/ai-companion/mines.txt}).
 */
final class MineHub {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-mine");

    static final int BOTTOM_Y = -58;
    static final int TORCH_EVERY = 6;
    static final int BRANCH_EVERY = 3;
    static final int BRANCH_LEN = 24;
    static final int TRIP_LEN = 30;
    private static final long AWAIT_MS = 15 * 60_000L;
    private static final long TRIP_MS = 40 * 60_000L;

    private MineHub() {}

    // ------------------------------------------------------------------------
    // The mine and where it's saved
    // ------------------------------------------------------------------------

    static final class Mine {
        final String dim;
        final int dx, dz;
        /** Where the bot's feet go on each step, top (the entrance) first. */
        final List<BlockPos> stairs = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile BlockPos bottom;
        /**
         * y -> {dx, dz, length, step index it starts from, state} of the trunk tunnel at that
         * level. State: 0 open, 1 moved to the other side once, 2 blocked for good.
         */
        final Map<Integer, int[]> trunks = new ConcurrentHashMap<>();
        boolean temporary;

        Mine(String dim, int dx, int dz) { this.dim = dim; this.dx = dx; this.dz = dz; }

        BlockPos origin() { return stairs.get(0); }
        BlockPos last() { return stairs.get(stairs.size() - 1); }

        /** First step at or below {@code y}, or -1 if the stairs don't go that deep. */
        int indexAtOrBelow(int y) {
            for (int i = 0; i < stairs.size(); i++) if (stairs.get(i).getY() <= y) return i;
            return -1;
        }
    }

    private static final Map<String, Mine> MINES = new ConcurrentHashMap<>();
    private static volatile Path loadedFrom;

    private static String who(ServerPlayer bot) {
        return bot.getName().getString().toLowerCase(Locale.ROOT);
    }

    private static String key(ServerPlayer bot) {
        return who(bot) + "|" + Home.dim(bot.level());
    }

    private static Path file() {
        return Home.worldFile("mines.txt");
    }

    private static String pos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private static BlockPos parsePos(String s) {
        String[] p = s.split(",");
        return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }

    private static synchronized void load() {
        Path f = file();
        if (f.equals(loadedFrom)) return;
        MINES.clear();
        loadedFrom = f;
        List<String> lines;
        try {
            if (!Files.exists(f)) return;
            lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[mine] couldn't read {}: {}", f, e.toString());
            return;
        }
        for (String line : lines) {
            try { // one bad line doesn't lose the other bots' mines
                // bot dim dx dz bottom trunks stairs
                String[] p = line.trim().split(" ");
                if (p.length != 7) continue;
                Mine m = new Mine(p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                if (!p[4].equals("-")) m.bottom = parsePos(p[4]);
                for (String c : p[6].split(";")) m.stairs.add(parsePos(c));
                if (!p[5].equals("-")) {
                    for (String t : p[5].split(";")) {
                        String[] q = t.split(":");
                        int y = Integer.parseInt(q[0]);
                        int idx = q.length >= 6 ? Integer.parseInt(q[4]) : m.indexAtOrBelow(y);
                        int state = q.length >= 6 ? Integer.parseInt(q[5]) : 0;
                        if (idx < 0 || idx >= m.stairs.size()) continue;
                        m.trunks.put(y, new int[]{Integer.parseInt(q[1]), Integer.parseInt(q[2]), Integer.parseInt(q[3]), idx, state});
                    }
                }
                if (!m.stairs.isEmpty()) MINES.put(p[0] + "|" + p[1], m);
            } catch (Exception e) {
                LOGGER.warn("[mine] skipping a bad line in {}: {}", f, e.toString());
            }
        }
    }

    private static synchronized void save() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Mine> e : MINES.entrySet()) {
                Mine m = e.getValue();
                if (m.temporary || m.stairs.isEmpty()) continue;
                String bot = e.getKey().substring(0, e.getKey().indexOf('|'));
                StringBuilder trunks = new StringBuilder();
                for (Map.Entry<Integer, int[]> t : m.trunks.entrySet()) {
                    if (trunks.length() > 0) trunks.append(';');
                    int[] v = t.getValue();
                    trunks.append(t.getKey()).append(':').append(v[0]).append(':').append(v[1]).append(':').append(v[2])
                            .append(':').append(v[3]).append(':').append(v[4]);
                }
                StringBuilder stairs = new StringBuilder();
                for (BlockPos p : m.stairs) {
                    if (stairs.length() > 0) stairs.append(';');
                    stairs.append(pos(p));
                }
                sb.append(bot).append(' ').append(m.dim).append(' ').append(m.dx).append(' ').append(m.dz).append(' ')
                        .append(m.bottom == null ? "-" : pos(m.bottom)).append(' ')
                        .append(trunks.length() == 0 ? "-" : trunks).append(' ').append(stairs).append('\n');
            }
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[mine] couldn't save: {}", e.toString());
        }
    }

    /** This bot's mine in this dimension, if its entrance is within {@code radius} blocks. Server thread. */
    static Mine near(ServerPlayer bot, int radius) {
        load();
        Mine m = MINES.get(key(bot));
        if (m == null) return null;
        BlockPos f = bot.blockPosition();
        List<BlockPos> spots = new ArrayList<>(List.of(m.origin(), m.last()));
        for (int[] t : m.trunks.values()) {
            if (t[3] < 0 || t[3] >= m.stairs.size()) continue;
            spots.add(m.stairs.get(t[3]).offset(t[0] * t[2], 0, t[1] * t[2]));
        }
        for (BlockPos o : spots) {
            if (Math.hypot(o.getX() - f.getX(), o.getZ() - f.getZ()) <= radius) return m;
        }
        return null;
    }

    /** A new mine starting where the bot stands, heading {@code (dx, dz)}. Replaces its old one. Server thread. */
    private static Mine create(ServerPlayer bot, int dx, int dz, boolean temporary) {
        load();
        Mine m = new Mine(Home.dim(bot.level()), dx, dz);
        m.temporary = temporary;
        m.stairs.add(BotPathing.feet(bot));
        if (!temporary) {
            MINES.put(key(bot), m);
            save();
        }
        return m;
    }

    private static int[] heading(ServerPlayer bot, Float yawHint) {
        double yaw = Math.toRadians(yawHint != null ? yawHint : bot.getYRot());
        double x = -Math.sin(yaw), z = Math.cos(yaw);
        return Math.abs(x) > Math.abs(z) ? new int[]{x > 0 ? 1 : -1, 0} : new int[]{0, z > 0 ? 1 : -1};
    }

    private static int[] left(int[] d) { return new int[]{d[1], -d[0]}; }

    private static int[] right(int[] d) { return new int[]{-d[1], d[0]}; }

    // ------------------------------------------------------------------------
    // Chat: "dig down"
    // ------------------------------------------------------------------------

    private static final Pattern DIG_DOWN = Pattern.compile("\\b(dig (straight |all the way )?down|dig (a |us a |me a )?(staircase|stair ?case|stairs|mine ?shaft|mine)"
            + "|(make|build|start|open) (a |us a |me a |the |our )?(mine|mine ?shaft|staircase|stair ?case)|dig to bedrock"
            + "|(dig|mine) (down )?to (y ?-?\\d+|bedrock)|(go|head) (down )?to y ?-\\d+)\\b");
    private static final Pattern Y = Pattern.compile("\\b(?:y|lvl|level|layer)\\s*[=:]?\\s*(-?\\d+)\\b|\\bto (-\\d+)\\b");

    /** "dig down", "dig a staircase", "make a mine", "dig to bedrock" (no ore named: that's a mining job). */
    static MiningSkills.Request parseDigDown(String text, Float yawHint) {
        String m = text == null ? "" : text.toLowerCase(Locale.ROOT).replace(',', ' ').replaceAll("\\s+", " ").trim();
        if (m.isEmpty() || !DIG_DOWN.matcher(m).find()) return null;
        if (m.matches(".*\\b(strip|branch|coal|iron|copper|gold|lapis|redstone|diamonds?|emeralds?|debris|netherite|ores?)\\b.*")) return null;
        int y = BOTTOM_Y;
        Matcher ym = Y.matcher(m);
        if (ym.find()) y = Integer.parseInt(ym.group(1) != null ? ym.group(1) : ym.group(2));
        y = Math.max(MiningSkills.LOWEST_Y + 1, Math.min(300, y));
        final int fy = y;
        String ack = HumanChat.pick("ok, digging a staircase down to y " + y, "sure, making us a mine down to " + y,
                "alright, stairs down to " + y + " coming up. this takes a bit");
        return new MiningSkills.Request("dig down to y " + y, ack, (server, bot, b) -> digDown(server, bot, b, fy, yawHint));
    }

    // ------------------------------------------------------------------------
    // Jobs
    // ------------------------------------------------------------------------

    /** Progress of one trip down the mine. */
    private static final class Trip {
        final Map<String, Integer> found = new LinkedHashMap<>();
        final int[] got = {0};
        final MiningSkills.Target want;
        final int wantCount;
        final int needTier;
        final long end = System.currentTimeMillis() + TRIP_MS;
        boolean saidNoTorches;
        Trip(MiningSkills.Target want, int wantCount) {
            this.want = want;
            this.wantCount = wantCount;
            this.needTier = Math.max(1, want == null ? 1 : want.tier());
        }
    }

    /**
     * "dig down": a staircase from where the bot stands (or its existing mine right here) down
     * to {@code y}, a lit landing at the bottom, then it waits there for what to mine.
     */
    static void digDown(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, int y, Float yawHint)
            throws InterruptedException {
        if (!MiningSkills.ensurePickaxe(server, bot, b, 1)) return;
        Lighting.ensureTorches(server, bot, b, 48, 12, true);
        MiningSkills.emptyPocketsFirst(server, bot, b);
        if (!SurvivalBrain.canContinue(b)) return;
        stepOutOfHouse(server, bot);
        Trip trip = new Trip(null, 0);
        Mine m = onServer(server, () -> {
            Mine ex = near(bot, 8);
            if (ex != null) return ex;
            int[] d = heading(bot, yawHint);
            return create(bot, d[0], d[1], false);
        }, null);
        if (m == null) return;
        String why = goDown(server, bot, b, m, y, trip);
        if (why != null || !SurvivalBrain.canContinue(b)) {
            if (why != null) MiningSkills.finishStrip(server, b, trip.found, why);
            onServer(server, () -> { save(); return null; }, null);
            if (SurvivalBrain.canContinue(b)) climbOut(server, bot, b, m);
            return;
        }
        boolean atBottom = onServer(server, () -> BotPathing.feet(bot).equals(m.last()), false);
        if (atBottom) buildLanding(server, bot, b, m);
        onServer(server, () -> { save(); return null; }, null);
        StringBuilder sb = new StringBuilder(HumanChat.pick("made it to y " + y + ".", "ok, the stairs are done, we're at y " + y + "."));
        if (!trip.found.isEmpty()) {
            sb.append(" found ");
            int i = 0;
            for (Map.Entry<String, Integer> e : trip.found.entrySet()) {
                if (i++ > 0) sb.append(", ");
                sb.append(e.getValue()).append(' ').append(e.getKey());
            }
            sb.append(" on the way.");
        }
        sb.append(' ').append(HumanChat.pick("what should i mine? diamonds, redstone, iron...", "what do you want me to mine down here?"));
        HumanChat.say(server, b.name, sb.toString());

        // AWAITING_ORE_COMMAND: stay on the landing until told what to mine (a new job ends this one)
        long until = System.currentTimeMillis() + AWAIT_MS;
        while (SurvivalBrain.jobAlive(b) && System.currentTimeMillis() < until) {
            if (!MiningSkills.upkeep(server, bot, b)) return;
            SurvivalBrain.sleep(2000);
        }
        if (!SurvivalBrain.jobAlive(b)) return;
        HumanChat.say(server, b.name, HumanChat.pick("nobody needs me down here, heading back up", "ok going back up, call me if you need ores"));
        climbOut(server, bot, b, m);
        Storage.afterMining(server, bot, b);
    }

    /**
     * Mine {@code want} (or anything, at {@code yOverride}) the deterministic way: down the
     * mine's stairs to that ore's level, then along the trunk and its branches.
     */
    static void mineOre(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, MiningSkills.Target want, int wantCount,
                        Integer yOverride, int length, Float yawHint) throws InterruptedException {
        Plan plan = onServer(server, () -> levelFor(bot, want, yOverride), null);
        if (plan == null) return;
        if (plan.error() != null) {
            HumanChat.say(server, b.name, plan.error());
            return;
        }
        int y = plan.y();
        Trip trip = new Trip(want, wantCount);
        if (!MiningSkills.ensurePickaxe(server, bot, b, trip.needTier)) return;
        Lighting.ensureTorches(server, bot, b, 64, 16, true);
        MiningSkills.emptyPocketsFirst(server, bot, b);
        if (!SurvivalBrain.canContinue(b)) return;
        stepOutOfHouse(server, bot);

        Mine here = onServer(server, () -> near(bot, 160), null);
        if (here != null && y >= here.origin().getY()
                && onServer(server, () -> BotPathing.feet(bot).getY() < here.origin().getY() - 3, false)) {
            climbOut(server, bot, b, here);
            if (!SurvivalBrain.canContinue(b)) return;
        }
        int feetY = onServer(server, () -> BotPathing.feet(bot).getY(), y);
        Mine m = onServer(server, () -> {
            // the bot's mine, when this level is somewhere below its entrance (from wherever in the mine it is)
            Mine ex = near(bot, 160);
            if (ex != null && plan.y() < ex.origin().getY()) return ex;
            int[] d = heading(bot, yawHint);
            // no mine around here yet: this is where it starts
            if (ex == null && plan.y() < feetY) return create(bot, d[0], d[1], false);
            // the level is above the mine / above us (a mountain, "at y 100"): a one-off staircase, the mine is kept
            return create(bot, d[0], d[1], true);
        }, null);
        if (m == null) return;
        if (!m.temporary) {
            // an existing tunnel a few blocks off this level: carry on with that one (tunnels 1-2 apart dig into each other)
            if (!m.trunks.containsKey(y)) {
                int best = y, bd = 5;
                for (Map.Entry<Integer, int[]> e : m.trunks.entrySet()) {
                    int d = Math.abs(e.getKey() - y);
                    if (d < bd && e.getValue()[4] < 2) { bd = d; best = e.getKey(); }
                }
                y = best;
            }
        }
        final int level = y;
        String what = want == null ? "at y " + level : want.label() + " at y " + level;
        SurvivalBrain.maybeSay(server, b, HumanChat.pick("heading down the mine for " + what, "going down to y " + level), 0.6);

        String why = goDown(server, bot, b, m, level, trip);
        if (why == null && SurvivalBrain.canContinue(b)) {
            int idx = m.temporary ? m.stairs.size() - 1 : m.indexAtOrBelow(level);
            if (idx < 0) idx = m.stairs.size() - 1;
            MiningSkills.maybeSay(server, b, HumanChat.pick("ok, at y " + level + ". tunnelling out", "made it down, branching out now"));
            why = tunnel(server, bot, b, m, idx, Math.max(4, length), trip);
        }
        onServer(server, () -> { save(); return null; }, null);
        if (!SurvivalBrain.canContinue(b) && why == null) return; // told to stop: the chat already answered
        MiningSkills.finishStrip(server, b, trip.found, why);
        if (SurvivalBrain.canContinue(b)) climbOut(server, bot, b, m);
    }

    // ------------------------------------------------------------------------
    // Which level for which ore
    // ------------------------------------------------------------------------

    record Plan(int y, String error) {
        static Plan at(int y) { return new Plan(Math.max(MiningSkills.LOWEST_Y + 1, Math.min(300, y)), null); }
        static Plan no(String why) { return new Plan(0, why); }
    }

    /** Where to strip mine for {@code want}, or why not here. Server thread. */
    static Plan levelFor(ServerPlayer bot, MiningSkills.Target want, Integer yOverride) {
        ServerLevel level = bot.level();
        String dim = Home.dim(level);
        String label = want == null ? "" : want.label();
        int feet = BotPathing.feet(bot).getY();
        if (dim.contains("the_end")) return Plan.no("not strip mining in the end, there's nothing down there");
        boolean nether = dim.contains("nether");
        if (nether && (want == null || want.stripY() == null) && yOverride == null) return Plan.at(15);
        if (label.equals("ancient debris")) {
            if (!nether) return Plan.no("ancient debris is only in the nether, take me there first");
            return Plan.at(yOverride != null ? yOverride : 15);
        }
        if (nether && want != null && !label.equals("quartz") && want.stripY() != null)
            return Plan.no("there's no " + label + " in the nether");
        if (yOverride != null) return Plan.at(yOverride);
        if (label.equals("emerald")) {
            if (!mountains(level, BotPathing.feet(bot)))
                return Plan.no("emeralds only spawn in mountain biomes (peaks, slopes, windswept hills). take me to some mountains");
            return Plan.at(Math.max(-16, Math.min(232, Math.floorDiv(feet - 6, 8) * 8)));
        }
        if (label.equals("coal")) return Plan.at(feet >= 102 ? 96 : Math.max(0, Math.floorDiv(feet - 6, 8) * 8)); // high up, into the hills
        if (want == null || want.stripY() == null) return Plan.at(BOTTOM_Y);
        return Plan.at(want.stripY());
    }

    private static final Pattern BIOME = Pattern.compile("worldgen/biome / [a-z0-9_.-]+:([a-z0-9_/.-]+)");

    /** Mountain biomes (where emeralds are). Unknown biome name: let it try. Server thread. */
    static boolean mountains(ServerLevel level, BlockPos p) {
        try {
            Matcher bm = BIOME.matcher(String.valueOf(level.getBiome(p)));
            if (!bm.find()) return true;
            return isMountainBiome(bm.group(1));
        } catch (Throwable t) {
            return true;
        }
    }

    static boolean isMountainBiome(String id) {
        return id.contains("windswept") || id.contains("peaks") || id.contains("slopes") || id.equals("meadow")
                || id.contains("grove") || id.contains("mountain");
    }

    // ------------------------------------------------------------------------
    // Stairs
    // ------------------------------------------------------------------------

    /** Standing in its own house: walk out the door first (not dig down through the floor). */
    private static void stepOutOfHouse(MinecraftServer server, ServerPlayer bot) throws InterruptedException {
        BlockPos out = onServer(server, () -> {
            Home.Base h = Home.get(bot);
            if (h == null || !h.inside(BotPathing.feet(bot))) return null;
            return h.site().at(h.site().size() / 2, 1, -4);
        }, null);
        if (out == null) return;
        BotPathing.Options o = BotPathing.Options.walkOnly();
        o.timeoutTicks = 20 * 30;
        BotPathing.goToBlocking(bot, ActionPathfinder.near(out.getX(), out.getY(), out.getZ(), 1.5), o, 32_000L);
    }

    /** Index of the step the bot is standing on (or right next to), or -1. Server thread. */
    private static int stairUnder(ServerPlayer bot, Mine m) {
        BlockPos f = BotPathing.feet(bot);
        int best = -1;
        double bd = 2.3;
        for (int i = 0; i < m.stairs.size(); i++) {
            BlockPos s = m.stairs.get(i);
            if (Math.abs(s.getY() - f.getY()) > 1) continue;
            double d = Math.hypot(s.getX() - f.getX(), s.getZ() - f.getZ()) + Math.abs(s.getY() - f.getY()) * 0.5;
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** Walks cell to cell with the movement keys (the stairs, a tunnel). Job thread. */
    static boolean walkCells(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, List<BlockPos> cells)
            throws InterruptedException {
        for (BlockPos c : cells) {
            if (!SurvivalBrain.canContinue(b)) return false;
            if (MiningSkills.at(server, bot, c)) continue;
            // fallen gravel/sand, or a block it put there itself: dig it (tunnel rules: never fluids or builds)
            for (BlockPos in : new BlockPos[]{c, c.above()}) {
                if (onServer(server, () -> Building.isSolid(bot.level(), in), false)) { // (torches don't get in the way)
                    MiningSkills.dig(server, bot, b, in, true, 0);
                }
            }
            int fy = onServer(server, () -> BotPathing.feet(bot).getY(), c.getY());
            if (MiningSkills.walkInto(server, bot, c, c.getY() > fy)) continue;
            if (!MiningSkills.returnTo(server, bot, c)) return false;
        }
        return true;
    }

    /** Onto the mine's stairs and down (or up) them to {@code y}, digging more steps if they don't go that far. */
    private static String goDown(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Mine m, int y, Trip trip)
            throws InterruptedException {
        int start = onServer(server, () -> stairUnder(bot, m), -1);
        if (start < 0) {
            BlockPos o = m.origin();
            SurvivalBrain.maybeSay(server, b, HumanChat.pick("going over to the mine", "walking to the mine entrance"), 0.5);
            BotPathing.Options opt = BotPathing.Options.full();
            opt.timeoutTicks = 20 * 180;
            BotPathing.goToBlocking(bot, ActionPathfinder.near(o.getX(), o.getY(), o.getZ(), 0.5), opt, 185_000L);
            start = onServer(server, () -> stairUnder(bot, m), -1);
            if (start < 0) return HumanChat.pick("can't get to the mine entrance", "couldn't get back to my mine");
        }
        // going up (a level above where the stairs start): to the top step, then dig on up
        int target = y > m.origin().getY() ? -1 : m.indexAtOrBelow(y);
        int to = target >= 0 ? target : m.stairs.size() - 1;
        List<BlockPos> path = new ArrayList<>();
        if (to >= start) for (int i = start; i <= to; i++) path.add(m.stairs.get(i));
        else for (int i = start; i >= to; i--) path.add(m.stairs.get(i));
        if (!walkCells(server, bot, b, path)) {
            return SurvivalBrain.canContinue(b) ? HumanChat.pick("the stairs are blocked, hm", "couldn't get down my stairs") : null;
        }
        if (target >= 0 && m.stairs.get(target).getY() == y) return null;
        if (target >= 0 && m.stairs.get(target).getY() < y) {
            // the steps skip this level (a sidestep made two at once): close enough
            return null;
        }
        return extendStairs(server, bot, b, m, y, trip);
    }

    /** Digs more steps from the bottom of the stairs until the feet are at {@code y}. */
    private static String extendStairs(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Mine m, int y, Trip trip)
            throws InterruptedException {
        int[] dir = {m.dx, m.dz};
        int[] sidestep = left(dir);
        int blocked = 0;
        while (SurvivalBrain.canContinue(b)) {
            int feetY = onServer(server, () -> BotPathing.feet(bot).getY(), y);
            if (feetY == y) return null;
            String why = MiningSkills.stopReason(server, bot, b, trip.needTier, trip.want, trip.wantCount, trip.got[0], trip.end);
            if (why != null) return why;
            int v = feetY > y ? -1 : 1;
            BlockPos from = onServer(server, () -> BotPathing.feet(bot), null);
            if (from == null) return null;
            if (MiningSkills.step(server, bot, b, dir[0], dir[1], v)) {
                afterStep(server, bot, b, m, from, dir, trip, true);
                blocked = 0;
                continue;
            }
            if (!SurvivalBrain.canContinue(b)) return null;
            if (++blocked > 8) return HumanChat.pick("can't dig down here, there's water or lava everywhere",
                    "hit lava or water on the way down, not risking it");
            // water, lava or a cave in the way: a couple of blocks to the side and carry on
            int moved = 0;
            for (int k = 0; k < 2; k++) {
                BlockPos f2 = onServer(server, () -> BotPathing.feet(bot), null);
                if (f2 == null || !MiningSkills.step(server, bot, b, sidestep[0], sidestep[1], 0)) break;
                afterStep(server, bot, b, m, f2, sidestep, trip, true);
                moved++;
            }
            if (moved == 0) {
                sidestep = new int[]{-sidestep[0], -sidestep[1]};
                for (int k = 0; k < 2; k++) {
                    BlockPos f2 = onServer(server, () -> BotPathing.feet(bot), null);
                    if (f2 == null || !MiningSkills.step(server, bot, b, sidestep[0], sidestep[1], 0)) break;
                    afterStep(server, bot, b, m, f2, sidestep, trip, true);
                    moved++;
                }
            }
            if (moved == 0) return HumanChat.pick("boxed in by water and lava down here, not risking it",
                    "can't find a safe way down from here");
        }
        return null;
    }

    /** At the bottom: a 3x3, 3 high room around the last step, floored, with a torch. */
    private static void buildLanding(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Mine m) throws InterruptedException {
        // centred one block past the last step, so the step before it keeps its floor
        BlockPos c = m.last().offset(m.dx, 0, m.dz);
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!SurvivalBrain.canContinue(b)) return;
                    BlockPos p = c.offset(dx, dy, dz);
                    if (onServer(server, () -> Building.isFree(bot.level(), p), true)) continue;
                    MiningSkills.dig(server, bot, b, p, true, 0); // (refuses anything next to water or lava)
                }
            }
        }
        SurvivalBrain.pickUpNearbyItems(server, bot, 3, false);
        int[] dir = {m.dx, m.dz};
        int[] l = left(dir);
        onServer(server, () -> {
            ServerLevel level = bot.level();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos fl = c.offset(dx, -1, dz);
                if (Building.isFree(level, fl) && level.getFluidState(fl).isEmpty()) {
                    String blk = Building.firstItem(bot, LevelPathWorld::isThrowaway);
                    if (blk != null && Building.placeAt(bot, fl, blk)) MiningSkills.ownBlock(fl);
                }
            }
            // the safety torch: in the far corner, out of the way
            BlockPos corner = c.offset(dir[0] + l[0], 0, dir[1] + l[1]);
            if (!Lighting.place(bot, corner, Direction.DOWN)) Lighting.tunnelTorch(bot, m.last(), Building.dirOf(l[0], l[1]));
            m.bottom = c;
            return null;
        }, null);
    }

    // ------------------------------------------------------------------------
    // Trunk and branches
    // ------------------------------------------------------------------------

    /** The trunk at the level of step {@code idx}: carries on from where it ended, branching every 3. */
    private static String tunnel(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Mine m, int idx, int length, Trip trip)
            throws InterruptedException {
        int y = m.stairs.get(idx).getY();
        int[] tr = m.trunks.get(y);
        if (tr != null && tr[4] >= 2) return HumanChat.pick("that level's blocked off by water and lava here, try another ore",
                "can't tunnel at this level from my mine, there's lava/water both ways");
        if (tr != null) idx = tr[3];
        BlockPos s0 = m.stairs.get(idx);
        int[] dir = {m.dx, m.dz};
        // always off to the right of the stairs: the stairs can go on down past it, and the
        // stair torches (left wall) and sidesteps (left first) stay out of its way
        int[] t = tr != null ? new int[]{tr[0], tr[1]} : right(dir);
        int len0 = tr != null ? tr[2] : 0;
        int state = tr != null ? tr[4] : 0;
        final int sIdx = idx;
        if (tr == null) m.trunks.put(y, new int[]{t[0], t[1], 0, sIdx, 0});

        // back to where the tunnel ended last time
        List<BlockPos> old = new ArrayList<>();
        for (int k = 1; k <= len0; k++) old.add(s0.offset(t[0] * k, 0, t[1] * k));
        if (!old.isEmpty()) {
            SurvivalBrain.maybeSay(server, b, HumanChat.pick("carrying on where i left off", "back to the end of my tunnel"), 0.5);
            if (!walkCells(server, bot, b, old)) {
                if (!SurvivalBrain.canContinue(b)) return null;
                giveUpTrunk(m, y, t, sIdx, state);
                return HumanChat.pick("my old tunnel is blocked, i'll start a new one next time", "couldn't get to the end of the tunnel");
            }
        }
        int[] l = left(t), r = right(t);
        Direction[] trunkWalls = {Building.dirOf(l[0], l[1]), Building.dirOf(r[0], r[1])};
        for (int k = len0 + 1; k <= len0 + length && SurvivalBrain.canContinue(b); k++) {
            String why = MiningSkills.stopReason(server, bot, b, trip.needTier, trip.want, trip.wantCount, trip.got[0], trip.end);
            if (why != null) return why;
            BlockPos prev = s0.offset(t[0] * (k - 1), 0, t[1] * (k - 1));
            if (!MiningSkills.returnTo(server, bot, prev)) return HumanChat.pick("got a bit lost down here, stopping", "lost my tunnel lol, stopping here");
            if (!MiningSkills.step(server, bot, b, t[0], t[1], 0)) {
                if (!SurvivalBrain.canContinue(b)) return null;
                giveUpTrunk(m, y, t, sIdx, state);
                return HumanChat.pick("tunnel ran into water or lava, i'll start a fresh one next time", "hit lava or water up ahead, that's it for this tunnel");
            }
            afterStep(server, bot, b, m, prev, t, trip, false);
            BlockPos here = s0.offset(t[0] * k, 0, t[1] * k);
            final int kk = k;
            m.trunks.put(y, new int[]{t[0], t[1], k, sIdx, state});
            if (k % TORCH_EVERY == 1) torch(server, bot, b, here, trip, trunkWalls);
            if (k % 5 == 0) onServer(server, () -> { save(); return null; }, null);
            if (kk % BRANCH_EVERY != 0) continue;
            for (int[] side : new int[][]{l, r}) {
                why = branch(server, bot, b, m, here, side, t, trip);
                if (why != null) return why;
                if (!SurvivalBrain.canContinue(b)) return null;
            }
        }
        return null;
    }

    /** This trunk can't go on: next time start one on the other side of the stairs; if that fails too, give up on the level. */
    private static void giveUpTrunk(Mine m, int y, int[] t, int idx, int state) {
        if (state == 0) m.trunks.put(y, new int[]{-t[0], -t[1], 0, idx, 1});
        else m.trunks.put(y, new int[]{t[0], t[1], 0, idx, 2});
    }

    /** One side branch from {@code junction}: 24 blocks out, torches every 6, then back. */
    private static String branch(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Mine m, BlockPos junction,
                                 int[] side, int[] trunk, Trip trip) throws InterruptedException {
        List<BlockPos> path = new ArrayList<>();
        path.add(junction);
        Direction[] walls = {Building.dirOf(trunk[0], trunk[1]), Building.dirOf(-trunk[0], -trunk[1])};
        String why = null;
        for (int j = 1; j <= BRANCH_LEN && SurvivalBrain.canContinue(b); j++) {
            why = MiningSkills.stopReason(server, bot, b, trip.needTier, trip.want, trip.wantCount, trip.got[0], trip.end);
            if (why != null) break;
            BlockPos prev = path.get(path.size() - 1);
            if (!MiningSkills.returnTo(server, bot, prev)) break;
            if (!MiningSkills.step(server, bot, b, side[0], side[1], 0)) break; // water/lava/bedrock ahead: that's the end of this one
            afterStep(server, bot, b, m, prev, side, trip, false);
            BlockPos at = onServer(server, () -> BotPathing.feet(bot), null);
            if (at == null) break;
            path.add(at);
            if (j % TORCH_EVERY == 0) torch(server, bot, b, at, trip, walls);
        }
        // back out to the trunk the way it came
        for (int k = path.size() - 2; k >= 0 && SurvivalBrain.canContinue(b); k--) {
            if (!MiningSkills.returnTo(server, bot, path.get(k))) break;
        }
        return why;
    }

    private static void torch(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, BlockPos feet, Trip trip, Direction... walls) {
        boolean ok = onServer(server, () -> Lighting.tunnelTorch(bot, feet, walls), false);
        if (!ok && !trip.saidNoTorches && onServer(server, () -> Lighting.torches(bot), 0) == 0) {
            trip.saidNoTorches = true;
            HumanChat.say(server, b.name, HumanChat.pick("out of torches, carrying on in the dark", "no torches left, gotta be careful"));
        }
    }

    /** After every step: stairs get a torch every 6, caves get lit or sealed, ores in the walls get mined. */
    private static void afterStep(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Mine m, BlockPos from, int[] dir,
                                  Trip trip, boolean stairs) throws InterruptedException {
        BlockPos at = onServer(server, () -> BotPathing.feet(bot), null);
        if (at == null) return;
        if (stairs) {
            m.stairs.add(at);
            int[] l = left(new int[]{m.dx, m.dz});
            if ((m.stairs.size() - 1) % TORCH_EVERY == 0) torch(server, bot, b, at, trip, Building.dirOf(l[0], l[1]));
            if (m.stairs.size() % 10 == 0 && !m.temporary) onServer(server, () -> { save(); return null; }, null);
        }
        breach(server, bot, b, from, at, dir, stairs);
        MiningSkills.mineOresInReach(server, bot, b, trip.found, trip.want, trip.got);
    }

    // ------------------------------------------------------------------------
    // Breaking into caves
    // ------------------------------------------------------------------------

    /** What the tunnel just opened up into. */
    record Opening(List<BlockPos> holes, int size, boolean fluid) {}

    /**
     * Open space around the cells just dug ({@code at}, 2 or 3 high), other than the tunnel
     * itself: how big it is and whether there's water or lava in it. Server thread.
     */
    static Opening look(ServerLevel level, BlockPos from, BlockPos at, int height) {
        Set<BlockPos> ours = new HashSet<>();
        for (int dy = 0; dy <= 2; dy++) { ours.add(from.offset(0, dy, 0)); ours.add(at.offset(0, dy, 0)); }
        ours.add(at.below());
        List<BlockPos> holes = new ArrayList<>();
        boolean fluid = false;
        for (int dy = 0; dy < height; dy++) {
            BlockPos c = at.offset(0, dy, 0);
            for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
                if (d == Direction.UP && dy < height - 1) continue;
                BlockPos n = c.relative(d);
                if (ours.contains(n)) continue;
                if (!level.getFluidState(n).isEmpty()) { fluid = true; holes.add(n); continue; }
                if (Building.isFree(level, n) && !Lighting.isTorchId(SurvivalBrain.blockPath(level.getBlockState(n)))) holes.add(n);
            }
        }
        if (holes.isEmpty()) return new Opening(holes, 0, false);
        // how much space is behind the holes (a real cave, or a one-block pocket)?
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(holes);
        Set<BlockPos> seen = new HashSet<>(holes);
        int size = 0;
        while (!queue.isEmpty() && size < 60) {
            BlockPos p = queue.poll();
            size++;
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (ours.contains(n) || seen.contains(n)) continue;
                if (Math.abs(n.getX() - at.getX()) > 5 || Math.abs(n.getY() - at.getY()) > 5 || Math.abs(n.getZ() - at.getZ()) > 5) continue;
                if (!level.getFluidState(n).isEmpty()) { fluid = true; continue; }
                if (!Building.isFree(level, n)) continue;
                seen.add(n);
                queue.add(n);
            }
        }
        return new Opening(holes, size, fluid);
    }

    /**
     * Broke into a cave: stop, light the opening so nothing spawns there, then carry on; if
     * there's water or lava in it, block the holes up instead (cobblestone or whatever's to hand).
     */
    private static void breach(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, BlockPos from, BlockPos at, int[] dir,
                               boolean stairs) {
        String line = onServer(server, () -> {
            ServerLevel level = bot.level();
            Opening o = look(level, from, at, stairs ? 3 : 2);
            if (o.holes().isEmpty() || (o.size() < 6 && !o.fluid())) return null;
            if (o.fluid()) {
                int sealed = 0;
                for (BlockPos h : o.holes()) {
                    String blk = Building.firstItem(bot, LevelPathWorld::isThrowaway);
                    if (blk == null) break;
                    // (the bot's own seals can be dug again: the tunnel carries on through them)
                    if (Building.placeAt(bot, h, blk)) { sealed++; MiningSkills.ownBlock(h); }
                }
                return sealed > 0 ? HumanChat.pick("broke into a cave with water/lava in it, sealed it off", "blocked off a wet cave, not going in there") : null;
            }
            int lit = Lighting.lightOpening(bot, at, dir);
            return lit > 0 ? HumanChat.pick("broke into a cave, lighting it up", "cave here, putting a torch down") : null;
        }, null);
        if (line != null) MiningSkills.maybeSay(server, b, line);
    }

    // ------------------------------------------------------------------------
    // Back up
    // ------------------------------------------------------------------------

    /**
     * Back to the surface the way it came: along the trunk to the stairs, then up the stairs.
     * Falls back to digging its own way up. Job thread.
     */
    static boolean climbOut(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Mine m) throws InterruptedException {
        // in a trunk? walk back along it to the stairs first
        int[] where = onServer(server, () -> {
            BlockPos f = BotPathing.feet(bot);
            for (Map.Entry<Integer, int[]> e : m.trunks.entrySet()) {
                int y = e.getKey();
                if (Math.abs(f.getY() - y) > 1) continue;
                int[] t = e.getValue();
                int idx = t[3];
                if (idx < 0 || idx >= m.stairs.size()) continue;
                BlockPos s0 = m.stairs.get(idx);
                for (int k = t[2]; k >= 1; k--) {
                    BlockPos c = s0.offset(t[0] * k, 0, t[1] * k);
                    if (Math.hypot(c.getX() - f.getX(), c.getZ() - f.getZ()) < 1.6) return new int[]{idx, k, t[0], t[1]};
                }
            }
            return null;
        }, null);
        if (where != null) {
            BlockPos s0 = m.stairs.get(where[0]);
            List<BlockPos> back = new ArrayList<>();
            for (int k = where[1] - 1; k >= 0; k--) back.add(s0.offset(where[2] * k, 0, where[3] * k));
            walkCells(server, bot, b, back);
        }
        int idx = onServer(server, () -> stairUnder(bot, m), -1);
        if (idx >= 0) {
            List<BlockPos> up = new ArrayList<>();
            for (int i = idx; i >= 0; i--) up.add(m.stairs.get(i));
            if (walkCells(server, bot, b, up)) return true;
        }
        return Surface.backUp(server, bot, b, null);
    }

    /** Tunnel cells of the mine near {@code p}, for tests and diagnostics. */
    static int stairsCount(ServerPlayer bot) {
        load();
        Mine m = MINES.get(key(bot));
        return m == null ? 0 : m.stairs.size();
    }

    static Vec3 entrance(ServerPlayer bot) {
        load();
        Mine m = MINES.get(key(bot));
        return m == null ? null : Vec3.atBottomCenterOf(m.origin());
    }
}
