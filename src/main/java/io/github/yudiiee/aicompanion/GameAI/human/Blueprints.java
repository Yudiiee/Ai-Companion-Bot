package io.github.yudiiee.aicompanion.GameAI.human;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The schematics folder ({@code config/ai-companion/schematics}): every {@code .schem},
 * {@code .nbt} and {@code .litematic} in it is something the companions can build ("build the
 * iron farm"). A {@code <same name>.txt} next to a file can give it other names, a description,
 * which layer sits at ground level and which side is the front. Also remembers what each bot
 * built where ({@code <world>/ai-companion/builds.txt}), so it can finish it later, keep a farm
 * harvested, and doesn't dig its own builds up for materials.
 */
public final class Blueprints {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-blueprints");

    static final String[] EXTENSIONS = {".schem", ".schematic", ".nbt", ".litematic"};
    /** Designs that ship with the mod (put in the folder the first time, never again if deleted). */
    static final String[] STARTERS = {"sugar_cane_farm", "cactus_farm", "bamboo_farm", "iron_farm", "medieval_house_1",
            "medieval_house_2", "medieval_house_3", "medieval_house_4", "medieval_house_5", "medieval_house_6",
            "city_plaza", "city_temple", "city_amphitheatre", "city_shop", "city_mall", "city_farm", "city_warehouse"};

    private Blueprints() {}

    /** A design in the folder. {@code ground}: which layer is level with the ground. {@code front}: north/east/south/west. */
    public record Entry(String name, Path file, List<String> aliases, String about, int ground, String front, String note,
                        boolean starter) {
        String fileName() { return file.getFileName().toString(); }
    }

    // ------------------------------------------------------------------------
    // The folder
    // ------------------------------------------------------------------------

    private static volatile boolean installed;

    /** The schematics folder (made, with the starter designs, the first time). Null if there's no config dir. */
    static Path folder() {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("ai-companion").resolve("schematics");
            Files.createDirectories(dir);
            if (!installed) {
                installed = true;
                installStarters(dir);
            }
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void installStarters(Path dir) {
        Path marker = dir.resolve(".installed");
        List<String> done = new ArrayList<>();
        try {
            if (Files.exists(marker)) done.addAll(Files.readAllLines(marker, StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
        boolean changed = false;
        for (String s : STARTERS) {
            if (done.contains(s)) continue; // the player deleted it: leave it gone
            for (String ext : new String[]{".nbt", ".txt"}) {
                Path to = dir.resolve(s + ext);
                if (Files.exists(to)) continue;
                try (InputStream in = Blueprints.class.getResourceAsStream("/assets/ai-companion/schematics/" + s + ext)) {
                    if (in != null) Files.copy(in, to);
                } catch (IOException e) {
                    LOGGER.warn("[blueprints] couldn't add {}{}: {}", s, ext, e.toString());
                }
            }
            done.add(s);
            changed = true;
        }
        if (changed) {
            try {
                Files.write(marker, done, StandardCharsets.UTF_8);
            } catch (IOException ignored) { }
        }
    }

    private static volatile List<Entry> cached = List.of();
    private static volatile long scannedAt;

    /** Everything in the folder (looked at again every few seconds). */
    public static List<Entry> list() {
        long now = System.currentTimeMillis();
        if (now - scannedAt < 3000) return cached;
        scannedAt = now;
        Path dir = folder();
        if (dir == null) return cached = List.of();
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.sorted().forEach(p -> {
                String fn = p.getFileName().toString().toLowerCase(Locale.ROOT);
                for (String ext : EXTENSIONS) {
                    if (fn.endsWith(ext) && Files.isRegularFile(p)) { out.add(entryFor(p)); break; }
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[blueprints] couldn't list {}: {}", dir, e.toString());
        }
        return cached = Collections.unmodifiableList(out);
    }

    /** Reads the side file ({@code name.txt}: "aliases: a, b", "about: ...", "ground: 1", "front: south"). */
    static Entry entryFor(Path file) {
        String base = file.getFileName().toString().replaceFirst("\\.[A-Za-z0-9]+$", "");
        Path side = file.resolveSibling(base + ".txt");
        String name = Schematic.displayName(file.getFileName().toString());
        List<String> aliases = new ArrayList<>();
        String about = "";
        int ground = 0;
        String front = "south";
        String note = "";
        boolean starter = false;
        if (Files.isRegularFile(side)) {
            try {
                for (String line : Files.readAllLines(side, StandardCharsets.UTF_8)) {
                    int c = line.indexOf(':');
                    if (c <= 0 || line.startsWith("#")) continue;
                    String k = line.substring(0, c).trim().toLowerCase(Locale.ROOT), v = line.substring(c + 1).trim();
                    switch (k) {
                        case "name" -> { if (!v.isEmpty()) name = v.toLowerCase(Locale.ROOT); }
                        case "aliases", "alias", "also" -> {
                            for (String a : v.split("[,;]")) if (!a.isBlank()) aliases.add(a.trim().toLowerCase(Locale.ROOT));
                        }
                        case "about", "description" -> about = v;
                        case "note", "when done", "after" -> note = v;
                        case "starter" -> starter = v.toLowerCase(Locale.ROOT).matches("yes|true|1");
                        case "ground" -> {
                            try { ground = Math.max(0, Integer.parseInt(v)); } catch (NumberFormatException ignored) { }
                        }
                        case "front" -> {
                            String f = v.toLowerCase(Locale.ROOT);
                            if (f.matches("north|south|east|west")) front = f;
                        }
                        default -> { }
                    }
                }
            } catch (IOException ignored) { }
        }
        return new Entry(name, file, List.copyOf(aliases), about, ground, front, note, starter);
    }

    private static final Map<Path, SoftReference<Object[]>> PLANS = new ConcurrentHashMap<>();

    /** The design itself (cached until the file changes). Job thread: reading a big file takes a moment. */
    static Schematic plan(Entry e) throws IOException {
        long mod = Files.getLastModifiedTime(e.file()).toMillis(), size = Files.size(e.file());
        SoftReference<Object[]> ref = PLANS.get(e.file());
        Object[] hit = ref == null ? null : ref.get();
        if (hit != null && (long) hit[1] == mod && (long) hit[2] == size) return (Schematic) hit[0];
        Schematic s = Schematic.load(e.file());
        PLANS.put(e.file(), new SoftReference<>(new Object[]{s, mod, size}));
        return s;
    }

    static Entry byFile(String fileName) {
        for (Entry e : list()) if (e.fileName().equals(fileName)) return e;
        return null;
    }

    // ------------------------------------------------------------------------
    // Finding a design by what the player called it
    // ------------------------------------------------------------------------

    private static final java.util.Set<String> FILLER = java.util.Set.of("a", "an", "the", "my", "our", "your", "me", "us",
            "some", "another", "new", "one", "more", "that", "this", "big", "small", "little", "simple", "basic");
    private static final java.util.Set<String> GENERIC = java.util.Set.of("farm", "farms", "build", "building", "structure",
            "machine", "design", "schematic", "schem", "thing", "contraption", "generator", "house", "home", "hut", "cabin",
            "shelter", "base", "shack");

    static String norm(String s) {
        String t = s.toLowerCase(Locale.ROOT).replace("sugarcane", "sugar cane").replace("cacti", "cactus")
                .replaceAll("[^a-z0-9 ]", " ").trim().replaceAll("\\s+", " ");
        StringBuilder sb = new StringBuilder();
        for (String w : t.split(" ")) {
            if (w.isEmpty() || FILLER.contains(w)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString();
    }

    private static String singular(String w) {
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 3 && w.endsWith("es") && (w.endsWith("shes") || w.endsWith("ches") || w.endsWith("xes"))) return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    /** The design asked for: one entry, several that fit equally well, or none. */
    public record Match(Entry entry, List<Entry> options) {}

    public static Match find(String query) {
        return find(query, list());
    }

    public static Match find(String query, List<Entry> entries) {
        String q = norm(query == null ? "" : query);
        if (q.isEmpty() || q.length() > 48) return null;
        List<String> sig = new ArrayList<>();
        for (String w : q.split(" ")) if (!GENERIC.contains(w)) sig.add(singular(w));
        if (sig.isEmpty()) return null; // just "a farm": not specific enough
        int best = 0;
        List<Entry> tops = new ArrayList<>();
        for (Entry e : entries) {
            List<String> names = new ArrayList<>();
            names.add(e.name());
            names.addAll(e.aliases());
            int score = 0;
            for (String n : names) {
                String c = norm(n);
                int s;
                if (c.equals(q)) s = 100;
                else {
                    java.util.Set<String> words = new java.util.HashSet<>();
                    for (String w : c.split(" ")) words.add(singular(w));
                    int hit = 0;
                    for (String w : sig) if (words.contains(w)) hit++;
                    s = hit == sig.size() ? 50 + Math.min(40, hit * 10) - Math.min(20, words.size()) : 0;
                }
                score = Math.max(score, s);
            }
            if (score <= 0) continue;
            if (score > best) { best = score; tops.clear(); tops.add(e); }
            else if (score == best) tops.add(e);
        }
        if (tops.isEmpty()) return null;
        return new Match(tops.size() == 1 ? tops.get(0) : null, List.copyOf(tops));
    }

    // ------------------------------------------------------------------------
    // What got built where
    // ------------------------------------------------------------------------

    /** A build: the box it fills ({@code x,y,z} is its low corner), the turn it was placed with, done or not. */
    public record Build(String bot, String dim, String file, int x, int y, int z, int rot, int sx, int sy, int sz,
                        boolean done, String swaps) {
        /** Stand-ins agreed for this build ("wood:warped>dark_oak;calcite>diorite"). */
        public Build {
            swaps = swaps == null ? "" : swaps;
        }

        boolean inside(BlockPos p, int margin, int above) {
            return p.getX() >= x - margin && p.getX() < x + sx + margin && p.getZ() >= z - margin && p.getZ() < z + sz + margin
                    && p.getY() >= y - margin && p.getY() < y + sy + margin + above;
        }

        BlockPos center() { return new BlockPos(x + sx / 2, y, z + sz / 2); }

        String name() {
            Entry e = byFile(file);
            return e != null ? e.name() : Schematic.displayName(file);
        }
    }

    private static final List<Build> BUILDS = new CopyOnWriteArrayList<>();
    private static volatile Path loadedFrom;

    private static synchronized void load() {
        Path f;
        try {
            f = Home.worldFile("builds.txt");
        } catch (Throwable t) {
            return; // no world (yet)
        }
        if (f == null || f.equals(loadedFrom)) return;
        BUILDS.clear();
        loadedFrom = f;
        try {
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length != 11 && p.length != 12) continue;
                try {
                    BUILDS.add(new Build(p[0], p[1], p[10], Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                            Integer.parseInt(p[5]), Integer.parseInt(p[6]), Integer.parseInt(p[7]), Integer.parseInt(p[8]),
                            p[9].equals("done"), p.length == 12 ? (p[11].equals("-") ? "" : p[11]) : BlueprintBuilder.LEGACY + ">wood"));
                } catch (NumberFormatException ignored) { }
            }
        } catch (Exception e) {
            LOGGER.warn("[blueprints] couldn't read {}: {}", f, e.toString());
        }
    }

    private static synchronized void save() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Build b : BUILDS) {
                sb.append(b.bot()).append('\t').append(b.dim()).append('\t').append(b.x()).append('\t').append(b.y()).append('\t')
                        .append(b.z()).append('\t').append(b.rot()).append('\t').append(b.sx()).append('\t').append(b.sy())
                        .append('\t').append(b.sz()).append('\t').append(b.done() ? "done" : "building").append('\t')
                        .append(b.file()).append('\t').append(b.swaps().isEmpty() ? "-" : b.swaps()).append('\n');
            }
            Files.writeString(Home.worldFile("builds.txt"), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[blueprints] couldn't save: {}", e.toString());
        }
    }

    static List<Build> builds() {
        load();
        return BUILDS;
    }

    /** Remembers a build (replacing one of the same design in the same spot). */
    static synchronized void remember(Build b) {
        load();
        BUILDS.removeIf(o -> o.file().equals(b.file()) && o.dim().equals(b.dim()) && o.x() == b.x() && o.y() == b.y() && o.z() == b.z());
        BUILDS.add(b);
        save();
    }

    static synchronized void forget(Build b) {
        load();
        BUILDS.remove(b);
        save();
    }

    /** The bot's most recent unfinished build, or null. */
    static Build unfinished(String bot) {
        Build last = null;
        for (Build b : builds()) if (!b.done() && b.bot().equals(bot)) last = b;
        return last;
    }

    /** Builds (newest last) of designs matching {@code name}, any bot's. */
    static List<Build> named(String name) {
        List<Build> out = new ArrayList<>();
        Match m = find(name);
        for (Build b : builds()) {
            if (m != null && m.options().stream().anyMatch(e -> e.fileName().equals(b.file()))) out.add(b);
            else if (m == null && norm(b.name()).equals(norm(name))) out.add(b);
        }
        return out;
    }

    private static final Map<ServerLevel, String> DIMS = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Part of one of the companions' builds (or right above it, where its plants grow)? Those
     * blocks aren't mined for materials or broken by the pathfinder. Server thread.
     */
    static boolean protects(ServerLevel level, BlockPos p) {
        return protects(level, p, false);
    }

    /**
     * {@code unfinished}: count builds still going up too (for gathering: don't mine the sand
     * just laid down). Walking and tunnelling only respect finished ones, so a half-built
     * design never walls anybody in.
     */
    static boolean protects(ServerLevel level, BlockPos p, boolean unfinished) {
        List<Build> all = builds();
        String dim = DIMS.computeIfAbsent(level, Home::dim);
        if (City.protectsRoad(dim, p)) return true; // the town's roads and street lamps
        if (all.isEmpty()) return false;
        for (Build b : all) {
            if ((b.done() || unfinished) && b.dim().equals(dim) && b.inside(p, 1, 3)) return true;
        }
        return false;
    }

    /** Is this build still on record (not forgotten)? */
    static boolean known(Build b) {
        for (Build o : builds()) {
            if (o.file().equals(b.file()) && o.dim().equals(b.dim()) && o.x() == b.x() && o.y() == b.y() && o.z() == b.z()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------------

    /** What to do about a message: something to say now, or a job (with what to say when starting it). */
    public record Ask(Supplier<String> say, MiningSkills.Request job) {}

    private static final String PREFIX = "^(?:(?:can|could|would|will) (?:you|u) |(?:hey|yo|bro|dude|ok|okay|now|also|and|pls|please|go|lets|let'?s|i want you to|i need you to|you should|try to) )*";
    private static final String END = "(?: (?:pls|please|for me|for us|now|too|asap|real quick))*[!.?\\s]*$";
    private static final Pattern LIST = Pattern.compile(PREFIX
            + "(?:(?:list|show(?: me)?|what are|which are|tell me) (?:the |your |our |all (?:the |your )?)?(?:schematics?|schems?|blueprints?|designs?)"
            + "|what (?:schematics?|schems?|blueprints?|designs?|farms?|stuff|things) (?:do you have|have you got|can you build|you got)"
            + "|what can you build)" + END);
    private static final Pattern BUILD = Pattern.compile(PREFIX
            + "(?:build|make|construct|set up|setup|put up|place|paste|start building|start)(?: me| us)? (.+?)"
            + "(?: (here|right here|over here|at (-?\\d+)[ ,]+(-?\\d+)[ ,]+(-?\\d+)))?"
            + "(?: (?:facing|towards?|pointing) (north|south|east|west))?" + END);
    private static final Pattern NEEDS = Pattern.compile(
            "\\b(?:what do (?:you|we) need|what(?:'?s| is| are) needed|what does it take|how much (?:stuff|materials?|blocks?)(?: do (?:you|we) need| does it take)?"
            + "|(?:what )?materials?(?: do (?:you|we) need)?|shopping list|bill of materials)"
            + " (?:for|to build|to make) (.+?)[!.?\\s]*$");
    private static final Pattern CONTINUE = Pattern.compile(PREFIX
            + "(?:continue|finish|resume|carry on|keep|get back to|go back to)(?: (?:with|on))? (?:the |our |that |your )?"
            + "(?:build(?:ing)?|construction)(?: (?:the |our |that |your )?(.+?))?" + END);
    private static final Pattern FINISH = Pattern.compile(PREFIX + "(?:continue|finish|resume|complete) (.+?)" + END);
    private static final Pattern TEND = Pattern.compile(PREFIX
            + "(?:harvest|tend|tend to|collect from|empty|check on|check|work on|farm|cut|chop)(?: down)? (?:the |our |your |my |all the |some )?(.+?)" + END);
    private static final Pattern FORGET = Pattern.compile(PREFIX
            + "(?:forget(?: about)?|scrap|abandon|cancel|give up on|never mind) (?:the |our |that |your )?(.+?)" + END);

    /** "... next to the house", "... over there": where-ish words that aren't part of the name. */
    private static final Pattern LOCATION = Pattern.compile(
            "\\s+(?:next to|near|nearby|by|beside|behind|around|close to|in front of|outside|over there|somewhere|for (?:me|us)).*$");

    private static boolean generic(String q) {
        for (String w : norm(q).split(" ")) if (GENERIC.contains(w)) return true;
        return false;
    }

    private static boolean exact(String q, Match m) {
        String n = norm(q);
        for (Entry e : m.options()) {
            if (norm(e.name()).equals(n)) return true;
            for (String a : e.aliases()) if (norm(a).equals(n)) return true;
        }
        return false;
    }

    private static final Pattern PLANT = Pattern.compile("^(?:sugar cane|cane|bamboo|cactus)(?: farm)?$");

    /**
     * A message about designs and builds, or null if it's about something else. {@code sender}
     * and {@code bot} may be null (just checking what kind of message it is).
     */
    public static Ask parse(String text, ServerPlayer sender, ServerPlayer bot) {
        String m = text == null ? "" : text.toLowerCase(Locale.ROOT).replace(',', ' ').replaceAll("\\s+", " ").trim();
        if (m.isEmpty() || m.length() > 90) return null;

        if (LIST.matcher(m).find()) return new Ask(Blueprints::describeAll, null);

        Matcher nm = NEEDS.matcher(m);
        if (nm.find()) {
            Match match = find(nm.group(1));
            if (match == null) return null;
            if (match.entry() == null) return new Ask(() -> which(match), null);
            Entry e = match.entry();
            return new Ask(() -> bot == null ? "" : BlueprintBuilder.describeNeeds(bot, e), null);
        }

        Matcher cm = CONTINUE.matcher(m);
        if (cm.find()) {
            String name = cm.group(1);
            return new Ask(null, resume(name, bot));
        }

        Matcher fm = FINISH.matcher(m);
        if (fm.find()) {
            List<Build> bs = named(fm.group(1));
            if (!bs.isEmpty() && bs.stream().anyMatch(b -> !b.done())) return new Ask(null, resume(fm.group(1), bot));
        }

        Matcher bm = BUILD.matcher(m);
        if (bm.find()) {
            String what = LOCATION.matcher(bm.group(1)).replaceFirst("").trim();
            Match found = find(what);
            boolean loose = found != null && !m.matches(PREFIX + "(?:build|construct|start building) .*") && !exact(what, found) && !generic(what);
            final Match match = loose ? null : found; // "make sugar" is crafting, not a sugar cane farm
            if (match != null) {
                if (match.entry() == null) return new Ask(() -> which(match), null);
                Entry e = match.entry();
                BlueprintBuilder.Spot spot = BlueprintBuilder.Spot.auto();
                if (bm.group(2) != null && bm.group(3) != null) {
                    spot = BlueprintBuilder.Spot.at(new BlockPos(Integer.parseInt(bm.group(3)), Integer.parseInt(bm.group(4)),
                            Integer.parseInt(bm.group(5))), dir(bm.group(6)));
                } else if (bm.group(2) != null) {
                    if (sender == null) return new Ask(() -> "where's here? stand where you want it and ask me again", null);
                    spot = BlueprintBuilder.Spot.here(BotPathing.feet(sender), facingOf(sender.getYRot()));
                }
                final BlueprintBuilder.Spot where = spot;
                String label = "build the " + e.name();
                return new Ask(null, new MiningSkills.Request(label,
                        HumanChat.pick("ok, building the " + e.name(), "on it, one " + e.name() + " coming up",
                                "sure, gonna build the " + e.name()),
                        (server, b0, brain) -> BlueprintBuilder.build(server, b0, brain, e, where, null)));
            }
        }

        Matcher tm = TEND.matcher(m);
        if (tm.find()) {
            String what = tm.group(1).trim();
            List<Build> bs = new ArrayList<>();
            for (Build b : named(what)) if (b.done()) bs.add(b);
            String n = norm(what);
            if (bs.isEmpty() && PLANT.matcher(n).find()) {
                String plant = n.replace(" farm", "").replace("cane", "sugar_cane").replace("sugar sugar_cane", "sugar_cane");
                for (Build b : builds()) if (b.done() && BlueprintBuilder.grows(b, plant)) bs.add(b);
            }
            if (!bs.isEmpty()) {
                Build target = bs.get(bs.size() - 1);
                if (bot != null) {
                    String me = bot.getName().getString().toLowerCase(Locale.ROOT);
                    for (Build b : bs) if (b.bot().equals(me)) target = b;
                }
                final Build t = target;
                return new Ask(null, new MiningSkills.Request("tend the " + t.name(),
                        HumanChat.pick("ok, harvesting the " + t.name(), "sure, checking the " + t.name()),
                        (server, b0, brain) -> BlueprintBuilder.tend(server, b0, brain, t, true)));
            }
            Match match = find(what);
            boolean plainPlant = PLANT.matcher(n).find() && !n.endsWith(" farm"); // "harvest bamboo": go find some
            if (match != null && match.entry() != null && !plainPlant) {
                String nm2 = match.entry().name();
                return new Ask(() -> "haven't built a " + nm2 + " yet. say \"build a " + nm2 + "\" and i'll make one", null);
            }
        }

        Matcher gm = FORGET.matcher(m);
        if (gm.find()) {
            List<Build> bs = named(gm.group(1));
            if (!bs.isEmpty()) {
                Build b = bs.get(bs.size() - 1);
                return new Ask(() -> {
                    forget(b);
                    if (bot != null && SurvivalBrain.jobName(bot).equals("build the " + b.name())) SurvivalBrain.stopTask(bot);
                    return "ok, forgot about the " + b.name() + " (i left the blocks where they are)";
                }, null);
            }
        }
        return null;
    }

    private static MiningSkills.Request resume(String name, ServerPlayer bot) {
        return new MiningSkills.Request("continue building", HumanChat.pick("ok, back to building", "sure, gonna finish it"),
                (server, b0, brain) -> {
                    String me = b0.getName().getString().toLowerCase(Locale.ROOT);
                    Build target = null;
                    if (name != null && !name.isBlank()) {
                        for (Build b : named(name)) if (!b.done()) target = b;
                    }
                    if (target == null) target = unfinished(me);
                    if (target == null) {
                        for (Build b : builds()) if (!b.done()) target = b;
                    }
                    if (target == null) {
                        HumanChat.say(server, brain.name, "there's nothing half built that i know of");
                        return;
                    }
                    Entry e = byFile(target.file());
                    if (e == null) {
                        HumanChat.say(server, brain.name, "can't find " + target.file() + " in the schematics folder anymore");
                        return;
                    }
                    if (e.starter() && target.bot().equals(me)) {
                        StarterHouse.build(server, b0, brain, null); // its own house: moves in when it's up
                        return;
                    }
                    BlueprintBuilder.build(server, b0, brain, e, BlueprintBuilder.Spot.resume(target), target);
                });
    }

    private static String which(Match m) {
        StringBuilder sb = new StringBuilder("which one? ");
        for (int i = 0; i < m.options().size() && i < 5; i++) sb.append(i == 0 ? "" : ", ").append(m.options().get(i).name());
        return sb.toString();
    }

    static String describeAll() {
        List<Entry> all = list();
        if (all.isEmpty()) {
            return "i don't have any schematics. drop .schem, .nbt or .litematic files in config/ai-companion/schematics"
                    + " and i can build them";
        }
        StringBuilder sb = new StringBuilder("i can build: ");
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) sb.append(", ");
            if (i >= 20) { sb.append("and ").append(all.size() - 20).append(" more"); break; }
            sb.append(all.get(i).name());
        }
        return sb.append(". more go in config/ai-companion/schematics").toString();
    }

    static Direction dir(String s) {
        if (s == null) return null;
        return switch (s) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    /** The way someone with this yaw is looking (0 = south, 90 = west). */
    static Direction facingOf(float yaw) {
        return switch (Math.floorMod(Math.round(yaw / 90f), 4)) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    static String name(Direction d) {
        return switch (d) {
            case NORTH -> "north";
            case SOUTH -> "south";
            case EAST -> "east";
            case WEST -> "west";
            default -> "south";
        };
    }
}
