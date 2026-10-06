import io.github.yudiiee.aicompanion.GameAI.human.*;
import io.github.yudiiee.aicompanion.GameAI.human.Schematic.Rules;
import io.github.yudiiee.aicompanion.GameAI.human.Schematic.State;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.HashMap;
import java.util.zip.GZIPOutputStream;

/** Schematic reading, rotation, block rules, name matching and chat routing for the builder. */
public class BlueprintTest {
    static int fails = 0;

    static void check(boolean c, String msg) {
        if (!c) { fails++; System.out.println("FAIL: " + msg); } else System.out.println("ok: " + msg);
    }

    // ---- tiny NBT writer for fixtures ----
    static final class W {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream d = new DataOutputStream(bytes);
        W begin(String root) throws IOException { d.writeByte(10); d.writeUTF(root); return this; }
        W tag(int t, String n) throws IOException { d.writeByte(t); d.writeUTF(n); return this; }
        W i(String n, int v) throws IOException { tag(3, n); d.writeInt(v); return this; }
        W s(String n, short v) throws IOException { tag(2, n); d.writeShort(v); return this; }
        W str(String n, String v) throws IOException { tag(8, n); d.writeUTF(v); return this; }
        W bytesTag(String n, byte[] v) throws IOException { tag(7, n); d.writeInt(v.length); d.write(v); return this; }
        W longs(String n, long[] v) throws IOException { tag(12, n); d.writeInt(v.length); for (long l : v) d.writeLong(l); return this; }
        W comp(String n) throws IOException { return tag(10, n); }
        W end() throws IOException { d.writeByte(0); return this; }
        byte[] gz() throws IOException {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (GZIPOutputStream g = new GZIPOutputStream(o)) { g.write(bytes.toByteArray()); }
            return o.toByteArray();
        }
    }

    static byte[] varints(int[] vals) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int v : vals) {
            while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; }
            o.write(v);
        }
        return o.toByteArray();
    }

    static Path write(Path dir, String name, byte[] data) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, data);
        return p;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("bp");
        Path starters = Paths.get(a.length > 0 ? a[0] : "src/src/main/resources/assets/ai-companion/schematics");

        // ---------------- states ----------------
        State st = State.parse("oak_stairs[half=bottom,facing=east]");
        check(st.id().equals("minecraft:oak_stairs") && st.get("facing").equals("east"), "state parse adds namespace");
        check(st.toString().equals("minecraft:oak_stairs[facing=east,half=bottom]"), "state prints sorted");
        State ws = State.ofWorld("Block{minecraft:repeater}[delay=3,facing=north,locked=false,powered=false]");
        check(ws.path().equals("repeater") && ws.get("delay").equals("3"), "world state text parsed");
        check(State.ofWorld("Block{minecraft:stone}").props().isEmpty(), "world state without props");

        // ---------------- rotation ----------------
        check(Schematic.turnDir("north", 1).equals("east") && Schematic.turnDir("west", 1).equals("north")
                && Schematic.turnDir("up", 3).equals("up"), "directions turn clockwise");
        int[] p1 = invoke("turnXZ", 0, 0, 3, 5, 1);
        check(p1[0] == 4 && p1[1] == 0, "north-west corner goes to north-east after a quarter turn: " + Arrays.toString(p1));
        int[] p2 = invoke("turnXZ", 2, 4, 3, 5, 2);
        check(p2[0] == 0 && p2[1] == 0, "half turn swaps corners: " + Arrays.toString(p2));
        Method rot = State.class.getDeclaredMethod("rotated", int.class);
        rot.setAccessible(true);
        check(rot.invoke(State.parse("observer[facing=north]"), 1).toString().equals("minecraft:observer[facing=east]"), "observer turns");
        check(rot.invoke(State.parse("oak_log[axis=x]"), 1).toString().equals("minecraft:oak_log[axis=z]"), "log axis swaps");
        check(rot.invoke(State.parse("rail[shape=north_south]"), 1).toString().equals("minecraft:rail[shape=east_west]"), "rail straight turns");
        check(rot.invoke(State.parse("rail[shape=south_east]"), 1).toString().equals("minecraft:rail[shape=south_west]"), "rail curve turns");
        check(rot.invoke(State.parse("rail[shape=ascending_north]"), 3).toString().equals("minecraft:rail[shape=ascending_west]"), "rail slope turns");
        check(rot.invoke(State.parse("oak_sign[rotation=14]"), 1).toString().equals("minecraft:oak_sign[rotation=2]"), "sign rotation wraps");
        check(rot.invoke(State.parse("oak_fence[east=true,north=false,south=false,west=true]"), 1).toString()
                .equals("minecraft:oak_fence[east=false,north=true,south=true,west=false]"), "fence sides turn");
        check(rot.invoke(State.parse("oak_stairs[facing=east,shape=inner_left]"), 2).toString()
                .equals("minecraft:oak_stairs[facing=west,shape=inner_left]"), "stair shape kept");

        // ---------------- rules ----------------
        check(Rules.kind(State.parse("air")) == Rules.Kind.AIR && Rules.kind(State.parse("cave_air")) == Rules.Kind.AIR, "air kinds");
        check(Rules.kind(State.parse("water[level=0]")) == Rules.Kind.WATER && Rules.kind(State.parse("water[level=3]")) == Rules.Kind.KEEP, "water source vs flowing");
        check(Rules.kind(State.parse("oak_door[half=upper,facing=north]")) == Rules.Kind.COMPANION, "door top comes with the bottom");
        check(Rules.kind(State.parse("oak_stairs[half=top]")) == Rules.Kind.BLOCK, "upside-down stairs are a block");
        check(Rules.kind(State.parse("red_bed[part=head]")) == Rules.Kind.COMPANION, "bed head comes with the foot");
        check(Rules.kind(State.parse("spawner")) == Rules.Kind.UNOBTAINABLE && Rules.kind(State.parse("bedrock")) == Rules.Kind.UNOBTAINABLE, "unobtainables");
        check(Rules.kind(State.parse("piston_head[facing=up]")) == Rules.Kind.KEEP, "piston head left alone");
        check(Rules.items(State.parse("stone_slab[type=double]")).get(0).count() == 2, "double slab is two slabs");
        check(Rules.items(State.parse("redstone_wire[power=0]")).get(0).item().equals("redstone"), "wire is redstone dust");
        check(Rules.items(State.parse("wall_torch[facing=east]")).get(0).item().equals("torch"), "wall torch is a torch");
        check(Rules.items(State.parse("carrots[age=7]")).get(0).item().equals("carrot"), "carrots are carrots");
        check(Rules.items(State.parse("oak_wall_sign[facing=east]")).get(0).item().equals("oak_sign"), "wall sign is a sign");
        check(Rules.items(State.parse("potted_dead_bush")).size() == 2
                && Rules.items(State.parse("potted_dead_bush")).get(1).item().equals("dead_bush"), "potted dead bush keeps its name");
        check(Rules.items(State.parse("sea_pickle[pickles=3]")).get(0).count() == 3, "sea pickles count");
        check(Rules.phase(State.parse("stone")) == 0 && Rules.phase(State.parse("water")) == 1 && Rules.phase(State.parse("torch")) == 2
                && Rules.phase(State.parse("sugar_cane")) == 2 && Rules.phase(State.parse("bamboo")) == 2
                && Rules.phase(State.parse("bamboo_planks")) == 0 && Rules.phase(State.parse("bamboo_button[face=wall]")) == 2
                && Rules.phase(State.parse("oak_trapdoor")) == 0 && Rules.phase(State.parse("crimson_stem")) == 0
                && Rules.phase(State.parse("melon_stem")) == 2 && Rules.phase(State.parse("jack_o_lantern")) == 0
                && Rules.phase(State.parse("snow_block")) == 0 && Rules.phase(State.parse("oak_door")) == 2, "build phases");
        check(Rules.byHand(State.parse("stone")) && Rules.byHand(State.parse("glass")) && !Rules.byHand(State.parse("observer[facing=up]"))
                && !Rules.byHand(State.parse("grass_block[snowy=false]")) && !Rules.byHand(State.parse("torch")), "by hand only for plain cubes");
        check(Rules.placeState(State.parse("wheat[age=7]")).toString().equals("minecraft:wheat[age=0]"), "crops planted young");
        check(Rules.placeState(State.parse("bamboo[age=1,leaves=large,stage=0]")).toString().equals("minecraft:bamboo_sapling"), "bamboo starts as a shoot");
        check(Rules.placeState(State.parse("sticky_piston[extended=true,facing=up]")).get("extended").equals("false"), "pistons retracted");
        check(Rules.placeState(State.parse("observer[facing=up,powered=true]")).get("powered").equals("false")
                && Rules.placeState(State.parse("lever[face=wall,facing=north,powered=true]")).get("powered").equals("true"), "only levers keep power");
        check(Rules.placeState(State.parse("oak_slab[type=bottom,waterlogged=true]")).get("waterlogged").equals("false"), "water added later");
        // matching
        check(Rules.matches(State.parse("grass_block"), State.parse("dirt")), "dirt counts for grass");
        check(Rules.matches(State.parse("sugar_cane[age=0]"), State.parse("sugar_cane[age=9]")), "cane any age");
        check(Rules.matches(State.parse("bamboo_sapling"), State.parse("bamboo[age=0,leaves=none,stage=0]")), "grown bamboo counts");
        check(!Rules.matches(State.parse("observer[facing=up]"), State.parse("observer[facing=down,powered=false]")), "observer facing matters");
        check(Rules.matches(State.parse("repeater[delay=2,facing=east,powered=false]"), State.parse("repeater[delay=2,facing=east,powered=true,locked=false]")), "power ignored");
        check(!Rules.matches(State.parse("repeater[delay=2,facing=east]"), State.parse("repeater[delay=1,facing=east]")), "repeater delay matters");
        check(Rules.matches(State.parse("air"), State.parse("water[level=3]")) && !Rules.matches(State.parse("air"), State.parse("water[level=0]")), "flowing water ok in air, source not");
        check(!Rules.matches(State.parse("air"), State.parse("short_grass")), "grass gets cleared");
        check(Rules.matches(State.parse("oak_door[half=lower,open=false]"), State.parse("oak_door[half=lower,open=true]")), "doors can be open");
        check(Rules.matches(State.parse("chest[type=single,facing=north]"), State.parse("chest[type=left,facing=north]")), "chest pairing ignored");
        check(Rules.matches(State.parse("oak_trapdoor[open=true]"), State.parse("oak_trapdoor[open=false,powered=true]"))
                && !Rules.matches(State.parse("oak_trapdoor[open=true]"), State.parse("oak_trapdoor[open=false,powered=false]")), "trapdoor open unless redstone flips it");
        check(!Rules.matches(State.parse("oak_planks"), State.parse("spruce_planks")) && !Rules.matches(State.parse("dark_oak_log[axis=y]"), State.parse("spruce_log[axis=y]"))
                && Rules.matches(State.parse("red_bed[part=foot,facing=north]"), State.parse("white_bed[part=foot,facing=north,occupied=false]")), "wood is exact (colour matters), beds any colour");
        check(Rules.matches(State.parse("farmland[moisture=7]"), State.parse("dirt")) && Rules.matches(State.parse("red_concrete_powder"), State.parse("red_concrete")), "blocks that change by themselves");
        check(Rules.matches(State.parse("attached_melon_stem[facing=east]"), State.parse("melon_stem[age=7]")), "stems any way");
        check(Rules.kind(State.parse("structure_void")) == Rules.Kind.KEEP, "structure void left as is");
        check(State.parse("grass").path().equals("short_grass"), "old names renamed");
        check(Rules.placeState(State.parse("oak_leaves[distance=7,persistent=false]")).get("persistent").equals("true"), "placed leaves don't decay");
        check(Rules.needsWaterlogging(State.parse("oak_slab[waterlogged=true]"), State.parse("oak_slab[waterlogged=false]")), "waterlogging noticed");
        check(Rules.ripe(State.parse("wheat[age=7]")) && !Rules.ripe(State.parse("wheat[age=0]")) && Rules.ripe(State.parse("beetroots[age=3]")), "ripe crops");
        check(Rules.itemFor("oak_wall_hanging_sign").equals("oak_hanging_sign"), "hanging wall sign item");

        // ---------------- formats ----------------
        // vanilla structure (the starter designs)
        for (String n : new String[]{"sugar_cane_farm", "cactus_farm", "bamboo_farm"}) {
            Schematic s = Schematic.load(starters.resolve(n + ".nbt"));
            check(s.name.equals(n.replace('_', ' ')), n + " loads: " + s.sx + "x" + s.sy + "x" + s.sz + " " + s.blockCounts());
        }
        Schematic cane = Schematic.load(starters.resolve("sugar_cane_farm.nbt"));
        Map<String, Integer> cc = cane.blockCounts();
        check(cc.get("minecraft:sand") == 60 && cc.get("minecraft:water") == 21 && cc.get("minecraft:sugar_cane") == 42, "cane farm contents");
        for (int x = 0; x < 9; x++) for (int z = 1; z < 8; z++) {
            if (x % 3 == 1) continue;
            boolean nextToWater = false;
            for (int dx = -1; dx <= 1; dx += 2) {
                State n = cane.at(x + dx, 0, z);
                if (n != null && Rules.kind(n) == Rules.Kind.WATER) nextToWater = true;
            }
            if (!nextToWater) check(false, "cane at " + x + "," + z + " has no water");
        }
        check(true, "every cane is next to water");
        Schematic cactus = Schematic.load(starters.resolve("cactus_farm.nbt"));
        boolean spaced = true;
        for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) {
            State c = cactus.at(x, 1, z);
            if (c == null || !c.path().equals("cactus")) continue;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                State n = cactus.at(x + d[0], 1, z + d[1]);
                if (n != null && Rules.kind(n) != Rules.Kind.AIR) spaced = false;
            }
        }
        check(spaced && cactus.blockCounts().get("minecraft:cactus") == 16, "cactus have room round them");
        Schematic bamboo = Schematic.load(starters.resolve("bamboo_farm.nbt"));
        check(bamboo.blockCounts().get("minecraft:bamboo_sapling") == 16 && bamboo.blockCounts().get("minecraft:torch") == 5, "bamboo farm contents");
        Method bill = Class.forName("io.github.yudiiee.aicompanion.GameAI.human.BlueprintBuilder").getDeclaredMethod("billOf", Schematic.class);
        bill.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Integer> bb = (Map<String, Integer>) bill.invoke(null, bamboo);
        check(bb.get("dirt") == 49 && bb.get("bamboo") == 16 && bb.get("torch") == 5, "bamboo farm bill: " + bb);
        Schematic turned = cane.rotated(1);
        check(turned.sx == 9 && turned.sz == 9 && turned.at(8 - 1, 0, 1) != null, "rotated plan in bounds");
        check(Rules.kind(cane.at(1, 0, 3)) == Rules.Kind.WATER && Rules.kind(turned.at(8 - 3, 0, 1)) == Rules.Kind.WATER, "channel moves with the turn");

        // Sponge v2: 2x2x1, palette air=0 stone=1 observer=2
        int[] data = {1, 2, 0, 300 % 3};
        W w = new W().begin("Schematic").i("Version", 2).s("Width", (short) 2).s("Height", (short) 1).s("Length", (short) 2)
                .comp("Palette").i("minecraft:air", 0).i("minecraft:stone", 1).i("minecraft:observer[facing=up]", 2).end()
                .bytesTag("BlockData", varints(data)).end();
        Schematic sp2 = Schematic.load(write(dir, "v2.schem", w.gz()));
        check(sp2.at(0, 0, 0).path().equals("stone") && sp2.at(1, 0, 0).toString().equals("minecraft:observer[facing=up]")
                && Rules.kind(sp2.at(0, 0, 1)) == Rules.Kind.AIR, "sponge v2 reads");
        // Sponge v3 with a palette index over 127 (two-byte varint)
        W w3 = new W().begin("").comp("Schematic").i("Version", 3).s("Width", (short) 1).s("Height", (short) 2).s("Length", (short) 1)
                .comp("Blocks").comp("Palette").i("minecraft:dirt", 200).i("minecraft:glass", 0).end()
                .bytesTag("Data", varints(new int[]{200, 0})).end().end().end();
        Schematic sp3 = Schematic.load(write(dir, "v3.schem", w3.gz()));
        check(sp3.at(0, 0, 0).path().equals("dirt") && sp3.at(0, 1, 0).path().equals("glass"), "sponge v3 reads (2-byte index)");
        // Litematica: one region 3x1x2 with a negative size on x, palette of 5 (3 bits each)
        List<String> pal = List.of("minecraft:air", "minecraft:stone", "minecraft:hopper", "minecraft:glass", "minecraft:dirt");
        int[] vals = {1, 2, 3, 4, 0, 2};
        int bitsPer = 3;
        long[] packed = new long[(vals.length * bitsPer + 63) / 64];
        for (int i = 0; i < vals.length; i++) {
            long start = (long) i * bitsPer;
            int si = (int) (start >> 6), off = (int) (start & 63);
            packed[si] |= ((long) vals[i]) << off;
            int ei = (int) (((long) (i + 1) * bitsPer - 1) >> 6);
            if (ei != si) packed[ei] |= ((long) vals[i]) >>> (64 - off);
        }
        W lw = new W().begin("").i("Version", 6).comp("Regions").comp("main")
                .comp("Position").i("x", 2).i("y", 0).i("z", 0).end()
                .comp("Size").i("x", -3).i("y", 1).i("z", 2).end();
        lw.tag(9, "BlockStatePalette");
        lw.d.writeByte(10);
        lw.d.writeInt(pal.size());
        for (String s : pal) { lw.str("Name", s); if (s.endsWith("hopper")) lw.comp("Properties").str("facing", "down").end(); lw.end(); }
        lw.longs("BlockStates", packed).end().end().end();
        Schematic lit = Schematic.load(write(dir, "farm.litematic", lw.gz()));
        check(lit.sx == 3 && lit.sz == 2 && lit.at(0, 0, 0).path().equals("stone") && lit.at(1, 0, 0).toString().equals("minecraft:hopper[facing=down]")
                && lit.at(2, 0, 0).path().equals("glass") && lit.at(0, 0, 1).path().equals("dirt") && lit.at(2, 0, 1).path().equals("hopper"),
                "litematica reads (packed bits, negative size)");
        // old MCEdit format is refused with a reason
        W old = new W().begin("Schematic").s("Width", (short) 1).s("Height", (short) 1).s("Length", (short) 1)
                .str("Materials", "Alpha").bytesTag("Blocks", new byte[]{1}).bytesTag("Data", new byte[]{0}).end();
        try {
            Schematic.load(write(dir, "old.schematic", old.gz()));
            check(false, "old format refused");
        } catch (IOException e) {
            check(e.getMessage().contains("MCEdit"), "old format refused: " + e.getMessage());
        }
        try {
            Schematic.load(write(dir, "junk.nbt", new byte[]{1, 2, 3}));
            check(false, "junk refused");
        } catch (IOException e) {
            check(true, "junk refused: " + e.getMessage());
        }

        // ---------------- wood and recipes ----------------
        check(Arrays.equals(Rules.wood("spruce_stairs"), new String[]{"spruce", "stairs"}) && Arrays.equals(Rules.wood("dark_oak_planks"), new String[]{"dark_oak", "planks"})
                && Arrays.equals(Rules.wood("stripped_crimson_stem"), new String[]{"crimson", "stripped_log"}) && Rules.wood("oak_leaves") == null
                && Rules.wood("stone_bricks") == null && Arrays.equals(Rules.wood("spruce_wall_sign"), new String[]{"spruce", "wall_sign"}), "wood names");
        check(Rules.woodPath("warped", "log").equals("warped_stem") && Rules.woodPath("oak", "stripped_log").equals("stripped_oak_log")
                && Rules.woodPath("birch", "fence_gate").equals("birch_fence_gate"), "wood ids");
        check(!Rules.matches(State.parse("spruce_trapdoor[facing=east,half=top,open=false]"), State.parse("oak_trapdoor[facing=east,half=top,open=false,powered=false]"))
                && !Rules.matches(State.parse("spruce_trapdoor"), State.parse("spruce_door")), "spruce trapdoor isn't oak");
        Class<?> bbc = Class.forName("io.github.yudiiee.aicompanion.GameAI.human.BlueprintBuilder");
        Method recipeFor = bbc.getDeclaredMethod("recipeFor", String.class);
        recipeFor.setAccessible(true);
        Object rs = recipeFor.invoke(null, "spruce_stairs");
        check(rs != null && rs.toString().contains("spruce_planks=6") && rs.toString().contains("out=4"), "stairs recipe: " + rs);
        Object rstrip = recipeFor.invoke(null, "stripped_spruce_log");
        check(rstrip != null && rstrip.toString().contains("spruce_log=1") && rstrip.toString().contains("_axe"), "stripped log takes an axe");
        check(recipeFor.invoke(null, "chiseled_stone_bricks").toString().contains("stone_brick_slab=2"), "chiseled from slabs");
        Method obtainable = bbc.getDeclaredMethod("obtainable", String.class, int.class);
        obtainable.setAccessible(true);
        for (String it : new String[]{"stone_brick_slab", "spruce_fence_gate", "lantern", "hopper", "glass_pane", "campfire", "chest", "spruce_sign"})
            check((boolean) obtainable.invoke(null, it, 0), it + " can be made");
        for (String it : new String[]{"moss_carpet", "decorated_pot", "purple_banner"})
            check(!(boolean) obtainable.invoke(null, it, 0), it + " has to be brought");

        // ---------------- woods ----------------
        check(Woods.closest("dark_oak").subList(0, 2).contains("spruce") && Woods.closest("spruce").subList(0, 2).contains("dark_oak"),
                "dark oak and spruce are each other's near colours: " + Woods.closest("dark_oak"));
        check(Woods.closest("birch").get(0).equals("oak") || Woods.closest("birch").get(0).equals("pale_oak") || Woods.closest("birch").get(0).equals("cherry"),
                "birch's nearest colour: " + Woods.closest("birch"));
        System.out.println("  warped -> " + Woods.closest("warped") + ", spruce -> " + Woods.closest("spruce"));
        check(Woods.speciesOf("stripped_dark_oak_log").equals("dark_oak") && Woods.speciesOf("flowering_azalea_leaves").equals("oak")
                && Woods.speciesOf("warped_stem").equals("warped") && Woods.speciesOf("cherry_leaves").equals("cherry")
                && Woods.speciesOf("stone") == null, "which tree a block comes from");
        check(Woods.isQuestion("what kind of tree is that") && Woods.isQuestion("where do i find dark oak")
                && Woods.isQuestion("what wood is this") && !Woods.isQuestion("get me some wood"), "tree questions");
        check(Woods.answerNamed("where do i find dark oak").contains("dark forest") && Woods.answerNamed("what tree is that") == null, "named answers");
        check(Woods.describe("warped", null).contains("nether") && Woods.describe("birch", null).contains("pale yellow"), "descriptions");
        check(Rules.decorative("poppy") && Rules.decorative("azalea_leaves") && Rules.decorative("cave_vines_plant") && Rules.decorative("red_carpet")
                && !Rules.decorative("spruce_planks") && !Rules.decorative("grass_block") && !Rules.decorative("moss_block") && !Rules.decorative("lantern"), "decoration");
        Method swapped = bbc.getDeclaredMethod("swapped", State.class, Map.class);
        swapped.setAccessible(true);
        Map<String, String> sw = new HashMap<>(Map.of("wood:warped", "dark_oak", "calcite", "diorite"));
        check(swapped.invoke(null, State.parse("warped_stairs[facing=east,half=top]"), sw).toString().equals("minecraft:dark_oak_stairs[facing=east,half=top]")
                && swapped.invoke(null, State.parse("calcite"), sw).toString().equals("minecraft:diorite")
                && swapped.invoke(null, State.parse("stripped_warped_stem[axis=x]"), sw).toString().equals("minecraft:stripped_dark_oak_log[axis=x]")
                && swapped.invoke(null, State.parse("spruce_slab"), sw).toString().equals("minecraft:spruce_slab"), "stand-ins swap in");
        Map<String, String> chain = new HashMap<>(Map.of("wood:warped", "dark_oak", "wood:dark_oak", "spruce"));
        check(swapped.invoke(null, State.parse("warped_stairs[facing=east]"), chain).toString().equals("minecraft:spruce_stairs[facing=east]"),
                "stand-ins chain (warped -> dark oak -> spruce)");
        Method enc = bbc.getDeclaredMethod("encodeSwaps", Map.class), dec = bbc.getDeclaredMethod("decodeSwaps", String.class);
        enc.setAccessible(true);
        dec.setAccessible(true);
        check(dec.invoke(null, enc.invoke(null, sw)).equals(sw), "stand-ins saved and read back: " + enc.invoke(null, sw));
        check(!(boolean) obtainable.invoke(null, "warped_stairs", 0) && !(boolean) obtainable.invoke(null, "prismarine_brick_stairs", 0)
                && !(boolean) obtainable.invoke(null, "gray_stained_glass", 0) && (boolean) obtainable.invoke(null, "dark_oak_stairs", 0)
                && (boolean) obtainable.invoke(null, "cobblestone_stairs", 0) && (boolean) obtainable.invoke(null, "stone_brick_wall", 0)
                && (boolean) obtainable.invoke(null, "barrel", 0) && (boolean) obtainable.invoke(null, "smoker", 0), "what it can make in the overworld");
        Method itemTest = bbc.getDeclaredMethod("itemTest", String.class);
        itemTest.setAccessible(true);
        @SuppressWarnings("unchecked") java.util.function.Predicate<String> planksAny = (java.util.function.Predicate<String>) itemTest.invoke(null, "#planks");
        @SuppressWarnings("unchecked") java.util.function.Predicate<String> spruce = (java.util.function.Predicate<String>) itemTest.invoke(null, "spruce_planks");
        check(planksAny.test("birch_planks") && spruce.test("spruce_planks") && !spruce.test("oak_planks"), "recipe wildcards vs exact wood");

        // ---------------- the six starter houses ----------------
        Class<?> sh = Class.forName("io.github.yudiiee.aicompanion.GameAI.human.StarterHouse");
        Method layoutAt = sh.getDeclaredMethod("layoutAt", Schematic.class, int.class, int.class, int.class, int.class);
        layoutAt.setAccessible(true);
        Method keyOf = sh.getDeclaredMethod("key", int.class, int.class, int.class);
        keyOf.setAccessible(true);
        Method essential = bbc.getDeclaredMethod("essentialCells", Schematic.class);
        essential.setAccessible(true);
        Method entryFor = Blueprints.class.getDeclaredMethod("entryFor", Path.class);
        entryFor.setAccessible(true);
        for (int i = 1; i <= 6; i++) {
            Path f = starters.resolve("medieval_house_" + i + ".nbt");
            Blueprints.Entry en = (Blueprints.Entry) entryFor.invoke(null, f);
            Schematic h = Schematic.load(f);
            Object lay = layoutAt.invoke(null, h, 0, 64, 0, 0);
            int[] m = (int[]) call(lay, "mid");
            int fl = ((List<?>) call(lay, "floorCells")).size(), stn = ((List<?>) call(lay, "storageCells")).size(),
                    sp = ((List<?>) call(lay, "spareCells")).size();
            boolean midInside = ((Set<?>) call(lay, "local")).contains(keyOf.invoke(null, m[0], m[1], m[2]));
            Map<String, Integer> woodsOf = Woods.palette(h.blockCounts());
            System.out.println("  house " + i + " " + en.name() + " " + h.sx + "x" + h.sy + "x" + h.sz + ": " + essential.invoke(null, h)
                    + " blocks, floor " + fl + ", storage " + stn + ", spare " + sp + ", middle " + Arrays.toString(m) + ", wood " + woodsOf);
            check(en.starter() && en.name().startsWith("medieval"), "house " + i + " is a starter design");
            check(fl >= 12 && midInside && sp >= 2, "house " + i + " has an inside, a middle in it and room for chests");
        }
        Object l4 = layoutAt.invoke(null, Schematic.load(starters.resolve("medieval_house_4.nbt")), 0, 64, 0, 0);
        check(((List<?>) call(l4, "storageCells")).size() == 25 && call(l4, "bedCell") != null, "the workshop's barrels are its storage and it has a bed");
        Object l3 = layoutAt.invoke(null, Schematic.load(starters.resolve("medieval_house_3.nbt")), 0, 64, 0, 0);
        check(call(l3, "bedCell") != null, "the inn has beds");

        // the bundled iron farm
        Schematic iron = Schematic.load(starters.resolve("iron_farm.nbt"));
        check(iron.sx == 25 && iron.sy == 24 && iron.sz == 11 && iron.solidCount() == 1861, "iron farm loads: " + iron.sx + "x" + iron.sy + "x" + iron.sz);
        int soil = 0, above = 0;
        for (int x = 0; x < 25; x++) for (int z = 0; z < 11; z++) {
            State g = iron.at(x, 4, z), u = iron.at(x, 5, z);
            if (g != null && (g.path().equals("dirt") || g.path().equals("grass_block"))) soil++;
            if (u != null && (u.path().equals("dirt") || u.path().equals("grass_block"))) above++;
        }
        check(soil > 200 && above == 0, "iron farm layer 4 is the ground (" + soil + " soil blocks)");
        Map<String, Integer> ib = (Map<String, Integer>) bill.invoke(null, iron);
        List<String> bring = new ArrayList<>();
        for (String k : ib.keySet()) if (!(boolean) obtainable.invoke(null, k, 0)) bring.add(k);
        System.out.println("  iron farm, has to be brought: " + bring);
        check(ib.get("yellow_bed") == 3 && !ib.containsKey("water"), "beds counted once each, water isn't an item");

        // ---------------- names ----------------
        List<Blueprints.Entry> entries = List.of(
                entry("sugar cane farm", "cane farm", "sugarcane farm"),
                entry("cactus farm", "cacti farm"),
                entry("bamboo farm"),
                entry("iron farm"), entry("iron trap"), entry("iron golem trap"), entry("medieval inn", "house 3", "inn"), entry("creeper farm", "gunpowder farm"));
        check(name(Blueprints.find("sugarcane farm", entries)).equals("sugar cane farm"), "sugarcane -> sugar cane farm");
        check(name(Blueprints.find("a cane farm", entries)).equals("sugar cane farm"), "cane farm alias");
        check(name(Blueprints.find("the cacti farm", entries)).equals("cactus farm"), "cacti");
        check(name(Blueprints.find("gunpowder farm", entries)).equals("creeper farm"), "gunpowder farm alias");
        check(Blueprints.find("farm", entries) == null, "just 'farm' is too vague");
        check(Blueprints.find("house", entries) == null, "house isn't a design here");
        check(Blueprints.find("stone pickaxe", entries) == null, "tools aren't designs");
        Blueprints.Match ironMatch = Blueprints.find("iron", entries);
        check(ironMatch != null && ironMatch.entry() == null && ironMatch.options().size() == 2, "'iron' is ambiguous between two designs");
        check(name(Blueprints.find("iron farm", entries)).equals("iron farm"), "exact beats partial");

        // ---------------- chat routing ----------------
        Field cached = Blueprints.class.getDeclaredField("cached");
        cached.setAccessible(true);
        cached.set(null, entries);
        Field scanned = Blueprints.class.getDeclaredField("scannedAt");
        scanned.setAccessible(true);
        scanned.set(null, Long.MAX_VALUE / 4);
        String[][] cases = {
                {"build a sugar cane farm", "BLUEPRINT"}, {"can you build the iron farm", "BLUEPRINT"},
                {"make me a cactus farm pls", "BLUEPRINT"}, {"build the creeper farm at 100 64 -20", "BLUEPRINT"},
                {"build a bamboo farm here", "BLUEPRINT"}, {"what schematics do you have", "BLUEPRINT"},
                {"what can you build", "BLUEPRINT"}, {"list the blueprints", "BLUEPRINT"},
                {"continue the build", "BLUEPRINT"}, {"keep building", "BLUEPRINT"}, {"finish building the iron farm", "BLUEPRINT"},
                {"what do you need for the iron farm", "BLUEPRINT"}, {"materials for the cactus farm", "BLUEPRINT"},
                {"build a house", "HOUSE"}, {"build a farm", "FARM"}, {"harvest the crops", "FARM"},
                {"make a stone pickaxe", "COLLECT"}, {"get me 10 iron", "COLLECT"},
                {"keep mining", "MINE"}, {"stop", "STOP"}, {"cancel", "STOP"},
                {"what do you think about building a castle", "null"},
                {"build a cactus farm next to the house", "BLUEPRINT"}, {"make sugar", "null"}, {"make some cactus", "null"},
                {"what kind of tree is that", "TREE"}, {"where can i find cherry wood", "TREE"}, {"build the medieval inn", "BLUEPRINT"},
        };
        for (String[] c : cases) {
            Object got = HumanChatListener.classifyLocal(c[0], "Bro");
            check(String.valueOf(got).equals(c[1]), "\"" + c[0] + "\" -> " + got + " (want " + c[1] + ")");
        }
        check(Blueprints.parse("harvest bamboo", null, null) == null, "harvest bamboo with no bamboo farm: go find some");
        check(Blueprints.parse("harvest the crops", null, null) == null, "crops are the food farm's");
        Blueprints.Ask here = Blueprints.parse("build a bamboo farm here", null, null);
        check(here != null && here.job() == null && here.say().get().contains("where"), "'here' needs someone standing there");
        Blueprints.Ask amb = Blueprints.parse("build the iron", null, null);
        check(amb != null && amb.say().get().startsWith("which one"), "ambiguous asks which");
        Blueprints.Ask list = Blueprints.parse("what can you build", null, null);
        check(list.say().get().contains("sugar cane farm") && list.say().get().contains("creeper farm"), "list names designs: " + list.say().get());
        Blueprints.Ask b2 = Blueprints.parse("build the creeper farm at 100 64 -20 facing west", null, null);
        check(b2 != null && b2.job() != null && b2.job().label().equals("build the creeper farm"), "build at coordinates is a job");

        System.out.println(fails == 0 ? "ALL PASSED" : (fails + " FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }

    static Object call(Object o, String m) throws Exception {
        Method mm = o.getClass().getDeclaredMethod(m);
        mm.setAccessible(true);
        return mm.invoke(o);
    }

    static String name(Blueprints.Match m) {
        return m == null || m.entry() == null ? "?" : m.entry().name();
    }

    static Blueprints.Entry entry(String name, String... aliases) {
        return new Blueprints.Entry(name, Paths.get(name.replace(" ", "_") + ".nbt"), List.of(aliases), "", 0, "south", "", false);
    }

    static int[] invoke(String m, int x, int z, int sx, int sz, int t) throws Exception {
        Method mm = Schematic.class.getDeclaredMethod(m, int.class, int.class, int.class, int.class, int.class);
        mm.setAccessible(true);
        return (int[]) mm.invoke(null, x, z, sx, sz, t);
    }
}
