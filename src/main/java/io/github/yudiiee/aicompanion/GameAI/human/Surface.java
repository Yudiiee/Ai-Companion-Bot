package io.github.yudiiee.aicompanion.GameAI.human;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

import static io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.onServer;

/**
 * Getting back up from deep underground the way a player does: dig a staircase up towards
 * where the others are, and when the staircase runs into a cave, water or lava, let the
 * pathfinder find a way up (walking, pillaring, swimming) and carry on digging from there.
 *
 * <p>Without this the bot would try to walk to people on the surface through solid rock,
 * fail, and wander around the caves.
 */
public final class Surface {

    private Surface() {}

    /** Blocks overhead that make it "underground" (leaves and logs don't count: that's a forest). */
    private static final int ROOF = 3;

    /** Is there rock over its head? Server thread. */
    static boolean underground(ServerLevel level, BlockPos feet) {
        int solid = 0;
        for (int y = feet.getY() + 2; y < feet.getY() + 400; y++) {
            BlockPos p = new BlockPos(feet.getX(), y, feet.getZ());
            if (level.isOutsideBuildHeight(p)) break;
            BlockState s = level.getBlockState(p);
            if (s.isAir() || s.canBeReplaced()) continue;
            String path = SurvivalBrain.blockPath(s);
            if (path.endsWith("_leaves") || SurvivalBrain.isLog(s)) continue;
            if (s.getCollisionShape(level, p).isEmpty()) continue;
            if (++solid >= ROOF) return true;
        }
        return false;
    }

    /** Following someone who is well above while it's in a mine: it needs to dig/pillar, not just walk. */
    public static boolean needsToDigUp(ServerPlayer bot, ServerPlayer target) {
        if (bot == null || target == null || bot.level() != target.level()) return false;
        BlockPos f = BotPathing.feet(bot);
        return target.getY() > f.getY() + 8 && underground(bot.level(), f);
    }

    private record Where(BlockPos feet, boolean underground, BlockPos human) {}

    private static Where where(MinecraftServer server, ServerPlayer bot, UUID towards) {
        return where(server, bot, towards, null);
    }

    /** {@code fixed}: a place to head for instead of a person (home). */
    private static Where where(MinecraftServer server, ServerPlayer bot, UUID towards, BlockPos fixed) {
        return onServer(server, () -> {
            BlockPos f = BotPathing.feet(bot);
            if (fixed != null) return new Where(f, underground(bot.level(), f), fixed);
            ServerPlayer h = towards == null ? null : server.getPlayerList().getPlayer(towards);
            if (h == null || h.level() != bot.level()) h = SurvivalBrain.nearestHuman(bot);
            BlockPos hp = h != null && h.level() == bot.level() ? h.blockPosition() : null;
            return new Where(f, underground(bot.level(), f), hp);
        }, null);
    }

    /**
     * Should it head up before going to the others? Underground, and the person it's going
     * to is well above it (or there's nobody around to go to).
     */
    static boolean shouldClimb(MinecraftServer server, ServerPlayer bot, UUID towards) {
        Where w = where(server, bot, towards);
        if (w == null || !w.underground()) return false;
        if (w.human() == null) return true;
        return w.human().getY() > w.feet().getY() + 8;
    }

    /**
     * Back up to the surface (or to the level of the person it's going to) if it's deep
     * underground and they aren't down there with it. True if it went up.
     */
    static boolean backUp(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, UUID towards)
            throws InterruptedException {
        if (!shouldClimb(server, bot, towards)) return false;
        climb(server, bot, b, towards);
        return true;
    }

    /** A goal the pathfinder is happy with once it's at least at {@code y}. */
    private static ActionPathfinder.Goal upTo(int y) {
        return new ActionPathfinder.Goal() {
            public boolean reached(int x, int yy, int z) { return yy >= y; }
            public double heuristic(int x, int yy, int z) {
                return Math.max(0, y - yy) * (ActionPathfinder.WALK + ActionPathfinder.JUMP) * 0.5;
            }
            public String toString() { return "up to y " + y; }
        };
    }

    /** Staircase direction: towards the person, else the way it's facing. */
    private static int[] heading(MinecraftServer server, ServerPlayer bot, UUID towards, BlockPos fixed) {
        Where w = where(server, bot, towards, fixed);
        return onServer(server, () -> {
            double dx, dz;
            if (w != null && w.human() != null
                    && Math.hypot(w.human().getX() - w.feet().getX(), w.human().getZ() - w.feet().getZ()) > 4) {
                dx = w.human().getX() - w.feet().getX();
                dz = w.human().getZ() - w.feet().getZ();
            } else {
                double yaw = Math.toRadians(bot.getYRot());
                dx = -Math.sin(yaw);
                dz = Math.cos(yaw);
            }
            return Math.abs(dx) > Math.abs(dz) ? new int[]{dx > 0 ? 1 : -1, 0} : new int[]{0, dz > 0 ? 1 : -1};
        }, new int[]{1, 0});
    }

    /** Job step: dig / climb up until there's sky overhead or it's level with the person. */
    static boolean climb(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, UUID towards)
            throws InterruptedException {
        return climb(server, bot, b, towards, null);
    }

    /** Up towards a place (home) rather than a person. */
    static boolean climbToward(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, BlockPos target)
            throws InterruptedException {
        Where w = where(server, bot, null, target);
        if (w == null || !w.underground() || target.getY() <= w.feet().getY() + 8) return false;
        return climb(server, bot, b, null, target);
    }

    private static boolean climb(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, UUID towards, BlockPos fixed)
            throws InterruptedException {
        SurvivalBrain.maybeSay(server, b, HumanChat.pick("heading back up", "ok, going back up to the surface",
                "digging my way back up"), 0.8);
        long end = System.currentTimeMillis() + 8 * 60_000L;
        int[] dir = heading(server, bot, towards, fixed);
        int blocked = 0, pathFails = 0, steps = 0;
        while (SurvivalBrain.canContinue(b) && System.currentTimeMillis() < end) {
            if (!MiningSkills.upkeep(server, bot, b)) return false;
            SurvivalBrain.waitWhileFighting(bot);
            Where w = where(server, bot, towards, fixed);
            if (w == null) return false;
            if (!w.underground()) return true;
            if (w.human() != null && w.feet().getY() >= w.human().getY() - 2) return true; // level with them

            if (MiningSkills.step(server, bot, b, dir[0], dir[1], 1)) {
                blocked = 0;
                if (++steps % 8 == 0) dir = heading(server, bot, towards, fixed); // keep heading their way
                continue;
            }
            if (!SurvivalBrain.canContinue(b)) return false;
            // wall of water/lava, a cave, or the tunnel it's standing in: try another side
            dir = new int[]{-dir[1], dir[0]};
            if (++blocked < 4) continue;

            // no way to stair up from here: let the pathfinder find a way (pillar, walk round, swim)
            blocked = 0;
            int target = w.feet().getY() + 10;
            if (w.human() != null) target = Math.min(target, w.human().getY());
            BotPathing.Options o = BotPathing.Options.full();
            o.timeoutTicks = 20 * 45;
            BotPathing.Result r = BotPathing.goToBlocking(bot, upTo(target), o, 50_000L);
            if (r == BotPathing.Result.CANCELLED && !PvpController.isBusy(bot.getUUID())
                    && !SurvivalBrain.canContinue(b)) return false;
            int nowY = onServer(server, () -> BotPathing.feet(bot).getY(), w.feet().getY());
            if (nowY <= w.feet().getY()) {
                if (++pathFails >= 3) {
                    HumanChat.say(server, b.name, HumanChat.pick("i'm kinda stuck down here, can someone come get me?",
                            "can't find a way up from here, help lol"));
                    return false;
                }
            } else {
                pathFails = 0;
            }
            dir = heading(server, bot, towards, fixed);
        }
        return false;
    }

    /** "come here" from deep underground: climb up first, then walk over. */
    static MiningSkills.Request comeUp(UUID player, String playerName) {
        return new MiningSkills.Request("come up to " + playerName, HumanChat.pick("coming, gotta dig up first",
                "omw, i'm down in the mines, give me a sec", "coming up"), (server, bot, b) -> {
            climb(server, bot, b, player);
            if (!SurvivalBrain.canContinue(b)) return;
            ServerPlayer p = onServer(server, () -> server.getPlayerList().getPlayer(player), null);
            if (p == null) return;
            BlockPos at = onServer(server, p::blockPosition, null);
            if (at == null) return;
            BotPathing.Options o = BotPathing.Options.full();
            o.timeoutTicks = 20 * 120;
            BotPathing.goToBlocking(bot, ActionPathfinder.near(at.getX(), at.getY(), at.getZ(), 2.5), o, 125_000L);
        });
    }
}
