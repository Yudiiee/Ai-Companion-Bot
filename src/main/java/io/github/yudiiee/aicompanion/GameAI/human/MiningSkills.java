package io.github.yudiiee.aicompanion.GameAI.human;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import io.github.yudiiee.aicompanion.Entity.AutoFaceEntity;
import io.github.yudiiee.aicompanion.Entity.LookController;
import io.github.yudiiee.aicompanion.PlayerUtils.FoodConsumptionTool;
import io.github.yudiiee.aicompanion.PlayerUtils.MiningResult;
import io.github.yudiiee.aicompanion.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.blockPath;
import static io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.onServer;

/**
 * "Mine 10 iron", "get me some sand", "strip mine at y -58".
 *
 * <p>Works the way players (and the mindcraft bots) do it: work out which blocks the
 * request means, look around for the nearest one, walk there, and if it's buried dig a
 * 1x2 tunnel / staircase to it. Every block dug is checked first: nothing next to
 * lava or water, nothing unbreakable, nothing in a village or somebody's build.
 * Strip mining digs a staircase down to the chosen level, then a main tunnel with side
 * branches, mining any ores that show up in the walls.
 *
 * <p>Everything here runs on the bot's brain thread; world access goes through
 * {@link SurvivalBrain#onServer}.
 */
public final class MiningSkills {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-mining");

    /** Feet-to-block-centre distance we mine from ({@link MiningTool} allows 5). */
    static final double REACH = 4.6;
    static final int LOWEST_Y = -59;
    static final int DEFAULT_STRIP_Y = -58;

    private static final int CLEAR = 0, BREAK = 1, UNSAFE = 2;
    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private MiningSkills() {}

    // ------------------------------------------------------------------------
    // What does the player want?
    // ------------------------------------------------------------------------

    /**
     * @param label        what to call it in chat
     * @param matches      block id path test
     * @param logs         tree logs (natural trees only, no tunnelling)
     * @param natural      natural terrain block: skip anything close to a build
     * @param tier         pickaxe tier needed (0 none, 1 wood, 2 stone, 3 iron, 4 diamond)
     * @param defaultCount how many when the player doesn't say
     * @param stripY       level to branch-mine at when none is in sight (ores), or null
     */
    public record Target(String label, Predicate<String> matches, boolean logs, boolean natural, int tier,
                  int defaultCount, Integer stripY) {
        boolean test(String path) { return path != null && !path.isEmpty() && matches.test(path); }
    }

    private static volatile List<String> blockIds;

    static List<String> blockIds() {
        List<String> ids = blockIds;
        if (ids != null) return ids;
        List<String> out = new ArrayList<>();
        try {
            for (Object key : BuiltInRegistries.BLOCK.keySet()) {
                if (key instanceof Identifier id) out.add(id.getPath());
            }
        } catch (Throwable ignored) {
            // registry not available (unit tests)
        }
        if (!out.isEmpty()) blockIds = out;
        return out;
    }

    private static final String[][] ORES = {
            // name, pickaxe tier, strip-mine y: what the ore index (OreBook) says wins, these are if it's missing
            // (emerald depends on where the bot is: see MineHub.levelFor)
            {"coal", "1", "96"}, {"copper", "2", "48"}, {"iron", "2", "16"}, {"lapis", "2", "0"},
            {"gold", "3", "-16"}, {"redstone", "3", "-58"}, {"diamond", "3", "-58"}, {"emerald", "3", "232"},
    };

    static boolean isNaturalLogId(String p) {
        return (p.endsWith("_log") || p.endsWith("_stem")) && !p.startsWith("stripped_");
    }

    static boolean isOreId(String p) {
        return p.endsWith("_ore") || p.equals("ancient_debris");
    }

    /** Pickaxe tier needed to get drops from a block. */
    public static int tierFor(String p) {
        if (p.equals("ancient_debris") || p.contains("obsidian") || p.equals("respawn_anchor")) return 4;
        if (p.startsWith("nether_")) return p.endsWith("_ore") ? 1 : 0;
        if (p.endsWith("_ore")) return SurvivalBrain.requiredPickFor(p);
        if (p.equals("iron_block") || p.equals("raw_iron_block") || p.equals("lapis_block")) return 2;
        if (p.equals("diamond_block") || p.equals("gold_block") || p.equals("emerald_block")
                || p.equals("redstone_block") || p.equals("raw_gold_block")) return 3;
        if (p.equals("glowstone") || p.contains("redstone_") || p.endsWith("_powder")) return 0;
        if (p.contains("stone") || p.contains("deepslate") || p.contains("terracotta") || p.contains("bricks")
                || p.endsWith("_concrete") || p.equals("andesite") || p.equals("diorite") || p.equals("granite")
                || p.equals("tuff") || p.equals("calcite") || p.contains("dripstone") || p.equals("netherrack")
                || p.contains("basalt") || p.contains("prismarine") || p.contains("amethyst") || p.contains("copper")
                || p.equals("coal_block") || p.equals("magma_block") || p.contains("nylium")) return 1;
        return 0;
    }

    private static Target logs(String label, Predicate<String> test) {
        return new Target(label, test, true, true, 0, 16, null);
    }

    /** Turns "iron", "diamonds", "oak logs", "sand", "cobble"... into a block target, or null. */
    public static Target resolve(String phrase) {
        if (phrase == null) return null;
        String q = phrase.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_ ]", " ")
                .replaceAll("\\b(some|more|the|a|an|of|me|us|few|couple|bunch|lot|lots|stack|stacks|piece|pieces|"
                        + "block|blocks|pls|please|for|too|now|nearby|here|around|there|real|quick|asap|yourself|any)\\b", " ")
                .trim().replaceAll("\\s+", " ");
        if (q.isEmpty() || q.length() > 32) return null;

        // wood
        if (q.matches("(wood|woods|log|logs|tree|trees|timber|lumber)")) return logs("wood", MiningSkills::isNaturalLogId);
        Matcher wm = Pattern.compile("^(oak|spruce|birch|jungle|acacia|dark oak|mangrove|cherry|pale oak|crimson|warped)"
                + "( (wood|woods|log|logs|tree|trees|stem|stems))?$").matcher(q);
        if (wm.find()) {
            String sp = wm.group(1).replace(' ', '_');
            String id = sp.equals("crimson") || sp.equals("warped") ? sp + "_stem" : sp + "_log";
            return logs(wm.group(1) + " logs", id::equals);
        }
        // everyday terrain
        if (q.matches("(stone|stones|cobble|cobbles|cobblestone|cobblestones|rock|rocks)"))
            return new Target("stone", "stone"::equals, false, true, 1, 16, null);
        if (q.matches("(deepslate|cobbled deepslate)"))
            return new Target("deepslate", "deepslate"::equals, false, true, 1, 16, null);
        if (q.matches("(dirt|grass|grass block|grass blocks|soil)"))
            return new Target("dirt", p -> p.equals("dirt") || p.equals("grass_block"), false, true, 0, 16, null);
        if (q.matches("(sand|sands)")) return new Target("sand", "sand"::equals, false, true, 0, 16, null);
        // ores
        Matcher om = Pattern.compile("^(raw )?(coal|copper|iron|lapis|lapis lazuli|gold|redstone|redstone dust|diamonds?|emeralds?)"
                + "( ores?| ingots?)?$").matcher(q);
        if (om.find()) {
            String name = om.group(2).replaceAll(" (lazuli|dust)$", "").replaceAll("^(diamond|emerald)s$", "$1");
            for (String[] o : ORES) {
                if (!o[0].equals(name)) continue;
                String ore = name + "_ore";
                String deep = "deepslate_" + ore;
                Integer y = o[2].isEmpty() ? null : OreBook.bestY(name, Integer.parseInt(o[2]));
                return new Target(name, p -> p.equals(ore) || p.equals(deep), false, false,
                        OreBook.tier(name, Integer.parseInt(o[1])), 6, y);
            }
        }
        if (q.matches("(ore|ores|any ore|any ores)"))
            return new Target("ores", p -> p.endsWith("_ore") && !p.startsWith("nether_"), false, false, 1, 8, null);
        if (q.matches("(quartz|nether quartz|quartz ore)"))
            return new Target("quartz", "nether_quartz_ore"::equals, false, false, 1, 8, null);
        if (q.matches("(ancient debris|debris|netherite|netherite scrap|netherite scraps)"))
            return new Target("ancient debris", "ancient_debris"::equals, false, false, OreBook.tier("ancient debris", 4), 2,
                    OreBook.bestY("ancient debris", 15));

        // anything else: exact block id, singular, or a small family ("leaves", "terracotta")
        List<String> ids = blockIds();
        if (ids.isEmpty()) return null;
        String id = q.replace(' ', '_');
        List<String> cands = new ArrayList<>(List.of(id));
        if (id.endsWith("es")) cands.add(id.substring(0, id.length() - 2));
        if (id.endsWith("s")) cands.add(id.substring(0, id.length() - 1));
        for (String c : cands) {
            if (ids.contains(c)) {
                if (Protection.isManMade(c)) return null; // that's somebody's build, not a resource
                return new Target(q, c::equals, false, true, tierFor(c), 8, null);
            }
        }
        for (String c : cands) {
            if (c.length() < 4) continue;
            List<String> fam = new ArrayList<>();
            for (String p : ids) {
                if (p.startsWith("potted_") || p.contains("_wall_") || p.startsWith("infested_")) continue;
                if (p.endsWith("_" + c) || p.startsWith(c + "_")) fam.add(p);
            }
            if (!fam.isEmpty() && fam.size() <= 24) {
                if (fam.stream().anyMatch(Protection::isManMade)) return null;
                int tier = fam.stream().mapToInt(MiningSkills::tierFor).min().orElse(0);
                return new Target(q, fam::contains, false, true, tier, 8, null);
            }
        }
        return null;
    }

    private static final List<String> SHOVEL_BLOCKS = List.of("dirt", "grass_block", "sand", "red_sand", "gravel",
            "clay", "snow", "snow_block", "soul_sand", "soul_soil", "mud", "mycelium", "podzol", "coarse_dirt", "rooted_dirt");

    /** The tool a player would use for this: "axe", "shovel", or null (pickaxes are handled separately). */
    public static String toolKindFor(Target t) {
        if (t.logs()) return "axe";
        for (String id : SHOVEL_BLOCKS) if (t.test(id)) return "shovel";
        return null;
    }

    static int parseCount(String word, Target t) {
        if (word == null || word.isBlank()) return t.defaultCount();
        String w = word.trim();
        int n;
        if (w.matches("\\d+")) n = Integer.parseInt(w);
        else if (w.matches("(a|an|one)")) n = 1;
        else if (w.equals("two") || w.startsWith("a couple")) n = 2;
        else if (w.equals("three") || w.equals("a few")) n = 3;
        else if (w.equals("four")) n = 4;
        else if (w.equals("five")) n = 5;
        else if (w.equals("six")) n = 6;
        else if (w.equals("seven")) n = 7;
        else if (w.equals("eight")) n = 8;
        else if (w.equals("nine")) n = 9;
        else if (w.equals("ten")) n = 10;
        else if (w.contains("stack")) n = 64;
        else if (w.contains("lot") || w.contains("bunch")) n = 32;
        else n = t.defaultCount();
        return Math.max(1, Math.min(n, 256));
    }

    // ------------------------------------------------------------------------
    // Chat / plan requests
    // ------------------------------------------------------------------------

    /** A job parsed from chat or from a plan step. */
    public record Request(String label, String ack, SurvivalBrain.Job job) {}

    private static final String PREFIX = "^(?:(?:can|could|would|will) (?:you|u) |(?:hey|yo|bro|dude|ok|okay|now|also|and|pls|please|go|lets|let'?s) )*";
    private static final Pattern COLLECT = Pattern.compile(PREFIX
            + "(?:mine|collect|get|gather|dig(?: up| out| for)?|grab|harvest|chop(?: down)?|cut(?: down)?|find|look for|search for|go for|fetch|bring me|bring|farm|make|craft|build|smelt|cook|bake|roast|i need|we need|i want)"
            + "(?: me| us)?"
            + "(?: (a stack of|a stack|stacks of|stack of|a lot of|lots of|a bunch of|a few|a couple(?: of)?|\\d+|an|a|one|two|three|four|five|six|seven|eight|nine|ten))?"
            + "\\s+(.+?)"
            + "(?: (?:pls|please|for me|for us|now|too|nearby|around here|real quick|asap))*[!.?\\s]*$");
    private static final Pattern ORE_AT_Y = Pattern.compile(
            // (the greedy .* picks the last verb: "get back to mining diamonds at y -58" -> "diamonds")
            ".*\\b(?:mine|mining|dig|digging|get|getting|find|finding|look for|search for|go for)\\b\\s+(?:for\\s+|some\\s+|me\\s+|us\\s+)?(?:\\d+\\s+)?"
            + "([a-z_ ]+?)\\s+(?:at|on|down at|around)\\s+(?:y|lvl|level|layer)\\b");
    private static final Pattern STRIP = Pattern.compile("\\b(strip ?min(?:e|ing)|branch ?min(?:e|ing)|strip ?mine)\\b");
    private static final Pattern STRIP_Y = Pattern.compile("\\b(?:y|lvl|level|layer)\\s*[=:]?\\s*(-?\\d+)\\b");
    private static final Pattern STRIP_LEN = Pattern.compile("\\b(\\d+)\\s*(?:blocks?|long|b\\b)");
    private static final Pattern STRIP_FOR = Pattern.compile("\\bfor (.+?)(?: at .*| to .*| on .*| (?:y|level)\\b.*)?$");

    private static String clean(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace(',', ' ').replaceAll("\\s+", " ").trim();
    }

    public static Request parseCollect(String text) {
        return parseCollect(text, null);
    }

    private static final Pattern FOR_ME = Pattern.compile("\\b(me|us|bring|give|i need|we need|i want|for me|for us)\\b");

    /**
     * "get me 10 iron", "mine some stone", "i need 32 planks", "make a chest".
     * {@code requester}: the player asking; they get the stuff brought to them.
     */
    public static Request parseCollect(String text, UUID requester) {
        String m = clean(text);
        if (m.isEmpty() || STRIP.matcher(m).find()) return null;
        Matcher cm = COLLECT.matcher(m);
        if (!cm.find()) return null;
        String phrase = cm.group(2);
        // smelting: "smelt 4 iron", "cook the beef", "get me 5 iron ingots"
        if (m.matches(".*\\b(smelt|cook|bake|roast)\\b.*") || phrase.matches(".*\\bingots?\\b.*")) {
            Smelting.Recipe r = Smelting.recipeFor(phrase);
            if (r != null) {
                int want = cm.group(1) == null ? 0 : parseCount(cm.group(1), new Target(r.label(), p -> false, false, false, 0, 1, null));
                UUID to = requester;
                String what = (want > 0 ? want + " " : "") + r.label();
                String ack = HumanChat.pick("ok, gonna smelt " + what, "sure, " + what + " coming up", "on it");
                return new Request("smelt " + what, ack, (server, bot, b) -> Smelting.smeltFor(server, bot, b, r, want, to));
            }
        }
        boolean crafting = m.matches(".*\\b(make|craft|build)\\b.*");
        Target t = crafting ? null : resolve(phrase);
        if (t == null) {
            Gathering.Craftable c = Gathering.craftable(phrase);
            if (c == null) return null;
            int want = parseCraftCount(cm.group(1), c);
            UUID to = requester;
            String what = want + " " + c.label();
            String ack = HumanChat.pick("ok, making " + what, "sure, one sec", "on it", "bet");
            return new Request("craft " + what, ack, (server, bot, b) -> {
                SurvivalBrain.keep(bot, c.matches());
                try {
                    Gathering.craftFor(server, bot, b, c, want, to);
                } finally {
                    SurvivalBrain.keep(bot, null);
                }
            });
        }
        int n = parseCount(cm.group(1), t);
        if (t.logs() && phrase.matches(".*\\btrees?\\b.*") && cm.group(1) != null) n = Math.min(64, n * 5);
        final int count = n;
        final UUID deliverTo = requester != null && FOR_ME.matcher(m).find() ? requester : null;
        String what = count + " " + t.label();
        String ack = HumanChat.pick("ok, getting " + what, "sure, " + what + " coming up", "on it, " + what,
                "bet, gonna get " + what);
        return new Request("collect " + what, ack, (server, bot, b) -> {
            if (deliverTo != null) SurvivalBrain.keep(bot, Gathering.itemsFor(t)); // don't craft it away
            try {
                int toMine = count;
                if (deliverTo != null) {
                    // for someone: what's in the pockets and the chests counts before going out to mine
                    int have = onServer(server, () -> Gathering.countOf(bot, Gathering.itemsFor(t)), 0);
                    int fromChest = Storage.withdraw(server, bot, b, Gathering.itemsFor(t), count - have, t.label());
                    toMine = count - have - fromChest;
                }
                if (toMine > 0) collect(server, bot, b, t, toMine, deliverTo != null);
                if (deliverTo != null && SurvivalBrain.canContinue(b)) {
                    Gathering.deliver(server, bot, b, deliverTo, Gathering.itemsFor(t), t.label(),
                            Gathering.oneToOne(t) ? count : 0);
                } else if (deliverTo == null && SurvivalBrain.canContinue(b)) {
                    Surface.backUp(server, bot, b, null);
                    Storage.afterMining(server, bot, b);
                }
            } finally {
                SurvivalBrain.keep(bot, null);
            }
        });
    }

    private static int parseCraftCount(String word, Gathering.Craftable c) {
        if (word == null || word.isBlank()) return c.perCraft() > 1 && !c.item().equals("torch") ? c.perCraft() * 4 : (c.item().equals("torch") ? 16 : 1);
        MiningSkills.Target dummy = new Target(c.label(), p -> false, false, false, 0, 1, null);
        return parseCount(word, dummy);
    }

    public static Request parseStrip(String text) {
        return parseStrip(text, null);
    }

    /** @param yawHint the direction the asking player faces: the tunnel goes that way. */
    public static Request parseStrip(String text, Float yawHint) {
        String m = clean(text);
        Target want = null;
        if (!STRIP.matcher(m).find()) {
            // "mine diamonds at y -58", "get back to mining diamonds at y level -58": strip mining too
            Matcher am = ORE_AT_Y.matcher(m);
            if (!am.find()) return null;
            want = resolve(am.group(1));
            if (want == null || want.logs()) return null;
        }
        Matcher fm = STRIP_FOR.matcher(m);
        if (want == null && fm.find()) want = resolve(fm.group(1));
        int y = MineHub.defaultLevel(want);
        Integer yOverride = null;
        Matcher ym = STRIP_Y.matcher(m);
        if (ym.find()) yOverride = y = Integer.parseInt(ym.group(1));
        y = Math.max(LOWEST_Y, Math.min(300, y));
        final Integer fOverride = yOverride == null ? null : y;
        int len = TRIP;
        Matcher lm = STRIP_LEN.matcher(m);
        if (lm.find()) len = Math.max(4, Math.min(128, Integer.parseInt(lm.group(1))));
        final int fy = y, flen = len;
        final Target fwant = want;
        final int wantCount = want == null ? 0 : want.defaultCount();
        // coal and emerald levels depend on where the bot is (hills, mountains): don't promise a number
        boolean depends = fOverride == null && fwant != null && (fwant.label().equals("coal") || fwant.label().equals("emerald"));
        String ack = depends ? HumanChat.pick("ok, going strip mining for " + fwant.label(), "sure, gonna tunnel for " + fwant.label())
                : HumanChat.pick("ok, strip mining at y " + y, "sure, gonna dig down to y " + y + " and branch mine",
                "alright, going down to " + y + ". this'll take a bit");
        return new Request(depends ? "strip mine for " + fwant.label() : "strip mine at y " + y, ack, (server, bot, b) -> {
            stripMine(server, bot, b, fOverride, flen, fwant, wantCount, yawHint);
            if (!SurvivalBrain.canContinue(b)) return;
            Surface.backUp(server, bot, b, null);
            Storage.afterMining(server, bot, b);
        });
    }

    // ------------------------------------------------------------------------
    // Collect N blocks
    // ------------------------------------------------------------------------

    static void collect(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Target t, int count)
            throws InterruptedException {
        collect(server, bot, b, t, count, false);
    }

    /** @param quiet don't announce the total at the end (someone else will, e.g. when handing it over) */
    static void collect(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Target t, int count, boolean quiet)
            throws InterruptedException {
        // stone and ore come out of the mine, never just anywhere
        if (onServer(server, () -> MineHub.underground(bot, t), false)) {
            MineHub.mineFor(server, bot, b, t, count, quiet);
            return;
        }
        long end = System.currentTimeMillis() + 20 * 60_000L;
        int got = 0, misses = 0;
        if (t.tier() > 0 && !ensurePickaxe(server, bot, b, t.tier())) return;
        // gather around where it started, like a player: not tree after tree off into the distance
        BlockPos origin = onServer(server, bot::blockPosition, null);
        int farTrips = 0;
        String toolKind = toolKindFor(t);
        boolean announced = false;
        int lastToolCheck = -1;
        while (got < count && SurvivalBrain.canContinue(b) && System.currentTimeMillis() < end) {
            if (!upkeep(server, bot, b)) return;
            if (toolKind != null && got / 8 != lastToolCheck && count - got >= 3) {
                // like a player: grab the right tool for the job (and replace it when it breaks)
                lastToolCheck = got / 8;
                String made = onServer(server, () -> SurvivalBrain.craftTool(bot, toolKind), null);
                if (made != null) maybeSay(server, b, made);
            }
            if (pocketsFull(server, bot, b)) {
                say(server, b, HumanChat.pick("my inventory's full, got " + got + " " + t.label(), "no space left in my inventory"));
                return;
            }
            int tier = pickTier(server, bot);
            BlockPos found = onServer(server, () -> findTarget(bot, b, t, Protection.scan(bot, 48), tier), null);
            // too far from where it started (it has drifted, e.g. chasing trees across a lake): stop there
            BlockPos cand = found != null && origin != null && t.stripY() == null
                    && found.distSqr(origin) > 64 * 64 ? null : found;
            // ores: only ones lying in the open close by; otherwise it's the mine (not random holes)
            boolean farOre = cand != null && t.stripY() != null && onServer(server, () -> !SurvivalBrain.exposed(bot.level(), cand)
                    || cand.distSqr(bot.blockPosition()) > 16 * 16, true);
            final BlockPos target = farOre ? null : cand;
            if (target == null && t.logs() && farTrips < 5) {
                // none in this neighbourhood: go and find some, like a player would (other species too)
                farTrips++;
                if (farTrips == 1) say(server, b, HumanChat.pick("no " + t.label() + " around here, gonna go look further out",
                        "none nearby, heading out to find some"));
                if (travelToTrees(server, bot, b, t)) {
                    origin = onServer(server, bot::blockPosition, null);
                    continue;
                }
            }
            if (target == null) {
                if (t.stripY() != null) {
                    say(server, b, HumanChat.pick("no " + t.label() + " in sight, heading down the mine for some",
                            "gonna go strip mine for " + t.label()));
                    stripMine(server, bot, b, null, TRIP, t, count - got, null);
                    return;
                }
                say(server, b, got == 0
                        ? HumanChat.pick("can't find any " + t.label() + " nearby", "i don't see any " + t.label() + " around here")
                        : "that's all the " + t.label() + " i can find around here, got " + got);
                return;
            }
            if (!announced) {
                String path = onServer(server, () -> blockPath(bot.level().getBlockState(target)), "");
                announceFind(server, b, path);
                announced = true;
            }
            if (t.logs()) {
                // chop the whole tree, not just one log of it
                int n = fellTree(server, bot, b, target);
                if (n == 0) { blacklist(b, target); if (++misses >= 8) { say(server, b, "can't get to any trees, sorry"); return; } }
                else { got += n; misses = 0; }
                continue;
            }
            if (!reach(server, bot, b, target, t.logs())) {
                blacklist(b, target);
                if (++misses >= 8) {
                    say(server, b, HumanChat.pick("can't get to any " + t.label() + ", sorry", "couldn't reach the " + t.label()));
                    return;
                }
                continue;
            }
            if (dig(server, bot, b, target, false, 0)) {
                got++;
                misses = 0;
                // grab what it dropped before moving on (ores pop out and can land a block away)
                SurvivalBrain.pickUpNearbyItems(server, bot, 5, true);
            } else {
                blacklist(b, target);
                misses++;
            }
        }
        SurvivalBrain.pickUpNearbyItems(server, bot, 6, true);
        if (got >= count && !quiet) {
            say(server, b, HumanChat.pick("got " + got + " " + t.label(), "ok that's " + got + " " + t.label(),
                    "done, " + got + " " + t.label()));
        }
    }

    private static final String[] SPECIES = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak"};

    /** Leaf blocks of the species this target wants (all of them for plain "wood"). */
    static java.util.Set<String> wantedLeaves(Target t) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String sp : SPECIES) if (t.test(sp + "_log")) out.add(sp + "_leaves");
        return out;
    }

    /**
     * The nearest tree of the wanted species beyond the usual search, found by its leaves in
     * rings out to 160 blocks (loaded chunks only). Returns a standing spot at its foot, or null.
     */
    static BlockPos findTreesFar(MinecraftServer server, ServerPlayer bot, Target t) {
        java.util.Set<String> leaves = wantedLeaves(t);
        if (leaves.isEmpty()) return null;
        for (int r = 40; r < 160; r += 20) {
            final int r0 = r, r1 = r + 20;
            BlockPos hit = onServer(server, () -> {
                ServerLevel level = bot.level();
                BlockPos o = bot.blockPosition();
                BlockPos best = null;
                double bd = Double.MAX_VALUE;
                for (int dx = -r1; dx <= r1; dx += 4) for (int dz = -r1; dz <= r1; dz += 4) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) < r0) continue;
                    double d2 = dx * dx + dz * dz;
                    if (d2 >= bd) continue;
                    if (!level.isLoaded(o.offset(dx, 0, dz))) continue;
                    for (int dy = 28; dy >= -28; dy -= 2) {
                        BlockPos p = o.offset(dx, dy, dz);
                        if (!leaves.contains(blockPath(level.getBlockState(p)))) continue;
                        // down to the ground under the canopy
                        BlockPos q = p;
                        for (int k = 0; k < 40; k++) {
                            BlockPos below = q.below();
                            String bp = blockPath(level.getBlockState(below));
                            if (Building.isSolid(level, below) && !bp.endsWith("_leaves") && !bp.endsWith("_log")) break;
                            q = below;
                        }
                        if (Protection.nearManMade(level, q, 3)) break;
                        best = q;
                        bd = d2;
                        break;
                    }
                }
                return best;
            }, null);
            if (hit != null) return hit;
        }
        return null;
    }

    /** Walks out (in legs) to the nearest wanted trees. True if it got there. Job thread. */
    static boolean travelToTrees(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Target t)
            throws InterruptedException {
        BlockPos dest = findTreesFar(server, bot, t);
        if (dest == null) return false;
        for (int leg = 0; leg < 10 && SurvivalBrain.canContinue(b); leg++) {
            BlockPos here = onServer(server, bot::blockPosition, null);
            if (here == null) return false;
            double dx = dest.getX() - here.getX(), dz = dest.getZ() - here.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist <= 24) return true;
            if (!upkeep(server, bot, b)) return false;
            BlockPos next = dist <= 40 ? dest
                    : new BlockPos(here.getX() + (int) (dx / dist * 32), here.getY(), here.getZ() + (int) (dz / dist * 32));
            BotPathing.Options o = BotPathing.Options.walkOnly();
            o.allowPlace = true;
            o.timeoutTicks = 20 * 60;
            BotPathing.goToBlocking(bot, ActionPathfinder.near(next.getX(), next.getY(), next.getZ(), next == dest ? 3 : 10),
                    o, 63_000L);
        }
        BlockPos end = onServer(server, bot::blockPosition, null);
        return end != null && Math.hypot(dest.getX() - end.getX(), dest.getZ() - end.getZ()) <= 40;
    }

    /** Nearest matching block, searching outward in shells; exposed ones are preferred. (server thread) */
    static BlockPos findTarget(ServerPlayer bot, SurvivalBrain.Brain b, Target t, Protection.Context ctx, int pickTier) {
        ServerLevel level = bot.level();
        BlockPos o = bot.blockPosition();
        int[][] shells = {{16, 12}, {32, 20}, {48, 28}};
        for (int[] sh : shells) {
            int r = sh[0], v = sh[1];
            BlockPos best = null;
            double bestScore = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (!level.isLoaded(o.offset(dx, 0, dz))) continue;
                    for (int dy = -v; dy <= v; dy++) {
                        if (dx == 0 && dz == 0 && dy == -1) continue; // not the block we stand on
                        double d2 = dx * dx + dz * dz + dy * dy * 1.5;
                        if (d2 >= bestScore) continue;
                        BlockPos p = o.offset(dx, dy, dz);
                        BlockState s = level.getBlockState(p);
                        if (s.isAir()) continue;
                        String path = blockPath(s);
                        if (!t.test(path)) continue;
                        boolean exp = SurvivalBrain.exposed(level, p);
                        if (t.logs() && !exp) continue;
                        double score = d2 + (exp ? 0 : 80);
                        if (score >= bestScore) continue;
                        if (SurvivalBrain.blacklisted(b, p) || tierFor(path) > pickTier) continue;
                        if (ctx.isVillage(p)) continue;
                        if (t.natural() && Protection.nearManMade(level, p, 2)) continue;
                        if (Blueprints.protects(level, p, true)) continue; // a farm's sand isn't a sand pit
                        if (t.logs() && !Protection.isNaturalTreeLog(level, p)) continue;
                        if (fluidNear(level, p) == 2) continue;
                        best = p;
                        bestScore = score;
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** Gets within mining reach of {@code target}: walk, and if it's buried, tunnel. */
    static boolean reach(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, BlockPos target, boolean logs)
            throws InterruptedException {
        if (inReach(server, bot, target)) return true;
        // action-based A*: walks, jumps, drops, tunnels, pillars and bridges as needed
        BotPathing.Options o = logs ? BotPathing.Options.walkOnly() : BotPathing.Options.full();
        o.timeoutTicks = 20 * 90;
        boolean high = logs && onServer(server, () -> target.getY() - bot.getY() > 4.5, false);
        if (!high) {
            BotPathing.goToBlocking(bot, ActionPathfinder.reach(target.getX(), target.getY(), target.getZ(), REACH - 0.2),
                    o, 95_000L);
            if (inReach(server, bot, target)) return true;
        }
        if (logs) {
            // a tall tree: pillar up next to it like a player would
            o = BotPathing.Options.full();
            o.allowBreak = false;
            o.timeoutTicks = 20 * 30;
            BotPathing.goToBlocking(bot, ActionPathfinder.reach(target.getX(), target.getY(), target.getZ(), REACH - 0.2), o, 35_000L);
            return inReach(server, bot, target);
        }
        return tunnelTo(server, bot, b, target);
    }

    static boolean tunnelTo(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, BlockPos target)
            throws InterruptedException {
        double dist = onServer(server, () -> bot.position().distanceTo(Vec3.atCenterOf(target)), 99.0);
        int budget = (int) (dist * 2.5) + 12;
        int[] lastDir = {1, 0};
        for (int i = 0; i < budget; i++) {
            if (inReach(server, bot, target)) return true;
            if (!upkeep(server, bot, b)) return false;
            BlockPos f = onServer(server, bot::blockPosition, null);
            if (f == null) return false;
            int dx = target.getX() - f.getX(), dy = target.getY() - f.getY(), dz = target.getZ() - f.getZ();
            int[] dir;
            if (dx == 0 && dz == 0) dir = lastDir;
            else if (Math.abs(dx) >= Math.abs(dz)) dir = new int[]{Integer.signum(dx), 0};
            else dir = new int[]{0, Integer.signum(dz)};
            int v = dy < -1 ? -1 : (dy > 1 ? 1 : 0);
            boolean moved = step(server, bot, b, dir[0], dir[1], v) || (v != 0 && step(server, bot, b, dir[0], dir[1], 0));
            if (!moved) {
                int[] alt = dir[0] != 0 ? new int[]{0, dz == 0 ? 1 : Integer.signum(dz)}
                        : new int[]{dx == 0 ? 1 : Integer.signum(dx), 0};
                moved = step(server, bot, b, alt[0], alt[1], v) || (v != 0 && step(server, bot, b, alt[0], alt[1], 0));
                if (moved) dir = alt;
            }
            if (!moved) return inReach(server, bot, target);
            lastDir = dir;
        }
        return inReach(server, bot, target);
    }

    // ------------------------------------------------------------------------
    // Strip mining
    // ------------------------------------------------------------------------

    /** Trunk length of one mining trip. */
    static final int TRIP = MineHub.TRIP_LEN;

    /**
     * Strip mining goes through the bot's mine: down its staircase to the ore's level (or
     * {@code yOverride}), then trunk and branches. See {@link MineHub}.
     */
    static void stripMine(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Integer yOverride, int length,
                          Target want, int wantCount, Float yawHint) throws InterruptedException {
        MineHub.mineOre(server, bot, b, want, wantCount, yOverride, length, yawHint);
    }

    static String stopReason(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, int needTier,
                                     Target want, int wantCount, int got, long end) throws InterruptedException {
        if (!upkeep(server, bot, b)) return null;
        if (want != null && wantCount > 0 && got >= wantCount) return "got enough " + want.label();
        if (System.currentTimeMillis() > end) return "been down here long enough";
        float hp = onServer(server, bot::getHealth, 20f);
        if (hp < 8f) return HumanChat.pick("i'm pretty hurt, stopping", "low on health, not gonna push it");
        if (pocketsFull(server, bot, b)) return HumanChat.pick("inventory's full", "i'm out of inventory space");
        if (pickTier(server, bot) < needTier) {
            onServer(server, () -> SurvivalBrain.craftNow(bot), null);
            if (pickTier(server, bot) < needTier) return HumanChat.pick("my pickaxe broke", "pick broke, need a new one");
        }
        return null;
    }

    static void finishStrip(MinecraftServer server, SurvivalBrain.Brain b, Map<String, Integer> found, String why) {
        StringBuilder sb = new StringBuilder();
        if (why != null) sb.append(why).append(". ");
        if (found.isEmpty()) {
            sb.append(HumanChat.pick("didn't find anything good this time", "no ores this time, unlucky"));
        } else {
            sb.append("got ");
            List<Map.Entry<String, Integer>> e = new ArrayList<>(found.entrySet());
            for (int i = 0; i < e.size(); i++) {
                if (i > 0) sb.append(i == e.size() - 1 ? " and " : ", ");
                sb.append(e.get(i).getValue()).append(' ').append(e.get(i).getKey());
            }
        }
        HumanChat.say(server, b.name, sb.toString());
    }

    /** Mines every ore it can see from where it stands (the tunnel walls), following veins. */
    static void mineOresInReach(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b,
                                        Map<String, Integer> found, Target want, int[] got) throws InterruptedException {
        for (int k = 0; k < 16 && SurvivalBrain.canContinue(b); k++) {
            int tier = pickTier(server, bot);
            BlockPos ore = onServer(server, () -> nearestOreInReach(bot, b, tier, want), null);
            if (ore == null) return;
            String path = onServer(server, () -> blockPath(bot.level().getBlockState(ore)), "");
            if (dig(server, bot, b, ore, false, 0)) {
                SurvivalBrain.pickUpNearbyItems(server, bot, 4, true);
                String name = oreName(path);
                if (!found.containsKey(name)) announceFind(server, b, path);
                found.merge(name, 1, Integer::sum);
                if (want != null && want.test(path)) got[0]++;
            } else {
                blacklist(b, ore);
            }
        }
        SurvivalBrain.pickUpNearbyItems(server, bot, 4);
    }

    // ------------------------------------------------------------------------
    // Trees
    // ------------------------------------------------------------------------

    /** Every log of the tree {@code start} belongs to, bottom first. Server thread. */
    static List<BlockPos> treeLogs(ServerLevel level, BlockPos start) {
        List<BlockPos> out = new ArrayList<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && out.size() < 96) {
            BlockPos p = queue.poll();
            if (!SurvivalBrain.isLog(level.getBlockState(p))) continue;
            if (Protection.nearManMade(level, p, 1)) continue; // touching a build: not part of a tree
            out.add(p);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos n = p.offset(dx, dy, dz);
                if (Math.abs(n.getX() - start.getX()) > 6 || Math.abs(n.getZ() - start.getZ()) > 6) continue;
                if (n.getY() < start.getY() - 4 || n.getY() > start.getY() + 32) continue;
                if (seen.add(n)) queue.add(n);
            }
        }
        out.sort((a, c) -> a.getY() != c.getY() ? Integer.compare(a.getY(), c.getY())
                : Double.compare(a.distSqr(start), c.distSqr(start)));
        return out;
    }

    /**
     * Chops down a whole tree like a player: bottom log first, then up the trunk and the
     * branches (pillaring up for tall trees), picks up the logs and saplings, and replants.
     * Returns how many logs it chopped.
     */
    static int fellTree(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, BlockPos start) throws InterruptedException {
        List<BlockPos> logs = onServer(server, () -> Protection.isNaturalTreeLog(bot.level(), start)
                ? treeLogs(bot.level(), start) : List.<BlockPos>of(), List.of());
        if (logs.isEmpty()) return 0;
        BlockPos base = logs.get(0);
        String species = onServer(server, () -> blockPath(bot.level().getBlockState(base)), "oak_log");
        int chopped = 0, failed = 0;
        int top = logs.get(logs.size() - 1).getY() - base.getY() + 1;
        if (top > 4) {
            // a tall tree: pillar blocks (dirt will do) to get at the top, like a player carries
            int need = Math.min(top - 2, 10);
            int have = onServer(server, () -> LevelPathWorld.countThrowaway(bot), 0);
            if (have < need) {
                maybeSay(server, b, HumanChat.pick("tall one, need some dirt to climb it", "grabbing dirt to get up that tree"));
                collect(server, bot, b, new Target("dirt", p -> p.equals("dirt") || p.equals("grass_block") || p.equals("coarse_dirt"),
                        false, true, 0, 8, null), need - have + 2, true);
            }
        }
        for (BlockPos log : logs) {
            if (!SurvivalBrain.canContinue(b)) break;
            if (!onServer(server, () -> SurvivalBrain.isLog(bot.level().getBlockState(log)), false)) continue;
            if (!reach(server, bot, b, log, true) || !dig(server, bot, b, log, false, 0)) {
                if (++failed >= 8) break; // way out of reach: leave the rest
                continue;
            }
            chopped++;
        }
        if (chopped > 0) {
            // came up on a pillar for the top of the tree? dig back down like a player would
            if (onServer(server, () -> bot.getY() > base.getY() + 1.5, false)) {
                SurvivalBrain.goTo(bot, base, 20, true);
            }
            SurvivalBrain.pickUpNearbyItems(server, bot, 8, false);
            replant(server, bot, base, species);
        }
        return chopped;
    }

    /** Plants a sapling where the tree stood, if it has one. */
    private static void replant(MinecraftServer server, ServerPlayer bot, BlockPos base, String logPath) throws InterruptedException {
        String sapling = logPath.replace("stripped_", "").replaceAll("_(log|wood)$", "_sapling");
        if (logPath.contains("mangrove")) sapling = "mangrove_propagule";
        if (!sapling.endsWith("_sapling") && !sapling.equals("mangrove_propagule")) return;
        final String item = sapling;
        if (onServer(server, () -> bot.position().distanceTo(Vec3.atCenterOf(base)) > 4.5, true)) {
            SurvivalBrain.goTo(bot, base, 10);
        }
        onServer(server, () -> {
            ServerLevel level = bot.level();
            if (!level.getBlockState(base).isAir()) return null;
            String soil = blockPath(level.getBlockState(base.below()));
            if (!(soil.contains("dirt") || soil.equals("grass_block") || soil.equals("podzol") || soil.contains("mud")
                    || soil.equals("moss_block") || soil.contains("rooted"))) return null;
            if (!Smelting.holdItem(bot, item)) return null;
            Vec3 hitAt = new Vec3(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
            io.github.yudiiee.aicompanion.PlayerUtils.BlockAim.look(bot, hitAt);
            try {
                bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(hitAt, net.minecraft.core.Direction.UP, base.below(), false));
                Motions.swingArm(bot);
            } catch (Throwable ignored) { }
            return null;
        }, null);
    }

    private static BlockPos nearestOreInReach(ServerPlayer bot, SurvivalBrain.Brain b, int tier) {
        return nearestOreInReach(bot, b, tier, null);
    }

    /** Ore in reach, or (with {@code want}: andesite, granite, tuff...) a block of what's wanted showing in the tunnel walls. */
    private static BlockPos nearestOreInReach(ServerPlayer bot, SurvivalBrain.Brain b, int tier, Target want) {
        boolean wantBlocks = want != null && want.stripY() == null && !want.label().equals("stone") && !want.label().equals("deepslate")
                && !want.label().equals("ores");
        ServerLevel level = bot.level();
        BlockPos o = bot.blockPosition();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) for (int dy = -3; dy <= 5; dy++) for (int dz = -4; dz <= 4; dz++) {
            BlockPos p = o.offset(dx, dy, dz);
            if (p.equals(o.below())) continue;
            String path = blockPath(level.getBlockState(p));
            if (!(isOreId(path) || (wantBlocks && want.test(path))) || tierFor(path) > tier) continue;
            double d = bot.position().distanceTo(Vec3.atCenterOf(p));
            if (d > REACH || d >= bd) continue;
            if (SurvivalBrain.blacklisted(b, p) || !SurvivalBrain.exposed(level, p) || fluidNear(level, p) == 2) continue;
            best = p;
            bd = d;
        }
        return best;
    }

    static String oreName(String path) {
        if (path.equals("ancient_debris")) return "ancient debris";
        String n = path.replace("deepslate_", "").replace("nether_", "").replace("_ore", "");
        return n.equals("lapis") ? "lapis" : n;
    }

    private static void announceFind(MinecraftServer server, SurvivalBrain.Brain b, String path) {
        if (path.contains("diamond")) HumanChat.say(server, b.name, HumanChat.pick("DIAMONDS!!", "yo diamonds!!", "no way, diamonds"));
        else if (path.equals("ancient_debris")) HumanChat.say(server, b.name, HumanChat.pick("ANCIENT DEBRIS", "yooo netherite"));
        else if (path.contains("emerald")) maybeSay(server, b, "ooh an emerald");
        else if (path.contains("iron")) maybeSay(server, b, HumanChat.pick("ooh iron", "found some iron"));
        else if (path.contains("gold")) maybeSay(server, b, HumanChat.pick("gold!", "found gold"));
    }

    // ------------------------------------------------------------------------
    // Digging and moving
    // ------------------------------------------------------------------------

    /**
     * One step of a 1x2 tunnel: forward ({@code v}=0), stairs down (-1) or up (+1).
     * Every block is checked before it's dug. Returns true if the bot moved.
     */
    static boolean step(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, int dx, int dz, int v)
            throws InterruptedException {
        SurvivalBrain.waitWhileFighting(bot);
        BlockPos f = onServer(server, bot::blockPosition, null);
        if (f == null || !SurvivalBrain.canContinue(b)) return false;
        BlockPos front = f.offset(dx, 0, dz);
        BlockPos dest;
        List<BlockPos> clear;
        if (v < 0) { dest = front.below(); clear = List.of(front.above(), front, dest); }
        else if (v > 0) { dest = front.above(); clear = List.of(f.above().above(), dest.above(), dest); }
        else { dest = front; clear = List.of(front.above(), front); }
        if (dest.getY() < LOWEST_Y) return false;

        boolean ok = onServer(server, () -> {
            ServerLevel level = bot.level();
            Protection.Context ctx = Protection.scan(bot, 4);
            for (BlockPos p : clear) {
                // digState already refuses placed blocks themselves (planks, torches, cobble...)
                if (digState(bot, p, true) == UNSAFE || ctx.isVillage(p)) return false;
            }
            if (fluidNear(level, dest) == 2 || fluidNear(level, dest.above()) == 2) return false;
            // stepping out over a cave: put a block down to walk on, like a player would
            if (v <= 0 && Building.isFree(level, dest.below()) && fluidAt(level, dest.below()) == 0
                    && fluidNear(level, dest.below()) != 2 && !ctx.isVillage(dest.below())) {
                String blk = Building.firstItem(bot, LevelPathWorld::isThrowaway);
                if (blk != null) Building.placeAt(bot, dest.below(), blk);
            }
            return solidFloor(level, dest.below());
        }, false);
        if (!ok) return false;
        for (BlockPos p : clear) {
            if (!dig(server, bot, b, p, true, 0)) return false;
        }
        if (walkInto(server, bot, dest, v > 0)) return true;
        // odd geometry: let the pathfinder have a go
        SurvivalBrain.goTo(bot, dest, 6);
        return at(server, bot, dest);
    }

    /** Back to a tunnel cell after wandering off (picking up drops, a fight...). */
    static boolean returnTo(MinecraftServer server, ServerPlayer bot, BlockPos cell) throws InterruptedException {
        if (at(server, bot, cell)) return true;
        double d = onServer(server, () -> bot.position().distanceTo(Vec3.atBottomCenterOf(cell)), 99.0);
        if (d < 1.8 && walkInto(server, bot, cell, false)) return true;
        SurvivalBrain.goTo(bot, cell, 15);
        if (at(server, bot, cell)) return true;
        d = onServer(server, () -> bot.position().distanceTo(Vec3.atBottomCenterOf(cell)), 99.0);
        return d < 1.8 && walkInto(server, bot, cell, false);
    }

    static boolean at(MinecraftServer server, ServerPlayer bot, BlockPos dest) {
        return onServer(server, () -> {
            Vec3 c = Vec3.atBottomCenterOf(dest);
            Vec3 p = bot.position();
            return Math.hypot(c.x - p.x, c.z - p.z) < 0.45 && Math.abs(p.y - dest.getY()) < 0.6;
        }, false);
    }

    /**
     * Walks the one block into {@code dest} with the movement keys, like a player in a
     * tunnel: face it, hold forward (jump for a step up), let go once centred.
     */
    static boolean walkInto(MinecraftServer server, ServerPlayer bot, BlockPos dest, boolean up) throws InterruptedException {
        if (!(bot instanceof ServerPlayerInterface spi)) return false;
        long end = System.currentTimeMillis() + 4000;
        boolean arrived = false;
        try {
            while (System.currentTimeMillis() < end) {
                if (PvpController.isBusy(bot.getUUID())) { // a mob: the fight has the keys
                    SurvivalBrain.waitWhileFighting(bot);
                    end = System.currentTimeMillis() + 4000;
                    continue;
                }
                Boolean done = onServer(server, () -> {
                    EntityPlayerActionPack ap = spi.getActionPack();
                    Vec3 c = Vec3.atBottomCenterOf(dest);
                    Vec3 p = bot.position();
                    double dx = c.x - p.x, dz = c.z - p.z;
                    double h = Math.hypot(dx, dz);
                    boolean levelOk = Math.abs(p.y - dest.getY()) < 0.6;
                    if (h < 0.3 && levelOk && bot.onGround()) {
                        ap.setForward(0f);
                        return true;
                    }
                    if (h > 0.05) ap.look((float) Math.toDegrees(Math.atan2(-dx, dz)), 25f);
                    ap.setSprinting(false);
                    ap.setStrafing(0f);
                    ap.setForward(h < 0.7 ? 0.3f : 1f);
                    if (up && bot.onGround() && p.y < dest.getY() - 0.4) {
                        ap.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
                    }
                    return false;
                }, false);
                if (done) { arrived = true; break; }
                SurvivalBrain.sleep(50);
            }
        } finally {
            onServer(server, () -> { spi.getActionPack().setForward(0f); spi.getActionPack().setStrafing(0f); return null; }, null);
        }
        return arrived || at(server, bot, dest);
    }

    /**
     * Mines one block (and anything that blocks the view of it), re-mining gravel or sand
     * that falls into the gap. {@code tunnel} = part of our tunnel, so stricter checks.
     * Returns true once the spot is empty.
     */
    static boolean dig(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, BlockPos p, boolean tunnel, int depth)
            throws InterruptedException {
        for (int attempt = 0; attempt < 12; attempt++) {
            if (!SurvivalBrain.canContinue(b)) return false;
            SurvivalBrain.waitWhileFighting(bot);
            int st = onServer(server, () -> {
                int d = digState(bot, p, tunnel);
                if (d != BREAK) return d;
                Protection.Context ctx = Protection.scan(bot, 4);
                if (ctx.isVillage(p)) return UNSAFE; // placed blocks themselves are refused by digState
                return BREAK;
            }, UNSAFE);
            if (st == CLEAR) return true;
            if (st == UNSAFE) return false;
            double d = onServer(server, () -> bot.position().distanceTo(Vec3.atCenterOf(p)), 99.0);
            if (d > 4.9) return false;

            // Mining hits whatever is under the crosshair, so clear the line of sight first.
            BlockPos blocker = onServer(server, () -> blockerOf(bot, p), null);
            if (blocker != null) {
                if (depth >= 2 || !dig(server, bot, b, blocker, true, depth + 1)) return false;
                continue;
            }
            boolean fallingAbove = onServer(server, () -> isFalling(blockPath(bot.level().getBlockState(p.above()))), false);
            onServer(server, () -> { LookController.faceBlock(bot, p); return null; }, null);
            try {
                MiningResult r = MiningTool.mineBlock(bot, p).get(45, TimeUnit.SECONDS);
                if (r != null && r.status() == MiningResult.Status.CANCELLED && SurvivalBrain.canContinue(b)
                        && SurvivalBrain.waitWhileFighting(bot)) {
                    continue; // a mob interrupted: finish the block after the fight
                }
                if (r == null || r.status() != MiningResult.Status.SUCCESS) {
                    LOGGER.debug("[mining] {} could not mine {}: {}", b.name, p, r == null ? "null" : r.status());
                    return false;
                }
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                MiningTool.cancelFor(server, bot.getUUID(), "Took too long");
                return false;
            }
            SurvivalBrain.sleep(fallingAbove ? 800 : 120);
        }
        return false;
    }

    /** The block in the way when looking at {@code p}, or null if {@code p} is visible. (server thread) */
    private static BlockPos blockerOf(ServerPlayer bot, BlockPos p) {
        try {
            BlockHitResult hit = bot.level().clip(new ClipContext(bot.getEyePosition(), Vec3.atCenterOf(p),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
            if (hit == null || hit.getType() == HitResult.Type.MISS) return null;
            BlockPos hp = hit.getBlockPos();
            return hp == null || hp.equals(p) ? null : hp;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Blocks the bot itself put in a tunnel's way (cave seals, a wall thrown up while
     * retreating): unlike other placed blocks, it may dig these out again.
     */
    private static final java.util.Set<BlockPos> OWN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void ownBlock(BlockPos p) {
        if (OWN.size() > 4096) OWN.clear();
        OWN.add(p.immutable());
    }

    static boolean isOwnBlock(BlockPos p) {
        return OWN.contains(p);
    }

    /** CLEAR (nothing to dig), BREAK or UNSAFE. (server thread) */
    private static int digState(ServerPlayer bot, BlockPos p, boolean tunnel) {
        ServerLevel level = bot.level();
        if (fluidAt(level, p) != 0) return UNSAFE;
        BlockState s = level.getBlockState(p);
        if (s.isAir()) return CLEAR;
        if (s.getDestroyProgress(bot, level, p) <= 0f) return UNSAFE; // bedrock, barriers...
        int near = fluidNear(level, p);
        if (near == 2 || (tunnel && near == 1)) return UNSAFE;
        String path = blockPath(s);
        if (tunnel && (Protection.isManMade(path) || path.contains("spawner") || path.contains("chest")) && !OWN.contains(p)) return UNSAFE;
        return BREAK;
    }

    /** 0 none, 1 water, 2 lava. */
    static int fluidAt(ServerLevel level, BlockPos p) {
        FluidState fs = level.getFluidState(p);
        if (fs == null) return 0;
        if (fs.is(FluidTags.LAVA)) return 2;
        if (fs.is(FluidTags.WATER)) return 1;
        return 0;
    }

    /** Worst fluid right next to {@code p}. */
    static int fluidNear(ServerLevel level, BlockPos p) {
        int worst = 0;
        for (int[] o : SIDES) worst = Math.max(worst, fluidAt(level, p.offset(o[0], o[1], o[2])));
        return worst;
    }

    private static boolean solidFloor(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return !s.isAir() && !s.canBeReplaced() && fluidAt(level, p) == 0 && !blockPath(s).contains("magma");
    }

    static boolean isFalling(String path) {
        return path.equals("gravel") || path.equals("sand") || path.equals("red_sand") || path.startsWith("suspicious_")
                || path.endsWith("_concrete_powder") || path.contains("anvil") || path.equals("pointed_dripstone");
    }

    private static boolean inReach(MinecraftServer server, ServerPlayer bot, BlockPos p) {
        return onServer(server, () -> bot.position().distanceTo(Vec3.atCenterOf(p)) <= REACH, false);
    }

    // ------------------------------------------------------------------------
    // Upkeep
    // ------------------------------------------------------------------------

    /** Between steps: wait out fights, eat, craft upgrades. False if the job should end. */
    static boolean upkeep(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        if (!SurvivalBrain.canContinue(b)) return false;
        for (int i = 0; i < 30 && fighting(); i++) SurvivalBrain.sleep(1000);
        SurvivalBrain.waitWhileFighting(bot);
        if (!onServer(server, () -> bot.isAlive() && !bot.hasDisconnected(), false)) return false;
        if (onServer(server, () -> SurvivalBrain.shouldEat(bot), false) && onServer(server, () -> FoodConsumptionTool.hasSafeFood(bot), false)) {
            FoodConsumptionTool.consumeBestFood(bot);
        }
        onServer(server, () -> {
            for (int i = 0; i < 4; i++) {
                String line = SurvivalBrain.craftUpgrades(bot);
                if (line != null) HumanChat.say(server, b.name, line);
            }
            return null;
        }, null);
        return SurvivalBrain.canContinue(b);
    }

    private static boolean fighting() {
        return AutoFaceEntity.hostileEntityInFront || AutoFaceEntity.isShooting
                || AutoFaceEntity.isActivelyBlocking || AutoFaceEntity.isDefendingFromProjectile;
    }

    /** Makes sure there's a pickaxe of at least {@code minTier}, getting wood/stone for one if needed. */
    private static void craftPick(MinecraftServer server, ServerPlayer bot) {
        onServer(server, () -> {
            SurvivalBrain.craftNow(bot);
            if (SurvivalBrain.Inv.of(bot).pickaxeTier() == 0
                    || SurvivalBrain.Inv.of(bot).pickaxeTier() == 1 && SurvivalBrain.Inv.of(bot).cobble() >= 3) {
                SurvivalBrain.craftTool(bot, "pickaxe"); // ignores "keep the wood for a build"
            }
            return null;
        }, null);
    }

    static boolean ensurePickaxe(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, int minTier)
            throws InterruptedException {
        if (pickTier(server, bot) >= minTier) return true;
        craftPick(server, bot);
        if (pickTier(server, bot) >= minTier) return true;
        if (minTier >= 4) {
            say(server, b, HumanChat.pick("need a diamond pickaxe for that", "can't mine that without a diamond pick"));
            return false;
        }
        if (minTier >= 3) {
            say(server, b, HumanChat.pick("need an iron pickaxe for that and i don't have one", "can't mine that without an iron pick"));
            return false;
        }
        if (pickTier(server, bot) == 0) {
            say(server, b, HumanChat.pick("need a pickaxe first, gonna grab some wood", "no pickaxe yet, getting wood first"));
            collect(server, bot, b, logs("wood", MiningSkills::isNaturalLogId), 4);
            craftPick(server, bot);
        }
        if (minTier >= 2 && pickTier(server, bot) == 1) {
            say(server, b, HumanChat.pick("need a stone pick for that, one sec", "gonna get some stone for a better pick"));
            collect(server, bot, b, new Target("stone", "stone"::equals, false, true, 1, 3, null), 3);
            craftPick(server, bot);
        }
        if (pickTier(server, bot) >= minTier) return true;
        if (SurvivalBrain.canContinue(b)) say(server, b, HumanChat.pick("couldn't make a good enough pickaxe", "need a better pickaxe for that"));
        return false;
    }

    static int pickTier(MinecraftServer server, ServerPlayer bot) {
        return onServer(server, () -> SurvivalBrain.Inv.of(bot).pickaxeTier(), 0);
    }

    /**
     * Full pockets while mining: throw out the junk a player would (cobble/deepslate beyond a
     * stack, dirt, gravel, diorite, tuff, seeds, flowers...) and carry on. Only if it's all
     * worth keeping is it really full. Job thread.
     */
    static boolean pocketsFull(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) {
        return onServer(server, () -> {
            if (!inventoryFull(bot)) return false;
            Storage.makeRoom(bot, 4);
            return inventoryFull(bot);
        }, false);
    }

    /**
     * Before going mining: if the pockets are already half full of stuff it doesn't need, drop
     * it at home first (like a player emptying their inventory before a mining trip). Job thread.
     */
    static void emptyPocketsFirst(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        if (!HumanConfig.get().autoStore) return;
        boolean worth = onServer(server, () -> {
            Home.Base h = Home.get(bot);
            if (h == null || h.middle().distSqr(bot.blockPosition()) > 150 * 150) return false;
            int free = 0;
            var inv = bot.getInventory();
            for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) if (inv.getItem(i).isEmpty()) free++;
            return free < 14 && Storage.storableSlots(bot) >= 4;
        }, false);
        if (worth) {
            BlockPos start = onServer(server, bot::blockPosition, null);
            maybeSay(server, b, HumanChat.pick("dropping my stuff at home first", "gonna empty my inventory before heading down"));
            Storage.storeAll(server, bot, b, false);
            // then back to where it was going to dig (not straight down through the house floor)
            if (start != null && SurvivalBrain.canContinue(b)) {
                BotPathing.Options o = BotPathing.Options.full();
                o.timeoutTicks = 20 * 120;
                BotPathing.goToBlocking(bot, ActionPathfinder.near(start.getX(), start.getY(), start.getZ(), 1.5), o, 125_000L);
            }
        }
        onServer(server, () -> Storage.makeRoom(bot, 10, true), 0);
    }

    private static boolean inventoryFull(ServerPlayer bot) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            if (inv.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    private static void blacklist(SurvivalBrain.Brain b, BlockPos p) {
        b.blacklist.put(p, System.currentTimeMillis() + 5 * 60_000L);
    }

    private static void say(MinecraftServer server, SurvivalBrain.Brain b, String line) {
        if (line != null) HumanChat.say(server, b.name, line);
    }

    static void maybeSay(MinecraftServer server, SurvivalBrain.Brain b, String line) {
        SurvivalBrain.maybeSay(server, b, line, 0.7);
    }
}
