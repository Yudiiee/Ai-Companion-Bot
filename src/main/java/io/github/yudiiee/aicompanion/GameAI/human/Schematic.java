package io.github.yudiiee.aicompanion.GameAI.human;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * A building plan read from a schematic file: Sponge {@code .schem} (WorldEdit, versions 1-3),
 * vanilla structure {@code .nbt} (structure blocks) and Litematica {@code .litematic}.
 * Plain Java, no game classes: positions are local ({@code 0..sx-1} etc.) and blocks are
 * {@link State}s ("minecraft:observer[facing=up]").
 */
public final class Schematic {

    /** Most cells a plan may have (a 160x160x160 box). */
    static final int MAX_VOLUME = 160 * 160 * 160;

    public final String name;
    public final int sx, sy, sz;
    /** Palette index per cell, {@code (y * sz + z) * sx + x}; -1 = leave whatever is there. */
    private final int[] cells;
    private final List<State> palette;

    Schematic(String name, int sx, int sy, int sz, int[] cells, List<State> palette) {
        this.name = name;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.cells = cells;
        this.palette = palette;
    }

    int index(int x, int y, int z) { return (y * sz + z) * sx + x; }

    boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
    }

    /** The block planned at (x, y, z), or null if the plan doesn't care about that cell. */
    public State at(int x, int y, int z) {
        if (!inside(x, y, z)) return null;
        int i = cells[index(x, y, z)];
        return i < 0 ? null : palette.get(i);
    }

    /** How many of each block (by state text), air and "don't care" left out. */
    public Map<String, Integer> blockCounts() {
        Map<String, Integer> out = new TreeMap<>();
        for (int c : cells) {
            if (c < 0) continue;
            State s = palette.get(c);
            if (Rules.kind(s) == Rules.Kind.AIR) continue;
            out.merge(s.id(), 1, Integer::sum);
        }
        return out;
    }

    /** Cells with a block in them (not air, not "don't care"). */
    public int solidCount() {
        int n = 0;
        for (int c : cells) if (c >= 0 && Rules.kind(palette.get(c)) != Rules.Kind.AIR) n++;
        return n;
    }

    // ------------------------------------------------------------------------
    // Rotation (clockwise, seen from above: north -> east -> south -> west)
    // ------------------------------------------------------------------------

    /** The plan turned {@code turns} quarter turns clockwise. */
    public Schematic rotated(int turns) {
        int t = Math.floorMod(turns, 4);
        if (t == 0) return this;
        int nsx = (t % 2 == 1) ? sz : sx, nsz = (t % 2 == 1) ? sx : sz;
        int[] out = new int[cells.length];
        java.util.Arrays.fill(out, -1);
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int c = cells[index(x, y, z)];
                    if (c < 0) continue;
                    int[] p = turnXZ(x, z, sx, sz, t);
                    out[(y * nsz + p[1]) * nsx + p[0]] = c;
                }
            }
        }
        List<State> pal = new ArrayList<>(palette.size());
        for (State s : palette) pal.add(s.rotated(t));
        return new Schematic(name, nsx, sy, nsz, out, pal);
    }

    /** Local (x, z) in an {@code sx} by {@code sz} box after {@code t} clockwise quarter turns. */
    static int[] turnXZ(int x, int z, int sx, int sz, int t) {
        int cx = x, cz = z, w = sx, l = sz;
        for (int i = 0; i < Math.floorMod(t, 4); i++) {
            int nx = l - 1 - cz, nz = cx;
            cx = nx;
            cz = nz;
            int tmp = w;
            w = l;
            l = tmp;
        }
        return new int[]{cx, cz};
    }

    static final String[] HORIZONTAL = {"north", "east", "south", "west"};

    /** A horizontal direction after {@code t} clockwise quarter turns (others unchanged). */
    public static String turnDir(String d, int t) {
        for (int i = 0; i < 4; i++) {
            if (HORIZONTAL[i].equals(d)) return HORIZONTAL[Math.floorMod(i + t, 4)];
        }
        return d;
    }

    // ------------------------------------------------------------------------
    // Block states
    // ------------------------------------------------------------------------

    /** A block and its properties, e.g. {@code minecraft:repeater[delay=2,facing=east]}. */
    public record State(String id, SortedMap<String, String> props) {

        private static final Pattern TEXT = Pattern.compile("^\\s*([a-z0-9_.\\-]+:)?([a-z0-9_./\\-]+)\\s*(?:\\[(.*)\\])?\\s*$");
        private static final Pattern WORLD = Pattern.compile("Block\\{([^}]+)\\}(?:\\[(.*)\\])?");

        public State {
            props = Collections.unmodifiableSortedMap(new TreeMap<>(props));
        }

        /** "minecraft:stone", "oak_stairs[facing=east]"... (no namespace means minecraft). */
        public static State parse(String text) {
            Matcher m = TEXT.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT));
            if (!m.matches()) return new State("minecraft:air", new TreeMap<>());
            String ns = m.group(1) == null ? "minecraft:" : m.group(1);
            String path = m.group(2);
            if (ns.equals("minecraft:")) path = switch (path) { // renamed since older files were saved
                case "grass" -> "short_grass";
                case "grass_path" -> "dirt_path";
                case "chain" -> "iron_chain";
                default -> path;
            };
            return new State(ns + path, props(m.group(3)));
        }

        /** The text a game block state prints: "Block{minecraft:stone}[...]". Falls back to {@link #parse}. */
        public static State ofWorld(String text) {
            Matcher m = WORLD.matcher(text == null ? "" : text);
            if (!m.find()) return parse(text);
            String id = m.group(1).contains(":") ? m.group(1) : "minecraft:" + m.group(1);
            return new State(id, props(m.group(2)));
        }

        private static SortedMap<String, String> props(String inner) {
            SortedMap<String, String> p = new TreeMap<>();
            if (inner == null || inner.isBlank()) return p;
            for (String kv : inner.split(",")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                p.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
            return p;
        }

        /** The id without "minecraft:" ("oak_stairs"); other mods keep their namespace. */
        public String path() {
            return id.startsWith("minecraft:") ? id.substring(10) : id;
        }

        public String get(String key) { return props.get(key); }

        public State with(String key, String value) {
            SortedMap<String, String> p = new TreeMap<>(props);
            if (value == null) p.remove(key);
            else p.put(key, value);
            return new State(id, p);
        }

        public State withId(String newId) {
            return new State(newId.contains(":") ? newId : "minecraft:" + newId, props);
        }

        @Override
        public String toString() {
            if (props.isEmpty()) return id;
            StringBuilder sb = new StringBuilder(id).append('[');
            boolean first = true;
            for (Map.Entry<String, String> e : props.entrySet()) {
                if (!first) sb.append(',');
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            return sb.append(']').toString();
        }

        /** This block turned {@code t} quarter turns clockwise. */
        State rotated(int t) {
            if (Math.floorMod(t, 4) == 0 || props.isEmpty()) return this;
            SortedMap<String, String> out = new TreeMap<>();
            for (Map.Entry<String, String> e : props.entrySet()) {
                String k = e.getKey(), v = e.getValue();
                switch (k) {
                    case "facing" -> out.put(k, turnDir(v, t));
                    case "axis" -> out.put(k, t % 2 == 1 && !v.equals("y") ? (v.equals("x") ? "z" : "x") : v);
                    case "rotation" -> {
                        try {
                            out.put(k, String.valueOf(Math.floorMod(Integer.parseInt(v) + 4 * t, 16)));
                        } catch (NumberFormatException ex) {
                            out.put(k, v);
                        }
                    }
                    case "north", "east", "south", "west" -> out.put(turnDir(k, t), v);
                    case "shape" -> out.put(k, path().contains("rail") ? turnRail(v, t) : v);
                    case "orientation" -> {
                        String[] parts = v.split("_");
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < parts.length; i++) sb.append(i == 0 ? "" : "_").append(turnDir(parts[i], t));
                        out.put(k, sb.toString());
                    }
                    default -> out.put(k, v);
                }
            }
            return new State(id, out);
        }

        private static String turnRail(String shape, int t) {
            if (shape.startsWith("ascending_")) return "ascending_" + turnDir(shape.substring(10), t);
            String[] p = shape.split("_");
            if (p.length != 2) return shape;
            String a = turnDir(p[0], t), b = turnDir(p[1], t);
            boolean aNs = a.equals("north") || a.equals("south"), bNs = b.equals("north") || b.equals("south");
            if (aNs && bNs) return "north_south";
            if (!aNs && !bNs) return "east_west";
            return aNs ? a + "_" + b : b + "_" + a;
        }
    }

    // ------------------------------------------------------------------------
    // What a block costs, when it goes in, and when the world already matches
    // ------------------------------------------------------------------------

    /** How the builder treats each planned block. Plain Java so it can be tested. */
    public static final class Rules {

        private Rules() {}

        public enum Kind {
            /** Should be empty. */
            AIR,
            /** Doesn't matter (flowing water, a piston's head, fire...). */
            KEEP,
            /** A block placed from items. */
            BLOCK,
            /** A water source (a bucket of water). */
            WATER,
            /** A lava source (a bucket of lava). */
            LAVA,
            /** The second half of a door, bed or tall plant: comes with the first half. */
            COMPANION,
            /** Can't be had in survival (bedrock, spawners, command blocks...). */
            UNOBTAINABLE
        }

        /** A number of an item. */
        public record Need(String item, int count) {}

        private static final java.util.Set<String> AIRS = java.util.Set.of("air", "cave_air", "void_air");
        private static final java.util.Set<String> KEEPS = java.util.Set.of("structure_void", "piston_head", "moving_piston", "fire", "soul_fire",
                "nether_portal", "end_portal", "end_gateway", "frosted_ice");
        private static final java.util.Set<String> UNOBTAINABLE = java.util.Set.of("bedrock", "barrier", "light", "spawner",
                "trial_spawner", "vault", "command_block", "chain_command_block", "repeating_command_block", "structure_block",
                "jigsaw", "end_portal_frame", "budding_amethyst", "reinforced_deepslate", "petrified_oak_slab", "test_block",
                "test_instance_block");

        public static Kind kind(State s) {
            String p = s.path();
            if (AIRS.contains(p)) return Kind.AIR;
            if (KEEPS.contains(p)) return Kind.KEEP;
            if (p.equals("water") || p.equals("bubble_column")) return "0".equals(s.get("level")) || s.get("level") == null ? Kind.WATER : Kind.KEEP;
            if (p.equals("lava")) return "0".equals(s.get("level")) || s.get("level") == null ? Kind.LAVA : Kind.KEEP;
            if (UNOBTAINABLE.contains(p) || p.startsWith("infested_")) return Kind.UNOBTAINABLE;
            if ("upper".equals(s.get("half")) && !p.endsWith("_stairs") && !p.endsWith("_trapdoor")) return Kind.COMPANION;
            if ("head".equals(s.get("part")) && p.endsWith("_bed")) return Kind.COMPANION;
            return Kind.BLOCK;
        }

        /** The items one of these takes (a double slab is two slabs, a flower pot with a flower is both). */
        public static List<Need> items(State s) {
            String p = s.path();
            if (kind(s) != Kind.BLOCK) return List.of();
            if (p.startsWith("potted_")) {
                String plant = p.substring(7);
                if (plant.endsWith("azalea_bush")) plant = plant.replace("_bush", "");
                return List.of(new Need("flower_pot", 1), new Need(plant, 1));
            }
            int n = 1;
            if (p.endsWith("_slab") && "double".equals(s.get("type"))) n = 2;
            n = Math.max(n, number(s.get("candles")));
            n = Math.max(n, number(s.get("pickles")));
            n = Math.max(n, number(s.get("eggs")));
            if (p.equals("snow")) n = Math.max(n, number(s.get("layers")));
            if (p.endsWith("_petals") || p.equals("wildflowers") || p.equals("leaf_litter")) n = Math.max(n, number(s.get("flower_amount")));
            if (p.equals("leaf_litter")) n = Math.max(n, number(s.get("segment_amount")));
            return List.of(new Need(itemFor(p), n));
        }

        private static int number(String v) {
            try {
                return v == null ? 1 : Math.max(1, Integer.parseInt(v));
            } catch (NumberFormatException e) {
                return 1;
            }
        }

        private static final Map<String, String> ITEM = new HashMap<>();
        static {
            String[][] pairs = {
                    {"redstone_wire", "redstone"}, {"tripwire", "string"}, {"wall_torch", "torch"},
                    {"soul_wall_torch", "soul_torch"}, {"redstone_wall_torch", "redstone_torch"},
                    {"copper_wall_torch", "copper_torch"},
                    {"wheat", "wheat_seeds"}, {"carrots", "carrot"}, {"potatoes", "potato"}, {"beetroots", "beetroot_seeds"},
                    {"melon_stem", "melon_seeds"}, {"attached_melon_stem", "melon_seeds"}, {"pumpkin_stem", "pumpkin_seeds"},
                    {"attached_pumpkin_stem", "pumpkin_seeds"}, {"cocoa", "cocoa_beans"}, {"sweet_berry_bush", "sweet_berries"},
                    {"cave_vines", "glow_berries"}, {"cave_vines_plant", "glow_berries"}, {"kelp_plant", "kelp"},
                    {"bamboo_sapling", "bamboo"}, {"twisting_vines_plant", "twisting_vines"}, {"weeping_vines_plant", "weeping_vines"},
                    {"torchflower_crop", "torchflower_seeds"}, {"pitcher_crop", "pitcher_pod"}, {"big_dripleaf_stem", "big_dripleaf"},
                    {"water_cauldron", "cauldron"}, {"lava_cauldron", "cauldron"}, {"powder_snow_cauldron", "cauldron"},
                    {"farmland", "dirt"}, {"dirt_path", "dirt"}, {"grass_block", "dirt"}, {"podzol", "dirt"}, {"mycelium", "dirt"},
                    {"tall_seagrass", "seagrass"}, {"powder_snow", "powder_snow_bucket"}, {"bubble_column", "water_bucket"},
            };
            for (String[] p : pairs) ITEM.put(p[0], p[1]);
        }

        /** The item that places this block ("wall_torch" -> "torch", "carrots" -> "carrot"). */
        public static String itemFor(String path) {
            String i = ITEM.get(path);
            if (i != null) return i;
            if (path.contains("_wall_hanging_sign")) return path.replace("_wall_hanging_sign", "_hanging_sign");
            if (path.endsWith("_wall_sign")) return path.replace("_wall_sign", "_sign");
            if (path.endsWith("_wall_banner")) return path.replace("_wall_banner", "_banner");
            if (path.endsWith("_wall_head")) return path.replace("_wall_head", "_head");
            if (path.endsWith("_wall_skull")) return path.replace("_wall_skull", "_skull");
            if (path.endsWith("_wall_fan")) return path.replace("_wall_fan", "_fan");
            return path;
        }

        /** The block actually set: crops and cane start young, pistons retracted, water added later. */
        public static State placeState(State s) {
            State out = s;
            if ("true".equals(out.get("waterlogged"))) out = out.with("waterlogged", "false");
            if (out.get("age") != null && (isCrop(out.path()) || isTallPlant(out.path()))) out = out.with("age", "0");
            if (out.path().equals("bamboo")) out = out.withId("bamboo_sapling").with("age", null).with("leaves", null).with("stage", null);
            if ("true".equals(out.get("extended"))) out = out.with("extended", "false");
            if ("true".equals(out.get("occupied"))) out = out.with("occupied", "false");
            if ("true".equals(out.get("triggered"))) out = out.with("triggered", "false");
            if (out.path().endsWith("_leaves")) out = out.with("persistent", "true").with("distance", null); // placed leaves don't decay
            // the circuit powers itself back up; a lever's position is the player's choice
            if ("true".equals(out.get("powered")) && !out.path().equals("lever")) out = out.with("powered", "false");
            String id = switch (out.path()) {
                case "grass_block", "podzol", "mycelium" -> "dirt";
                default -> null;
            };
            if (id != null) out = new State("minecraft:" + id, new TreeMap<>());
            return out;
        }

        /** 0 = solid parts, bottom up; 1 = water and lava; 2 = things that hang on other blocks, and plants. */
        public static int phase(State s) {
            Kind k = kind(s);
            if (k == Kind.WATER || k == Kind.LAVA) return 1;
            return attached(s.path()) ? 2 : 0;
        }

        private static final String[] ATTACHED = {"torch", "redstone_wire", "repeater", "comparator", "button", "lever",
                "pressure_plate", "rail", "carpet", "_sign", "ladder", "vine", "_door", "banner", "tripwire", "sapling",
                "_bush", "sugar_cane", "cactus", "kelp", "seagrass", "sea_pickle", "lily_pad", "nether_wart",
                "cocoa", "snow", "glow_lichen", "sculk_vein", "coral", "dead_bush", "short_grass", "fern", "tall_grass",
                "mushroom", "dripstone", "flower", "tulip", "dandelion", "poppy", "orchid", "allium", "bluet", "daisy",
                "cornflower", "lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush", "peony",
                "torchflower", "pitcher", "_petals", "wildflowers", "leaf_litter", "hanging_roots", "spore_blossom",
                "dripleaf", "lantern", "candle", "_head", "_skull", "frogspawn", "item_frame", "string",
                "wheat", "carrots", "potatoes", "beetroots", "melon_stem", "pumpkin_stem", "cave_vines", "weeping_vines", "twisting_vines",
                "chain", "end_rod", "lightning_rod", "bell", "scaffolding", "crimson_roots", "warped_roots", "crimson_fungus",
                "warped_fungus"};

        static boolean attached(String path) {
            if (path.equals("bamboo") || path.equals("bamboo_sapling")) return true;
            if (path.endsWith("_block") || path.endsWith("_planks") || path.endsWith("_log") || path.endsWith("_wood")
                    || path.endsWith("_hyphae") || path.endsWith("_stem") && !path.endsWith("melon_stem") && !path.endsWith("pumpkin_stem")
                    || path.equals("jack_o_lantern") || path.equals("sea_lantern")) return false;
            for (String a : ATTACHED) if (path.contains(a)) return true;
            return false;
        }

        public static boolean isCrop(String path) {
            return path.equals("wheat") || path.equals("carrots") || path.equals("potatoes") || path.equals("beetroots")
                    || path.equals("nether_wart") || path.equals("torchflower_crop") || path.equals("pitcher_crop");
        }

        /** Grows upwards from the planted block and gets cut above it. */
        public static boolean isTallPlant(String path) {
            return path.equals("sugar_cane") || path.equals("cactus") || path.equals("bamboo") || path.equals("bamboo_sapling")
                    || path.equals("kelp") || path.equals("kelp_plant");
        }

        /** The plant a tall-plant cell grows into ("bamboo_sapling" -> "bamboo"). */
        public static String grown(String path) {
            return switch (path) {
                case "bamboo_sapling" -> "bamboo";
                case "kelp" -> "kelp_plant";
                default -> path;
            };
        }

        /** Ripe enough to harvest (a crop at its last age). */
        public static boolean ripe(State world) {
            String p = world.path();
            int age;
            try {
                age = world.get("age") == null ? -1 : Integer.parseInt(world.get("age"));
            } catch (NumberFormatException e) {
                age = -1;
            }
            return switch (p) {
                case "wheat", "carrots", "potatoes" -> age >= 7;
                case "beetroots", "nether_wart" -> age >= 3;
                case "torchflower_crop" -> false; // turns into the flower
                case "pitcher_crop" -> age >= 4;
                default -> false;
            };
        }

        public static boolean isGravity(String path) {
            return path.equals("sand") || path.equals("red_sand") || path.equals("gravel") || path.endsWith("concrete_powder")
                    || path.contains("anvil") || path.equals("dragon_egg") || path.equals("suspicious_sand")
                    || path.equals("suspicious_gravel");
        }

        /** Properties that change how a block works or points; the rest the game sorts out by itself. */
        private static final java.util.Set<String> KEY = java.util.Set.of("facing", "axis", "rotation", "half", "type",
                "face", "attachment", "hinge", "orientation", "mode", "delay", "part", "hanging", "open", "powered", "inverted");

        /** Does the block that's there count as the planned one? */
        public static boolean matches(State planned, State world) {
            Kind k = kind(planned);
            String wp = world.path();
            switch (k) {
                case KEEP:
                    return true;
                case AIR:
                    if (AIRS.contains(wp)) return true;
                    // water running through on its way somewhere is fine; a source is in the way
                    return (wp.equals("water") || wp.equals("lava")) && !"0".equals(world.get("level"));
                case WATER:
                    return (wp.equals("water") && "0".equals(world.get("level"))) || wp.equals("bubble_column");
                case LAVA:
                    return wp.equals("lava") && "0".equals(world.get("level"));
                default:
                    break;
            }
            if (!sameBlock(planned.path(), wp)) return false;
            if (isTallPlant(planned.path()) || isCrop(planned.path()) || planned.path().endsWith("_stem") && planned.path().contains("melon")
                    || planned.path().endsWith("_stem") && planned.path().contains("pumpkin")) return true;
            for (String key : KEY) {
                String a = planned.get(key);
                if (a == null) continue;
                // redstone flips these while the thing runs; only a lever's own setting is part of the plan
                if (key.equals("powered") && !planned.path().equals("lever")) continue;
                if (key.equals("open") && (planned.path().endsWith("_door") || "true".equals(world.get("powered")))) continue;
                if (key.equals("type") && planned.path().endsWith("chest")) continue; // double chests pair up by themselves
                if (!a.equals(world.get(key))) return false;
            }
            if (planned.path().contains("rail") && planned.get("shape") != null && !planned.get("shape").equals(world.get("shape")))
                return false;
            return true;
        }

        /** Waterlogged in the plan but not yet in the world. */
        public static boolean needsWaterlogging(State planned, State world) {
            return "true".equals(planned.get("waterlogged")) && sameBlock(planned.path(), world.path())
                    && !"true".equals(world.get("waterlogged"));
        }

        static boolean sameBlock(String planned, String world) {
            if (planned.equals(world)) return true;
            switch (planned) {
                case "grass_block", "podzol", "mycelium", "dirt", "coarse_dirt", "rooted_dirt":
                    return world.equals("dirt") || world.equals("grass_block") || world.equals("podzol") || world.equals("mycelium")
                            || world.equals("coarse_dirt") || world.equals("rooted_dirt");
                case "bamboo_sapling":
                    return world.equals("bamboo");
                case "bamboo":
                    return world.equals("bamboo_sapling");
                case "kelp":
                    return world.equals("kelp_plant");
                case "kelp_plant":
                    return world.equals("kelp");
                case "cave_vines":
                    return world.equals("cave_vines_plant");
                case "cave_vines_plant":
                    return world.equals("cave_vines");
                default:
                    break;
            }
            // any wood will do: oak stairs for spruce stairs (the bot uses what it has)
            String[] wa = wood(planned), wb = wood(world);
            if (wa != null && wb != null && wa[1].equals(wb[1])) return true;
            // things that change by themselves: farmland dries out, powder sets next to water
            if (planned.equals("farmland") && world.equals("dirt")) return true;
            if (planned.endsWith("_concrete_powder") && world.equals(planned.replace("_powder", ""))) return true;
            // a pumpkin/melon stem grows into the attached one and back
            if (planned.endsWith("_stem") && world.endsWith("_stem") && planned.replace("attached_", "").equals(world.replace("attached_", "")))
                return true;
            // copper weathers
            String strip = planned.replaceFirst("^(waxed_)?(exposed_|weathered_|oxidized_)?", "");
            return planned.contains("copper") && strip.equals(world.replaceFirst("^(waxed_)?(exposed_|weathered_|oxidized_)?", ""));
        }

        private static final String[] SPECIES = {"dark_oak", "pale_oak", "oak", "spruce", "birch", "jungle", "acacia",
                "mangrove", "cherry", "crimson", "warped", "bamboo"};
        private static final java.util.Set<String> WOOD_KINDS = java.util.Set.of("planks", "log", "wood", "stairs", "slab",
                "fence", "fence_gate", "trapdoor", "door", "pressure_plate", "button", "sign", "wall_sign", "hanging_sign",
                "wall_hanging_sign", "stripped_log", "stripped_wood");

        /**
         * {species, kind} for anything made of one kind of wood ("spruce_stairs" -> {spruce, stairs},
         * "stripped_crimson_stem" -> {crimson, stripped_log}), else null.
         */
        public static String[] wood(String path) {
            boolean stripped = path.startsWith("stripped_");
            String p = stripped ? path.substring(9) : path;
            for (String sp : SPECIES) {
                if (!p.startsWith(sp + "_")) continue;
                String kind = p.substring(sp.length() + 1);
                kind = switch (kind) {
                    case "stem" -> "log";
                    case "hyphae" -> "wood";
                    case "block" -> sp.equals("bamboo") ? "log" : kind;
                    default -> kind;
                };
                if (sp.equals("bamboo") && kind.equals("wood")) return null;
                if (stripped) {
                    if (!kind.equals("log") && !kind.equals("wood")) return null;
                    kind = "stripped_" + kind;
                }
                return WOOD_KINDS.contains(kind) ? new String[]{sp, kind} : null;
            }
            return null;
        }

        /** The id of {@code kind} in {@code species} ({@code crimson, log} -> "crimson_stem"). */
        public static String woodPath(String species, String kind) {
            boolean nether = species.equals("crimson") || species.equals("warped");
            if (kind.startsWith("stripped_")) return "stripped_" + woodPath(species, kind.substring(9));
            if (kind.equals("log")) return species.equals("bamboo") ? "bamboo_block" : species + (nether ? "_stem" : "_log");
            if (kind.equals("wood")) return species + (nether ? "_hyphae" : "_wood");
            return species + "_" + kind;
        }

        /** Plain cubes that go in with a normal right-click (the bot places those itself, like a player). */
        public static boolean byHand(State s) {
            if (kind(s) != Kind.BLOCK || phase(s) != 0 || "true".equals(s.get("waterlogged"))) return false;
            for (String key : s.props().keySet()) if (!key.equals("waterlogged") && !key.equals("snowy")) return false;
            return placeState(s).id().equals(s.id()) && itemFor(s.path()).equals(s.path());
        }
    }

    // ------------------------------------------------------------------------
    // Reading files
    // ------------------------------------------------------------------------

    /** "iron_farm.schem" -> "iron farm". */
    public static String displayName(String fileName) {
        String base = fileName.replaceFirst("\\.[A-Za-z0-9]+$", "");
        return base.replaceAll("[_\\-.]+", " ").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /** Reads a schematic file: {@code .schem}, {@code .nbt} or {@code .litematic}. */
    public static Schematic load(Path file) throws IOException {
        String fn = file.getFileName().toString().toLowerCase(Locale.ROOT);
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(file)) {
            root = Nbt.read(in);
        }
        return fromNbt(displayName(file.getFileName().toString()), fn, root);
    }

    static Schematic fromNbt(String name, String fileName, Map<String, Object> root) throws IOException {
        if (fileName.endsWith(".litematic") || root.get("Regions") instanceof Map) return litematica(name, root);
        if (root.get("blocks") instanceof List && root.get("size") instanceof List) return structure(name, root);
        if (root.get("Schematic") instanceof Map || root.containsKey("Palette") || root.containsKey("BlockData")) return sponge(name, root);
        if (root.containsKey("Materials") || root.get("Blocks") instanceof byte[])
            throw new IOException("that's an old MCEdit .schematic; save it again as a .schem (WorldEdit) or .litematic");
        throw new IOException("not a schematic I can read");
    }

    private static int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        throw new IllegalArgumentException("expected a number, got " + o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> comp(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    private static void checkSize(int sx, int sy, int sz) throws IOException {
        if (sx <= 0 || sy <= 0 || sz <= 0) throw new IOException("it's empty");
        if ((long) sx * sy * sz > MAX_VOLUME) throw new IOException("it's too big (" + sx + "x" + sy + "x" + sz + ")");
    }

    private static State fromCompound(Map<String, Object> c) {
        Object n = c.get("Name");
        SortedMap<String, String> props = new TreeMap<>();
        Map<String, Object> p = comp(c.get("Properties"));
        if (p != null) for (Map.Entry<String, Object> e : p.entrySet()) props.put(e.getKey(), String.valueOf(e.getValue()));
        State s = State.parse(n == null ? "air" : String.valueOf(n));
        return new State(s.id(), props);
    }

    /** Sponge schematic, versions 1-3 (WorldEdit's .schem). */
    static Schematic sponge(String name, Map<String, Object> root) throws IOException {
        Map<String, Object> s = comp(root.get("Schematic")) != null ? comp(root.get("Schematic")) : root;
        try {
            int w = num(s.get("Width")) & 0xFFFF, h = num(s.get("Height")) & 0xFFFF, l = num(s.get("Length")) & 0xFFFF;
            checkSize(w, h, l);
            Map<String, Object> pal;
            Object data;
            Map<String, Object> blocks = comp(s.get("Blocks"));
            if (blocks != null) {
                pal = comp(blocks.get("Palette"));
                data = blocks.get("Data");
            } else {
                pal = comp(s.get("Palette"));
                data = s.get("BlockData");
            }
            if (pal == null || !(data instanceof byte[] bytes)) throw new IOException("there are no blocks in it");
            int max = 0;
            for (Object v : pal.values()) max = Math.max(max, num(v));
            if (max > 1 << 20) throw new IOException("its palette is broken");
            State[] byIndex = new State[max + 1];
            for (Map.Entry<String, Object> e : pal.entrySet()) byIndex[num(e.getValue())] = State.parse(e.getKey());
            List<State> palette = new ArrayList<>();
            Map<String, Integer> seen = new HashMap<>();
            int[] remap = new int[max + 1];
            for (int i = 0; i <= max; i++) {
                if (byIndex[i] == null) { remap[i] = -1; continue; }
                String key = byIndex[i].toString();
                Integer at = seen.get(key);
                if (at == null) { at = palette.size(); palette.add(byIndex[i]); seen.put(key, at); }
                remap[i] = at;
            }
            int n = w * h * l;
            int[] cells = new int[n];
            java.util.Arrays.fill(cells, -1);
            int i = 0, pos = 0;
            while (i < n && pos < bytes.length) {
                int value = 0, shift = 0;
                byte b;
                do {
                    if (pos >= bytes.length) throw new IOException("its block data is cut short");
                    b = bytes[pos++];
                    value |= (b & 0x7F) << shift;
                    shift += 7;
                    if (shift > 35) throw new IOException("its block data is broken");
                } while ((b & 0x80) != 0);
                cells[i++] = value >= 0 && value <= max ? remap[value] : -1;
            }
            return new Schematic(name, w, h, l, cells, palette);
        } catch (IllegalArgumentException | ClassCastException e) {
            throw new IOException("it's not a proper .schem file");
        }
    }

    /** A structure block file (.nbt). Cells the file doesn't list are left as they are. */
    static Schematic structure(String name, Map<String, Object> root) throws IOException {
        try {
            List<Object> size = list(root.get("size"));
            int sx = num(size.get(0)), sy = num(size.get(1)), sz = num(size.get(2));
            checkSize(sx, sy, sz);
            List<Object> pal = list(root.get("palette"));
            if (pal == null) {
                List<Object> pals = list(root.get("palettes"));
                pal = pals == null || pals.isEmpty() ? null : list(pals.get(0));
            }
            if (pal == null) throw new IOException("there's no palette in it");
            List<State> palette = new ArrayList<>();
            for (Object o : pal) palette.add(fromCompound(comp(o)));
            int[] cells = new int[sx * sy * sz];
            java.util.Arrays.fill(cells, -1);
            Schematic tmp = new Schematic(name, sx, sy, sz, cells, palette);
            for (Object o : list(root.get("blocks"))) {
                Map<String, Object> b = comp(o);
                List<Object> pos = list(b.get("pos"));
                int x = num(pos.get(0)), y = num(pos.get(1)), z = num(pos.get(2));
                int st = num(b.get("state"));
                if (!tmp.inside(x, y, z) || st < 0 || st >= palette.size()) continue;
                cells[tmp.index(x, y, z)] = st;
            }
            return tmp;
        } catch (IllegalArgumentException | ClassCastException | NullPointerException | IndexOutOfBoundsException e) {
            throw new IOException("it's not a proper structure file");
        }
    }

    /** Litematica (.litematic): every region of it, put together. */
    static Schematic litematica(String name, Map<String, Object> root) throws IOException {
        try {
            Map<String, Object> regions = comp(root.get("Regions"));
            if (regions == null || regions.isEmpty()) throw new IOException("there are no regions in it");
            record Region(int x, int y, int z, int sx, int sy, int sz, List<State> palette, long[] bits) {}
            List<Region> rs = new ArrayList<>();
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (Object o : regions.values()) {
                Map<String, Object> r = comp(o);
                Map<String, Object> p = comp(r.get("Position")), size = comp(r.get("Size"));
                int px = num(p.get("x")), py = num(p.get("y")), pz = num(p.get("z"));
                int qx = num(size.get("x")), qy = num(size.get("y")), qz = num(size.get("z"));
                int x0 = qx < 0 ? px + qx + 1 : px, y0 = qy < 0 ? py + qy + 1 : py, z0 = qz < 0 ? pz + qz + 1 : pz;
                int ax = Math.abs(qx), ay = Math.abs(qy), az = Math.abs(qz);
                checkSize(ax, ay, az);
                List<State> pal = new ArrayList<>();
                for (Object e : list(r.get("BlockStatePalette"))) pal.add(fromCompound(comp(e)));
                if (!(r.get("BlockStates") instanceof long[] bits)) throw new IOException("a region has no blocks");
                rs.add(new Region(x0, y0, z0, ax, ay, az, pal, bits));
                minX = Math.min(minX, x0); minY = Math.min(minY, y0); minZ = Math.min(minZ, z0);
                maxX = Math.max(maxX, x0 + ax - 1); maxY = Math.max(maxY, y0 + ay - 1); maxZ = Math.max(maxZ, z0 + az - 1);
            }
            int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
            checkSize(sx, sy, sz);
            int[] cells = new int[sx * sy * sz];
            java.util.Arrays.fill(cells, -1);
            List<State> palette = new ArrayList<>();
            Map<String, Integer> seen = new HashMap<>();
            Schematic out = new Schematic(name, sx, sy, sz, cells, palette);
            for (Region r : rs) {
                int[] remap = new int[r.palette().size()];
                for (int i = 0; i < remap.length; i++) {
                    String key = r.palette().get(i).toString();
                    Integer at = seen.get(key);
                    if (at == null) { at = palette.size(); palette.add(r.palette().get(i)); seen.put(key, at); }
                    remap[i] = at;
                }
                int bitsPer = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, r.palette().size() - 1)));
                long mask = (1L << bitsPer) - 1;
                long[] arr = r.bits();
                int n = r.sx() * r.sy() * r.sz();
                if ((long) n * bitsPer > (long) arr.length * 64) throw new IOException("a region's block data is cut short");
                for (int i = 0; i < n; i++) {
                    long start = (long) i * bitsPer;
                    int si = (int) (start >> 6), ei = (int) (((long) (i + 1) * bitsPer - 1) >> 6);
                    int off = (int) (start & 63);
                    long v = si == ei ? (arr[si] >>> off) & mask : ((arr[si] >>> off) | (arr[ei] << (64 - off))) & mask;
                    if (v >= remap.length) continue;
                    int lx = i % r.sx(), lz = (i / r.sx()) % r.sz(), ly = i / (r.sx() * r.sz());
                    cells[out.index(r.x() - minX + lx, r.y() - minY + ly, r.z() - minZ + lz)] = remap[(int) v];
                }
            }
            return out;
        } catch (IllegalArgumentException | ClassCastException | NullPointerException | IndexOutOfBoundsException e) {
            throw new IOException("it's not a proper .litematic file");
        }
    }

    // ------------------------------------------------------------------------
    // NBT
    // ------------------------------------------------------------------------

    /** Minimal NBT reader: compounds become maps, lists lists, arrays arrays. Gzipped or not. */
    static final class Nbt {
        private static final int MAX_LEN = 1 << 26;

        private Nbt() {}

        @SuppressWarnings("unchecked")
        static Map<String, Object> read(InputStream raw) throws IOException {
            BufferedInputStream b = new BufferedInputStream(raw);
            b.mark(4);
            int m1 = b.read(), m2 = b.read();
            b.reset();
            InputStream in = (m1 == 0x1f && m2 == 0x8b) ? new GZIPInputStream(b) : b;
            DataInputStream d = new DataInputStream(new BufferedInputStream(in));
            int type = d.readUnsignedByte();
            if (type != 10) throw new IOException("not an NBT file");
            d.readUTF();
            return (Map<String, Object>) payload(d, 10, 0);
        }

        private static int len(DataInputStream d) throws IOException {
            int n = d.readInt();
            if (n < 0 || n > MAX_LEN) throw new IOException("broken NBT (length " + n + ")");
            return n;
        }

        private static Object payload(DataInputStream d, int type, int depth) throws IOException {
            if (depth > 96) throw new IOException("NBT nested too deep");
            switch (type) {
                case 1: return d.readByte();
                case 2: return d.readShort();
                case 3: return d.readInt();
                case 4: return d.readLong();
                case 5: return d.readFloat();
                case 6: return d.readDouble();
                case 7: {
                    byte[] a = new byte[len(d)];
                    d.readFully(a);
                    return a;
                }
                case 8: return d.readUTF();
                case 9: {
                    int et = d.readUnsignedByte();
                    int n = len(d);
                    if (et == 0 && n > 0) throw new IOException("broken NBT list");
                    List<Object> l = new ArrayList<>(Math.min(n, 4096));
                    for (int i = 0; i < n; i++) l.add(payload(d, et, depth + 1));
                    return l;
                }
                case 10: {
                    Map<String, Object> m = new LinkedHashMap<>();
                    while (true) {
                        int t = d.readUnsignedByte();
                        if (t == 0) break;
                        String k = d.readUTF();
                        m.put(k, payload(d, t, depth + 1));
                    }
                    return m;
                }
                case 11: {
                    int n = len(d);
                    int[] a = new int[n];
                    for (int i = 0; i < n; i++) a[i] = d.readInt();
                    return a;
                }
                case 12: {
                    int n = len(d);
                    long[] a = new long[n];
                    for (int i = 0; i < n; i++) a[i] = d.readLong();
                    return a;
                }
                default:
                    throw new IOException("unknown NBT tag " + type);
            }
        }
    }
}
