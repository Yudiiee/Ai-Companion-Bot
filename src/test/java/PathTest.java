import io.github.yudiiee.aicompanion.GameAI.human.ActionPathfinder;
import io.github.yudiiee.aicompanion.GameAI.human.ActionPathfinder.*;

import java.util.*;

/** Exercises the action-based A* on synthetic worlds (no Minecraft needed). */
public class PathTest {
    static int fails = 0;
    static void check(boolean c, String msg) { if (!c) { fails++; System.out.println("FAIL: " + msg); } else System.out.println("ok: " + msg); }

    /** y <= groundY is stone, above is air, with overrides. */
    static class W implements World {
        int groundY = 0;
        Map<Long, Character> cells = new HashMap<>(); // '#' stone, '.' air, '~' water, 'L' lava, 'B' bedrock
        void set(int x, int y, int z, char c) { cells.put(ActionPathfinder.pack(x, y, z), c); }
        void box(int x0, int y0, int z0, int x1, int y1, int z1, char c) {
            for (int x = Math.min(x0,x1); x <= Math.max(x0,x1); x++) for (int y = Math.min(y0,y1); y <= Math.max(y0,y1); y++)
                for (int z = Math.min(z0,z1); z <= Math.max(z0,z1); z++) set(x, y, z, c);
        }
        char at(int x, int y, int z) {
            Character c = cells.get(ActionPathfinder.pack(x, y, z));
            if (c != null) return c;
            return y <= groundY ? '#' : '.';
        }
        public int flags(int x, int y, int z) {
            switch (at(x, y, z)) {
                case '.': return PASS;
                case '~': return PASS | WATER;
                case 'L': return PASS | AVOID;
                case 'G': return STAND | FALLS;
                default: return STAND;
            }
        }
        public double breakTicks(int x, int y, int z) {
            char c = at(x, y, z);
            if (c == 'B') return -1;
            for (int[] d : new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
                char n = at(x + d[0], y + d[1], z + d[2]);
                if (n == '~' || n == 'L') return -1;
            }
            return c == 'G' ? 10 : 23; // stone with a wooden pick ~ 23 ticks
        }
    }

    static List<Move> run(W w, int sx, int sy, int sz, Goal g, Options o, String name) {
        Search s = new Search(w, sx, sy, sz, g, o);
        Status st;
        long t0 = System.nanoTime();
        do { st = s.step(50_000_000L); } while (st == Status.RUNNING);
        List<Move> p = s.path();
        double cost = p.stream().mapToDouble(m -> m.cost).sum();
        System.out.printf("  %-34s %-7s moves=%d cost=%.0ft nodes=%d %.1fms%n", name, st, p.size(), cost, s.expanded(), (System.nanoTime() - t0) / 1e6);
        for (Move m : p) if (p.size() <= 14) System.out.println("      " + m);
        lastStatus = st;
        return p;
    }
    static Status lastStatus;

    static boolean ends(List<Move> p, Goal g, int sx, int sy, int sz) {
        if (p.isEmpty()) return g.reached(sx, sy, sz);
        Move m = p.get(p.size() - 1);
        return g.reached(m.dx, m.dy, m.dz);
    }
    static boolean continuous(List<Move> p, int sx, int sy, int sz) {
        int x = sx, y = sy, z = sz;
        for (Move m : p) { if (m.sx != x || m.sy != y || m.sz != z) return false; x = m.dx; y = m.dy; z = m.dz; }
        return true;
    }
    static long count(List<Move> p, Kind k) { return p.stream().filter(m -> m.kind == k).count(); }

    public static void main(String[] a) {
        Options walk = new Options(); walk.allowBreak = false; walk.allowPlace = false;
        Options full = new Options(); full.throwaway = 64;
        Options noBlocks = new Options(); noBlocks.throwaway = 0;

        // 1. flat walk
        { W w = new W(); Goal g = ActionPathfinder.near(10, 1, 0, 0);
          List<Move> p = run(w, 0, 1, 0, g, walk, "flat 10 blocks");
          check(ends(p, g, 0,1,0) && continuous(p,0,1,0) && p.stream().allMatch(m -> m.breaks.length == 0), "flat walk reaches goal without digging");
          double cost = p.stream().mapToDouble(m -> m.cost).sum();
          check(Math.abs(cost - 10 * ActionPathfinder.SPRINT) < 0.01, "flat cost = 10 sprint blocks (" + cost + ")"); }

        // 2a. short wall: go around rather than dig
        { W w = new W(); w.box(5, 1, -1, 5, 3, 1, '#'); Goal g = ActionPathfinder.near(10, 1, 0, 0);
          List<Move> p = run(w, 0, 1, 0, g, full, "short wall");
          check(ends(p,g,0,1,0) && p.stream().allMatch(m -> m.breaks.length == 0), "walks around a short wall"); }
        // 2b. long breakable wall: tunnel (cheaper than a 60-block detour)
        { W w = new W(); w.box(5, 1, -30, 5, 6, 30, '#'); Goal g = ActionPathfinder.near(10, 1, 0, 0);
          List<Move> p = run(w, 0, 1, 0, g, full, "long wall, digging allowed");
          check(ends(p,g,0,1,0) && continuous(p,0,1,0), "gets through a long wall");
          check(p.stream().anyMatch(m -> m.breaks.length > 0), "by tunnelling"); }
        // 2c. long wall, walking only: no path (partial or failed, never reaches)
        { W w = new W(); w.box(5, 1, -30, 5, 6, 30, 'B'); Goal g = ActionPathfinder.near(10, 1, 0, 0);
          List<Move> p = run(w, 0, 1, 0, g, walk, "long bedrock wall, walk only");
          check(p.stream().allMatch(m -> m.breaks.length == 0) && p.stream().noneMatch(m -> m.dx == 5 && Math.abs(m.dz) <= 30), "walks round the end of a bedrock wall, never through it");
          W wb = new W(); wb.box(5, 1, -300, 5, 6, 300, 'B'); Options small = new Options(); small.allowBreak = false; small.allowPlace = false; small.maxNodes = 5000;
          List<Move> pb = run(wb, 0, 1, 0, g, small, "endless bedrock wall");
          check(!ends(pb,g,0,1,0), "can't get through an endless bedrock wall"); }

        // 3. step up
        { W w = new W(); w.box(3, 1, -5, 20, 1, 5, '#'); Goal g = ActionPathfinder.near(8, 2, 0, 0);
          List<Move> p = run(w, 0, 1, 0, g, walk, "step up one block");
          check(ends(p,g,0,1,0) && count(p, Kind.ASCEND) == 1, "one jump up"); }

        // 4. stuck at the bottom of a 1x1 hole, 4 deep
        { W w = new W(); w.groundY = 0; w.box(0, -3, 0, 0, 0, 0, '.'); Goal g = ActionPathfinder.near(3, 1, 0, 1.0);
          List<Move> p = run(w, 0, -3, 0, g, full, "out of a 4-deep hole (blocks)");
          check(ends(p,g,0,-3,0) && continuous(p,0,-3,0), "climbs out of a hole");
          List<Move> p2 = run(w, 0, -3, 0, g, noBlocks, "out of a 4-deep hole (no blocks)");
          check(ends(p2,g,0,-3,0) && count(p2, Kind.PILLAR) == 0, "digs a staircase out without blocks");
          List<Move> p3 = run(w, 0, -3, 0, g, walk, "out of a hole, walk only");
          check(!ends(p3,g,0,-3,0), "walk-only can't get out of a hole"); }

        // 5. ravine: 2 wide, 20 deep
        { W w = new W(); w.box(4, -20, -40, 5, 0, 40, '.'); Goal g = ActionPathfinder.near(9, 1, 0, 0);
          List<Move> p = run(w, 0, 1, 0, g, full, "ravine with blocks");
          check(ends(p,g,0,1,0) && count(p, Kind.BRIDGE) == 2, "bridges a 2-wide gap");
          List<Move> p2 = run(w, 0, 1, 0, g, walk, "ravine walk only");
          check(!ends(p2,g,0,1,0) || p2.stream().noneMatch(m -> m.sy - m.dy > 3), "no deadly drops"); }

        // 6. lava pool in the way
        { W w = new W(); w.box(3, 0, -1, 6, 0, 1, 'L'); Goal g = ActionPathfinder.near(10, 1, 0, 0);
          List<Move> p = run(w, 0, 1, 0, g, full, "lava pool");
          boolean touches = false;
          for (Move m : p) { char under = w.at(m.dx, m.dy - 1, m.dz); if (under == 'L' || w.at(m.dx, m.dy, m.dz) == 'L') touches = true; }
          check(ends(p,g,0,1,0) && !touches, "goes around lava, never over it"); }

        // 7. cliffs
        { W w = new W(); w.box(-10, 1, -10, 4, 3, 10, '#'); Goal g = ActionPathfinder.near(8, 1, 0, 0);
          List<Move> p = run(w, 0, 4, 0, g, walk, "3-block cliff");
          check(ends(p,g,0,4,0) && count(p, Kind.FALL) == 1, "drops 3 blocks");
          W w2 = new W(); w2.box(-10, 1, -10, 4, 7, 10, '#'); Goal g2 = ActionPathfinder.near(8, 1, 0, 0);
          List<Move> p2 = run(w2, 0, 8, 0, g2, walk, "7-block cliff, walk only");
          check(p2.stream().noneMatch(m -> m.sy - m.dy > 3), "never drops more than 3 onto ground");
          W w3 = new W(); w3.box(-10, 1, -10, 4, 7, 10, '#'); w3.box(5, 1, -2, 7, 2, 2, '~'); Goal g3 = ActionPathfinder.near(8, 1, 0, 0);
          List<Move> p3 = run(w3, 0, 8, 0, g3, walk, "7-block cliff into water");
          check(ends(p3,g3,0,8,0), "jumps into water from high up"); }

        // 7b. a lake: swim across on the surface, don't dive
        { W w = new W(); w.groundY = 0; w.box(2, -4, -6, 20, 0, 6, '~'); w.box(2, 1, -6, 20, 1, 6, '~');
          Goal g = ActionPathfinder.near(24, 1, 0, 0.5);
          List<Move> p = run(w, 0, 1, 0, g, walk, "lake crossing");
          boolean dived = p.stream().anyMatch(m -> w.at(m.dx, m.dy + 1, m.dz) == '~');
          check(ends(p,g,0,1,0) && !dived, "swims across at the surface"); }

        // 8. buried: surrounded by stone, target up and away
        { W w = new W(); w.groundY = 20; w.set(0,10,0,'.'); w.set(0,11,0,'.'); Goal g = ActionPathfinder.near(6, 14, 0, 0.5);
          List<Move> p = run(w, 0, 10, 0, g, full, "tunnel through solid stone");
          check(ends(p,g,0,10,0) && continuous(p,0,10,0), "tunnels to a spot inside rock"); }

        // 9. reach goal for mining a buried block
        { W w = new W(); Goal g = ActionPathfinder.reach(12, -5, 0, 4.4);
          List<Move> p = run(w, 0, 1, 0, g, full, "get in reach of a buried ore");
          check(ends(p,g,0,1,0), "digs down to reach a buried block"); }

        // 10. gravel over the tunnel costs more
        { W w = new W(); w.groundY = 5; w.set(0,1,0,'.'); w.set(0,2,0,'.'); Goal g = ActionPathfinder.near(6, 1, 0, 0);
          for (int x = 1; x <= 6; x++) w.set(x, 3, 0, 'G');
          List<Move> p = run(w, 0, 1, 0, g, full, "tunnel under gravel");
          check(ends(p,g,0,1,0), "still gets there under gravel"); }

        // 11. big open search: 150 blocks
        { W w = new W(); Goal g = ActionPathfinder.near(150, 1, 20, 1);
          List<Move> p = run(w, 0, 1, 0, g, full, "150 blocks open field");
          check(ends(p,g,0,1,0), "long distance"); }

        // fall table sanity
        check(ActionPathfinder.FALL[1] > 3 && ActionPathfinder.FALL[1] < 8 && ActionPathfinder.FALL[3] > ActionPathfinder.FALL[1], "fall ticks: 1=" + ActionPathfinder.FALL[1] + " 3=" + ActionPathfinder.FALL[3]);

        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails);
    }
}
