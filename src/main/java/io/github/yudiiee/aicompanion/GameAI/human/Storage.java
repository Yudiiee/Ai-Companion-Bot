package io.github.yudiiee.aicompanion.GameAI.human;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.onServer;

/**
 * Chests the bot keeps its stuff in, like a player's base storage. It remembers the chests
 * it placed or was shown ("use this chest"), empties what it mined into them, adds another
 * chest when they're full, and can fetch things back out ("get me iron from the chest").
 * Tools, weapons, armour, food, torches and a few building blocks stay in its pockets.
 */
public final class Storage {

    private Storage() {}

    private record Spot(String dim, BlockPos pos) {}

    private static final List<Spot> CHESTS = new CopyOnWriteArrayList<>();
    private static volatile long nextChestTry = 0;
    /** After the house turned out full or unreachable: don't keep walking back every two minutes. */
    static volatile long backoffUntil = 0;
    private static volatile boolean loaded;

    // ------------------------------------------------------------------------
    // Remembering chests (<world>/ai-companion/chests.txt)
    // ------------------------------------------------------------------------

    private static Path file() {
        return Home.worldFile("chests.txt"); // per world, next to the save
    }

    private static volatile Path loadedFrom;

    private static synchronized void load() {
        Path f0 = file();
        if (loaded && f0.equals(loadedFrom)) return;
        // another world: its chests aren't ours
        CHESTS.clear();
        SEEN.clear();
        loaded = true;
        loadedFrom = f0;
        try {
            Path f = f0;
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = line.trim().split(" ");
                if (p.length != 4) continue;
                CHESTS.add(new Spot(p[0], new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]))));
            }
        } catch (Exception ignored) { }
    }

    private static synchronized void save() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Spot s : CHESTS) sb.append(s.dim()).append(' ').append(s.pos().getX()).append(' ')
                    .append(s.pos().getY()).append(' ').append(s.pos().getZ()).append('\n');
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
    }

    private static String dim(ServerLevel level) {
        return String.valueOf(level.dimension()).replaceAll("[^A-Za-z0-9_:/.]", "");
    }

    static boolean isStorageBlock(String path) {
        return path.equals("chest") || path.equals("trapped_chest") || path.equals("barrel");
    }

    static void remember(ServerLevel level, BlockPos pos) {
        load();
        String d = dim(level);
        for (Spot s : CHESTS) if (s.dim().equals(d) && s.pos().equals(pos)) return;
        CHESTS.add(new Spot(d, pos.immutable()));
        save();
    }

    /** Nearest remembered chest that still exists (server thread). */
    static BlockPos nearest(ServerPlayer bot, double maxDist) {
        load();
        ServerLevel level = bot.level();
        String d = dim(level);
        BlockPos best = null;
        double bd = maxDist * maxDist;
        boolean changed = false;
        for (Spot s : CHESTS) {
            if (!s.dim().equals(d)) continue;
            if (!level.isLoaded(s.pos())) continue;
            if (!isStorageBlock(SurvivalBrain.blockPath(level.getBlockState(s.pos())))) {
                CHESTS.remove(s);
                changed = true;
                continue;
            }
            double dd = s.pos().distSqr(bot.blockPosition());
            if (dd < bd) { bd = dd; best = s.pos(); }
        }
        if (changed) save();
        return best;
    }

    /** "use this chest": the chest the player is looking at becomes storage. Server thread. */
    static String designate(ServerPlayer bot, ServerPlayer player) {
        BlockPos hit = io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.crosshair(player, 6.0);
        if (hit == null || !isStorageBlock(SurvivalBrain.blockPath(player.level().getBlockState(hit)))) {
            return "which chest? look at it and say that again";
        }
        remember(player.level(), hit);
        return HumanChat.pick("ok, i'll keep our stuff in that one", "got it, that's our storage chest", "cool, i'll put stuff in there");
    }

    // ------------------------------------------------------------------------
    // What stays in the bot's pockets
    // ------------------------------------------------------------------------

    static boolean isFood(ItemStack s) {
        try {
            return s.get(DataComponents.FOOD) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True if this stack stays with the bot. {@code kept} counts what's been kept so far. */
    static boolean keeps(ItemStack s, Map<String, Integer> kept) {
        String p = SurvivalBrain.itemPath(s);
        if (p.isEmpty()) return true;
        if (s.getMaxDamage() > 0) return true;              // tools, weapons, armour, shield, flint & steel...
        if (isFood(s)) { // about a stack of food stays on hand, the rest goes in the chests
            int have = kept.getOrDefault("#food", 0);
            if (have >= Farm.KEEP_FOOD) return false;
            kept.put("#food", have + s.getCount());
            return true;
        }
        if (p.equals("torch") || p.equals("crafting_table") || p.equals("totem_of_undying") || p.endsWith("bucket")
                || p.equals("furnace") || p.equals("chest") || p.endsWith("_bed")) return true;
        // a few of the basics a player keeps on hand
        int limit = switch (p) {
            case "cobblestone", "dirt", "cobbled_deepslate" -> 32;
            case "stick" -> 8;
            case "coal", "charcoal" -> 8;
            case "wheat_seeds", "beetroot_seeds" -> 16;
            default -> p.endsWith("_planks") ? 8 : 0;
        };
        if (limit == 0) return false;
        int have = kept.getOrDefault(p, 0);
        if (have >= limit) return false;
        kept.put(p, have + s.getCount());
        return true;
    }

    private static boolean sameItem(ItemStack a, ItemStack b) {
        try {
            return ItemStack.isSameItemSameComponents(a, b);
        } catch (Throwable t) {
            return false; // unknown API: only use empty slots
        }
    }

    /** Moves as much of {@code s} as fits into {@code c}. Returns how many moved. */
    static int insert(Container c, ItemStack s) {
        int moved = 0;
        int size = c.getContainerSize();
        for (int i = 0; i < size && !s.isEmpty(); i++) {
            ItemStack t = c.getItem(i);
            if (t.isEmpty() || !sameItem(t, s)) continue;
            int room = t.getMaxStackSize() - t.getCount();
            if (room <= 0) continue;
            int n = Math.min(room, s.getCount());
            t.setCount(t.getCount() + n);
            s.shrink(n);
            moved += n;
        }
        for (int i = 0; i < size && !s.isEmpty(); i++) {
            if (!c.getItem(i).isEmpty()) continue;
            c.setItem(i, s.copy());
            moved += s.getCount();
            s.setCount(0);
        }
        c.setChanged();
        return moved;
    }

    /** Puts everything it doesn't need into the container. Returns [moved, leftOver]. Server thread. */
    static int[] deposit(ServerPlayer bot, Container c) {
        Inventory inv = bot.getInventory();
        Map<String, Integer> kept = new HashMap<>();
        int moved = 0, left = 0;
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || keeps(s, kept)) continue;
            moved += insert(c, s);
            if (!s.isEmpty()) left += s.getCount();
        }
        inv.setChanged();
        return new int[]{moved, left};
    }

    /** A chest nearby, or the makings of one on hand (so it won't go chopping trees with full pockets). */
    static boolean canStoreSoon(ServerPlayer bot) {
        if (Home.get(bot) != null) return true;
        if (nearest(bot, 96) != null) return true;
        if (Building.firstItem(bot, "chest"::equals) != null) return true;
        return Gathering.countOf(bot, "planks") >= 8 || Gathering.countOf(bot, "log") >= 2;
    }

    static int usedSlots(ServerPlayer bot) {
        Inventory inv = bot.getInventory();
        int n = 0;
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) if (!inv.getItem(i).isEmpty()) n++;
        return n;
    }

    // ------------------------------------------------------------------------
    // Jobs
    // ------------------------------------------------------------------------

    private static ServerPlayer nearestHuman(ServerPlayer bot) {
        ServerPlayer best = null;
        double bd = 48 * 48;
        for (ServerPlayer p : bot.level().getServer().getPlayerList().getPlayers()) {
            if (HumanBehavior.isBot(p) || p.level() != bot.level()) continue;
            double d = p.distanceToSqr(bot);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** Gets a chest into the world (crafting one if needed) near the players' base. */
    private static BlockPos newChest(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, BlockPos nextTo)
            throws InterruptedException {
        if (onServer(server, () -> Building.firstItem(bot, "chest"::equals), null) == null) {
            Gathering.Craftable chest = Gathering.craftable("chest");
            if (chest == null || !Gathering.makeSure(server, bot, b, chest, 1)) return null;
        }
        if (nextTo != null) {
            // a second chest right next to the full one
            BlockPos placed = onServer(server, () -> {
                ServerLevel level = bot.level();
                for (BlockPos p : new BlockPos[]{nextTo.offset(1, 0, 0), nextTo.offset(-1, 0, 0),
                        nextTo.offset(0, 0, -1), nextTo.offset(0, 0, 1), nextTo.offset(2, 0, 0), nextTo.offset(-2, 0, 0),
                        nextTo.offset(0, 0, -2), nextTo.offset(0, 0, 2)}) {
                    if (!Building.isFree(level, p) || !Building.isSolid(level, p.below())) continue;
                    if (Building.placeAt(bot, p, "chest") && isStorageBlock(SurvivalBrain.blockPath(level.getBlockState(p)))) return p;
                }
                return null;
            }, null);
            if (placed != null) return placed;
        } else {
            // home is where the people are
            ServerPlayer home = onServer(server, () -> nearestHuman(bot), null);
            if (home != null) {
                BlockPos hp = onServer(server, home::blockPosition, null);
                if (hp != null && onServer(server, () -> bot.blockPosition().distSqr(hp) > 25, false)) {
                    SurvivalBrain.goTo(bot, hp, 60, false);
                }
            }
        }
        return onServer(server, () -> {
            BlockPos p = Building.placeNear(bot, "chest");
            return p != null && isStorageBlock(SurvivalBrain.blockPath(bot.level().getBlockState(p))) ? p : null;
        }, null);
    }

    /** Stuff a player wouldn't think twice about throwing away, most useless first. */
    private static final String[][] JUNK = {
            {"rotten_flesh", "poisonous_potato", "spider_eye", "dead_bush", "wheat_seeds", "beetroot_seeds", "melon_seeds",
                    "pumpkin_seeds", "short_grass", "tall_grass", "fern", "large_fern", "seagrass", "kelp", "lily_pad"},
            {"dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip",
                    "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley", "sunflower", "lilac", "rose_bush", "peony",
                    "pink_petals", "vine", "glow_lichen", "moss_carpet"},
            {"andesite", "diorite", "granite", "tuff", "calcite", "dripstone_block", "pointed_dripstone", "gravel", "netherrack"}
    };

    private static int freeSlots(Inventory inv) {
        int n = 0;
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) if (inv.getItem(i).isEmpty()) n++;
        return n;
    }

    /**
     * Frees up {@code want} inventory slots by getting rid of junk (seeds, flowers, rotten flesh,
     * diorite...), then extra saplings, dirt and deepslate beyond a handful. Returns free slots.
     * Server thread.
     */
    static int makeRoom(ServerPlayer bot, int want) {
        return makeRoom(bot, want, false);
    }

    /** {@code junkOnly}: only the real junk, never extra blocks/logs. Items a job has reserved are never thrown out. */
    static int makeRoom(ServerPlayer bot, int want, boolean junkOnly) {
        Inventory inv = bot.getInventory();
        int size = Math.min(36, inv.getContainerSize());
        if (freeSlots(inv) >= want) return freeSlots(inv);
        Predicate<String> reserved = SurvivalBrain.KEEP.getOrDefault(bot.getUUID(), p -> false);
        for (String[] tier : JUNK) {
            for (int i = 0; i < size && freeSlots(inv) < want; i++) {
                String p = SurvivalBrain.itemPath(inv.getItem(i));
                if (reserved.test(p)) continue;
                if (Farm.isSeed(p) && Farm.hasFarm(bot)) continue; // seeds are worth something once there's a farm
                for (String j : tier) if (p.equals(j)) { inv.setItem(i, ItemStack.EMPTY); break; }
            }
        }
        if (junkOnly) { inv.setChanged(); return freeSlots(inv); }
        // then extras of cheap stuff: keep one stack's worth of each at most
        String[][] extras = {{"_sapling", "4"}, {"dirt", "16"}, {"coarse_dirt", "0"}, {"cobbled_deepslate", "16"},
                {"sand", "16"}, {"stick", "16"}, {"flint", "8"},
                // last resort: nobody needs four stacks of logs in their pockets
                {"stone", "32"}, {"cobblestone", "64"}, {"_log", "64"}};
        for (String[] e : extras) {
            if (freeSlots(inv) >= want) break;
            int keep = Integer.parseInt(e[1]);
            int kept = 0;
            for (int i = 0; i < size; i++) {
                ItemStack s = inv.getItem(i);
                String p = SurvivalBrain.itemPath(s);
                if (s.isEmpty() || !(e[0].startsWith("_") ? p.endsWith(e[0]) : p.equals(e[0]))) continue;
                if (reserved.test(p)) continue;
                if (kept + s.getCount() <= keep) { kept += s.getCount(); continue; }
                if (freeSlots(inv) >= want) break;
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        inv.setChanged();
        return freeSlots(inv);
    }

    /** How many items it would put away. Server thread. */
    static int storable(ServerPlayer bot) {
        Inventory inv = bot.getInventory();
        Map<String, Integer> kept = new HashMap<>();
        int n = 0;
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && !keeps(s, kept)) n += s.getCount();
        }
        return n;
    }

    /** How many inventory slots hold stuff it would put away. Server thread. */
    static int storableSlots(ServerPlayer bot) {
        Inventory inv = bot.getInventory();
        Map<String, Integer> kept = new HashMap<>();
        int n = 0;
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && !keeps(s, kept)) n++;
        }
        return n;
    }

    /** Job: take everything it doesn't need to the storage chest. Returns items stored. */
    static int storeAll(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, boolean talk) throws InterruptedException {
        if (onServer(server, () -> storable(bot), 0) == 0) {
            if (talk) HumanChat.say(server, b.name, "nothing to put away");
            return 0;
        }
        Home.Base home = onServer(server, () -> Home.get(bot), null);
        boolean canBuild = HumanConfig.get().autoHome && onServer(server, () -> Home.canStartBuilding(bot), false);
        if (home == null && canBuild && onServer(server, () -> nearest(bot, 96), null) == null) {
            // no base and no chest: build the base first, it comes with a chest
            SurvivalBrain.maybeSay(server, b, HumanChat.pick("we need a base for all this stuff, gonna build a house",
                    "no chest yet, building us a house with one"), 1.0);
            if (!SurvivalBrain.jobAlive(b)) {
                // playing on its own (not inside a job): building is a job of its own
                onServer(server, () -> Home.startBuilding(bot), false);
                return 0;
            }
            onServer(server, () -> { Home.noteBuildAttempt(bot); return null; }, null);
            House.build(server, bot, b, null);
            home = onServer(server, () -> Home.get(bot), null);
        }
        if (home != null) return storeAtHome(server, bot, b, home, talk);
        // no base (off, not possible here, or it couldn't be built): a plain chest will do
        BlockPos chest = onServer(server, () -> nearest(bot, 96), null);
        if (chest == null) {
            if (!talk && System.currentTimeMillis() < nextChestTry) return 0; // failed recently, don't keep chopping trees for it
            if (talk) SurvivalBrain.maybeSay(server, b, HumanChat.pick("no chest yet, making one", "gonna set up a chest for our stuff"), 1.0);
            chest = newChest(server, bot, b, null);
            if (chest == null) {
                nextChestTry = System.currentTimeMillis() + 10 * 60_000L;
                if (talk) HumanChat.say(server, b.name, "couldn't set up a chest, sorry");
                return 0;
            }
            final BlockPos c0 = chest;
            onServer(server, () -> { remember(bot.level(), c0); return null; }, null);
        }
        int total = 0;
        for (int round = 0; round < 4 && SurvivalBrain.canContinue(b); round++) {
            final BlockPos target = chest;
            if (onServer(server, () -> bot.position().distanceTo(Vec3.atCenterOf(target)) > 4.0, true)) {
                BotPathing.Options o = BotPathing.Options.walkOnly();
                o.allowPlace = true;
                o.timeoutTicks = 20 * 90;
                BotPathing.goToBlocking(bot, ActionPathfinder.near(target.getX(), target.getY(), target.getZ(), 2.5), o, 95_000L);
            }
            int[] r = onServer(server, () -> {
                Container c = HopperBlockEntity.getContainerAt(bot.level(), target);
                if (c == null || bot.position().distanceTo(Vec3.atCenterOf(target)) > 5.0) return new int[]{-1, 0};
                io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, Vec3.atCenterOf(target));
                Motions.swingArm(bot);
                return deposit(bot, c);
            }, new int[]{-1, 0});
            if (r[0] < 0) {
                if (talk) HumanChat.say(server, b.name, "can't get to the chest");
                break;
            }
            total += r[0];
            if (r[1] == 0) break;
            // chest is full: put another one next to it
            BlockPos more = newChest(server, bot, b, target);
            if (more == null) {
                if (talk) HumanChat.say(server, b.name, "chest's full and i can't make another one");
                break;
            }
            final BlockPos m = more;
            onServer(server, () -> { remember(bot.level(), m); return null; }, null);
            chest = more;
        }
        if (talk) {
            final int t = total;
            HumanChat.say(server, b.name, t > 0 ? HumanChat.pick("put " + t + " items in the chest", "stored everything", "ok, chest's got our stuff now")
                    : "nothing to put away");
        }
        return total;
    }

    /** The chests in the house. Server thread. */
    static List<BlockPos> homeChests(ServerLevel level, Home.Base home) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : home.chestSpots()) {
            if (isStorageBlock(SurvivalBrain.blockPath(level.getBlockState(p)))) out.add(p);
        }
        return out;
    }

    /** Goes home and fills the chests there, adding a chest inside when they're full. */
    private static int storeAtHome(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Home.Base home, boolean talk)
            throws InterruptedException {
        if (!Home.goHome(server, bot, b)) {
            backoffUntil = System.currentTimeMillis() + 5 * 60_000L;
            if (talk) HumanChat.say(server, b.name, "can't get back to the base right now");
            return 0;
        }
        int total = 0;
        for (int round = 0; round < 6 && SurvivalBrain.canContinue(b); round++) {
            int[] r = onServer(server, () -> {
                ServerLevel level = bot.level();
                int moved = 0, left = storable(bot);
                for (BlockPos p : homeChests(level, home)) {
                    if (left == 0) break;
                    Container c = HopperBlockEntity.getContainerAt(level, p);
                    if (c == null) continue;
                    io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, Vec3.atCenterOf(p));
                    int[] d = deposit(bot, c);
                    moved += d[0];
                    left = d[1];
                }
                if (moved > 0) Motions.swingArm(bot);
                return new int[]{moved, left};
            }, new int[]{0, 0});
            total += r[0];
            if (r[1] == 0) break;
            // full (or no chest left in the house): put another chest in
            BlockPos spot = onServer(server, () -> {
                for (BlockPos p : home.chestSpots()) if (Building.isFree(bot.level(), p) && Building.isSolid(bot.level(), p.below())) return p;
                return null;
            }, null);
            if (spot == null) {
                backoffUntil = System.currentTimeMillis() + 15 * 60_000L;
                if (talk || HumanReactions.cooldown(b.name + ":housefull", 20 * 60_000L)) {
                    HumanChat.say(server, b.name, HumanChat.pick("the house is full of chests lol, we need a bigger base",
                            "no room for more chests in the house"));
                }
                break;
            }
            if (onServer(server, () -> Building.firstItem(bot, "chest"::equals), null) == null) {
                Gathering.Craftable chest = Gathering.craftable("chest");
                if (chest == null || !Gathering.makeSure(server, bot, b, chest, 1)) {
                    backoffUntil = System.currentTimeMillis() + 10 * 60_000L;
                    break;
                }
                if (!Home.goHome(server, bot, b)) break;
            }
            boolean placed = onServer(server, () -> Building.placeAt(bot, spot, "chest")
                    && isStorageBlock(SurvivalBrain.blockPath(bot.level().getBlockState(spot))), false);
            if (!placed) {
                backoffUntil = System.currentTimeMillis() + 10 * 60_000L;
                break;
            }
            onServer(server, () -> { remember(bot.level(), spot); return null; }, null);
            SurvivalBrain.sleep(300);
        }
        if (talk) {
            final int t = total;
            HumanChat.say(server, b.name, t > 0 ? HumanChat.pick("put " + t + " items in the chest at home", "stored everything at the base",
                    "ok, it's all in the chests at home") : "nothing went in, hm");
        }
        return total;
    }

    /** After a mining job: put the haul away if that's switched on. */
    static void afterMining(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        if (!HumanConfig.get().autoStore || !SurvivalBrain.canContinue(b)) return;
        int used = onServer(server, () -> usedSlots(bot), 0);
        if (used < 6) return; // hardly anything
        storeAll(server, bot, b, false);
        SurvivalBrain.maybeSay(server, b, HumanChat.pick("put the stuff in the chest", "dropped the haul in our chest"), 0.7);
    }

    /** "get me 5 iron from the chest": takes it out, then hands it over. */
    /** "iron ingots" -> "iron ingot" (what people call it, singular). */
    public static String itemName(String what) {
        String q = what.toLowerCase(java.util.Locale.ROOT).replaceAll("\\bcobble ?stone\\b", "cobblestone")
                .replaceAll("\\b(that |which )?(you|u) (have|got|own)\\b", " ")
                .replaceAll("[^a-z0-9 ]", " ").trim().replaceAll("\\s+", " ");
        q = q.replaceAll("^(\\d+|a|an|some|the|my|our|all|any) ", "").trim();
        if (q.endsWith("s") && q.length() > 3 && !q.endsWith("ss")) q = q.substring(0, q.length() - 1);
        return q;
    }

    /** Which item ids a name like "iron", "wood", "cobblestone", "iron ingot" means. */
    public static Predicate<String> matcherFor(String what) {
        final String name = itemName(what);
        return p -> !p.isEmpty() && (p.replace('_', ' ').contains(name)
                || (name.equals("wood") && SurvivalBrain.isLogItem(p)) || (name.equals("iron") && p.equals("raw_iron"))
                || (name.equals("cobble") && SurvivalBrain.isCobbleItem(p)) || (name.equals("stone") && p.equals("cobblestone")));
    }

    static void fetchFor(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, String what, int count, UUID requester)
            throws InterruptedException {
        final String name = itemName(what);
        Predicate<String> match = matcherFor(what);
        Home.Base home = onServer(server, () -> Home.get(bot), null);
        if (home != null && onServer(server, () -> !homeChests(bot.level(), home).isEmpty(), false)) {
            if (!Home.goHome(server, bot, b)) { HumanChat.say(server, b.name, "can't get back to the base right now"); return; }
            int got = onServer(server, () -> {
                int left = count > 0 ? count : Integer.MAX_VALUE, n = 0;
                for (BlockPos p : homeChests(bot.level(), home)) {
                    Container c = HopperBlockEntity.getContainerAt(bot.level(), p);
                    if (c == null) continue;
                    int t = take(bot, c, match, left);
                    n += t;
                    left -= t;
                    if (left <= 0) break;
                }
                if (n > 0) Motions.swingArm(bot);
                return n;
            }, 0);
            if (got == 0) { HumanChat.say(server, b.name, "there's no " + name + " in the chests at home"); return; }
            if (requester != null) Gathering.deliver(server, bot, b, requester, match, name, got);
            return;
        }
        BlockPos chest = onServer(server, () -> nearest(bot, 96), null);
        if (chest == null) { HumanChat.say(server, b.name, "we don't have a chest yet"); return; }
        if (onServer(server, () -> bot.position().distanceTo(Vec3.atCenterOf(chest)) > 4.0, true)) {
            BotPathing.Options o = BotPathing.Options.walkOnly();
            o.timeoutTicks = 20 * 90;
            BotPathing.goToBlocking(bot, ActionPathfinder.near(chest.getX(), chest.getY(), chest.getZ(), 2.5), o, 95_000L);
        }
        int got = onServer(server, () -> {
            Container c = HopperBlockEntity.getContainerAt(bot.level(), chest);
            if (c == null || bot.position().distanceTo(Vec3.atCenterOf(chest)) > 5.0) return -1;
            io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, Vec3.atCenterOf(chest));
            Motions.swingArm(bot);
            int left = count > 0 ? count : Integer.MAX_VALUE, n = 0;
            for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !match.test(SurvivalBrain.itemPath(s))) continue;
                int take = Math.min(left, s.getCount());
                ItemStack part = s.copy();
                part.setCount(take);
                s.shrink(take);
                bot.getInventory().add(part);
                if (!part.isEmpty()) { insert(c, part); break; } // bot is full: put it back
                n += take;
                left -= take;
            }
            c.setChanged();
            return n;
        }, -1);
        if (got < 0) { HumanChat.say(server, b.name, "can't get to the chest"); return; }
        if (got == 0) { HumanChat.say(server, b.name, "there's no " + name + " in the chest"); return; }
        if (requester != null) Gathering.deliver(server, bot, b, requester, match, name, got);
    }

    // ------------------------------------------------------------------------
    // What people say
    // ------------------------------------------------------------------------

    static final java.util.regex.Pattern CHEST_HERE = java.util.regex.Pattern.compile(
            "\\b((use|this is|that'?s|that is) (this|that|the|our|my) (chest|barrel)( for (storage|our stuff|stuff|items))?"
            + "|(this|that) (chest|barrel) is (ours|our storage|for storage|storage|for our stuff)"
            + "|(store|keep|put) (our |the |your )?(stuff|things|items|it|them) (here|in this|in that|in there))\\b");
    static final java.util.regex.Pattern STORE = java.util.regex.Pattern.compile(
            "\\b(store|stash|deposit|unload|put away|put (all )?(your|ur|the|that|those|our) (stuff|items|things|loot|ores?|blocks|haul)"
            + "|dump (your|ur|the|all|everything|it|that|those|stuff)|empty (your|ur) (inv\\w*|pockets|bags?))\\b");
    static final java.util.regex.Pattern TAKE = java.util.regex.Pattern.compile(
            "\\b(?:get|grab|take|bring|fetch|give)(?: me| us)?(?: (\\d+|a stack of|a few|an|a|some|all(?: the)?|the))? (.+?) "
            + "(?:from|out of|outta|in) (?:the |our |my |your |ur )?(?:storage )?(?:chests?|storage|box|barrels?)\\b");

    public record Take(String what, int count) {}

    public static Take parseTake(String m) {
        java.util.regex.Matcher tm = TAKE.matcher(m);
        if (!tm.find()) return null;
        String what = tm.group(2).replaceAll("^(some|the|a|an|my|our|all( the)?|stuff|items) ", "").trim();
        if (what.isEmpty() || what.matches("(stuff|things|items|everything|it|them)")) return null;
        int count = 0;
        String c = tm.group(1);
        if (c != null && c.matches("\\d+")) count = Math.min(9999, Integer.parseInt(c));
        else if (c != null && c.startsWith("a stack")) count = 64;
        else if (c != null && (c.equals("a") || c.equals("an"))) count = 1;
        else if (c != null && c.equals("a few")) count = 4;
        java.util.regex.Matcher lead = java.util.regex.Pattern.compile("^(\\d+) (.+)$").matcher(what);
        if (lead.find()) { count = Integer.parseInt(lead.group(1)); what = lead.group(2); }
        return new Take(what, count);
    }

    /** "store your stuff" / "take 5 iron from the chest" as a job, or null. */
    static MiningSkills.Request request(String text, UUID requester) {
        if (text == null) return null;
        String m = text.toLowerCase(java.util.Locale.ROOT);
        Take t = parseTake(m);
        if (t != null) {
            String ack = HumanChat.pick("ok, grabbing " + t.what() + " from the chest", "sure, one sec", "on it");
            return new MiningSkills.Request("get " + t.what() + " from the chest", ack,
                    (server, bot, b) -> fetchFor(server, bot, b, t.what(), t.count(), requester));
        }
        if (STORE.matcher(m).find() || m.matches(".*\\b(store|put|stash) (items|stuff|things|everything) in (a |the |our )?chests?\\b.*")) {
            String ack = HumanChat.pick("ok, putting my stuff away", "sure, gonna empty my inventory into the chest", "k, storing it");
            return new MiningSkills.Request("store items", ack, (server, bot, b) -> storeAll(server, bot, b, true));
        }
        return null;
    }

    /** Takes up to {@code max} matching items out of {@code c} into the bot's pockets. Server thread. */
    private static int take(ServerPlayer bot, Container c, Predicate<String> match, int max) {
        int left = max, n = 0;
        for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !match.test(SurvivalBrain.itemPath(s))) continue;
            int t = Math.min(left, s.getCount());
            ItemStack part = s.copy();
            part.setCount(t);
            s.shrink(t);
            bot.getInventory().add(part);
            int added = t - part.getCount();
            n += added;
            left -= added;
            if (!part.isEmpty()) { insert(c, part); break; } // bot is full: put the rest back
        }
        c.setChanged();
        return n;
    }

    // ------------------------------------------------------------------------
    // What's in the chests: it remembers, and uses it before going out to gather
    // ------------------------------------------------------------------------

    /** Last thing seen in each chest ("dim|x|y|z" -> item -> count). */
    private static final Map<String, Map<String, Integer>> SEEN = new java.util.concurrent.ConcurrentHashMap<>();

    private static String key(ServerLevel level, BlockPos p) {
        return dim(level) + "|" + p.getX() + "|" + p.getY() + "|" + p.getZ();
    }

    /** Remembers what's in a chest right now. Server thread. */
    static void note(ServerLevel level, BlockPos p, Container c) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty()) m.merge(SurvivalBrain.itemPath(s), s.getCount(), Integer::sum);
        }
        SEEN.put(key(level, p), m);
    }

    /** Every storage chest it knows about in this dimension, the ones at home first. Server thread. */
    static List<BlockPos> allChests(ServerLevel level) {
        java.util.LinkedHashSet<BlockPos> out = new java.util.LinkedHashSet<>();
        for (Home.Base h : Home.allIn(level)) out.addAll(homeChests(level, h));
        for (BlockPos p : known(level)) {
            if (level.isLoaded(p) && !isStorageBlock(SurvivalBrain.blockPath(level.getBlockState(p)))) continue;
            out.add(p);
        }
        // a double chest shows up as both of its halves: keep one
        List<BlockPos> list = new ArrayList<>();
        for (BlockPos p : out) if (!secondHalf(level, p)) list.add(p);
        return list;
    }

    /** The "other" half of a double chest (the half with the lower x/z counts for both). Server thread. */
    private static boolean secondHalf(ServerLevel level, BlockPos p) {
        if (!level.isLoaded(p)) return false;
        try {
            net.minecraft.world.level.block.state.BlockState s = level.getBlockState(p);
            String path = SurvivalBrain.blockPath(s);
            if (!path.equals("chest") && !path.equals("trapped_chest")) return false;
            if (s.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
                    == net.minecraft.world.level.block.state.properties.ChestType.SINGLE) return false;
            BlockPos partner = p.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(s));
            // keep the half with the smaller x (then z); the other one is the "second half"
            return partner.getX() < p.getX() || (partner.getX() == p.getX() && partner.getZ() < p.getZ());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Matching items in one chest: looked at if it's loaded, otherwise what it remembers. Server thread. */
    private static int stockIn(ServerLevel level, BlockPos p, Predicate<String> test,
                               java.util.Set<Container> counted) {
        if (level.isLoaded(p)) {
            Container c = HopperBlockEntity.getContainerAt(level, p);
            if (c == null) return 0;
            note(level, p, c);
            if (!counted.add(c)) return 0; // the other half of a double chest
        }
        Map<String, Integer> m = SEEN.get(key(level, p));
        if (m == null) return 0;
        int n = 0;
        for (Map.Entry<String, Integer> e : m.entrySet()) if (test.test(e.getKey())) n += e.getValue();
        return n;
    }

    /** How many matching items are in all the chests together. Server thread. */
    static int stockOf(ServerLevel level, Predicate<String> test) {
        java.util.Set<Container> counted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int n = 0;
        for (BlockPos p : allChests(level)) n += stockIn(level, p, test, counted);
        return n;
    }

    /**
     * Takes up to {@code want} matching items out of the storage chests, walking over to them,
     * nearest first. Returns how many it got (0 straight away if no chest has any). Job thread.
     */
    static int withdraw(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Predicate<String> test, int want,
                        String label) throws InterruptedException {
        if (want <= 0) return 0;
        List<BlockPos> chests = onServer(server, () -> {
            ServerLevel level = bot.level();
            java.util.Set<Container> counted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            List<BlockPos> out = new ArrayList<>();
            for (BlockPos p : allChests(level)) {
                if (p.distSqr(bot.blockPosition()) > 200 * 200) continue;
                if (stockIn(level, p, test, counted) > 0) out.add(p);
            }
            out.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(bot.blockPosition())));
            return out;
        }, List.of());
        if (chests.isEmpty()) return 0;
        if (label != null) {
            SurvivalBrain.maybeSay(server, b, HumanChat.pick("there's " + label + " in the chest, grabbing that",
                    "got some " + label + " stored, one sec", "getting the " + label + " from our chest"), 0.8);
        }
        onServer(server, () -> makeRoom(bot, 3), 0);
        Home.Base home = onServer(server, () -> Home.get(bot), null);
        int got = 0;
        for (BlockPos p : chests) {
            if (got >= want || !SurvivalBrain.canContinue(b)) break;
            boolean near = onServer(server, () -> bot.position().distanceTo(Vec3.atCenterOf(p)) <= 4.5, false);
            if (!near) {
                if (home != null && home.chestSpots().contains(p)) {
                    if (!Home.goHome(server, bot, b)) continue;
                } else {
                    BotPathing.Options o = BotPathing.Options.full();
                    o.timeoutTicks = 20 * 120;
                    BotPathing.goToBlocking(bot, ActionPathfinder.near(p.getX(), p.getY(), p.getZ(), 2.5), o, 125_000L);
                }
            }
            final int need = want - got;
            int t = onServer(server, () -> {
                ServerLevel level = bot.level();
                Container c = HopperBlockEntity.getContainerAt(level, p);
                if (c == null || bot.position().distanceTo(Vec3.atCenterOf(p)) > 5.5) return -1;
                io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, Vec3.atCenterOf(p));
                int n = take(bot, c, test, need);
                note(level, p, c);
                if (n > 0) Motions.swingArm(bot);
                return n;
            }, -1);
            if (t > 0) got += t;
            if (t >= 0) SurvivalBrain.sleep(250);
        }
        return got;
    }

    /** "what's in the chest": from memory (and a look if it's nearby). Server thread. */
    static String describe(ServerPlayer bot) {
        ServerLevel level = bot.level();
        Map<String, Integer> all = new HashMap<>();
        java.util.Set<Container> counted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<BlockPos> chests = allChests(level);
        for (BlockPos p : chests) {
            if (level.isLoaded(p)) {
                Container c = HopperBlockEntity.getContainerAt(level, p);
                if (c == null || !counted.add(c)) continue;
                note(level, p, c);
            }
            Map<String, Integer> m = SEEN.get(key(level, p));
            if (m != null) m.forEach((k, v) -> all.merge(k, v, Integer::sum));
        }
        if (chests.isEmpty()) return "we don't have any chests yet";
        if (all.isEmpty()) return "the chests are empty";
        List<Map.Entry<String, Integer>> e = new ArrayList<>(all.entrySet());
        e.sort((x, y) -> y.getValue() - x.getValue());
        StringBuilder sb = new StringBuilder("chests have ");
        for (int i = 0; i < Math.min(8, e.size()); i++) {
            if (i > 0) sb.append(i == Math.min(8, e.size()) - 1 ? " and " : ", ");
            sb.append(e.get(i).getValue()).append(' ').append(e.get(i).getKey().replace('_', ' '));
        }
        if (e.size() > 8) sb.append(" and more");
        return sb.toString();
    }

    static List<BlockPos> known(ServerLevel level) {
        load();
        String d = dim(level);
        List<BlockPos> out = new ArrayList<>();
        for (Spot s : CHESTS) if (s.dim().equals(d)) out.add(s.pos());
        return out;
    }
}
