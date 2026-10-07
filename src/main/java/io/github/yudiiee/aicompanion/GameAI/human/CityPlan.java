package io.github.yudiiee.aicompanion.GameAI.human;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How the companions lay out a town: a plaza in the middle, four avenues out of it with street
 * lamps, the buildings along them facing the road, the temple at the end of one avenue and the
 * PvP arena at the end of the opposite one. The other two avenues stay open at their ends:
 * roads to the next towns join there. Plain Java, local coordinates around the plaza's centre.
 *
 * <pre>
 *                        [temple]
 *                  house  |   |  house
 *   farm  farm  ====road==[plaza]==road====  shop shop shop  ==> to the next town
 *   warehouse  amphitheatre |   |   shop shop mall
 *                  house  |   |  house
 *                        [ arena ]
 * </pre>
 */
public final class CityPlan {

    private CityPlan() {}

    /** Half the road's width (the road is 5 wide: -2..2 across). */
    static final int ROAD_HALF = 2;
    /** Lamps stand one block off the road; buildings start one further out. */
    static final int LOT_START = ROAD_HALF + 2;
    /** Space between buildings along a road. */
    static final int GAP = 3;
    /** Roads go up a piece at a time. */
    static final int SEGMENT = 16;

    static final String[] DIRS = {"north", "east", "south", "west"};

    /** A design to put down: what it is, its file, its size and the side its door is on. */
    public record Design(String kind, String file, int sx, int sz, String front) {
        /** Along its front. */
        int width() { return front.equals("north") || front.equals("south") ? sx : sz; }

        /** Front to back. */
        int depth() { return front.equals("north") || front.equals("south") ? sz : sx; }
    }

    /** Where a design goes in the town: arm 0..3 (north, east, south, west) or -1 for the middle; on a side or at the end. */
    public record Spot(Design design, int arm, boolean end) {}

    /**
     * A piece of the town: a building (its low corner {@code x0, z0} and size {@code w} by {@code l}
     * after turning; {@code face}: the side its front looks to; {@code rot}: quarter turns) or a
     * piece of road ({@code kind} "road", {@code face}: the way the avenue runs). Local coordinates.
     */
    public record Lot(int id, String kind, String file, int x0, int z0, int w, int l, String face, int rot, int arm) {
        int x1() { return x0 + w - 1; }

        int z1() { return z0 + l - 1; }

        boolean road() { return kind.equals("road"); }

        boolean overlaps(Lot o) {
            return x0 <= o.x1() && o.x0 <= x1() && z0 <= o.z1() && o.z0 <= z1();
        }
    }

    static int[] vec(String dir) {
        return switch (dir) {
            case "north" -> new int[]{0, -1};
            case "east" -> new int[]{1, 0};
            case "south" -> new int[]{0, 1};
            default -> new int[]{-1, 0};
        };
    }

    static String dirOf(int dx, int dz) {
        if (dx > 0) return "east";
        if (dx < 0) return "west";
        return dz < 0 ? "north" : "south";
    }

    /** Quarter turns that bring a front around to face {@code to}. */
    static int turnsTo(String front, String to) {
        for (int t = 0; t < 4; t++) if (Schematic.turnDir(front, t).equals(to)) return t;
        return 0;
    }

    /** An (along, across) box on an arm, as an x/z box: {x0, z0, x1, z1}. */
    static int[] box(int arm, int a0, int a1, int p0, int p1) {
        int[] d = vec(DIRS[arm]);
        int nx = -d[1], nz = d[0];
        int xa = a0 * d[0] + p0 * nx, za = a0 * d[1] + p0 * nz;
        int xb = a1 * d[0] + p1 * nx, zb = a1 * d[1] + p1 * nz;
        return new int[]{Math.min(xa, xb), Math.min(za, zb), Math.max(xa, xb), Math.max(za, zb)};
    }

    private record Placed(Spot spot, int[] box, String face, int alongEnd) {}

    /**
     * The whole town: the plaza first, then the buildings in the order given, each with the road
     * out to it just before it. Every building's front faces the road (landmarks face the plaza).
     */
    public static List<Lot> layout(Design plaza, List<Spot> spots) {
        int half = Math.max(plaza.sx(), plaza.sz()) / 2;
        int roadStart = half + 1;
        int[] cursor = new int[8]; // arm*2 + side: how far along the buildings on that side reach
        for (int i = 0; i < 8; i++) cursor[i] = roadStart + 1;
        Map<Spot, Placed> where = new java.util.IdentityHashMap<>(); // two farms are equal records
        List<int[]> taken = new ArrayList<>();
        taken.add(new int[]{-(plaza.sx() / 2), -(plaza.sz() / 2), plaza.sx() - 1 - plaza.sx() / 2, plaza.sz() - 1 - plaza.sz() / 2});
        // the buildings along the sides first (in order), so the landmarks go past the last of them;
        // where two avenues' buildings meet in a corner, the later one moves further out
        for (Spot s : spots) {
            if (s.end() || s.arm() < 0) continue;
            int left = s.arm() * 2, right = left + 1;
            int side = cursor[left] <= cursor[right] ? left : right;
            int sign = side == left ? 1 : -1;
            Design d = s.design();
            int p0 = sign > 0 ? LOT_START : -(LOT_START + d.depth() - 1);
            int p1 = sign > 0 ? LOT_START + d.depth() - 1 : -LOT_START;
            int a0 = cursor[side];
            int[] b = box(s.arm(), a0, a0 + d.width() - 1, p0, p1);
            for (int tries = 0; tries < 600 && clashes(b, taken); tries++) {
                a0++;
                b = box(s.arm(), a0, a0 + d.width() - 1, p0, p1);
            }
            int a1 = a0 + d.width() - 1;
            int[] dv = vec(DIRS[s.arm()]);
            int nx = -dv[1], nz = dv[0];
            String face = sign > 0 ? dirOf(-nx, -nz) : dirOf(nx, nz);
            where.put(s, new Placed(s, b, face, a1));
            taken.add(b);
            cursor[side] = a1 + 1 + GAP;
        }
        int[] roadEnd = new int[4];
        for (int arm = 0; arm < 4; arm++) {
            int far = Math.max(cursor[arm * 2], cursor[arm * 2 + 1]) - GAP;
            roadEnd[arm] = Math.max(roadStart + SEGMENT - 1, far);
        }
        for (Spot s : spots) {
            if (!s.end() || s.arm() < 0) continue;
            Design d = s.design();
            int p0 = -(d.width() / 2), p1 = p0 + d.width() - 1;
            int a0 = roadEnd[s.arm()] + 1;
            int[] b = box(s.arm(), a0, a0 + d.depth() - 1, p0, p1);
            for (int tries = 0; tries < 600 && clashes(b, taken); tries++) {
                a0++;
                b = box(s.arm(), a0, a0 + d.depth() - 1, p0, p1);
            }
            roadEnd[s.arm()] = a0 - 1; // the road runs right up to it
            int[] dv = vec(DIRS[s.arm()]);
            where.put(s, new Placed(s, b, dirOf(-dv[0], -dv[1]), a0 - 1));
            taken.add(b);
        }
        // the roads, in pieces (an avenue with no landmark at its end stops past its last building)
        List<List<int[]>> pieces = new ArrayList<>();
        for (int arm = 0; arm < 4; arm++) {
            List<int[]> ps = new ArrayList<>();
            for (int a = roadStart; a <= roadEnd[arm]; a += SEGMENT) {
                int b = Math.min(roadEnd[arm], a + SEGMENT - 1);
                ps.add(new int[]{a, b});
            }
            pieces.add(ps);
        }
        int[] roadsDone = new int[4];
        List<Lot> out = new ArrayList<>();
        int px0 = -(plaza.sx() / 2), pz0 = -(plaza.sz() / 2);
        out.add(new Lot(0, plaza.kind(), plaza.file(), px0, pz0, plaza.sx(), plaza.sz(), "south", 0, -1));
        for (Spot s : spots) {
            Placed p = where.get(s);
            if (p == null) continue;
            int arm = s.arm();
            List<int[]> ps = pieces.get(arm);
            while (roadsDone[arm] < ps.size() && ps.get(roadsDone[arm])[0] <= p.alongEnd()) {
                out.add(road(out.size(), arm, ps.get(roadsDone[arm])));
                roadsDone[arm]++;
            }
            Design d = s.design();
            int rot = turnsTo(d.front(), p.face());
            int w = p.box()[2] - p.box()[0] + 1, l = p.box()[3] - p.box()[1] + 1;
            out.add(new Lot(out.size(), d.kind(), d.file(), p.box()[0], p.box()[1], w, l, p.face(), rot, arm));
        }
        for (int arm = 0; arm < 4; arm++) {
            List<int[]> ps = pieces.get(arm);
            while (roadsDone[arm] < ps.size()) {
                out.add(road(out.size(), arm, ps.get(roadsDone[arm])));
                roadsDone[arm]++;
            }
        }
        return out;
    }

    /** Within a gap of another building (or the plaza)? */
    private static boolean clashes(int[] b, List<int[]> taken) {
        for (int[] o : taken) {
            if (b[0] - GAP + 1 <= o[2] && o[0] <= b[2] + GAP - 1 && b[1] - GAP + 1 <= o[3] && o[1] <= b[3] + GAP - 1) return true;
        }
        return false;
    }

    private static Lot road(int id, int arm, int[] piece) {
        int[] b = box(arm, piece[0], piece[1], -(ROAD_HALF + 1), ROAD_HALF + 1);
        return new Lot(id, "road", "", b[0], b[1], b[2] - b[0] + 1, b[3] - b[1] + 1, DIRS[arm], 0, arm);
    }

    /** The box around the whole town {x0, z0, x1, z1}. */
    static int[] bounds(List<Lot> lots) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Lot l : lots) {
            b[0] = Math.min(b[0], l.x0());
            b[1] = Math.min(b[1], l.z0());
            b[2] = Math.max(b[2], l.x1());
            b[3] = Math.max(b[3], l.z1());
        }
        return b;
    }

    // ------------------------------------------------------------------------
    // The program: what a town has, in the order it gets built
    // ------------------------------------------------------------------------

    /** The usual town for {@code shops} shopkeepers, given the designs it has (by kind), with the temple at the north end. */
    public static List<Spot> program(Map<String, Design> d, int shops, List<Design> houses) {
        return program(d, shops, houses, 0);
    }

    /**
     * The usual town, built in this order: a warehouse, a shop each, a farm, the temple (at the
     * end of avenue {@code templeArm}), the PvP arena (at the end of the opposite avenue), another
     * farm, the mall and the amphitheatre (along the other two avenues, which stay open at their
     * ends: that's where the roads to the next towns go), town houses and a last farm.
     */
    public static List<Spot> program(Map<String, Design> d, int shops, List<Design> houses, int templeArm) {
        int t = Math.floorMod(templeArm, 4), right = (t + 1) % 4, back = (t + 2) % 4, left = (t + 3) % 4;
        List<Spot> out = new ArrayList<>();
        add(out, d.get("warehouse"), left, false);
        for (int i = 0; i < Math.max(1, shops); i++) add(out, d.get("shop"), right, false);
        add(out, d.get("farm"), left, false);
        add(out, d.get("temple"), t, true);
        add(out, d.get("arena"), back, true);
        add(out, d.get("farm"), left, false);
        add(out, d.get("mall"), right, false);
        add(out, d.get("amphitheatre"), left, false);
        int i = 0;
        for (Design h : houses) {
            add(out, h, i % 2 == 0 ? t : back, false);
            i++;
        }
        add(out, d.get("farm"), right, false);
        return out;
    }

    /** The avenues left open at the end (no landmark): where roads to other towns join. */
    static int[] gates(int templeArm) {
        int t = Math.floorMod(templeArm, 4);
        return new int[]{(t + 1) % 4, (t + 3) % 4};
    }

    private static void add(List<Spot> out, Design d, int arm, boolean end) {
        if (d != null) out.add(new Spot(d, arm, end));
    }
}
