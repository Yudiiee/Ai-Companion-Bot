package io.github.yudiiee.aicompanion.PlayerUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Aiming at blocks the way a player does. Carpet's attack/use actions act on whatever is
 * under the crosshair, so the bot must look at a face of the block it can actually see.
 * Server thread only.
 */
public final class BlockAim {
    private static final int[][] FACES = {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};

    private BlockAim() {}

    /** The point on {@code target} to look at: the nearest visible face centre, or null if none is visible. */
    public static Vec3 visiblePoint(ServerPlayer bot, BlockPos target) {
        Vec3 eye = bot.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(target);
        if (target.equals(hit(bot, eye, centre))) return centre;
        Vec3 best = null;
        double bestD = Double.MAX_VALUE;
        for (int[] f : FACES) {
            Vec3 p = centre.add(f[0] * 0.49, f[1] * 0.49, f[2] * 0.49);
            double d = p.distanceTo(eye);
            if (d >= bestD) continue;
            if (target.equals(hit(bot, eye, p))) { best = p; bestD = d; }
        }
        return best;
    }

    /** First block hit on the way from {@code from} to {@code to}, or null. */
    public static BlockPos hit(ServerPlayer bot, Vec3 from, Vec3 to) {
        try {
            BlockHitResult r = bot.level().clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
            if (r == null || r.getType() == HitResult.Type.MISS) return null;
            return r.getBlockPos();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Turns the head/body to look at {@code point} (no logging, unlike LookController). */
    public static void look(ServerPlayer bot, Vec3 point) {
        Vec3 eye = bot.getEyePosition();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        bot.setYRot(yaw);
        bot.setXRot(pitch);
        bot.setYHeadRot(yaw);
    }

    /** What the crosshair is on right now (within {@code reach}), or null. */
    public static BlockPos crosshair(ServerPlayer bot, double reach) {
        Vec3 eye = bot.getEyePosition();
        Vec3 dir = bot.getViewVector(1.0f);
        return hit(bot, eye, eye.add(dir.scale(reach)));
    }
}
