package io.github.yudiiee.aicompanion.GameAI.human;

import io.github.yudiiee.aicompanion.GameAI.human.Schematic.State;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.onServer;

/**
 * The companions' town: they agree on a spot, lay it out (see {@link CityPlan}) and build it
 * together a piece at a time: the plaza, the roads with their street lamps, a warehouse they
 * all share, a shop each, community farms, a temple, a mall and an amphitheatre, then town
 * houses. Each takes the next piece nobody's on, they ask each other for what they're short of
 * (and fetch it for each other), keep the town's farms harvested, and once a day each one walks
 * up to the temple and leaves some food on the altar. Remembered in
 * {@code <world>/ai-companion/city.txt}.
 */
public final class City {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-city");
    private static final Random RNG = new Random();

    /** Who the town's builds are recorded under (not any one bot: nobody "moves in" to the temple). */
    static final String TOWN_BUILDER = "town";
    static final int NO_Y = Integer.MIN_VALUE;

    private City() {}

    // ------------------------------------------------------------------------
    // The town
    // ------------------------------------------------------------------------

    /** A piece of the town (world coordinates). {@code state}: todo, done or blocked. */
    static final class Plot {
        final int id;
        final String kind, file;
        final int x0, z0, w, l;
        final String face;
        final int rot, arm;
        volatile int y;
        volatile String owner;
        volatile String state;

        Plot(int id, String kind, String file, int x0, int z0, int w, int l, String face, int rot, int arm, int y, String owner,
             String state) {
            this.id = id;
            this.kind = kind;
            this.file = file;
            this.x0 = x0;
            this.z0 = z0;
            this.w = w;
            this.l = l;
            this.face = face;
            this.rot = rot;
            this.arm = arm;
            this.y = y;
            this.owner = owner == null ? "" : owner;
            this.state = state;
        }

        boolean road() { return kind.equals("road"); }

        boolean done() { return state.equals("done"); }

        int midX() { return x0 + w / 2; }

        int midZ() { return z0 + l / 2; }

        boolean inside(int x, int z, int margin) {
            return x >= x0 - margin && x < x0 + w + margin && z >= z0 - margin && z < z0 + l + margin;
        }

        /** "the temple", "Bro's shop", "the east road". */
        String label() {
            return switch (kind) {
                case "road" -> "the " + face + " road";
                case "shop" -> owner.isEmpty() ? "a shop" : owner + "'s shop";
                case "farm" -> "a community farm";
                case "house" -> "a town house";
                case "amphitheatre" -> "the amphitheatre";
                default -> "the " + kind;
            };
        }

        String line() {
            return "plot|" + id + "|" + kind + "|" + file + "|" + x0 + "|" + z0 + "|" + w + "|" + l + "|" + face + "|" + rot + "|" + arm
                    + "|" + (y == NO_Y ? "-" : String.valueOf(y)) + "|" + (owner.isEmpty() ? "-" : owner) + "|" + state;
        }

        static Plot parse(String line) {
            String[] f = line.split("\\|", -1);
            if (f.length < 14 || !f[0].equals("plot")) return null;
            try {
                return new Plot(Integer.parseInt(f[1]), f[2], f[3], Integer.parseInt(f[4]), Integer.parseInt(f[5]), Integer.parseInt(f[6]),
                        Integer.parseInt(f[7]), f[8], Integer.parseInt(f[9]), Integer.parseInt(f[10]),
                        f[11].equals("-") ? NO_Y : Integer.parseInt(f[11]), f[12].equals("-") ? "" : f[12], f[13]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    static final class Town {
        final String dim, name, founder;
        final int cx, cz;
        volatile int cy;
        final long founded;
        final List<Plot> plots = new CopyOnWriteArrayList<>();

        Town(String dim, int cx, int cy, int cz, String name, String founder, long founded) {
            this.dim = dim;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.name = name;
            this.founder = founder;
            this.founded = founded;
        }

        BlockPos center() { return new BlockPos(cx, cy, cz); }

        int done() {
            int n = 0;
            for (Plot p : plots) if (p.done()) n++;
            return n;
        }

        /** The finished building of a kind (the first), or null. */
        Plot built(String kind) {
            for (Plot p : plots) if (p.kind.equals(kind) && p.done()) return p;
            return null;
        }

        Plot shopOf(String bot) {
            for (Plot p : plots) if (p.kind.equals("shop") && p.owner.equalsIgnoreCase(bot)) return p;
            return null;
        }

        /** Inside the town (or right next to it)? */
        boolean contains(int x, int z, int margin) {
            for (Plot p : plots) if (p.inside(x, z, margin)) return true;
            return false;
        }
    }

    private static volatile Town town;
    /** Told to forget the town: no new one unless they're asked for it. */
    private static volatile boolean forgotten;
    private static volatile Path loadedFrom;

    private static Path file() {
        return Home.worldFile("city.txt");
    }

    private static synchronized void load() {
        Path f;
        try {
            f = file();
        } catch (Throwable t) {
            return;
        }
        if (f == null || f.equals(loadedFrom)) return;
        loadedFrom = f;
        town = null;
        forgotten = false;
        if (!Files.isRegularFile(f)) return;
        try {
            Town t = null;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.startsWith("forgotten")) forgotten = true;
                if (line.startsWith("town|")) {
                    String[] x = line.split("\\|", -1);
                    if (x.length < 8) continue;
                    t = new Town(x[1], Integer.parseInt(x[2]), Integer.parseInt(x[3]), Integer.parseInt(x[4]), x[5], x[6],
                            Long.parseLong(x[7]));
                } else if (line.startsWith("plot|") && t != null) {
                    Plot p = Plot.parse(line);
                    if (p != null) t.plots.add(p);
                }
            }
            town = t;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[city] couldn't read {}: {}", f, e.toString());
        }
    }

    static synchronized void save() {
        Town t = town;
        Path f = file();
        if (f == null) return;
        try {
            if (t == null) {
                // a town somebody told them to forget: they don't go founding another by themselves
                if (forgotten) Files.write(f, List.of("forgotten"), StandardCharsets.UTF_8);
                else Files.deleteIfExists(f);
                return;
            }
            List<String> out = new ArrayList<>();
            out.add("town|" + t.dim + "|" + t.cx + "|" + t.cy + "|" + t.cz + "|" + t.name.replace('|', ' ') + "|" + t.founder + "|" + t.founded);
            for (Plot p : t.plots) out.add(p.line());
            Files.write(f, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[city] couldn't save {}: {}", f, e.toString());
        }
    }

    /** The town, or null if there isn't one yet. */
    static Town town() {
        load();
        return town;
    }

    /** The town, if it's in this dimension. Server thread. */
    static Town townIn(ServerLevel level) {
        Town t = town();
        return t != null && t.dim.equals(Home.dim(level)) ? t : null;
    }

    // ------------------------------------------------------------------------
    // The designs it's built from
    // ------------------------------------------------------------------------

    static final String[] KINDS = {"plaza", "warehouse", "shop", "farm", "temple", "mall", "amphitheatre"};

    /** The town's designs by kind (from the schematics folder), plus up to four houses. Job thread (reads files). */
    static Map<String, CityPlan.Design> designs(List<CityPlan.Design> houses) {
        Map<String, CityPlan.Design> out = new LinkedHashMap<>();
        for (String k : KINDS) {
            Blueprints.Entry e = Blueprints.byFile("city_" + k + ".nbt");
            if (e == null) continue;
            try {
                Schematic s = Blueprints.plan(e);
                out.put(k, new CityPlan.Design(k, e.fileName(), s.sx, s.sz, e.front()));
            } catch (IOException | RuntimeException ex) {
                LOGGER.warn("[city] can't read {}: {}", e.fileName(), ex.toString());
            }
        }
        List<CityPlan.Design> hs = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            Blueprints.Entry e = Blueprints.byFile("medieval_house_" + i + ".nbt");
            if (e == null) continue;
            try {
                Schematic s = Blueprints.plan(e);
                hs.add(new CityPlan.Design("house", e.fileName(), s.sx, s.sz, e.front()));
            } catch (IOException | RuntimeException ignored) { }
        }
        hs.sort((a, b) -> Integer.compare(a.sx() * a.sz(), b.sx() * b.sz()));
        houses.addAll(hs.subList(0, Math.min(4, hs.size())));
        return out;
    }

    // ------------------------------------------------------------------------
    // Ground
    // ------------------------------------------------------------------------

    /** {y, water 0/1} of the top of a column (trees and plants don't count), or null if it isn't loaded. Server thread. */
    static int[] column(ServerLevel level, int x, int z, int nearY) {
        BlockPos col = new BlockPos(x, nearY, z);
        if (!level.isLoaded(col)) return null;
        for (int dy = 40; dy >= -40; dy--) {
            BlockPos p = col.offset(0, dy, 0);
            if (level.isOutsideBuildHeight(p)) continue;
            if (!level.getFluidState(p).isEmpty()) return new int[]{p.getY(), 1};
            net.minecraft.world.level.block.state.BlockState s = level.getBlockState(p);
            if (s.isAir()) continue;
            String path = SurvivalBrain.blockPath(s);
            if (path.endsWith("_leaves") || SurvivalBrain.isLog(s) || path.equals("snow") || path.endsWith("mushroom_block")) continue;
            if (Building.isSolid(level, p)) return new int[]{p.getY(), 0};
        }
        return null;
    }

    /** The ground level for a footprint (the middle of a few spots), or NO_Y if it isn't loaded. Server thread. */
    static int groundY(ServerLevel level, int x0, int z0, int w, int l, int nearY) {
        List<Integer> ys = new ArrayList<>();
        int[] fx = {0, w / 4, w / 2, (3 * w) / 4, w - 1}, fz = {0, l / 4, l / 2, (3 * l) / 4, l - 1};
        for (int ax : fx) {
            for (int az : fz) {
                int[] c = column(level, x0 + ax, z0 + az, nearY);
                if (c != null) ys.add(c[0]);
            }
        }
        if (ys.size() < 12) return NO_Y;
        Collections.sort(ys);
        return ys.get(ys.size() / 2);
    }

    /** How good a place is for the town ({x, y, z, score}), or null if it won't do. Server thread. */
    static int[] rateSite(ServerLevel level, int cx, int cz, int nearY, int[] bounds) {
        String dim = Home.dim(level);
        int bx0 = cx + bounds[0] - 6, bz0 = cz + bounds[1] - 6, bx1 = cx + bounds[2] + 6, bz1 = cz + bounds[3] + 6;
        for (Blueprints.Build b : Blueprints.builds()) {
            if (!b.dim().equals(dim)) continue;
            if (b.x() <= bx1 && b.x() + b.sx() - 1 >= bx0 && b.z() <= bz1 && b.z() + b.sz() - 1 >= bz0) return null;
        }
        for (Home.Base h : Home.allIn(level)) {
            BlockPos m = h.middle();
            if (m.getX() >= bx0 - 8 && m.getX() <= bx1 + 8 && m.getZ() >= bz0 - 8 && m.getZ() <= bz1 + 8) return null;
        }
        List<Integer> ys = new ArrayList<>();
        int samples = 0, water = 0, unloaded = 0;
        for (int x = bx0; x <= bx1; x += 8) {
            for (int z = bz0; z <= bz1; z += 8) {
                samples++;
                int[] c = column(level, x, z, nearY);
                if (c == null) { unloaded++; continue; }
                if (c[1] == 1) { water++; continue; }
                ys.add(c[0]);
            }
        }
        if (samples == 0 || ys.size() < samples / 3) return null;
        if (water > samples / 5) return null;
        Collections.sort(ys);
        int med = ys.get(ys.size() / 2);
        double dev = 0;
        for (int y : ys) dev += Math.abs(y - med);
        dev /= ys.size();
        int[] mid = column(level, cx, cz, med);
        int cy = mid != null && mid[1] == 0 && Math.abs(mid[0] - med) <= 4 ? mid[0] : med;
        int score = (int) Math.round(dev * 10 + 300.0 * water / samples + 60.0 * unloaded / samples);
        return new int[]{cx, cy, cz, score};
    }

    // ------------------------------------------------------------------------
    // Founding it
    // ------------------------------------------------------------------------

    private static final String[] NAMES = {"Diamondvale", "Oakhollow", "Stonebridge", "Brookhaven", "Emberfield", "Willowmere",
            "Ironwood", "Cobble Cove", "Lanternfall", "Mossgate", "Copperbrook", "Pinecrest", "Amberhold", "Riverbend"};

    private static List<ServerPlayer> bots(MinecraftServer server) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (HumanBehavior.isAiBot(p) && p.isAlive()) out.add(p);
        return out;
    }

    /** An online player by name (any case), or null. Server thread. */
    static ServerPlayer byName(MinecraftServer server, String name) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (p.getName().getString().equalsIgnoreCase(name)) return p;
        return null;
    }

    private static List<String> botNames(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        for (ServerPlayer p : bots(server)) out.add(p.getName().getString());
        return out;
    }

    /**
     * Lays the town out and remembers it. {@code at}: where the plaza goes (null: the bot finds a
     * spot near everyone's homes). Job thread. Returns the town, or null (and says why).
     */
    static Town found(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, BlockPos at, String name) {
        Town existing = town();
        if (existing != null) {
            HumanChat.say(server, b.name, "we've already got a town, " + existing.name + ", at " + existing.cx + " " + existing.cz);
            return null;
        }
        List<CityPlan.Design> houses = new ArrayList<>();
        Map<String, CityPlan.Design> d = designs(houses);
        CityPlan.Design plaza = d.get("plaza");
        if (plaza == null) {
            HumanChat.say(server, b.name, "can't find the town designs (city_plaza and the rest) in the schematics folder");
            return null;
        }
        List<String> names = onServer(server, () -> botNames(server), List.of(b.name));
        List<String> owners = new ArrayList<>();
        owners.add(b.name);
        for (String n : names) if (!n.equalsIgnoreCase(b.name)) owners.add(n);
        List<CityPlan.Lot> lots = CityPlan.layout(plaza, CityPlan.program(d, Math.max(3, owners.size()), houses));
        int[] bounds = CityPlan.bounds(lots);
        int[] site;
        if (at != null) {
            site = onServer(server, () -> {
                int[] c = column(bot.level(), at.getX(), at.getZ(), at.getY());
                return new int[]{at.getX(), c != null && c[1] == 0 ? c[0] : at.getY() - 1, at.getZ(), 0};
            }, null);
        } else {
            site = findSite(server, bot, bounds);
        }
        if (site == null) {
            HumanChat.say(server, b.name, "couldn't find a big enough flat spot for a town around here. stand where you want the middle of it and say \"build a city here\"");
            return null;
        }
        String dim = onServer(server, () -> Home.dim(bot.level()), "");
        String townName = name != null && !name.isBlank() ? cap(name.trim()) : NAMES[RNG.nextInt(NAMES.length)];
        Town t = new Town(dim, site[0], site[1], site[2], townName, b.name, System.currentTimeMillis());
        int shopIndex = 0;
        for (CityPlan.Lot lot : lots) {
            String owner = "";
            if (lot.kind().equals("shop")) {
                owner = shopIndex < owners.size() ? owners.get(shopIndex) : "";
                shopIndex++;
            }
            int y = lot.kind().equals("plaza") ? site[1] : NO_Y;
            t.plots.add(new Plot(lot.id(), lot.kind(), lot.file(), site[0] + lot.x0(), site[2] + lot.z0(), lot.w(), lot.l(), lot.face(),
                    lot.rot(), lot.arm(), y, owner, "todo"));
        }
        synchronized (City.class) {
            if (town() != null) return null;
            town = t;
            forgotten = false;
            save();
        }
        LOGGER.info("[city] {} founded {} at {} {} {} ({} pieces)", b.name, townName, site[0], site[1], site[2], t.plots.size());
        announce(server, t, b.name, names);
        return t;
    }

    private static String cap(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split("\\s+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.length() == 0 ? s : sb.toString();
    }

    /** The founder tells the others, and each says what they'll take on first. */
    private static void announce(MinecraftServer server, Town t, String founder, List<String> names) {
        List<String> others = new ArrayList<>();
        for (String n : names) if (!n.equalsIgnoreCase(founder)) others.add(n);
        String to = others.isEmpty() ? "" : String.join(", ", others) + ", ";
        HumanChat.say(server, founder, to + "let's build a town! calling it " + t.name + ". the plaza goes at " + t.cx + " " + t.cy + " " + t.cz
                + ". plaza and roads first, then a warehouse for all our stuff, a shop each, farms, a temple, a mall and an amphitheatre");
        String[] jobs = {"i'll start on the roads", "i'll get the warehouse going", "i'll do the farm", "i'll put up my shop first",
                "i'll gather stone, we'll need tons"};
        String last = founder;
        int i = 0;
        for (String o : others) {
            String line = HumanChat.pick("i'm in! ", "let's go ", "bet, ", "love it. ") + jobs[(i++) % jobs.length];
            HumanChat.sayAfter(server, o, last, founder + " " + line);
            last = o;
        }
    }

    /** Somewhere near everyone's homes, flat and dry, clear of other builds: {x, y, z, score} or null. Job thread. */
    static int[] findSite(MinecraftServer server, ServerPlayer bot, int[] bounds) {
        int[] anchor = onServer(server, () -> {
            List<Home.Base> homes = Home.allIn(bot.level());
            BlockPos f = bot.blockPosition();
            if (homes.isEmpty()) return new int[]{f.getX(), f.getY(), f.getZ()};
            long x = 0, y = 0, z = 0;
            for (Home.Base h : homes) {
                x += h.middle().getX();
                y += h.middle().getY();
                z += h.middle().getZ();
            }
            return new int[]{(int) (x / homes.size()), (int) (y / homes.size()), (int) (z / homes.size())};
        }, null);
        if (anchor == null) return null;
        int radius = Math.max(Math.max(-bounds[0], bounds[2]), Math.max(-bounds[1], bounds[3]));
        int[] best = null;
        for (double ring : new double[]{0.6, 1.0, 1.4}) {
            int dist = (int) Math.round((radius + 16) * ring);
            for (int k = 0; k < 8; k++) {
                double ang = Math.PI / 4 * k;
                int cx = anchor[0] + (int) Math.round(Math.cos(ang) * dist), cz = anchor[2] + (int) Math.round(Math.sin(ang) * dist);
                int[] r = onServer(server, () -> rateSite(bot.level(), cx, cz, anchor[1], bounds), null);
                if (r == null) continue;
                r[3] += (int) (ring * 20);
                if (best == null || r[3] < best[3]) best = r;
            }
            if (best != null && best[3] < 60) break;
        }
        return best;
    }

    /** "forget the town". */
    static synchronized void forget() {
        load();
        town = null;
        forgotten = true;
        save();
        CLAIMS.clear();
        NEEDS.clear();
    }

    // ------------------------------------------------------------------------
    // Who builds what
    // ------------------------------------------------------------------------

    private record Claim(String bot, long beat) {}

    private static final Map<Integer, Claim> CLAIMS = new ConcurrentHashMap<>();
    /** A piece that went nowhere: left alone till then. */
    private static final Map<Integer, Long> PLOT_BACKOFF = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> PLOT_FAILS = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> LAST_LEFT = new ConcurrentHashMap<>();
    /** Pieces being worked on this moment (claims can go stale during a long build; these can't). */
    private static final Map<Integer, Claim> ACTIVE = new ConcurrentHashMap<>();
    /** Pieces that couldn't even be looked at (not loaded, couldn't get there). */
    private static final Map<Integer, Integer> SOFT_FAILS = new ConcurrentHashMap<>();

    /** The piece this bot works on next (the one it's on, else the first free one in order), or null. */
    static synchronized Plot claim(Town t, String me, Set<String> online) {
        long now = System.currentTimeMillis();
        ACTIVE.values().removeIf(a -> now - a.beat() > 3 * 60 * 60_000L); // a bot that died mid-build
        for (Plot p : t.plots) {
            Claim c = CLAIMS.get(p.id);
            if (c != null && c.bot().equals(me) && p.state.equals("todo") && now >= PLOT_BACKOFF.getOrDefault(p.id, 0L)) {
                CLAIMS.put(p.id, new Claim(me, now));
                return p;
            }
        }
        for (Plot p : t.plots) {
            if (!p.state.equals("todo") || now < PLOT_BACKOFF.getOrDefault(p.id, 0L)) continue;
            Claim c = CLAIMS.get(p.id);
            if (c != null && !c.bot().equals(me) && now - c.beat() < 15 * 60_000L) continue;
            Claim a = ACTIVE.get(p.id);
            if (a != null && !a.bot().equals(me)) continue; // somebody's on it right now, however long it takes
            if (!p.owner.isEmpty() && !p.owner.equalsIgnoreCase(me) && online.contains(p.owner.toLowerCase(Locale.ROOT))) continue;
            // a shop of your own first, before anybody else's
            if (p.kind.equals("shop") && p.owner.isEmpty() && t.shopOf(me) != null && !t.shopOf(me).done()) continue;
            CLAIMS.put(p.id, new Claim(me, now));
            return p;
        }
        return null;
    }

    static void release(Plot p) {
        CLAIMS.remove(p.id);
    }

    /** Who's on a piece right now, or null. */
    static String builderOf(Plot p) {
        Claim c = CLAIMS.get(p.id);
        return c != null && System.currentTimeMillis() - c.beat() < 15 * 60_000L ? c.bot() : null;
    }

    // ------------------------------------------------------------------------
    // Building a piece
    // ------------------------------------------------------------------------

    static Direction direction(String face) {
        return switch (face) {
            case "north" -> Direction.NORTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> Direction.SOUTH;
        };
    }

    /** Walks over to a spot (giving up after a while). Job thread. */
    static boolean walk(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, BlockPos to, double reach, int seconds)
            throws InterruptedException {
        if (onServer(server, () -> Math.sqrt(bot.blockPosition().distSqr(to)) <= reach + 0.5, false)) return true;
        Surface.backUp(server, bot, b, null);
        BotPathing.Options o = BotPathing.Options.full();
        o.timeoutTicks = 20 * seconds;
        BotPathing.goToBlocking(bot, ActionPathfinder.near(to.getX(), to.getY(), to.getZ(), reach), o, seconds * 1000L + 5000L);
        return onServer(server, () -> Math.sqrt(bot.blockPosition().distSqr(to)) <= reach + 3, false);
    }

    /** Builds (or carries on with) a piece. 1: finished, 0: some way along, -1: couldn't. Job thread. */
    static int work(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Town t, Plot p) throws InterruptedException {
        ACTIVE.put(p.id, new Claim(b.name, System.currentTimeMillis()));
        try {
            return workOn(server, bot, b, t, p);
        } finally {
            ACTIVE.remove(p.id);
        }
    }

    /** Couldn't get there or see the ground: try again in a while, and skip it after a few goes. */
    private static int softFail(MinecraftServer server, String bot, Plot p) {
        int n = SOFT_FAILS.merge(p.id, 1, Integer::sum);
        release(p);
        if (n >= 6) {
            block(server, bot, p, "can't get there");
            return -1;
        }
        PLOT_BACKOFF.put(p.id, System.currentTimeMillis() + 5 * 60_000L);
        return -1;
    }

    private static int workOn(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Town t, Plot p) throws InterruptedException {
        BlockPos mid = new BlockPos(p.midX(), p.y != NO_Y ? p.y : t.cy, p.midZ());
        walk(server, bot, b, mid, 8, 150);
        if (!SurvivalBrain.canContinue(b)) return 0;
        int left;
        BlueprintBuilder.Placed placed;
        if (p.road()) {
            placed = onServer(server, () -> roadPlan(bot.level(), t, p), null);
            if (placed == null) return softFail(server, b.name, p); // not loaded: couldn't get near it
            left = construct(server, bot, b, placed);
        } else {
            Blueprints.Entry e = Blueprints.byFile(p.file);
            if (e == null) {
                block(server, b.name, p, "the " + p.file + " design isn't in the schematics folder anymore");
                return -1;
            }
            if (p.y == NO_Y) {
                int y = onServer(server, () -> groundY(bot.level(), p.x0, p.z0, p.w, p.l, t.cy), NO_Y);
                if (y == NO_Y) return softFail(server, b.name, p);
                p.y = y;
                save();
            }
            Blueprints.Build prev = null;
            for (Blueprints.Build r : Blueprints.builds()) {
                if (r.file().equals(e.fileName()) && r.dim().equals(t.dim) && r.x() == p.x0 && r.z() == p.z0 && r.y() == p.y - e.ground()) prev = r;
            }
            BlueprintBuilder.Spot spot = prev != null ? BlueprintBuilder.Spot.resume(prev)
                    : BlueprintBuilder.Spot.at(new BlockPos(p.x0, p.y, p.z0), direction(p.face));
            // a farm wants seeds: they come from pulling up grass, not from a recipe
            if (p.kind.equals("farm") && onServer(server, () -> Gathering.countOf(bot, "wheat_seeds"::equals), 0) < 16) {
                Farm.ensureSeeds(server, bot, b, 32);
            }
            BlueprintBuilder.Result r = BlueprintBuilder.buildFor(server, bot, b, e, spot, prev, TOWN_BUILDER);
            if (r == null) {
                int fails = PLOT_FAILS.merge(p.id, 1, Integer::sum);
                if (fails >= 2) block(server, b.name, p, "something's in the way there");
                else PLOT_BACKOFF.put(p.id, System.currentTimeMillis() + 10 * 60_000L);
                return -1;
            }
            placed = r.placed();
            left = r.complete() ? 0 : Math.max(1, r.remaining());
            // a farm with everything but some of the planting done counts: whoever tends it plants the rest
            if (left > 0 && p.kind.equals("farm") && SurvivalBrain.canContinue(b)) {
                BlueprintBuilder.Work w = BlueprintBuilder.scan(server, bot, placed);
                if (w != null && onlyPlanting(w) && plantedEnough(w, placed)) {
                    for (Blueprints.Build rec : Blueprints.builds()) {
                        if (rec.file().equals(e.fileName()) && rec.dim().equals(t.dim) && rec.x() == placed.origin().getX()
                                && rec.y() == placed.origin().getY() && rec.z() == placed.origin().getZ()) {
                            Blueprints.remember(new Blueprints.Build(rec.bot(), rec.dim(), rec.file(), rec.x(), rec.y(), rec.z(), rec.rot(),
                                    rec.sx(), rec.sy(), rec.sz(), true, rec.swaps()));
                        }
                    }
                    left = 0;
                }
            }
        }
        if (left == 0) {
            finished(server, bot, b, t, p);
            return 1;
        }
        if (left < 0) return 0;
        // short of something: ask the others
        if (placed != null && SurvivalBrain.canContinue(b)) {
            BlueprintBuilder.Work w = BlueprintBuilder.scan(server, bot, placed);
            if (w != null) {
                Map<String, Integer> shortBy = onServer(server, () -> BlueprintBuilder.shortfall(bot, w.needs), Map.of());
                askFor(server, b.name, shortBy, p);
            }
        }
        SOFT_FAILS.remove(p.id);
        Integer before = LAST_LEFT.put(p.id, left);
        if (before == null || before > left) PLOT_FAILS.remove(p.id); // getting somewhere
        if (before != null && before <= left) {
            // nothing got done this time: leave it a while (the others may bring what's missing)
            PLOT_BACKOFF.put(p.id, System.currentTimeMillis() + 8 * 60_000L);
            release(p);
        }
        return 0;
    }

    /** At least a quarter of the crops are in (so whoever tends it has something to go on). */
    static boolean plantedEnough(BlueprintBuilder.Work w, BlueprintBuilder.Placed placed) {
        int crops = 0;
        Schematic s = placed.plan();
        for (int y = 0; y < s.sy; y++) for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++) {
            State st = s.at(x, y, z);
            if (st != null && Schematic.Rules.isCrop(st.path())) crops++;
        }
        int missing = w.solid.size() + w.attach.size();
        return crops == 0 || missing <= crops * 3 / 4;
    }

    /** All that's left is putting seeds in (and decoration). */
    static boolean onlyPlanting(BlueprintBuilder.Work w) {
        List<BlueprintBuilder.Cell> rest = new ArrayList<>();
        rest.addAll(w.clear);
        rest.addAll(w.solid);
        rest.addAll(w.fluid);
        rest.addAll(w.attach);
        for (BlueprintBuilder.Cell c : rest) if (!Schematic.Rules.isCrop(c.plan().path())) return false;
        return true;
    }

    private static void block(MinecraftServer server, String bot, Plot p, String why) {
        p.state = "blocked";
        release(p);
        save();
        HumanChat.say(server, bot, "skipping " + p.label() + ": " + why);
    }

    private static void finished(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Town t, Plot p) {
        p.state = "done";
        if (p.kind.equals("shop") && p.owner.isEmpty() && t.shopOf(b.name) == null) p.owner = b.name;
        release(p);
        LAST_LEFT.remove(p.id);
        save();
        NEEDS.removeIf(n -> n.plot() == p.id);
        if (p.kind.equals("warehouse")) {
            onServer(server, () -> {
                for (BlockPos c : containers(bot.level(), p, "chest")) Storage.remember(bot.level(), c);
                return true;
            }, false);
        }
        int done = t.done(), all = t.plots.size();
        if (p.road()) {
            if (RNG.nextInt(3) == 0) SurvivalBrain.maybeSay(server, b, "another bit of the " + p.face + " road done", 0.6);
            return;
        }
        String extra = switch (p.kind) {
            case "temple" -> ". there's an offering chest on the altar, bring the temple some food";
            case "shop" -> ". open for business! ask me what i sell";
            case "warehouse" -> ". it's our shared storage now, everyone can use the chests";
            case "farm" -> ". we'll all keep it harvested";
            case "mall" -> ". anyone can sell their stuff there";
            case "amphitheatre" -> ". showtime";
            default -> "";
        };
        HumanChat.say(server, b.name, p.label() + " is finished" + extra + " (" + t.name + ": " + done + "/" + all + " done)");
    }

    /** Puts a plan together the usual way (gather, clear, place), a few rounds. Remaining essential cells, or -1. Job thread. */
    static int construct(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, BlueprintBuilder.Placed p)
            throws InterruptedException {
        Predicate<String> keep = BlueprintBuilder.keepFor(p);
        SurvivalBrain.keep(bot, keep);
        try {
            Map<BlockPos, Integer> fails = new HashMap<>();
            Set<String> noItems = new TreeSet<>();
            for (int round = 0; round < 5 && SurvivalBrain.canContinue(b); round++) {
                BlueprintBuilder.Work w = BlueprintBuilder.scan(server, bot, p);
                if (w == null) return -1;
                if (w.isEmpty()) return 0;
                if (!w.needs.isEmpty()) BlueprintBuilder.gather(server, bot, b, w.needs, keep, p);
                if (!SurvivalBrain.canContinue(b)) break;
                BlockPos mid = p.center();
                if (onServer(server, () -> bot.blockPosition().distSqr(mid) > 20 * 20, false)) walk(server, bot, b, mid.above(), 5, 90);
                noItems.clear();
                int done = BlueprintBuilder.clearAll(server, bot, b, p, w.clear, fails);
                done += BlueprintBuilder.placeAll(server, bot, b, p, w.solid, fails, noItems);
                done += BlueprintBuilder.placeAll(server, bot, b, p, w.attach, fails, noItems);
                done += BlueprintBuilder.placeAll(server, bot, b, p, w.decor, fails, noItems);
                SurvivalBrain.pickUpNearbyItems(server, bot, 8, false);
                if (done == 0) break;
            }
            BlueprintBuilder.Work left = BlueprintBuilder.scan(server, bot, p);
            return left == null ? -1 : left.essential();
        } finally {
            SurvivalBrain.keep(bot, null);
        }
    }

    // ------------------------------------------------------------------------
    // Roads
    // ------------------------------------------------------------------------

    private static boolean ours(String path) {
        return path.equals("cobblestone") || path.equals("stone_bricks") || path.equals("oak_fence") || path.equals("lantern");
    }

    /**
     * A piece of road as a plan: 5 wide (stone brick edges, cobblestone down the middle) at the
     * ground's height, never more than a block up or down from the last, with what's above it
     * cleared, gaps under it filled, and a street lamp every 8 blocks on both sides. Someone's
     * build in the way is left alone. Null if the ground there isn't loaded. Server thread.
     */
    static BlueprintBuilder.Placed roadPlan(ServerLevel level, Town t, Plot p) {
        int[] d = CityPlan.vec(p.face);
        boolean alongX = d[0] != 0;
        int len = alongX ? p.w : p.l;
        // the height the road starts at: where the piece before it (nearer the plaza) ended
        int startY = t.cy;
        for (Plot o : t.plots) {
            if (!o.road() || o.arm != p.arm || o.id >= p.id || o.y == NO_Y) continue;
            startY = o.y;
        }
        // along the road, outwards from the plaza
        int[] heights = new int[len];
        int prev = startY;
        for (int i = 0; i < len; i++) {
            int a = alongX ? (d[0] > 0 ? p.x0 + i : p.x0 + p.w - 1 - i) : (d[1] > 0 ? p.z0 + i : p.z0 + p.l - 1 - i);
            int cx = alongX ? a : p.midX(), cz = alongX ? p.midZ() : a;
            int[] c = column(level, cx, cz, prev);
            if (c == null) return null;
            prev = Math.max(prev - 1, Math.min(prev + 1, c[0])); // over water: level with the surface, a bridge
            heights[i] = prev;
        }
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int h : heights) {
            lo = Math.min(lo, h);
            hi = Math.max(hi, h);
        }
        int y0 = lo - 3, sy = hi - lo + 8;
        int sx = p.w, sz = p.l;
        List<State> palette = new ArrayList<>(List.of(State.parse("minecraft:air"), State.parse("minecraft:cobblestone"),
                State.parse("minecraft:stone_bricks"), State.parse("minecraft:oak_fence"),
                State.parse("minecraft:lantern[hanging=false,waterlogged=false]")));
        int[] cells = new int[sx * sy * sz];
        java.util.Arrays.fill(cells, -1);
        for (int lx = 0; lx < sx; lx++) {
            for (int lz = 0; lz < sz; lz++) {
                int wx = p.x0 + lx, wz = p.z0 + lz;
                int i = alongX ? (d[0] > 0 ? lx : sx - 1 - lx) : (d[1] > 0 ? lz : sz - 1 - lz);
                int across = alongX ? wz - p.midZ() : wx - p.midX();
                int along = alongX ? Math.abs(wx - t.cx) : Math.abs(wz - t.cz);
                int ry = heights[i];
                boolean lamp = Math.abs(across) == CityPlan.ROAD_HALF + 1 && along % 8 == 4;
                if (Math.abs(across) > CityPlan.ROAD_HALF && !lamp) continue;
                // someone's build in the way: leave this column alone
                boolean skip = false;
                for (int y = ry; y <= ry + 3 && !skip; y++) {
                    BlockPos q = new BlockPos(wx, y, wz);
                    String path = SurvivalBrain.blockPath(level.getBlockState(q));
                    if (Protection.isManMade(path) && !ours(path)) skip = true;
                }
                if (skip) continue;
                int top = Math.abs(across) == CityPlan.ROAD_HALF ? 2 : 1;
                set(cells, sx, sz, lx, ry - y0, lz, lamp ? 1 : top);
                // fill a dip under it (up to three deep)
                for (int y = ry - 1; y >= ry - 3; y--) {
                    BlockPos q = new BlockPos(wx, y, wz);
                    if (Building.isSolid(level, q)) break;
                    set(cells, sx, sz, lx, y - y0, lz, 1);
                }
                if (lamp) {
                    set(cells, sx, sz, lx, ry + 1 - y0, lz, 3);
                    set(cells, sx, sz, lx, ry + 2 - y0, lz, 3);
                    set(cells, sx, sz, lx, ry + 3 - y0, lz, 4);
                    set(cells, sx, sz, lx, ry + 4 - y0, lz, 0);
                } else {
                    for (int y = ry + 1; y <= ry + 3; y++) set(cells, sx, sz, lx, y - y0, lz, 0);
                }
            }
        }
        Schematic plan = new Schematic("road", sx, sy, sz, cells, palette);
        p.y = heights[len - 1];
        return new BlueprintBuilder.Placed(plan, new BlockPos(p.x0, y0, p.z0), 0, t.dim);
    }

    /** Part of a finished road (or its lamps)? Those aren't dug up for materials or by the pathfinder. */
    static boolean protectsRoad(String dim, BlockPos q) {
        Town t = town;
        if (t == null || !t.dim.equals(dim)) return false;
        for (Plot p : t.plots) {
            if (!p.road() || !p.done() || p.y == NO_Y) continue;
            if (p.inside(q.getX(), q.getZ(), 0) && q.getY() >= p.y - 18 && q.getY() <= p.y + 18) return true;
        }
        return false;
    }

    private static void set(int[] cells, int sx, int sz, int x, int y, int z, int v) {
        int sy = cells.length / (sx * sz);
        if (x < 0 || z < 0 || y < 0 || x >= sx || z >= sz || y >= sy) return;
        cells[(y * sz + z) * sx + x] = v;
    }

    // ------------------------------------------------------------------------
    // Containers in a building: shop stock, the till, the offering chest, the warehouse
    // ------------------------------------------------------------------------

    /** Chests (or barrels) inside a piece of the town, one per double chest. Server thread. */
    private record Found(List<BlockPos> where, long at) {}

    private static final Map<String, Found> FOUND_CACHE = new ConcurrentHashMap<>();

    static List<BlockPos> containers(ServerLevel level, Plot p, String which) {
        if (p.y == NO_Y) return new ArrayList<>();
        String key = p.id + "|" + which + "|" + p.y;
        long now = System.currentTimeMillis();
        Found f = FOUND_CACHE.get(key);
        if (f != null && now - f.at() < 20_000L) {
            List<BlockPos> still = new ArrayList<>();
            for (BlockPos q : f.where()) {
                String path = level.isLoaded(q) ? SurvivalBrain.blockPath(level.getBlockState(q)) : "";
                if (which.equals("chest") ? path.equals("chest") || path.equals("trapped_chest") : path.equals(which)) still.add(q);
            }
            return still;
        }
        List<BlockPos> out = scanContainers(level, p, which);
        FOUND_CACHE.put(key, new Found(List.copyOf(out), now));
        return out;
    }

    private static List<BlockPos> scanContainers(ServerLevel level, Plot p, String which) {
        List<BlockPos> out = new ArrayList<>();
        for (int y = p.y - 2; y <= p.y + 10; y++) {
            for (int x = p.x0; x < p.x0 + p.w; x++) {
                for (int z = p.z0; z < p.z0 + p.l; z++) {
                    BlockPos q = new BlockPos(x, y, z);
                    if (!level.isLoaded(q)) continue;
                    String path = SurvivalBrain.blockPath(level.getBlockState(q));
                    boolean hit = which.equals("chest") ? path.equals("chest") || path.equals("trapped_chest") : path.equals(which);
                    if (hit && !Storage.secondHalf(level, q)) out.add(q);
                }
            }
        }
        return out;
    }

    /** Whatever's in these containers, by item. Server thread. */
    static Map<String, Integer> contents(ServerLevel level, List<BlockPos> where) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Set<Container> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (BlockPos q : where) {
            Container c = HopperBlockEntity.getContainerAt(level, q);
            if (c == null || !seen.add(c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) out.merge(SurvivalBrain.itemPath(s), s.getCount(), Integer::sum);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------
    // Helping each other: "anyone got 64 stone bricks?"
    // ------------------------------------------------------------------------

    /** Something a builder is short of. {@code taken}: who's fetching it (and since when). */
    record Need(String from, String item, int count, int plot, long at, String taken, long takenAt) {}

    static final List<Need> NEEDS = new CopyOnWriteArrayList<>();

    /** Puts what a builder is missing on the board and asks the others (once per item). */
    static void askFor(MinecraftServer server, String from, Map<String, Integer> missing, Plot p) {
        if (missing == null || missing.isEmpty()) return;
        List<String> others = new ArrayList<>();
        for (String n : onServer(server, () -> botNames(server), List.<String>of())) if (!n.equalsIgnoreCase(from)) others.add(n);
        if (others.isEmpty()) return;
        List<Map.Entry<String, Integer>> es = new ArrayList<>(missing.entrySet());
        es.sort((a, c) -> c.getValue() - a.getValue());
        List<String> asked = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Integer> e : es) {
            if (asked.size() >= 3) break;
            if (e.getValue() <= 0) continue;
            boolean already = false;
            for (Need n : NEEDS) if (n.from().equalsIgnoreCase(from) && n.item().equals(e.getKey())) already = true;
            if (already) continue;
            NEEDS.add(new Need(from, e.getKey(), Math.min(e.getValue(), BlueprintBuilder.MAX_GATHER), p.id, now, null, 0));
            asked.add(e.getValue() + " " + BlueprintBuilder.label(e.getKey()));
        }
        if (asked.isEmpty()) return;
        HumanChat.say(server, from, String.join(", ", others) + ": anyone got " + String.join(", ", asked) + "? need it for " + p.label());
    }

    /** A request this bot can take on, or null. */
    static synchronized Need takeNeed(String me) {
        long now = System.currentTimeMillis();
        NEEDS.removeIf(n -> now - n.at() > 60 * 60_000L);
        for (Need n : NEEDS) {
            if (n.from().equalsIgnoreCase(me)) continue;
            if (n.taken() != null && now - n.takenAt() < 15 * 60_000L) continue;
            Need t = new Need(n.from(), n.item(), n.count(), n.plot(), n.at(), me, now);
            NEEDS.remove(n);
            NEEDS.add(t);
            return t;
        }
        return null;
    }

    /** Gets what someone asked for and brings it to them (or to the warehouse). Job thread. */
    static void help(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Town t, Need n) throws InterruptedException {
        String label = BlueprintBuilder.label(n.item());
        HumanChat.say(server, b.name, n.from() + " " + HumanChat.pick("on it, getting you " + n.count() + " " + label,
                "i'll get the " + label, "got you, bringing " + n.count() + " " + label));
        Predicate<String> test = BlueprintBuilder.itemTest(n.item());
        if (n.item().endsWith("_seeds")) Farm.ensureSeeds(server, bot, b, n.count());
        int have = onServer(server, () -> Gathering.countOf(bot, test), 0);
        if (have < n.count()) {
            Storage.withdraw(server, bot, b, test, n.count() - have, null);
            have = onServer(server, () -> Gathering.countOf(bot, test), 0);
        }
        if (have < n.count() && SurvivalBrain.canContinue(b)) {
            SurvivalBrain.keep(bot, test);
            try {
                BlueprintBuilder.make(server, bot, b, n.item(), n.count(), 0, test);
            } finally {
                SurvivalBrain.keep(bot, null);
            }
            have = onServer(server, () -> Gathering.countOf(bot, test), 0);
        }
        NEEDS.removeIf(x -> x.from().equals(n.from()) && x.item().equals(n.item()));
        if (have <= 0) {
            HumanChat.say(server, b.name, n.from() + " couldn't get any " + label + ", sorry");
            return;
        }
        final int give = Math.min(have, n.count());
        // hand it over if they're around, else leave it in the warehouse (they take from the chests)
        ServerPlayer to = onServer(server, () -> byName(server, n.from()), null);
        if (to != null) {
            BlockPos at = onServer(server, to::blockPosition, null);
            if (at != null && onServer(server, () -> at.distSqr(bot.blockPosition()) < 160 * 160, false)) {
                for (int i = 0; i < 3 && SurvivalBrain.canContinue(b); i++) {
                    BlockPos now = onServer(server, to::blockPosition, at);
                    walk(server, bot, b, now, 2.5, 60);
                    if (onServer(server, () -> bot.distanceToSqr(to) <= 5 * 5, false)) {
                        int given = onServer(server, () -> Gathering.handOver(bot, to, test, give), 0);
                        if (given > 0) {
                            HumanChat.say(server, b.name, n.from() + " " + HumanChat.pick("here's " + given + " " + label, "there you go, " + given + " " + label));
                            HumanChat.sayAfter(server, n.from(), b.name, HumanChat.pick("thanks " + b.name + "!", "ty!", "legend, thanks"));
                            return;
                        }
                    }
                }
            }
        }
        Plot wh = t.built("warehouse");
        if (wh != null) {
            List<BlockPos> chests = onServer(server, () -> containers(bot.level(), wh, "chest"), List.of());
            for (BlockPos c : chests) {
                if (!walk(server, bot, b, c, 2.5, 90)) continue;
                int put = onServer(server, () -> putIn(bot, c, test, give), 0);
                if (put > 0) {
                    HumanChat.say(server, b.name, n.from() + " left " + put + " " + label + " in the warehouse for you");
                    return;
                }
            }
        }
        Storage.storeAll(server, bot, b, false);
        HumanChat.say(server, b.name, n.from() + " put " + give + " " + label + " in the chests for you");
    }

    /** Moves up to {@code max} matching items from the bot into a container. Server thread. */
    static int putIn(ServerPlayer bot, BlockPos at, Predicate<String> test, int max) {
        Map<String, Integer> one = new HashMap<>();
        net.minecraft.world.entity.player.Inventory inv = bot.getInventory();
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && test.test(SurvivalBrain.itemPath(s))) one.merge(SurvivalBrain.itemPath(s), s.getCount(), Integer::sum);
        }
        // the overall cap, shared out item by item
        int left = max;
        for (Map.Entry<String, Integer> e : one.entrySet()) {
            int n = Math.min(left, e.getValue());
            e.setValue(n);
            left -= n;
        }
        return putIn(bot, at, one);
    }

    /**
     * Moves up to {@code limits.get(item)} of each item into a container, from the last slots
     * first (the first stacks are what it keeps on hand). Server thread.
     */
    static int putIn(ServerPlayer bot, BlockPos at, Map<String, Integer> limits) {
        Container c = HopperBlockEntity.getContainerAt(bot.level(), at);
        if (c == null || bot.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(at)) > 5.5) return 0;
        io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, net.minecraft.world.phys.Vec3.atCenterOf(at));
        net.minecraft.world.entity.player.Inventory inv = bot.getInventory();
        Map<String, Integer> todo = new HashMap<>(limits);
        int moved = 0;
        for (int i = Math.min(36, inv.getContainerSize()) - 1; i >= 0; i--) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            String path = SurvivalBrain.itemPath(s);
            int left = todo.getOrDefault(path, 0);
            if (left <= 0) continue;
            int n = Math.min(left, s.getCount());
            ItemStack part = s.copy();
            part.setCount(n);
            int in = Storage.insert(c, part);
            s.shrink(in); // what didn't fit stays in the pocket
            moved += in;
            todo.put(path, left - in);
            if (in < n) break; // full
        }
        inv.setChanged();
        Storage.note(bot.level(), at, c);
        if (moved > 0) Motions.swingArm(bot);
        return moved;
    }

    // ------------------------------------------------------------------------
    // The temple: a bit of food on the altar, once a day
    // ------------------------------------------------------------------------

    private static final Map<String, Long> OFFERED_DAY = new ConcurrentHashMap<>();
    static final String[] OFFERINGS = {"bread", "wheat", "apple", "golden_apple", "golden_carrot", "carrot", "potato", "baked_potato",
            "beetroot", "melon_slice", "sweet_berries", "glow_berries", "pumpkin_pie", "cookie", "cake", "cooked_beef",
            "cooked_porkchop", "cooked_mutton", "cooked_chicken", "cooked_cod", "cooked_salmon", "honey_bottle", "mushroom_stew"};

    static boolean isOffering(String path) {
        for (String o : OFFERINGS) if (o.equals(path)) return true;
        return false;
    }

    private static long day(ServerPlayer bot) {
        return bot.level().getDefaultClockTime() / 24000L;
    }

    /** Hasn't been to the temple yet today? Server thread. */
    static boolean offeringDue(ServerPlayer bot) {
        Long d = OFFERED_DAY.get(bot.getName().getString());
        return d == null || d < day(bot);
    }

    /** Takes some food to the temple and leaves it on the altar. Job thread. True if it went. */
    static boolean offer(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Town t, boolean asked) throws InterruptedException {
        Plot temple = t.built("temple");
        String me = b.name;
        if (temple == null) {
            if (asked) HumanChat.say(server, me, "we haven't built the temple yet");
            return false;
        }
        OFFERED_DAY.put(me, onServer(server, () -> day(bot), 0L));
        List<BlockPos> chests = onServer(server, () -> containers(bot.level(), temple, "chest"), List.of());
        if (chests.isEmpty()) {
            BlockPos mid = new BlockPos(temple.midX(), temple.y, temple.midZ());
            walk(server, bot, b, mid, 6, 120);
            chests = onServer(server, () -> containers(bot.level(), temple, "chest"), List.of());
            if (chests.isEmpty()) {
                if (asked) HumanChat.say(server, me, "the offering chest on the temple altar is gone");
                return false;
            }
        }
        // something to give: from the pockets (keeping enough to eat), else from the chests
        Predicate<String> food = City::isOffering;
        int have = onServer(server, () -> Gathering.countOf(bot, food), 0);
        if (have < 6) {
            Storage.withdraw(server, bot, b, food, 6 - have, null);
            have = onServer(server, () -> Gathering.countOf(bot, food), 0);
        }
        int give = have >= 12 ? 4 : have >= 6 ? 2 : asked && have > 0 ? 1 : 0;
        if (give == 0) {
            if (asked) HumanChat.say(server, me, "don't have any food to offer right now");
            return false;
        }
        SurvivalBrain.maybeSay(server, b, HumanChat.pick("going to the temple to make an offering", "off to the temple, brb",
                "gonna leave something at the temple"), asked ? 1.0 : 0.5);
        BlockPos chest = chests.get(0);
        if (!walk(server, bot, b, chest, 2.5, 150)) {
            if (asked) HumanChat.say(server, me, "can't get to the altar");
            return false;
        }
        final int g = give;
        int put = onServer(server, () -> putIn(bot, chest, food, g), 0);
        if (put <= 0) {
            HumanChat.say(server, me, "the offering chest is full, the gods are well fed lol");
            return true;
        }
        Economy.noteOffering(me, put);
        HumanChat.say(server, me, HumanChat.pick("for a good harvest", "may the crops grow tall", "keep the creepers away pls",
                "thanks for the diamonds, more pls", "for luck in the mines", "an offering for " + t.name));
        return true;
    }

    // ------------------------------------------------------------------------
    // Town life: what a bot does in the town when it's free (brain thread)
    // ------------------------------------------------------------------------

    private static final Map<String, Long> NEXT = new ConcurrentHashMap<>();
    private static volatile long nextFounding = 0;
    private static final long STARTED = System.currentTimeMillis();
    private static final Map<String, Long> ANNOUNCED = new ConcurrentHashMap<>();
    private static final Map<String, String[]> TENDING = new ConcurrentHashMap<>();

    /** One bot at a time tends each of the town's farms. */
    static boolean tendClaim(Blueprints.Build rec, String me) {
        String key = rec.file() + "|" + rec.x() + "|" + rec.y() + "|" + rec.z();
        long now = System.currentTimeMillis();
        String[] c = TENDING.get(key);
        if (c != null && !c[0].equals(me) && now - Long.parseLong(c[1]) < 10 * 60_000L) return false;
        TENDING.put(key, new String[]{me, String.valueOf(now)});
        return true;
    }

    /** Things to do in town: the temple, the shop, the next piece of the town, helping someone. True if it did something. */
    static boolean tick(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        if (!HumanConfig.get().autoCity) return false;
        String me = b.name;
        long now = System.currentTimeMillis();
        if (now < NEXT.getOrDefault(me, 0L)) return false;
        Town t = town();
        if (t == null) return maybeFound(server, bot, b);
        boolean here = onServer(server, () -> t.dim.equals(Home.dim(bot.level()))
                && bot.blockPosition().distSqr(t.center()) < 400 * 400, false);
        if (!here) return false;
        return doTownThing(server, bot, b, t, false);
    }

    private static boolean doTownThing(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Town t, boolean asked)
            throws InterruptedException {
        String me = b.name;
        long now = System.currentTimeMillis();
        if (town() != t) return false; // forgotten meanwhile
        // once a day: the temple
        if (t.built("temple") != null && onServer(server, () -> offeringDue(bot), false) && offer(server, bot, b, t, false)) return true;
        // the shop: stock it and empty the till now and then
        if (Economy.upkeepDue(me) && Economy.restock(server, bot, b, t)) return true;
        // the next piece of the town
        Set<String> online = new java.util.HashSet<>();
        for (String n : onServer(server, () -> botNames(server), List.<String>of())) online.add(n.toLowerCase(Locale.ROOT));
        Plot p = claim(t, me, online);
        if (p != null) {
            String key = me + "#" + p.id;
            if (ANNOUNCED.putIfAbsent(key, now) == null) {
                SurvivalBrain.maybeSay(server, b, HumanChat.pick("i'll take " + p.label(), "working on " + p.label() + " now",
                        "gonna build " + p.label()), p.road() ? 0.4 : 1.0);
            }
            int r = work(server, bot, b, t, p);
            if (r != 0) NEXT.put(me, System.currentTimeMillis() + (r > 0 ? 5_000L : 60_000L));
            return true;
        }
        // nothing free to build: fetch what someone else is short of
        Need n = takeNeed(me);
        if (n != null) {
            help(server, bot, b, t, n);
            return true;
        }
        NEXT.put(me, now + (asked ? 30_000L : 4 * 60_000L));
        return false;
    }

    /** Founds a town on its own once everyone has a finished home and things have settled. Brain thread. */
    private static boolean maybeFound(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        long now = System.currentTimeMillis();
        if (now < nextFounding || now - STARTED < 20 * 60_000L) return false;
        if (forgotten) return false;
        nextFounding = now + 20 * 60_000L;
        boolean ready = onServer(server, () -> {
            if (!Home.overworld(bot.level())) return false;
            for (ServerPlayer p : bots(server)) {
                if (Home.get(p) == null) return false;
                if (Blueprints.unfinished(p.getName().getString().toLowerCase(Locale.ROOT)) != null) return false;
            }
            return true;
        }, false);
        if (!ready) return false;
        return found(server, bot, b, null, null) != null;
    }

    /** Job: keep working on the town till there's nothing left to do (or told to stop). */
    static void workJob(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        Town t = town();
        if (t == null) return;
        // asked to work on it: give the pieces that got skipped another go
        boolean retry = false;
        for (Plot p : t.plots) {
            if (!p.state.equals("blocked")) continue;
            p.state = "todo";
            PLOT_FAILS.remove(p.id);
            SOFT_FAILS.remove(p.id);
            PLOT_BACKOFF.remove(p.id);
            retry = true;
        }
        if (retry) save();
        int idle = 0;
        while (SurvivalBrain.canContinue(b) && idle < 3 && town() == t) {
            NEXT.remove(b.name);
            if (doTownThing(server, bot, b, t, true)) idle = 0;
            else {
                idle++;
                SurvivalBrain.sleep(20_000L);
            }
        }
        if (SurvivalBrain.canContinue(b)) HumanChat.say(server, b.name, "nothing left i can do on the town right now. "
                + t.done() + "/" + t.plots.size() + " done");
    }

    // ------------------------------------------------------------------------
    // Talking about it
    // ------------------------------------------------------------------------

    /** "Diamondvale: 6/30 done. building: the temple (Bro). next: a shop, ...". */
    static String describe() {
        Town t = town();
        if (t == null) return "we don't have a town yet. say \"let's build a city\" and we'll start one";
        StringBuilder sb = new StringBuilder(t.name).append(" (plaza at ").append(t.cx).append(' ').append(t.cy).append(' ').append(t.cz)
                .append("): ").append(t.done()).append('/').append(t.plots.size()).append(" done");
        List<String> on = new ArrayList<>(), next = new ArrayList<>(), built = new ArrayList<>();
        for (Plot p : t.plots) {
            if (p.road()) continue;
            if (p.done()) built.add(p.label().replaceFirst("^(the|a) ", ""));
            else if (p.state.equals("todo")) {
                String who = builderOf(p);
                if (who != null) on.add(p.label() + " (" + who + ")");
                else if (next.size() < 3) next.add(p.label());
            }
        }
        if (!built.isEmpty()) sb.append(". built: ").append(String.join(", ", built.subList(0, Math.min(6, built.size()))));
        if (!on.isEmpty()) sb.append(". working on ").append(String.join(", ", on));
        if (!next.isEmpty()) sb.append(". next up: ").append(String.join(", ", next));
        if (!NEEDS.isEmpty()) {
            List<String> ns = new ArrayList<>();
            for (Need n : NEEDS) if (ns.size() < 3) ns.add(n.count() + " " + BlueprintBuilder.label(n.item()));
            sb.append(". short of ").append(String.join(", ", ns));
        }
        return sb.toString();
    }

    /** A line for the language model about the town. */
    static String persona() {
        Town t = town();
        if (t == null) return "";
        StringBuilder sb = new StringBuilder("You and the other companions are building a town together called ").append(t.name)
                .append(" (plaza at ").append(t.cx).append(' ').append(t.cy).append(' ').append(t.cz).append("), ")
                .append(t.done()).append(" of ").append(t.plots.size()).append(" pieces done.");
        List<String> built = new ArrayList<>();
        for (Plot p : t.plots) if (!p.road() && p.done()) built.add(p.label());
        if (!built.isEmpty()) sb.append(" Built so far: ").append(String.join(", ", built)).append('.');
        sb.append(" Each of you visits the temple once a day to leave food on the altar.");
        return sb.toString();
    }

    /** Where something in town is. */
    static String where(String what) {
        Town t = town();
        if (t == null) return "we don't have a town yet";
        if (what == null || what.matches("city|town|plaza|square|town square|middle")) {
            return t.name + "'s plaza is at " + t.cx + " " + t.cy + " " + t.cz;
        }
        String kind = what.replace("amphitheater", "amphitheatre").replace("theatre", "amphitheatre").replace("theater", "amphitheatre")
                .replace("market", "mall").replace("storage", "warehouse").replace("farms", "farm").replace("church", "temple");
        if (kind.equals("amphiamphitheatre")) kind = "amphitheatre";
        for (Plot p : t.plots) {
            if (p.kind.equals(kind) || (kind.endsWith("shop") && p.kind.equals("shop") && kind.startsWith(p.owner.toLowerCase(Locale.ROOT)))) {
                return p.label() + " is at " + p.midX() + " " + (p.y == NO_Y ? t.cy : p.y) + " " + p.midZ()
                        + (p.done() ? "" : " (not built yet)");
            }
        }
        return "there's no " + what + " in " + t.name + " yet";
    }

    // ------------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------------

    private static final Pattern FOUND = Pattern.compile(
            "^(?:(?:ok|okay|hey|yo|guys|pls|please|can you|could you|you|y'?all|let'?s|lets|we should|go|i want you to|i want to)\\s+)*"
            + "(?:build|make|start|found|create|set up)\\s+(?:a|an|our|the|a new|us a)?\\s*(?:big |massive |huge |small |little )?"
            + "(?:city|town|village)(?:\\s+(here|right here|over here))?(?:\\s+(?:called|named)\\s+([a-z0-9' ]{2,24}))?[!.\\s]*$");
    private static final Pattern STATUS = Pattern.compile(
            "\\b(how'?s|how is|how are) (the|our) (city|town)( going| coming( along)?| looking)?|\\b(city|town) (status|progress)\\b"
            + "|what'?s left (in|for|to do in|to build in) the (city|town)|what (does|do) the (city|town) (need|have)");
    private static final Pattern WORK = Pattern.compile(
            "^(?:(?:ok|okay|pls|please|can you|could you|you|go|now|let'?s|lets)\\s+)*(work on|help (build|with)|keep building|continue( building)?|finish|get back to) (the |our )?(city|town)( please| pls)?[!.\\s]*$");
    private static final Pattern WHERE = Pattern.compile(
            "\\bwhere('?s| is| are)( the| our| your)? (city|town|plaza|square|temple|mall|market|amphitheatre|amphitheater|theatre|theater|warehouse|storage|farms?|shop)\\b");
    private static final Pattern FORGET = Pattern.compile("^(forget( about)?|cancel|scrap|abandon|delete) (the |our )?(city|town)[!.\\s]*$");
    private static final Pattern OFFER = Pattern.compile(
            "^(?:(?:ok|okay|pls|please|can you|could you|you|go|now|let'?s|lets)\\s+)*(go )?(pray|make an offering|make (an )?offerings?|offer (some )?food|go to the temple|visit the temple)\\b");

    /** What to do about a message about the town, or null if it isn't one. {@code player}/{@code bot} null: just checking. */
    static Blueprints.Ask parse(String m, ServerPlayer player, ServerPlayer bot) {
        if (m == null || m.length() > 90) return null;
        String t = m.toLowerCase(Locale.ROOT).trim();
        Matcher f = FOUND.matcher(t);
        if (f.find()) {
            boolean here = f.group(1) != null;
            String name = f.group(2);
            if (player == null) return new Blueprints.Ask(() -> "", null);
            BlockPos at = here ? player.blockPosition() : null;
            Town existing = town();
            if (existing != null) {
                return new Blueprints.Ask(null, new MiningSkills.Request("work on the town",
                        "we've got " + existing.name + " going already (" + existing.done() + "/" + existing.plots.size() + " done), back to work on it",
                        (s, bt, bb) -> workJob(s, bt, bb)));
            }
            return new Blueprints.Ask(null, new MiningSkills.Request("found a town",
                    here ? "a town right here? love it, let me lay it out" : "a town! let me find a good spot near our houses",
                    (s, bt, bb) -> {
                        found(s, bt, bb, at, name);
                        if (town() != null) workJob(s, bt, bb); // founded by this one or another: get building
                    }));
        }
        if (FORGET.matcher(t).find()) {
            return new Blueprints.Ask(() -> {
                Town x = town();
                if (x == null) return "there's no town to forget";
                if (player == null) return "";
                forget();
                return "ok, forgot about " + x.name + ". what's built stays where it is";
            }, null);
        }
        if (STATUS.matcher(t).find()) return new Blueprints.Ask(City::describe, null);
        Matcher w = WHERE.matcher(t);
        if (w.find()) {
            String what = w.group(3);
            if (what.equals("shop") && bot != null) {
                Town x = town();
                Plot s = x == null ? null : x.shopOf(bot.getName().getString());
                return new Blueprints.Ask(() -> s == null ? "i don't have a shop yet" : "my shop is at " + s.midX() + " "
                        + (s.y == NO_Y ? x.cy : s.y) + " " + s.midZ() + (s.done() ? "" : " (still building it)"), null);
            }
            return new Blueprints.Ask(() -> where(what), null);
        }
        if (WORK.matcher(t).find()) {
            if (town() == null) return new Blueprints.Ask(() -> "we don't have a town yet. say \"let's build a city\" first", null);
            return new Blueprints.Ask(null, new MiningSkills.Request("work on the town",
                    HumanChat.pick("on it, back to the town", "ok, building the town", "sure, let's get the town done"),
                    (s, bt, bb) -> workJob(s, bt, bb)));
        }
        if (OFFER.matcher(t).find()) {
            return new Blueprints.Ask(null, new MiningSkills.Request("make an offering at the temple",
                    HumanChat.pick("sure, heading to the temple", "ok, off to the temple"),
                    (s, bt, bb) -> {
                        Town x = town();
                        if (x == null) HumanChat.say(s, bb.name, "we don't have a town (or a temple) yet");
                        else offer(s, bt, bb, x, true);
                    }));
        }
        return null;
    }
}
