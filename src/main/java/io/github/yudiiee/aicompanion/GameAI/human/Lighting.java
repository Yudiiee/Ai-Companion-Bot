package io.github.yudiiee.aicompanion.GameAI.human;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.onServer;

/**
 * Torches: keeping some on hand (pockets, then the chests, then crafting them from coal and
 * sticks, digging a bit of coal if needed) and putting them up like a player does: on the
 * wall at head height, every few blocks in tunnels, and wherever it's still dark enough for
 * mobs.
 */
final class Lighting {

    private Lighting() {}

    /** Block light a spot needs to count as lit (spec: 8+). */
    static final int MIN_LIGHT = 8;

    static boolean isTorchId(String p) {
        return p.equals("torch") || p.equals("wall_torch") || p.equals("lantern") || p.equals("soul_torch")
                || p.equals("soul_wall_torch") || p.equals("soul_lantern");
    }

    static int torches(ServerPlayer bot) {
        return Gathering.countOf(bot, "torch"::equals);
    }

    /** Block light at {@code p} (torches etc., not the sun). 15 if it can't be read, so nothing gets spammed. */
    static int blockLight(ServerLevel level, BlockPos p) {
        try {
            return level.getBrightness(LightLayer.BLOCK, p);
        } catch (Throwable t) {
            return 15;
        }
    }

    /** Crafts torches from coal/charcoal and sticks (or planks/logs for sticks) already in the pockets. Server thread. */
    static int craftFromPockets(ServerPlayer bot, int want) {
        int have = torches(bot);
        if (have >= want || Gathering.countOf(bot, "coal") == 0) return have;
        Gathering.Craftable c = Gathering.craftable("torches");
        if (c != null) Gathering.craftTowards(bot, c, want);
        return torches(bot);
    }

    /**
     * Gets the torch count up to {@code want}: pockets, chests, crafting (coal from the
     * chests too). If there are still fewer than {@code minimum} and {@code dig} is set, digs
     * some coal ore nearby (no strip-mining trip for it). Returns how many it has. Job thread.
     */
    static int ensureTorches(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, int want, int minimum, boolean dig)
            throws InterruptedException {
        int have = onServer(server, () -> craftFromPockets(bot, want), 0);
        if (have >= want) return have;
        have += Storage.withdraw(server, bot, b, "torch"::equals, want - have, null);
        if (have >= want) return have;
        int coal = (want - have + 3) / 4;
        int coalHave = onServer(server, () -> Gathering.countOf(bot, "coal"), 0);
        if (coalHave < coal) Storage.withdraw(server, bot, b, p -> p.equals("coal") || p.equals("charcoal"), coal - coalHave, null);
        if (onServer(server, () -> Gathering.countOf(bot, "stick") + Gathering.countOf(bot, "planks") * 2
                + Gathering.countOf(bot, "log") * 8, 0) < coal) {
            Storage.withdraw(server, bot, b, p -> p.endsWith("_planks"), 4, null);
        }
        have = onServer(server, () -> craftFromPockets(bot, want), have);
        if (have >= minimum || !dig || !SurvivalBrain.canContinue(b)) return have;
        // coal lying in the open nearby only: no tunnelling after buried ore just for torches
        int dug = 0;
        for (int tries = 0; tries < Math.max(2, coal) + 4 && dug < Math.max(2, coal) && SurvivalBrain.canContinue(b); tries++) {
            BlockPos ore = onServer(server, () -> exposedCoal(bot, b, 16), null);
            if (ore == null) break;
            if (tries == 0) SurvivalBrain.maybeSay(server, b, HumanChat.pick("low on torches, grabbing that coal", "need coal for torches"), 0.7);
            if (MiningSkills.reach(server, bot, b, ore, false) && MiningSkills.dig(server, bot, b, ore, false, 0)) {
                dug++;
                SurvivalBrain.pickUpNearbyItems(server, bot, 4, false);
            } else {
                b.blacklist.put(ore, System.currentTimeMillis() + 5 * 60_000L);
            }
        }
        return onServer(server, () -> craftFromPockets(bot, want), have);
    }

    /** Nearest coal ore within {@code r} that's open to the air. Server thread. */
    private static BlockPos exposedCoal(ServerPlayer bot, SurvivalBrain.Brain b, int r) {
        ServerLevel level = bot.level();
        BlockPos f = BotPathing.feet(bot);
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -8; dy <= 8; dy++) {
            double d = dx * dx + dz * dz + dy * dy;
            if (d >= bd) continue;
            BlockPos p = f.offset(dx, dy, dz);
            String path = SurvivalBrain.blockPath(level.getBlockState(p));
            if (!path.equals("coal_ore") && !path.equals("deepslate_coal_ore")) continue;
            if (!SurvivalBrain.exposed(level, p) || SurvivalBrain.blacklisted(b, p) || MiningSkills.fluidNear(level, p) == 2) continue;
            best = p;
            bd = d;
        }
        return best;
    }

    /** A torch into {@code cell}, stuck to the block on side {@code support}. Server thread. */
    static boolean place(ServerPlayer bot, BlockPos cell, Direction support) {
        ServerLevel level = bot.level();
        if (isTorchId(SurvivalBrain.blockPath(level.getBlockState(cell)))) return true;
        if (!Building.isFree(level, cell) || !level.getFluidState(cell).isEmpty()) return false;
        if (torches(bot) == 0 && craftFromPockets(bot, 4) == 0) return false;
        boolean ok = Building.placeAgainst(bot, cell, "torch", support);
        if (ok) MiningSkills.ownBlock(cell); // a tunnel may dig through its own torch later
        return ok;
    }

    /**
     * In a 1-wide tunnel: a torch on one of the side walls at head height of {@code feet}
     * (sides tried in order), else on the floor. Server thread.
     */
    static boolean tunnelTorch(ServerPlayer bot, BlockPos feet, Direction... sides) {
        BlockPos head = feet.above();
        for (Direction d : sides) {
            if (place(bot, head, d)) return true;
        }
        for (Direction d : sides) {
            if (place(bot, feet, d)) return true;
        }
        return place(bot, feet, Direction.DOWN);
    }

    /**
     * Broke into a cave next to {@code at}: light up the dark spots near the opening (up to
     * two torches on the cave floor within reach, none straight ahead along {@code dir}). Server thread.
     */
    static int lightOpening(ServerPlayer bot, BlockPos at, int[] dir) {
        ServerLevel level = bot.level();
        Vec3 eye = bot.getEyePosition();
        List<BlockPos> dark = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -2; dy <= 1; dy++) {
            BlockPos p = at.offset(dx, dy, dz);
            // not in the tunnel's way ahead (it would stop the next step)
            if (dir != null && dx * dir[1] - dz * dir[0] == 0 && dx * dir[0] + dz * dir[1] > 0) continue;
            if (!Building.isFree(level, p) || !level.getFluidState(p).isEmpty()) continue;
            if (!Building.isSolid(level, p.below())) continue;
            if (eye.distanceTo(Vec3.atCenterOf(p)) > 4.4) continue;
            if (blockLight(level, p) >= MIN_LIGHT) continue;
            dark.add(p);
        }
        dark.sort((a, c) -> Integer.compare(blockLight(level, a), blockLight(level, c)));
        int placed = 0;
        List<BlockPos> lit = new ArrayList<>();
        for (BlockPos p : dark) {
            if (placed >= 2) break;
            boolean close = false;
            for (BlockPos l : lit) if (l.distSqr(p) < 9) close = true;
            if (close) continue;
            if (place(bot, p, Direction.DOWN)) { placed++; lit.add(p); }
        }
        return placed;
    }

    /** The darkest free, floored cell in {@code cells} under the minimum light, or null. Server thread. */
    static BlockPos darkest(ServerLevel level, List<BlockPos> cells) {
        BlockPos worst = null;
        int wl = MIN_LIGHT;
        for (BlockPos p : cells) {
            if (!Building.isFree(level, p) || !level.getFluidState(p).isEmpty()) continue;
            if (!Building.isSolid(level, p.below())) continue;
            if (isTorchId(SurvivalBrain.blockPath(level.getBlockState(p)))) continue;
            int l = blockLight(level, p);
            if (l < wl) { wl = l; worst = p; }
        }
        return worst;
    }
}
