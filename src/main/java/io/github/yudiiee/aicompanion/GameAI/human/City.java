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

    /** Road pieces between two towns (the "arm" of their plots). */
    static final int HIGHWAY = 4;

    static final class Town {
        final String dim, name, founder;
        final int cx, cz;
        volatile int cy;
        final long founded;
        /** Its number in the network (plots are numbered from id * 1000). */
        final int id;
        /** The avenue with the temple at its end (the arena is at the opposite end). */
        final int templeArm;
        /** The town it was built out from (-1: the first), and the height the road from there starts at. */
        final int parent, fromY;
        /** Avenues left open at the end for roads to other towns, and the ones already joined up. */
        final Set<Integer> gates = ConcurrentHashMap.newKeySet(), linked = ConcurrentHashMap.newKeySet();
        final List<Plot> plots = new CopyOnWriteArrayList<>();

        Town(String dim, int cx, int cy, int cz, String name, String founder, long founded) {
            this(dim, cx, cy, cz, name, founder, founded, 0, 0, -1, cy);
        }

        Town(String dim, int cx, int cy, int cz, String name, String founder, long founded, int id, int templeArm, int parent,
             int fromY) {
            this.dim = dim;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.name = name;
            this.founder = founder;
            this.founded = founded;
            this.id = id;
            this.templeArm = templeArm;
            this.parent = parent;
            this.fromY = fromY;
        }

        String line() {
            return "town|" + dim + "|" + cx + "|" + cy + "|" + cz + "|" + name.replace('|', ' ') + "|" + founder + "|" + founded + "|" + id
                    + "|" + templeArm + "|" + set(gates) + "|" + set(linked) + "|" + parent + "|" + fromY;
        }

        private static String set(Set<Integer> s) {
            if (s.isEmpty()) return "-";
            List<String> out = new ArrayList<>();
            for (int i : new TreeSet<>(s)) out.add(String.valueOf(i));
            return String.join(",", out);
        }

        /** A saved town line; {@code index}: its place in the file (for towns saved before there were several). */
        static Town parse(String line, int index) {
            String[] x = line.split("\\|", -1);
            if (x.length < 8) return null;
            try {
                int cy = Integer.parseInt(x[3]);
                if (x.length < 14) {
                    // a town from before the network: temple north, mall east, amphitheatre south; the west end is open
                    Town t = new Town(x[1], Integer.parseInt(x[2]), cy, Integer.parseInt(x[4]), x[5], x[6], Long.parseLong(x[7]),
                            index, 0, -1, cy);
                    t.gates.add(3);
                    return t;
                }
                Town t = new Town(x[1], Integer.parseInt(x[2]), cy, Integer.parseInt(x[4]), x[5], x[6], Long.parseLong(x[7]),
                        Integer.parseInt(x[8]), Integer.parseInt(x[9]), Integer.parseInt(x[12]), Integer.parseInt(x[13]));
                for (String g : x[10].split(",")) if (g.matches("[0-3]")) t.gates.add(Integer.parseInt(g));
                for (String g : x[11].split(",")) if (g.matches("[0-3]")) t.linked.add(Integer.parseInt(g));
                return t;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** How far out an avenue's road reaches from the plaza's middle (0 if it has none). */
        int reach(int arm) {
            int[] v = CityPlan.vec(CityPlan.DIRS[arm]);
            int far = 0;
            for (Plot p : plots) {
                if (!p.road() || p.arm != arm) continue;
                int a = v[0] > 0 ? p.x0 + p.w - 1 - cx : v[0] < 0 ? cx - p.x0 : v[1] > 0 ? p.z0 + p.l - 1 - cz : cz - p.z0;
                far = Math.max(far, a);
            }
            return far;
        }

        /** Built and to build, in that order of the town's own pieces (roads to other towns don't count). */
        double progress() {
            int all = 0, done = 0;
            for (Plot p : plots) {
                if (p.arm == HIGHWAY) continue;
                all++;
                if (p.done()) done++;
            }
            return all == 0 ? 1 : (double) done / all;
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

    /** Every town, oldest first. */
    static final List<Town> TOWNS = new CopyOnWriteArrayList<>();
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
        TOWNS.clear();
        forgotten = false;
        if (!Files.isRegularFile(f)) return;
        try {
            Town t = null;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.startsWith("forgotten")) forgotten = true;
                if (line.startsWith("town|")) {
                    t = Town.parse(line, TOWNS.size());
                    if (t != null) TOWNS.add(t);
                } else if (line.startsWith("plot|") && t != null) {
                    Plot p = Plot.parse(line);
                    if (p != null) t.plots.add(p);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[city] couldn't read {}: {}", f, e.toString());
        }
    }

    static synchronized void save() {
        Path f = file();
        if (f == null) return;
        try {
            if (TOWNS.isEmpty()) {
                // a town somebody told them to forget: they don't go founding another by themselves
                if (forgotten) Files.write(f, List.of("forgotten"), StandardCharsets.UTF_8);
                else Files.deleteIfExists(f);
                return;
            }
            List<String> out = new ArrayList<>();
            for (Town t : TOWNS) {
                out.add(t.line());
                for (Plot p : t.plots) out.add(p.line());
            }
            Files.write(f, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[city] couldn't save {}: {}", f, e.toString());
        }
    }

    /** The newest town, or null if there isn't one yet. */
    static Town town() {
        load();
        return TOWNS.isEmpty() ? null : TOWNS.get(TOWNS.size() - 1);
    }

    /** Every town, oldest first. */
    static List<Town> towns() {
        load();
        return TOWNS;
    }

    /** The newest town in this dimension, or null. */
    static Town townIn(ServerLevel level) {
        String dim = Home.dim(level);
        List<Town> all = towns();
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).dim.equals(dim)) return all.get(i);
        return null;
    }

    /** The town nearest a spot in this dimension, or null. */
    static Town nearest(ServerLevel level, BlockPos at) {
        String dim = Home.dim(level);
        Town best = null;
        double bd = Double.MAX_VALUE;
        for (Town t : towns()) {
            if (!t.dim.equals(dim)) continue;
            double d = t.center().distSqr(at);
            if (d < bd) { bd = d; best = t; }
        }
        return best;
    }

    /** The town with this bot's shop in it (its finished one nearest {@code at}), else the town nearest. */
    static Town shopTown(ServerLevel level, BlockPos at, String bot) {
        String dim = Home.dim(level);
        Town best = null;
        double bd = Double.MAX_VALUE;
        for (Town t : towns()) {
            if (!t.dim.equals(dim)) continue;
            Plot s = t.shopOf(bot);
            if (s == null || !s.done()) continue;
            double d = t.center().distSqr(at);
            if (d < bd) { bd = d; best = t; }
        }
        return best != null ? best : nearest(level, at);
    }

    /** The town a piece belongs to. */
    static Town townOf(Plot p) {
        for (Town t : towns()) if (t.plots.contains(p)) return t;
        return null;
    }

    /** A finished building of a kind in the town nearest {@code at} that has one (within {@code range}), or null. */
    static Plot nearestBuilt(ServerLevel level, BlockPos at, String kind, int range) {
        String dim = Home.dim(level);
        Plot best = null;
        double bd = (double) range * range;
        for (Town t : towns()) {
            if (!t.dim.equals(dim)) continue;
            Plot p = t.built(kind);
            if (p == null) continue;
            double d = new BlockPos(p.midX(), p.y == NO_Y ? t.cy : p.y, p.midZ()).distSqr(at);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    // ------------------------------------------------------------------------
    // The designs it's built from
    // ------------------------------------------------------------------------

    static final String[] KINDS = {"plaza", "warehouse", "shop", "farm", "temple", "arena", "mall", "amphitheatre"};

    /** The design files for a kind of building, the preferred one first. */
    static String[] filesFor(String kind) {
        return switch (kind) {
            case "temple" -> new String[]{"town_temple.nbt", "city_temple.nbt"};
            case "arena" -> new String[]{"town_arena.nbt"};
            default -> new String[]{"city_" + kind + ".nbt"};
        };
    }

    /** The town's designs by kind (from the schematics folder), plus up to four houses. Job thread (reads files). */
    static Map<String, CityPlan.Design> designs(List<CityPlan.Design> houses) {
        Map<String, CityPlan.Design> out = new LinkedHashMap<>();
        for (String k : KINDS) {
            Blueprints.Entry e = null;
            for (String f : filesFor(k)) {
                e = Blueprints.byFile(f);
                if (e != null) break;
            }
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
    /** Within {@code margin} of any piece of a town in this dimension? */
    static boolean overlapsTown(String dim, int x0, int z0, int x1, int z1, int margin) {
        for (Town t : towns()) {
            if (!t.dim.equals(dim)) continue;
            for (Plot p : t.plots) {
                if (p.x0 - margin <= x1 && p.x0 + p.w - 1 + margin >= x0 && p.z0 - margin <= z1 && p.z0 + p.l - 1 + margin >= z0) return true;
            }
        }
        return false;
    }

    /** Too little loaded to judge (a rating with this mark isn't a real one). */
    static boolean unknown(int[] r) {
        return r != null && r.length > 4 && r[4] == 1;
    }

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
        if (overlapsTown(dim, bx0, bz0, bx1, bz1, 16)) return null;
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
        // too little of it loaded to tell: say so (that's not the same as a bad spot)
        if (samples == 0 || ys.size() + water < samples / 3) return new int[]{cx, nearY, cz, Integer.MAX_VALUE, 1};
        if (ys.isEmpty()) return null;
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
    static Town found(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, BlockPos at, String name)
            throws InterruptedException {
        Town existing = onServer(server, () -> townIn(bot.level()), null);
        if (existing != null && at == null) {
            // there's a town already: the next one goes up down a road from it
            return expand(server, bot, b, name);
        }
        List<CityPlan.Design> houses = new ArrayList<>();
        Map<String, CityPlan.Design> d = designs(houses);
        CityPlan.Design plaza = d.get("plaza");
        if (plaza == null) {
            HumanChat.say(server, b.name, "can't find the town designs (city_plaza and the rest) in the schematics folder");
            return null;
        }
        List<String> names = onServer(server, () -> botNames(server), List.of(b.name));
        List<String> owners = owners(b.name, names);
        int templeArm = 0;
        List<CityPlan.Lot> lots = CityPlan.layout(plaza, CityPlan.program(d, Math.max(3, owners.size()), houses, templeArm));
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
        if (overlapsTown(dim, site[0] + bounds[0], site[2] + bounds[1], site[0] + bounds[2], site[2] + bounds[3], 16)) {
            Town in = onServer(server, () -> nearest(bot.level(), new BlockPos(site[0], site[1], site[2])), null);
            HumanChat.say(server, b.name, "that's right on top of " + (in == null ? "one of our towns" : in.name)
                    + ". a new town needs about " + (bounds[2] - bounds[0]) + " blocks clear each way, try further out");
            return null;
        }
        Town t = create(dim, site, name, b.name, templeArm, -1, site[1], lots, owners, List.of(), null, -1);
        if (t == null) return null;
        LOGGER.info("[city] {} founded {} at {} {} {} ({} pieces)", b.name, t.name, site[0], site[1], site[2], t.plots.size());
        announce(server, t, b.name, names, null);
        return t;
    }

    /** Whose shop is whose: the one who founded the town first, then the others. */
    private static List<String> owners(String founder, List<String> names) {
        List<String> owners = new ArrayList<>();
        owners.add(founder);
        for (String n : names) if (!n.equalsIgnoreCase(founder)) owners.add(n);
        return owners;
    }

    /** Makes a town from a layout (and the road out to it, if any) and remembers it. Null if it can't. */
    private static Town create(String dim, int[] site, String name, String founder, int templeArm, int parent, int fromY,
                               List<CityPlan.Lot> lots, List<String> owners, List<Plot> highway, Town from, int gate) {
        synchronized (City.class) {
            load();
            int id = 0;
            for (Town o : TOWNS) id = Math.max(id, o.id + 1);
            String townName = name != null && !name.isBlank() ? cap(name.trim()) : freshName();
            Town t = new Town(dim, site[0], site[1], site[2], townName, founder, System.currentTimeMillis(), id, templeArm, parent, fromY);
            for (int g : CityPlan.gates(templeArm)) t.gates.add(g);
            int n = id * 1000;
            for (Plot h : highway) {
                t.plots.add(new Plot(n++, h.kind, h.file, h.x0, h.z0, h.w, h.l, h.face, h.rot, HIGHWAY, NO_Y, "", "todo"));
            }
            int shopIndex = 0;
            for (CityPlan.Lot lot : lots) {
                String owner = "";
                if (lot.kind().equals("shop")) {
                    owner = shopIndex < owners.size() ? owners.get(shopIndex) : "";
                    shopIndex++;
                }
                int y = lot.kind().equals("plaza") ? site[1] : NO_Y;
                t.plots.add(new Plot(n++, lot.kind(), lot.file(), site[0] + lot.x0(), site[2] + lot.z0(), lot.w(), lot.l(), lot.face(),
                        lot.rot(), lot.arm(), y, owner, "todo"));
            }
            if (from != null && gate >= 0) {
                from.linked.add(gate);
                t.linked.add((gate + 2) % 4);
            }
            TOWNS.add(t);
            forgotten = false;
            save();
            return t;
        }
    }

    /** A name no town has yet. */
    private static String freshName() {
        List<String> free = new ArrayList<>();
        for (String n : NAMES) {
            boolean used = false;
            for (Town t : TOWNS) if (t.name.equalsIgnoreCase(n)) used = true;
            if (!used) free.add(n);
        }
        if (!free.isEmpty()) return free.get(RNG.nextInt(free.size()));
        return NAMES[RNG.nextInt(NAMES.length)] + " " + (TOWNS.size() + 1);
    }

    // ------------------------------------------------------------------------
    // The network: each new town goes up at the end of a road out of one already there
    // ------------------------------------------------------------------------

    /** Gates that led nowhere (water, somebody's builds): not tried again this session. */
    private static final Set<String> DEAD_GATES = ConcurrentHashMap.newKeySet();
    /** Gates somebody is looking down right now (so two bots don't put two towns in one spot). */
    private static final Set<String> BUSY_GATES = ConcurrentHashMap.newKeySet();
    private static volatile long lastNoRoomSaid = 0;

    /**
     * The next town of the network. It looks at every town's open avenue ends (newest town first),
     * and for each tries spots straight on down that avenue, from a short road to a long one, for
     * somewhere dry and flattish with nobody's builds in the way. The new town is turned so one of
     * its own open avenues faces back down the road, with the temple and the arena on the other
     * axis; the road between them (5 wide, street lamps) is the first thing built. Job thread.
     */
    static Town expand(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, String name) throws InterruptedException {
        List<CityPlan.Design> houses = new ArrayList<>();
        Map<String, CityPlan.Design> d = designs(houses);
        CityPlan.Design plaza = d.get("plaza");
        if (plaza == null) {
            HumanChat.say(server, b.name, "can't find the town designs (city_plaza and the rest) in the schematics folder");
            return null;
        }
        List<String> names = onServer(server, () -> botNames(server), List.of(b.name));
        List<String> owners = owners(b.name, names);
        String dim = onServer(server, () -> Home.dim(bot.level()), "");
        List<Town> mine = new ArrayList<>();
        for (Town t : towns()) if (t.dim.equals(dim)) mine.add(t);
        Collections.reverse(mine);
        for (Town from : mine) {
            for (int g : new TreeSet<>(from.gates)) {
                String key = from.id + ":" + g;
                if (from.linked.contains(g) || DEAD_GATES.contains(key)) continue;
                if (!BUSY_GATES.add(key)) continue;
                try {
                int in = (g + 2) % 4;
                // the temple on one side of the road coming in, the arena on the other; which side alternates
                int templeArm = (from.id % 2 == 0) ? (in + 1) % 4 : (in + 3) % 4;
                List<CityPlan.Lot> lots = CityPlan.layout(plaza, CityPlan.program(d, Math.max(3, owners.size()), houses, templeArm));
                int[] bounds = CityPlan.bounds(lots);
                int reachNew = reach(lots, in), reachOld = from.reach(g);
                if (reachOld <= 0) reachOld = Math.max(plaza.sx(), plaza.sz()) / 2;
                int[] v = CityPlan.vec(CityPlan.DIRS[g]);
                int[] best = null;
                int bestGap = 0;
                boolean unsure = false;
                // twice: from where it stands, then (if too little of it was loaded to tell) after walking out that way
                for (int look = 0; look < 2 && best == null; look++) {
                    if (look == 1) {
                        if (!unsure || !SurvivalBrain.canContinue(b)) break;
                        int out = reachOld + 160;
                        BlockPos toward = new BlockPos(from.cx + v[0] * out, from.cy, from.cz + v[1] * out);
                        SurvivalBrain.maybeSay(server, b, "gonna go look at the land " + CityPlan.DIRS[g] + " of " + from.name, 0.7);
                        walk(server, bot, b, toward, 8, 200);
                        unsure = false;
                    }
                    for (int gap = 64; gap <= 384; gap += 32) {
                        int[] c = spotFor(from, g, reachOld, gap, reachNew);
                        int cx = c[0], cz = c[1];
                        final int fcy = from.cy;
                        int[] r = onServer(server, () -> rateSite(bot.level(), cx, cz, fcy, bounds), null);
                        if (r == null) continue;
                        if (unknown(r)) { unsure = true; continue; }
                        r[3] += gap / 16; // shorter roads are better
                        if (best == null || r[3] < best[3]) { best = r; bestGap = gap; }
                        if (r[3] < 50) break;
                    }
                }
                if (best == null) {
                    if (!unsure) DEAD_GATES.add(key); // water or builds all the way: don't keep looking there
                    continue;
                }
                if (from.linked.contains(g)) continue;
                List<Plot> road = highway(from, g, reachOld, bestGap);
                Town t = create(dim, best, name, b.name, templeArm, from.id, from.cy, lots, owners, road, from, g);
                if (t == null) return null;
                LOGGER.info("[city] {} started {} {} of {} ({} block road)", b.name, t.name, CityPlan.DIRS[g], from.name, bestGap);
                announce(server, t, b.name, names, from);
                return t;
                } finally {
                    BUSY_GATES.remove(key);
                }
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastNoRoomSaid > 60 * 60_000L) {
            lastNoRoomSaid = now;
            HumanChat.say(server, b.name, "couldn't find room for another town down any of our roads (water, builds, or too far to see)."
                    + " stand somewhere clear and say \"build a city here\"");
        }
        return null;
    }

    /**
     * Where the next town's plaza goes: straight on down avenue {@code g}, so that the road from the
     * end of this town's avenue ({@code reachOld} out), {@code gap} blocks long, ends right where the
     * new town's own avenue back ({@code reachNew} long) begins. {x, z}.
     */
    static int[] spotFor(Town from, int g, int reachOld, int gap, int reachNew) {
        int[] v = CityPlan.vec(CityPlan.DIRS[g]);
        int dist = reachOld + gap + reachNew + 1;
        return new int[]{from.cx + v[0] * dist, from.cz + v[1] * dist};
    }

    /** How far out an avenue reaches in a layout (local coordinates). */
    static int reach(List<CityPlan.Lot> lots, int arm) {
        int[] v = CityPlan.vec(CityPlan.DIRS[arm]);
        int far = 0;
        for (CityPlan.Lot l : lots) {
            if (!l.road() || l.arm() != arm) continue;
            int a = v[0] > 0 ? l.x1() : v[0] < 0 ? -l.x0() : v[1] > 0 ? l.z1() : -l.z0();
            far = Math.max(far, a);
        }
        return far;
    }

    /** The road from the end of a town's avenue out {@code gap} blocks, in pieces (world coordinates). */
    static List<Plot> highway(Town from, int arm, int reachOld, int gap) {
        List<Plot> out = new ArrayList<>();
        for (int a = reachOld + 1; a <= reachOld + gap; a += CityPlan.SEGMENT) {
            int a1 = Math.min(reachOld + gap, a + CityPlan.SEGMENT - 1);
            int[] bx = CityPlan.box(arm, a, a1, -(CityPlan.ROAD_HALF + 1), CityPlan.ROAD_HALF + 1);
            out.add(new Plot(0, "road", "", from.cx + bx[0], from.cz + bx[1], bx[2] - bx[0] + 1, bx[3] - bx[1] + 1,
                    CityPlan.DIRS[arm], 0, HIGHWAY, NO_Y, "", "todo"));
        }
        return out;
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
    private static void announce(MinecraftServer server, Town t, String founder, List<String> names, Town from) {
        List<String> others = new ArrayList<>();
        for (String n : names) if (!n.equalsIgnoreCase(founder)) others.add(n);
        String to = others.isEmpty() ? "" : String.join(", ", others) + ", ";
        if (from != null) {
            String way = CityPlan.dirOf(Integer.signum(t.cx - from.cx), Integer.signum(t.cz - from.cz));
            HumanChat.say(server, founder, to + "time for the next town! " + t.name + ", " + way + " of " + from.name + " (plaza at " + t.cx + " " + t.cy + " " + t.cz + "). first the road out there, then the plaza, a temple"
                    + " and a pvp arena like every town gets, shops, farms, the lot");
        } else {
            HumanChat.say(server, founder, to + "let's build a town! calling it " + t.name + ". the plaza goes at " + t.cx + " " + t.cy + " " + t.cz
                    + ". plaza and roads first, then a warehouse for all our stuff, a shop each, farms, a temple, a pvp arena, a mall and an amphitheatre");
        }
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
                if (r == null || unknown(r)) continue;
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
        TOWNS.clear();
        DEAD_GATES.clear();
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
        return claim(List.of(t), me, online);
    }

    /** Same, over several towns (the older ones' pieces first). */
    static synchronized Plot claim(List<Town> ts, String me, Set<String> online) {
        long now = System.currentTimeMillis();
        ACTIVE.values().removeIf(a -> now - a.beat() > 3 * 60 * 60_000L); // a bot that died mid-build
        List<Plot> all = new ArrayList<>();
        for (Town t : ts) all.addAll(t.plots);
        for (Plot p : all) {
            Claim c = CLAIMS.get(p.id);
            if (c != null && c.bot().equals(me) && p.state.equals("todo") && now >= PLOT_BACKOFF.getOrDefault(p.id, 0L)) {
                CLAIMS.put(p.id, new Claim(me, now));
                return p;
            }
        }
        for (Plot p : all) {
            Town t = townOf(p);
            if (t == null) continue;
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
            finished(server, bot, b, t, p, placed);
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

    private static void finished(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Town t, Plot p,
                                 BlueprintBuilder.Placed placed) throws InterruptedException {
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
            if (p.arm == HIGHWAY) {
                boolean last = true;
                for (Plot o : t.plots) if (o.arm == HIGHWAY && !o.done()) last = false;
                Town from = null;
                for (Town o : towns()) if (o.id == t.parent) from = o;
                if (last) HumanChat.say(server, b.name, "the road to " + t.name + " is done" + (from == null ? "" : ", you can walk there from " + from.name));
                else if (RNG.nextInt(4) == 0) SurvivalBrain.maybeSay(server, b, "another bit of the road to " + t.name + " done", 0.6);
                return;
            }
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
            case "arena" -> ". want a duel? say \"fight me\" and meet me there";
            default -> "";
        };
        HumanChat.say(server, b.name, p.label() + " is finished" + extra + " (" + t.name + ": " + done + "/" + all + " done)");
        if (p.kind.equals("temple")) {
            NEXT_PORTAL.put(p.id, System.currentTimeMillis() + 20 * 60_000L);
            lightPortal(server, bot, b, placed, true);
        }
    }

    /** A design with a nether portal in it (the temple): once the frame is up, light it. Job thread. */
    static boolean lightPortal(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, BlueprintBuilder.Placed placed,
                               boolean talk) throws InterruptedException {
        if (placed == null) return false;
        Schematic s = placed.plan();
        List<BlockPos> cells = new ArrayList<>();
        for (int y = 0; y < s.sy; y++) for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++) {
            State st = s.at(x, y, z);
            if (st != null && st.path().equals("nether_portal")) cells.add(placed.world(x, y, z));
        }
        if (cells.isEmpty()) return false;
        // lit already? (or the frame isn't real obsidian yet: a stand-in frame can't hold a portal)
        boolean[] state = onServer(server, () -> {
            boolean lit = false, frame = true;
            for (BlockPos q : cells) {
                if (SurvivalBrain.blockPath(bot.level().getBlockState(q)).equals("nether_portal")) lit = true;
            }
            for (BlockPos q : cells) {
                for (BlockPos n : new BlockPos[]{q.below(), q.above(), q.offset(0, 0, -1), q.offset(0, 0, 1), q.offset(1, 0, 0), q.offset(-1, 0, 0)}) {
                    State plan = placed.at(n);
                    if (plan != null && plan.path().equals("obsidian")
                            && !SurvivalBrain.blockPath(bot.level().getBlockState(n)).equals("obsidian")) frame = false;
                }
            }
            return new boolean[]{lit, frame};
        }, new boolean[]{true, false});
        if (state[0]) return false;
        if (!state[1]) {
            if (talk) HumanChat.say(server, b.name, "the temple's portal frame needs real obsidian before it'll light. put some in a chest and i'll swap it in");
            return false;
        }
        if (onServer(server, () -> Gathering.countOf(bot, "flint_and_steel"::equals), 0) == 0) {
            Storage.withdraw(server, bot, b, "flint_and_steel"::equals, 1, null);
            if (onServer(server, () -> Gathering.countOf(bot, "flint_and_steel"::equals), 0) == 0) {
                Storage.withdraw(server, bot, b, "flint"::equals, 1, null);
                if (onServer(server, () -> Gathering.countOf(bot, "flint"::equals), 0) > 0) {
                    BlueprintBuilder.make(server, bot, b, "iron_ingot", 1, 0, it -> it.equals("flint"));
                }
                onServer(server, () -> {
                    if (Gathering.countOf(bot, "flint"::equals) > 0 && Gathering.countOf(bot, "iron_ingot"::equals) > 0) {
                        SurvivalBrain.take(bot, "flint"::equals, 1);
                        SurvivalBrain.take(bot, "iron_ingot"::equals, 1);
                        SurvivalBrain.give(bot, "flint_and_steel", 1);
                    }
                    return true;
                }, false);
            }
        }
        if (onServer(server, () -> Gathering.countOf(bot, "flint_and_steel"::equals), 0) == 0) {
            if (talk) HumanChat.say(server, b.name, "the temple's portal is ready to light. bring me a flint and steel (or some flint) and i'll do it");
            return false;
        }
        if (!walk(server, bot, b, cells.get(0), 3, 120)) return false;
        String result = onServer(server, () -> {
            ServerLevel level = bot.level();
            // anything in the frame (dirt, plants, snow) has to go first
            for (BlockPos q : cells) {
                if (!level.getBlockState(q).isAir()) BlueprintBuilder.setState(level, q, State.parse("minecraft:air"));
            }
            BlockPos at = cells.get(0);
            io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, net.minecraft.world.phys.Vec3.atCenterOf(at));
            Motions.swingArm(bot);
            BlueprintBuilder.setState(level, at, State.parse("minecraft:fire"));
            return SurvivalBrain.blockPath(level.getBlockState(at)).equals("nether_portal") ? "lit" : "no";
        }, "no");
        if (result.equals("lit")) {
            HumanChat.say(server, b.name, HumanChat.pick("lit the temple portal!", "the portal's lit, the temple's open to the nether"));
            return true;
        }
        HumanChat.say(server, b.name, "tried to light the temple portal but it won't catch, something's off with the frame");
        return true;
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
        int startY = p.arm == HIGHWAY ? highwayStart(t, p) : t.cy;
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

    /** The height the road between two towns starts at: where the old town's avenue ended (else its plaza). */
    static int highwayStart(Town t, Plot p) {
        Town from = null;
        for (Town o : TOWNS) if (o.id == t.parent) from = o;
        if (from == null) return t.fromY;
        int arm = -1;
        for (int i = 0; i < 4; i++) if (CityPlan.DIRS[i].equals(p.face)) arm = i;
        int y = from.cy, far = -1;
        int[] v = CityPlan.vec(p.face);
        for (Plot o : from.plots) {
            if (!o.road() || o.arm != arm || o.y == NO_Y) continue;
            int a = v[0] > 0 ? o.x0 + o.w - 1 - from.cx : v[0] < 0 ? from.cx - o.x0 : v[1] > 0 ? o.z0 + o.l - 1 - from.cz : from.cz - o.z0;
            if (a > far) { far = a; y = o.y; }
        }
        return y;
    }

    /** Part of a finished road (or its lamps)? Those aren't dug up for materials or by the pathfinder. */
    static boolean protectsRoad(String dim, BlockPos q) {
        for (Town t : TOWNS) {
            if (!t.dim.equals(dim)) continue;
            for (Plot p : t.plots) {
                if (!p.road() || !p.done() || p.y == NO_Y) continue;
                if (p.inside(q.getX(), q.getZ(), 0) && q.getY() >= p.y - 18 && q.getY() <= p.y + 18) return true;
            }
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
        Town near = onServer(server, () -> nearest(bot.level(), bot.blockPosition()), null);
        if (near == null) return maybeFound(server, bot, b);
        boolean here = onServer(server, () -> bot.blockPosition().distSqr(near.center()) < 600 * 600, false);
        if (!here) return false;
        return doTownThing(server, bot, b, false);
    }

    /**
     * Every town gets a PvP arena: a town laid out before there were arenas gets one at the end of
     * its open avenue (that end then isn't free for a road to another town). Job/brain thread.
     */
    static void addMissingArena(Town t) {
        for (Plot p : t.plots) if (p.kind.equals("arena")) return;
        Blueprints.Entry e = Blueprints.byFile("town_arena.nbt");
        if (e == null || ARENA_TRIED.contains(t.id)) return;
        Schematic s;
        try {
            s = Blueprints.plan(e);
        } catch (IOException | RuntimeException ex) {
            return;
        }
        synchronized (City.class) {
            for (Plot p : t.plots) if (p.kind.equals("arena")) return;
            ARENA_TRIED.add(t.id);
            // the end of the avenue opposite the temple (the open ends stay free for roads to other towns)
            int arm = (t.templeArm + 2) % 4;
            CityPlan.Design d = new CityPlan.Design("arena", e.fileName(), s.sx, s.sz, e.front());
            int[] v = CityPlan.vec(CityPlan.DIRS[arm]);
            int reach = Math.max(t.reach(arm), 8);
            boolean blocked = false; // a building sitting across the avenue's line past its road (the amphitheatre)
            for (Plot p : t.plots) {
                int a = v[0] > 0 ? p.x0 + p.w - 1 - t.cx : v[0] < 0 ? t.cx - p.x0 : v[1] > 0 ? p.z0 + p.l - 1 - t.cz : t.cz - p.z0;
                boolean onLine = v[0] != 0 ? p.z0 - t.cz <= 3 && p.z0 + p.l - 1 - t.cz >= -3 : p.x0 - t.cx <= 3 && p.x0 + p.w - 1 - t.cx >= -3;
                if (!p.road() && onLine && a > reach) blocked = true;
                reach = Math.max(reach, a);
            }
            int a0 = reach + CityPlan.GAP + 1;
            // clear of anybody's builds and homes
            for (int tries = 0; tries < 8; tries++) {
                int[] bx = CityPlan.box(arm, a0, a0 + d.depth() - 1, -(d.width() / 2), -(d.width() / 2) + d.width() - 1);
                int x0 = t.cx + bx[0] - 4, z0 = t.cz + bx[1] - 4, x1 = t.cx + bx[2] + 4, z1 = t.cz + bx[3] + 4;
                boolean clash = false;
                for (Blueprints.Build b : Blueprints.builds()) {
                    if (b.dim().equals(t.dim) && b.x() <= x1 && b.x() + b.sx() - 1 >= x0 && b.z() <= z1 && b.z() + b.sz() - 1 >= z0) clash = true;
                }
                for (Home.Base h : Home.all()) {
                    BlockPos m = h.middle();
                    if (h.dim().equals(t.dim) && m.getX() >= x0 && m.getX() <= x1 && m.getZ() >= z0 && m.getZ() <= z1) clash = true;
                }
                if (!clash) break;
                a0 += 16;
                blocked = true; // the road would have to go round
            }
            int[] bx = CityPlan.box(arm, a0, a0 + d.depth() - 1, -(d.width() / 2), -(d.width() / 2) + d.width() - 1);
            String face = CityPlan.dirOf(-v[0], -v[1]);
            int id = t.id * 1000 + 900;
            for (Plot p : t.plots) id = Math.max(id, p.id + 1);
            if (!blocked) {
                // the avenue carries on out to it
                for (int a = Math.max(t.reach(arm), 8) + 1; a < a0; a += CityPlan.SEGMENT) {
                    int a1 = Math.min(a0 - 1, a + CityPlan.SEGMENT - 1);
                    int[] rb = CityPlan.box(arm, a, a1, -(CityPlan.ROAD_HALF + 1), CityPlan.ROAD_HALF + 1);
                    t.plots.add(new Plot(id++, "road", "", t.cx + rb[0], t.cz + rb[1], rb[2] - rb[0] + 1, rb[3] - rb[1] + 1,
                            CityPlan.DIRS[arm], 0, arm, NO_Y, "", "todo"));
                }
            }
            t.plots.add(new Plot(id, "arena", e.fileName(), t.cx + bx[0], t.cz + bx[1], bx[2] - bx[0] + 1, bx[3] - bx[1] + 1, face,
                    CityPlan.turnsTo(d.front(), face), arm, NO_Y, "", "todo"));
            save();
        }
    }

    private static final Set<Integer> ARENA_TRIED = ConcurrentHashMap.newKeySet();

    /** The towns in the bot's dimension within a long walk, oldest first. Server thread. */
    private static List<Town> around(ServerPlayer bot) {
        String dim = Home.dim(bot.level());
        List<Town> out = new ArrayList<>();
        for (Town t : towns()) if (t.dim.equals(dim) && bot.blockPosition().distSqr(t.center()) < 1200 * 1200) out.add(t);
        return out;
    }

    private static volatile long nextExpansion = 0;
    private static final Map<Integer, Long> NEXT_PORTAL = new ConcurrentHashMap<>();

    /** A finished building of the town as it was put down (its plan, turned and placed), or null. */
    static BlueprintBuilder.Placed placedOf(Town t, Plot p) {
        if (t == null || p.y == NO_Y) return null;
        Blueprints.Entry e = Blueprints.byFile(p.file);
        if (e == null) return null;
        for (Blueprints.Build r : Blueprints.builds()) {
            if (!r.file().equals(e.fileName()) || !r.dim().equals(t.dim) || r.x() != p.x0 || r.z() != p.z0) continue;
            try {
                return new BlueprintBuilder.Placed(Blueprints.plan(e).rotated(r.rot()), new BlockPos(r.x(), r.y(), r.z()), r.rot(), r.dim());
            } catch (IOException | RuntimeException ex) {
                return null;
            }
        }
        return null;
    }
    private static final Map<String, Long> INVITED = new ConcurrentHashMap<>();

    private static boolean doTownThing(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, boolean asked)
            throws InterruptedException {
        String me = b.name;
        long now = System.currentTimeMillis();
        List<Town> mine = onServer(server, () -> around(bot), List.<Town>of());
        if (mine.isEmpty()) return false; // forgotten meanwhile, or too far away
        for (Town t : mine) addMissingArena(t);
        Town near = onServer(server, () -> nearest(bot.level(), bot.blockPosition()), mine.get(0));
        // once a day: the temple (the nearest one that's built)
        Plot temple = onServer(server, () -> nearestBuilt(bot.level(), bot.blockPosition(), "temple", 800), null);
        if (temple != null && onServer(server, () -> offeringDue(bot), false) && offer(server, bot, b, townOf(temple), false)) return true;
        // the shop: stock it and empty the till now and then (its shop in the nearest town that has one)
        if (Economy.upkeepDue(me)) {
            Town shopTown = null;
            for (Town t : mine) {
                Plot s = t.shopOf(me);
                if (s != null && s.done() && (shopTown == null || t == near)) shopTown = t;
            }
            if (shopTown != null && Economy.restock(server, bot, b, shopTown)) return true;
        }
        // the temple's portal, if it never got lit (no flint and steel back then, something in the frame)
        if (temple != null && now >= NEXT_PORTAL.getOrDefault(temple.id, 0L)) {
            NEXT_PORTAL.put(temple.id, now + 20 * 60_000L);
            BlueprintBuilder.Placed tp = placedOf(townOf(temple), temple);
            if (tp != null && lightPortal(server, bot, b, tp, false)) return true;
        }
        // the arena: now and then, invite whoever's around to a duel there
        if (!asked && inviteToArena(server, bot, b)) return true;
        // the next piece of a town (the older towns' first)
        Set<String> online = new java.util.HashSet<>();
        for (String n : onServer(server, () -> botNames(server), List.<String>of())) online.add(n.toLowerCase(Locale.ROOT));
        Plot p = claim(mine, me, online);
        Town pt = p == null ? null : townOf(p);
        if (p != null && pt != null) {
            String key = me + "#" + p.id;
            if (ANNOUNCED.putIfAbsent(key, now) == null) {
                String where = mine.size() > 1 ? " in " + pt.name : "";
                SurvivalBrain.maybeSay(server, b, HumanChat.pick("i'll take " + p.label() + where, "working on " + p.label() + where + " now",
                        "gonna build " + p.label() + where), p.road() ? 0.4 : 1.0);
            }
            int r = work(server, bot, b, pt, p);
            if (r != 0) NEXT.put(me, System.currentTimeMillis() + (r > 0 ? 5_000L : 60_000L));
            return true;
        }
        // nothing free to build: fetch what someone else is short of
        Need n = takeNeed(me);
        if (n != null) {
            Town nt = near;
            for (Town t : mine) for (Plot q : t.plots) if (q.id == n.plot()) nt = t;
            help(server, bot, b, nt, n);
            return true;
        }
        // the newest town is mostly built: on to the next one
        Town newest = onServer(server, () -> townIn(bot.level()), mine.get(mine.size() - 1));
        if (HumanConfig.get().autoCity && now >= nextExpansion && newest != null && newest.progress() >= 0.7) {
            nextExpansion = now + 15 * 60_000L;
            if (expand(server, bot, b, null) != null) return true;
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

    /** Job: keep working on the towns till there's nothing left to do (or told to stop). */
    static void workJob(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        List<Town> mine = onServer(server, () -> around(bot), List.<Town>of());
        if (mine.isEmpty()) return;
        // asked to work on it: give the pieces that got skipped another go
        boolean retry = false;
        for (Town t : mine) {
            for (Plot p : t.plots) {
                if (!p.state.equals("blocked")) continue;
                p.state = "todo";
                PLOT_FAILS.remove(p.id);
                SOFT_FAILS.remove(p.id);
                PLOT_BACKOFF.remove(p.id);
                retry = true;
            }
        }
        if (retry) save();
        int idle = 0;
        while (SurvivalBrain.canContinue(b) && idle < 3 && !towns().isEmpty()) {
            NEXT.remove(b.name);
            if (doTownThing(server, bot, b, true)) idle = 0;
            else {
                idle++;
                SurvivalBrain.sleep(20_000L);
            }
        }
        Town t = town();
        if (SurvivalBrain.canContinue(b) && t != null) HumanChat.say(server, b.name, "nothing left i can do on the towns right now. "
                + t.name + " is " + t.done() + "/" + t.plots.size() + " done");
    }

    // ------------------------------------------------------------------------
    // The arena: duels happen there
    // ------------------------------------------------------------------------

    /** Close enough to the middle of the arena to count as in it. */
    static boolean inArena(Plot arena, BlockPos at) {
        int dx = Math.abs(at.getX() - arena.midX()), dz = Math.abs(at.getZ() - arena.midZ());
        int half = Math.max(6, Math.min(arena.w, arena.l) / 2 - 17); // the floor inside the walls, not the stands
        int dy = at.getY() - arena.y;
        return dx <= half && dz <= half && dy >= -1 && dy <= 4;
    }

    /**
     * "fight me": every companion wants a duel to happen in the arena. With an arena built nearby,
     * it says where, walks there and waits for you; the fight starts once you're both in it. With
     * none, it says so and fights where you are. Server thread; returns what to say.
     */
    static String arenaDuel(MinecraftServer server, ServerPlayer bot, ServerPlayer player) {
        Plot arena = nearestBuilt(player.level(), player.blockPosition(), "arena", 800);
        if (arena == null) {
            Town t = nearest(player.level(), player.blockPosition());
            String later = t == null ? "" : t.built("arena") == null ? " (fights go in the arena once we've built one)" : "";
            return PvpController.start(bot, player, null) + later;
        }
        if (inArena(arena, bot.blockPosition()) && inArena(arena, player.blockPosition())) return PvpController.start(bot, player, null);
        Town t = townOf(arena);
        UUID who = player.getUUID();
        String name = player.getName().getString();
        SurvivalBrain.startJob(bot, "duel " + name + " at the arena", true, (s, bt, bb) -> duelJob(s, bt, bb, arena, who, name));
        return HumanChat.pick("not here. 1v1 me at the arena", "let's do it properly, at the arena", "arena. now. 1v1")
                + (t == null ? "" : " in " + t.name) + " (" + arena.midX() + " " + arena.y + " " + arena.midZ() + "). meet me there";
    }

    /** Goes to the arena and waits for the challenger, then fights. Job thread. */
    private static void duelJob(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Plot arena, UUID who, String name)
            throws InterruptedException {
        BlockPos mid = new BlockPos(arena.midX(), arena.y + 1, arena.midZ());
        walk(server, bot, b, mid, 4, 240);
        long until = System.currentTimeMillis() + 4 * 60_000L;
        boolean nagged = false;
        while (SurvivalBrain.canContinue(b) && System.currentTimeMillis() < until) {
            String started = onServer(server, () -> {
                ServerPlayer p = server.getPlayerList().getPlayer(who);
                if (p == null) return "gone";
                if (!inArena(arena, p.blockPosition())) return null;
                if (!inArena(arena, bot.blockPosition())) return null;
                return PvpController.start(bot, p, null);
            }, null);
            if ("gone".equals(started)) {
                HumanChat.say(server, b.name, HumanChat.pick("they left lol", "no show, ok"));
                return;
            }
            if (started != null) {
                HumanChat.say(server, b.name, started);
                return;
            }
            if (!nagged && System.currentTimeMillis() > until - 2 * 60_000L) {
                nagged = true;
                HumanChat.say(server, b.name, name + " i'm at the arena (" + arena.midX() + " " + arena.midZ() + "), where you at");
            }
            // keep to the middle (the fight might start any second)
            if (onServer(server, () -> !inArena(arena, bot.blockPosition()), false)) walk(server, bot, b, mid, 4, 60);
            SurvivalBrain.sleep(1500);
        }
        if (SurvivalBrain.canContinue(b)) HumanChat.say(server, b.name, HumanChat.pick("you never showed up lol", "chickened out? the arena's open whenever"));
    }

    /** Once a day, with an arena built and a player around: "anyone up for a 1v1 at the arena?". Brain thread. */
    private static boolean inviteToArena(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) {
        long day = onServer(server, () -> day(bot), 0L);
        Long last = INVITED.get(b.name);
        if (last != null && last >= day) return false;
        Plot arena = onServer(server, () -> nearestBuilt(bot.level(), bot.blockPosition(), "arena", 300), null);
        if (arena == null) return false;
        ServerPlayer human = onServer(server, () -> SurvivalBrain.nearestHuman(bot), null);
        if (human == null || onServer(server, () -> human.distanceToSqr(bot) > 48 * 48, true)) return false;
        INVITED.put(b.name, day);
        if (RNG.nextInt(3) != 0) return false; // not every day
        HumanChat.say(server, b.name, HumanChat.pick("anyone up for a 1v1 at the arena? say \"fight me\"",
                "arena's open, who wants a duel", "bet nobody can beat me at the arena"));
        return false; // it's only talk: carry on with the day
    }

    // ------------------------------------------------------------------------
    // Talking about it
    // ------------------------------------------------------------------------

    /** "Diamondvale: 6/30 done. building: the temple (Bro). next: a shop, ...". */
    static String describe() {
        Town t = town();
        if (t == null) return "we don't have a town yet. say \"let's build a city\" and we'll start one";
        StringBuilder sb = new StringBuilder();
        List<Town> all = towns();
        if (all.size() > 1) {
            List<String> names = new ArrayList<>();
            for (Town o : all) names.add(o.name + " " + Math.round(o.progress() * 100) + "%");
            sb.append(all.size()).append(" towns joined up by road: ").append(String.join(", ", names)).append(". newest: ");
        }
        sb.append(t.name).append(" (plaza at ").append(t.cx).append(' ').append(t.cy).append(' ').append(t.cz)
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
        boolean roadOut = false;
        for (Plot p : t.plots) if (p.arm == HIGHWAY && !p.done()) roadOut = true;
        if (roadOut) sb.append(". the road out to it isn't finished yet");
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

    /** A few lines for the language model about the towns. */
    static String persona() {
        List<Town> all = towns();
        if (all.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("You and the other companions are building a network of towns together, joined by roads: ");
        List<String> parts = new ArrayList<>();
        for (Town t : all) {
            List<String> built = new ArrayList<>();
            for (Plot p : t.plots) if (!p.road() && p.done() && !p.kind.equals("house") && !p.kind.equals("farm")) built.add(p.label());
            parts.add(t.name + " (plaza at " + t.cx + " " + t.cy + " " + t.cz + ", " + Math.round(t.progress() * 100) + "% built"
                    + (built.isEmpty() ? "" : ": " + String.join(", ", built)) + ")");
        }
        sb.append(String.join("; ", parts)).append(". Every town gets a temple and a PvP arena. Each of you visits a temple once a day to"
                + " leave food on the altar. Duels happen in the arena: if anyone wants to fight, tell them to meet you there.");
        return sb.toString();
    }

    /** Where something in town is (the nearest one to {@code at}, when there are several towns). */
    static String where(String what, ServerLevel level, BlockPos at) {
        Town t = level != null && at != null ? nearest(level, at) : town();
        if (t == null) return "we don't have a town yet";
        if (what == null || what.matches("city|town|plaza|square|town square|middle")) {
            String more = towns().size() > 1 ? " (it's one of " + towns().size() + " towns, the newest is " + town().name + ")" : "";
            return t.name + "'s plaza is at " + t.cx + " " + t.cy + " " + t.cz + more;
        }
        String kind = what.replace("amphitheater", "amphitheatre").replace("theatre", "amphitheatre").replace("theater", "amphitheatre")
                .replace("market", "mall").replace("storage", "warehouse").replace("farms", "farm").replace("church", "temple")
                .replace("pvp arena", "arena").replace("colosseum", "arena");
        if (kind.equals("amphiamphitheatre")) kind = "amphitheatre";
        Plot found = null;
        Town in = t;
        if (level != null && at != null) {
            Plot built = nearestBuilt(level, at, kind, 5000);
            if (built != null) { found = built; in = townOf(built); }
        }
        if (found == null) {
            for (Plot p : t.plots) {
                if (p.kind.equals(kind) || (kind.endsWith("shop") && p.kind.equals("shop") && kind.startsWith(p.owner.toLowerCase(Locale.ROOT)))) {
                    found = p;
                    break;
                }
            }
        }
        if (found == null) return "there's no " + what + " in " + t.name + " yet";
        Town ft = in == null ? t : in;
        return found.label() + (towns().size() > 1 ? " in " + ft.name : "") + " is at " + found.midX() + " "
                + (found.y == NO_Y ? ft.cy : found.y) + " " + found.midZ() + (found.done() ? "" : " (not built yet)");
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
            "\\bwhere('?s| is| are)( the| our| your)? (city|town|plaza|square|temple|mall|market|amphitheatre|amphitheater|theatre|theater"
            + "|warehouse|storage|farms?|shop|pvp arena|arena|colosseum)\\b");
    private static final Pattern EXPAND = Pattern.compile(
            "^(?:(?:ok|okay|hey|yo|guys|pls|please|can you|could you|you|y'?all|let'?s|lets|we should|go|now)\\s+)*"
            + "(?:(?:build|make|start|found|create|set up)\\s+(?:another|a second|a third|the next|one more|a new|more)\\s+(?:city|town|cities|towns)"
            + "|expand (?:the |our )?(?:network|city|cities|town|towns))(?:\\s+(?:called|named)\\s+([a-z0-9' ]{2,24}))?[!.\\s]*$");
    private static final Pattern FORGET = Pattern.compile("^(forget( about)?|cancel|scrap|abandon|delete) (the |our )?(city|town)[!.\\s]*$");
    private static final Pattern OFFER = Pattern.compile(
            "^(?:(?:ok|okay|pls|please|can you|could you|you|go|now|let'?s|lets)\\s+)*(go )?(pray|make an offering|make (an )?offerings?|offer (some )?food|go to the temple|visit the temple)\\b");

    /** What to do about a message about the town, or null if it isn't one. {@code player}/{@code bot} null: just checking. */
    static Blueprints.Ask parse(String m, ServerPlayer player, ServerPlayer bot) {
        if (m == null || m.length() > 90) return null;
        String t = m.toLowerCase(Locale.ROOT).trim();
        Matcher xm = EXPAND.matcher(t);
        if (xm.find()) {
            if (player == null) return new Blueprints.Ask(() -> "", null);
            String name = xm.group(1);
            if (town() == null) {
                return new Blueprints.Ask(null, new MiningSkills.Request("found a town", "a town! let me find a good spot near our houses",
                        (s, bt, bb) -> {
                            found(s, bt, bb, null, name);
                            if (town() != null) workJob(s, bt, bb);
                        }));
            }
            return new Blueprints.Ask(null, new MiningSkills.Request("start the next town",
                    HumanChat.pick("another town! gonna find a spot down one of the roads", "next town, let's go. finding a spot"),
                    (s, bt, bb) -> {
                        if (expand(s, bt, bb, name) != null) workJob(s, bt, bb);
                    }));
        }
        Matcher f = FOUND.matcher(t);
        if (f.find()) {
            boolean here = f.group(1) != null;
            String name = f.group(2);
            if (player == null) return new Blueprints.Ask(() -> "", null);
            BlockPos at = here ? player.blockPosition() : null;
            Town existing = town();
            if (existing != null && !here && existing.progress() >= 0.5) {
                return new Blueprints.Ask(null, new MiningSkills.Request("start the next town",
                        existing.name + "'s coming along, so on to the next one. finding a spot down one of its roads",
                        (s, bt, bb) -> {
                            if (expand(s, bt, bb, name) != null) workJob(s, bt, bb);
                        }));
            }
            if (existing != null && !here) {
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
                Town x2 = town();
                if (x2 == null) return "there's no town to forget";
                if (player == null) return "";
                int n = towns().size();
                forget();
                return "ok, forgot about " + (n > 1 ? "all " + n + " towns" : x2.name) + ". what's built stays where it is";
            }, null);
        }
        if (STATUS.matcher(t).find()) return new Blueprints.Ask(City::describe, null);
        Matcher w = WHERE.matcher(t);
        if (w.find()) {
            String what = w.group(3);
            if (what.equals("shop") && bot != null) {
                Town x = null;
                for (Town o : towns()) {
                    Plot os = o.shopOf(bot.getName().getString());
                    if (os != null && (x == null || os.done())) x = o; // the newest finished one
                }
                Plot s = x == null ? null : x.shopOf(bot.getName().getString());
                final int cy = x == null ? 0 : x.cy;
                return new Blueprints.Ask(() -> s == null ? "i don't have a shop yet" : "my shop is at " + s.midX() + " "
                        + (s.y == NO_Y ? cy : s.y) + " " + s.midZ() + (s.done() ? "" : " (still building it)"), null);
            }
            return new Blueprints.Ask(() -> player == null ? where(what, null, null) : where(what, player.level(), player.blockPosition()), null);
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
                        Plot tp = onServer(s, () -> nearestBuilt(bt.level(), bt.blockPosition(), "temple", 5000), null);
                        Town x = tp != null ? townOf(tp) : town();
                        if (x == null) HumanChat.say(s, bb.name, "we don't have a town (or a temple) yet");
                        else offer(s, bt, bb, x, true);
                    }));
        }
        return null;
    }
}
