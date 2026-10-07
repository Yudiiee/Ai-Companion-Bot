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
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where every ore is and what it takes to mine it: the ore index every companion remembers. The
 * index ships with the mod ({@code assets/ai-companion/ores.txt}); {@code config/ai-companion/ores.txt}
 * holds your own lines on top of it (a line there wins for its ID). One ore per line:
 * <pre>ORE | ID: minecraft:iron_ore | DIM: overworld | Y_RANGE: -64 to 320 | OPTIMAL_Y: 16, 232 | SHAPE: TRIANGULAR | ... | MIN_TOOL: STONE | DROPS: raw_iron</pre>
 * and the mining plans:
 * <pre>STRATEGY | GOAL: diamond_mining | TARGET_Y: -59 | TECHNIQUE: strip_mine_or_caving | NOTE: ...</pre>
 * The mine's hubs go at the best levels in here. Plain Java, no game classes.
 */
public final class OreBook {

    private OreBook() {}

    /** Pickaxe tiers, as the mod counts them (1 wood ... 5 netherite). */
    static final String[] TIERS = {"", "WOODEN", "STONE", "IRON", "DIAMOND", "NETHERITE"};

    /**
     * One line of the index. {@code best}: the levels it's most common at (a range "32 to 256" is
     * {@code {32, 256}} with {@code spread}); {@code tier}: the pickaxe it needs (1 wood ... 5 netherite).
     */
    public record Ore(String id, String dim, int min, int max, int[] best, boolean spread, String shape, String biome,
                      String air, int tier, String drops, String extra) {

        /** "iron", "deepslate iron", "ancient debris", "iron vein", "amethyst geode"... */
        public String name() { return nameOf(id); }

        /** A deepslate copy of an ore that's listed in plain stone too. */
        boolean deepslate() { return id.startsWith("deepslate_"); }

        /** Only in one sort of place (badlands, mountain peaks)? */
        boolean biomeOnly() { return biome.endsWith("_only") || id.endsWith("_badlands"); }

        /** One of the overworld's own ores (not a deepslate copy, a vein, a geode or a biome special). */
        boolean primary() {
            return dim.equals("overworld") && id.endsWith("_ore") && !deepslate() && !biomeOnly();
        }

        /** The level it's most common at underground (the first peak). */
        int bestY() {
            if (best.length == 0) return (min + max) / 2;
            return best[0];
        }

        /** "best at y 16 (and 232)", "anywhere from y 10 to 117"... */
        String bestText() {
            if (best.length == 0) return "";
            if (spread) return "anywhere from y " + best[0] + " to " + best[best.length - 1] + ", all the same";
            if (best.length == 1) return "best at y " + best[0];
            StringBuilder sb = new StringBuilder("best at y " + best[0] + " (and ");
            for (int i = 1; i < best.length; i++) sb.append(i > 1 ? ", " : "").append(best[i]);
            return sb.append(")").toString();
        }

        /** "iron: y -64 to 320, best at y 16 (and 232), mountains help. needs a stone pickaxe, drops raw iron". */
        public String explain() {
            StringBuilder sb = new StringBuilder(name()).append(": ");
            if (!dim.equals("overworld")) sb.append("in the ").append(dim).append(", ");
            sb.append("y ").append(min).append(" to ").append(max);
            String bt = bestText();
            if (!bt.isEmpty()) sb.append(", ").append(bt);
            String where = biomeText();
            if (!where.isEmpty()) sb.append(", ").append(where);
            if (air.startsWith("100") || air.startsWith("50")) sb.append(", ").append(air.startsWith("100") ? "never next to air (dig into the walls)" : "often skipped next to air");
            if (tier > 0) sb.append(". needs ").append(article(TIERS[tier].toLowerCase(Locale.ROOT))).append(" pickaxe or better");
            if (!drops.isEmpty()) sb.append(", drops ").append(drops.replace('_', ' '));
            if (!extra.isEmpty()) sb.append(" (").append(extra).append(")");
            return sb.toString();
        }

        private String biomeText() {
            String b = biome.toLowerCase(Locale.ROOT);
            if (b.isEmpty() || b.equals("none") || b.startsWith("all_")) return "";
            if (b.endsWith("_only")) return "only in " + b.substring(0, b.length() - 5).replace('_', ' ');
            return "more in " + b.replace('_', ' ');
        }
    }

    /** One plan from the index: what to aim for, at what level, how. */
    public record Strategy(String goal, int[] y, String technique, String note) {

        /** "diamond", "iron early", "netherite"... */
        String what() {
            return goal.replaceAll("_mining", "").replaceAll("_mass$", "").replace('_', ' ').trim();
        }

        String explain() {
            String at = y.length == 0 ? "" : y.length == 1 ? "y " + y[0] : "y " + y[0] + " to " + y[y.length - 1];
            return what() + ": " + at + ", " + technique.replace('_', ' ') + (note.isEmpty() ? "" : ". " + note);
        }
    }

    // ------------------------------------------------------------------------
    // Reading the index
    // ------------------------------------------------------------------------

    private static final Pattern NUM = Pattern.compile("-?\\d+");

    private static Map<String, String> fields(String line) {
        Map<String, String> f = new LinkedHashMap<>();
        for (String part : line.split("\\|")) {
            int c = part.indexOf(':');
            if (c <= 0) continue;
            f.put(part.substring(0, c).trim().toUpperCase(Locale.ROOT), part.substring(c + 1).trim());
        }
        return f;
    }

    private static int[] numbers(String s) {
        if (s == null) return new int[0];
        List<Integer> out = new ArrayList<>();
        Matcher m = NUM.matcher(s);
        while (m.find()) {
            try { out.add(Integer.parseInt(m.group())); } catch (NumberFormatException ignored) { }
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** "WOODEN" -> 1 ... "NETHERITE" -> 5 (0: doesn't say). */
    static int tierOf(String tool) {
        if (tool == null) return 0;
        String t = tool.trim().toUpperCase(Locale.ROOT);
        if (t.startsWith("WOOD") || t.startsWith("GOLD")) return 1;
        for (int i = 1; i < TIERS.length; i++) if (t.startsWith(TIERS[i])) return i;
        return 0;
    }

    /** One ore line, or null if the line isn't one (or is mangled). */
    public static Ore parseOre(String line) {
        try {
            if (line == null || !line.trim().toUpperCase(Locale.ROOT).startsWith("ORE")) return null;
            Map<String, String> f = fields(line);
            String id = f.getOrDefault("ID", "").toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "").trim();
            if (!id.matches("[a-z0-9_]+")) return null;
            int[] range = numbers(f.get("Y_RANGE"));
            if (range.length < 2) return null;
            int min = Math.min(range[0], range[1]), max = Math.max(range[0], range[1]);
            String opt = f.getOrDefault("OPTIMAL_Y", "");
            int[] best = numbers(opt);
            boolean spread = opt.toLowerCase(Locale.ROOT).contains(" to ");
            String dim = f.getOrDefault("DIM", "overworld").toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "").replace("the_", "").trim();
            StringBuilder extra = new StringBuilder();
            for (String k : new String[]{"HOST_BLOCK", "MIX", "LAYERS"}) {
                String v = f.get(k);
                if (v == null || v.isBlank()) continue;
                if (extra.length() > 0) extra.append("; ");
                extra.append(k.equals("HOST_BLOCK") ? "in " : k.equals("MIX") ? "" : "layers: ").append(v.replace('_', ' '));
            }
            return new Ore(id, dim, min, max, best, spread, f.getOrDefault("SHAPE", "").toLowerCase(Locale.ROOT),
                    f.getOrDefault("BIOME_BIAS", "none"), f.getOrDefault("AIR_DISCARD", "0%"), tierOf(f.get("MIN_TOOL")),
                    f.getOrDefault("DROPS", "").toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", ""), extra.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One strategy line, or null. */
    public static Strategy parseStrategy(String line) {
        try {
            if (line == null || !line.trim().toUpperCase(Locale.ROOT).startsWith("STRATEGY")) return null;
            Map<String, String> f = fields(line);
            String goal = f.getOrDefault("GOAL", "").toLowerCase(Locale.ROOT).trim();
            if (goal.isEmpty()) return null;
            return new Strategy(goal, numbers(f.get("TARGET_Y")), f.getOrDefault("TECHNIQUE", "").toLowerCase(Locale.ROOT),
                    f.getOrDefault("NOTE", "").trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static volatile Map<String, Ore> byId = Map.of();
    private static volatile Map<String, Strategy> byGoal = Map.of();
    private static volatile long loadedAt;
    private static volatile long fileStamp = -1;

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
        List<String> lines = new ArrayList<>();
        try (InputStream in = OreBook.class.getResourceAsStream("/assets/ai-companion/ores.txt")) {
            if (in != null) lines.addAll(readLines(in));
        } catch (IOException ignored) { }
        if (file != null && Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                lines.addAll(readLines(in));
            } catch (IOException ignored) { }
        }
        use(lines);
    }

    /** Uses these lines (a later line for the same ID or goal wins). */
    static synchronized void use(List<String> lines) {
        Map<String, Ore> o = new LinkedHashMap<>();
        Map<String, Strategy> s = new LinkedHashMap<>();
        for (String l : lines) {
            Ore ore = parseOre(l);
            if (ore != null) { o.put(ore.id(), ore); continue; }
            Strategy st = parseStrategy(l);
            if (st != null) s.put(st.goal(), st);
        }
        byId = Collections.unmodifiableMap(o);
        byGoal = Collections.unmodifiableMap(s);
        loadedAt = System.currentTimeMillis();
    }

    static List<String> readLines(InputStream in) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        }
        return out;
    }

    private static volatile boolean installed;

    /** {@code config/ai-companion/ores.txt} (overrides only; made the first time), or null. */
    static Path file() {
        try {
            Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("ai-companion");
            Files.createDirectories(dir);
            Path f = dir.resolve("ores.txt");
            if (!installed) {
                installed = true;
                if (!Files.exists(f)) {
                    Files.write(f, List.of("# Your own ore lines for AI Companion, on top of the index that comes with the mod.",
                            "# A line here wins over the mod's line for the same ID (or STRATEGY goal). Same format, e.g.:",
                            "# ORE | ID: minecraft:iron_ore | DIM: overworld | Y_RANGE: -64 to 320 | OPTIMAL_Y: 16, 232 | SHAPE: TRIANGULAR | BIOME_BIAS: none | AIR_DISCARD: 0% | MIN_TOOL: STONE | DROPS: raw_iron",
                            "# The shared mine puts a hub at each overworld ore's best level (OPTIMAL_Y).",
                            "# (the mod's own index is assets/ai-companion/ores.txt inside the mod jar)"), StandardCharsets.UTF_8);
                }
            }
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    public static List<Ore> all() {
        refresh();
        return List.copyOf(byId.values());
    }

    public static List<Strategy> strategies() {
        refresh();
        return List.copyOf(byGoal.values());
    }

    // ------------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------------

    /** "iron_ore" -> "iron", "deepslate_iron_ore" -> "deepslate iron", "ancient_debris_cluster" -> "ancient debris"... */
    static String nameOf(String id) {
        String n = id;
        if (n.startsWith("ancient_debris")) return "ancient debris";
        if (n.equals("nether_quartz_ore")) return "quartz";
        n = n.replaceFirst("_ore_badlands$", " (badlands)").replaceFirst("_ore_vein$", " vein").replaceFirst("_ore$", "");
        return n.replace('_', ' ').trim();
    }

    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        String[][] a = {
                {"diamonds", "diamond_ore"}, {"diamond", "diamond_ore"}, {"dias", "diamond_ore"}, {"dia", "diamond_ore"},
                {"iron", "iron_ore"}, {"raw iron", "iron_ore"}, {"iron ingots", "iron_ore"},
                {"gold", "gold_ore"}, {"raw gold", "gold_ore"}, {"gold ingots", "gold_ore"},
                {"copper", "copper_ore"}, {"raw copper", "copper_ore"},
                {"coal", "coal_ore"}, {"lapis", "lapis_ore"}, {"lapis lazuli", "lapis_ore"},
                {"redstone", "redstone_ore"}, {"redstone dust", "redstone_ore"}, {"emerald", "emerald_ore"}, {"emeralds", "emerald_ore"},
                {"quartz", "nether_quartz_ore"}, {"nether quartz", "nether_quartz_ore"}, {"nether gold", "nether_gold_ore"},
                {"ancient debris", "ancient_debris_cluster"}, {"debris", "ancient_debris_cluster"}, {"netherite", "ancient_debris_cluster"},
                {"netherite scrap", "ancient_debris_cluster"}, {"amethyst", "amethyst_geode"}, {"geode", "amethyst_geode"},
                {"geodes", "amethyst_geode"}, {"amethyst geode", "amethyst_geode"}, {"iron vein", "iron_ore_vein"},
                {"iron veins", "iron_ore_vein"}, {"copper vein", "copper_ore_vein"}, {"copper veins", "copper_ore_vein"},
                {"badlands gold", "gold_ore_badlands"},
        };
        for (String[] p : a) ALIASES.put(p[0], p[1]);
    }

    /** The ore a phrase is about ("diamonds", "deepslate iron", "iron ore", "netherite"), or null. */
    public static Ore find(String phrase) {
        if (phrase == null) return null;
        refresh();
        String q = phrase.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_ ]", " ")
                .replaceAll("\\b(the|a|an|some|any|minecraft)\\b", " ").trim().replaceAll("\\s+", " ");
        if (q.isEmpty()) return null;
        String id = q.replace(' ', '_');
        for (String c : new String[]{id, id + "_ore", id.replaceFirst("_ores?$", "_ore"), id.replaceFirst("s$", "") + "_ore"}) {
            Ore o = byId.get(c);
            if (o != null) return o;
        }
        String alias = ALIASES.get(q.replaceFirst(" ores?$", "").replaceFirst(" blocks?$", ""));
        if (alias != null && byId.get(alias) != null) return byId.get(alias);
        return null;
    }

    /** The ore a block id is (iron_ore, deepslate_gold_ore, ancient_debris...), or null. */
    static Ore forBlock(String blockId) {
        if (blockId == null) return null;
        refresh();
        if (blockId.equals("ancient_debris")) return byId.get("ancient_debris_cluster");
        Ore o = byId.get(blockId);
        return o != null && o.id().endsWith("_ore") ? o : null;
    }

    /** The pickaxe a block needs per the index (1 wood ... 5 netherite), or 0 if it isn't in it. */
    public static int tierFor(String blockId) {
        Ore o = forBlock(blockId);
        return o == null ? 0 : o.tier();
    }

    /** The pickaxe for an ore by name ("iron" -> 2), or {@code fallback}. */
    public static int tier(String name, int fallback) {
        Ore o = find(name);
        return o == null || o.tier() <= 0 ? fallback : o.tier();
    }

    /** The level to mine an ore at (its first peak), or {@code fallback}. */
    public static int bestY(String name, int fallback) {
        Ore o = find(name);
        return o == null || o.best().length == 0 ? fallback : o.bestY();
    }

    // ------------------------------------------------------------------------
    // The mine's hubs
    // ------------------------------------------------------------------------

    /**
     * Where the mine stops for a hub, top first: the best level of each of the overworld's own
     * ores (nothing lower than {@code bottom}: the deepest ones share the hub at the bottom).
     * Level -> the ores it's for.
     */
    public static Map<Integer, List<String>> hubLevels(int bottom) {
        refresh();
        TreeMap<Integer, List<String>> out = new TreeMap<>(Collections.reverseOrder());
        for (Ore o : byId.values()) {
            if (!o.primary() || o.best().length == 0) continue;
            int y = Math.max(bottom, o.bestY());
            out.computeIfAbsent(y, k -> new ArrayList<>()).add(o.name());
        }
        out.computeIfAbsent(bottom, k -> new ArrayList<>());
        return out;
    }

    // ------------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------------

    private static final Pattern ASK = Pattern.compile(
            "^(?:(?:hey|yo|so|ok|okay|and|but|bro|pls|please|um|uh|also)[,\\s]+)*(?:"
            + "where (?:is|are|do (?:i|you|we) (?:find|get)|can (?:i|you|we) (?:find|get)|does|do|should (?:i|we) (?:look for|mine|dig for)) (?:the |some )?(.+?)(?: spawn| generate| be| found| at)?"
            + "|(?:what|which) (?:y ?level|y|level|layer|height|depth) (?:is|are|for|has|to (?:mine|find|get)|do (?:i|you|we) (?:mine|find|get|need for)|should (?:i|we) (?:mine|dig|strip mine) (?:at )?for) (?:the |some )?(.+?)(?: at| on)?"
            + "|(?:best|optimal|good) (?:y ?level|y|level|layer|height|spot) (?:for|to (?:mine|find|get)) (?:the |some )?(.+?)"
            + "|how deep (?:is|are|do (?:i|you|we) (?:go|dig|mine) for|for) (?:the |some )?(.+?)"
            + "|what (?:pick|pickaxe|tool) (?:do (?:i|you|we) need|is needed|does it take|to use) (?:for|to (?:mine|get|break)) (?:the |some )?(.+?)"
            + "|what (?:pick|pickaxe|tool) (?:for|mines|breaks) (?:the |some )?(.+?)"
            + "|(?:can (?:i|you|we) mine|does) (?:the |some )?(.+?) (?:with|need|take|require)s? (?:an? )?(?:wood(?:en)?|stone|iron|gold(?:en)?|diamond|netherite)? ?(?:pick|pickaxe)"
            + "|(?:the )?(.+?) (?:y ?level|y|level|spawn rates?|distribution))[?!.\\s]*$");

    /** "where do i find diamonds" -> "diamonds" (the ore it's about), or null if it's not that sort of question. */
    public static String question(String m) {
        if (m == null || m.length() > 90) return null;
        String t = m.toLowerCase(Locale.ROOT).trim();
        Matcher h = ASK.matcher(t);
        if (!h.find()) return null;
        for (int g = 1; g <= h.groupCount(); g++) {
            if (h.group(g) != null && !h.group(g).isBlank()) {
                String q = h.group(g).trim();
                return find(q) != null ? q : null;
            }
        }
        return null;
    }

    /** "where are diamonds", "what pickaxe for gold": the answer from the index, or null if it doesn't know the ore. */
    public static String answer(String phrase) {
        Ore o = find(phrase);
        if (o == null) return null;
        StringBuilder sb = new StringBuilder(o.explain());
        // the plain-stone ore: mention its deepslate copy, which is where most of the deep ones are
        Ore deep = byId.get("deepslate_" + o.id());
        if (deep != null && deep.best().length > 0) sb.append(". deepslate ").append(o.name()).append(" peaks at y ").append(deep.bestY());
        Strategy s = strategyFor(o.name());
        if (s != null && !s.note().isEmpty()) sb.append(". tip: ").append(s.note().substring(0, 1).toLowerCase(Locale.ROOT)).append(s.note().substring(1));
        return sb.toString();
    }

    static Strategy strategyFor(String name) {
        refresh();
        String n = name.equals("ancient debris") ? "netherite" : name.replace(' ', '_');
        for (Strategy s : byGoal.values()) if (s.goal().startsWith(n + "_")) return s;
        return null;
    }

    /** Index lines for ores a bit of chat mentions (for the language model's prompt), up to {@code max}. */
    public static List<String> mentioned(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        refresh();
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ") + " ";
        List<String> done = new ArrayList<>();
        for (Map.Entry<String, String> a : ALIASES.entrySet()) {
            if (out.size() >= max) break;
            if (!t.contains(" " + a.getKey() + " ") || done.contains(a.getValue())) continue;
            Ore o = byId.get(a.getValue());
            if (o == null) continue;
            done.add(a.getValue());
            out.add(o.explain());
        }
        return out;
    }

    /** One line with every ore's best level and pickaxe, for the language model. */
    public static String primer() {
        refresh();
        StringBuilder sb = new StringBuilder();
        for (Ore o : byId.values()) {
            if (!o.primary() && !o.dim().equals("nether")) continue;
            if (o.id().equals("ancient_debris_scatter")) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(o.name()).append(o.dim().equals("nether") ? " (nether)" : "").append(" y ").append(o.bestY())
                    .append(" (").append(o.tier() > 0 ? TIERS[o.tier()].toLowerCase(Locale.ROOT) : "any").append(" pick)");
        }
        return sb.toString();
    }

    private static String article(String w) {
        return (w.matches("^[aeiou].*") ? "an " : "a ") + w;
    }
}
