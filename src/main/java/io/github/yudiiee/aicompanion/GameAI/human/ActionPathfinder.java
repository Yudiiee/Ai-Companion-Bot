package io.github.yudiiee.aicompanion.GameAI.human;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Modified 3D A* over block positions using action-based movement primitives.
 *
 * <p>Nodes are feet positions. Edges are the things a player can actually do from a spot:
 * walk, sprint diagonally, jump up a block, drop down (up to a safe height, further into
 * water), tunnel through blocks, dig straight down, pillar up by jumping and placing a
 * block, bridge over a gap, and swim. Every edge costs the number of game ticks the
 * action takes to perform: walking/sprinting time from vanilla movement speeds, falling
 * time from vanilla gravity, and for breaking the real per-block dig time with the best
 * tool the bot is carrying. So the cheapest path is the fastest one to execute, and
 * digging through a hill is only chosen when walking around would take longer.
 *
 * <p>This class is pure logic over a {@link World} view, so it can run incrementally on
 * the server thread (a few milliseconds per tick) and be unit tested without Minecraft.
 */
public final class ActionPathfinder {

    // ------------------------------------------------------------------------
    // Cost model, in ticks (20 per second)
    // ------------------------------------------------------------------------

    public static final double WALK = 20.0 / 4.317;       // walking speed 4.317 m/s
    public static final double SPRINT = 20.0 / 5.612;     // sprinting 5.612 m/s
    public static final double SWIM = 20.0 / 2.2;         // swimming ~2.2 m/s
    public static final double JUMP = 6.5;                // extra time to hop up one block
    public static final double PLACE = 12.0;              // aim + place a block
    public static final double PLACE_PENALTY = 18.0;      // it also uses up a block
    public static final double BREAK_OVERHEAD = 5.0;      // aim + switch tool
    public static final double FALLING_BLOCK_PENALTY = 30.0; // gravel/sand falls into the gap
    /** Per move that ends with the head under water: swim on the surface, don't dive (drowning). */
    public static final double UNDERWATER_PENALTY = 40.0;
    /** Per move in water at all: swimming is slow and risky, so walking round a lake wins unless it's much longer. */
    public static final double WATER_PENALTY = 8.0;
    public static final double INF = Double.POSITIVE_INFINITY;
    /** FALL[n] = ticks to fall n blocks from standing (vanilla gravity and drag). */
    public static final double[] FALL = new double[128];

    static {
        double v = 0, dist = 0;
        int t = 0, n = 1;
        while (n < FALL.length) {
            v = (v - 0.08) * 0.98;
            dist -= v;
            t++;
            while (n < FALL.length && dist >= n) FALL[n++] = t + 1;
        }
    }

    // ------------------------------------------------------------------------
    // World view and goals
    // ------------------------------------------------------------------------

    /** What the pathfinder needs to know about a block. */
    public interface World {
        int PASS = 1;       // can be occupied (air, grass, flowers, water...)
        int WATER = 2;      // water
        int STAND = 4;      // solid top you can stand on
        int AVOID = 8;      // never enter or stand on (lava, fire, cactus, magma...)
        int UNLOADED = 16;  // unknown: not loaded
        int FALLS = 32;     // gravel / sand: falls when unsupported

        int flags(int x, int y, int z);

        /** Ticks to break with the best tool at hand, or a negative number if it must not be broken. */
        double breakTicks(int x, int y, int z);
    }

    public interface Goal {
        boolean reached(int x, int y, int z);

        /** Estimated ticks left. */
        double heuristic(int x, int y, int z);
    }

    /** Stand within {@code r} blocks of a spot. */
    public static Goal near(int gx, int gy, int gz, double r) {
        double r2 = r * r;
        return new Goal() {
            public boolean reached(int x, int y, int z) {
                double dx = x - gx, dy = y - gy, dz = z - gz;
                return dx * dx + dy * dy + dz * dz <= r2;
            }
            public double heuristic(int x, int y, int z) {
                double dx = x - gx, dz = z - gz;
                int dy = gy - y;
                double h = SPRINT * Math.max(0, Math.sqrt(dx * dx + dz * dz) - r)
                        + (dy > 0 ? dy * (WALK + JUMP) * 0.5 : -dy * 1.0);
                return Math.max(0, h);
            }
            public String toString() { return "near " + gx + " " + gy + " " + gz + " r=" + r; }
        };
    }

    /** Stand where the block at (bx,by,bz) is within mining reach. */
    public static Goal reach(int bx, int by, int bz, double reach) {
        double r2 = reach * reach;
        return new Goal() {
            public boolean reached(int x, int y, int z) {
                if (x == bx && z == bz && (y == by || y == by - 1)) return false; // can't stand inside it
                double dx = x - bx, dy = y - (by + 0.5), dz = z - bz;
                return dx * dx + dy * dy + dz * dz <= r2 && Math.abs(y - by) <= 3;
            }
            public double heuristic(int x, int y, int z) {
                double dx = x - bx, dz = z - bz;
                int dy = by - y;
                double d = Math.sqrt(dx * dx + dz * dz + dy * dy);
                return Math.max(0, d - reach) * SPRINT;
            }
            public String toString() { return "reach " + bx + " " + by + " " + bz; }
        };
    }

    // ------------------------------------------------------------------------
    // Moves
    // ------------------------------------------------------------------------

    public enum Kind { WALK, DIAGONAL, ASCEND, DESCEND, FALL, DIG_DOWN, PILLAR, BRIDGE, SWIM_UP, SWIM_DOWN }

    public static final long NONE = Long.MIN_VALUE;

    /** One action: from src feet to dest feet, breaking these blocks (in order) and placing one. */
    public static final class Move {
        public final Kind kind;
        public final int sx, sy, sz, dx, dy, dz;
        public final long[] breaks;
        public final long place;
        public final double cost;

        Move(Kind kind, int sx, int sy, int sz, int dx, int dy, int dz, long[] breaks, long place, double cost) {
            this.kind = kind;
            this.sx = sx; this.sy = sy; this.sz = sz;
            this.dx = dx; this.dy = dy; this.dz = dz;
            this.breaks = breaks;
            this.place = place;
            this.cost = cost;
        }

        @Override
        public String toString() {
            return kind + " " + sx + "," + sy + "," + sz + " -> " + dx + "," + dy + "," + dz
                    + (breaks.length > 0 ? " break " + breaks.length : "") + (place != NONE ? " place" : "")
                    + " (" + Math.round(cost) + "t)";
        }
    }

    public static final class Options {
        public boolean allowBreak = true;
        public boolean allowPlace = true;
        public int throwaway = 0;        // blocks available to place
        public int maxFall = 3;          // safe drop without water
        public int maxNodes = 40_000;
        public double weight = 1.25;     // weighted A*: a bit greedy, much faster
        public java.util.Set<Long> avoid = Collections.emptySet(); // cells not to use (failed before)
    }

    // ------------------------------------------------------------------------
    // Packing
    // ------------------------------------------------------------------------

    public static long pack(int x, int y, int z) {
        return ((long) (x + (1 << 25)) << 38) | ((long) (z + (1 << 25)) << 12) | (long) (y + 2048);
    }
    public static int unpackX(long k) { return (int) (k >>> 38) - (1 << 25); }
    public static int unpackZ(long k) { return (int) ((k >>> 12) & ((1L << 26) - 1)) - (1 << 25); }
    public static int unpackY(long k) { return (int) (k & 0xFFF) - 2048; }

    // ------------------------------------------------------------------------
    // Search (incremental)
    // ------------------------------------------------------------------------

    public enum Status { RUNNING, FOUND, PARTIAL, FAILED }

    private static final class Node {
        final int x, y, z;
        double g = INF, h;
        Node parent;
        Move via;
        boolean closed;
        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    private record Entry(Node node, double f) {}

    public static final class Search {
        private final World w;
        private final Goal goal;
        private final Options opt;
        private final LongMaps.LongObj<Node> nodes = new LongMaps.LongObj<>(4096);
        private final PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        private final Node start;
        private Node best;
        private Node found;
        private int expanded;
        private Status status = Status.RUNNING;

        public Search(World w, int sx, int sy, int sz, Goal goal, Options opt) {
            this.w = w;
            this.goal = goal;
            this.opt = opt;
            start = node(sx, sy, sz);
            start.g = 0;
            start.h = goal.heuristic(sx, sy, sz);
            best = start;
            open.add(new Entry(start, start.h * opt.weight));
            if (goal.reached(sx, sy, sz)) { found = start; status = Status.FOUND; }
        }

        public Status status() { return status; }
        public int expanded() { return expanded; }

        private Node node(int x, int y, int z) {
            long k = pack(x, y, z);
            Node n = nodes.get(k);
            if (n == null) { n = new Node(x, y, z); nodes.put(k, n); }
            return n;
        }

        /** Expands nodes for up to {@code maxNanos}. */
        public Status step(long maxNanos) {
            if (status != Status.RUNNING) return status;
            long end = System.nanoTime() + maxNanos;
            int since = 0;
            while (!open.isEmpty()) {
                if (++since >= 64) {
                    since = 0;
                    if (System.nanoTime() > end) return status;
                }
                Entry e = open.poll();
                Node n = e.node;
                if (n.closed || e.f > n.g + n.h * opt.weight + 1e-9) continue;
                n.closed = true;
                if (goal.reached(n.x, n.y, n.z)) {
                    found = n;
                    return status = Status.FOUND;
                }
                if (n.h < best.h || (n.h == best.h && n.g < best.g)) best = n;
                if (++expanded >= opt.maxNodes) break;
                expand(n);
            }
            return status = best == start ? Status.FAILED : Status.PARTIAL;
        }

        /** Moves to the goal (FOUND) or to the closest spot found (PARTIAL). */
        public List<Move> path() {
            Node end = found != null ? found : best;
            List<Move> out = new ArrayList<>();
            for (Node n = end; n != null && n.via != null; n = n.parent) out.add(n.via);
            Collections.reverse(out);
            return out;
        }

        private void relax(Node from, Move m) {
            if (!opt.avoid.isEmpty() && opt.avoid.contains(pack(m.dx, m.dy, m.dz))) return;
            Node to = node(m.dx, m.dy, m.dz);
            if (to.closed) return;
            double g = from.g + m.cost;
            if (water(m.dx, m.dy + 1, m.dz)) g += UNDERWATER_PENALTY; // would be holding its breath there
            else if (water(m.dx, m.dy, m.dz)) g += WATER_PENALTY;
            if (g >= to.g) return;
            to.g = g;
            to.parent = from;
            to.via = m;
            to.h = goal.heuristic(to.x, to.y, to.z);
            open.add(new Entry(to, g + to.h * opt.weight));
        }

        // ---------------- cell helpers ----------------

        private boolean pass(int x, int y, int z) {
            int f = w.flags(x, y, z);
            return (f & World.PASS) != 0 && (f & (World.AVOID | World.UNLOADED)) == 0;
        }

        private boolean water(int x, int y, int z) {
            return (w.flags(x, y, z) & World.WATER) != 0;
        }

        private boolean stand(int x, int y, int z) {
            int f = w.flags(x, y, z);
            return (f & World.STAND) != 0 && (f & (World.AVOID | World.UNLOADED)) == 0;
        }

        private boolean avoidCell(int x, int y, int z) {
            if (opt.avoid.isEmpty()) return false;
            return opt.avoid.contains(pack(x, y, z));
        }

        /** Cost to make a cell passable (0 if it is), recording what has to be broken. */
        private double clear(int x, int y, int z, List<Long> breaks) {
            int f = w.flags(x, y, z);
            if ((f & (World.AVOID | World.UNLOADED)) != 0) return INF;
            if ((f & World.PASS) != 0) return 0;
            if (!opt.allowBreak || avoidCell(x, y, z)) return INF;
            double t = w.breakTicks(x, y, z);
            if (t < 0) return INF;
            breaks.add(pack(x, y, z));
            double c = t + BREAK_OVERHEAD;
            if ((w.flags(x, y + 1, z) & World.FALLS) != 0) c += FALLING_BLOCK_PENALTY;
            return c;
        }

        private static long[] arr(List<Long> l) {
            long[] a = new long[l.size()];
            for (int i = 0; i < a.length; i++) a[i] = l.get(i);
            return a;
        }

        private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

        private void expand(Node n) {
            final int x = n.x, y = n.y, z = n.z;
            boolean inWater = water(x, y, z);
            // standing on a block this path just placed (bridging / pillaring) counts as ground
            boolean onGround = stand(x, y - 1, z) || (n.via != null && n.via.place == pack(x, y - 1, z));

            for (int[] d : CARDINAL) {
                int tx = x + d[0], tz = z + d[1];

                // walk / tunnel / bridge: same level
                {
                    List<Long> br = new ArrayList<>(2);
                    double c = clear(tx, y + 1, tz, br) + clear(tx, y, tz, br);
                    if (c < INF) {
                        boolean wet = inWater || water(tx, y, tz);
                        if (stand(tx, y - 1, tz) || water(tx, y, tz)) {
                            double base = br.isEmpty() ? (wet ? SWIM : SPRINT) : WALK;
                            relax(n, new Move(Kind.WALK, x, y, z, tx, y, tz, arr(br), NONE, base + c));
                        } else if (opt.allowPlace && opt.throwaway > 0 && onGround && !wet
                                && pass(tx, y - 1, tz) && !water(tx, y - 1, tz) && !avoidCell(tx, y - 1, tz)
                                && (w.flags(tx, y - 2, tz) & World.AVOID) == 0) {
                            relax(n, new Move(Kind.BRIDGE, x, y, z, tx, y, tz, arr(br), pack(tx, y - 1, tz),
                                    WALK + PLACE + PLACE_PENALTY + c));
                        }
                    }
                }

                // ascend: jump up onto the next block
                if (stand(tx, y, tz) && (onGround || inWater)) {
                    List<Long> br = new ArrayList<>(3);
                    double c = clear(x, y + 2, z, br) + clear(tx, y + 2, tz, br) + clear(tx, y + 1, tz, br);
                    if (c < INF) relax(n, new Move(Kind.ASCEND, x, y, z, tx, y + 1, tz, arr(br), NONE, WALK + JUMP + c));
                }

                // descend / fall: step off into the next column
                {
                    List<Long> br = new ArrayList<>(3);
                    double c = clear(tx, y + 1, tz, br) + clear(tx, y, tz, br);
                    if (c < INF) {
                        if (!stand(tx, y - 1, tz) && !water(tx, y, tz)) {
                            // nothing to stand on: drop until something is under us
                            boolean hasBreaks = !br.isEmpty();
                            for (int k = 1; k < FALL.length - 1; k++) {
                                int fy = y - k;
                                if (!pass(tx, fy, tz)) break;
                                if (water(tx, fy, tz)) {
                                    relax(n, new Move(Kind.FALL, x, y, z, tx, fy, tz, arr(br), NONE, WALK + FALL[k] + c));
                                    break;
                                }
                                if (stand(tx, fy - 1, tz)) {
                                    if (k <= opt.maxFall) {
                                        relax(n, new Move(k == 1 ? Kind.DESCEND : Kind.FALL, x, y, z, tx, fy, tz, arr(br), NONE,
                                                WALK + FALL[k] + c + (k == 3 ? 4 : 0)));
                                    }
                                    break;
                                }
                                if ((w.flags(tx, fy - 1, tz) & (World.AVOID | World.UNLOADED)) != 0) break;
                                if (hasBreaks && k >= 1) break; // don't tunnel into a drop
                            }
                        } else if (stand(tx, y - 1, tz) && opt.allowBreak && !water(tx, y, tz)) {
                            // staircase down: dig out the block in front-below too
                            double c2 = clear(tx, y - 1, tz, br);
                            if (c2 < INF && c2 > 0 && stand(tx, y - 2, tz)) {
                                relax(n, new Move(Kind.DESCEND, x, y, z, tx, y - 1, tz, arr(br), NONE, WALK + FALL[1] + c + c2));
                            }
                        }
                    }
                }
            }

            // diagonal walk (no digging, no corner cutting)
            if (!inWater) {
                for (int[] d : DIAGONAL) {
                    int tx = x + d[0], tz = z + d[1];
                    if (pass(tx, y, tz) && pass(tx, y + 1, tz) && stand(tx, y - 1, tz)
                            && pass(x + d[0], y, z) && pass(x + d[0], y + 1, z)
                            && pass(x, y, z + d[1]) && pass(x, y + 1, z + d[1])
                            && !water(tx, y, tz)) {
                        relax(n, new Move(Kind.DIAGONAL, x, y, z, tx, y, tz, new long[0], NONE, SPRINT * Math.sqrt(2)));
                    }
                }
            }

            // dig straight down (only onto something solid)
            if (opt.allowBreak && onGround && !inWater && stand(x, y - 2, z)) {
                List<Long> br = new ArrayList<>(1);
                double c = clear(x, y - 1, z, br);
                if (c < INF && !br.isEmpty()) {
                    relax(n, new Move(Kind.DIG_DOWN, x, y, z, x, y - 1, z, arr(br), NONE, c + FALL[1]));
                }
            }

            // pillar up: jump and place a block under yourself
            if (opt.allowPlace && opt.throwaway > 0 && onGround && !inWater && !avoidCell(x, y, z)) {
                List<Long> br = new ArrayList<>(1);
                double c = clear(x, y + 2, z, br);
                if (c < INF) {
                    relax(n, new Move(Kind.PILLAR, x, y, z, x, y + 1, z, arr(br), pack(x, y, z), JUMP + PLACE + PLACE_PENALTY + c));
                }
            }

            // swimming up / down
            if (inWater || water(x, y + 1, z)) {
                if (pass(x, y + 1, z) && pass(x, y + 2, z)) {
                    relax(n, new Move(Kind.SWIM_UP, x, y, z, x, y + 1, z, new long[0], NONE, SWIM * 0.8));
                }
            }
            if (inWater && water(x, y - 1, z)) {
                relax(n, new Move(Kind.SWIM_DOWN, x, y, z, x, y - 1, z, new long[0], NONE, SWIM * 0.8));
            }
        }
    }
}
