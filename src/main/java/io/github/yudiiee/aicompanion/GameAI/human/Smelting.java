package io.github.yudiiee.aicompanion.GameAI.human;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

import static io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.onServer;

/**
 * Smelting like a player: get the raw stuff (mining it if needed), get fuel, make a
 * furnace if there isn't one, put it down, load it, wait for the real furnace to cook,
 * take the results, pick the furnace back up and hand the results over.
 */
public final class Smelting {

    private Smelting() {}

    /** What goes in, what comes out, and how to get more input if we run out. */
    record Recipe(String output, String label, Predicate<String> input, String gatherPhrase) {}

    private static final List<Recipe> RECIPES = List.of(
            new Recipe("iron_ingot", "iron ingots", p -> p.equals("raw_iron") || p.endsWith("iron_ore"), "iron"),
            new Recipe("gold_ingot", "gold ingots", p -> p.equals("raw_gold") || (p.endsWith("gold_ore") && !p.startsWith("nether_")), "gold"),
            new Recipe("copper_ingot", "copper ingots", p -> p.equals("raw_copper") || p.endsWith("copper_ore"), "copper"),
            new Recipe("glass", "glass", p -> p.equals("sand") || p.equals("red_sand"), "sand"),
            new Recipe("stone", "smooth stone", "cobblestone"::equals, "stone"),
            new Recipe("charcoal", "charcoal", p -> (p.endsWith("_log") || p.endsWith("_wood")) && !p.startsWith("stripped_"), "wood"),
            new Recipe("cooked_beef", "steak", "beef"::equals, null),
            new Recipe("cooked_porkchop", "cooked porkchops", "porkchop"::equals, null),
            new Recipe("cooked_chicken", "cooked chicken", "chicken"::equals, null),
            new Recipe("cooked_mutton", "cooked mutton", "mutton"::equals, null),
            new Recipe("cooked_cod", "cooked cod", "cod"::equals, null),
            new Recipe("cooked_salmon", "cooked salmon", "salmon"::equals, null),
            new Recipe("cooked_rabbit", "cooked rabbit", "rabbit"::equals, null),
            new Recipe("baked_potato", "baked potatoes", "potato"::equals, null),
            new Recipe("dried_kelp", "dried kelp", "kelp"::equals, null),
            new Recipe("brick", "bricks", "clay_ball"::equals, null));

    /** "iron", "iron ingots", "raw iron", "gold", "beef", "sand"... -> recipe, or null. */
    static Recipe recipeFor(String phrase) {
        String q = phrase == null ? "" : phrase.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z_ ]", " ")
                .replaceAll("\\b(some|more|the|a|an|of|me|us|my|your|all|raw|ores?|ingots?|pls|please|for|too|now|it|them)\\b", " ")
                .trim().replaceAll("\\s+", " ");
        return switch (q) {
            case "iron" -> RECIPES.get(0);
            case "gold" -> RECIPES.get(1);
            case "copper" -> RECIPES.get(2);
            case "sand", "glass" -> RECIPES.get(3);
            case "cobblestone", "cobble", "smooth stone", "stone" -> RECIPES.get(4);
            case "wood", "logs", "log", "charcoal" -> RECIPES.get(5);
            case "beef", "steak", "steaks" -> RECIPES.get(6);
            case "pork", "porkchop", "porkchops" -> RECIPES.get(7);
            case "chicken" -> RECIPES.get(8);
            case "mutton" -> RECIPES.get(9);
            case "cod", "fish" -> RECIPES.get(10);
            case "salmon" -> RECIPES.get(11);
            case "rabbit" -> RECIPES.get(12);
            case "potato", "potatoes" -> RECIPES.get(13);
            case "kelp" -> RECIPES.get(14);
            case "clay", "clay ball", "clay balls" -> RECIPES.get(15);
            default -> null;
        };
    }

    // ------------------------------------------------------------------------
    // Fuel
    // ------------------------------------------------------------------------

    /** Items one piece of this fuel smelts (vanilla burn times / 200). 0 = not fuel. */
    static double fuelValue(String p) {
        if (p.equals("coal") || p.equals("charcoal")) return 8;
        if (p.equals("coal_block")) return 80;
        if (p.equals("blaze_rod")) return 12;
        if (p.equals("lava_bucket")) return 100;
        if (p.endsWith("_planks") || p.endsWith("_log") || p.endsWith("_wood") || p.endsWith("_stem")) return 1.5;
        if (p.equals("stick")) return 0.5;
        return 0;
    }

    private static int count(ServerPlayer bot, Predicate<String> test) {
        Inventory inv = bot.getInventory();
        int n = 0;
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && test.test(SurvivalBrain.itemPath(s))) n += s.getCount();
        }
        return n;
    }

    private static double fuelAvailable(ServerPlayer bot, Predicate<String> notThis) {
        Inventory inv = bot.getInventory();
        double v = 0;
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            String p = SurvivalBrain.itemPath(s);
            if (notThis.test(p)) continue;
            v += fuelValue(p) * s.getCount();
        }
        return v;
    }

    /** Takes up to {@code max} of ONE kind of item matching {@code test} out of the inventory. */
    private static ItemStack takeStack(ServerPlayer bot, Predicate<String> test, int max) {
        Inventory inv = bot.getInventory();
        ItemStack out = ItemStack.EMPTY;
        String kind = null;
        for (int i = 0; i < Math.min(36, inv.getContainerSize()) && (out.isEmpty() || out.getCount() < max); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            String p = SurvivalBrain.itemPath(s);
            if (!test.test(p) || (kind != null && !kind.equals(p))) continue;
            int n = Math.min(s.getCount(), max - (out.isEmpty() ? 0 : out.getCount()));
            if (out.isEmpty()) { out = s.copy(); out.setCount(n); kind = p; }
            else out.setCount(out.getCount() + n);
            s.shrink(n);
        }
        inv.setChanged();
        return out;
    }

    /** Best single fuel kind: coal first, then planks/logs. */
    private static ItemStack takeFuel(ServerPlayer bot, int items, Predicate<String> notThis) {
        for (String[] kind : new String[][]{{"coal", "charcoal"}, {"coal_block"}, {"_planks"}, {"_log", "_stem"}, {"stick"}}) {
            Predicate<String> t = p -> {
                if (notThis.test(p)) return false;
                for (String k : kind) if (k.startsWith("_") ? p.endsWith(k) : p.equals(k)) return true;
                return false;
            };
            if (count(bot, t) == 0) continue;
            ItemStack probe = takeStack(bot, t, 1);
            double per = fuelValue(SurvivalBrain.itemPath(probe));
            int need = Math.max(1, (int) Math.ceil(items / per)) - 1;
            if (need > 0) {
                ItemStack more = takeStack(bot, SurvivalBrain.itemPath(probe)::equals, need);
                if (!more.isEmpty()) probe.setCount(probe.getCount() + more.getCount());
            }
            return probe;
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------------
    // The job
    // ------------------------------------------------------------------------

    /**
     * Smelts {@code want} of the recipe's output (or everything it has when want &lt;= 0) and gives
     * it to {@code requester} (or keeps it when null).
     */
    static void smeltFor(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Recipe r, int want, UUID requester)
            throws InterruptedException {
        SurvivalBrain.keep(bot, p -> p.equals(r.output()) || r.input().test(p) || p.equals("coal") || p.equals("charcoal"));
        try {
            run(server, bot, b, r, want, requester);
        } finally {
            SurvivalBrain.keep(bot, null);
        }
    }

    private static void run(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Recipe r, int want, UUID requester)
            throws InterruptedException {
        final int asked = want;
        // 0. someone wants ingots and there are some already (pockets, then chests): just bring those
        if (requester != null && want > 0) {
            int ready = onServer(server, () -> count(bot, r.output()::equals), 0);
            if (ready < want) ready += Storage.withdraw(server, bot, b, r.output()::equals, want - ready, r.label());
            if (ready >= want) {
                Gathering.deliver(server, bot, b, requester, r.output()::equals, r.label(), want);
                return;
            }
            want -= ready; // smelt the rest
        }
        // 1. the raw material (pockets, then chests, then go mine it)
        int inBag = onServer(server, () -> count(bot, r.input()), 0);
        int needRaw = (want > 0 ? want : 0) - inBag;
        if (needRaw > 0) Storage.withdraw(server, bot, b, r.input(), needRaw, r.label().replace(" ingot", "").replace("cooked ", "raw "));
        int have = onServer(server, () -> count(bot, r.input()), 0);
        int target = want > 0 ? want : (have > 0 ? have : 3);
        if (have < target) {
            if (r.gatherPhrase() == null) {
                HumanChat.say(server, b.name, have == 0 ? "i don't have any " + r.label().replace("cooked ", "raw ") + " to cook"
                        : "only got " + have + ", cooking those");
                if (have == 0) return;
                target = have;
            } else {
                MiningSkills.Target t = MiningSkills.resolve(r.gatherPhrase());
                if (t == null) return;
                HumanChat.say(server, b.name, HumanChat.pick("need to mine some " + t.label() + " first", "gotta get the " + t.label() + " first"));
                MiningSkills.collect(server, bot, b, t, target - have, true);
                if (!SurvivalBrain.jobAlive(b)) return;
                have = onServer(server, () -> count(bot, r.input()), 0);
                if (have == 0) { HumanChat.say(server, b.name, "couldn't get any " + t.label() + ", sorry"); return; }
                target = Math.min(target, have);
            }
        }
        final int amount = Math.min(target, 64);

        // 2. fuel
        double fuel = onServer(server, () -> fuelAvailable(bot, r.input()), 0.0);
        if (fuel < amount) { // coal from the chests before digging for it
            Storage.withdraw(server, bot, b, p -> p.equals("coal") || p.equals("charcoal"), (int) Math.ceil((amount - fuel) / 8.0), "coal");
            fuel = onServer(server, () -> fuelAvailable(bot, r.input()), 0.0);
        }
        if (fuel < amount) {
            MiningSkills.Target coal = MiningSkills.resolve("coal");
            MiningSkills.collect(server, bot, b, coal, (int) Math.ceil((amount - fuel) / 8.0) + 1, true);
            fuel = onServer(server, () -> fuelAvailable(bot, r.input()), 0.0);
            if (fuel < amount) {
                MiningSkills.collect(server, bot, b, MiningSkills.resolve("wood"), (int) Math.ceil((amount - fuel) / 1.5), true);
            }
            if (!SurvivalBrain.jobAlive(b)) return;
        }

        // 3. a furnace
        if (onServer(server, () -> count(bot, "furnace"::equals), 0) == 0) {
            Gathering.Craftable furnace = Gathering.craftable("furnace");
            if (furnace == null || !Gathering.makeSure(server, bot, b, furnace, 1)) return;
        }

        // 4. put it down
        BlockPos placedAt = onServer(server, () -> placeFurnace(bot), null);
        if (placedAt == null) placedAt = digFurnaceSpot(server, bot, b); // in a 1-wide tunnel: cut a nook in the wall
        final BlockPos spot = placedAt;
        if (spot == null) {
            HumanChat.say(server, b.name, "no room to put a furnace down here");
            return;
        }
        SurvivalBrain.maybeSay(server, b, HumanChat.pick("smelting, one sec", "gonna cook these real quick", "furnace time"), 0.8);

        // 5. load it
        boolean loaded = onServer(server, () -> {
            Container c = HopperBlockEntity.getContainerAt(bot.level(), spot);
            if (c == null || c.getContainerSize() < 3) return false;
            ItemStack in = takeStack(bot, r.input(), amount);
            ItemStack fuelStack = takeFuel(bot, in.getCount(), r.input());
            if (in.isEmpty() || fuelStack.isEmpty()) {
                if (!in.isEmpty()) bot.getInventory().add(in);
                if (!fuelStack.isEmpty()) bot.getInventory().add(fuelStack);
                return false;
            }
            c.setItem(0, in);
            c.setItem(1, fuelStack);
            c.setChanged();
            io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, Vec3.atCenterOf(spot));
            Motions.swingArm(bot);
            return true;
        }, false);
        int made = 0;
        if (loaded) {
            // 6. wait for the real furnace (10 s per item), taking results as they come out
            long end = System.currentTimeMillis() + amount * 10_500L + 15_000L;
            while (SurvivalBrain.jobAlive(b) && System.currentTimeMillis() < end) {
                SurvivalBrain.sleep(1000);
                int[] state = onServer(server, () -> {
                    Container c = HopperBlockEntity.getContainerAt(bot.level(), spot);
                    if (c == null) return new int[]{-1, 0};
                    ItemStack out = c.getItem(2);
                    int got = 0;
                    if (!out.isEmpty()) {
                        got = out.getCount();
                        giveBack(bot, out);
                        c.setItem(2, ItemStack.EMPTY);
                    }
                    int left = c.getItem(0).isEmpty() ? 0 : c.getItem(0).getCount();
                    // out of fuel with stuff left: top it up
                    if (left > 0 && c.getItem(1).isEmpty()) {
                        ItemStack more = takeFuel(bot, left, r.input());
                        if (!more.isEmpty()) c.setItem(1, more);
                    }
                    c.setChanged();
                    io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, Vec3.atCenterOf(spot));
                    return new int[]{left, got};
                }, new int[]{-1, 0});
                if (state[0] < 0) break; // furnace gone
                made += state[1];
                if (state[0] == 0 && made > 0) {
                    // last one might still be in the output next second
                    SurvivalBrain.sleep(600);
                    int[] last = onServer(server, () -> {
                        Container c = HopperBlockEntity.getContainerAt(bot.level(), spot);
                        if (c == null) return new int[]{0};
                        ItemStack out = c.getItem(2);
                        if (out.isEmpty()) return new int[]{0};
                        int got = out.getCount();
                        giveBack(bot, out);
                        c.setItem(2, ItemStack.EMPTY);
                        c.setChanged();
                        return new int[]{got};
                    }, new int[]{0});
                    made += last[0];
                    break;
                }
            }
        }

        // 7. empty it and pick it back up
        onServer(server, () -> {
            Container c = HopperBlockEntity.getContainerAt(bot.level(), spot);
            if (c != null) {
                for (int i = 0; i < 3; i++) {
                    ItemStack s = c.getItem(i);
                    if (s.isEmpty()) continue;
                    giveBack(bot, s);
                    c.setItem(i, ItemStack.EMPTY);
                }
                c.setChanged();
            }
            return null;
        }, null);
        MiningSkills.dig(server, bot, b, spot, false, 0);
        SurvivalBrain.pickUpNearbyItems(server, bot, 4);

        if (!loaded || made == 0) {
            HumanChat.say(server, b.name, "the furnace didn't work out, sorry");
            return;
        }
        if (requester != null) {
            Gathering.deliver(server, bot, b, requester, r.output()::equals, r.label(), asked > 0 ? asked : 0);
        } else {
            HumanChat.say(server, b.name, HumanChat.pick("got " + made + " " + r.label(), "done, " + made + " " + r.label()));
        }
    }

    /** Into the bot's inventory, or dropped at its feet if full. */
    private static void giveBack(ServerPlayer bot, ItemStack s) {
        ItemStack copy = s.copy();
        bot.getInventory().add(copy);
        if (!copy.isEmpty()) {
            net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(
                    bot.level(), bot.getX(), bot.getY() + 0.5, bot.getZ(), copy);
            bot.level().addFreshEntity(drop);
        }
    }

    /** No free spot to put a furnace (tight tunnel): dig one into the wall next to the bot and place it there. */
    private static BlockPos digFurnaceSpot(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        BlockPos cell = onServer(server, () -> {
            ServerLevel level = bot.level();
            BlockPos f = BotPathing.feet(bot);
            for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                BlockPos p = f.offset(o[0], 0, o[1]);
                String path = SurvivalBrain.blockPath(level.getBlockState(p));
                if (Building.isSolid(level, p) && Building.isSolid(level, p.below()) && !Protection.isManMade(path)
                        && !path.contains("ore") && level.getFluidState(p).isEmpty()) return p;
            }
            return null;
        }, null);
        if (cell == null || !MiningSkills.dig(server, bot, b, cell, true, 0)) return null;
        return onServer(server, () -> Building.placeAt(bot, cell, "furnace")
                && SurvivalBrain.blockPath(bot.level().getBlockState(cell)).equals("furnace") ? cell : null, null);
    }

    /** Puts the furnace on the ground next to the bot. Returns where, or null. Server thread. */
    private static BlockPos placeFurnace(ServerPlayer bot) {
        BlockPos p = Building.placeNear(bot, "furnace");
        return p != null && SurvivalBrain.blockPath(bot.level().getBlockState(p)).equals("furnace") ? p : null;
    }

    /** Puts an item with this id in the main hand (moving it to the hotbar if needed). */
    static boolean holdItem(ServerPlayer bot, String path) {
        Inventory inv = bot.getInventory();
        int size = Math.min(36, inv.getContainerSize());
        int slot = -1;
        for (int i = 0; i < size; i++) {
            if (SurvivalBrain.itemPath(inv.getItem(i)).equals(path)) { slot = i; if (i < 9) break; }
        }
        if (slot < 0) return false;
        if (slot < 9) { inv.setSelectedSlot(slot); return true; }
        int target = -1;
        for (int i = 0; i < 9; i++) if (inv.getItem(i).isEmpty()) { target = i; break; }
        if (target < 0) target = inv.getSelectedSlot();
        ItemStack it = inv.getItem(slot);
        inv.setItem(slot, inv.getItem(target));
        inv.setItem(target, it);
        inv.setSelectedSlot(target);
        inv.setChanged();
        return true;
    }
}
