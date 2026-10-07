package io.github.yudiiee.aicompanion.GameAI.human;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What things are worth, in diamonds: the price list every companion remembers. The list
 * ships with the mod; {@code config/ai-companion/prices.txt} holds your own lines on top of it
 * (a line there wins for its item). One price per line:
 * <pre>PRICE | ID: minecraft:bread | RATIO: 16 = 1 DIA | UNIT_DIA: 0.06250 | RARITY: T3_REFINED | CAT: food</pre>
 * Things that aren't on the list are worth what goes into making them (from the recipe book),
 * plus a little for the work. Plain Java, no game classes.
 */
public final class PriceBook {

    private PriceBook() {}

    /** What a shop pays for something, as a share of its price (it sells at the full price). */
    public static final double BUY_RATE = 0.8;
    /** Added to the cost of the ingredients for something that isn't on the list. */
    static final double CRAFT_MARKUP = 1.1;

    /** A listed price: {@code qty} of {@code item} for {@code dia} diamonds. */
    public record Price(String item, int qty, double dia, double unit, String rarity, String cat) {

        /** "16 bread for a diamond", "an iron block for 1.13 diamonds". */
        public String ratio() {
            String name = item.replace('_', ' ');
            if (qty == 1) return "1 " + name + " for " + money(dia);
            return qty + " " + plural(name, qty) + " for " + (dia == 1.0 ? "a diamond" : money(dia));
        }

        /** "uncommon", "legendary"... */
        public String tier() {
            int u = rarity.indexOf('_');
            return (u >= 0 ? rarity.substring(u + 1) : rarity).toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    // ------------------------------------------------------------------------
    // Reading the list
    // ------------------------------------------------------------------------

    private static final Pattern RATIO = Pattern.compile("^\\s*(\\d+)\\s*(?:[a-z0-9_:]+\\s*)?=\\s*([0-9]*\\.?[0-9]+)\\s*(?:dia|diamonds?)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** One line of the list, or null if it isn't a price. */
    public static Price parseLine(String line) {
        try {
            return parse(line);
        } catch (RuntimeException e) {
            return null; // a mangled line: skip it
        }
    }

    private static Price parse(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (!t.toUpperCase(Locale.ROOT).startsWith("PRICE")) return null;
        Map<String, String> f = new LinkedHashMap<>();
        for (String part : t.split("\\|")) {
            int c = part.indexOf(':');
            if (c <= 0) continue;
            f.put(part.substring(0, c).trim().toUpperCase(Locale.ROOT), part.substring(c + 1).trim());
        }
        String id = f.get("ID");
        if (id == null || id.isBlank()) return null;
        String item = id.toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "").trim();
        if (!item.matches("[a-z0-9_]+")) return null;
        int qty = 1;
        double dia = -1;
        String ratio = f.get("RATIO");
        if (ratio != null) {
            Matcher m = RATIO.matcher(ratio);
            if (m.matches()) {
                qty = Math.max(1, Integer.parseInt(m.group(1)));
                dia = Double.parseDouble(m.group(2));
            }
        }
        double unit;
        if (dia > 0) {
            unit = dia / qty;
        } else {
            try {
                unit = Double.parseDouble(f.getOrDefault("UNIT_DIA", "").trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (unit <= 0) return null;
            qty = unit >= 1 ? 1 : (int) Math.min(1_000_000L, Math.max(1, Math.round(1 / unit)));
            dia = unit * qty;
        }
        if (unit <= 0 || Double.isNaN(unit) || Double.isInfinite(unit)) return null;
        String rarity = f.getOrDefault("RARITY", "").trim();
        String cat = f.getOrDefault("CAT", "").trim().toLowerCase(Locale.ROOT);
        return new Price(item, qty, dia, unit, rarity.isEmpty() ? "T3_REFINED" : rarity.toUpperCase(Locale.ROOT), cat);
    }

    private static volatile Map<String, Price> byItem = Map.of();
    private static volatile long loadedAt;
    private static volatile long fileStamp = -1;
    /** Worked-out prices for things that aren't listed (-1: worth nothing we know of). */
    private static final Map<String, Double> DERIVED = new ConcurrentHashMap<>();

    static synchronized void refresh() {
        long now = System.currentTimeMillis();
        if (now - loadedAt < 5000 && !byItem.isEmpty()) return;
        loadedAt = now;
        Path file = file();
        long stamp = -2;
        try {
            if (file != null && Files.isRegularFile(file)) stamp = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ignored) { }
        if (stamp == fileStamp && !byItem.isEmpty()) return;
        fileStamp = stamp;
        List<Price> all = new ArrayList<>();
        try (InputStream in = PriceBook.class.getResourceAsStream("/assets/ai-companion/prices.txt")) {
            if (in != null) all.addAll(read(in));
        } catch (IOException ignored) { }
        if (file != null && Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                all.addAll(read(in));
            } catch (IOException ignored) { }
        }
        use(all);
    }

    /** Uses these prices (a later line for the same item wins). */
    static synchronized void use(List<Price> all) {
        Map<String, Price> m = new LinkedHashMap<>();
        for (Price p : all) m.put(p.item(), p);
        byItem = Collections.unmodifiableMap(m);
        DERIVED.clear();
        loadedAt = System.currentTimeMillis();
    }

    static List<Price> read(InputStream in) throws IOException {
        List<Price> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                Price p = parseLine(line);
                if (p != null) out.add(p);
            }
        }
        return out;
    }

    private static volatile boolean installed;

    /** {@code config/ai-companion/prices.txt} (copied out of the mod the first time), or null. */
    static Path file() {
        try {
            Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("ai-companion");
            Files.createDirectories(dir);
            Path f = dir.resolve("prices.txt");
            if (!installed) {
                installed = true;
                if (!Files.exists(f)) {
                    // overrides only: the full list ships with the mod (and updates with it)
                    Files.write(f, List.of("# Your own prices for AI Companion, on top of the list that comes with the mod.",
                            "# A line here wins over the mod's line for the same item. Same format, one per line, e.g.:",
                            "# PRICE | ID: minecraft:bread | RATIO: 16 = 1 DIA | UNIT_DIA: 0.0625 | RARITY: T3_REFINED | CAT: food",
                            "# (the mod's own list is assets/ai-companion/prices.txt inside the mod jar)"), StandardCharsets.UTF_8);
                }
            }
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    public static int size() {
        refresh();
        return byItem.size();
    }

    /** Every listed price, in the list's order. */
    public static List<Price> all() {
        refresh();
        return List.copyOf(byItem.values());
    }

    /** The listed price of an item, or null. */
    public static Price get(String item) {
        if (item == null) return null;
        refresh();
        return byItem.get(strip(item));
    }

    private static String strip(String item) {
        return item.toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
    }

    // ------------------------------------------------------------------------
    // What things are worth
    // ------------------------------------------------------------------------

    /** Diamonds one of these is worth (listed, or worked out from its recipe), or -1 if it has no price. */
    public static double unit(String item) {
        if (item == null || item.isBlank()) return -1;
        refresh();
        String it = strip(item);
        Price p = byItem.get(it);
        if (p != null) return p.unit();
        Double d = DERIVED.get(it);
        if (d != null) return d;
        double v = derive(it, new HashSet<>(), 0);
        if (DERIVED.size() < 5000) DERIVED.put(it, v);
        return v;
    }

    /** Is there any price for it? */
    public static boolean priced(String item) {
        return unit(item) > 0;
    }

    /** The cheapest listed thing a recipe tag stands for. */
    private static String tagItem(String tag) {
        return switch (tag) {
            case "#planks" -> "oak_planks";
            case "#logs" -> "oak_log";
            case "#wooden_slab" -> "oak_slab";
            case "#stone_crafting" -> "cobblestone";
            case "#soul_fire_base" -> "soul_sand";
            default -> tag.startsWith("#") ? null : tag;
        };
    }

    /** What making one costs: the ingredients' worth over what the recipe makes, plus a little for the work. */
    private static double derive(String item, Set<String> seen, int depth) {
        if (depth > 5 || !seen.add(item)) return -1;
        try {
            // the builder's own recipes (stone bricks, lanterns, wooden things of every kind), then the recipe book's
            List<Object[]> rs = new ArrayList<>(); // {inputs, makes, cooked}
            try {
                BlueprintBuilder.Recipe own = BlueprintBuilder.recipeFor(item);
                if (own != null) rs.add(new Object[]{own.in(), own.out(), false});
            } catch (Throwable ignored) { }
            try {
                for (RecipeBook.Recipe r : RecipeBook.forItem(item)) rs.add(new Object[]{r.in(), r.out(), r.cooked()});
            } catch (Throwable ignored) { }
            double best = -1;
            for (Object[] r : rs) {
                @SuppressWarnings("unchecked")
                Map<String, Integer> ins = (Map<String, Integer>) r[0];
                int makes = (Integer) r[1];
                boolean cooked = (Boolean) r[2];
                double sum = 0;
                boolean ok = true;
                for (Map.Entry<String, Integer> e : ins.entrySet()) {
                    String in = tagItem(e.getKey());
                    if (in == null) { ok = false; break; }
                    Price lp = byItem.get(in);
                    double u = lp != null ? lp.unit() : derive(in, seen, depth + 1);
                    if (u <= 0) { ok = false; break; }
                    sum += u * e.getValue();
                }
                if (!ok) continue;
                if (cooked) sum += 0.03125 / 8; // fuel: a coal cooks 8
                double each = sum / Math.max(1, makes) * CRAFT_MARKUP;
                if (each > 0 && (best < 0 || each < best)) best = each;
            }
            return best <= 0 ? -1 : round(best);
        } finally {
            seen.remove(item);
        }
    }

    /** Worth of {@code n} of an item, or -1. */
    public static double value(String item, int n) {
        double u = unit(item);
        return u <= 0 ? -1 : round(u * Math.max(0, n));
    }

    /** What someone pays a companion for {@code n} (the list price), or -1. */
    public static double sellPrice(String item, int n) {
        return value(item, n);
    }

    /** What a companion pays for {@code n} (a bit under the list price: shops have to make a living), or -1. */
    public static double buyPrice(String item, int n) {
        double v = value(item, n);
        return v <= 0 ? -1 : round(v * BUY_RATE);
    }

    /** How many a diamond buys (at least 1 shown as a fraction of a diamond's worth). */
    public static int perDiamond(String item) {
        double u = unit(item);
        return u <= 0 ? 0 : (int) Math.floor(1.0 / u + 1e-6);
    }

    static double round(double d) {
        return Math.round(d * 100000.0) / 100000.0;
    }

    /** "1 diamond", "2.25 diamonds", "0.06 of a diamond". */
    public static String money(double dia) {
        if (dia <= 0) return "nothing";
        if (Math.abs(dia - 1) < 1e-6) return "1 diamond";
        if (dia >= 1) {
            String s = trim(String.format(Locale.ROOT, "%.2f", dia));
            return s + " diamonds";
        }
        String s = trim(String.format(Locale.ROOT, dia >= 0.1 ? "%.2f" : "%.3f", dia));
        if (s.equals("0")) s = trim(String.format(Locale.ROOT, "%.4f", dia));
        return s + " of a diamond";
    }

    private static String trim(String s) {
        if (!s.contains(".")) return s;
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    static String plural(String name, int n) {
        if (n == 1) return name;
        if (name.endsWith("s") || name.endsWith("glass") || name.endsWith("wheat") || name.endsWith("bread") || name.endsWith("sand")
                || name.endsWith("dirt") || name.endsWith("gravel") || name.endsWith("redstone") || name.endsWith("string")
                || name.endsWith("leather") || name.endsWith("sugar") || name.endsWith("paper") || name.endsWith("clay")
                || name.endsWith("netherrack") || name.endsWith("stone") || name.endsWith("deepslate") || name.endsWith("cobblestone")
                || name.endsWith("gunpowder") || name.endsWith("bone meal") || name.endsWith("coal") || name.endsWith("charcoal")
                || name.endsWith("debris") || name.endsWith("quartz") || name.endsWith("calcite") || name.endsWith("tuff")
                || name.endsWith("basalt") || name.endsWith("granite") || name.endsWith("diorite") || name.endsWith("andesite")
                || name.endsWith("obsidian") || name.endsWith("sweet berries") || name.endsWith("glow berries")
                || name.endsWith("lapis lazuli") || name.endsWith("soil") || name.endsWith("wool") || name.startsWith("cooked ")
                || name.endsWith("beef") || name.endsWith("mutton") || name.endsWith("porkchop") || name.endsWith("flesh")
                || name.endsWith("cream") || name.endsWith("powder")) return name;
        if (name.endsWith("y") && !name.endsWith("ey") && !name.endsWith("ay")) return name.substring(0, name.length() - 1) + "ies";
        if (name.endsWith("sh") || name.endsWith("ch") || name.endsWith("x")) return name + "es";
        return name + "s";
    }

    /** "bread: 16 for a diamond (0.063 of a diamond each), common food". */
    public static String explain(String item) {
        refresh();
        String it = strip(item);
        Price p = byItem.get(it);
        String name = it.replace('_', ' ');
        if (p != null) {
            String each = p.qty() > 1 ? " (" + money(p.unit()) + " each)" : "";
            String cat = p.cat().isEmpty() ? "" : ", " + p.tier() + " " + p.cat().replace('_', ' ');
            return p.ratio() + each + cat;
        }
        double u = unit(it);
        if (u <= 0) return null;
        int per = perDiamond(it);
        return (per >= 2 ? per + " " + plural(name, per) + " for a diamond" : "1 " + name + " for " + money(u))
                + (per >= 2 ? " (" + money(u) + " each)" : "") + ", going by what goes into it";
    }

    // ------------------------------------------------------------------------
    // What people call things
    // ------------------------------------------------------------------------

    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        String[][] a = {
                {"diamonds", "diamond"}, {"dia", "diamond"}, {"dias", "diamond"}, {"emeralds", "emerald"},
                {"iron", "iron_ingot"}, {"gold", "gold_ingot"}, {"copper", "copper_ingot"}, {"netherite", "netherite_ingot"},
                {"cobble", "cobblestone"}, {"lapis", "lapis_lazuli"}, {"god apple", "enchanted_golden_apple"},
                {"notch apple", "enchanted_golden_apple"}, {"gapple", "golden_apple"}, {"golden apples", "golden_apple"},
                {"pearl", "ender_pearl"}, {"ender pearls", "ender_pearl"}, {"pearls", "ender_pearl"}, {"eye of ender", "ender_eye"},
                {"eyes of ender", "ender_eye"}, {"totem", "totem_of_undying"}, {"steak", "cooked_beef"}, {"pork", "cooked_porkchop"},
                {"chicken", "cooked_chicken"}, {"mutton", "cooked_mutton"}, {"seeds", "wheat_seeds"}, {"melon", "melon_slice"},
                {"berries", "sweet_berries"}, {"slime", "slime_ball"}, {"slimeball", "slime_ball"}, {"bonemeal", "bone_meal"},
                {"wood", "oak_log"}, {"logs", "oak_log"}, {"planks", "oak_planks"}, {"sticks", "stick"}, {"cane", "sugar_cane"},
                {"sugarcane", "sugar_cane"}, {"wither skull", "wither_skeleton_skull"}, {"scrap", "netherite_scrap"},
                {"debris", "ancient_debris"}, {"star", "nether_star"}, {"core", "heavy_core"}, {"nautilus", "nautilus_shell"},
                {"heart of the sea", "heart_of_the_sea"}, {"enchanting table", "enchanting_table"}, {"workbench", "crafting_table"},
                {"glowberries", "glow_berries"}, {"potatoes", "potato"}, {"carrots", "carrot"}, {"tomatoes", "tomato"},
                {"trident", "trident"}, {"elytras", "elytra"}, {"wings", "elytra"}};
        for (String[] kv : a) ALIASES.put(kv[0], kv[1]);
    }

    /** The item a phrase means ("64 cobble", "a god apple", "iron pick"), if it has a price. */
    public static String itemFor(String phrase) {
        if (phrase == null) return null;
        refresh();
        String q = phrase.toLowerCase(Locale.ROOT).replace("minecraft:", "").replaceAll("[^a-z0-9_ ]", " ")
                .replaceAll("\\b(for|at) (a |an |one |\\d+ )?(diamonds?|dias?|emeralds?)\\b.*$", " ")
                .replaceAll("\\b(please|pls|plz|thanks|thx|ty|bro|mate|dude|man|then|now|from you|off you|if you can|if you have any)\\b", " ")
                .replaceAll("\\bx\\d+\\b|\\b\\d+x\\b", " ")
                .replaceAll("\\b(a|an|the|some|me|us|of|few|couple|pair|set|my|your|new|stack|stacks|half|piece|pieces|x|worth|one|each|per|all)\\b", " ")
                .replaceAll("\\b\\d+\\b", " ").trim().replaceAll("\\s+", " ");
        if (q.isEmpty()) return null;
        q = q.replaceAll("\\bpick\\b", "pickaxe").replaceAll("\\bchestplates?\\b", "chestplate").replaceAll("\\bingots\\b", "ingot");
        if (ALIASES.containsKey(q)) return ALIASES.get(q);
        List<String> cands = new ArrayList<>();
        String id = q.replace(' ', '_');
        cands.add(id);
        if (id.endsWith("ies")) cands.add(id.substring(0, id.length() - 3) + "y");
        if (id.endsWith("ves")) cands.add(id.substring(0, id.length() - 3) + "f");
        if (id.endsWith("es")) cands.add(id.substring(0, id.length() - 2));
        if (id.endsWith("s")) cands.add(id.substring(0, id.length() - 1));
        for (String c : cands) if (byItem.containsKey(c)) return c;
        // "iron pickaxes" -> iron_pickaxe already covered; "raw iron" fine. Then anything with a recipe.
        for (String c : cands) {
            if (c.matches("[a-z0-9_]{2,48}") && unit(c) > 0) return c; // something it can make: worth what goes into it
        }
        String alias = ALIASES.get(q.endsWith("s") ? q.substring(0, q.length() - 1) : q);
        return alias;
    }

    private static final Pattern ASK = Pattern.compile(
            "^(?:(?:hey|yo|so|ok|okay|and|but|bro|pls|please|um|uh|also)[,\\s]+)*(?:how much (?:is|are|for|does|do|would|will|'?s)?\\s*(?:an? |the |some )?(.+?)(?:\\s+(?:cost|costs|go for|sell for|worth|be))?"
            + "|what(?:'s| is| are|'re) (?:an? |the )?(.+?) (?:worth|going for|selling for)"
            + "|(?:the )?(?:price|value|cost) (?:of|for) (?:an? |the |some )?(.+?)"
            + "|what (?:does|do|would) (?:an? |the )?(.+?) cost"
            + "|how many (?:diamonds|dias?) (?:for|is|are|does|do) (?:an? |the )?(.+?)(?: cost| worth)?"
            + "|(?:an? |the )?(.+?) price)[?!.\\s]*$");

    /** "how much is a stack of iron" -> "a stack of iron" (the phrase about the thing), or null. */
    public static String question(String m) {
        if (m == null || m.length() > 90) return null;
        String t = m.toLowerCase(Locale.ROOT).trim();
        if (t.matches(".*\\b(how much (do|does) (you|u) (have|got|own)|how much (time|longer|left|further|is left))\\b.*")) return null;
        Matcher h = ASK.matcher(t);
        if (!h.find()) return null;
        for (int g = 1; g <= h.groupCount(); g++) {
            if (h.group(g) != null && !h.group(g).isBlank()) return h.group(g).trim();
        }
        return null;
    }

    private static final Pattern COUNT = Pattern.compile("\\b(\\d+)\\b|\\b(half a stack|a stack|stack)\\b");

    /** How many a phrase asks about ("64 cobble" -> 64, "a stack of iron" -> 64), or 0 if it doesn't say. */
    public static int countIn(String phrase) {
        if (phrase == null) return 0;
        Matcher st = Pattern.compile("\\b(\\d{1,5}) stacks?\\b").matcher(phrase.toLowerCase(Locale.ROOT));
        if (st.find()) return (int) Math.min(100_000L, Long.parseLong(st.group(1)) * 64);
        Matcher x = Pattern.compile("\\bx(\\d{1,5})\\b|\\b(\\d{1,5})x\\b").matcher(phrase.toLowerCase(Locale.ROOT));
        if (x.find()) return Integer.parseInt(x.group(1) != null ? x.group(1) : x.group(2));
        Matcher m = COUNT.matcher(phrase.toLowerCase(Locale.ROOT));
        if (!m.find()) return 0;
        if (m.group(1) != null) {
            try {
                return Math.min(100_000, Integer.parseInt(m.group(1)));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return m.group(2).startsWith("half") ? 32 : 64;
    }

    /** The answer to "how much is X", or null if it doesn't know the thing. */
    public static String answer(String phrase) {
        String item = itemFor(phrase);
        if (item == null) return null;
        if (item.equals("diamond")) return "a diamond is a diamond lol, everything's priced in them";
        int n = countIn(phrase);
        String ex = explain(item);
        if (ex == null) return null;
        if (n > 1) {
            String name = plural(item.replace('_', ' '), n);
            return n + " " + name + " is " + money(sellPrice(item, n)) + " (" + ex + "). shops pay " + money(buyPrice(item, n))
                    + " if you're selling";
        }
        return ex;
    }

    /** Prices for things a bit of chat mentions (for the language model's prompt), up to {@code max}. */
    public static List<String> mentioned(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        refresh();
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ") + " ";
        Set<String> done = new HashSet<>();
        for (Price p : byItem.values()) {
            String name = p.item().replace('_', ' ');
            if (t.contains(" " + name + " ") || t.contains(" " + plural(name, 2) + " ")) {
                if (done.add(p.item())) out.add(p.item().replace('_', ' ') + ": " + explain(p.item()));
                if (out.size() >= max) return out;
            }
        }
        for (Map.Entry<String, String> a : ALIASES.entrySet()) {
            if (out.size() >= max) break;
            if (t.contains(" " + a.getKey() + " ") && done.add(a.getValue())) {
                String ex = explain(a.getValue());
                if (ex != null) out.add(a.getValue().replace('_', ' ') + ": " + ex);
            }
        }
        return out;
    }

    /** A few lines about how the money works, for the language model. */
    public static String primer() {
        return "Everything is priced in diamonds (1 diamond = 1 DIA). Shops sell at the list price and buy at "
                + Math.round(BUY_RATE * 100) + "% of it. Fractions of a diamond go on a tab (credit) that any companion honours.";
    }
}
