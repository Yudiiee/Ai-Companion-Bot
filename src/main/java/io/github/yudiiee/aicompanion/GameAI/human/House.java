package io.github.yudiiee.aicompanion.GameAI.human;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.onServer;

/**
 * "build a house": a proper starter base. 9x9 outside (7x7 inside, 4 high) with oak-log
 * corner pillars, a cobblestone bottom row, plank walls, glass windows if it has glass, a
 * plank roof with a slab overhang and cap, and a door facing whoever asked with torches either
 * side. Inside: two double chests (the storage), a furnace between them, a crafting table and
 * a bed.
 *
 * <p>It takes what it can from the chests it already has, gathers the rest (wood, a bit of
 * stone, wool from sheep for the bed) in one go, picks a flat spot close by that isn't in a
 * village, on water or in a tree, levels it, then builds standing in the middle.
 */
final class House {

    private House() {}

    static final int SIZE = 9;
    static final int WALL_H = 4;
    private static final int RADIUS = 16;
    private static final int ROOF_BLOCKS = SIZE * SIZE;

    private static final Pattern HOUSE = Pattern.compile(
            "\\b(build|make|craft|set up|put up|throw up)\\b.*\\b(house|home|hut|shelter|base|cabin|shack)\\b");

    /** "build a house" (chat or a language-model plan step) -> a job, or null. */
    static MiningSkills.Request request(String text, UUID requester) {
        if (text == null) return null;
        String m = text.toLowerCase(Locale.ROOT);
        if (!HOUSE.matcher(m).find()) return null;
        if (m.matches("^(i'?ll|i will|i'?m|im|i am|i'?d|we'?ll|we will|gonna|i'?m gonna|i was)\\b.*")) return null; // their plans, not a request
        String ack = HumanChat.pick("ok, let's build a house", "sure, gonna build us a proper house",
                "bet, house coming up. gonna need a lot of wood", "alright, building a base");
        return new MiningSkills.Request("build a house", ack, (server, bot, b) -> build(server, bot, b, requester));
    }

    // ------------------------------------------------------------------------
    // The site
    // ------------------------------------------------------------------------

    /**
     * A {@code size}x{@code size} footprint. {@code y} is the floor (the ground blocks); the
     * inside starts at y+1. {@code door} is the wall the door goes in. (Old saved homes are 5x5.)
     */
    record Site(int x0, int z0, int y, Direction door, int work, int size) {

        Site(int x0, int z0, int y, Direction door, int work) { this(x0, z0, y, door, work, 5); }

        /** World position of local (lx, lz), where the door is at local (size/2, 0) and lz grows inwards. */
        BlockPos at(int lx, int dy, int lz) {
            int m = size - 1;
            return switch (door) {
                case SOUTH -> new BlockPos(x0 + m - lx, y + dy, z0 + m - lz);
                case WEST -> new BlockPos(x0 + lz, y + dy, z0 + m - lx);
                case EAST -> new BlockPos(x0 + m - lz, y + dy, z0 + lx);
                default -> new BlockPos(x0 + lx, y + dy, z0 + lz);
            };
        }

        BlockPos middle() { return new BlockPos(x0 + size / 2, y + 1, z0 + size / 2); }

        /** The way you face looking from the door to the back wall. */
        Direction back() { return door.getOpposite(); }

        int wallHeight() { return size >= 7 ? WALL_H : 3; }
    }

    private static final int BAD = Integer.MIN_VALUE;

    /** Height of the ground in this column, or BAD (water, a tree, something somebody built, nothing). */
    private static int ground(ServerLevel level, int x, int z, int top, int bottom) {
        for (int y = top; y >= bottom; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (!level.isLoaded(p)) return BAD;
            FluidState fs = level.getFluidState(p);
            if (fs != null && (fs.is(FluidTags.WATER) || fs.is(FluidTags.LAVA))) return BAD;
            BlockState s = level.getBlockState(p);
            if (s.isAir()) continue;
            String path = SurvivalBrain.blockPath(s);
            if (Protection.isManMade(path) || SurvivalBrain.isLog(s)) return BAD;
            if (path.endsWith("_leaves") || s.canBeReplaced() || s.getCollisionShape(level, p).isEmpty()) continue;
            if (path.contains("magma") || path.contains("ice") || path.contains("powder_snow")) return BAD;
            return y;
        }
        return BAD;
    }

    /** Finds a flat-ish spot near {@code anchor}. Server thread. */
    static Site findSite(ServerPlayer bot, BlockPos anchor) {
        ServerLevel level = bot.level();
        Protection.Context ctx = Protection.scan(bot, RADIUS + 8);
        int ax = anchor.getX(), ay = anchor.getY(), az = anchor.getZ();
        int span = 2 * RADIUS + SIZE + 1;
        int mid = SIZE / 2;
        int[][] h = new int[span][span];
        for (int i = 0; i < span; i++) for (int j = 0; j < span; j++) {
            h[i][j] = ground(level, ax - RADIUS + i, az - RADIUS + j, ay + 10, ay - 10);
        }
        Site best = null;
        double bestScore = Double.MAX_VALUE;
        int[] hs = new int[SIZE * SIZE];
        for (int i = 0; i + SIZE <= span; i++) {
            for (int j = 0; j + SIZE <= span; j++) {
                int x0 = ax - RADIUS + i, z0 = az - RADIUS + j;
                // not on top of the person asking, leave them a block of room
                if (ax >= x0 - 1 && ax <= x0 + SIZE && az >= z0 - 1 && az <= z0 + SIZE) continue;
                boolean ok = true;
                for (int k = 0; k < SIZE * SIZE && ok; k++) {
                    hs[k] = h[i + k % SIZE][j + k / SIZE];
                    if (hs[k] == BAD) ok = false;
                }
                if (!ok) continue;
                int[] sorted = hs.clone();
                Arrays.sort(sorted);
                int floor = sorted[sorted.length / 2];
                if (h[i + mid][j + mid] != floor) continue; // the bot stands in the middle while it builds
                int work = 0;
                for (int v : hs) {
                    if (Math.abs(v - floor) > 2) { ok = false; break; }
                    work += Math.abs(v - floor);
                }
                if (!ok || Math.abs(floor - ay) > 6) continue;
                double cx = x0 + SIZE / 2.0, cz = z0 + SIZE / 2.0;
                double dist = Math.hypot(cx - (ax + 0.5), cz - (az + 0.5));
                double score = work * 2.5 + dist;
                if (score >= bestScore) continue;
                // what's in the way inside the house (leaves, grass, bumps are fine to clear; builds are not)
                int clear = clearWork(level, x0, z0, floor);
                if (clear < 0) continue;
                score += clear * 0.4;
                if (score >= bestScore) continue;
                if (ctx.isVillage(new BlockPos(x0 + mid, floor, z0 + mid))) continue;
                bestScore = score;
                best = new Site(x0, z0, floor, doorSide(x0, z0, ax, az), work + clear, SIZE);
            }
        }
        return best;
    }

    /** How many blocks need clearing in the house volume, or -1 if something there must not be broken. */
    private static int clearWork(ServerLevel level, int x0, int z0, int floor) {
        int n = 0;
        for (int dx = 0; dx < SIZE; dx++) for (int dz = 0; dz < SIZE; dz++) for (int dy = 1; dy <= WALL_H + 1; dy++) {
            BlockPos p = new BlockPos(x0 + dx, floor + dy, z0 + dz);
            BlockState s = level.getBlockState(p);
            if (s.isAir()) continue;
            FluidState fs = level.getFluidState(p);
            if (fs != null && (fs.is(FluidTags.WATER) || fs.is(FluidTags.LAVA))) return -1;
            String path = SurvivalBrain.blockPath(s);
            if (Protection.isManMade(path) || SurvivalBrain.isLog(s) || path.contains("spawner")) return -1;
            if (s.canBeReplaced()) continue;
            n++;
        }
        return n;
    }

    /** The door goes in the wall facing the person who asked. */
    private static Direction doorSide(int x0, int z0, int ax, int az) {
        double dx = ax - (x0 + SIZE / 2), dz = az - (z0 + SIZE / 2);
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // ------------------------------------------------------------------------
    // Materials
    // ------------------------------------------------------------------------

    private static boolean isPlanks(String p) { return p.endsWith("_planks"); }

    private static boolean isDoor(String p) { return p.endsWith("_door") && !p.equals("iron_door"); }

    private static boolean isLog(String p) { return SurvivalBrain.isLogItem(p) && !p.startsWith("stripped_"); }

    private static boolean isBed(String p) { return p.endsWith("_bed"); }

    private static boolean isWool(String p) { return p.endsWith("_wool"); }

    private static final Predicate<String> MATERIALS = p -> isPlanks(p) || SurvivalBrain.isCobbleItem(p)
            || SurvivalBrain.isLogItem(p) || isDoor(p) || p.equals("chest") || p.equals("crafting_table")
            || p.equals("glass") || p.equals("torch") || p.equals("stick") || p.endsWith("_slab")
            || p.equals("furnace") || isBed(p) || isWool(p);

    private static int count(ServerPlayer bot, Predicate<String> test) {
        int n = 0;
        Inventory inv = bot.getInventory();
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && test.test(SurvivalBrain.itemPath(s))) n += s.getCount();
        }
        return n;
    }

    @SafeVarargs
    private static String first(ServerPlayer bot, Predicate<String>... prefs) {
        for (Predicate<String> t : prefs) {
            String s = Building.firstItem(bot, t);
            if (s != null) return s;
        }
        return null;
    }

    /** Log pillars at the corners, a cobblestone bottom row, plank walls above (or whatever it has). */
    private static String wallItem(ServerPlayer bot, boolean corner, int dy) {
        if (corner) return first(bot, House::isLog, House::isPlanks, SurvivalBrain::isCobbleItem, LevelPathWorld::isThrowaway);
        if (dy == 1) return first(bot, SurvivalBrain::isCobbleItem, House::isPlanks, LevelPathWorld::isThrowaway);
        return first(bot, House::isPlanks, SurvivalBrain::isCobbleItem, LevelPathWorld::isThrowaway);
    }

    private static String roofItem(ServerPlayer bot) {
        return first(bot, House::isPlanks, SurvivalBrain::isCobbleItem, LevelPathWorld::isThrowaway);
    }

    private static String slabItem(ServerPlayer bot) {
        return first(bot, p -> p.endsWith("_slab"));
    }

    /** Filler for holes: dirt and junk stone first; cobblestone only if there's more than the walls need. */
    private static String fillItem(ServerPlayer bot) {
        String s = first(bot, p -> p.equals("dirt") || p.equals("coarse_dirt"),
                p -> LevelPathWorld.isThrowaway(p) && !p.equals("cobblestone"));
        if (s == null && count(bot, SurvivalBrain::isCobbleItem) > BASE_ROW + 8) s = Building.firstItem(bot, SurvivalBrain::isCobbleItem);
        return s;
    }

    // How much of everything a house takes
    private static final int CORNER_LOGS = 4 * WALL_H;                       // 16
    private static final int BASE_ROW = 4 * (SIZE - 2) - 1;                  // 27: bottom row minus corners and the door
    private static final int UPPER_WALLS = 4 * (SIZE - 2) * (WALL_H - 1) - 1; // 83: the rest minus the door's top half
    private static final int SLABS = 4 * (SIZE + 1) + 9;                     // 49: overhang all round + a 3x3 cap
    private static final int CHESTS = 4;                                     // two double chests
    private static final int MAX_WINDOWS = 4;
    /** Inside wall torches, the door ones and a few spare for dark corners. */
    static final int TORCHES = 16;

    /** Planks still to find, counting everything that's crafted from planks. Server thread. */
    private static int planksNeeded(ServerPlayer bot, int windows) {
        int planks = UPPER_WALLS - windows + ROOF_BLOCKS;
        planks += Math.max(0, BASE_ROW - count(bot, SurvivalBrain::isCobbleItem)); // planks stand in for cobblestone
        planks += (Math.max(0, SLABS - count(bot, p -> p.endsWith("_slab"))) + 5) / 6 * 3;
        if (Building.firstItem(bot, House::isDoor) == null) planks += 6;
        planks += 8 * Math.max(0, CHESTS - count(bot, "chest"::equals));
        if (Building.firstItem(bot, "crafting_table"::equals) == null) planks += 4;
        if (Building.firstItem(bot, House::isBed) == null) planks += 3;
        return planks;
    }

    /**
     * Before gathering anything: what's already in the chests comes out first (door, chests,
     * table, furnace, bed, wool, slabs, glass, cobblestone, planks, logs, torches).
     */
    private static void takeFromChests(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, int windows)
            throws InterruptedException {
        boolean any = onServer(server, () -> !Storage.allChests(bot.level()).isEmpty(), false);
        if (!any) return;
        int[] need = onServer(server, () -> new int[]{
                Building.firstItem(bot, House::isDoor) == null ? 1 : 0,
                Math.max(0, CHESTS - count(bot, "chest"::equals)),
                Building.firstItem(bot, "crafting_table"::equals) == null ? 1 : 0,
                Building.firstItem(bot, "furnace"::equals) == null ? 1 : 0,
                Building.firstItem(bot, House::isBed) == null ? 1 : 0,
                Math.max(0, SLABS - count(bot, p -> p.endsWith("_slab"))),
                Math.max(0, MAX_WINDOWS - count(bot, "glass"::equals)),
                Math.max(0, BASE_ROW + 8 - count(bot, SurvivalBrain::isCobbleItem)),
                Math.max(0, planksNeeded(bot, windows) - count(bot, House::isPlanks)),
                Math.max(0, TORCHES - count(bot, "torch"::equals))}, null);
        if (need == null) return;
        int got = 0;
        got += Storage.withdraw(server, bot, b, House::isDoor, need[0], null);
        got += Storage.withdraw(server, bot, b, "chest"::equals, need[1], null);
        got += Storage.withdraw(server, bot, b, "crafting_table"::equals, need[2], null);
        got += Storage.withdraw(server, bot, b, "furnace"::equals, need[3], null);
        if (need[4] > 0) {
            int bed = Storage.withdraw(server, bot, b, House::isBed, 1, null);
            got += bed;
            if (bed == 0) got += Storage.withdraw(server, bot, b, House::isWool, 3, null);
        }
        got += Storage.withdraw(server, bot, b, p -> p.endsWith("_slab"), need[5], null);
        got += Storage.withdraw(server, bot, b, "glass"::equals, need[6], null);
        got += Storage.withdraw(server, bot, b, SurvivalBrain::isCobbleItem, need[7], null);
        int planks = Storage.withdraw(server, bot, b, House::isPlanks, need[8], null);
        got += planks;
        int logsShort = onServer(server, () -> CORNER_LOGS - count(bot, House::isLog), 0)
                + (Math.max(0, need[8] - planks) + 3) / 4;
        got += Storage.withdraw(server, bot, b, House::isLog, logsShort, null);
        got += Storage.withdraw(server, bot, b, "torch"::equals, need[9], null);
        if (got > 0) HumanChat.say(server, b.name, HumanChat.pick("grabbed " + got + " blocks and stuff from our chests for the house",
                "took what we had in the chests, " + got + " things"));
    }

    private static boolean gather(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, int windows)
            throws InterruptedException {
        takeFromChests(server, bot, b, windows);
        // Work out all the wood the house needs and get it in one go, instead of a trip per item.
        for (int round = 0; round < 4 && SurvivalBrain.jobAlive(b); round++) {
            int logsShort = onServer(server, () -> {
                int planksShort = Math.max(0, planksNeeded(bot, windows) - count(bot, House::isPlanks));
                return CORNER_LOGS + (planksShort + 3) / 4 - count(bot, House::isLog);
            }, 0);
            if (logsShort <= 0) break;
            int logs = logsShort + 1;
            HumanChat.say(server, b.name, round == 0
                    ? HumanChat.pick("need about " + logs + " more logs for the house, gonna chop some trees",
                            "gotta get wood for the house first (" + logs + " logs or so)")
                    : "still a bit short on wood, chopping a few more");
            onServer(server, () -> { Storage.makeRoom(bot, 6); return null; }, null);
            MiningSkills.Target wood = MiningSkills.resolve("wood");
            if (wood == null) return false;
            int before = onServer(server, () -> count(bot, SurvivalBrain::isLogItem), 0);
            MiningSkills.collect(server, bot, b, wood, logs, true);
            int after = onServer(server, () -> count(bot, SurvivalBrain::isLogItem), 0);
            if (after <= before) {
                HumanChat.say(server, b.name, "can't find any trees around here for the house");
                return false;
            }
        }
        if (!SurvivalBrain.jobAlive(b)) return false;
        // Crafting needs free slots: toss junk (flowers, seeds, diorite...) if the pockets are full.
        onServer(server, () -> { Storage.makeRoom(bot, 6); return null; }, null);
        if (onServer(server, () -> Building.firstItem(bot, House::isDoor) == null, true)) {
            Gathering.Craftable c = Gathering.craftable("door");
            if (c == null || !Gathering.makeSure(server, bot, b, c, 1)) return false;
        }
        Gathering.Craftable chest = Gathering.craftable("chest");
        if (chest == null || !Gathering.makeSure(server, bot, b, chest, CHESTS)) return false;
        if (onServer(server, () -> Building.firstItem(bot, "crafting_table"::equals) == null, true)) {
            Gathering.Craftable c = Gathering.craftable("crafting table");
            if (c == null || !Gathering.makeSure(server, bot, b, c, 1)) return false;
        }
        int planks = UPPER_WALLS - windows + ROOF_BLOCKS
                + Math.max(0, BASE_ROW - onServer(server, () -> count(bot, SurvivalBrain::isCobbleItem), 0));
        int slabPlanks = (Math.max(0, SLABS - onServer(server, () -> count(bot, p -> p.endsWith("_slab")), 0)) + 5) / 6 * 3;
        Gathering.Craftable pl = Gathering.craftable("planks");
        if (pl == null || !Gathering.makeSure(server, bot, b, pl, planks + slabPlanks + 3)) return false;
        if (slabPlanks > 0) {
            Gathering.Craftable slab = Gathering.craftable("slabs");
            // slabs are trim: without them the house still gets built
            if (slab != null) Gathering.makeSure(server, bot, b, slab, SLABS);
        }
        // the furnace and the bed are nice to have: the house goes up without them
        if (onServer(server, () -> Building.firstItem(bot, "furnace"::equals) == null, true)) {
            Gathering.Craftable f = Gathering.craftable("furnace");
            if (f != null) Gathering.makeSure(server, bot, b, f, 1);
        }
        ensureBed(server, bot, b);
        // a lit house: no mobs spawning inside (torches aren't worth failing the house over)
        Lighting.ensureTorches(server, bot, b, TORCHES, 10, true);
        return SurvivalBrain.jobAlive(b);
    }

    // ------------------------------------------------------------------------
    // A bed: one it has, one from the chest, or 3 wool (from sheep) + 3 planks
    // ------------------------------------------------------------------------

    private static boolean ensureBed(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        if (onServer(server, () -> Building.firstItem(bot, House::isBed) != null, false)) return true;
        if (Storage.withdraw(server, bot, b, House::isBed, 1, "bed") > 0) return true;
        int wool = onServer(server, () -> count(bot, House::isWool), 0);
        if (wool < 3) wool += Storage.withdraw(server, bot, b, House::isWool, 3 - wool, "wool");
        for (int tries = 0; wool < 3 && tries < 6 && SurvivalBrain.jobAlive(b); tries++) {
            if (tries == 0) SurvivalBrain.maybeSay(server, b, HumanChat.pick("need wool for a bed, looking for sheep",
                    "gonna get some wool for a bed"), 1.0);
            LivingEntity sheep = onServer(server, () -> nearestSheep(bot), null);
            if (sheep == null) {
                HumanChat.say(server, b.name, HumanChat.pick("no sheep around, the bed will have to wait",
                        "couldn't find sheep for a bed, i'll make one later"));
                return false;
            }
            onServer(server, () -> { PvpController.attack(bot, sheep, true); return null; }, null);
            for (int i = 0; i < 80 && PvpController.isFighting(bot.getUUID()); i++) SurvivalBrain.sleep(500);
            SurvivalBrain.pickUpNearbyItems(server, bot, 6);
            wool = onServer(server, () -> count(bot, House::isWool), 0);
        }
        if (wool < 3) return false;
        // craft it (like the other recipes, straight from the inventory)
        return onServer(server, () -> {
            if (count(bot, House::isPlanks) < 3 || count(bot, House::isWool) < 3) return false;
            String w = SurvivalBrain.take(bot, House::isWool, 3);
            SurvivalBrain.take(bot, House::isPlanks, 3);
            String bed = w == null ? "white_bed" : w.replace("_wool", "_bed");
            SurvivalBrain.give(bot, bed, 1);
            Motions.swingArm(bot);
            return Building.firstItem(bot, House::isBed) != null;
        }, false);
    }

    /** A grown, unsheared, unnamed, unleashed sheep close enough to chase (a pet or a pen sheep is left alone). */
    private static LivingEntity nearestSheep(ServerPlayer bot) {
        LivingEntity best = null;
        double bd = 22 * 22; // the fight gives up beyond 24 blocks
        for (Entity e : bot.level().getEntities(bot, bot.getBoundingBox().inflate(22))) {
            if (!(e instanceof LivingEntity le) || !le.isAlive()) continue;
            if (!e.getClass().getSimpleName().equals("Sheep")) continue;
            if (le.isBaby() || le.hasCustomName() || flag(e, "isSheared") || flag(e, "isLeashed")) continue;
            double d = e.distanceToSqr(bot);
            if (d < bd) { bd = d; best = le; }
        }
        return best;
    }

    /** Calls a boolean getter by name if the entity has it (sheep's isSheared, isLeashed...). */
    private static boolean flag(Entity e, String method) {
        try {
            Object v = e.getClass().getMethod(method).invoke(e);
            return v instanceof Boolean bv && bv;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------------

    /** Job: build a house near whoever asked (or where the bot is). */
    static void build(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, UUID requester) throws InterruptedException {
        SurvivalBrain.keep(bot, MATERIALS);
        try {
            BlockPos anchor = onServer(server, () -> {
                ServerPlayer p = requester == null ? null : server.getPlayerList().getPlayer(requester);
                if (p == null || p.level() != bot.level()) p = SurvivalBrain.nearestHuman(bot);
                return p != null && p.level() == bot.level() && p.distanceToSqr(bot) < 64 * 64 ? p.blockPosition() : bot.blockPosition();
            }, null);
            if (anchor == null) return;
            int windows = onServer(server, () -> Math.min(MAX_WINDOWS, count(bot, "glass"::equals)), 0);

            if (!gather(server, bot, b, windows)) {
                if (SurvivalBrain.jobAlive(b)) HumanChat.say(server, b.name, "couldn't get enough stuff for the house, sorry");
                return;
            }

            Site site = onServer(server, () -> findSite(bot, anchor), null);
            if (site == null) {
                // maybe where the bot is now works better
                BlockPos here = onServer(server, bot::blockPosition, null);
                if (here != null) site = onServer(server, () -> findSite(bot, here), null);
            }
            if (site == null) {
                HumanChat.say(server, b.name, "can't find a flat enough spot around here. show me somewhere flatter?");
                return;
            }
            final Site s = site;
            SurvivalBrain.maybeSay(server, b, HumanChat.pick("gonna build it here", "this spot looks good", "ok, building it right here"), 1.0);

            if (!standInMiddle(server, bot, s)) {
                HumanChat.say(server, b.name, "can't get to the spot i picked, hm");
                return;
            }
            if (!level(server, bot, b, s)) {
                if (SurvivalBrain.jobAlive(b)) HumanChat.say(server, b.name, "couldn't clear the ground there, sorry");
                return;
            }
            SurvivalBrain.pickUpNearbyItems(server, bot, 6);
            if (!standInMiddle(server, bot, s)) return;

            // (glass may have come out of a chest since)
            int glassNow = onServer(server, () -> Math.min(MAX_WINDOWS, count(bot, "glass"::equals)), windows);
            int missing = walls(server, bot, b, s, glassNow);
            missing += roof(server, bot, b, s);
            if (!SurvivalBrain.jobAlive(b)) return;
            furnish(server, bot, b, s);

            onServer(server, () -> { Home.set(bot, s); return null; }, null);
            if (missing > 0) {
                HumanChat.say(server, b.name, "house is mostly done, ran out of blocks for " + missing + " spots");
            } else {
                HumanChat.say(server, b.name, HumanChat.pick("done! house is ready", "house is done, come check it out",
                        "ok the house is built. chests, furnace, crafting table and a bed inside"));
            }
            if (HumanConfig.get().autoStore) Storage.storeAll(server, bot, b, false);
        } finally {
            SurvivalBrain.keep(bot, null);
        }
    }

    private static boolean standInMiddle(MinecraftServer server, ServerPlayer bot, Site s) throws InterruptedException {
        BlockPos mid = s.middle();
        for (int attempt = 0; attempt < 3; attempt++) {
            SurvivalBrain.waitWhileFighting(bot);
            boolean there = onServer(server, () -> {
                BlockPos f = BotPathing.feet(bot);
                return f.getX() == mid.getX() && f.getZ() == mid.getZ() && Math.abs(f.getY() - mid.getY()) <= 1;
            }, false);
            if (there) return true;
            // before the walls go up it may dig/pillar to get there; after, it walks (never through its own walls)
            boolean walled = onServer(server, () -> !Building.isFree(bot.level(), s.at(0, 1, 0)), false);
            BotPathing.Options o = walled ? BotPathing.Options.walkOnly() : BotPathing.Options.full();
            o.timeoutTicks = 20 * 90;
            BotPathing.goToBlocking(bot, ActionPathfinder.near(mid.getX(), mid.getY(), mid.getZ(), 0.5), o, 95_000L);
        }
        return onServer(server, () -> {
            BlockPos f = BotPathing.feet(bot);
            return f.getX() == mid.getX() && f.getZ() == mid.getZ();
        }, false);
    }

    private static final double REACH = 4.4;

    /**
     * The house is too big to build from one spot: walks to the closest free cell inside the
     * house from which {@code target} is within arm's reach (a player's 4.5 blocks). Walking
     * only, it never digs through its own walls. Job thread.
     */
    private static void comeWithinReach(MinecraftServer server, ServerPlayer bot, Site s, BlockPos target, BlockPos... avoid)
            throws InterruptedException {
        SurvivalBrain.waitWhileFighting(bot);
        BlockPos stand = onServer(server, () -> {
            Vec3 t = Vec3.atCenterOf(target);
            BlockPos feet = BotPathing.feet(bot);
            boolean inTheWay = feet.equals(target) || feet.above().equals(target);
            if (!inTheWay && bot.getEyePosition().distanceTo(t) <= REACH) return null; // already fine
            ServerLevel level = bot.level();
            BlockPos best = null;
            double bestScore = Double.MAX_VALUE;
            for (int lx = 1; lx <= s.size() - 2; lx++) {
                for (int lz = 1; lz <= s.size() - 2; lz++) {
                    BlockPos p = s.at(lx, 1, lz);
                    if (p.equals(target) || p.above().equals(target)) continue;
                    boolean skip = false;
                    for (BlockPos a : avoid) if (a.equals(p)) skip = true;
                    if (skip) continue;
                    if (!Building.isFree(level, p) || !Building.isFree(level, p.above()) || !Building.isSolid(level, p.below())) continue;
                    double reach = new Vec3(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5).distanceTo(t);
                    if (reach > REACH) continue;
                    double score = reach + 0.3 * Math.sqrt(p.distSqr(bot.blockPosition()));
                    if (score < bestScore) { bestScore = score; best = p; }
                }
            }
            return best;
        }, null);
        if (stand == null) return; // in reach, or nowhere better: place from here
        BotPathing.Options o = BotPathing.Options.walkOnly();
        o.timeoutTicks = 20 * 15;
        BotPathing.goToBlocking(bot, ActionPathfinder.near(stand.getX(), stand.getY(), stand.getZ(), 0.5), o, 17_000L);
    }

    /** Digs a block of the site, walking closer first if it's out of reach from where it stands. */
    private static boolean clear(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Site s, BlockPos p) throws InterruptedException {
        comeWithinReach(server, bot, s, p);
        if (MiningSkills.dig(server, bot, b, p, false, 0)) return true;
        if (onServer(server, () -> Building.isFree(bot.level(), p), false)) return true;
        if (!MiningSkills.reach(server, bot, b, p, false)) return false;
        return MiningSkills.dig(server, bot, b, p, false, 0) || onServer(server, () -> Building.isFree(bot.level(), p), false);
    }

    /** Digs out what's in the house volume and fills holes in the floor. */
    private static boolean level(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Site s) throws InterruptedException {
        // clear from the top down, so sand and gravel don't fall into the gap
        for (int dy = s.wallHeight() + 1; dy >= 1; dy--) {
            for (int dx = 0; dx < s.size(); dx++) {
                for (int dz = 0; dz < s.size(); dz++) {
                    if (!SurvivalBrain.jobAlive(b)) return false;
                    BlockPos p = new BlockPos(s.x0() + dx, s.y() + dy, s.z0() + dz);
                    boolean blocked = onServer(server, () -> !Building.isFree(bot.level(), p), false);
                    if (!blocked) continue;
                    if (!clear(server, bot, b, s, p)) return false;
                }
            }
        }
        standInMiddle(server, bot, s);
        // fill holes in the floor, bottom up
        for (int dx = 0; dx < s.size(); dx++) {
            for (int dz = 0; dz < s.size(); dz++) {
                for (int dy = -2; dy <= 0; dy++) {
                    if (!SurvivalBrain.jobAlive(b)) return false;
                    BlockPos p = new BlockPos(s.x0() + dx, s.y() + dy, s.z0() + dz);
                    final int fdy = dy;
                    // only holes open to the top (a cavity under a solid floor is left alone)
                    boolean hole = onServer(server, () -> {
                        for (int k = fdy; k <= 0; k++) if (!Building.isFree(bot.level(), p.offset(0, k - fdy, 0))) return false;
                        return true;
                    }, false);
                    if (!hole) continue;
                    comeWithinReach(server, bot, s, p);
                    int r = onServer(server, () -> {
                        if (!Building.isFree(bot.level(), p)) return 0;
                        return Building.placeAt(bot, p, fillItem(bot)) ? 1 : -1;
                    }, -1);
                    if (r < 0 && dy == 0) return false; // a hole in the floor we can't fill
                    if (r > 0) SurvivalBrain.sleep(120);
                }
            }
        }
        return true;
    }

    /** Local coordinates of the wall cells, in a sensible building order. */
    private static List<int[]> wallCells(int size) {
        List<int[]> out = new ArrayList<>();
        for (int lx = 0; lx < size; lx++) out.add(new int[]{lx, size - 1});      // back wall
        for (int lz = size - 2; lz >= 0; lz--) out.add(new int[]{0, lz});        // left
        for (int lz = size - 2; lz >= 0; lz--) out.add(new int[]{size - 1, lz}); // right
        for (int lx = 1; lx < size - 1; lx++) out.add(new int[]{lx, 0});         // front (door wall)
        return out;
    }

    private static boolean corner(int lx, int lz, int size) {
        return (lx == 0 || lx == size - 1) && (lz == 0 || lz == size - 1);
    }

    /** Windows: two-high in the middle of each side wall (left first). */
    private static boolean window(int lx, int lz, int dy, int windows, int size) {
        if (windows <= 0 || lz != size / 2 || (dy != 2 && dy != 3)) return false;
        int index = (lx == 0 ? 0 : lx == size - 1 ? 2 : -10) + (dy - 2);
        return index >= 0 && index < windows;
    }

    /** Places the walls. Returns how many cells were left empty. */
    private static int walls(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Site s, int windows)
            throws InterruptedException {
        int missing = 0;
        int size = s.size(), doorX = size / 2;
        for (int dy = 1; dy <= s.wallHeight(); dy++) {
            for (int[] c : wallCells(size)) {
                if (!SurvivalBrain.jobAlive(b)) return missing;
                if (SurvivalBrain.waitWhileFighting(bot)) standInMiddle(server, bot, s); // a mob came by
                boolean doorGap = c[0] == doorX && c[1] == 0 && dy <= 2;
                if (doorGap) continue;
                boolean glass = window(c[0], c[1], dy, windows, size);
                boolean isCorner = corner(c[0], c[1], size);
                final int fdy = dy;
                BlockPos p = s.at(c[0], dy, c[1]);
                if (onServer(server, () -> Building.isFree(bot.level(), p), false)) comeWithinReach(server, bot, s, p);
                boolean ok = onServer(server, () -> {
                    if (!Building.isFree(bot.level(), p)) return true;
                    String item = glass ? "glass" : wallItem(bot, isCorner, fdy);
                    if (item == null) return false;
                    return Building.placeAt(bot, p, item);
                }, false);
                if (!ok) missing++;
                SurvivalBrain.sleep(140 + (long) (Math.random() * 130));
            }
        }
        return missing;
    }

    /**
     * The roof: planks (outer ring first, it rests on the walls, then inwards), then a slab
     * overhang all round and a raised slab cap in the middle. Returns empty cells of the plank
     * roof (the trim is optional).
     */
    private static int roof(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Site s) throws InterruptedException {
        int size = s.size(), ry = s.y() + s.wallHeight() + 1;
        for (int pass = 0; pass < 2; pass++) {
            for (int ring = 0; ring <= size / 2; ring++) {
                for (int dx = 0; dx < size; dx++) {
                    for (int dz = 0; dz < size; dz++) {
                        int r = Math.min(Math.min(dx, dz), Math.min(size - 1 - dx, size - 1 - dz));
                        if (r != ring) continue;
                        if (!SurvivalBrain.jobAlive(b)) return 0;
                        if (SurvivalBrain.waitWhileFighting(bot)) standInMiddle(server, bot, s);
                        BlockPos p = new BlockPos(s.x0() + dx, ry, s.z0() + dz);
                        if (onServer(server, () -> Building.isFree(bot.level(), p), false)) comeWithinReach(server, bot, s, p);
                        boolean placed = onServer(server, () -> {
                            if (!Building.isFree(bot.level(), p)) return false;
                            String item = roofItem(bot);
                            return item != null && Building.placeAt(bot, p, item);
                        }, false);
                        if (placed) SurvivalBrain.sleep(120 + (long) (Math.random() * 110));
                    }
                }
            }
        }
        int holes = onServer(server, () -> {
            int n = 0;
            for (int dx = 0; dx < size; dx++) for (int dz = 0; dz < size; dz++) {
                if (Building.isFree(bot.level(), new BlockPos(s.x0() + dx, ry, s.z0() + dz))) n++;
            }
            return n;
        }, 0);

        // Trim: slab overhang one block out all the way round (sides first, then the corners
        // against them), and a 3x3 slab cap on top.
        List<BlockPos> trim = new ArrayList<>();
        for (int d = 0; d < size; d++) {
            trim.add(new BlockPos(s.x0() + d, ry, s.z0() - 1));
            trim.add(new BlockPos(s.x0() + d, ry, s.z0() + size));
            trim.add(new BlockPos(s.x0() - 1, ry, s.z0() + d));
            trim.add(new BlockPos(s.x0() + size, ry, s.z0() + d));
        }
        trim.add(new BlockPos(s.x0() - 1, ry, s.z0() - 1));
        trim.add(new BlockPos(s.x0() + size, ry, s.z0() - 1));
        trim.add(new BlockPos(s.x0() - 1, ry, s.z0() + size));
        trim.add(new BlockPos(s.x0() + size, ry, s.z0() + size));
        int c0 = size / 2 - 1;
        for (int dx = c0; dx <= c0 + 2; dx++) for (int dz = c0; dz <= c0 + 2; dz++) trim.add(new BlockPos(s.x0() + dx, ry + 1, s.z0() + dz));
        for (BlockPos p : trim) {
            if (!SurvivalBrain.jobAlive(b)) break;
            if (SurvivalBrain.waitWhileFighting(bot)) standInMiddle(server, bot, s);
            if (onServer(server, () -> Building.isFree(bot.level(), p) && slabItem(bot) != null, false)) comeWithinReach(server, bot, s, p);
            boolean placed = onServer(server, () -> {
                if (!Building.isFree(bot.level(), p)) return false;
                String item = slabItem(bot);
                return item != null && Building.placeAt(bot, p, item);
            }, false);
            if (placed) SurvivalBrain.sleep(100 + (long) (Math.random() * 90));
        }
        return holes;
    }

    /**
     * Door, then along the back wall two double chests with a furnace between them, a crafting
     * table on the left, a bed on the right, torches inside and either side of the door.
     */
    private static void furnish(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Site s) throws InterruptedException {
        int size = s.size();
        int doorX = size / 2, back = size - 2, right = size - 2;
        // the door: from right behind it, so it faces out
        BlockPos behindDoor = s.at(doorX, 1, 2);
        BotPathing.Options wo = BotPathing.Options.walkOnly();
        wo.timeoutTicks = 20 * 15;
        BotPathing.goToBlocking(bot, ActionPathfinder.near(behindDoor.getX(), behindDoor.getY(), behindDoor.getZ(), 0.5), wo, 17_000L);
        onServer(server, () -> {
            String d = Building.firstItem(bot, House::isDoor);
            // clicking the floor in the gap from inside: the door faces the way the bot looks
            if (d != null) Building.placeAt(bot, s.at(doorX, 1, 0), d);
            return null;
        }, null);
        SurvivalBrain.sleep(300);

        // storage: two double chests against the back wall, both facing the room
        List<BlockPos> chests = new ArrayList<>();
        int[][] chestCells = size >= 7 ? new int[][]{{1, back}, {2, back}, {right - 1, back}, {right, back}}
                : new int[][]{{3, back}};
        for (int[] c : chestCells) {
            BlockPos p = s.at(c[0], 1, c[1]);
            comeWithinReach(server, bot, s, p);
            boolean ok = onServer(server, () -> Building.firstItem(bot, "chest"::equals) != null
                    && Building.placeFacing(bot, p, "chest", s.back())
                    && Storage.isStorageBlock(SurvivalBrain.blockPath(bot.level().getBlockState(p))), false);
            if (ok) chests.add(p);
            SurvivalBrain.sleep(250);
        }
        for (BlockPos p : chests) onServer(server, () -> { Storage.remember(bot.level(), p); return null; }, null);

        BlockPos furnace = s.at(doorX, 1, back);
        BlockPos table = s.at(1, 1, doorX);
        BlockPos bedFoot = s.at(right, 1, doorX - 1);
        BlockPos bedHead = s.at(right, 1, doorX);
        comeWithinReach(server, bot, s, furnace);
        onServer(server, () -> {
            if (Building.firstItem(bot, "furnace"::equals) != null) Building.placeAt(bot, furnace, "furnace");
            return null;
        }, null);
        SurvivalBrain.sleep(300);
        comeWithinReach(server, bot, s, table);
        onServer(server, () -> {
            if (Building.firstItem(bot, "crafting_table"::equals) != null) Building.placeAt(bot, table, "crafting_table");
            return null;
        }, null);
        SurvivalBrain.sleep(300);
        comeWithinReach(server, bot, s, bedFoot, bedHead);
        boolean bed = onServer(server, () -> {
            String item = Building.firstItem(bot, House::isBed);
            // the head goes the way the bot faces: foot by the door side, head towards the back
            return item != null && Building.placeFacing(bot, bedFoot, item, s.back());
        }, false);
        if (!bed && size >= 7) SurvivalBrain.maybeSay(server, b, "no bed yet, gonna need some wool for that", 0.8);
        SurvivalBrain.sleep(300);

        // torches: inside by the door, and outside either side of it
        BlockPos[] torches = size >= 7
                ? new BlockPos[]{s.at(1, 1, 1), s.at(right, 1, 1), s.at(doorX - 1, 2, -1), s.at(doorX + 1, 2, -1)}
                : new BlockPos[]{s.at(3, 1, 1), s.at(1, 2, -1), s.at(3, 2, -1)};
        for (BlockPos t : torches) {
            comeWithinReach(server, bot, s, t);
            onServer(server, () -> {
                if (Building.firstItem(bot, "torch"::equals) != null && Building.isFree(bot.level(), t)) Building.placeAt(bot, t, "torch");
                return null;
            }, null);
            SurvivalBrain.sleep(250);
        }
        lightUp(server, bot, b, s);
    }

    // ------------------------------------------------------------------------
    // Lighting the inside
    // ------------------------------------------------------------------------

    /** Local cells for wall torches at head height (dy 2), each with the local wall cell it hangs on. */
    static List<int[]> wallTorchSpots(int size) {
        List<int[]> out = new ArrayList<>();
        if (size < 7) return out;
        int in = size - 2;               // last inside row/column
        int a = 2, c = size - 3;         // ~4 blocks apart, clear of the windows in the middle of the side walls
        out.add(new int[]{1, a, 0, a});  out.add(new int[]{1, c, 0, c});                       // left wall
        out.add(new int[]{in, a, size - 1, a}); out.add(new int[]{in, c, size - 1, c});         // right wall
        out.add(new int[]{3, in, 3, size - 1}); out.add(new int[]{size - 4, in, size - 4, size - 1}); // back wall
        out.add(new int[]{a, 1, a, 0}); out.add(new int[]{c, 1, c, 0});                       // front wall
        return out;
    }

    /** Every walkable cell inside (feet level). */
    static List<BlockPos> insideCells(Site s) {
        List<BlockPos> out = new ArrayList<>();
        for (int lx = 1; lx <= s.size() - 2; lx++) for (int lz = 1; lz <= s.size() - 2; lz++) out.add(s.at(lx, 1, lz));
        return out;
    }

    /** Anywhere inside darker than a torch-lit room? Server thread. */
    static boolean hasDarkSpot(ServerLevel level, Site s) {
        return Lighting.darkest(level, insideCells(s)) != null;
    }

    /**
     * Torches on the inside walls at head height every ~4 blocks, then checks every floor cell
     * inside has block light 8+ and puts a torch on the floor at the darkest spot until it does.
     * Job thread.
     */
    static void lightUp(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Site s) throws InterruptedException {
        if (s.size() < 5) return;
        if (onServer(server, () -> Lighting.torches(bot), 0) < 4) Lighting.ensureTorches(server, bot, b, 12, 4, false);
        for (int[] t : wallTorchSpots(s.size())) {
            if (!SurvivalBrain.jobAlive(b) && !SurvivalBrain.canContinue(b)) return;
            BlockPos cell = s.at(t[0], 2, t[1]);
            BlockPos wall = s.at(t[2], 2, t[3]);
            if (onServer(server, () -> !Building.isFree(bot.level(), cell) || Lighting.torches(bot) == 0, true)) continue;
            comeWithinReach(server, bot, s, cell);
            onServer(server, () -> Lighting.place(bot, cell, Building.dirTo(cell, wall)), false);
            SurvivalBrain.sleep(200);
        }
        // light spreads a moment after the torch goes up
        for (int pass = 0; pass < 5; pass++) {
            SurvivalBrain.sleep(900);
            BlockPos dark = onServer(server, () -> Lighting.darkest(bot.level(), insideCells(s)), null);
            if (dark == null) return;
            if (onServer(server, () -> Lighting.torches(bot), 0) == 0) {
                SurvivalBrain.maybeSay(server, b, "out of torches, the house is still a bit dark", 0.8);
                return;
            }
            comeWithinReach(server, bot, s, dark);
            boolean ok = onServer(server, () -> Lighting.place(bot, dark, Direction.DOWN), false);
            if (!ok) return;
        }
    }
}
