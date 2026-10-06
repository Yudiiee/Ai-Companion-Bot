package io.github.yudiiee.aicompanion.GameAI.human;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * Putting blocks down like a player: hold the block, look at a face of a solid neighbour,
 * crouch (so clicking a chest or table doesn't open it) and right-click. Server thread.
 */
final class Building {

    private Building() {}

    private static final Direction[] SUPPORT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

    static boolean isFree(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.isAir() || s.canBeReplaced();
    }

    static boolean isSolid(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return !s.isAir() && !s.canBeReplaced() && !s.getCollisionShape(level, p).isEmpty();
    }

    /** First inventory item matching {@code test}, or null. */
    static String firstItem(ServerPlayer bot, Predicate<String> test) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && test.test(SurvivalBrain.itemPath(s))) return SurvivalBrain.itemPath(s);
        }
        return null;
    }

    /**
     * Places the item {@code itemPath} into the empty cell {@code target}, clicking on whichever
     * solid neighbour is available. True if the cell is filled afterwards.
     */
    static boolean placeAt(ServerPlayer bot, BlockPos target, String itemPath) {
        ServerLevel level = bot.level();
        if (!isFree(level, target)) return true; // something's already there
        if (itemPath == null || !Smelting.holdItem(bot, itemPath)) return false;
        for (Direction d : SUPPORT_ORDER) {
            if (clickFace(bot, target, d)) return true;
        }
        return false;
    }

    /**
     * Places {@code itemPath} into {@code target} against the block on side {@code support} only
     * (a torch on a wall: the wall's side; standing on the floor: DOWN). True if it's there after.
     */
    static boolean placeAgainst(ServerPlayer bot, BlockPos target, String itemPath, Direction support) {
        ServerLevel level = bot.level();
        if (!isFree(level, target)) return false;
        if (itemPath == null || !Smelting.holdItem(bot, itemPath)) return false;
        return clickFace(bot, target, support);
    }

    /** Sneak-right-clicks the face of the neighbour on side {@code d} that looks into {@code target}. */
    private static boolean clickFace(ServerPlayer bot, BlockPos target, Direction d) {
        ServerLevel level = bot.level();
        BlockPos n = target.relative(d);
        if (!isSolid(level, n)) return false;
        Direction face = d.getOpposite();
        Vec3 hitAt = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, hitAt);
        bot.setShiftKeyDown(true); // sneak-click: place against chests/tables instead of opening them
        try {
            bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(hitAt, face, n, false));
        } catch (Throwable ignored) {
        } finally {
            bot.setShiftKeyDown(false);
        }
        Motions.swingArm(bot);
        return !isFree(level, target);
    }

    /** The horizontal (or vertical) direction from {@code a} to the neighbouring cell {@code b}. */
    static Direction dirTo(BlockPos a, BlockPos b) {
        int dx = Integer.signum(b.getX() - a.getX()), dy = Integer.signum(b.getY() - a.getY()), dz = Integer.signum(b.getZ() - a.getZ());
        if (dx > 0) return Direction.EAST;
        if (dx < 0) return Direction.WEST;
        if (dz > 0) return Direction.SOUTH;
        if (dz < 0) return Direction.NORTH;
        return dy > 0 ? Direction.UP : Direction.DOWN;
    }

    /** Direction for a unit step (dx, dz). */
    static Direction dirOf(int dx, int dz) {
        if (dx > 0) return Direction.EAST;
        if (dx < 0) return Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * Places on the floor while facing {@code facing} (yaw), without sneaking: chests placed
     * side by side this way join into a double chest, and a bed's head goes that way.
     */
    static boolean placeFacing(ServerPlayer bot, BlockPos target, String itemPath, Direction facing) {
        ServerLevel level = bot.level();
        if (!isFree(level, target)) return true;
        BlockPos n = target.below();
        if (itemPath == null || !isSolid(level, n) || !Smelting.holdItem(bot, itemPath)) return false;
        Vec3 hitAt = Vec3.atCenterOf(n).add(0, 0.5, 0);
        io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, hitAt);
        float yaw = switch (facing) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            default -> -90f; // EAST
        };
        bot.setYRot(yaw);
        bot.setYHeadRot(yaw);
        bot.setShiftKeyDown(false);
        try {
            bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(hitAt, Direction.UP, n, false));
        } catch (Throwable ignored) { }
        Motions.swingArm(bot);
        return !isFree(level, target);
    }

    /** Puts {@code itemPath} down on the ground next to the bot. Returns where, or null. */
    static BlockPos placeNear(ServerPlayer bot, String itemPath) {
        ServerLevel level = bot.level();
        BlockPos feet = BotPathing.feet(bot);
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}, {2, 0}, {0, 2}, {-2, 0}, {0, -2}};
        for (int dy = 0; dy >= -1; dy--) {
            for (int[] o : offsets) {
                BlockPos p = feet.offset(o[0], dy, o[1]);
                if (!isFree(level, p) || !isSolid(level, p.below())) continue;
                if (!isFree(level, p.above())) continue; // room to open a chest / reach it
                if (placeAt(bot, p, itemPath) && !isFree(level, p)) return p;
            }
        }
        return null;
    }
}
