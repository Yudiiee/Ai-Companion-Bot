package io.github.yudiiee.aicompanion.GameAI.human;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * What everybody has: each companion's pockets (counted every few seconds, remembered between
 * sessions) next to what's in the chests. It's how a companion knows before it goes out that it
 * already holds enough of something (or that the depot does), and what to tell the others.
 *
 * <p>Saved in {@code <world>/ai-companion/stock.txt}: one line per companion,
 * {@code name|item=count,item=count|spareItem=count,...}.
 */
public final class Stock {

    private Stock() {}

    /** One companion's pockets: everything, and the part it would give away. */
    record Snap(long at, Map<String, Integer> items, Map<String, Integer> spare) {}

    private static final Map<String, Snap> BOTS = new ConcurrentHashMap<>();
    private static volatile boolean loaded;
    private static volatile java.nio.file.Path loadedFrom;
    private static volatile long lastSave;
    private static volatile boolean dirty;

    // ------------------------------------------------------------------------
    // Counting
    // ------------------------------------------------------------------------

    /** Counts what the bot holds right now and remembers it. Server thread. */
    static Snap record(ServerPlayer bot) {
        load();
        Inventory inv = bot.getInventory();
        Map<String, Integer> all = new HashMap<>();
        Map<String, Integer> kept = new HashMap<>();
        Map<String, Integer> spare = new HashMap<>();
        Predicate<String> reserved = SurvivalBrain.KEEP.getOrDefault(bot.getUUID(), p -> false);
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            String p = SurvivalBrain.itemPath(s);
            all.merge(p, s.getCount(), Integer::sum);
            if (!reserved.test(p) && !Storage.keeps(s, kept)) spare.merge(p, s.getCount(), Integer::sum);
        }
        Snap snap = new Snap(System.currentTimeMillis(), all, spare);
        Snap old = BOTS.put(key(bot.getName().getString()), snap);
        if (old == null || !old.items().equals(all)) dirty = true;
        maybeSave();
        return snap;
    }

    private static String key(String bot) {
        return bot.toLowerCase(Locale.ROOT);
    }

    /** Last count for this companion (may be a few seconds old, or from the last session). */
    static Snap of(String bot) {
        load();
        return BOTS.get(key(bot));
    }

    /** How many matching items this companion holds (counted live). Server thread. */
    static int has(ServerPlayer bot, Predicate<String> test) {
        return record(bot).items().entrySet().stream().filter(e -> test.test(e.getKey())).mapToInt(Map.Entry::getValue).sum();
    }

    /** How many matching items this companion would hand over (it doesn't need them). Server thread. */
    static int spareOf(ServerPlayer bot, Predicate<String> test) {
        return record(bot).spare().entrySet().stream().filter(e -> test.test(e.getKey())).mapToInt(Map.Entry::getValue).sum();
    }

    /** Matching items in all the chests together (what it remembers of chests it can't see right now). Server thread. */
    static int inChests(ServerLevel level, Predicate<String> test) {
        return Storage.stockOf(level, test);
    }

    /**
     * Everything the team knows it has: the bot's own pockets, the chests, and (when
     * {@code withOthers}) what the others carry. Server thread.
     */
    static int team(ServerPlayer bot, Predicate<String> test, boolean withOthers) {
        int n = has(bot, test) + inChests(bot.level(), test);
        if (withOthers) {
            for (Map.Entry<String, Snap> e : BOTS.entrySet()) {
                if (e.getKey().equals(key(bot.getName().getString()))) continue;
                if (System.currentTimeMillis() - e.getValue().at() > 10 * 60_000L) continue;
                n += e.getValue().spare().entrySet().stream().filter(x -> test.test(x.getKey())).mapToInt(Map.Entry::getValue).sum();
            }
        }
        return n;
    }

    // ------------------------------------------------------------------------
    // Saying what they have (for the language model)
    // ------------------------------------------------------------------------

    private static final String[] USEFUL = {"log", "planks", "cobblestone", "stone", "dirt", "sand", "gravel", "coal", "iron",
            "gold", "copper", "diamond", "redstone", "lapis", "andesite", "diorite", "granite", "glass", "stick",
            "bread", "torch", "chest", "bed"};

    /** "Ovi has 40 oak log, 64 cobblestone. Bro has ..." for the prompt; empty if nobody's counted yet. */
    static String teamSummary(String forBot) {
        load();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Snap> e : BOTS.entrySet()) {
            if (System.currentTimeMillis() - e.getValue().at() > 15 * 60_000L) continue;
            String who = e.getKey().equalsIgnoreCase(forBot) ? "you" : cap(e.getKey());
            String line = top(e.getValue().items(), 6);
            if (line.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(who).append(who.equals("you") ? " carry " : " carries ").append(line).append('.');
        }
        return sb.toString();
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String top(Map<String, Integer> m, int n) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            for (String u : USEFUL) if (e.getKey().contains(u) && e.getValue() >= 4) { list.add(e); break; }
        }
        list.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).getValue()).append(' ').append(list.get(i).getKey().replace('_', ' '));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------
    // Remembering between sessions
    // ------------------------------------------------------------------------

    private static java.nio.file.Path file() {
        return Home.worldFile("stock.txt");
    }

    private static synchronized void load() {
        java.nio.file.Path f0 = file();
        if (loaded && f0.equals(loadedFrom)) return;
        BOTS.clear();
        loaded = true;
        loadedFrom = f0;
        try {
            if (!Files.exists(f0)) return;
            for (String line : Files.readAllLines(f0, StandardCharsets.UTF_8)) {
                String[] p = line.split("\\|", -1);
                if (p.length < 3) continue;
                // old numbers are what it had when it last played: good enough until it counts again
                BOTS.put(key(p[0]), new Snap(0L, parse(p[1]), parse(p[2])));
            }
        } catch (Exception ignored) { }
    }

    private static Map<String, Integer> parse(String s) {
        Map<String, Integer> m = new HashMap<>();
        if (s.isEmpty()) return m;
        for (String kv : s.split(",")) {
            int i = kv.lastIndexOf('=');
            if (i <= 0) continue;
            try { m.put(kv.substring(0, i), Integer.parseInt(kv.substring(i + 1))); } catch (NumberFormatException ignored) { }
        }
        return m;
    }

    private static String join(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static synchronized void maybeSave() {
        long now = System.currentTimeMillis();
        if (!dirty || now - lastSave < 30_000L) return;
        lastSave = now;
        dirty = false;
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Snap> e : BOTS.entrySet()) {
                sb.append(e.getKey()).append('|').append(join(e.getValue().items())).append('|')
                        .append(join(e.getValue().spare())).append('\n');
            }
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
    }

    static void flush() {
        lastSave = 0;
        dirty = true;
        maybeSave();
    }

    /** Where a chest is, for chat: "the depot" or "a chest at x y z". */
    static String where(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }
}
