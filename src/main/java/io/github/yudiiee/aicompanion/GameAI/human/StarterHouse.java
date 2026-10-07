package io.github.yudiiee.aicompanion.GameAI.human;

import io.github.yudiiee.aicompanion.GameAI.human.Schematic.Rules;
import io.github.yudiiee.aicompanion.GameAI.human.Schematic.State;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.onServer;

/**
 * Each companion's own house, from the starter designs in the schematics folder (side file
 * {@code starter: yes}): the first bot takes the first design nobody has, the next bot the
 * next one. It builds it like any other design (gathering the right wood and stone, stand-ins
 * when something can't be had), then moves in: the design tells it where inside is, where the
 * middle is to come home to, where the barrels or chests are, and where the bed goes. A house
 * that isn't finished yet becomes home once it's mostly up, and the bot keeps working on it in
 * the daytime until it's done. Without starter designs it builds the small classic house.
 */
final class StarterHouse {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-house");

    /** Moves in once this much of the house (decoration aside) is up. */
    static final double MOVE_IN = 0.6;

    private StarterHouse() {}

    // ------------------------------------------------------------------------
    // Which house
    // ------------------------------------------------------------------------

    /** The starter designs in the folder, in order. */
    static List<Blueprints.Entry> designs() {
        List<Blueprints.Entry> out = new ArrayList<>();
        for (Blueprints.Entry e : Blueprints.list()) if (e.starter()) out.add(e);
        out.sort(Comparator.comparing(Blueprints.Entry::fileName));
        return out;
    }

    static boolean available() {
        return !designs().isEmpty();
    }

    /** The bot's own unfinished starter build, if any. */
    static Blueprints.Build unfinishedHouse(String bot) {
        Blueprints.Build last = null;
        for (Blueprints.Build b : Blueprints.builds()) {
            if (b.done() || !b.bot().equals(bot)) continue;
            Blueprints.Entry e = Blueprints.byFile(b.file());
            if (e != null && e.starter()) last = b;
        }
        return last;
    }

    /**
     * The design for this bot: the one it already started, else the first one nobody else has
     * (homes and builds), else one picked by its name.
     */
    static Blueprints.Entry pick(ServerPlayer bot) {
        List<Blueprints.Entry> all = designs();
        if (all.isEmpty()) return null;
        String me = bot.getName().getString().toLowerCase(Locale.ROOT);
        Blueprints.Build mine = unfinishedHouse(me);
        if (mine != null) {
            Blueprints.Entry e = Blueprints.byFile(mine.file());
            if (e != null) return e;
        }
        Set<String> taken = new HashSet<>();
        for (Home.Base h : Home.all()) {
            Design d = Design.parse(h.design());
            if (d != null) taken.add(d.file());
        }
        for (Blueprints.Build b : Blueprints.builds()) {
            Blueprints.Entry e = Blueprints.byFile(b.file());
            if (e != null && e.starter() && !b.bot().equals(me) && !b.bot().equals(City.TOWN_BUILDER)) taken.add(b.file());
        }
        for (Blueprints.Entry e : all) if (!taken.contains(e.fileName())) return e;
        return all.get(Math.floorMod(me.hashCode(), all.size()));
    }

    // ------------------------------------------------------------------------
    // Building it
    // ------------------------------------------------------------------------

    /** Job: build (or carry on with) the bot's house, and move in once it's mostly up. */
    static void build(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, UUID requester) throws InterruptedException {
        Blueprints.Entry e = onServer(server, () -> pick(bot), null);
        if (e == null) {
            House.build(server, bot, b, requester);
            return;
        }
        String me = b.name.toLowerCase(Locale.ROOT);
        // finished it some other way ("continue the build") but never moved in: move in now
        if (onServer(server, () -> Home.get(bot), null) == null) {
            String here = onServer(server, () -> Home.dim(bot.level()), "");
            for (Blueprints.Build x : Blueprints.builds()) {
                Blueprints.Entry xe = Blueprints.byFile(x.file());
                if (!x.done() || !x.bot().equals(me) || xe == null || !xe.starter() || !x.dim().equals(here)) continue;
                moveIn(server, bot, b, xe, x.rot(), new BlockPos(x.x(), x.y(), x.z()), x.sx(), x.sz());
                HumanChat.say(server, b.name, "moving into the " + xe.name());
                return;
            }
        }
        Blueprints.Build prev = unfinishedHouse(me);
        if (prev != null && !prev.file().equals(e.fileName())) prev = null;
        if (prev == null) {
            Home.Base home = onServer(server, () -> Home.get(bot), null);
            Design d = home == null ? null : Design.parse(home.design());
            if (d != null) {
                for (Blueprints.Build x : Blueprints.builds()) {
                    if (x.file().equals(d.file()) && x.x() == d.x() && x.y() == d.y() && x.z() == d.z()) prev = x;
                }
                if (prev != null && prev.done()) {
                    HumanChat.say(server, b.name, "my house is already built");
                    return;
                }
            }
        }
        if (prev == null) {
            HumanChat.say(server, b.name, HumanChat.pick("gonna build my house: the " + e.name() + ". it's a big one, this'll take a while",
                    "time to build a proper house. going with the " + e.name()));
        }
        BlueprintBuilder.Spot spot = prev != null ? BlueprintBuilder.Spot.resume(prev) : BlueprintBuilder.Spot.auto();
        BlueprintBuilder.Result r = BlueprintBuilder.buildFor(server, bot, b, e, spot, prev);
        if (r == null) {
            if (prev == null && SurvivalBrain.canContinue(b) && onServer(server, () -> Home.get(bot), null) == null) {
                HumanChat.say(server, b.name, "gonna throw up a small house for now instead");
                House.build(server, bot, b, requester);
            }
            return;
        }
        if (!r.complete() && r.progress() < MOVE_IN) {
            if (!SurvivalBrain.canContinue(b)) return;
            HumanChat.say(server, b.name, "the house is " + (int) Math.round(r.progress() * 100) + "% up. i'll keep at it");
            // second go and still no roof over its head (no trees around, say): a small house to live in meanwhile
            if (prev != null && onServer(server, () -> Home.get(bot), null) == null) {
                HumanChat.say(server, b.name, "gonna put up a small house to live in while i work on it");
                House.build(server, bot, b, requester);
            }
            return;
        }
        BlueprintBuilder.Placed p = r.placed();
        moveIn(server, bot, b, e, p.rot(), p.origin(), p.plan().sx, p.plan().sz);
        if (!r.complete()) {
            HumanChat.say(server, b.name, "moving in, the house is " + (int) Math.round(r.progress() * 100)
                    + "% done. i'll finish the rest in the daytime");
        } else {
            HumanChat.say(server, b.name, HumanChat.pick("home sweet home, the " + e.name() + " is done",
                    "house is finished! the " + e.name() + ", come have a look"));
        }
        if (HumanConfig.get().autoStore) Storage.storeAll(server, bot, b, false);
    }

    /** Makes the house home, and puts in storage and a bed if it has none. Job thread. */
    private static void moveIn(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Blueprints.Entry e, int rot,
                               BlockPos origin, int sx, int sz) throws InterruptedException {
        Design d = new Design(e.fileName(), rot, origin.getX(), origin.getY(), origin.getZ(), e.ground());
        Layout layout = layout(d); // worked out here, not on the server's tick
        House.Site site = siteOf(origin, rot, sx, sz, e);
        onServer(server, () -> { Home.set(bot, site, d.encode()); return null; }, null);
        if (layout != null) furnish(server, bot, b, layout);
    }

    /** The square the house covers, front = the way its door looks out (used for keeping things off it). */
    static House.Site siteOf(BlockPos origin, int rot, int sx, int sz, Blueprints.Entry e) {
        Direction door = Blueprints.dir(Schematic.turnDir(e.front(), rot));
        return new House.Site(origin.getX(), origin.getZ(), origin.getY() + e.ground(), door == null ? Direction.SOUTH : door, 0,
                Math.max(sx, sz));
    }

    /** Storage if the design has none, and a bed if it has none: the things a home needs. Job thread. */
    private static void furnish(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Layout l) throws InterruptedException {
        // what's already in there, anywhere on the floor (it may have furnished it on an earlier visit)
        int storage = onServer(server, () -> {
            int n = 0;
            for (BlockPos c : l.storage()) if (Storage.isStorageBlock(SurvivalBrain.blockPath(bot.level().getBlockState(c)))) n++;
            for (BlockPos c : l.floor()) if (Storage.isStorageBlock(SurvivalBrain.blockPath(bot.level().getBlockState(c)))) n++;
            return n;
        }, 0);
        if (storage == 0) {
            Gathering.Craftable chest = Gathering.craftable("chest");
            if (chest != null && Gathering.makeSure(server, bot, b, chest, 2)) {
                int placed = 0;
                for (BlockPos c : l.spare()) {
                    if (placed >= 2 || !SurvivalBrain.canContinue(b)) break;
                    if (!BlueprintBuilder.approach(server, bot, c, 4.3)) continue;
                    Direction toMid = Building.dirTo(c, l.middle());
                    Direction face = toMid == Direction.UP || toMid == Direction.DOWN ? Direction.SOUTH : toMid;
                    boolean ok = onServer(server, () -> Building.placeFacing(bot, c, "chest", face)
                            && Storage.isStorageBlock(SurvivalBrain.blockPath(bot.level().getBlockState(c))), false);
                    if (ok) {
                        placed++;
                        onServer(server, () -> { Storage.remember(bot.level(), c); return null; }, null);
                    }
                }
            }
        } else {
            onServer(server, () -> {
                for (BlockPos c : l.storage()) {
                    if (Storage.isStorageBlock(SurvivalBrain.blockPath(bot.level().getBlockState(c)))) Storage.remember(bot.level(), c);
                }
                return null;
            }, null);
        }
        boolean bed = onServer(server, () -> {
            if (l.bed() != null && SurvivalBrain.blockPath(bot.level().getBlockState(l.bed())).endsWith("_bed")) return true;
            for (BlockPos c : l.floor()) if (SurvivalBrain.blockPath(bot.level().getBlockState(c)).endsWith("_bed")) return true;
            return false;
        }, false);
        if (!bed && House.ensureBed(server, bot, b)) {
            for (BlockPos c : l.spare()) {
                if (!SurvivalBrain.canContinue(b)) break;
                Direction d = onServer(server, () -> {
                    for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                        BlockPos head = c.relative(dir);
                        if (l.isInside(head) && Building.isFree(bot.level(), head) && Building.isFree(bot.level(), c)
                                && !head.equals(l.middle()) && !c.equals(l.middle())) return dir;
                    }
                    return null;
                }, null);
                if (d == null) continue;
                if (!BlueprintBuilder.approach(server, bot, c, 4.3)) continue;
                boolean ok = onServer(server, () -> {
                    String item = Building.firstItem(bot, p -> p.endsWith("_bed"));
                    return item != null && Building.placeFacing(bot, c, item, d);
                }, false);
                if (ok) break;
            }
        }
    }

    // ------------------------------------------------------------------------
    // Living in it
    // ------------------------------------------------------------------------

    private static final Map<String, Long> NEXT_WORK = new ConcurrentHashMap<>();
    private static final Map<String, Integer> TRIES = new ConcurrentHashMap<>();

    /** Daytime at home with the house not finished: get back to it (less often each time it gets nowhere). Brain thread. */
    static boolean tick(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) {
        String me = b.name.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        if (now < NEXT_WORK.getOrDefault(me, 0L)) return false;
        Blueprints.Build mine = unfinishedHouse(me);
        if (mine == null) {
            // still in the small classic house: a proper one from the designs, once
            Home.Base h = onServer(server, () -> Home.get(bot), null);
            if (h == null || h.design() != null || !available() || TRIES.getOrDefault(me, 0) > 0) {
                NEXT_WORK.put(me, now + 10 * 60_000L);
                return false;
            }
        }
        int tries = TRIES.merge(me, 1, Integer::sum);
        NEXT_WORK.put(me, now + Math.min(120, 10L << Math.min(4, tries - 1)) * 60_000L);
        SurvivalBrain.maybeSay(server, b, HumanChat.pick("gonna work on the house some more", "back to building the house"), 0.8);
        SurvivalBrain.startJob(bot, "finish the house", false, (s, bt, bb) -> build(s, bt, bb, null));
        return true;
    }

    // ------------------------------------------------------------------------
    // The house's layout, worked out from the design
    // ------------------------------------------------------------------------

    /** A starter house in the world: the design file, the turn, its low corner, which layer is the ground. */
    record Design(String file, int rot, int x, int y, int z, int ground) {
        String encode() { return file + "|" + rot + "|" + x + "|" + y + "|" + z + "|" + ground; }

        /** Null if it's not a proper design string. */
        static Design parse(String s) {
            if (s == null) return null;
            String[] p = s.split("\\|");
            if (p.length != 6) return null;
            try {
                return new Design(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]), Integer.parseInt(p[5]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /**
     * Where inside is (feet cells with a roof over them), the middle of the ground floor (where
     * it stands at night), storage blocks in the design, spare spots along the walls for more
     * chests, and the bed. Worked out in the design's own coordinates; {@code ox, oy, oz} is
     * where it sits in the world.
     */
    record Layout(int ox, int oy, int oz, Set<Long> local, int[] mid, List<int[]> storageCells, List<int[]> spareCells,
                  int[] bedCell, List<int[]> floorCells) {

        private BlockPos at(int[] c) { return new BlockPos(ox + c[0], oy + c[1], oz + c[2]); }

        private List<BlockPos> at(List<int[]> cs) {
            List<BlockPos> out = new ArrayList<>(cs.size());
            for (int[] c : cs) out.add(at(c));
            return out;
        }

        boolean isInside(BlockPos feet) {
            return local.contains(key(feet.getX() - ox, feet.getY() - oy, feet.getZ() - oz));
        }

        BlockPos middle() { return at(mid); }

        List<BlockPos> storage() { return at(storageCells); }

        List<BlockPos> spare() { return at(spareCells); }

        BlockPos bed() { return bedCell == null ? null : at(bedCell); }

        List<BlockPos> floor() { return at(floorCells); }

        /** Storage blocks first, then spare spots: where chests are or can go. */
        List<BlockPos> chestSpots() {
            List<BlockPos> out = new ArrayList<>(storage());
            List<BlockPos> sp = spare();
            for (int i = 0; i < sp.size() && i < 6; i++) out.add(sp.get(i));
            return out;
        }
    }

    private static final Map<String, Layout> LAYOUTS = new ConcurrentHashMap<>();

    /** A position in the design as one number (for sets). */
    static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (z & 0x1FFFFF) << 21) | (y & 0x1FFFFF);
    }

    /** The layout for a home's design (cached), or null if the design file is gone. */
    static Layout layout(String encoded) {
        Design d = Design.parse(encoded);
        return d == null ? null : layout(d);
    }

    /** Designs whose layout couldn't be worked out, and when: not tried again for a while (it's asked every tick). */
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();

    static Layout layout(Design d) {
        String k = d.encode();
        Layout l = LAYOUTS.get(k);
        if (l != null) return l;
        Long failed = FAILED.get(k);
        if (failed != null && System.currentTimeMillis() - failed < 5 * 60_000L) return null;
        Blueprints.Entry e = Blueprints.byFile(d.file());
        try {
            if (e == null) throw new java.io.IOException("the design file is gone");
            Schematic plan = Blueprints.plan(e).rotated(d.rot());
            l = layoutAt(plan, d.x(), d.y(), d.z(), d.ground());
            LAYOUTS.put(k, l);
            FAILED.remove(k);
            return l;
        } catch (Exception ex) {
            if (failed == null) LOGGER.warn("[house] couldn't work out the layout of {}: {}", d.file(), ex.toString());
            FAILED.put(k, System.currentTimeMillis());
            return null;
        }
    }

    /** Can you stand in this cell of the plan (air, a carpet, a flower, an open door...)? */
    static boolean passable(State s) {
        if (s == null) return true;
        Rules.Kind k = Rules.kind(s);
        if (k == Rules.Kind.AIR || k == Rules.Kind.KEEP) return true;
        String p = s.path();
        if (p.endsWith("_door") || p.endsWith("_carpet") || p.endsWith("_pressure_plate") || p.endsWith("_button")
                || p.contains("torch") || p.contains("sign") || p.endsWith("_banner") || p.equals("ladder") || p.equals("lever")
                || p.equals("redstone_wire") || p.contains("rail") || p.equals("snow")) return true;
        if (p.endsWith("_fence") || p.endsWith("_wall") || p.endsWith("_fence_gate") || p.endsWith("_bars") || p.endsWith("_pane")
                || p.endsWith("_leaves")) return false;
        return Rules.decorative(p) && !p.endsWith("_wool") && !p.contains("cake");
    }

    /** Pure: works the layout out from a (turned) plan placed at {@code origin}. */
    static Layout layoutOf(Schematic s, BlockPos origin, int ground) {
        return layoutAt(s, origin.getX(), origin.getY(), origin.getZ(), ground);
    }

    private static final int[][] SIDES = {{0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}};

    private static final Set<String> NATURAL_GROUND = Set.of("grass_block", "dirt", "coarse_dirt", "podzol", "mycelium",
            "rooted_dirt", "moss_block", "sand", "gravel", "mud", "dirt_path", "farmland");

    /** Pure: the layout of a (turned) plan whose low corner is at ox, oy, oz. */
    static Layout layoutAt(Schematic s, int ox, int oy, int oz, int ground) {
        Set<Long> inside = new HashSet<>();
        int lowest = Integer.MAX_VALUE;
        for (int y = ground + 1; y < s.sy - 1; y++) {
            for (int z = 0; z < s.sz; z++) {
                for (int x = 0; x < s.sx; x++) {
                    if (!passable(s.at(x, y, z)) || !passable(s.at(x, y + 1, z)) || passable(s.at(x, y - 1, z))) continue;
                    State under = s.at(x, y - 1, z);
                    if (under != null && NATURAL_GROUND.contains(under.path())) continue; // a garden, not a floor
                    boolean roof = false;
                    for (int up = y + 2; up < Math.min(s.sy, y + 16) && !roof; up++) {
                        State above = s.at(x, up, z);
                        if (!passable(above) && !above.path().endsWith("_leaves") && !Rules.decorative(above.path())) roof = true;
                    }
                    if (!roof) continue;
                    inside.add(key(x, y, z));
                    lowest = Math.min(lowest, y);
                }
            }
        }
        List<int[]> storage = new ArrayList<>();
        int[] bed = null;
        Set<Long> doors = new HashSet<>();
        for (int y = 0; y < s.sy; y++) for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++) {
            State st = s.at(x, y, z);
            if (st == null) continue;
            String p = st.path();
            if (p.equals("chest") || p.equals("barrel") || p.equals("trapped_chest")) storage.add(new int[]{x, y, z});
            if (p.endsWith("_bed") && "foot".equals(st.get("part")) && bed == null) bed = new int[]{x, y, z};
            if (p.endsWith("_door")) doors.add(key(x, y, z));
        }
        // the ground floor: the biggest connected patch of floor in the bottom few layers with a roof
        List<int[]> floor = new ArrayList<>();
        if (lowest != Integer.MAX_VALUE) {
            Set<Long> seen = new HashSet<>();
            for (int fy = lowest; fy <= lowest + 3 && fy < s.sy; fy++) {
                final int y = fy;
                for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++) {
                    if (!inside.contains(key(x, y, z)) || !seen.add(key(x, y, z))) continue;
                    List<int[]> comp = new ArrayList<>();
                    ArrayDeque<int[]> q = new ArrayDeque<>();
                    q.add(new int[]{x, y, z});
                    while (!q.isEmpty()) {
                        int[] c = q.poll();
                        comp.add(c);
                        for (int[] d : SIDES) {
                            int nx = c[0] + d[0], nz = c[2] + d[2];
                            if (inside.contains(key(nx, y, nz)) && seen.add(key(nx, y, nz))) q.add(new int[]{nx, y, nz});
                        }
                    }
                    if (comp.size() > floor.size()) floor = comp;
                }
            }
        }
        int[] mid;
        if (floor.isEmpty()) {
            mid = new int[]{s.sx / 2, ground + 1, s.sz / 2};
        } else {
            double cx = 0, cz = 0;
            for (int[] c : floor) { cx += c[0]; cz += c[2]; }
            final double fx = cx / floor.size(), fz = cz / floor.size();
            mid = floor.stream().min(Comparator.comparingDouble(c -> Math.pow(c[0] - fx, 2) + Math.pow(c[2] - fz, 2))).orElse(floor.get(0));
        }
        final int[] m = mid;
        Comparator<int[]> byDist = Comparator.comparingDouble(c -> Math.pow(c[0] - m[0], 2) + Math.pow(c[1] - m[1], 2) + Math.pow(c[2] - m[2], 2));
        storage.sort(byDist);
        // spare spots: floor cells against a wall, not by a door, not at the middle
        Set<Long> floorSet = new HashSet<>();
        for (int[] c : floor) floorSet.add(key(c[0], c[1], c[2]));
        List<int[]> spare = new ArrayList<>();
        for (int[] c : floor) {
            if (Math.abs(c[0] - m[0]) + Math.abs(c[2] - m[2]) <= 1) continue;
            State here = s.at(c[0], c[1], c[2]), head = s.at(c[0], c[1] + 1, c[2]);
            if (here != null && Rules.kind(here) != Rules.Kind.AIR || head != null && Rules.kind(head) != Rules.Kind.AIR) continue;
            boolean wall = false, door = false;
            for (int[] d : SIDES) {
                int nx = c[0] + d[0], ny = c[1], nz = c[2] + d[2];
                if (doors.contains(key(nx, ny, nz)) || doors.contains(key(nx, ny + 1, nz))
                        || doors.contains(key(c[0] + 2 * d[0], ny, c[2] + 2 * d[2]))) door = true; // in a doorway's way
                if (!floorSet.contains(key(nx, ny, nz)) && !passable(s.at(nx, ny, nz))) wall = true;
            }
            if (wall && !door) spare.add(c);
        }
        spare.sort(byDist);
        return new Layout(ox, oy, oz, inside, mid, storage, spare, bed, floor);
    }
}
