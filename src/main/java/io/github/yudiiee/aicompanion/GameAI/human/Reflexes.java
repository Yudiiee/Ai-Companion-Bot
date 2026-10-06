package io.github.yudiiee.aicompanion.GameAI.human;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;
import io.github.yudiiee.aicompanion.PlayerUtils.MiningTool;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Top-priority reflexes, checked every tick before anything else gets a say (like a player
 * who drops everything when they fall in lava): get out of lava, put fire out in water,
 * dig out when a block (falling gravel, sand) ends up in the head. Server thread.
 */
final class Reflexes {

    private Reflexes() {}

    private static final class State {
        boolean escaping;
        long ticks;
        long nextFire;
        long nextDigOut;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    static void forget(UUID id) { STATES.remove(id); }

    /** Server thread, every tick for each bot. True if a reflex has the controls this tick. */
    static boolean tick(ServerPlayer bot) {
        if (bot.gameMode.isCreative() || bot.isSpectator() || !bot.isAlive()) return false;
        if (!(bot instanceof ServerPlayerInterface spi)) return false;
        State st = STATES.computeIfAbsent(bot.getUUID(), k -> new State());
        long tick = ++st.ticks;
        EntityPlayerActionPack ap = spi.getActionPack();
        ServerLevel level = bot.level();
        BlockPos feet = BotPathing.feet(bot);
        try {
            // 1. lava: straight out, towards the nearest solid ground, jumping
            boolean lava = level.getFluidState(feet).is(FluidTags.LAVA) || level.getFluidState(feet.above()).is(FluidTags.LAVA);
            if (lava) {
                if (!st.escaping) {
                    st.escaping = true;
                    MinecraftServer server = level.getServer();
                    BotPathing.cancel(bot);
                    MiningTool.cancelFor(server, bot.getUUID(), "In lava");
                }
                PvpController.flee(bot.getUUID(), 2_000L); // jobs and fights wait
                BlockPos safe = safeGround(level, feet, 6);
                if (safe != null) {
                    Vec3 c = Vec3.atBottomCenterOf(safe);
                    ap.look((float) Math.toDegrees(Math.atan2(-(c.x - bot.getX()), c.z - bot.getZ())), 0f);
                }
                ap.setSneaking(false);
                ap.setStrafing(0f);
                ap.setForward(1f);
                ap.setSprinting(true);
                ap.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
                return true;
            }
            if (st.escaping) { // out: let go of the keys
                st.escaping = false;
                ap.setForward(0f);
                ap.setSprinting(false);
            }

            // 2. on fire: into water if there's some close by
            if (bot.isOnFire() && !bot.isInWater() && tick >= st.nextFire) {
                st.nextFire = tick + 40;
                BlockPos water = nearestWater(level, feet, 8);
                if (water != null) {
                    BotPathing.Options o = BotPathing.Options.walkOnly();
                    o.fight = true; // don't wait for anything, it's urgent
                    o.timeoutTicks = 20 * 6;
                    BotPathing.goTo(bot, ActionPathfinder.near(water.getX(), water.getY(), water.getZ(), 0.6), o);
                } else if (SurvivalBrain.blockPath(level.getBlockState(feet)).contains("fire")) {
                    BlockPos out = safeGround(level, feet, 3);
                    if (out != null) {
                        Vec3 c = Vec3.atBottomCenterOf(out);
                        ap.look((float) Math.toDegrees(Math.atan2(-(c.x - bot.getX()), c.z - bot.getZ())), 0f);
                        ap.setForward(1f);
                    }
                }
            }

            // 3. suffocating (gravel fell on it, pushed into a wall): mine the block out of its head
            BlockPos head = BlockPos.containing(bot.getEyePosition());
            if (Building.isSolid(level, head) && tick >= st.nextDigOut && !MiningTool.isMining(bot.getUUID())) {
                st.nextDigOut = tick + 20;
                String path = SurvivalBrain.blockPath(level.getBlockState(head));
                if (!path.contains("bedrock") && !Protection.isManMade(path)) {
                    MiningTool.mineBlock(bot, head);
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** Closest cell to stand in: free, headroom, solid floor, no lava/fire in or under it. */
    private static BlockPos safeGround(ServerLevel level, BlockPos from, int r) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -1; dy <= 2; dy++) {
            BlockPos p = from.offset(dx, dy, dz);
            double d = dx * dx + dz * dz + dy * dy * 0.5;
            if (d >= bd || (dx == 0 && dz == 0)) continue;
            if (!Building.isFree(level, p) || !Building.isFree(level, p.above())) continue;
            if (!level.getFluidState(p).isEmpty() || !level.getFluidState(p.above()).isEmpty()) continue;
            if (!Building.isSolid(level, p.below()) || level.getFluidState(p.below()).is(FluidTags.LAVA)) continue;
            String floor = SurvivalBrain.blockPath(level.getBlockState(p.below()));
            if (floor.contains("magma") || SurvivalBrain.blockPath(level.getBlockState(p)).contains("fire")) continue;
            best = p;
            bd = d;
        }
        return best;
    }

    private static BlockPos nearestWater(ServerLevel level, BlockPos from, int r) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -2; dy <= 1; dy++) {
            BlockPos p = from.offset(dx, dy, dz);
            double d = dx * dx + dz * dz + dy * dy;
            if (d >= bd || !level.getFluidState(p).is(FluidTags.WATER)) continue;
            best = p;
            bd = d;
        }
        return best;
    }
}
