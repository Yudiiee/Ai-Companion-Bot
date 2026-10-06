package io.github.yudiiee.aicompanion.GameAI.human;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the companions know about trees and wood, like a player who's played a while: which
 * tree gives which wood, where it grows, and what colour it comes out (planks, bark, the
 * stripped log). Builds care: spruce and dark oak look nothing alike, so a design asks for
 * the exact wood, and when that tree can't be had the closest colour stands in (and the bot
 * says so). Plain Java, no game classes.
 */
public final class Woods {

    private Woods() {}

    /**
     * A kind of wood. {@code rgb}: the planks' colour, for "which looks closest".
     * {@code nether}: grows only in the Nether (and doesn't burn).
     */
    public record Wood(String species, String tree, String where, String planks, String bark, String stripped, int rgb,
                       boolean nether) {
        public String name() { return species.replace('_', ' '); }
    }

    private static final Map<String, Wood> WOODS = new LinkedHashMap<>();

    private static void w(String sp, String tree, String where, String planks, String bark, String stripped, int rgb, boolean nether) {
        WOODS.put(sp, new Wood(sp, tree, where, planks, bark, stripped, rgb, nether));
    }

    static {
        w("oak", "oak tree (azalea trees have oak logs too)", "plains, forests, swamps, almost everywhere",
                "light tan", "brown-grey", "pale tan", 0xA2824E, false);
        w("spruce", "spruce tree, tall and pointy", "taigas, snowy plains, old growth taigas",
                "medium brown", "dark brown", "warm brown", 0x725430, false);
        w("birch", "birch tree, white bark with black marks", "birch forests and mixed forests",
                "pale yellow, almost cream", "white with black spots", "light yellow", 0xC0AF79, false);
        w("jungle", "jungle tree, some of them huge", "jungles", "pinkish brown", "brown with green moss streaks",
                "pinkish tan", 0xA07350, false);
        w("acacia", "acacia tree, flat top and a bent trunk", "savannas", "bright orange", "grey", "orange", 0xA85A32, false);
        w("dark_oak", "dark oak, a thick 2x2 trunk under a flat canopy", "dark forests", "deep chocolate brown",
                "very dark brown", "dark brown", 0x422B14, false);
        w("mangrove", "mangrove tree, up on roots in the water", "mangrove swamps", "red", "dark red-brown", "red",
                0x753630, false);
        w("cherry", "cherry tree, pink leaves", "cherry groves up in the mountains", "pale pink", "dark plum",
                "pink", 0xE2B2AC, false);
        w("pale_oak", "pale oak, grey and a bit creepy", "pale gardens", "off-white", "grey", "near white",
                0xE3D9D5, false);
        w("bamboo", "bamboo (not really a tree: 9 bamboo make a block)", "jungles and bamboo jungles", "yellow",
                "green-yellow", "yellow", 0xC2AD50, false);
        // new in 26.3 (from the recipe book); colour not known yet, so it's never picked as a stand-in
        w("poplar", "poplar tree (new in 26.3)", "not sure yet, i haven't seen one", "not sure yet", "not sure yet",
                "not sure yet", -1, false);
        w("crimson", "crimson fungus, a giant red mushroom tree", "crimson forests in the Nether", "magenta red",
                "dark red", "red-purple", 0x653046, true);
        w("warped", "warped fungus, a giant teal mushroom tree", "warped forests in the Nether", "teal",
                "dark teal", "teal", 0x2B6863, true);
    }

    public static Wood get(String species) { return WOODS.get(species); }

    public static List<Wood> all() { return new ArrayList<>(WOODS.values()); }

    /** sRGB -> CIE L*a*b*, so "looks close" means close to the eye (hue counts, not just brightness). */
    static double[] lab(int rgb) {
        double[] c = {((rgb >> 16) & 255) / 255.0, ((rgb >> 8) & 255) / 255.0, (rgb & 255) / 255.0};
        for (int i = 0; i < 3; i++) c[i] = c[i] <= 0.04045 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        double x = (c[0] * 0.4124 + c[1] * 0.3576 + c[2] * 0.1805) / 0.95047;
        double y = c[0] * 0.2126 + c[1] * 0.7152 + c[2] * 0.0722;
        double z = (c[0] * 0.0193 + c[1] * 0.1192 + c[2] * 0.9505) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double f(double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }

    /** How different two colours look (CIE76 delta E). */
    static double distance(int a, int b) {
        double[] p = lab(a), q = lab(b);
        return Math.sqrt(Math.pow(p[0] - q[0], 2) + Math.pow(p[1] - q[1], 2) + Math.pow(p[2] - q[2], 2));
    }

    /** Every other wood, closest colour first ("dark_oak" -> spruce, mangrove, crimson...). */
    public static List<String> closest(String species) {
        Wood me = WOODS.get(species);
        List<Wood> others = new ArrayList<>();
        for (Wood x : WOODS.values()) {
            if (!x.species().equals(species) && !x.species().equals("bamboo") && x.rgb() >= 0) others.add(x);
        }
        if (me == null || me.rgb() < 0) return others.stream().map(Wood::species).toList();
        others.sort(Comparator.comparingDouble(x -> distance(me.rgb(), x.rgb())));
        return others.stream().map(Wood::species).toList();
    }

    /** "dark oak" -> "deep chocolate brown planks": how it'll look. */
    public static String look(String species) {
        Wood w = WOODS.get(species);
        return w == null ? species.replace('_', ' ') : w.planks();
    }

    /** One line about a kind of wood, in chat style. */
    public static String describe(String species, String seen) {
        Wood w = WOODS.get(species);
        if (w == null) return "not sure what wood that is";
        String what = seen == null ? w.name() : seen;
        return (what + ": " + w.tree() + ". grows in " + w.where() + ". the planks are " + w.planks()
                + ", bark's " + w.bark() + (w.nether() ? ", and it won't burn" : "")).toLowerCase(Locale.ROOT);
    }

    /** The species a log, leaf, plank or other wooden block belongs to, or null. */
    public static String speciesOf(String path) {
        String[] w = Schematic.Rules.wood(path);
        if (w != null) return w[0];
        if (path.equals("azalea_leaves") || path.equals("flowering_azalea_leaves")) return "oak";
        if (path.endsWith("_leaves")) {
            String sp = path.substring(0, path.length() - 7);
            return WOODS.containsKey(sp) ? sp : null;
        }
        if (path.endsWith("_sapling")) {
            String sp = path.substring(0, path.length() - 8);
            return WOODS.containsKey(sp) ? sp : null;
        }
        if (path.equals("crimson_fungus") || path.equals("nether_wart_block")) return "crimson";
        if (path.equals("warped_fungus") || path.equals("warped_wart_block")) return "warped";
        if (path.equals("mangrove_roots") || path.equals("muddy_mangrove_roots") || path.equals("mangrove_propagule")) return "mangrove";
        if (path.equals("bamboo")) return "bamboo";
        return null;
    }

    /** The kinds of wood a design uses, most used first: {"spruce": 900, "dark_oak": 400}. */
    public static Map<String, Integer> palette(Map<String, Integer> blockCounts) {
        Map<String, Integer> out = new LinkedHashMap<>();
        List<Map.Entry<String, Integer>> es = new ArrayList<>();
        Map<String, Integer> tmp = new java.util.HashMap<>();
        for (Map.Entry<String, Integer> e : blockCounts.entrySet()) {
            String p = e.getKey().startsWith("minecraft:") ? e.getKey().substring(10) : e.getKey();
            String[] w = Schematic.Rules.wood(p);
            if (w != null) tmp.merge(w[0], e.getValue(), Integer::sum);
        }
        es.addAll(tmp.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> e : es) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** "spruce (medium brown) and dark oak (deep chocolate brown)". */
    public static String paletteLine(Map<String, Integer> palette, int max) {
        List<String> parts = new ArrayList<>();
        for (String sp : palette.keySet()) {
            if (parts.size() >= max) break;
            parts.add(sp.replace('_', ' ') + " (" + look(sp) + ")");
        }
        if (parts.isEmpty()) return "";
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    // ------------------------------------------------------------------------
    // Chat: "what tree is that", "what wood is this"
    // ------------------------------------------------------------------------

    private static final java.util.regex.Pattern ASK = java.util.regex.Pattern.compile(
            "\\b(what|which)( kind of| type of| sort of)? (tree|wood|log|logs|planks|leaves)( is| are)? (this|that|these|those|it|here)\\b"
            + "|\\bwhat (is|'s) (this|that) (tree|wood|log)\\b"
            + "|\\b(tell me about|what do you know about|where (do|does|can) (i|you|we) (find|get))( the)? "
            + "(oak|spruce|birch|jungle|acacia|dark oak|mangrove|cherry|pale oak|bamboo|crimson|warped|poplar)( trees?| wood| logs?| planks)?\\b"
            + "|\\b(what|which) (woods?|trees?) (are there|do you know|can you (get|find|chop))\\b");

    private static final java.util.regex.Pattern NAMED = java.util.regex.Pattern.compile(
            "\\b(dark oak|pale oak|oak|spruce|birch|jungle|acacia|mangrove|cherry|bamboo|crimson|warped|poplar)\\b");

    /** A question about trees or wood? */
    public static boolean isQuestion(String m) {
        return m != null && m.length() <= 80 && ASK.matcher(m.toLowerCase(Locale.ROOT)).find();
    }

    /** The answer when the question names a kind ("where do i find dark oak"), or null to look around for one. */
    public static String answerNamed(String m) {
        String q = m.toLowerCase(Locale.ROOT);
        if (q.matches(".*\\b(what|which) (woods?|trees?) (are there|do you know|can you (get|find|chop))\\b.*")) {
            StringBuilder sb = new StringBuilder("there's ");
            List<Wood> ws = all();
            for (int i = 0; i < ws.size(); i++) {
                Wood w = ws.get(i);
                if (i > 0) sb.append(i == ws.size() - 1 ? " and " : ", ");
                sb.append(w.name()).append(" (").append(w.planks()).append(")");
            }
            return sb.append(". crimson and warped only grow in the nether").toString();
        }
        java.util.regex.Matcher n = NAMED.matcher(q);
        if (!n.find() || q.matches(".*\\b(this|that|these|those)\\b.*") && !q.contains("about") && !q.contains("find")) return null;
        return describe(n.group(1).replace(' ', '_'), null);
    }
}
