package io.github.yudiiee.aicompanion.GameAI.human;

import io.github.yudiiee.aicompanion.GameAI.human.Schematic.Rules;
import io.github.yudiiee.aicompanion.GameAI.human.Schematic.State;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.onServer;

/**
 * Builds a design from the schematics folder, the way a player follows a Litematica
 * hologram: picks a spot (or where it was told), works out what's missing and gathers it
 * (pockets, then the chests, then crafting, smelting and digging what it can), clears the
 * area, then puts the blocks in bottom up: the solid parts, then water and lava, then the
 * things that hang off other blocks (torches, redstone, rails, plants). Plain cubes it
 * places with a right-click; anything that has to point a certain way (pistons, observers,
 * hoppers, stairs, repeaters...) uses up the item and is set exactly as the plan says.
 * Anything it can't get, it lists, and finishes later ("continue the build").
 */
final class BlueprintBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-builder");

    /** Most blocks it will take on in one design. */
    static final int MAX_BLOCKS = 40_000;

    private BlueprintBuilder() {}

    // ------------------------------------------------------------------------
    // Where
    // ------------------------------------------------------------------------

    /** Where a build goes: picked by the bot, in front of a player, at given coordinates, or where it was started. */
    record Spot(int kind, BlockPos pos, Direction dir, Blueprints.Build previous) {
        static Spot auto() { return new Spot(0, null, null, null); }
        static Spot here(BlockPos feet, Direction look) { return new Spot(1, feet, look, null); }
        static Spot at(BlockPos corner, Direction front) { return new Spot(2, corner, front, null); }
        static Spot resume(Blueprints.Build b) { return new Spot(3, null, null, b); }
    }

    /** A design put somewhere: its local (0,0,0) is at {@code origin}. */
    record Placed(Schematic plan, BlockPos origin, int rot, String dim, Map<String, String> swaps) {
        Placed(Schematic plan, BlockPos origin, int rot, String dim) {
            this(plan, origin, rot, dim, new ConcurrentHashMap<>());
        }

        BlockPos world(int x, int y, int z) { return origin.offset(x, y, z); }

        /** The planned block, with any stand-in agreed for this build swapped in. */
        State at(BlockPos p) {
            return local(p.getX() - origin.getX(), p.getY() - origin.getY(), p.getZ() - origin.getZ());
        }

        State local(int x, int y, int z) {
            return swapped(plan.at(x, y, z), swaps);
        }

        boolean contains(BlockPos p) {
            return plan.inside(p.getX() - origin.getX(), p.getY() - origin.getY(), p.getZ() - origin.getZ());
        }

        BlockPos center() { return origin.offset(plan.sx / 2, 0, plan.sz / 2); }
    }

    // ------------------------------------------------------------------------
    // Stand-ins: when the exact block can't be had, the closest-looking one (agreed per build)
    // ------------------------------------------------------------------------

    /** "wood:warped" -> "dark_oak" (a kind of wood for another), or "calcite" -> "diorite" (a block for another). */
    static State swapped(State s, Map<String, String> swaps) {
        if (s == null || swaps.isEmpty()) return s;
        for (int hop = 0; hop < 6; hop++) { // dark oak -> spruce -> jungle: follow the chain
            String path = s.path();
            String[] w = Rules.wood(path);
            String to = w != null ? swaps.get("wood:" + w[0]) : swaps.get(path);
            if (to == null) return s;
            State next = w != null ? s.withId(Rules.woodPath(to, w[1])) : s.withId(to);
            if (next.id().equals(s.id())) return s;
            s = next;
        }
        return s;
    }

    /** Builds from before wood had to match exactly: any wood of the right shape counts. */
    static final String LEGACY = "legacy";

    private static boolean looseWoodMatch(Placed p, State plan, State world) {
        if (!p.swaps().containsKey(LEGACY)) return false;
        String[] a = Rules.wood(plan.path()), c = Rules.wood(world.path());
        return a != null && c != null && a[1].equals(c[1]);
    }

    static String encodeSwaps(Map<String, String> swaps) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(swaps).entrySet()) {
            if (e.getKey().startsWith("upgraded:")) continue; // only for this run
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('>').append(e.getValue());
        }
        return sb.toString();
    }

    static Map<String, String> decodeSwaps(String s) {
        Map<String, String> out = new ConcurrentHashMap<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(";")) {
            int i = part.indexOf('>');
            if (i > 0 && i < part.length() - 1) out.put(part.substring(0, i), part.substring(i + 1));
        }
        return out;
    }

    /**
     * Blocks that look about the same, closest first, for when the real one can't be had (same
     * shape: stairs for stairs, full blocks for full blocks).
     */
    static final Map<String, List<String>> LOOKALIKES = new HashMap<>();

    private static void like(String block, String... options) { LOOKALIKES.put(block, List.of(options)); }

    static {
        // sea-green prismarine (ocean monuments): mossy stone is the nearest green-grey you can make
        like("prismarine_brick_stairs", "mossy_stone_brick_stairs", "mossy_cobblestone_stairs", "stone_brick_stairs");
        like("prismarine_bricks", "mossy_stone_bricks", "mossy_cobblestone", "stone_bricks");
        like("prismarine_brick_slab", "mossy_stone_brick_slab", "mossy_cobblestone_slab", "stone_brick_slab");
        like("prismarine", "mossy_cobblestone", "mossy_stone_bricks", "cobblestone");
        like("prismarine_stairs", "mossy_cobblestone_stairs", "mossy_stone_brick_stairs", "cobblestone_stairs");
        like("prismarine_slab", "mossy_cobblestone_slab", "mossy_stone_brick_slab", "cobblestone_slab");
        like("prismarine_wall", "mossy_cobblestone_wall", "mossy_stone_brick_wall", "cobblestone_wall");
        like("dark_prismarine", "cobbled_deepslate", "mossy_stone_bricks", "stone_bricks");
        like("dark_prismarine_stairs", "cobbled_deepslate_stairs", "mossy_stone_brick_stairs", "stone_brick_stairs");
        like("dark_prismarine_slab", "cobbled_deepslate_slab", "mossy_stone_brick_slab", "stone_brick_slab");
        // white calcite (mountains, geodes): diorite is the other white stone
        like("calcite", "diorite", "andesite", "stone");
        like("cracked_stone_bricks", "stone_bricks");
        like("chiseled_stone_bricks", "stone_bricks");
        like("mossy_cobblestone", "cobblestone");
        like("mossy_stone_bricks", "stone_bricks");
        like("mossy_cobblestone_wall", "cobblestone_wall");
        like("mossy_stone_brick_wall", "stone_brick_wall");
        like("mossy_cobblestone_stairs", "cobblestone_stairs");
        like("mossy_stone_brick_stairs", "stone_brick_stairs");
        like("mossy_cobblestone_slab", "cobblestone_slab");
        like("mossy_stone_brick_slab", "stone_brick_slab");
        like("blast_furnace", "furnace");
        like("polished_andesite", "andesite");
        like("polished_diorite", "diorite");
        like("tuff", "andesite", "cobblestone");
        for (String c : new String[]{"white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow", "lime", "green",
                "cyan", "light_blue", "blue", "purple", "magenta", "pink"}) {
            like(c + "_stained_glass", "glass");          // tinted glass needs dye; clear glass for now
            like(c + "_stained_glass_pane", "glass_pane");
            like(c + "_terracotta", "terracotta");
        }
    }

    /** Quarter turns that make a design whose front is {@code front} face {@code faceTo}. */
    static int rotFor(String front, Direction faceTo) {
        String want = Blueprints.name(faceTo);
        for (int t = 0; t < 4; t++) if (Schematic.turnDir(front, t).equals(want)) return t;
        return 0;
    }

    /** Where the build goes, or null. Server thread. */
    static Placed site(ServerPlayer bot, Schematic base, Blueprints.Entry e, Spot spot) {
        String dim = Home.dim(bot.level());
        Placed p = switch (spot.kind()) {
            case 1 -> inFrontOf(base, e, spot.pos(), spot.dir(), dim);
            case 2 -> {
                int rot = spot.dir() == null ? 0 : rotFor(e.front(), spot.dir());
                yield new Placed(base.rotated(rot), spot.pos().offset(0, -e.ground(), 0), rot, dim);
            }
            case 3 -> {
                Blueprints.Build b = spot.previous();
                yield new Placed(base.rotated(b.rot()), new BlockPos(b.x(), b.y(), b.z()), b.rot(), b.dim());
            }
            default -> autoSite(bot, base, e);
        };
        if (p == null) return null;
        ServerLevel level = bot.level();
        if (level.isOutsideBuildHeight(p.origin()) || level.isOutsideBuildHeight(p.origin().offset(0, p.plan().sy - 1, 0))) return null;
        return p;
    }

    /** In front of a player: the front edge two blocks ahead of them, facing them. */
    static Placed inFrontOf(Schematic base, Blueprints.Entry e, BlockPos feet, Direction look, String dim) {
        int rot = rotFor(e.front(), look.getOpposite());
        Schematic r = base.rotated(rot);
        int x0, z0;
        switch (look) {
            case NORTH -> { x0 = feet.getX() - r.sx / 2; z0 = feet.getZ() - 2 - (r.sz - 1); }
            case SOUTH -> { x0 = feet.getX() - r.sx / 2; z0 = feet.getZ() + 2; }
            case EAST -> { x0 = feet.getX() + 2; z0 = feet.getZ() - r.sz / 2; }
            default -> { x0 = feet.getX() - 2 - (r.sx - 1); z0 = feet.getZ() - r.sz / 2; }
        }
        return new Placed(r, new BlockPos(x0, feet.getY() - 1 - e.ground(), z0), rot, dim);
    }

    private static final int BAD = Integer.MAX_VALUE;

    private record Cand(int x0, int z0, int rot, int w, int l) {}

    /** Somewhere flat and free next to the house (front towards it), else around the bot. Server thread. */
    static Placed autoSite(ServerPlayer bot, Schematic base, Blueprints.Entry e) {
        ServerLevel level = bot.level();
        String dim = Home.dim(level);
        Home.Base h = Home.get(bot);
        if (h != null && h.middle().distSqr(bot.blockPosition()) > 128 * 128) h = null; // far from home: build here
        List<int[]> avoid = avoidBoxes(bot, level, dim);
        Protection.Context ctx = Protection.scan(bot, 48);
        List<Cand> cands = new ArrayList<>();
        BlockPos anchor;
        if (h != null) {
            House.Site s = h.site();
            int n = s.size();
            for (Direction side : new Direction[]{Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH}) {
                if (side == s.door()) continue; // keep the front of the house clear
                int rot = rotFor(e.front(), side.getOpposite());
                int w = rot % 2 == 1 ? base.sz : base.sx, l = rot % 2 == 1 ? base.sx : base.sz;
                for (int gap : new int[]{3, 6, 9, 13}) {
                    for (int shift : new int[]{0, -6, 6, -12, 12}) {
                        int x0, z0;
                        switch (side) {
                            case EAST -> { x0 = s.x0() + n + gap; z0 = s.z0() + n / 2 - l / 2 + shift; }
                            case WEST -> { x0 = s.x0() - gap - w; z0 = s.z0() + n / 2 - l / 2 + shift; }
                            case SOUTH -> { z0 = s.z0() + n + gap; x0 = s.x0() + n / 2 - w / 2 + shift; }
                            default -> { z0 = s.z0() - gap - l; x0 = s.x0() + n / 2 - w / 2 + shift; }
                        }
                        cands.add(new Cand(x0, z0, rot, w, l));
                    }
                }
            }
            anchor = h.middle();
        } else {
            BlockPos f = BotPathing.feet(bot);
            for (int r : new int[]{4, 9, 14, 20, 28, 40, 56}) {
                for (int a = 0; a < 8; a++) {
                    double ang = a * Math.PI / 4;
                    int cx = f.getX() + (int) Math.round(Math.cos(ang) * (r + base.sx / 2.0));
                    int cz = f.getZ() + (int) Math.round(Math.sin(ang) * (r + base.sz / 2.0));
                    int dx = f.getX() - cx, dz = f.getZ() - cz;
                    Direction toBot = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
                    int rot = rotFor(e.front(), toBot);
                    int w = rot % 2 == 1 ? base.sz : base.sx, l = rot % 2 == 1 ? base.sx : base.sz;
                    cands.add(new Cand(cx - w / 2, cz - l / 2, rot, w, l));
                }
            }
            anchor = f;
        }
        int above = Math.max(1, Math.min(6, base.sy - e.ground() - 1));
        Cand best = null;
        int bestY = 0;
        long bestCost = Long.MAX_VALUE;
        for (Cand c : cands) {
            int gy = groundY(level, c, anchor.getY());
            if (gy == Integer.MIN_VALUE) continue;
            int cost = cost(level, c, gy, above, avoid, ctx);
            if (cost == BAD) continue;
            double dist = Math.sqrt(anchor.distSqr(new BlockPos(c.x0() + c.w() / 2, gy, c.z0() + c.l() / 2)));
            long total = cost + (long) (dist * 1.5);
            if (total < bestCost) { bestCost = total; best = c; bestY = gy; }
        }
        if (best == null) return null;
        return new Placed(base.rotated(best.rot()), new BlockPos(best.x0(), bestY - e.ground(), best.z0()), best.rot(), dim);
    }

    /** Houses, the farm and other builds (with a path round them): no building there. {x0, z0, x1, z1}. */
    private static List<int[]> avoidBoxes(ServerPlayer bot, ServerLevel level, String dim) {
        List<int[]> out = new ArrayList<>();
        for (Home.Base hb : Home.allIn(level)) {
            House.Site s = hb.site();
            out.add(new int[]{s.x0() - 2, s.z0() - 2, s.x0() + s.size() + 1, s.z0() + s.size() + 1});
        }
        for (Farm.Plot f : Farm.all()) {
            if (f.dim().equals(dim)) out.add(new int[]{f.x0() - 2, f.z0() - 2, f.x0() + Farm.SIZE + 1, f.z0() + Farm.SIZE + 1});
        }
        for (Blueprints.Build b : Blueprints.builds()) {
            if (!b.dim().equals(dim)) continue;
            out.add(new int[]{b.x() - 2, b.z() - 2, b.x() + b.sx() + 1, b.z() + b.sz() + 1});
        }
        return out;
    }

    /** The usual ground height under a footprint (the block you'd stand on), or MIN_VALUE if it's water or a cliff. */
    private static int groundY(ServerLevel level, Cand c, int nearY) {
        List<Integer> ys = new ArrayList<>();
        int[] fx = {0, c.w() / 2, c.w() - 1}, fz = {0, c.l() / 2, c.l() - 1};
        for (int ax : fx) {
            for (int az : fz) {
                BlockPos col = new BlockPos(c.x0() + ax, nearY, c.z0() + az);
                if (!level.isLoaded(col)) return Integer.MIN_VALUE;
                int found = Integer.MIN_VALUE;
                for (int dy = 10; dy >= -10; dy--) {
                    BlockPos p = col.offset(0, dy, 0);
                    if (!level.getFluidState(p).isEmpty()) {
                        if (!level.getFluidState(p.above()).isEmpty() || dy < 10) { found = Integer.MIN_VALUE + 1; break; }
                    }
                    BlockState s = level.getBlockState(p);
                    String path = SurvivalBrain.blockPath(s);
                    if (path.endsWith("_leaves") || SurvivalBrain.isLog(s)) continue;
                    if (Building.isSolid(level, p) && Building.isFree(level, p.above())) { found = p.getY(); break; }
                }
                if (found == Integer.MIN_VALUE + 1) return Integer.MIN_VALUE; // a lake or the sea
                if (found != Integer.MIN_VALUE) ys.add(found);
            }
        }
        if (ys.size() < 5) return Integer.MIN_VALUE;
        java.util.Collections.sort(ys);
        if (ys.get(ys.size() - 1) - ys.get(0) > 6) return Integer.MIN_VALUE;
        return ys.get(ys.size() / 2);
    }

    /** How much digging and filling a footprint takes, or BAD. Server thread. */
    private static int cost(ServerLevel level, Cand c, int gy, int above, List<int[]> avoid, Protection.Context ctx) {
        int stride = Math.max(1, (int) Math.ceil(Math.sqrt((double) c.w() * c.l() / 500.0)));
        int work = 0, fluids = 0, samples = 0;
        for (int dx = -1; dx <= c.w(); dx++) {
            for (int dz = -1; dz <= c.l(); dz++) {
                boolean ring = dx < 0 || dz < 0 || dx == c.w() || dz == c.l();
                if (!ring && stride > 1 && (dx % stride != 0 || dz % stride != 0)) continue;
                int x = c.x0() + dx, z = c.z0() + dz;
                BlockPos top = new BlockPos(x, gy, z);
                if (!level.isLoaded(top)) return BAD;
                for (int[] b : avoid) if (x >= b[0] && x <= b[2] && z >= b[1] && z <= b[3]) return BAD;
                if (ctx.isVillage(top)) return BAD;
                if (ring) continue;
                samples++;
                for (int dy = -1; dy <= above; dy++) {
                    BlockPos p = top.offset(0, dy, 0);
                    if (!level.getFluidState(p).isEmpty()) {
                        if (dy >= 0) fluids++;
                        continue;
                    }
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) {
                        if (dy <= 0) work += 2; // a hole to fill
                        continue;
                    }
                    String path = SurvivalBrain.blockPath(s);
                    if (SurvivalBrain.isLog(s) && Protection.isNaturalTreeLog(level, p)) { work += 4; continue; } // a tree to chop
                    if (Protection.isManMade(path) || SurvivalBrain.isLog(s) || path.contains("spawner")) return BAD;
                    if (dy >= 1) work += Building.isFree(level, p) ? 1 : 3;
                }
            }
        }
        if (samples == 0 || fluids * 6 > samples || work > samples * 4 + 30) return BAD;
        return work * stride * stride;
    }

    // ------------------------------------------------------------------------
    // What's left to do
    // ------------------------------------------------------------------------

    enum Op { CLEAR, PLACE, WATER, FIX }

    record Cell(BlockPos pos, State plan, Op op) {}

    /** The difference between a design and the world. */
    static final class Work {
        final List<Cell> clear = new ArrayList<>(), solid = new ArrayList<>(), fluid = new ArrayList<>(), attach = new ArrayList<>();
        /** Flowers, leaves, carpets...: placed if it has them, not needed for the build to count as done. */
        final List<Cell> decor = new ArrayList<>();
        final Map<String, Integer> needs = new TreeMap<>(), unobtainable = new TreeMap<>();
        int water, leftAlone, unloaded;
        /** Somebody's build in the way (an example of what). */
        int manMade;
        String manMadeWhat;

        boolean isEmpty() { return clear.isEmpty() && solid.isEmpty() && fluid.isEmpty() && attach.isEmpty() && decor.isEmpty(); }

        int size() { return clear.size() + solid.size() + fluid.size() + attach.size() + decor.size(); }

        /** What still has to happen for the build to count as done (decoration aside). */
        int essential() { return clear.size() + solid.size() + fluid.size() + attach.size(); }

        /** Blocks still to go in (for how far along it is; clearing a tree doesn't count). */
        int toPlace() { return solid.size() + fluid.size() + attach.size(); }
    }

    static State worldState(ServerLevel level, BlockPos p) {
        return State.ofWorld(String.valueOf(level.getBlockState(p)));
    }

    /** Blocks it leaves alone even if they're in the way: someone's stuff. */
    static boolean keepOut(String path) {
        return path.contains("chest") || path.contains("shulker_box") || path.equals("barrel") || path.endsWith("_bed")
                || path.contains("spawner") || path.equals("furnace") || path.equals("blast_furnace") || path.equals("smoker")
                || path.equals("beacon") || path.equals("enchanting_table") || path.equals("bedrock") || path.equals("hopper")
                || path.equals("dropper") || path.equals("dispenser") || path.equals("crafter") || path.equals("brewing_stand")
                || path.equals("lectern") || path.equals("jukebox") || path.equals("decorated_pot") || path.equals("chiseled_bookshelf")
                || path.endsWith("_sign") || path.equals("beehive") || path.equals("bee_nest") || path.equals("respawn_anchor")
                || path.equals("end_portal_frame") || path.equals("conduit") || path.equals("vault");
    }

    /** A plant that grew up out of one of the design's plant cells. Server thread. */
    private static boolean isGrowth(ServerLevel level, Placed p, BlockPos pos, State world) {
        String w = world.path();
        if (!Rules.isTallPlant(w)) return false;
        String grown = Rules.grown(w);
        BlockPos q = pos.below();
        for (int i = 0; i < 24; i++) {
            State below = worldState(level, q);
            if (!Rules.grown(below.path()).equals(grown)) return false;
            State planned = p.at(q);
            if (planned != null && Rules.isTallPlant(planned.path()) && Rules.grown(planned.path()).equals(grown)) return true;
            q = q.below();
        }
        return false;
    }

    /** Compares one layer of the design with the world and adds what needs doing. Server thread. */
    static void diffLayer(ServerLevel level, Placed p, int y, Work w) {
        Schematic s = p.plan();
        for (int z = 0; z < s.sz; z++) {
            for (int x = 0; x < s.sx; x++) {
                State plan = p.local(x, y, z);
                if (plan == null) continue;
                BlockPos pos = p.world(x, y, z);
                if (!level.isLoaded(pos)) { w.unloaded++; continue; }
                Rules.Kind k = Rules.kind(plan);
                if (k == Rules.Kind.KEEP) continue;
                if (Rules.isTallPlant(plan.path()) && y > 0) {
                    State under = p.local(x, y - 1, z);
                    if (under != null && Rules.grown(under.path()).equals(Rules.grown(plan.path()))) continue; // it grows there by itself
                }
                State world = worldState(level, pos);
                if (Rules.matches(plan, world) || looseWoodMatch(p, plan, world)) {
                    if (Rules.needsWaterlogging(plan, world)) { w.fluid.add(new Cell(pos, plan, Op.WATER)); w.water++; }
                    continue;
                }
                if (k == Rules.Kind.AIR) {
                    if (isGrowth(level, p, pos, world)) continue;
                    if (keepOut(world.path())) { w.leftAlone++; continue; }
                    noteManMade(w, world);
                    w.clear.add(new Cell(pos, plan, Op.CLEAR));
                    continue;
                }
                if (k == Rules.Kind.UNOBTAINABLE) {
                    w.unobtainable.merge(plan.path(), 1, Integer::sum);
                    continue;
                }
                boolean free = Building.isFree(level, pos);
                if ((k == Rules.Kind.BLOCK || k == Rules.Kind.COMPANION) && Rules.sameBlock(plan.path(), world.path())) {
                    // right block, pointing the wrong way: turn it
                    (Rules.phase(plan) == 2 ? w.attach : w.solid).add(new Cell(pos, plan, Op.FIX));
                    continue;
                }
                if (!free) {
                    if (keepOut(world.path())) { w.leftAlone++; continue; }
                    noteManMade(w, world);
                    w.clear.add(new Cell(pos, plan, Op.CLEAR));
                }
                if (k == Rules.Kind.WATER || k == Rules.Kind.LAVA) {
                    w.fluid.add(new Cell(pos, plan, Op.PLACE));
                    if (k == Rules.Kind.WATER) w.water++;
                    else w.needs.merge("lava_bucket", 1, Integer::sum);
                    continue;
                }
                if (Rules.decorative(plan.path())) {
                    w.decor.add(new Cell(pos, plan, Op.PLACE));
                    continue;
                }
                (Rules.phase(plan) == 2 ? w.attach : w.solid).add(new Cell(pos, plan, Op.PLACE));
                if (k == Rules.Kind.BLOCK) for (Rules.Need n : Rules.items(plan)) w.needs.merge(n.item(), n.count(), Integer::sum);
                if ("true".equals(plan.get("waterlogged"))) { w.fluid.add(new Cell(pos, plan, Op.WATER)); w.water++; }
            }
        }
    }

    private static void noteManMade(Work w, State world) {
        if (!Protection.isManMade(world.path())) return;
        w.manMade++;
        if (w.manMadeWhat == null) w.manMadeWhat = world.path().replace('_', ' ');
    }

    /** The whole design against the world, a layer per server call. Job thread. Null if the server didn't answer. */
    static Work scan(MinecraftServer server, ServerPlayer bot, Placed p) {
        Work w = new Work();
        for (int y = 0; y < p.plan().sy; y++) {
            final int yy = y;
            if (!onServer(server, () -> { diffLayer(bot.level(), p, yy, w); return true; }, false)) return null;
        }
        return w;
    }

    // ------------------------------------------------------------------------
    // Materials
    // ------------------------------------------------------------------------

    /** Most of one thing it will go out and gather for a build. */
    static final int MAX_GATHER = 1500;

    /**
     * Which items count for a need: exactly that one (spruce stairs aren't dark oak stairs). A few
     * recipe ingredients really can be any wood: "#planks", "#logs", "#wooden_slab". Any bed; coal or charcoal.
     */
    static Predicate<String> itemTest(String item) {
        switch (item) {
            case "#planks": return p -> p.endsWith("_planks");
            case "#logs": return p -> {
                String[] w = Rules.wood(p);
                return w != null && w[1].equals("log");
            };
            case "#wooden_slab": return p -> {
                String[] w = Rules.wood(p);
                return w != null && w[1].equals("slab");
            };
            case "coal": return p -> p.equals("coal") || p.equals("charcoal");
            case "#stone_crafting": return p -> p.equals("cobblestone") || p.equals("blackstone") || p.equals("cobbled_deepslate");
            case "#soul_fire_base": return p -> p.equals("soul_sand") || p.equals("soul_soil");
            default: break;
        }
        if (item.endsWith("_bed")) return p -> p.endsWith("_bed");
        return item::equals;
    }

    /** Per bot: the exact items the build in hand needs, so "any planks" recipes leave them alone. */
    private static final Map<java.util.UUID, Set<String>> RESERVED = new ConcurrentHashMap<>();

    /** {@link #itemTest}, with a wildcard ("#planks") not taking what the build needs exactly. */
    static Predicate<String> testFor(ServerPlayer bot, String item) {
        Predicate<String> base = itemTest(item);
        if (!item.startsWith("#")) return base;
        Set<String> reserved = RESERVED.getOrDefault(bot.getUUID(), Set.of());
        return p -> base.test(p) && !reserved.contains(p);
    }

    /** Anything made of this kind of wood (logs, planks, stairs...). */
    static Predicate<String> ofSpecies(String species) {
        return p -> {
            String[] w = Rules.wood(p);
            return w != null && w[0].equals(species);
        };
    }

    /** How many of each item it still needs beyond what's in its pockets. Server thread. */
    static Map<String, Integer> shortfall(ServerPlayer bot, Map<String, Integer> needs) {
        Map<String, Integer> out = new TreeMap<>();
        for (Map.Entry<String, Integer> e : needs.entrySet()) {
            int have = Gathering.countOf(bot, itemTest(e.getKey()));
            if (have < e.getValue()) out.put(e.getKey(), e.getValue() - have);
        }
        return out;
    }

    static String label(String item) {
        return switch (item) {
            case "#planks" -> "planks";
            case "#logs" -> "logs";
            case "#wooden_slab" -> "wooden slabs";
            case "#stone_crafting" -> "cobblestone";
            case "#soul_fire_base" -> "soul sand";
            default -> item.replace('_', ' ');
        };
    }

    // ------------------------------------------------------------------------
    // Recipes for building blocks (crafted from the inventory, like Gathering does)
    // ------------------------------------------------------------------------

    /** {@code out} of {@code item} from {@code in} (item, count pairs). {@code table}: needs a crafting table. */
    record Recipe(String item, int out, Map<String, Integer> in, boolean table, String tool) {}

    private static Map<String, Integer> in(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    private static final Map<String, Recipe> RECIPES = new HashMap<>();

    private static void r(String item, int out, boolean table, Object... kv) {
        RECIPES.put(item, new Recipe(item, out, in(kv), table, null));
    }

    /** Stairs (6 -> 4), slabs (3 -> 6) and walls (6 -> 6) of a stone. */
    private static void family(String base, String prefix, boolean wall) {
        r(prefix + "_stairs", 4, true, base, 6);
        r(prefix + "_slab", 6, true, base, 3);
        if (wall) r(prefix + "_wall", 6, true, base, 6);
    }

    static {
        r("#planks", 4, false, "#logs", 1);
        r("#wooden_slab", 6, true, "#planks", 3);
        r("stick", 4, false, "#planks", 2);
        r("crafting_table", 1, false, "#planks", 4);
        r("chest", 1, true, "#planks", 8);
        r("barrel", 1, true, "#planks", 6, "#wooden_slab", 2);
        r("composter", 1, true, "#wooden_slab", 7);
        r("ladder", 3, true, "stick", 7);
        r("torch", 4, false, "coal", 1, "stick", 1);
        r("stone_bricks", 4, false, "stone", 4);
        r("mossy_stone_bricks", 1, false, "stone_bricks", 1, "moss_block", 1);
        r("mossy_cobblestone", 1, false, "cobblestone", 1, "moss_block", 1);
        r("chiseled_stone_bricks", 1, false, "stone_brick_slab", 2);
        r("polished_andesite", 4, false, "andesite", 4);
        r("polished_diorite", 4, false, "diorite", 4);
        r("polished_granite", 4, false, "granite", 4);
        r("stone_pressure_plate", 1, false, "stone", 2);
        r("stone_button", 1, false, "stone", 1);
        r("furnace", 1, true, "cobblestone", 8);
        r("smoker", 1, true, "furnace", 1, "#logs", 4);
        r("glass_pane", 16, true, "glass", 6);
        r("iron_nugget", 9, false, "iron_ingot", 1);
        r("iron_chain", 1, true, "iron_ingot", 1, "iron_nugget", 2);
        r("lantern", 1, true, "iron_nugget", 8, "torch", 1);
        r("hopper", 1, true, "iron_ingot", 5, "chest", 1);
        r("bucket", 1, true, "iron_ingot", 3);
        r("cauldron", 1, true, "iron_ingot", 7);
        r("iron_bars", 16, true, "iron_ingot", 6);
        r("rail", 16, true, "iron_ingot", 6, "stick", 1);
        r("lightning_rod", 1, true, "copper_ingot", 3);
        r("coal_block", 1, true, "coal", 9);
        r("campfire", 1, true, "stick", 3, "coal", 1, "#logs", 3);
        r("redstone_torch", 1, false, "stick", 1, "redstone", 1);
        r("lever", 1, false, "stick", 1, "cobblestone", 1);
        r("repeater", 1, true, "redstone_torch", 2, "redstone", 1, "stone", 3);
        r("comparator", 1, true, "redstone_torch", 3, "quartz", 1, "stone", 3);
        r("piston", 1, true, "#planks", 3, "cobblestone", 4, "iron_ingot", 1, "redstone", 1);
        r("sticky_piston", 1, false, "piston", 1, "slime_ball", 1);
        r("observer", 1, true, "cobblestone", 6, "redstone", 2, "quartz", 1);
        r("dropper", 1, true, "cobblestone", 7, "redstone", 1);
        r("dispenser", 1, true, "cobblestone", 7, "redstone", 1, "bow", 1);
        r("redstone_lamp", 1, true, "redstone", 4, "glowstone", 1);
        r("note_block", 1, true, "#planks", 8, "redstone", 1);
        r("iron_block", 1, true, "iron_ingot", 9);
        r("gold_block", 1, true, "gold_ingot", 9);
        r("gold_nugget", 9, false, "gold_ingot", 1);
        r("paper", 3, true, "sugar_cane", 3);
        r("book", 1, false, "paper", 3, "leather", 1);
        r("bowl", 4, true, "#planks", 3);
        r("hay_block", 1, true, "wheat", 9);
        family("stone", "stone", false);
        family("cobblestone", "cobblestone", true);
        family("mossy_cobblestone", "mossy_cobblestone", true);
        family("stone_bricks", "stone_brick", true);
        family("mossy_stone_bricks", "mossy_stone_brick", true);
        family("andesite", "andesite", true);
        family("diorite", "diorite", true);
        family("granite", "granite", true);
        family("polished_andesite", "polished_andesite", false);
        family("polished_diorite", "polished_diorite", false);
        family("polished_granite", "polished_granite", false);
        family("cobbled_deepslate", "cobbled_deepslate", true);
    }

    /** The recipe for an item; wooden things are made from that same kind of wood. */
    static Recipe recipeFor(String item) {
        Recipe r = RECIPES.get(item);
        if (r != null) return r;
        // the recipe book every companion remembers (config/ai-companion/recipes.txt)
        RecipeBook.Recipe known = RecipeBook.craftingFor(item);
        if (known != null) {
            String station = known.type().equals("smithing") ? "smithing_table" : null;
            return new Recipe(item, known.out(), known.in(), known.needsTable(), station);
        }
        String[] w = Rules.wood(item);
        if (w == null) return null;
        String sp = w[0], planks = Rules.woodPath(sp, "planks"), log = Rules.woodPath(sp, "log");
        return switch (w[1]) {
            case "planks" -> new Recipe(item, sp.equals("bamboo") ? 2 : 4, in(log, 1), false, null);
            case "wood" -> new Recipe(item, 3, in(log, 4), false, null);
            case "stripped_log" -> new Recipe(item, 1, in(log, 1), false, "_axe"); // an axe on a log
            case "stripped_wood" -> new Recipe(item, 1, in(Rules.woodPath(sp, "wood"), 1), false, "_axe");
            case "stairs" -> new Recipe(item, 4, in(planks, 6), true, null);
            case "slab" -> new Recipe(item, 6, in(planks, 3), true, null);
            case "fence" -> new Recipe(item, 3, in(planks, 4, "stick", 2), true, null);
            case "fence_gate" -> new Recipe(item, 1, in(planks, 2, "stick", 4), true, null);
            case "trapdoor" -> new Recipe(item, 2, in(planks, 6), true, null);
            case "door" -> new Recipe(item, 3, in(planks, 6), true, null);
            case "pressure_plate" -> new Recipe(item, 1, in(planks, 2), false, null);
            case "button" -> new Recipe(item, 1, in(planks, 1), false, null);
            case "sign" -> new Recipe(item, 3, in(planks, 6, "stick", 1), true, null);
            default -> null;
        };
    }

    /** Can it get this one way or another (recipe, furnace, digging), in the Overworld? */
    static boolean obtainable(String item, int depth) {
        return obtainable(item, depth, false);
    }

    /** {@code nether}: it's in the Nether (crimson and warped trees grow there, nowhere else). */
    static boolean obtainable(String item, int depth, boolean nether) {
        if (depth > 6) return false;
        if (item.equals("#logs") || item.endsWith("_bed")) return true;
        String[] w = Rules.wood(item);
        if (w != null) {
            Woods.Wood wood = Woods.get(w[0]);
            if (wood != null && wood.nether() != nether) return false;
        }
        if (gatherTarget(item) != null || SMELTED.containsKey(item)) return true;
        if (item.equals("#stone_crafting") || item.equals("#soul_fire_base")) return true;
        RecipeBook.Recipe cook = RecipeBook.cookingFor(item);
        if (cook != null) {
            for (String i : cook.in().keySet()) if (!obtainable(i, depth + 1, nether)) return false;
            return true;
        }
        Recipe r = recipeFor(item);
        if (r == null) return false;
        for (String i : r.in().keySet()) if (!obtainable(i, depth + 1, nether)) return false;
        return true;
    }

    /** Furnace outputs it can make: output -> Smelting phrase. */
    private static final Map<String, String> SMELTED = Map.of("stone", "stone", "glass", "glass", "iron_ingot", "iron",
            "gold_ingot", "gold", "copper_ingot", "copper", "charcoal", "wood");

    /**
     * Gets {@code want} of {@code item} into the pockets: chests first, then the recipe (making
     * its ingredients the same way), the furnace, or digging and chopping (the right kind of
     * tree for a kind of wood). True if it has them. Job thread.
     */
    static boolean make(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, String item, int want, int depth,
                        Predicate<String> keep) throws InterruptedException {
        Predicate<String> test = testFor(bot, item);
        int have = onServer(server, () -> Gathering.countOf(bot, test), 0);
        if (have >= want) return true;
        if (depth > 6 || !SurvivalBrain.canContinue(b)) return false;
        have += Storage.withdraw(server, bot, b, test, want - have, depth == 0 ? label(item) : null);
        if (have >= want) return true;
        Recipe r = recipeFor(item);
        try {
            if (item.endsWith("_bed")) {
                House.ensureBed(server, bot, b);
                return onServer(server, () -> Gathering.countOf(bot, test), 0) >= want;
            }
            if (r == null) {
                String phrase = SMELTED.get(item);
                RecipeBook.Recipe cook = phrase == null ? RecipeBook.cookingFor(item) : null;
                if (phrase != null) {
                    Smelting.Recipe sr = Smelting.recipeFor(phrase);
                    if (sr != null) Smelting.smeltFor(server, bot, b, sr, want - have, null);
                } else if (cook != null && !cook.in().isEmpty()) {
                    // from the recipe book: make the input, then cook it ("smooth stone: stone in a furnace")
                    String input = cook.in().keySet().iterator().next();
                    int need = (want - have + cook.out() - 1) / cook.out();
                    int inHand = onServer(server, () -> Gathering.countOf(bot, testFor(bot, input)), 0);
                    make(server, bot, b, input, inHand + need, depth + 1, keep);
                    Predicate<String> inTest = testFor(bot, input);
                    Smelting.smeltFor(server, bot, b, new Smelting.Recipe(item, label(item), inTest, null), want - have, null);
                } else {
                    MiningSkills.Target t = switch (item) {
                        case "#logs" -> MiningSkills.resolve("wood");
                        case "#stone_crafting" -> MiningSkills.resolve("stone");
                        case "#soul_fire_base" -> MiningSkills.resolve("soul sand");
                        default -> gatherTarget(item);
                    };
                    if (t == null) return false;
                    if (depth == 0 || want - have >= 4) {
                        String[] w = Rules.wood(item);
                        String what = w != null && w[1].equals("log") ? w[0].replace('_', ' ') + " logs (" + Woods.look(w[0]) + ")" : label(item);
                        SurvivalBrain.maybeSay(server, b, HumanChat.pick("need " + (want - have) + " " + what + ", gonna go get some",
                                "getting " + what + " for the build"), 0.8);
                    }
                    MiningSkills.collect(server, bot, b, t, want - have, true);
                }
                return onServer(server, () -> Gathering.countOf(bot, test), 0) >= want;
            }
            int crafts = (want - have + r.out() - 1) / r.out();
            if (r.tool() != null && onServer(server, () -> Building.firstItem(bot, p -> p.endsWith(r.tool())) == null, true)) {
                if (r.tool().equals("_axe")) {
                    Gathering.Craftable axe = Gathering.craftable("stone axe");
                    if (axe == null || !Gathering.makeSure(server, bot, b, axe, 1)) return false;
                    SurvivalBrain.keep(bot, keep);
                } else if (!make(server, bot, b, r.tool(), 1, depth + 1, keep)) {
                    return false; // a smithing table for smithing
                }
            }
            if (r.table() && onServer(server, () -> Gathering.countOf(bot, "crafting_table"::equals) == 0, true)) {
                if (!make(server, bot, b, "crafting_table", 1, depth + 1, keep)) return false;
            }
            for (Map.Entry<String, Integer> e : r.in().entrySet()) {
                make(server, bot, b, e.getKey(), e.getValue() * crafts, depth + 1, keep);
                if (!SurvivalBrain.canContinue(b)) return false;
            }
            onServer(server, () -> {
                for (int c = 0; c < crafts; c++) {
                    for (Map.Entry<String, Integer> e : r.in().entrySet()) {
                        if (Gathering.countOf(bot, testFor(bot, e.getKey())) < e.getValue()) return null;
                    }
                    if (!Gathering.roomFor(bot, test, r.out())) {
                        Storage.makeRoom(bot, 2);
                        if (!Gathering.roomFor(bot, test, r.out())) return null;
                    }
                    String madeOf = null;
                    for (Map.Entry<String, Integer> e : r.in().entrySet()) {
                        String first = SurvivalBrain.take(bot, testFor(bot, e.getKey()), e.getValue());
                        if (madeOf == null && first != null && Rules.wood(first) != null) madeOf = first;
                    }
                    String out = item;
                    if (item.startsWith("#")) { // any-wood planks/slabs come out as the wood they were made of
                        String[] from = madeOf == null ? null : Rules.wood(madeOf);
                        String kind = item.equals("#planks") ? "planks" : "slab";
                        out = Rules.woodPath(from == null ? "oak" : from[0], kind);
                    }
                    SurvivalBrain.give(bot, out, r.out());
                }
                Motions.swingArm(bot);
                return null;
            }, null);
            return onServer(server, () -> Gathering.countOf(bot, test), 0) >= want;
        } finally {
            SurvivalBrain.keep(bot, keep); // crafting, smelting and gathering reset it
        }
    }

    private static final Set<String> DIGGABLE = Set.of("dirt", "sand", "red_sand", "gravel", "cobblestone", "cobbled_deepslate",
            "netherrack", "soul_sand", "soul_soil", "sugar_cane", "cactus", "bamboo", "blackstone", "coal", "redstone", "quartz",
            "raw_iron", "andesite", "diorite", "granite", "calcite", "tuff", "moss_block", "clay", "basalt");

    /** Where to get it out in the world (dig, chop, cut), or null. Logs: that kind of tree. */
    static MiningSkills.Target gatherTarget(String item) {
        String[] w = Rules.wood(item);
        if (w != null && w[1].equals("log") && !w[0].equals("bamboo")) {
            return MiningSkills.resolve(w[0].replace('_', ' '));
        }
        if (!DIGGABLE.contains(item)) return null;
        return switch (item) {
            case "cobblestone" -> MiningSkills.resolve("stone");
            case "cobbled_deepslate" -> MiningSkills.resolve("deepslate");
            case "raw_iron" -> MiningSkills.resolve("iron");
            default -> MiningSkills.resolve(item.replace('_', ' '));
        };
    }

    private record Seen(boolean trees, long at, BlockPos where) {}

    private static final Map<String, Seen> TREES_SEEN = new ConcurrentHashMap<>();

    /** Is there a tree of this kind within reach of a walk? (Remembered for a few minutes: it's a big look.) Server thread. */
    private static boolean treesNear(ServerPlayer bot, SurvivalBrain.Brain b, String species) {
        String key = b.name + "|" + species;
        Seen s = TREES_SEEN.get(key);
        BlockPos here = bot.blockPosition();
        if (s != null && System.currentTimeMillis() - s.at() < 5 * 60_000L && s.where().distSqr(here) < 32 * 32) return s.trees();
        MiningSkills.Target t = MiningSkills.resolve(species.replace('_', ' '));
        boolean found = false;
        if (t != null) {
            try {
                found = MiningSkills.findTarget(bot, b, t, Protection.scan(bot, 48), 0) != null;
            } catch (Throwable ex) {
                found = false;
            }
        }
        TREES_SEEN.put(key, new Seen(found, System.currentTimeMillis(), here));
        return found;
    }

    /**
     * The exact block can't be had: picks the closest-looking one it can get and says so. Wood
     * goes by colour (no dark oak around: spruce, the next darkest brown); other blocks by
     * {@link #LOOKALIKES}. Remembered with the build, and swapped back once the real thing turns up.
     */
    static void standIn(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Placed p, String item, boolean nether) {
        String[] w = Rules.wood(item);
        if (w != null) {
            String sp = w[0];
            if (p.swaps().containsKey("wood:" + sp) || p.swaps().containsKey("upgraded:wood:" + sp)) return;
            for (String t : Woods.closest(sp)) {
                Woods.Wood tw = Woods.get(t);
                if (tw == null || tw.nether() != nether) continue;
                boolean ok = onServer(server, () -> Gathering.countOf(bot, ofSpecies(t)) + Storage.stockOf(bot.level(), ofSpecies(t)) > 0
                        || treesNear(bot, b, t), false);
                if (!ok) continue;
                p.swaps().put("wood:" + sp, t);
                Woods.Wood sw = Woods.get(sp);
                String why = sw != null && sw.nether() ? " (it only grows in the nether)" : " anywhere near here";
                HumanChat.say(server, b.name, "no " + sp.replace('_', ' ') + " trees" + why + ", so i'm using " + t.replace('_', ' ')
                        + " instead: " + Woods.look(t) + " where it should be " + Woods.look(sp) + ". bring me some "
                        + sp.replace('_', ' ') + (sw != null && sw.nether() ? " stems" : " logs") + " and i'll swap it back in");
                return;
            }
            return;
        }
        if (p.swaps().containsKey(item) || p.swaps().containsKey("upgraded:" + item)) return;
        List<String> opts = LOOKALIKES.get(item);
        if (opts == null) return;
        for (String o : opts) {
            boolean ok = obtainable(o, 0, nether) || onServer(server, () -> Gathering.countOf(bot, o::equals)
                    + Storage.stockOf(bot.level(), o::equals) > 0, false);
            if (!ok) continue;
            p.swaps().put(item, o);
            HumanChat.say(server, b.name, "can't get " + label(item) + ", using " + label(o)
                    + " instead, it's the closest look i can make. put some " + label(item) + " in a chest and i'll swap them in");
            return;
        }
    }

    /** Stand-ins whose real block is now in the pockets or a chest: dropped, so the real thing goes in. Job thread. */
    static void upgradeStandIns(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Placed p) {
        for (String key : new ArrayList<>(p.swaps().keySet())) {
            if (key.equals(LEGACY)) continue;
            Predicate<String> real = key.startsWith("wood:") ? ofSpecies(key.substring(5)) : key::equals;
            // enough of the real thing for every block the stand-in took (a log makes 4 planks' worth)
            int cells = 0;
            Schematic s = p.plan();
            for (int y = 0; y < s.sy; y++) for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++) {
                State st = s.at(x, y, z);
                if (st == null || Rules.decorative(st.path())) continue;
                String[] w = Rules.wood(st.path());
                if (key.startsWith("wood:") ? w != null && w[0].equals(key.substring(5)) : st.path().equals(key)) cells++;
            }
            final int need = cells;
            boolean have = onServer(server, () -> {
                int n = 0;
                Predicate<String> logs = it -> {
                    if (!key.startsWith("wood:") || !real.test(it)) return false;
                    String[] w = Rules.wood(it);
                    return w != null && w[1].equals("log");
                };
                n += Gathering.countOf(bot, real) + Storage.stockOf(bot.level(), real);
                n += 3 * (Gathering.countOf(bot, logs) + Storage.stockOf(bot.level(), logs));
                return n >= need;
            }, false);
            if (!have) continue;
            p.swaps().put("upgraded:" + key, "1");
            String was = p.swaps().remove(key);
            String name = key.startsWith("wood:") ? key.substring(5).replace('_', ' ') : label(key);
            if (was == null) continue;
            HumanChat.say(server, b.name, "got " + name + " now, gonna swap it in for the " + label(was) + " i used");
        }
    }

    /**
     * Chests first, then craft, smelt, dig or chop what it can. What it can't get gets a stand-in
     * that looks close. Returns what's still missing. Job thread.
     */
    static Map<String, Integer> gather(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Map<String, Integer> needs,
                                       Predicate<String> keep, Placed p) throws InterruptedException {
        Map<String, Integer> missing = onServer(server, () -> shortfall(bot, needs), null);
        if (missing == null || missing.isEmpty()) return missing == null ? Map.of() : missing;
        Set<String> reserved = new HashSet<>();
        reserved.addAll(needs.keySet()); // the exact blocks the build places (logs it can always chop more of)
        RESERVED.put(bot.getUUID(), reserved);
        try {
            return gatherAll(server, bot, b, needs, keep, p, missing);
        } finally {
            RESERVED.remove(bot.getUUID());
        }
    }

    private static Map<String, Integer> gatherAll(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Map<String, Integer> needs,
                                                  Predicate<String> keep, Placed p, Map<String, Integer> missing) throws InterruptedException {
        boolean nether = onServer(server, () -> Home.dim(bot.level()).contains("nether"), false);
        for (Map.Entry<String, Integer> e : missing.entrySet()) {
            if (!SurvivalBrain.canContinue(b)) break;
            Storage.withdraw(server, bot, b, itemTest(e.getKey()), e.getValue(), label(e.getKey()));
        }
        missing = onServer(server, () -> shortfall(bot, needs), missing);
        // what isn't in the chests might be for sale in somebody's shop
        for (Map.Entry<String, Integer> e : new ArrayList<>(missing.entrySet())) {
            if (!SurvivalBrain.canContinue(b)) break;
            Economy.buyFromShops(server, bot, b, itemTest(e.getKey()), e.getKey(), e.getValue());
        }
        missing = onServer(server, () -> shortfall(bot, needs), missing);
        for (Map.Entry<String, Integer> e : new ArrayList<>(missing.entrySet())) {
            if (!SurvivalBrain.canContinue(b)) break;
            String item = e.getKey();
            int n = e.getValue();
            if (!obtainable(item, 0, nether)) {
                if (p != null) standIn(server, bot, b, p, item, nether);
                continue;
            }
            if (n > MAX_GATHER) continue;
            int have = onServer(server, () -> Gathering.countOf(bot, itemTest(item)), 0);
            make(server, bot, b, item, have + n, 0, keep);
            int got = onServer(server, () -> Gathering.countOf(bot, itemTest(item)), 0) - have;
            String[] w = Rules.wood(item);
            if (p != null && w != null && got < n / 4 && !onServer(server, () -> treesNear(bot, b, w[0]), true)) {
                standIn(server, bot, b, p, item, nether); // that kind of tree just isn't around
            } else if (p != null && w == null && got < n / 4 && LOOKALIKES.containsKey(item)) {
                standIn(server, bot, b, p, item, nether); // couldn't find it (calcite, moss...): the closest look
            }
        }
        return onServer(server, () -> shortfall(bot, needs), missing);
    }

    // ------------------------------------------------------------------------
    // "craft me 2 hoppers": anything in the recipe book, made from scratch
    // ------------------------------------------------------------------------

    private static final java.util.regex.Pattern CRAFT = java.util.regex.Pattern.compile(
            "^(?:(?:can|could|would|will) (?:you|u) |(?:hey|yo|ok|okay|pls|please|now|also|go) )*(?:craft|make|smith|forge)(?: me| us)?"
            + "(?: (\\d+|a|an|one|two|three|four|five|six|eight|ten|some|a few|a couple of))? (.+?)"
            + "(?: (?:for me|for us|pls|please|now|too))*[!.?\\s]*$");

    /** The item a "craft me ..." message asks for (in the recipe book), and how many; null if it's not that. */
    static Object[] parseCraft(String text) {
        String m = text == null ? "" : text.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
        if (m.length() > 70) return null;
        java.util.regex.Matcher cm = CRAFT.matcher(m);
        if (!cm.find()) return null;
        String item = RecipeBook.itemFor(cm.group(2));
        if (item == null) item = knownItem(cm.group(2));
        if (item == null) return null;
        String w = cm.group(1);
        int n = 1;
        if (w != null) {
            n = switch (w) {
                case "two", "a couple of" -> 2;
                case "three", "a few" -> 3;
                case "four" -> 4;
                case "five" -> 5;
                case "six" -> 6;
                case "eight" -> 8;
                case "ten" -> 10;
                case "some" -> Math.max(1, RecipeBook.find(item) == null ? 1 : RecipeBook.find(item).out());
                default -> w.matches("\\d+") ? Integer.parseInt(w) : 1;
            };
        }
        return new Object[]{item, Math.max(1, Math.min(n, 256))};
    }

    /** An item the bot can make from its own recipes ("lanterns" -> lantern), or null. */
    static String knownItem(String phrase) {
        String q = phrase.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_ ]", " ")
                .replaceAll("\\b(a|an|the|some|me|us|of|few|couple|my|your|new)\\b", " ").trim().replaceAll("\\s+", "_");
        if (q.isEmpty()) return null;
        for (String c : new String[]{q, q.replaceAll("ies$", "y"), q.replaceAll("es$", ""), q.replaceAll("s$", "")}) {
            if (RECIPES.containsKey(c) && !c.startsWith("#")) return c;
            String[] w = Rules.wood(c);
            if (w != null && recipeFor(c) != null) return c;
        }
        return null;
    }

    /** "craft me a hopper": a job that makes it (gathering and crafting what goes in) and brings it over. */
    static MiningSkills.Request craftRequest(String text, java.util.UUID requester) {
        Object[] p = parseCraft(text);
        if (p == null) return null;
        String item = (String) p[0];
        int n = (Integer) p[1];
        String what = n + " " + label(item);
        return new MiningSkills.Request("craft " + what, HumanChat.pick("ok, making " + what, "sure, i know how to make that", "on it"),
                (server, bot, b) -> {
                    Predicate<String> keep = it -> it.equals(item);
                    SurvivalBrain.keep(bot, keep);
                    try {
                        int have = onServer(server, () -> Gathering.countOf(bot, itemTest(item)), 0);
                        boolean ok = make(server, bot, b, item, have + n, 0, keep);
                        if (!SurvivalBrain.canContinue(b)) return;
                        if (!ok) {
                            List<String> miss = onServer(server, () -> missingFor(bot, item, n, 0), List.of());
                            HumanChat.say(server, b.name, "couldn't make the " + label(item)
                                    + (miss.isEmpty() ? "" : ", i'd need " + String.join(", ", miss.subList(0, Math.min(5, miss.size())))));
                            return;
                        }
                        if (requester != null) Gathering.deliver(server, bot, b, requester, itemTest(item), label(item), n);
                        else HumanChat.say(server, b.name, "made " + what);
                    } finally {
                        SurvivalBrain.keep(bot, null);
                    }
                });
    }

    /** The things it's short of for {@code n} of an item, down the recipe tree: "3 diamond", "1 blaze rod". Server thread. */
    static List<String> missingFor(ServerPlayer bot, String item, int n, int depth) {
        List<String> out = new ArrayList<>();
        int have = Gathering.countOf(bot, itemTest(item)) + Storage.stockOf(bot.level(), itemTest(item));
        if (have >= n || depth > 5) return out;
        int short_ = n - have;
        Recipe r = recipeFor(item);
        RecipeBook.Recipe cook = RecipeBook.cookingFor(item);
        if (r != null) {
            int crafts = (short_ + r.out() - 1) / r.out();
            for (Map.Entry<String, Integer> e : r.in().entrySet()) out.addAll(missingFor(bot, e.getKey(), e.getValue() * crafts, depth + 1));
        } else if (cook != null && !cook.in().isEmpty()) {
            out.addAll(missingFor(bot, cook.in().keySet().iterator().next(), short_, depth + 1));
        } else {
            out.add(short_ + " " + label(item));
        }
        return out;
    }

    /** "12 observers, 4 hoppers and 3 more things". */
    static String listOf(Map<String, Integer> m, int max) {
        List<Map.Entry<String, Integer>> es = new ArrayList<>(m.entrySet());
        es.sort((a, c) -> c.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < es.size() && i < max; i++) {
            if (i > 0) sb.append(", ");
            sb.append(es.get(i).getValue()).append(' ').append(label(es.get(i).getKey()));
        }
        if (es.size() > max) sb.append(" and ").append(es.size() - max).append(" more things");
        return sb.toString();
    }

    /** "what do you need for the cactus farm": the whole design against pockets and chests. Server thread. */
    static String describeNeeds(ServerPlayer bot, Blueprints.Entry e) {
        Schematic s;
        try {
            s = Blueprints.plan(e);
        } catch (IOException ex) {
            return "can't read that one: " + ex.getMessage();
        }
        Map<String, Integer> needs = new TreeMap<>();
        Map<String, Integer> unob = new TreeMap<>();
        int water = 0;
        for (int y = 0; y < s.sy; y++) for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++) {
            State st = s.at(x, y, z);
            if (st == null) continue;
            switch (Rules.kind(st)) {
                case BLOCK -> { for (Rules.Need n : Rules.items(st)) needs.merge(n.item(), n.count(), Integer::sum); }
                case WATER -> water++;
                case LAVA -> needs.merge("lava_bucket", 1, Integer::sum);
                case UNOBTAINABLE -> unob.merge(st.path(), 1, Integer::sum);
                default -> { }
            }
            if ("true".equals(st.get("waterlogged"))) water++;
        }
        if (needs.isEmpty() && water == 0) return "the " + e.name() + " doesn't need anything, it's empty";
        Map<String, Integer> still = new TreeMap<>();
        for (Map.Entry<String, Integer> n : needs.entrySet()) {
            int have = Gathering.countOf(bot, itemTest(n.getKey())) + Storage.stockOf(bot.level(), itemTest(n.getKey()));
            if (have < n.getValue()) still.put(n.getKey(), n.getValue() - have);
        }
        StringBuilder sb = new StringBuilder("the " + e.name() + " (" + s.sx + "x" + s.sy + "x" + s.sz + ") takes "
                + listOf(needs, 8) + (water > 0 ? ", " + water + " water" : ""));
        if (still.isEmpty()) sb.append(". we've got all of it");
        else {
            List<String> self = new ArrayList<>();
            for (String k : still.keySet()) if (obtainable(k, 0)) self.add(label(k));
            sb.append(". still need ").append(listOf(still, 8));
            if (!self.isEmpty()) sb.append(" (i can get the ").append(String.join(", ", self.subList(0, Math.min(4, self.size())))).append(" myself)");
        }
        if (!unob.isEmpty()) sb.append(". can't get ").append(listOf(unob, 3)).append(" in survival, i'll skip those");
        return sb.toString();
    }

    // ------------------------------------------------------------------------
    // Setting blocks
    // ------------------------------------------------------------------------

    /** The game block state for a plan state, or null if the game doesn't have that block. */
    static BlockState toBlockState(State st) {
        Identifier id = Identifier.tryParse(st.id());
        if (id == null) return null;
        Object o = BuiltInRegistries.BLOCK.getValue(id);
        if (!(o instanceof Block block)) return null;
        Identifier back = BuiltInRegistries.BLOCK.getKey(block);
        if (back == null || !back.toString().equals(st.id())) return null; // unknown id comes back as air
        BlockState bs = block.defaultBlockState();
        var def = block.getStateDefinition();
        for (Map.Entry<String, String> e : st.props().entrySet()) {
            Property<?> pr = def.getProperty(e.getKey());
            if (pr != null) bs = withValue(bs, pr, e.getValue());
        }
        return bs;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState withValue(BlockState s, Property p, String v) {
        Optional o = p.getValue(v);
        return o.isPresent() ? (BlockState) s.setValue(p, (Comparable) o.get()) : s;
    }

    /** "minecraft:the_nether" from a level's dimension key. */
    private static String dimensionId(ServerLevel level) {
        String s = String.valueOf(level.dimension());
        int i = s.lastIndexOf(" / ");
        String id = i >= 0 ? s.substring(i + 3).replace("]", "").trim() : "minecraft:overworld";
        return id.matches("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+") ? id : "minecraft:overworld";
    }

    /** Puts exactly this block state at {@code pos}. Server thread. */
    static boolean setState(ServerLevel level, BlockPos pos, State st) {
        try {
            BlockState bs = toBlockState(st);
            if (bs != null) {
                level.setBlock(pos, bs, 3);
                if (Rules.sameBlock(st.path(), worldState(level, pos).path())) return true;
            }
        } catch (Throwable t) {
            LOGGER.debug("[builder] direct set of {} failed: {}", st, t.toString());
        }
        // fall back on the setblock command (same result, just slower)
        try {
            MinecraftServer server = level.getServer();
            String cmd = "execute in " + dimensionId(level) + " run setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + st;
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput()
                    .withMaximumPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS), cmd);
        } catch (Throwable t) {
            LOGGER.warn("[builder] couldn't place {} at {}: {}", st, pos, t.toString());
        }
        return Rules.sameBlock(st.path(), worldState(level, pos).path());
    }

    private static boolean occupies(ServerPlayer bot, BlockPos pos) {
        Vec3 p = bot.position();
        return Math.abs(p.x - (pos.getX() + 0.5)) < 0.8 && Math.abs(p.z - (pos.getZ() + 0.5)) < 0.8
                && pos.getY() >= Math.floor(p.y) - 0.01 && pos.getY() <= Math.floor(p.y + 1.79);
    }

    private static boolean inReach(ServerPlayer bot, BlockPos pos, double reach) {
        return bot.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= reach;
    }

    static final int OK = 0, NO_ITEMS = 1, SKIP = 2, IN_THE_WAY = 3, FAR = 4, FAILED = 5;

    /** The item it'll use for a need (any planks for planks...), or null if it hasn't got enough. */
    private static String pickItem(ServerPlayer bot, Rules.Need n) {
        if (Gathering.countOf(bot, n.item()::equals) >= n.count()) return n.item();
        Predicate<String> any = itemTest(n.item());
        String sub = Building.firstItem(bot, any);
        return sub != null && Gathering.countOf(bot, sub::equals) >= n.count() ? sub : null;
    }

    /** Does it have what one cell takes? Server thread. */
    static boolean hasItemsFor(ServerPlayer bot, Cell c) {
        if (c.op() != Op.PLACE || Rules.kind(c.plan()) != Rules.Kind.BLOCK) return true;
        for (Rules.Need n : Rules.items(c.plan())) if (pickItem(bot, n) == null) return false;
        return true;
    }

    /** Places one cell of the design. Server thread. */
    static int placeCell(ServerPlayer bot, Placed p, Cell c) {
        ServerLevel level = bot.level();
        BlockPos pos = c.pos();
        State plan = c.plan();
        State world = worldState(level, pos);
        if (!inReach(bot, pos, 4.9)) return FAR;
        if (c.op() == Op.FIX) {
            if (!Rules.sameBlock(plan.path(), world.path())) return SKIP;
            State fixed = Rules.placeState(plan).withId(world.id());
            if ("true".equals(world.get("waterlogged")) && !"double".equals(plan.get("type"))) fixed = fixed.with("waterlogged", "true");
            if (plan.path().endsWith("_slab") && "double".equals(plan.get("type")) && !"double".equals(world.get("type"))) {
                if (Gathering.countOf(bot, world.path()::equals) == 0) return NO_ITEMS; // the second half
                SurvivalBrain.take(bot, world.path()::equals, 1);
            }
            io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, Vec3.atCenterOf(pos));
            Motions.swingArm(bot);
            return setState(level, pos, fixed) ? OK : FAILED;
        }
        if (Rules.matches(plan, world)) return OK;
        if (!Building.isFree(level, pos)) return SKIP; // clearing it didn't work
        if (occupies(bot, pos)) return IN_THE_WAY;
        Rules.Kind k = Rules.kind(plan);
        if (k == Rules.Kind.COMPANION) {
            if (!companionReady(level, p, pos, plan)) return SKIP;
            Motions.swingArm(bot);
            return setState(level, pos, Rules.placeState(plan)) ? OK : FAILED;
        }
        if (k != Rules.Kind.BLOCK) return SKIP;
        List<Rules.Need> needs = Rules.items(plan);
        List<String> using = new ArrayList<>();
        for (Rules.Need n : needs) {
            String it = pickItem(bot, n);
            if (it == null) return NO_ITEMS;
            using.add(it);
        }
        if (Rules.isGravity(plan.path()) && Building.isFree(level, pos.below()) && p.contains(pos.below())) return SKIP; // would just fall
        State target = Rules.placeState(plan);
        if (target.path().endsWith("_bed") && !using.isEmpty() && using.get(0).endsWith("_bed")) {
            target = target.withId(using.get(0)); // the bed it has, in its own colour
        }
        // plain cubes go in like a player places them
        if (Rules.byHand(plan) && target.path().equals(plan.path())) {
            Building.placeAt(bot, pos, using.get(0));
            State now = worldState(level, pos);
            if (Rules.sameBlock(plan.path(), now.path())) return OK;
            if (!Building.isFree(level, pos)) return FAILED;
        }
        io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, Vec3.atCenterOf(pos));
        for (int i = 0; i < needs.size(); i++) {
            if (Gathering.countOf(bot, using.get(i)::equals) < needs.get(i).count()) return NO_ITEMS;
        }
        for (int i = 0; i < needs.size(); i++) SurvivalBrain.take(bot, using.get(i)::equals, needs.get(i).count());
        Motions.swingArm(bot);
        if (!setState(level, pos, target)) {
            for (int i = 0; i < needs.size(); i++) SurvivalBrain.give(bot, using.get(i), needs.get(i).count());
            return FAILED;
        }
        placeCompanions(level, p, pos, plan);
        return OK;
    }

    /** The other half of a door, bed or tall flower goes in right after the first. Server thread. */
    private static void placeCompanions(ServerLevel level, Placed p, BlockPos pos, State plan) {
        for (Direction d : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos q = pos.relative(d);
            State other = p.at(q);
            if (other == null || Rules.kind(other) != Rules.Kind.COMPANION || !other.id().equals(plan.id())) continue;
            if (!Building.isFree(level, q)) continue;
            setState(level, q, Rules.placeState(other));
        }
    }

    /** The first half of a two-block thing is there already. */
    private static boolean companionReady(ServerLevel level, Placed p, BlockPos pos, State plan) {
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos q = pos.relative(d);
            State other = p.at(q);
            if (other == null || !other.id().equals(plan.id()) || Rules.kind(other) == Rules.Kind.COMPANION) continue;
            if (Rules.sameBlock(plan.path(), worldState(level, q).path())) return true;
        }
        return false;
    }

    /** Water or lava into a cell, or water into a waterloggable block, from a bucket. Server thread. */
    static int placeFluid(ServerPlayer bot, Cell c) {
        ServerLevel level = bot.level();
        BlockPos pos = c.pos();
        if (!inReach(bot, pos, 4.9)) return FAR;
        State world = worldState(level, pos);
        boolean lava = Rules.kind(c.plan()) == Rules.Kind.LAVA;
        String full = lava ? "lava_bucket" : "water_bucket";
        if (c.op() == Op.WATER) {
            if (!Rules.needsWaterlogging(c.plan(), world)) return Rules.sameBlock(c.plan().path(), world.path()) ? OK : SKIP;
        } else {
            if (Rules.matches(c.plan(), world)) return OK;
            if (!Building.isFree(level, pos)) return SKIP;
        }
        if (Gathering.countOf(bot, full::equals) == 0) return NO_ITEMS;
        io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, Vec3.atCenterOf(pos));
        SurvivalBrain.take(bot, full::equals, 1);
        SurvivalBrain.give(bot, "bucket", 1);
        Motions.swingArm(bot);
        State target = c.op() == Op.WATER ? world.with("waterlogged", "true") : State.parse(lava ? "minecraft:lava" : "minecraft:water");
        if (setState(level, pos, target)) return OK;
        SurvivalBrain.take(bot, "bucket"::equals, 1);
        SurvivalBrain.give(bot, full, 1);
        return FAILED;
    }

    // ------------------------------------------------------------------------
    // Doing it
    // ------------------------------------------------------------------------

    /** Within {@code reach} of the block, walking (and climbing/bridging) there if needed. Job thread. */
    static boolean approach(MinecraftServer server, ServerPlayer bot, BlockPos t, double reach) throws InterruptedException {
        if (onServer(server, () -> inReach(bot, t, reach), false)) return true;
        SurvivalBrain.waitWhileFighting(bot);
        BotPathing.Options o = BotPathing.Options.full();
        o.timeoutTicks = 20 * 30;
        BotPathing.goToBlocking(bot, ActionPathfinder.reach(t.getX(), t.getY(), t.getZ(), reach - 0.4), o, 32_000L);
        return onServer(server, () -> inReach(bot, t, reach + 0.3), false);
    }

    /** Nearest-first order through cells, layer by layer ({@code down}: top layer first). */
    static List<Cell> order(List<Cell> cells, BlockPos start, boolean down) {
        java.util.Comparator<Integer> byY = down ? java.util.Comparator.reverseOrder() : java.util.Comparator.naturalOrder();
        Map<Integer, List<Cell>> layers = new TreeMap<>(byY);
        for (Cell c : cells) layers.computeIfAbsent(c.pos().getY(), k -> new ArrayList<>()).add(c);
        List<Cell> out = new ArrayList<>(cells.size());
        BlockPos at = start;
        for (List<Cell> layer : layers.values()) {
            List<Cell> left = new ArrayList<>(layer);
            while (!left.isEmpty()) {
                int bi = 0;
                double bd = Double.MAX_VALUE;
                for (int i = 0; i < left.size(); i++) {
                    BlockPos q = left.get(i).pos();
                    double d = Math.pow(q.getX() - at.getX(), 2) + Math.pow(q.getZ() - at.getZ(), 2);
                    if (d < bd) { bd = d; bi = i; }
                    if (d <= 1) break;
                }
                Cell c = left.remove(bi);
                out.add(c);
                at = c.pos();
            }
        }
        return out;
    }

    private static final int MAX_FAILS = 2;

    /** Digs out what's in the way, top down. Returns how many cells it cleared. Job thread. */
    static int clearAll(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Placed p, List<Cell> cells,
                        Map<BlockPos, Integer> fails) throws InterruptedException {
        BlockPos start = onServer(server, bot::blockPosition, p.center());
        int done = 0, step = 0;
        for (Cell c : order(cells, start, true)) {
            if (!SurvivalBrain.canContinue(b)) break;
            if (fails.getOrDefault(c.pos(), 0) >= MAX_FAILS) continue;
            if (++step % 40 == 0 && !MiningSkills.upkeep(server, bot, b)) break;
            int fluid = onServer(server, () -> MiningSkills.fluidAt(bot.level(), c.pos()), 0);
            boolean empty = onServer(server, () -> bot.level().getBlockState(c.pos()).isAir()
                    && bot.level().getFluidState(c.pos()).isEmpty(), false);
            if (empty) continue;
            if (!approach(server, bot, c.pos(), 4.5)) { fails.merge(c.pos(), 1, Integer::sum); continue; }
            boolean ok;
            if (fluid != 0) {
                ok = onServer(server, () -> drain(bot, c.pos()), false);
            } else {
                ok = MiningSkills.dig(server, bot, b, c.pos(), false, 0);
            }
            if (ok) done++;
            else fails.merge(c.pos(), 1, Integer::sum);
            if (done > 0 && done % 12 == 0) SurvivalBrain.pickUpNearbyItems(server, bot, 6, false);
        }
        return done;
    }

    /**
     * Gets rid of a water or lava source in the way: scoops it with an empty bucket if it has
     * one, otherwise blocks it off with a throwaway block and mines that out. Server thread.
     */
    private static boolean drain(ServerPlayer bot, BlockPos pos) {
        ServerLevel level = bot.level();
        State w = worldState(level, pos);
        boolean lava = w.path().equals("lava");
        boolean source = "0".equals(w.get("level"));
        if (!source) return true; // flowing: stops once its source is gone
        if (Gathering.countOf(bot, "bucket"::equals) > 0 && Gathering.roomFor(bot, (lava ? "lava_bucket" : "water_bucket")::equals, 1)) {
            io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, Vec3.atCenterOf(pos));
            SurvivalBrain.take(bot, "bucket"::equals, 1);
            SurvivalBrain.give(bot, lava ? "lava_bucket" : "water_bucket", 1);
            Motions.swingArm(bot);
            return setState(level, pos, State.parse("minecraft:air"));
        }
        String blk = Building.firstItem(bot, LevelPathWorld::isThrowaway);
        if (blk == null) return false;
        if (!Building.placeAt(bot, pos, blk)) return false;
        return true; // the block gets mined out next pass
    }

    /** Places a list of cells. Returns how many went in; {@code noItems} collects what it ran out of. Job thread. */
    static int placeAll(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Placed p, List<Cell> cells,
                        Map<BlockPos, Integer> fails, Set<String> noItems) throws InterruptedException {
        BlockPos start = onServer(server, bot::blockPosition, p.center());
        int done = 0, step = 0;
        for (Cell c : order(cells, start, false)) {
            if (!SurvivalBrain.canContinue(b)) break;
            if (fails.getOrDefault(c.pos(), 0) >= MAX_FAILS) continue;
            if (++step % 40 == 0 && !MiningSkills.upkeep(server, bot, b)) break;
            if (!onServer(server, () -> hasItemsFor(bot, c), false)) {
                for (Rules.Need n : Rules.items(c.plan())) noItems.add(n.item());
                continue;
            }
            if (!approach(server, bot, c.pos(), 4.5)) { fails.merge(c.pos(), 1, Integer::sum); continue; }
            int r = onServer(server, () -> placeCell(bot, p, c), FAILED);
            if (r == IN_THE_WAY) {
                // standing where it goes: step aside and try again
                BlockPos aside = c.pos().offset(2, 0, 0);
                BotPathing.Options o = BotPathing.Options.walkOnly();
                o.timeoutTicks = 20 * 6;
                BotPathing.goToBlocking(bot, ActionPathfinder.near(aside.getX(), aside.getY(), aside.getZ(), 1.0), o, 7_000L);
                r = onServer(server, () -> placeCell(bot, p, c), FAILED);
            }
            switch (r) {
                case OK -> done++;
                case NO_ITEMS -> { for (Rules.Need n : Rules.items(c.plan())) noItems.add(n.item()); }
                case SKIP -> { }
                default -> fails.merge(c.pos(), 1, Integer::sum);
            }
            SurvivalBrain.sleep(90);
        }
        return done;
    }

    /** Water and lava, refilling the bucket as it goes. Job thread. */
    static int fluidAll(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Placed p, List<Cell> cells,
                        Map<BlockPos, Integer> fails, Set<String> noItems) throws InterruptedException {
        BlockPos start = onServer(server, bot::blockPosition, p.center());
        int done = 0;
        boolean noWater = false;
        for (Cell c : order(cells, start, false)) {
            if (!SurvivalBrain.canContinue(b)) break;
            if (fails.getOrDefault(c.pos(), 0) >= MAX_FAILS) continue;
            boolean lava = Rules.kind(c.plan()) == Rules.Kind.LAVA;
            String full = lava ? "lava_bucket" : "water_bucket";
            boolean have = onServer(server, () -> Gathering.countOf(bot, full::equals) > 0, false);
            if (!have) {
                if (lava) {
                    have = Storage.withdraw(server, bot, b, "lava_bucket"::equals, 1, null) > 0;
                } else if (!noWater) {
                    have = fillWater(server, bot, b, p);
                    noWater = !have;
                }
            }
            if (!have) { noItems.add(full); continue; }
            if (!approach(server, bot, c.pos(), 4.5)) { fails.merge(c.pos(), 1, Integer::sum); continue; }
            int r = onServer(server, () -> placeFluid(bot, c), FAILED);
            if (r == OK) done++;
            else if (r != SKIP && r != NO_ITEMS) fails.merge(c.pos(), 1, Integer::sum);
            SurvivalBrain.sleep(150);
        }
        return done;
    }

    /**
     * A bucket of water: one it has, else an empty bucket (from the chests, or made from iron)
     * filled at the nearest water that isn't part of the build (or part of it that refills by
     * itself, two sources side by side). Job thread.
     */
    static boolean fillWater(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Placed p) throws InterruptedException {
        if (onServer(server, () -> Gathering.countOf(bot, "water_bucket"::equals) > 0, false)) return true;
        boolean bucket = onServer(server, () -> Gathering.countOf(bot, "bucket"::equals) > 0, false)
                || Storage.withdraw(server, bot, b, "bucket"::equals, 1, null) > 0
                || Storage.withdraw(server, bot, b, "water_bucket"::equals, 1, null) > 0;
        if (!bucket) return Farm.ensureWaterBucket(server, bot, b);
        if (onServer(server, () -> Gathering.countOf(bot, "water_bucket"::equals) > 0, false)) return true;
        for (int attempt = 0; attempt < 3 && SurvivalBrain.canContinue(b); attempt++) {
            BlockPos src = onServer(server, () -> waterSource(bot, p, 40), null);
            if (src == null) return false;
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
                return Gathering.countOf(bot, "water_bucket"::equals) > 0;
            }, false);
            if (ok) return true;
            SurvivalBrain.sleep(400);
        }
        return false;
    }

    private static boolean isSource(ServerLevel level, BlockPos q) {
        State s = worldState(level, q);
        return s.path().equals("water") && "0".equals(s.get("level"));
    }

    /** Nearest still water open to the sky, not one of the build's own (unless it refills). Server thread. */
    static BlockPos waterSource(ServerPlayer bot, Placed p, int r) {
        ServerLevel level = bot.level();
        BlockPos f = BotPathing.feet(bot);
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -8; dy <= 5; dy++) {
            double d = dx * dx + dz * dz + dy * dy * 2;
            if (d >= bd) continue;
            BlockPos q = f.offset(dx, dy, dz);
            if (!level.isLoaded(q) || level.getFluidState(q).isEmpty() || !isSource(level, q)) continue;
            if (!level.getBlockState(q.above()).isAir()) continue;
            if ((p != null && p.contains(q)) || Blueprints.protects(level, q, true)) {
                int around = 0;
                for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                    if (isSource(level, q.relative(dir))) around++;
                }
                boolean floor = Building.isSolid(level, q.below()) || isSource(level, q.below());
                if (around < 2 || !floor) continue; // taking it would leave a hole in the build
            }
            best = q;
            bd = d;
        }
        return best;
    }

    /**
     * Items a build uses (stand-ins included): kept out of crafting and the chests while it's
     * going. Re-read from the plan each time, so stand-ins agreed later are kept too.
     */
    static Predicate<String> keepFor(Placed p) {
        Set<String> items = ConcurrentHashMap.newKeySet();
        Runnable fill = () -> {
            Set<String> now = new HashSet<>(List.of("bucket", "water_bucket", "lava_bucket"));
            Schematic s = p.plan();
            for (int y = 0; y < s.sy; y++) for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++) {
                State st = p.local(x, y, z);
                if (st != null && Rules.kind(st) == Rules.Kind.BLOCK) for (Rules.Need n : Rules.items(st)) now.add(n.item());
            }
            for (String i : new ArrayList<>(now)) {
                String[] w = Rules.wood(i);
                if (w != null) now.add(Rules.woodPath(w[0], "log")); // and the logs it's made from
            }
            items.clear();
            items.addAll(now);
        };
        fill.run();
        final int[] swapsSeen = {p.swaps().size()};
        return it -> {
            if (p.swaps().size() != swapsSeen[0]) { swapsSeen[0] = p.swaps().size(); fill.run(); }
            return items.contains(it);
        };
    }

    /** How a build went: where it is, done or not, and how much of it (decoration aside) is still missing. */
    record Result(Placed placed, boolean complete, int remaining, int total) {
        double progress() { return total <= 0 ? 1.0 : Math.max(0, 1.0 - (double) Math.max(0, remaining) / total); }
    }

    /** Blocks in a design that count (not air, not "don't care", not decoration). */
    static int essentialCells(Schematic s) {
        int n = 0;
        for (int y = 0; y < s.sy; y++) for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++) {
            State st = s.at(x, y, z);
            if (st == null) continue;
            Rules.Kind k = Rules.kind(st);
            if (k == Rules.Kind.AIR || k == Rules.Kind.KEEP || Rules.decorative(st.path())) continue;
            n++;
        }
        return n;
    }

    private static Blueprints.Build record(Blueprints.Build rec, boolean done, Placed p) {
        return new Blueprints.Build(rec.bot(), rec.dim(), rec.file(), rec.x(), rec.y(), rec.z(), rec.rot(), rec.sx(), rec.sy(),
                rec.sz(), done, encodeSwaps(p.swaps()));
    }

    /** Job: build a design (or carry on with one: {@code previous}). */
    static void build(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Blueprints.Entry e, Spot spot,
                      Blueprints.Build previous) throws InterruptedException {
        buildFor(server, bot, b, e, spot, previous);
    }

    /** Same, telling the caller how it went (null if it never started). Job thread. */
    static Result buildFor(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Blueprints.Entry e, Spot spot,
                           Blueprints.Build previous) throws InterruptedException {
        return buildFor(server, bot, b, e, spot, previous, null);
    }

    /** {@code recordAs}: whose build it goes down as ("town" for the town's), else the bot's own. */
    static Result buildFor(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Blueprints.Entry e, Spot spot,
                           Blueprints.Build previous, String recordAs) throws InterruptedException {
        Schematic base;
        try {
            base = Blueprints.plan(e);
        } catch (IOException | RuntimeException ex) {
            HumanChat.say(server, b.name, "can't read " + e.fileName() + ": " + ex.getMessage());
            return null;
        }
        int blocks = base.solidCount();
        if (blocks == 0) {
            HumanChat.say(server, b.name, "the " + e.name() + " schematic is empty");
            return null;
        }
        if (blocks > MAX_BLOCKS) {
            HumanChat.say(server, b.name, "the " + e.name() + " is way too big for me (" + blocks + " blocks)");
            return null;
        }
        Placed p = onServer(server, () -> site(bot, base, e, spot), null);
        if (p == null) {
            HumanChat.say(server, b.name, spot.kind() == 0
                    ? HumanChat.pick("can't find a flat enough spot for the " + e.name() + " around here",
                    "no good spot for the " + e.name() + " nearby. tell me where: stand there and say \"build the " + e.name() + " here\"")
                    : "can't build it there, it'd stick out of the world");
            return null;
        }
        if (!p.dim().equals(onServer(server, () -> Home.dim(bot.level()), ""))) {
            HumanChat.say(server, b.name, "that build is in another dimension");
            return null;
        }
        if (previous != null) {
            p.swaps().putAll(decodeSwaps(previous.swaps()));
            upgradeStandIns(server, bot, b, p);
        }
        String me = recordAs != null ? recordAs : b.name.toLowerCase(Locale.ROOT);
        Blueprints.Build rec = new Blueprints.Build(previous != null ? previous.bot() : me, p.dim(), e.fileName(),
                p.origin().getX(), p.origin().getY(), p.origin().getZ(), p.rot(), p.plan().sx, p.plan().sy, p.plan().sz, false,
                encodeSwaps(p.swaps()));
        Blueprints.remember(rec);
        int total = essentialCells(base);
        Predicate<String> keep = keepFor(p);
        SurvivalBrain.keep(bot, keep);
        try {
            if (spot.kind() != 3) {
                String woods = Woods.paletteLine(Woods.palette(base.blockCounts()), 3);
                HumanChat.say(server, b.name, "putting the " + e.name() + " at " + p.origin().getX() + " " + (p.origin().getY() + e.ground())
                        + " " + p.origin().getZ() + " (" + p.plan().sx + "x" + p.plan().sy + "x" + p.plan().sz + ", " + blocks + " blocks)"
                        + (woods.isEmpty() ? "" : ". the wood is " + woods + ", so i need those trees"));
            }
            // room in the pockets for the materials
            if (onServer(server, () -> Storage.usedSlots(bot) > 22 && Home.get(bot) != null, false)) {
                Storage.storeAll(server, bot, b, false);
                SurvivalBrain.keep(bot, keep);
            }
            Map<BlockPos, Integer> fails = new HashMap<>();
            Set<String> noItems = new java.util.TreeSet<>();
            Map<String, Integer> missing = Map.of();
            Work w = null;
            for (int round = 0; round < 6 && SurvivalBrain.canContinue(b); round++) {
                w = scan(server, bot, p);
                if (w == null || w.isEmpty()) break;
                if (round == 0 && previous == null && w.manMade > 0) {
                    Blueprints.forget(rec);
                    HumanChat.say(server, b.name, "there's a build in the way there (" + w.manMadeWhat + "), not gonna tear that down."
                            + " pick another spot: stand where you want it and say \"build the " + e.name() + " here\"");
                    return null;
                }
                if (!w.needs.isEmpty()) {
                    int swapsBefore = p.swaps().size();
                    missing = gather(server, bot, b, w.needs, keep, p);
                    if (p.swaps().size() != swapsBefore) {
                        if (Blueprints.known(rec)) Blueprints.remember(record(rec, false, p));
                        w = scan(server, bot, p); // stand-ins agreed: plan with those
                        if (w == null) break;
                        missing = gather(server, bot, b, w.needs, keep, p);
                    }
                    if (round == 0 && !missing.isEmpty()) {
                        HumanChat.say(server, b.name, "don't have everything: missing " + listOf(missing, 5)
                                + ". gonna build what i can");
                    }
                }
                if (!SurvivalBrain.canContinue(b)) break;
                BlockPos mid = p.center();
                if (onServer(server, () -> bot.blockPosition().distSqr(mid) > 24 * 24, false)) {
                    Surface.backUp(server, bot, b, null);
                    BotPathing.Options o = BotPathing.Options.full();
                    o.timeoutTicks = 20 * 150;
                    BotPathing.goToBlocking(bot, ActionPathfinder.near(mid.getX(), mid.getY() + 1, mid.getZ(), 6.0), o, 155_000L);
                }
                noItems.clear();
                int done = clearAll(server, bot, b, p, w.clear, fails);
                done += placeAll(server, bot, b, p, w.solid, fails, noItems);
                done += fluidAll(server, bot, b, p, w.fluid, fails, noItems);
                done += placeAll(server, bot, b, p, w.attach, fails, noItems);
                done += placeAll(server, bot, b, p, w.decor, fails, noItems);
                SurvivalBrain.pickUpNearbyItems(server, bot, 8, false);
                LOGGER.info("[builder] {} {} round {}: {} done, {} left", b.name, e.name(), round, done, w.size() - done);
                if (done == 0) break;
            }
            if (!SurvivalBrain.canContinue(b)) {
                Work now = scan(server, bot, p);
                return new Result(p, false, now == null ? total : now.toPlace(), total);
            }
            Work left = scan(server, bot, p);
            int remaining = left == null ? total : left.toPlace();
            boolean complete = left != null && left.essential() == 0 && left.unloaded == 0;
            if (Blueprints.known(rec)) Blueprints.remember(record(rec, complete, p));
            String skipped = left != null && !left.unobtainable.isEmpty()
                    ? " (skipped " + listOf(left.unobtainable, 3) + ", can't get those in survival)" : "";
            if (complete) {
                String decor = left.decor.isEmpty() ? "" : " (some flowers and leaves still to put in when i find them)";
                HumanChat.say(server, b.name, HumanChat.pick("done! the " + e.name() + " is built", "the " + e.name() + " is finished")
                        + decor + skipped + (left.leftAlone > 0 ? ". left a chest or bed that was in the way" : "")
                        + (e.note().isBlank() ? "" : ". " + e.note()));
                return new Result(p, true, 0, total);
            }
            Map<String, Integer> still = left == null ? Map.of() : onServer(server, () -> shortfall(bot, left.needs), Map.of());
            Map<String, Integer> report = new TreeMap<>(still);
            if (left != null && left.water > 0 && onServer(server, () -> Gathering.countOf(bot, "water_bucket"::equals) == 0
                    && Gathering.countOf(bot, "bucket"::equals) == 0, true)) report.put("bucket", 1);
            if (!report.isEmpty()) {
                HumanChat.say(server, b.name, "built what i could (" + (int) Math.round(new Result(p, false, remaining, total).progress() * 100)
                        + "%). still need " + listOf(report, 6)
                        + ". put them in a chest or give them to me and say \"continue the build\"" + skipped);
            } else {
                HumanChat.say(server, b.name, "mostly done, couldn't get to " + remaining + " spots. say \"continue the build\" and i'll try again"
                        + skipped);
            }
            return new Result(p, false, remaining, total);
        } finally {
            SurvivalBrain.keep(bot, null);
        }
    }

    // ------------------------------------------------------------------------
    // Farms: harvest and replant
    // ------------------------------------------------------------------------

    private record PlantCells(List<int[]> cells, long stamp) {}

    private static final Map<String, PlantCells> PLANT_CELLS = new ConcurrentHashMap<>();

    /** The design's tall-plant and crop cells (local x, y, z), cached. */
    static List<int[]> plantCells(Placed p, Blueprints.Build rec) {
        String key = rec.file() + "#" + rec.rot();
        Blueprints.Entry e = Blueprints.byFile(rec.file());
        long stamp = 0;
        try {
            if (e != null) stamp = java.nio.file.Files.getLastModifiedTime(e.file()).toMillis();
        } catch (IOException ignored) { }
        PlantCells pc = PLANT_CELLS.get(key);
        if (pc != null && pc.stamp() == stamp) return pc.cells();
        List<int[]> out = new ArrayList<>();
        Schematic s = p.plan();
        for (int y = 0; y < s.sy; y++) for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++) {
            State st = s.at(x, y, z);
            if (st == null) continue;
            String path = st.path();
            State under = y > 0 ? s.at(x, y - 1, z) : null;
            if (Rules.isTallPlant(path) && under != null && Rules.grown(under.path()).equals(Rules.grown(path))) continue;
            if ((Rules.isTallPlant(path) && !path.startsWith("kelp")) || Rules.isCrop(path)
                    || path.endsWith("melon_stem") || path.endsWith("pumpkin_stem")) out.add(new int[]{x, y, z});
        }
        PLANT_CELLS.put(key, new PlantCells(out, stamp));
        return out;
    }

    /** Does this build grow {@code plant} ("sugar_cane", "cactus", "bamboo")? */
    static boolean grows(Blueprints.Build b, String plant) {
        Blueprints.Entry e = Blueprints.byFile(b.file());
        if (e == null) return false;
        try {
            Schematic s = Blueprints.plan(e);
            for (int y = 0; y < s.sy; y++) for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++) {
                State st = s.at(x, y, z);
                if (st != null && Rules.grown(st.path()).equals(Rules.grown(plant))) return true;
            }
        } catch (IOException | RuntimeException ignored) { }
        return false;
    }

    private static Placed placedOf(Blueprints.Build rec) throws IOException {
        Blueprints.Entry e = Blueprints.byFile(rec.file());
        if (e == null) throw new IOException(rec.file() + " isn't in the schematics folder anymore");
        Schematic s = Blueprints.plan(e);
        return new Placed(s.rotated(rec.rot()), new BlockPos(rec.x(), rec.y(), rec.z()), rec.rot(), rec.dim());
    }

    /** What's ready to cut: cane/cactus/bamboo above the bottom block, ripe crops, melons and pumpkins. Server thread. */
    static List<BlockPos> harvestable(ServerLevel level, Placed p, List<int[]> cells) {
        List<BlockPos> out = new ArrayList<>();
        Set<Integer> stemLayers = new HashSet<>();
        for (int[] c : cells) {
            BlockPos pos = p.world(c[0], c[1], c[2]);
            if (!level.isLoaded(pos)) continue;
            State plan = p.plan().at(c[0], c[1], c[2]);
            State world = worldState(level, pos);
            String path = plan.path();
            if (path.endsWith("melon_stem") || path.endsWith("pumpkin_stem")) { stemLayers.add(c[1]); continue; }
            if (Rules.isTallPlant(path)) {
                String grown = Rules.grown(path);
                if (!Rules.grown(world.path()).equals(grown)) continue;
                if (Rules.grown(worldState(level, pos.above()).path()).equals(grown)) out.add(pos.above());
            } else if (Rules.isCrop(path) && Rules.sameBlock(path, world.path()) && Rules.ripe(world)) {
                out.add(pos);
            }
        }
        if (!stemLayers.isEmpty()) {
            Schematic s = p.plan();
            for (int y : stemLayers) for (int z = -1; z <= s.sz; z++) for (int x = -1; x <= s.sx; x++) {
                BlockPos pos = p.world(x, y, z);
                State plan = s.at(x, y, z);
                if (plan != null && Rules.kind(plan) != Rules.Kind.AIR) continue;
                BlockState bs = level.getBlockState(pos);
                if (bs.isAir()) continue;
                String w = SurvivalBrain.blockPath(bs);
                if (w.equals("melon") || w.equals("pumpkin")) out.add(pos);
            }
        }
        return out;
    }

    /** Job: harvest a farm build and replant it. */
    static void tend(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Blueprints.Build rec, boolean asked)
            throws InterruptedException {
        Placed p;
        try {
            p = placedOf(rec);
        } catch (IOException | RuntimeException ex) {
            if (asked) HumanChat.say(server, b.name, "can't: " + ex.getMessage());
            return;
        }
        if (!p.dim().equals(onServer(server, () -> Home.dim(bot.level()), ""))) {
            if (asked) HumanChat.say(server, b.name, "the " + rec.name() + " is in another dimension");
            return;
        }
        List<int[]> cells = plantCells(p, rec);
        if (cells.isEmpty()) {
            if (asked) HumanChat.say(server, b.name, "there's nothing to harvest on the " + rec.name());
            return;
        }
        BlockPos mid = p.center();
        if (onServer(server, () -> bot.blockPosition().distSqr(mid) > 14 * 14, true)) {
            Surface.backUp(server, bot, b, null);
            BotPathing.Options o = BotPathing.Options.full();
            o.timeoutTicks = 20 * 120;
            BotPathing.goToBlocking(bot, ActionPathfinder.near(mid.getX(), mid.getY() + 1, mid.getZ(), Math.max(3.0, p.plan().sx / 2.0)),
                    o, 125_000L);
            if (onServer(server, () -> bot.blockPosition().distSqr(mid) > 28 * 28, true)) {
                if (asked) HumanChat.say(server, b.name, "can't get to the " + rec.name() + " from here");
                return;
            }
        }
        Predicate<String> keep = keepFor(p);
        SurvivalBrain.keep(bot, keep);
        try {
            List<BlockPos> targets = onServer(server, () -> harvestable(bot.level(), p, cells), List.of());
            int cut = 0;
            List<Cell> asCells = new ArrayList<>();
            for (BlockPos t : targets) asCells.add(new Cell(t, null, Op.CLEAR));
            BlockPos start = onServer(server, bot::blockPosition, mid);
            for (Cell c : order(asCells, start, true)) {
                if (!SurvivalBrain.canContinue(b)) break;
                if (!approach(server, bot, c.pos(), 4.5)) continue;
                if (MiningSkills.dig(server, bot, b, c.pos(), false, 0)) cut++;
                if (cut > 0 && cut % 8 == 0) SurvivalBrain.pickUpNearbyItems(server, bot, 6, false);
            }
            SurvivalBrain.pickUpNearbyItems(server, bot, Math.max(8, p.plan().sx / 2.0 + 3), false);
            // replant what's gone (crops just harvested, cane that got broken)
            Work w = scan(server, bot, p);
            int planted = 0;
            if (w != null) {
                List<Cell> plants = new ArrayList<>();
                for (Cell c : w.attach) {
                    String path = c.plan().path();
                    if (c.op() == Op.PLACE && (Rules.isTallPlant(path) || Rules.isCrop(path) || path.endsWith("_stem"))) plants.add(c);
                }
                planted = placeAll(server, bot, b, p, plants, new HashMap<>(), new java.util.TreeSet<>());
            }
            String line = cut == 0 ? (planted > 0 ? "nothing ready yet, replanted " + planted : "nothing's ready on the " + rec.name() + " yet")
                    : "harvested the " + rec.name() + " (" + cut + " cut" + (planted > 0 ? ", " + planted + " replanted" : "") + ")";
            if (asked) HumanChat.say(server, b.name, line);
            else if (cut > 0) SurvivalBrain.maybeSay(server, b, line, 0.4);
            if (HumanConfig.get().autoStore && onServer(server, () -> Home.get(bot) != null && Storage.storableSlots(bot) >= 6, false)) {
                Storage.storeAll(server, bot, b, false);
            }
        } finally {
            SurvivalBrain.keep(bot, null);
        }
    }

    private static final Map<String, Long> NEXT_TEND = new ConcurrentHashMap<>();

    /** Every few minutes in the daytime: harvest a farm it built if enough has grown. Brain thread. */
    static boolean tick(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        String me = b.name.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        if (now < NEXT_TEND.getOrDefault(me, 0L)) return false;
        NEXT_TEND.put(me, now + 5 * 60_000L);
        String dim = onServer(server, () -> Home.dim(bot.level()), "");
        BlockPos at = onServer(server, bot::blockPosition, null);
        if (at == null) return false;
        for (Blueprints.Build rec : Blueprints.builds()) {
            boolean town = rec.bot().equals(City.TOWN_BUILDER); // the town's farms: whoever's around tends them
            if (!rec.done() || !(rec.bot().equals(me) || town) || !rec.dim().equals(dim)) continue;
            if (rec.center().distSqr(at) > 160 * 160) continue;
            if (town && !City.tendClaim(rec, me)) continue; // somebody else is on it
            Placed p;
            try {
                p = placedOf(rec);
            } catch (IOException | RuntimeException ex) {
                continue;
            }
            List<int[]> cells = plantCells(p, rec);
            if (cells.isEmpty()) continue;
            int ready = onServer(server, () -> harvestable(bot.level(), p, cells).size(), 0);
            // the town's farms also get planted up when a lot of it is bare (and there are seeds to do it)
            int bare = town ? onServer(server, () -> bare(bot.level(), p, cells), 0) : 0;
            boolean plant = bare >= 8 && onServer(server, () -> Gathering.countOf(bot, it -> it.endsWith("_seeds")
                    || it.equals("carrot") || it.equals("potato")) + Storage.stockOf(bot.level(), "wheat_seeds"::equals) > 0, false);
            if (ready < Math.max(4, cells.size() / 3) && !plant) continue;
            SurvivalBrain.maybeSay(server, b, HumanChat.pick("gonna go harvest the " + rec.name(), "the " + rec.name() + " needs harvesting"), 0.7);
            tend(server, bot, b, rec, false);
            return true;
        }
        return false;
    }

    /** Plant cells with nothing growing in them. Server thread. */
    static int bare(ServerLevel level, Placed p, List<int[]> cells) {
        int n = 0;
        for (int[] c : cells) {
            BlockPos q = p.world(c[0], c[1], c[2]);
            if (level.isLoaded(q) && level.getBlockState(q).isAir()) n++;
        }
        return n;
    }

    /** For the tests: which items a design takes, per item. */
    static Map<String, Integer> billOf(Schematic s) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int y = 0; y < s.sy; y++) for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++) {
            State st = s.at(x, y, z);
            if (st != null && Rules.kind(st) == Rules.Kind.BLOCK) for (Rules.Need n : Rules.items(st)) out.merge(n.item(), n.count(), Integer::sum);
        }
        return out;
    }
}
