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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The recipes every companion remembers: read from {@code config/ai-companion/recipes.txt}
 * (put there from the mod the first time; add your own lines) on top of the ones that ship
 * with the mod. One recipe per line:
 * <pre>RECIPE | ID: minecraft:hopper | TYPE: shaped | WORKSTATION: crafting_table | INPUTS: [...] | OUTPUT: 1x minecraft:hopper</pre>
 * The bots craft from these (for builds and when asked), explain them ("how do i make a
 * hopper"), and the language model gets the ones a conversation is about.
 */
public final class RecipeBook {

    private RecipeBook() {}

    /**
     * A remembered recipe. {@code in}: item -> count ("#planks" style tags for any of a kind).
     * {@code station}: crafting_table, inventory, furnace, blast_furnace, smoker, smithing_table...
     */
    public record Recipe(String id, String type, String station, Map<String, Integer> in, int out, String item, String pattern) {

        /** Cooked in a furnace (or a blast furnace or smoker). */
        public boolean cooked() {
            return type.equals("smelting") || type.equals("blasting") || type.equals("smoking") || type.equals("campfire_cooking");
        }

        public boolean needsTable() { return station.equals("crafting_table"); }

        /** "hopper: 5 iron ingots and a chest, at a crafting table (makes 1)". */
        public String explain() {
            StringBuilder sb = new StringBuilder(item.replace('_', ' ')).append(": ");
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : in.entrySet()) parts.add(e.getValue() + " " + label(e.getKey(), e.getValue()));
            sb.append(join(parts));
            if (cooked()) sb.append(", cooked in a ").append(station.replace('_', ' '));
            else if (type.equals("smithing")) sb.append(", on a smithing table");
            else if (needsTable()) sb.append(", at a crafting table");
            else sb.append(", right in the inventory");
            if (pattern.contains("/") && !cooked()) {
                String rows = pattern.replace("minecraft:", "").replaceAll("\\bnone\\b", "empty").replace('_', ' ')
                        .replaceAll("\\s*,\\s*", ", ").replaceAll("\\s*/\\s*", " | ");
                sb.append(" (rows: ").append(rows).append(")");
            }
            sb.append(out > 1 ? ". makes " + out : "");
            return sb.toString();
        }
    }

    static String label(String item, int n) {
        String s = switch (item) {
            case "#planks" -> "planks (any wood)";
            case "#logs" -> "logs (any wood)";
            case "#wooden_slab" -> "wooden slabs (any wood)";
            case "#stone_crafting" -> "cobblestone (or blackstone or cobbled deepslate)";
            case "#soul_fire_base" -> "soul sand or soul soil";
            default -> item.replace('_', ' ');
        };
        return s;
    }

    private static String join(List<String> parts) {
        if (parts.size() <= 1) return String.join("", parts);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    // ------------------------------------------------------------------------
    // Reading the file
    // ------------------------------------------------------------------------

    private static final Pattern FIELD = Pattern.compile("\\b(ID|TYPE|WORKSTATION|INPUTS|OUTPUT)\\s*:\\s*(.*?)\\s*(?=\\||$)");
    private static final Pattern OUTPUT = Pattern.compile("^(\\d+)\\s*x\\s*(?:[a-z0-9_.\\-]+:)?([a-z0-9_/.\\-]+)$");

    /** One line of the file, or null if it isn't a recipe line. */
    public static Recipe parseLine(String line) {
        if (line == null) return null;
        String l = line.trim();
        if (!l.startsWith("RECIPE")) return null;
        Map<String, String> f = new LinkedHashMap<>();
        Matcher m = FIELD.matcher(l);
        while (m.find()) f.put(m.group(1), m.group(2).trim());
        String out = f.get("OUTPUT"), inputs = f.get("INPUTS");
        if (out == null || inputs == null) return null;
        Matcher om = OUTPUT.matcher(out.toLowerCase(Locale.ROOT));
        if (!om.matches()) return null;
        Map<String, Integer> in = parseInputs(inputs);
        if (in.isEmpty()) return null;
        String id = f.getOrDefault("ID", om.group(2)).replaceFirst("^[a-z0-9_.\\-]+:", "");
        String type = f.getOrDefault("TYPE", "shaped").toLowerCase(Locale.ROOT);
        String station = f.getOrDefault("WORKSTATION", "crafting_table").toLowerCase(Locale.ROOT);
        String pattern = inputs.replaceAll("^\\[|\\]$", "").trim();
        return new Recipe(id, type, station, in, Integer.parseInt(om.group(1)), om.group(2), pattern);
    }

    private static final Pattern POS = Pattern.compile("^(top_center|top_left|top_right|top|mid_center|middle|mid|center|"
            + "bottom_center|bot_center|bottom|bot|left|right|template|base|add|row\\d?)\\s*:\\s*");
    /** Shapes and how many cells they fill. */
    private static final Map<String, Integer> SHAPES = Map.of("ring", 8, "cross", 4, "stairs_shape", 6, "boat_shape", 5,
            "u_shape", 7, "n_shape", 5, "chestplate_shape", 8, "pants_shape", 7, "boots_shape", 4);

    /**
     * The ingredient list of a recipe, counted ("[iron_ingot,none,iron_ingot / iron_ingot,chest,iron_ingot / none,iron_ingot,none]"
     * -> iron_ingot 5, chest 1). Understands grids, "x*3", "2x2:x", "1,1,1:x", "ring:x", "ring:x with center:y",
     * "cross:x*4 around y", "x cross around y" and named cells ("top:", "template:"...).
     */
    public static Map<String, Integer> parseInputs(String raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        String s = raw.trim().replaceAll("^\\[|\\]$", "").trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
        // "#logs cross around furnace" -> "cross:#logs around furnace"
        s = s.replaceAll("^(\\S+)\\s+(cross|ring)\\s+around\\s+", "$2:$1 around ");
        // "1,1,1:x" -> "3*x", "2x3:x" -> "6*x", "1x x" -> "1*x"
        Matcher rows = Pattern.compile("(\\d(?:\\s*,\\s*\\d)+)\\s*:\\s*([#a-z0-9_]+)").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (rows.find()) {
            int n = 0;
            for (String d : rows.group(1).split(",")) n += Integer.parseInt(d.trim());
            rows.appendReplacement(sb, rows.group(2) + "*" + n);
        }
        rows.appendTail(sb);
        s = sb.toString();
        s = s.replaceAll("\\b(\\d)x(\\d)\\s*:\\s*", "__G$1_$2__");
        Matcher g = Pattern.compile("__G(\\d)_(\\d)__([#a-z0-9_]+)").matcher(s);
        sb = new StringBuilder();
        while (g.find()) g.appendReplacement(sb, g.group(3) + "*" + (Integer.parseInt(g.group(1)) * Integer.parseInt(g.group(2))));
        g.appendTail(sb);
        s = sb.toString().replaceAll("\\b(\\d+)x\\s+([#a-z0-9_]+)", "$2*$1");

        // "ring:x with center:y, bottom:z": the ring gives up a cell for each thing on it (not the centre)
        String extra = null;
        int withAt = s.indexOf(" with ");
        if (withAt > 0) {
            extra = s.substring(withAt + 6);
            s = s.substring(0, withAt);
        }
        String around = null;
        int aroundAt = s.indexOf(" around ");
        if (aroundAt > 0) {
            around = s.substring(aroundAt + 8);
            s = s.substring(0, aroundAt);
        }
        int ringLoss = 0;
        if (extra != null) {
            for (String part : extra.split("[,/]")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                if (!p.startsWith("center")) ringLoss++;
                add(out, p);
            }
        }
        if (around != null) add(out, around.trim());
        for (String part : s.split("[,/]")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String shape = null;
            Matcher sm = Pattern.compile("^([a-z_]+)\\s*:\\s*(.*)$").matcher(p);
            if (sm.matches() && SHAPES.containsKey(sm.group(1))) {
                shape = sm.group(1);
                p = sm.group(2).trim();
            }
            if (shape != null && !p.contains("*")) {
                int n = SHAPES.get(shape) - (shape.equals("ring") ? ringLoss : 0);
                p = p + "*" + n;
            }
            add(out, p);
        }
        return out;
    }

    private static void add(Map<String, Integer> out, String token) {
        String t = token.trim();
        Matcher pm;
        while ((pm = POS.matcher(t)).find()) t = t.substring(pm.end()).trim();
        if (t.isEmpty() || t.equals("none") || t.equals("empty") || t.equals("air")) return;
        int n = 1;
        Matcher star = Pattern.compile("^(.*?)\\s*\\*\\s*(\\d+)$").matcher(t);
        if (star.matches()) {
            t = star.group(1).trim();
            n = Integer.parseInt(star.group(2));
        }
        t = alias(t);
        if (!t.matches("#?[a-z0-9_]+")) return;
        out.merge(t, n, Integer::sum);
    }

    /** The file's tags and the bots' own names for them. */
    static String alias(String t) {
        return switch (t) {
            case "#wooden_slabs" -> "#wooden_slab";
            case "#stone_crafting_materials", "#stone_tool_materials" -> "#stone_crafting";
            case "soul_soil_or_sand", "soul_sand_or_soil", "#soul_fire_base_blocks" -> "#soul_fire_base";
            case "chain" -> "iron_chain"; // renamed when copper chains came in
            default -> t;
        };
    }

    // ------------------------------------------------------------------------
    // The book
    // ------------------------------------------------------------------------

    private static volatile Map<String, List<Recipe>> byItem = Map.of();
    private static volatile Map<String, Recipe> byId = Map.of();
    private static volatile long loadedAt;
    private static volatile long fileStamp = -1;

    /** All recipes for an item, crafting first (then smithing, then cooking). */
    public static List<Recipe> forItem(String item) {
        refresh();
        return byItem.getOrDefault(item, List.of());
    }

    /** The recipe a bot uses to make an item: the first crafting one, else the first at all. */
    public static Recipe craftingFor(String item) {
        for (Recipe r : forItem(item)) if (!r.cooked()) return r;
        return null;
    }

    public static Recipe cookingFor(String item) {
        for (Recipe r : forItem(item)) if (r.cooked()) return r;
        return null;
    }

    public static int size() {
        refresh();
        return byId.size();
    }

    /** Loads the recipes (the mod's own, then the config file's on top). */
    static synchronized void refresh() {
        long now = System.currentTimeMillis();
        if (now - loadedAt < 5000 && !byId.isEmpty()) return;
        loadedAt = now;
        Path file = file();
        long stamp = -2;
        try {
            if (file != null && Files.isRegularFile(file)) stamp = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ignored) { }
        if (stamp == fileStamp && !byId.isEmpty()) return;
        fileStamp = stamp;
        List<Recipe> all = new ArrayList<>();
        try (InputStream in = RecipeBook.class.getResourceAsStream("/assets/ai-companion/recipes.txt")) {
            if (in != null) all.addAll(read(in));
        } catch (IOException ignored) { }
        if (file != null && Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                all.addAll(read(in));
            } catch (IOException ignored) { }
        }
        use(all);
    }

    /** Uses these recipes (later ones with the same id win). */
    static synchronized void use(List<Recipe> all) {
        Map<String, Recipe> ids = new LinkedHashMap<>();
        for (Recipe r : all) ids.put(r.id(), r);
        Map<String, List<Recipe>> items = new LinkedHashMap<>();
        for (Recipe r : ids.values()) items.computeIfAbsent(r.item(), k -> new ArrayList<>()).add(r);
        for (List<Recipe> l : items.values()) {
            l.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        }
        Map<String, List<Recipe>> frozen = new LinkedHashMap<>();
        items.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        byId = Collections.unmodifiableMap(ids);
        byItem = Collections.unmodifiableMap(frozen);
        loadedAt = System.currentTimeMillis();
    }

    private static int rank(Recipe r) {
        if (r.cooked()) return r.type().equals("smelting") ? 3 : 4;
        if (r.type().equals("smithing")) return 2;
        return r.id().contains("_from_") ? 1 : 0; // "planks from stripped logs" after the plain one
    }

    static List<Recipe> read(InputStream in) throws IOException {
        List<Recipe> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                Recipe rec = parseLine(line);
                if (rec != null) out.add(rec);
            }
        }
        return out;
    }

    private static volatile boolean installed;

    /** {@code config/ai-companion/recipes.txt} (copied out of the mod the first time), or null. */
    static Path file() {
        try {
            Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("ai-companion");
            Files.createDirectories(dir);
            Path f = dir.resolve("recipes.txt");
            if (!installed) {
                installed = true;
                if (!Files.exists(f)) {
                    try (InputStream in = RecipeBook.class.getResourceAsStream("/assets/ai-companion/recipes.txt")) {
                        if (in != null) Files.copy(in, f);
                    }
                }
            }
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------------
    // Finding a recipe by what someone calls the item
    // ------------------------------------------------------------------------

    /** "a hopper", "4 lanterns", "iron pick", "the blast furnace" -> the recipe, or null. */
    public static Recipe find(String phrase) {
        String item = itemFor(phrase);
        if (item == null) return null;
        List<Recipe> rs = forItem(item);
        return rs.isEmpty() ? null : rs.get(0);
    }

    /** The item id a phrase means, if the book has it. */
    public static String itemFor(String phrase) {
        if (phrase == null) return null;
        refresh();
        String q = phrase.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_ ]", " ")
                .replaceAll("\\b(a|an|the|some|me|us|of|few|couple|pair|set|my|your|new)\\b", " ")
                .replaceAll("^\\s*\\d+\\s*", " ").trim().replaceAll("\\s+", " ");
        if (q.isEmpty()) return null;
        q = q.replaceAll("\\bpick\\b", "pickaxe").replaceAll("\\bchestplates?\\b", "chestplate");
        List<String> cands = new ArrayList<>();
        String id = q.replace(' ', '_');
        cands.add(id);
        if (id.endsWith("ies")) cands.add(id.substring(0, id.length() - 3) + "y");
        if (id.endsWith("es")) cands.add(id.substring(0, id.length() - 2));
        if (id.endsWith("s")) cands.add(id.substring(0, id.length() - 1));
        for (String c : cands) if (byItem.containsKey(c)) return c;
        return null;
    }

    private static final Pattern HOW = Pattern.compile(
            "\\b(?:how (?:do|can|would|should) (?:i|you|we|u) (?:make|craft|get|smelt|cook)|how (?:is|are|do you make) "
            + "|what(?:'s| is) the recipe for|recipe for|how to (?:make|craft)|what do (?:i|you|we) need (?:to|for) (?:make|craft)(?:ing)?"
            + "|what goes into)\\s+(?:a |an |the |some )?(.+?)(?: made| crafted)?[?!.\\s]*$");

    /** "how do i make a hopper" -> the item phrase, or null. */
    public static String howTo(String m) {
        if (m == null || m.length() > 90) return null;
        Matcher h = HOW.matcher(m.toLowerCase(Locale.ROOT));
        return h.find() ? h.group(1) : null;
    }

    /** The answer to "how do i make X". */
    public static String answer(String phrase) {
        String item = itemFor(phrase);
        if (item == null) return null;
        List<Recipe> rs = forItem(item);
        if (rs.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(rs.get(0).explain());
        if (rs.size() > 1) {
            Recipe alt = rs.get(1);
            sb.append(". or: ").append(alt.explain().substring(alt.explain().indexOf(':') + 2));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Recipes for items a bit of chat mentions (for the language model's prompt), up to {@code max}. */
    public static List<Recipe> mentioned(String text, int max) {
        List<Recipe> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        refresh();
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ") + " ";
        for (Map.Entry<String, List<Recipe>> e : byItem.entrySet()) {
            String name = e.getKey().replace('_', ' ');
            if (t.contains(" " + name + " ") || t.contains(" " + name + "s ")) {
                out.add(e.getValue().get(0));
                if (out.size() >= max) break;
            }
        }
        return out;
    }
}
