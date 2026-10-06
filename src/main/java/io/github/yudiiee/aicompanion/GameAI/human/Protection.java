package io.github.yudiiee.aicompanion.GameAI.human;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * "Don't grief" rules: a decent player doesn't take apart villages or other people's
 * builds for materials. Server thread only.
 */
public final class Protection {

    /** Block id fragments that almost only appear in things somebody built. */
    private static final String[] MAN_MADE = {
            "planks", "door", "glass", "_bed", "chest", "barrel", "bell", "torch", "lantern",
            "crafting_table", "furnace", "smoker", "stairs", "slab", "fence", "_wall", "bricks",
            "carpet", "wool", "hay_block", "composter", "lectern", "anvil", "bookshelf", "sign",
            "dirt_path", "farmland", "scaffolding", "concrete", "cartography_table", "fletching_table",
            "smithing_table", "grindstone", "loom", "stonecutter", "brewing_stand", "cauldron",
            "banner", "flower_pot", "ladder", "trapdoor", "pressure_plate", "button", "rail",
            "cobble", "polished_", "smooth_", "chiseled_", "cut_", "_tiles", "lamp", "hopper", "dispenser", "dropper",
            "observer", "piston", "redstone_wire", "repeater", "comparator", "shulker_box", "beacon", "enchanting_table"
    };

    private static final double VILLAGE_RADIUS = 28.0;

    /** Snapshot of things around the bot that make an area off-limits. */
    public static final class Context {
        final List<Vec3> villagers = new ArrayList<>();

        /** True if breaking {@code pos} would probably damage a village or a build. */
        public boolean isProtected(ServerLevel level, BlockPos pos) {
            return isVillage(pos) || nearManMade(level, pos, 3) || Blueprints.protects(level, pos, true);
        }

        /** Inside a village (near villagers, golems or traders)? */
        public boolean isVillage(BlockPos pos) {
            Vec3 c = Vec3.atCenterOf(pos);
            for (Vec3 v : villagers) {
                if (v.distanceTo(c) < VILLAGE_RADIUS) return true;
            }
            return false;
        }
    }

    private Protection() {}

    /** Collects villagers / golems / traders within {@code radius} (+ village radius) of the bot. */
    public static Context scan(ServerPlayer bot, int radius) {
        Context ctx = new Context();
        double r = radius + VILLAGE_RADIUS;
        for (Entity e : bot.level().getEntities(bot, bot.getBoundingBox().inflate(r))) {
            String cls = e.getClass().getSimpleName();
            if (cls.contains("Villager") || cls.contains("IronGolem") || cls.contains("WanderingTrader")) {
                ctx.villagers.add(e.position());
            }
        }
        return ctx;
    }

    public static boolean isManMade(String path) {
        for (String m : MAN_MADE) if (path.contains(m)) return true;
        return false;
    }

    static boolean nearManMade(ServerLevel level, BlockPos pos, int r) {
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
            BlockState s = level.getBlockState(pos.offset(dx, dy, dz));
            if (s.isAir()) continue;
            if (isManMade(SurvivalBrain.blockPath(s))) return true;
        }
        return false;
    }

    /**
     * A log that is part of a real tree: natural soil under the trunk and leaves on top.
     * Village houses, log cabins and stripped/bark blocks don't qualify.
     */
    public static boolean isNaturalTreeLog(ServerLevel level, BlockPos pos) {
        String self = SurvivalBrain.blockPath(level.getBlockState(pos));
        if (self.startsWith("stripped_") || self.endsWith("_wood") || self.endsWith("_hyphae")) return false;
        // down to the bottom of the trunk
        BlockPos bottom = pos;
        for (int i = 0; i < 16; i++) {
            BlockPos below = bottom.below();
            if (!SurvivalBrain.isLog(level.getBlockState(below))) break;
            bottom = below;
        }
        // a trunk we've already started chopping floats a bit above its stump
        BlockPos under = bottom.below();
        for (int i = 0; i < 6 && level.getBlockState(under).isAir(); i++) under = under.below();
        String soil = SurvivalBrain.blockPath(level.getBlockState(under));
        boolean natural = soil.contains("dirt") || soil.equals("grass_block") || soil.equals("podzol")
                || soil.contains("mud") || soil.equals("moss_block") || soil.contains("nylium")
                || soil.equals("mycelium") || soil.equals("snow_block") || soil.equals("sand")
                || soil.contains("mangrove_roots") || SurvivalBrain.isLog(level.getBlockState(under));
        if (!natural || soil.equals("dirt_path")) return false;
        // up to the top, then look for leaves around it
        BlockPos top = pos;
        for (int i = 0; i < 32; i++) {
            BlockPos above = top.above();
            if (!SurvivalBrain.isLog(level.getBlockState(above))) break;
            top = above;
        }
        for (int dx = -2; dx <= 2; dx++) for (int dy = -1; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
            String p = SurvivalBrain.blockPath(level.getBlockState(top.offset(dx, dy, dz)));
            if (p.endsWith("_leaves") || p.endsWith("wart_block")) return true;
        }
        return false;
    }
}
