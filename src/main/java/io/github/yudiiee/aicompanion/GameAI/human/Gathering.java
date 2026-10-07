package io.github.yudiiee.aicompanion.GameAI.human;

import carpet.fakes.ServerPlayerInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.onServer;

/**
 * Doing things for whoever asked: gather or craft materials ("get me 10 iron", "make me a
 * chest", "i need 32 planks") and bring them back to that player, like a friend would.
 * Works for any player on the server, not just the host.
 */
public final class Gathering {

    private Gathering() {}

    // ------------------------------------------------------------------------
    // What a mined block turns into in your inventory
    // ------------------------------------------------------------------------

    /** Item paths a player would expect to get for a block target ("iron" -> raw_iron). */
    static Predicate<String> itemsFor(MiningSkills.Target t) {
        String label = t.label();
        return p -> {
            if (p.isEmpty()) return false;
            if (t.test(p)) return true; // sand, gravel, logs, dirt... drop themselves
            if (t.logs()) return (label.equals("wood") || label.equals("logs")) && SurvivalBrain.isLogItem(p);
            return switch (label) {
                case "coal" -> p.equals("coal");
                case "iron" -> p.equals("raw_iron");
                case "copper" -> p.equals("raw_copper");
                case "gold" -> p.equals("raw_gold");
                case "diamond" -> p.equals("diamond");
                case "emerald" -> p.equals("emerald");
                case "lapis" -> p.equals("lapis_lazuli");
                case "redstone" -> p.equals("redstone");
                case "quartz" -> p.equals("quartz");
                case "stone" -> p.equals("cobblestone");
                case "deepslate" -> p.equals("cobbled_deepslate");
                case "dirt" -> p.equals("dirt");
                case "ores" -> p.equals("coal") || p.startsWith("raw_") || p.equals("diamond") || p.equals("emerald")
                        || p.equals("lapis_lazuli") || p.equals("redstone");
                default -> false;
            };
        };
    }

    /** 1 block mined = 1 item for these, so hand over exactly as many as asked. */
    static boolean oneToOne(MiningSkills.Target t) {
        return t.logs() || switch (t.label()) {
            case "stone", "deepslate", "dirt", "sand" -> true;
            default -> !t.label().equals("ores") && t.stripY() == null && !t.label().equals("quartz");
        };
    }

    // ------------------------------------------------------------------------
    // Crafting materials
    // ------------------------------------------------------------------------

    /** A thing the bot can craft in its inventory, and what it's made of. */
    record Craftable(String item, String label, int perCraft, Map<String, Integer> needs, boolean table) {
        Predicate<String> matches() {
            return item.equals("planks") ? p -> p.endsWith("_planks") : item::equals;
        }
    }

    private static Map<String, Integer> m(Object... kv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], (Integer) kv[i + 1]);
        return out;
    }

    /** "planks", "4 sticks", "a crafting table", "stone pickaxe"... -> recipe, or null. */
    static Craftable craftable(String phrase) {
        if (phrase == null) return null;
        String q = phrase.toLowerCase(Locale.ROOT).replaceAll("[^a-z_ ]", " ")
                .replaceAll("\\b(some|more|the|a|an|of|me|us|few|couple|bunch|lot|lots|stack|stacks|pls|please|for|too|now)\\b", " ")
                .trim().replaceAll("\\s+", " ");
        if (q.matches("(wood(en)? )?planks?|(oak|spruce|birch|jungle|acacia|dark oak|mangrove|cherry|pale oak) planks?"))
            return new Craftable("planks", "planks", 4, m("log", 1), false);
        if (q.matches("sticks?")) return new Craftable("stick", "sticks", 4, m("planks", 2), false);
        if (q.matches("(crafting )?table|crafting bench|workbench")) return new Craftable("crafting_table", "crafting table", 1, m("planks", 4), false);
        if (q.matches("chests?")) return new Craftable("chest", "chest", 1, m("planks", 8), true);
        if (q.matches("torch(es)?")) return new Craftable("torch", "torches", 4, m("coal", 1, "stick", 1), false);
        if (q.matches("furnaces?")) return new Craftable("furnace", "furnace", 1, m("cobble", 8), true);
        if (q.matches("((wood(en)?|oak) )?slabs?")) return new Craftable("oak_slab", "slabs", 6, m("planks", 3), true);
        if (q.matches("((wood(en)?|oak) )?doors?")) return new Craftable("oak_door", "door", 3, m("planks", 6), true);
        if (q.matches("ladders?")) return new Craftable("ladder", "ladders", 3, m("stick", 7), true);
        java.util.regex.Matcher tm = java.util.regex.Pattern
                .compile("^(wood|wooden|stone)? ?(pickaxe|pick|axe|shovel|spade|sword|hoe)s?$").matcher(q);
        if (tm.find()) {
            String mat = tm.group(1) == null ? "stone" : (tm.group(1).startsWith("wood") ? "wooden" : "stone");
            String kind = switch (tm.group(2)) { case "pick" -> "pickaxe"; case "spade" -> "shovel"; default -> tm.group(2); };
            int head = switch (kind) { case "pickaxe", "axe" -> 3; case "shovel" -> 1; default -> 2; };
            int sticks = kind.equals("sword") ? 1 : 2;
            String material = mat.equals("stone") ? "cobble" : "planks";
            return new Craftable(mat + "_" + kind, mat.replace("wooden", "wooden") + " " + kind, 1, m(material, head, "stick", sticks), true);
        }
        return null;
    }

    /** Is there space in the pockets for {@code n} more of this (an empty slot, or a stack it fits on)? */
    static boolean roomFor(ServerPlayer bot, Predicate<String> same, int n) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) return true;
            if (same.test(SurvivalBrain.itemPath(s)) && s.getCount() + n <= s.getMaxStackSize()) return true;
        }
        return false;
    }

    static int countOf(ServerPlayer bot, Predicate<String> test) {
        Inventory inv = bot.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && test.test(SurvivalBrain.itemPath(s))) n += s.getCount();
        }
        return n;
    }

    static int countOf(ServerPlayer bot, String kind) {
        Predicate<String> test = kindTest(kind);
        Inventory inv = bot.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && test.test(SurvivalBrain.itemPath(s))) n += s.getCount();
        }
        return n;
    }

    private static Predicate<String> kindTest(String kind) {
        return switch (kind) {
            case "log" -> SurvivalBrain::isLogItem;
            case "planks" -> p -> p.endsWith("_planks");
            case "cobble" -> SurvivalBrain::isCobbleItem;
            case "coal" -> p -> p.equals("coal") || p.equals("charcoal");
            default -> kind::equals;
        };
    }

    private static final Map<String, Craftable> INTERMEDIATE = Map.of(
            "planks", new Craftable("planks", "planks", 4, m("log", 1), false),
            "stick", new Craftable("stick", "sticks", 4, m("planks", 2), false),
            "crafting_table", new Craftable("crafting_table", "crafting table", 1, m("planks", 4), false));

    /**
     * Crafts towards {@code want} of the item from what's in the inventory. Returns null when
     * done, or the raw material that's missing ("log", "cobble", "coal"). Server thread.
     */
    static String craftTowards(ServerPlayer bot, Craftable c, int want) {
        for (int guard = 0; guard < 256; guard++) {
            int before = countOf(bot, c.item());
            if (before >= want) return null;
            String missing = craftOnce(bot, c, 0);
            if (missing != null) return missing;
            if (countOf(bot, c.item()) <= before) return "stuck"; // inventory full or unknown item: don't loop forever
        }
        return null;
    }

    /** One craft of {@code c}, making ingredients first if needed. Returns a missing raw material or null. */
    private static String craftOnce(ServerPlayer bot, Craftable c, int depth) {
        if (depth > 4) return "log";
        if (c.table() && countOf(bot, "crafting_table") == 0) {
            String r = craftOnce(bot, INTERMEDIATE.get("crafting_table"), depth + 1);
            if (r != null) return r;
            // made one but it isn't in the inventory: no room for it (it got dropped)
            if (countOf(bot, "crafting_table") == 0) return countOf(bot, "planks") > 0 || countOf(bot, "log") > 0 ? "stuck" : "log";
        }
        for (Map.Entry<String, Integer> e : c.needs().entrySet()) {
            String kind = e.getKey();
            for (int guard = 0; countOf(bot, kind) < e.getValue(); guard++) {
                Craftable sub = INTERMEDIATE.get(kind);
                if (sub == null) return kind; // raw: log / cobble / coal
                int before = countOf(bot, kind);
                String r = craftOnce(bot, sub, depth + 1);
                if (r != null) return r;
                if (guard > 64 || countOf(bot, kind) <= before) return "stuck";
            }
        }
        if (!roomFor(bot, c.matches(), c.perCraft())) return "stuck"; // don't craft it just to drop it
        String made = null;
        for (Map.Entry<String, Integer> e : c.needs().entrySet()) {
            String first = SurvivalBrain.take(bot, kindTest(e.getKey()), e.getValue());
            if (e.getKey().equals("log")) made = first;
        }
        String out = c.item();
        if (out.equals("planks")) {
            out = made == null ? "oak_planks" : made.replace("stripped_", "").replaceAll("_(log|wood|stem|hyphae)$", "_planks");
            if ("air".equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(SurvivalBrain.item(out)).getPath())) out = "oak_planks";
        }
        SurvivalBrain.give(bot, out, c.perCraft());
        Motions.swingArm(bot);
        return null;
    }

    private static int rawEstimate(Craftable c, int want, String raw) {
        int crafts = (want + c.perCraft() - 1) / c.perCraft();
        int n = 0;
        for (Map.Entry<String, Integer> e : c.needs().entrySet()) {
            int qty = e.getValue() * crafts;
            switch (e.getKey()) {
                case "log" -> { if (raw.equals("log")) n += qty; }
                case "planks" -> { if (raw.equals("log")) n += (qty + 3) / 4; }
                case "stick" -> { if (raw.equals("log")) n += (qty + 15) / 16 + 0; }
                case "cobble" -> { if (raw.equals("cobble")) n += qty; }
                case "coal" -> { if (raw.equals("coal")) n += qty; }
                default -> { }
            }
        }
        if (raw.equals("log") && c.table()) n += 1;
        return Math.max(raw.equals("log") ? 2 : 1, n);
    }

    /** Job: gather what's needed, craft {@code want} of {@code c}, and hand it over. */
    static void craftFor(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Craftable c, int want, UUID requester)
            throws InterruptedException {
        if (!makeSure(server, bot, b, c, want)) return;
        int have = onServer(server, () -> countOf(bot, c.item()), 0);
        if (requester != null) deliver(server, bot, b, requester, c.matches(), c.label(), want);
        else HumanChat.say(server, b.name, "made " + Math.min(have, want) + " " + c.label());
    }

    /** Gathers raw materials and crafts until there are {@code want} of {@code c}. False (and says why) if it can't. */
    static boolean makeSure(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Craftable c, int want)
            throws InterruptedException {
        boolean madeRoom = false;
        // Already made one and put it away? Take it out of the chest.
        int stored = onServer(server, () -> countOf(bot, c.item()), 0);
        if (stored < want) Storage.withdraw(server, bot, b, c.matches(), want - stored, c.label());
        boolean triedChests = false;
        for (int round = 0; round < 6 && SurvivalBrain.jobAlive(b); round++) {
            // (the fallback is only used if the server didn't answer: that's not "missing wood")
            String missing = onServer(server, () -> craftTowards(bot, c, want), "busy");
            if (missing == null) break;
            if (missing.equals("busy")) { SurvivalBrain.sleep(500); continue; }
            if (missing.equals("stuck")) {
                if (!madeRoom) { // full pockets: toss the junk and try again
                    madeRoom = true;
                    onServer(server, () -> { Storage.makeRoom(bot, 5); return null; }, null);
                    continue;
                }
                HumanChat.say(server, b.name, HumanChat.pick("my inventory's too full to craft that", "can't craft that right now, inventory's full"));
                return false;
            }
            int amount = rawEstimate(c, want, missing);
            MiningSkills.Target raw = switch (missing) {
                case "cobble" -> MiningSkills.resolve("stone");
                case "coal" -> MiningSkills.resolve("coal");
                default -> MiningSkills.resolve("wood");
            };
            if (raw == null) return false;
            // Inventory first, then the chests, and only then go out and gather it.
            if (!triedChests) {
                triedChests = true;
                int fromChest = 0;
                if (missing.equals("log")) fromChest += Storage.withdraw(server, bot, b, p -> p.endsWith("_planks"), amount * 4, "planks");
                fromChest += Storage.withdraw(server, bot, b, kindTest(missing), amount, raw.label());
                if (fromChest > 0) { round--; continue; }
            }
            HumanChat.say(server, b.name, HumanChat.pick("need some " + raw.label() + " for that, one sec",
                    "gotta get " + raw.label() + " first"));
            int before = onServer(server, () -> countOf(bot, missing), 0);
            MiningSkills.collect(server, bot, b, raw, amount);
            int after = onServer(server, () -> countOf(bot, missing), 0);
            if (after <= before) {
                HumanChat.say(server, b.name, "couldn't find " + raw.label() + " for the " + c.label() + ", sorry");
                return false;
            }
        }
        if (!SurvivalBrain.jobAlive(b)) return false;
        int have = onServer(server, () -> countOf(bot, c.item()), 0);
        if (have == 0) {
            HumanChat.say(server, b.name, "couldn't make the " + c.label() + ", sorry");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------
    // Handing things over
    // ------------------------------------------------------------------------

    /**
     * Walks back to the player who asked and throws them what matches (up to {@code max},
     * or everything when max &lt;= 0).
     */
    static void deliver(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, UUID requester,
                        Predicate<String> items, String label, int max) throws InterruptedException {
        int have = onServer(server, () -> {
            Inventory inv = bot.getInventory();
            int n = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && items.test(SurvivalBrain.itemPath(s))) n += s.getCount();
            }
            return n;
        }, 0);
        if (have == 0) return;
        Surface.backUp(server, bot, b, requester); // deep in a mine: dig back up first
        for (int attempt = 0; attempt < 4 && SurvivalBrain.jobAlive(b); attempt++) {
            ServerPlayer player = onServer(server, () -> server.getPlayerList().getPlayer(requester), null);
            if (player == null || player.level() != bot.level()) {
                HumanChat.say(server, b.name, "got the " + label + ", i'll hold on to it for you");
                return;
            }
            double d = onServer(server, () -> Math.sqrt(bot.distanceToSqr(player)), 99.0);
            if (d <= 3.5) {
                int given = onServer(server, () -> handOver(bot, player, items, max), 0);
                String name = player.getName().getString();
                HumanChat.say(server, b.name, given > 0
                        ? HumanChat.pick("here, " + given + " " + label, "there you go " + name, "catch", "here's your " + label)
                        : "hm, don't have any " + label + " on me");
                return;
            }
            if (attempt == 0) SurvivalBrain.maybeSay(server, b, HumanChat.pick("got it, bringing it over", "coming back with the " + label), 0.8);
            BlockPos target = onServer(server, player::blockPosition, null);
            if (target == null) return;
            BotPathing.Options o = BotPathing.Options.full();
            o.timeoutTicks = 20 * 120;
            BotPathing.goToBlocking(bot, ActionPathfinder.near(target.getX(), target.getY(), target.getZ(), 2.5), o, 125_000L);
        }
        HumanChat.say(server, b.name, "can't get to you, come grab the " + label + " from me");
    }

    /**
     * Hands matching items to the player: the bot turns to them, swings its arm and the items
     * go into their inventory (or land at their feet if it's full). Returns how many items.
     * Server thread.
     */
    static int handOver(ServerPlayer bot, ServerPlayer player, Predicate<String> items, int max) {
        io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, player.getEyePosition().add(0, -0.6, 0));
        Inventory inv = bot.getInventory();
        int size = Math.min(36, inv.getContainerSize());
        int left = max > 0 ? max : Integer.MAX_VALUE;
        int given = 0;
        Vec3 at = player.position().add(0, 0.4, 0);
        for (int i = 0; i < size && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !items.test(SurvivalBrain.itemPath(s))) continue;
            int n = Math.min(left, s.getCount());
            ItemStack part = s.copy();
            part.setCount(n);
            s.shrink(n);
            // straight into their inventory (a thrown item can fall short or land under a flying player)
            player.getInventory().add(part);
            if (!part.isEmpty()) { // their inventory is full: leave it at their feet
                net.minecraft.world.entity.item.ItemEntity drop =
                        new net.minecraft.world.entity.item.ItemEntity(bot.level(), at.x, at.y, at.z, part);
                drop.setDeltaMovement(0, 0.05, 0);
                bot.level().addFreshEntity(drop);
            }
            given += n;
            left -= n;
        }
        inv.setChanged();
        Motions.swingArm(bot);
        return given;
    }
}
