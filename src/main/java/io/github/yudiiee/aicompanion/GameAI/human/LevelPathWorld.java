package io.github.yudiiee.aicompanion.GameAI.human;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;

/**
 * The live world as the pathfinder sees it: which cells can be walked through or stood
 * on, what to stay away from, and how many ticks each block takes to dig with the best
 * tool in the bot's inventory. Results are cached for the duration of one search.
 * Server thread only.
 */
final class LevelPathWorld implements ActionPathfinder.World {

    private static final String[] AVOID_NAMES = {
            "lava", "fire", "campfire", "cactus", "sweet_berry_bush", "wither_rose", "powder_snow",
            "magma_block", "cobweb", "nether_portal", "end_portal", "end_gateway", "pointed_dripstone"
    };
    private static final String[] THROWAWAY = {
            "dirt", "coarse_dirt", "cobblestone", "cobbled_deepslate", "netherrack", "andesite", "diorite",
            "granite", "tuff", "stone", "blackstone", "basalt", "end_stone"
    };

    private final ServerLevel level;
    private final ServerPlayer bot;
    private final Protection.Context protection;
    private final LongMaps.LongInt flags = new LongMaps.LongInt(8192);
    private final LongMaps.LongInt breaks = new LongMaps.LongInt(1024); // ticks * 10, or -10 for "never"
    private final Map<Block, Float> toolSpeed = new HashMap<>();

    LevelPathWorld(ServerPlayer bot) {
        this.bot = bot;
        this.level = bot.level();
        this.protection = Protection.scan(bot, 64);
    }

    @Override
    public int flags(int x, int y, int z) {
        long k = ActionPathfinder.pack(x, y, z);
        int c = flags.get(k, -1);
        if (c != -1) return c;
        int f = compute(new BlockPos(x, y, z));
        flags.put(k, f);
        return f;
    }

    private int compute(BlockPos p) {
        if (p.getY() > 330) return PASS;
        if (!level.isLoaded(p)) return UNLOADED;
        if (level.isOutsideBuildHeight(p)) return p.getY() > 0 ? PASS : 0;
        BlockState s = level.getBlockState(p);
        FluidState fs = level.getFluidState(p);
        boolean lava = fs != null && fs.is(FluidTags.LAVA);
        boolean water = fs != null && fs.is(FluidTags.WATER);
        String path = SurvivalBrain.blockPath(s);
        int f = 0;
        if (lava || isAvoid(path)) f |= AVOID;
        if (MiningSkills.isFalling(path)) f |= FALLS;
        if (s.isAir()) return f | PASS;
        if (BotPathing.isOpenableDoor(path)) return f | PASS; // the bot opens it on the way through
        VoxelShape shape = s.getCollisionShape(level, p);
        if (shape.isEmpty()) {
            f |= PASS;
            if (water) f |= WATER;
            return f;
        }
        double top = shape.max(Direction.Axis.Y);
        if (top <= 0.2) return f | PASS;            // carpet, low snow: walk right over it
        if (top <= 1.0) return f | STAND;           // full blocks, slabs, farmland, soul sand...
        return f;                                    // fences and walls: can't stand on or walk through
    }

    private static boolean isAvoid(String path) {
        for (String a : AVOID_NAMES) if (path.contains(a)) return true;
        return false;
    }

    @Override
    public double breakTicks(int x, int y, int z) {
        long k = ActionPathfinder.pack(x, y, z);
        int c = breaks.get(k, Integer.MIN_VALUE);
        if (c != Integer.MIN_VALUE) return c < 0 ? -1 : c / 10.0;
        double t = computeBreak(new BlockPos(x, y, z));
        breaks.put(k, t < 0 ? -10 : (int) Math.round(t * 10));
        return t;
    }

    private double computeBreak(BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir()) return 0;
        if (hasFluid(p)) return -1;
        // anything that would let water or lava in
        if (hasFluid(p.above()) || hasFluid(p.below()) || hasFluid(p.offset(1, 0, 0)) || hasFluid(p.offset(-1, 0, 0))
                || hasFluid(p.offset(0, 0, 1)) || hasFluid(p.offset(0, 0, -1))) return -1;
        String path = SurvivalBrain.blockPath(s);
        if (Protection.isManMade(path) || path.contains("spawner") || path.contains("chest") || path.contains("_bed")
                || path.contains("shulker") || path.contains("barrel")) return -1;
        // logs that aren't part of a tree are somebody's build (a house's corner pillars)
        if (SurvivalBrain.isLog(s) && !Protection.isNaturalTreeLog(level, p)) return -1;
        if (protection.isVillage(p)) return -1;
        if (Blueprints.protects(level, p)) return -1; // one of the companions' builds (a farm's sand, a redstone line)
        float hardness = s.getDestroySpeed(level, p);
        if (hardness < 0) return -1;
        if (hardness == 0) return 1;
        float speed = toolSpeed.computeIfAbsent(s.getBlock(), b -> bestSpeed(s));
        boolean harvest = speed > 1.0f || MiningSkills.tierFor(path) == 0;
        double ticks = Math.ceil(hardness * (harvest ? 30.0 : 100.0) / speed);
        if (ticks > 240) return -1; // obsidian by hand etc.: not worth it
        return ticks;
    }

    private float bestSpeed(BlockState s) {
        float best = 1.0f;
        Inventory inv = bot.getInventory();
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            try {
                best = Math.max(best, st.getDestroySpeed(s));
            } catch (Throwable ignored) { }
        }
        return best;
    }

    private boolean hasFluid(BlockPos p) {
        FluidState fs = level.getFluidState(p);
        return fs != null && (fs.is(FluidTags.WATER) || fs.is(FluidTags.LAVA));
    }

    // ------------------------------------------------------------------------
    // Throwaway blocks (for pillaring and bridging)
    // ------------------------------------------------------------------------

    static boolean isThrowaway(String itemPath) {
        for (String t : THROWAWAY) if (t.equals(itemPath)) return true;
        return false;
    }

    static int countThrowaway(ServerPlayer bot) {
        Inventory inv = bot.getInventory();
        int n = 0;
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && isThrowaway(SurvivalBrain.itemPath(st))) n += st.getCount();
        }
        return n;
    }

    /** Puts a throwaway block in the main hand. False if there is none. */
    static boolean holdThrowaway(ServerPlayer bot) {
        Inventory inv = bot.getInventory();
        int size = Math.min(36, inv.getContainerSize());
        int slot = -1;
        for (int i = 0; i < size; i++) {
            if (isThrowaway(SurvivalBrain.itemPath(inv.getItem(i)))) {
                slot = i;
                if (i < 9) break;
            }
        }
        if (slot < 0) return false;
        if (slot < 9) { inv.setSelectedSlot(slot); return true; }
        int target = -1;
        for (int i = 0; i < 9; i++) if (inv.getItem(i).isEmpty()) { target = i; break; }
        if (target < 0) {
            for (int i = 0; i < 9; i++) {
                ItemStack st = inv.getItem(i);
                if (st.getMaxDamage() <= 0 && !SurvivalBrain.itemPath(st).equals("torch")) { target = i; break; }
            }
        }
        if (target < 0) target = inv.getSelectedSlot();
        ItemStack block = inv.getItem(slot);
        inv.setItem(slot, inv.getItem(target));
        inv.setItem(target, block);
        inv.setSelectedSlot(target);
        inv.setChanged();
        return true;
    }
}
