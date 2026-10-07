package io.github.yudiiee.aicompanion.GameAI.human;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.onServer;

/**
 * Diamonds are money. Every companion knows the price list ({@link PriceBook}), keeps its
 * diamonds on it as its wallet, sells to players and buys from them ("sell me 16 bread",
 * "buy my iron"), runs a shop in the town (stocks it with what it gathered, takes the
 * diamonds out of the till) and buys from the other companions' shops when it needs
 * something. Fractions of a diamond go on a tab that any companion honours. The tab and a few
 * totals live in {@code <world>/ai-companion/economy.txt}.
 */
public final class Economy {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-economy");

    private Economy() {}

    static boolean on() {
        return HumanConfig.get().economy;
    }

    // ------------------------------------------------------------------------
    // The tab (credit) and the books
    // ------------------------------------------------------------------------

    /** What the companions owe someone, in diamonds (by lowercase name). */
    private static final Map<String, Double> CREDIT = new ConcurrentHashMap<>();
    /** Per bot: diamonds earned selling, items bought, offerings made. */
    private static final Map<String, double[]> BOOKS = new ConcurrentHashMap<>();
    private static volatile Path loadedFrom;

    private static Path file() {
        return Home.worldFile("economy.txt");
    }

    private static synchronized void load() {
        Path f;
        try {
            f = file();
        } catch (Throwable t) {
            return;
        }
        if (f == null || f.equals(loadedFrom)) return;
        loadedFrom = f;
        CREDIT.clear();
        BOOKS.clear();
        if (!Files.isRegularFile(f)) return;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] x = line.split("\\|", -1);
                try {
                    if (x[0].equals("credit") && x.length >= 3) CREDIT.put(x[1], Double.parseDouble(x[2]));
                    else if (x[0].equals("books") && x.length >= 5) {
                        BOOKS.put(x[1], new double[]{Double.parseDouble(x[2]), Double.parseDouble(x[3]), Double.parseDouble(x[4])});
                    }
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException e) {
            LOGGER.warn("[economy] couldn't read {}: {}", f, e.toString());
        }
    }

    private static synchronized void save() {
        Path f = file();
        if (f == null) return;
        List<String> out = new ArrayList<>();
        CREDIT.forEach((k, v) -> { if (v > 1e-6) out.add("credit|" + k + "|" + PriceBook.round(v)); });
        BOOKS.forEach((k, v) -> out.add("books|" + k + "|" + PriceBook.round(v[0]) + "|" + PriceBook.round(v[1]) + "|" + (long) v[2]));
        try {
            Files.write(f, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[economy] couldn't save {}: {}", f, e.toString());
        }
    }

    /** What's on someone's tab (diamonds the companions owe them). */
    static double credit(String who) {
        load();
        return CREDIT.getOrDefault(who.toLowerCase(Locale.ROOT), 0.0);
    }

    static void setCredit(String who, double dia) {
        load();
        double v = PriceBook.round(Math.max(0, dia));
        if (v <= 1e-6) CREDIT.remove(who.toLowerCase(Locale.ROOT));
        else CREDIT.put(who.toLowerCase(Locale.ROOT), v);
        save();
    }

    private static double[] books(String bot) {
        load();
        return BOOKS.computeIfAbsent(bot.toLowerCase(Locale.ROOT), k -> new double[3]);
    }

    static void noteSale(String bot, double dia) {
        books(bot)[0] += dia;
        save();
    }

    static void noteBought(String bot, int items) {
        books(bot)[1] += items;
        save();
    }

    static void noteOffering(String bot, int items) {
        books(bot)[2] += items;
        save();
    }

    // ------------------------------------------------------------------------
    // Settling up: whole diamonds change hands, the rest goes on the tab
    // ------------------------------------------------------------------------

    /** {diamonds to hand over, tab afterwards} when someone with {@code credit} on their tab buys for {@code price}. */
    static double[] payFor(double price, double credit) {
        double due = price - credit;
        int dia = due <= 1e-6 ? 0 : (int) Math.ceil(due - 1e-6);
        return new double[]{dia, PriceBook.round(credit + dia - price)};
    }

    /** {diamonds paid now, tab afterwards} when a companion with {@code wallet} diamonds buys for {@code price}. */
    static double[] payOut(double price, double credit, int wallet) {
        double total = price + credit;
        int pay = (int) Math.floor(total + 1e-6);
        int now = Math.max(0, Math.min(pay, wallet));
        return new double[]{now, PriceBook.round(total - now)};
    }

    static int diamonds(ServerPlayer p) {
        return Gathering.countOf(p, "diamond"::equals);
    }

    // ------------------------------------------------------------------------
    // What a companion has to sell
    // ------------------------------------------------------------------------

    /** What it would sell from its pockets: priced things beyond what it keeps on hand (no gear, no diamonds). Server thread. */
    static Map<String, Integer> pocketStock(ServerPlayer bot) {
        Inventory inv = bot.getInventory();
        Map<String, Integer> kept = new HashMap<>();
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            String p = SurvivalBrain.itemPath(s);
            if (p.equals("diamond") || Storage.keeps(s, kept)) continue;
            if (PriceBook.unit(p) <= 0) continue;
            out.merge(p, s.getCount(), Integer::sum);
        }
        return out;
    }

    /** Kinds of thing that go on the shop's shelves: loot, not the building blocks the town needs. */
    private static final java.util.Set<String> SHELF = java.util.Set.of("minerals", "metallurgy", "mob_drops", "food", "farmable",
            "currency", "rare_find", "combat", "tools", "armor", "trophy", "template", "netherite", "trial_chamber", "utility",
            "transport", "endgame", "junk", "crafting", "material", "vegetation");

    /** What it puts in its shop: loot from its pockets (ores, ingots, food, mob drops, gear it doesn't use...). Server thread. */
    static Map<String, Integer> shelfStock(ServerPlayer bot) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : pocketStock(bot).entrySet()) {
            PriceBook.Price p = PriceBook.get(e.getKey());
            if (p != null && SHELF.contains(p.cat())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** Its shop's stock chests (and the mall's), loaded ones only. Server thread. */
    static List<BlockPos> shopChests(ServerLevel level, City.Town t, String bot) {
        List<BlockPos> out = new ArrayList<>();
        if (t == null) return out;
        City.Plot shop = t.shopOf(bot);
        if (shop != null && shop.done()) out.addAll(City.containers(level, shop, "chest"));
        City.Plot mall = t.built("mall");
        if (mall != null) out.addAll(City.containers(level, mall, "chest"));
        return out;
    }

    /** Everything it can sell: pockets and shop. Server thread. */
    static Map<String, Integer> stock(ServerPlayer bot) {
        Map<String, Integer> out = new LinkedHashMap<>(pocketStock(bot));
        City.Town t = City.shopTown(bot.level(), bot.blockPosition(), bot.getName().getString());
        Map<String, Integer> shop = City.contents(bot.level(), shopChests(bot.level(), t, bot.getName().getString()));
        shop.forEach((k, v) -> { if (!k.equals("diamond") && PriceBook.unit(k) > 0) out.merge(k, v, Integer::sum); });
        return out;
    }

    // ------------------------------------------------------------------------
    // Deals with players
    // ------------------------------------------------------------------------

    /** A price a companion quoted, waiting for a yes. {@code botSells}: the player is buying. */
    record Deal(String bot, UUID player, String playerName, boolean botSells, String item, int count, double price, long until) {}

    /** Quotes waiting for an answer, by bot and the player it was for. */
    private static final Map<String, Deal> PENDING = new ConcurrentHashMap<>();

    private static String key(String bot, UUID player) {
        return bot.toLowerCase(Locale.ROOT) + "|" + player;
    }

    /** Is there an offer from this bot waiting for anyone's answer? */
    public static boolean hasPending(String bot) {
        long now = System.currentTimeMillis();
        String pre = bot.toLowerCase(Locale.ROOT) + "|";
        PENDING.values().removeIf(d -> d.until() < now);
        for (String k : PENDING.keySet()) if (k.startsWith(pre)) return true;
        return false;
    }

    /** Is there an offer from this bot waiting for this player's answer? */
    public static boolean hasPending(String bot, UUID player) {
        Deal d = PENDING.get(key(bot, player));
        return d != null && d.until() > System.currentTimeMillis();
    }

    public enum Kind { SELL, BUY, STOCK, WALLET, CREDIT, CASHOUT }

    /** A trade message: what kind and about what ("16 bread"). */
    public record Ask(Kind kind, String what) {}

    private static final String LEAD = "^(?:(?:hey|yo|ok|okay|so|um|pls|please|hi|hello|bro|dude|then)[,\\s]+)*";
    private static final Pattern BUY_FROM_PLAYER = Pattern.compile(LEAD
            + "(?:(?:can|could|will|would) (?:you|u) buy (?:my |some |these |this )?|(?:do|would|will) you (?:wanna |want to )?buy (?:my |some )?"
            + "|(?:you )?(?:wanna|want to) buy (?:my|some of my) |buy (?:my|these|this|some of my) "
            + "|i(?:'d| would)? (?:like|want|wanna) (?:to )?sell (?:you )?|i(?:'ll| will) sell (?:you )?|let me sell (?:you )?|i'?m selling |sell you )(.+?)[?!.\\s]*$");
    private static final Pattern SELL_TO_PLAYER = Pattern.compile(LEAD
            + "(?:sell me |(?:can|could|may) i buy |i(?:'d| would)? (?:like|want|wanna) (?:to )?buy |i(?:'ll| will) buy |let me buy "
            + "|i(?:'d| would) like |(?:can|could) (?:you|u) sell me |i need to buy |how about you sell me )(.+?)[?!.\\s]*$");
    private static final Pattern STOCK = Pattern.compile(
            "\\b(what (do|does|are) (you|u|your shop|ur shop) (sell|selling|have for sale|got for sale)|what'?s (in|on) (your|ur) (shop|stall|store)"
            + "|(show|tell) me (your|ur) (shop|wares|stock|prices)|what are you selling|(your|ur) (price list|prices|stock|wares)|what'?s for sale"
            + "|what can i buy)\\b");
    private static final Pattern WALLET = Pattern.compile(
            "\\b(how many (diamonds|dias?) (do|have) (you|u) (have|got)|how rich are (you|u)|(what'?s|whats|what is|check|show me) (your|ur) (wallet|money|savings|balance)"
            + "|how much money (do )?(you|u) (have|got))\\b");
    private static final Pattern CREDIT_Q = Pattern.compile(
            "\\b((what'?s|whats|what is|how much is) (on )?my (balance|credit|tab)|^my (balance|credit|tab)|how much (credit|do (you|u) owe me)"
            + "|do i have (any )?(credit|a tab)|check my (tab|balance|credit))\\b");
    private static final Pattern CASHOUT = Pattern.compile(
            "\\b(cash (me )?out|pay (me )?out|pay me (my|what (you|u) owe)|give me my (credit|money|tab)|settle (up|my tab))\\b");
    private static final Pattern YES = Pattern.compile(
            "^(deal|yes|yeah|yea|yep|yup|sure|ok|okay|k|done|sounds good|i'?ll take (it|them)|go ahead|do it|alright|ight|bet)( deal| please| pls)?[!.\\s]*$");
    private static final Pattern NO = Pattern.compile(
            "^(no|nah|nope|no deal|never ?mind|nvm|too (much|expensive|pricey)|pass|no thanks|forget it|cancel)[!.\\s]*$");

    /** A trade message, or null. */
    public static Ask parse(String m) {
        if (m == null || m.length() > 90) return null;
        String t = m.toLowerCase(Locale.ROOT).trim();
        Matcher b = BUY_FROM_PLAYER.matcher(t);
        if (b.find() && looksLikeGoods(b.group(1))) return new Ask(Kind.BUY, b.group(1));
        Matcher s = SELL_TO_PLAYER.matcher(t);
        if (s.find() && looksLikeGoods(s.group(1))) return new Ask(Kind.SELL, s.group(1));
        if (CASHOUT.matcher(t).find()) return new Ask(Kind.CASHOUT, "");
        if (CREDIT_Q.matcher(t).find()) return new Ask(Kind.CREDIT, "");
        if (STOCK.matcher(t).find()) return new Ask(Kind.STOCK, "");
        if (WALLET.matcher(t).find()) return new Ask(Kind.WALLET, "");
        return null;
    }

    /** Is it something with a price ("16 bread"), not "a house" or "you a drink"? */
    static boolean looksLikeGoods(String what) {
        return what != null && PriceBook.itemFor(what) != null;
    }

    public static boolean isYes(String m) {
        return m != null && YES.matcher(m.toLowerCase(Locale.ROOT).trim()).find();
    }

    public static boolean isNo(String m) {
        return m != null && NO.matcher(m.toLowerCase(Locale.ROOT).trim()).find();
    }

    /** Answers a trade message (and remembers the price it quoted). Server thread. */
    static String handle(MinecraftServer server, ServerPlayer bot, ServerPlayer player, Ask a) {
        if (a == null) return null;
        if (!on()) return "i'm not trading right now";
        String me = bot.getName().getString();
        String who = player.getName().getString();
        switch (a.kind()) {
            case SELL -> {
                String item = PriceBook.itemFor(a.what());
                if (item == null) return "what's that? don't know a price for it";
                if (item.equals("diamond")) return "lol diamonds are the money, can't sell you those";
                int want = Math.max(1, PriceBook.countIn(a.what()));
                int have = stock(bot).getOrDefault(item, 0);  // what it can spare, and its shop's
                String name = item.replace('_', ' ');
                if (have <= 0) {
                    String other = whoSells(bot, item);
                    return "don't have any " + PriceBook.plural(name, 2) + " right now" + (other == null ? "" : ", " + other + " might");
                }
                int n = Math.min(want, have);
                double price = PriceBook.sellPrice(item, n);
                double c = credit(who);
                double[] pay = payFor(price, c);
                String line = (n < want ? "only have " + n + ". " : "") + n + " " + PriceBook.plural(name, n) + " is " + PriceBook.money(price);
                if (c > 1e-6) {
                    line += pay[0] == 0 ? ", comes off your tab (" + PriceBook.money(c) + ")"
                            : ". with your tab (" + PriceBook.money(c) + ") that's " + (int) pay[0] + " diamond" + (pay[0] == 1 ? "" : "s");
                } else if (price < 1) {
                    line += ", so 1 diamond and " + PriceBook.money(pay[1]) + " goes on your tab";
                }
                PENDING.put(key(me, player.getUUID()), new Deal(me, player.getUUID(), who, true, item, n, price,
                        System.currentTimeMillis() + 120_000L));
                return line + ". deal?";
            }
            case BUY -> {
                String item = PriceBook.itemFor(a.what());
                if (item == null) return "can't put a price on that";
                if (item.equals("diamond")) return "a diamond for a diamond? lol no";
                int n = Math.max(1, PriceBook.countIn(a.what()));
                if (refuses(player, item)) return "i only buy " + PriceBook.plural(item.replace('_', ' '), 2) + " new (and empty), sorry";
                int has = Gathering.countOf(player, item::equals);
                if (has <= 0) return "you don't have any " + PriceBook.plural(item.replace('_', ' '), 2) + " on you";
                n = Math.min(n, has);
                double price = PriceBook.buyPrice(item, n);
                double c = credit(who);
                int wallet = diamonds(bot);
                double[] pay = payOut(price, c, wallet);
                String name = PriceBook.plural(item.replace('_', ' '), n);
                String line = "i'll give you " + PriceBook.money(price) + " for " + n + " " + name;
                if (pay[0] == 0) line += " (goes on your tab, " + PriceBook.money(pay[1]) + " after)";
                else line += " (" + (int) pay[0] + " diamond" + (pay[0] == 1 ? "" : "s") + " now"
                        + (pay[1] > 1e-6 ? ", " + PriceBook.money(pay[1]) + " on your tab" : "") + ")";
                if (Math.floor(price + c + 1e-6) > wallet) line += ". only got " + wallet + " diamonds on me so the rest goes on your tab";
                PENDING.put(key(me, player.getUUID()), new Deal(me, player.getUUID(), who, false, item, n, price,
                        System.currentTimeMillis() + 120_000L));
                return line + ". deal?";
            }
            case STOCK -> {
                Map<String, Integer> st = stock(bot);
                City.Town t = City.shopTown(bot.level(), bot.blockPosition(), bot.getName().getString());
                City.Plot shop = t == null ? null : t.shopOf(me);
                String where = shop != null && shop.done() ? " (shop's at " + shop.midX() + " " + shop.midZ() + ")" : "";
                if (st.isEmpty()) return "nothing to sell right now" + where;
                List<Map.Entry<String, Integer>> es = new ArrayList<>(st.entrySet());
                es.sort((x, y) -> Double.compare(PriceBook.value(y.getKey(), y.getValue()), PriceBook.value(x.getKey(), x.getValue())));
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, Integer> e : es) {
                    if (parts.size() >= 8) break;
                    parts.add(e.getValue() + " " + PriceBook.plural(e.getKey().replace('_', ' '), e.getValue()) + " (" + rate(e.getKey()) + ")");
                }
                return "selling " + String.join(", ", parts) + (es.size() > 8 ? " and more" : "") + where;
            }
            case WALLET -> {
                int w = diamonds(bot);
                double[] bk = books(me);
                return "got " + w + " diamond" + (w == 1 ? "" : "s") + " on me" + (bk[0] > 0 ? ", made " + PriceBook.money(bk[0]) + " selling so far" : "");
            }
            case CREDIT -> {
                double c = credit(who);
                return c <= 1e-6 ? "your tab's empty" : "you've got " + PriceBook.money(c) + " on your tab" + (c >= 1 ? ", say \"cash out\" for the whole diamonds" : "");
            }
            case CASHOUT -> {
                double c = credit(who);
                int whole = (int) Math.floor(c + 1e-6);
                if (whole <= 0) return c > 0 ? "only " + PriceBook.money(c) + " on your tab, not a whole diamond yet" : "your tab's empty";
                int pay = Math.min(whole, diamonds(bot));
                if (pay <= 0) return "i'm out of diamonds right now, sorry. the tab's still good";
                if (bot.distanceToSqr(player) > 5 * 5) {
                    UUID to = player.getUUID();
                    SurvivalBrain.startJob(bot, "pay " + who, true, (s, bt, bb) -> cashOut(s, bt, bb, to));
                    return "coming over with your diamonds";
                }
                int given = Gathering.handOver(bot, player, "diamond"::equals, pay);
                setCredit(who, c - given);
                return "here's " + given + " diamond" + (given == 1 ? "" : "s") + (c - given > 1e-6 ? ", " + PriceBook.money(c - given) + " still on your tab" : "");
            }
        }
        return null;
    }

    /** "16/diamond" or "2.1 diamonds each". */
    static String rate(String item) {
        int per = PriceBook.perDiamond(item);
        return per >= 2 ? per + " a diamond" : PriceBook.money(PriceBook.unit(item)) + " each";
    }

    /** Another companion who has some of this to sell, or null. Server thread. */
    private static String whoSells(ServerPlayer bot, String item) {
        for (ServerPlayer p : bot.level().getServer().getPlayerList().getPlayers()) {
            if (p == bot || !HumanBehavior.isAiBot(p)) continue;
            if (stock(p).getOrDefault(item, 0) > 0) return p.getName().getString();
        }
        return null;
    }

    /** "deal" / "no": the answer to a quote this player got. Null if there isn't one. Server thread. */
    static String answerDeal(MinecraftServer server, ServerPlayer bot, ServerPlayer player, boolean yes) {
        String key = key(bot.getName().getString(), player.getUUID());
        Deal d = PENDING.get(key);
        if (d == null || d.until() < System.currentTimeMillis() || !PENDING.remove(key, d)) return null;
        if (!yes) return HumanChat.pick("ok, no worries", "alright, maybe next time", "np");
        String label = d.botSells() ? "sell " + d.item().replace('_', ' ') + " to " + d.playerName() : "buy " + d.item().replace('_', ' ') + " from " + d.playerName();
        SurvivalBrain.startJob(bot, label, true, (s, bt, bb) -> trade(s, bt, bb, d));
        return HumanChat.pick("deal!", "pleasure doing business", "nice, one sec");
    }

    /** Walks over to a player; true once within reach. Job thread. */
    private static boolean reach(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, UUID who) throws InterruptedException {
        for (int attempt = 0; attempt < 4 && SurvivalBrain.canContinue(b); attempt++) {
            ServerPlayer p = onServer(server, () -> server.getPlayerList().getPlayer(who), null);
            if (p == null || p.level() != bot.level()) return false;
            if (onServer(server, () -> bot.distanceToSqr(p) <= 4 * 4, false)) return true;
            BlockPos at = onServer(server, p::blockPosition, null);
            if (at == null) return false;
            City.walk(server, bot, b, at, 2.5, 90);
        }
        ServerPlayer p = onServer(server, () -> server.getPlayerList().getPlayer(who), null);
        return p != null && onServer(server, () -> bot.distanceToSqr(p) <= 5 * 5, false);
    }

    /**
     * Runs something that moves goods or money on the server thread and waits for it properly
     * (a plain {@code onServer} gives up after 2 seconds while the work still happens later).
     * Job thread.
     */
    static <T> T settle(MinecraftServer server, java.util.function.Supplier<T> call, T fallback) {
        java.util.concurrent.CompletableFuture<T> f = new java.util.concurrent.CompletableFuture<>();
        server.execute(() -> {
            try {
                f.complete(call.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            return f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Moves up to {@code n} matching items, the real stacks (enchantments, names and all), from one
     * player's pockets to another's, last slots first (so what a bot keeps on hand stays put). What
     * doesn't fit lands at the receiver's feet. Returns how many moved. Server thread.
     */
    static int move(ServerPlayer from, ServerPlayer to, Predicate<String> test, int n) {
        Inventory inv = from.getInventory();
        int left = n, moved = 0;
        for (int i = Math.min(36, inv.getContainerSize()) - 1; i >= 0 && left > 0; i--) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !test.test(SurvivalBrain.itemPath(s))) continue;
            int k = Math.min(left, s.getCount());
            ItemStack part = s.copy();
            part.setCount(k);
            s.shrink(k);
            to.getInventory().add(part);
            if (!part.isEmpty()) {
                net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(to.level(),
                        to.getX(), to.getY() + 0.4, to.getZ(), part);
                drop.setDeltaMovement(0, 0.05, 0);
                to.level().addFreshEntity(drop);
            }
            moved += k;
            left -= k;
        }
        inv.setChanged();
        to.getInventory().setChanged();
        if (moved > 0) Motions.swingArm(from);
        return moved;
    }

    /** Used gear or a box with things in it: not something it buys at the list price. */
    static boolean refuses(ServerPlayer p, String item) {
        if (item.contains("shulker_box") || item.equals("bundle")) return true;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && SurvivalBrain.itemPath(s).equals(item) && s.getMaxDamage() > 0 && s.getDamageValue() > 0) return true;
        }
        return false;
    }

    /** Carries out a deal: fetch the goods from the shop if need be, meet up, swap. Job thread. */
    static void trade(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Deal d) throws InterruptedException {
        String me = b.name;
        Predicate<String> test = d.item()::equals;
        int allowance = 0;
        if (d.botSells()) {
            // what it can spare from its pockets, and the rest from the shop
            int spare = onServer(server, () -> pocketStock(bot).getOrDefault(d.item(), 0), 0);
            allowance = Math.min(spare, d.count());
            if (allowance < d.count()) allowance += fetchFromShop(server, bot, b, test, d.count() - allowance);
            if (allowance <= 0) {
                HumanChat.say(server, me, "sorry " + d.playerName() + ", ran out of " + PriceBook.plural(d.item().replace('_', ' '), 2));
                return;
            }
        }
        if (!reach(server, bot, b, d.player())) {
            HumanChat.say(server, me, "can't get to you, " + d.playerName() + ". come find me and we'll trade");
            return;
        }
        final int spareNow = allowance;
        String done = settle(server, () -> {
            ServerPlayer p = server.getPlayerList().getPlayer(d.player());
            if (p == null) return "you left lol";
            return d.botSells() ? settleSale(bot, p, d, spareNow) : settlePurchase(bot, p, d);
        }, "hm, that didn't go through. ask me again");
        HumanChat.say(server, me, done);
    }

    /** The goods go over, then the player pays. {@code spare}: how many it can part with. Server thread. */
    static String settleSale(ServerPlayer bot, ServerPlayer p, Deal d, int spare) {
        Predicate<String> test = d.item()::equals;
        int n = Math.min(Math.min(d.count(), spare), Gathering.countOf(bot, test));
        if (n <= 0) return "sorry, ran out of " + PriceBook.plural(d.item().replace('_', ' '), 2);
        double price = n == d.count() ? d.price() : PriceBook.sellPrice(d.item(), n);
        String who = p.getName().getString();
        double[] pay = payFor(price, credit(who));
        int dia = (int) pay[0];
        if (diamonds(p) < dia) return "you need " + dia + " diamond" + (dia == 1 ? "" : "s") + " for that, you've only got " + diamonds(p);
        int given = move(bot, p, test, n);
        if (given <= 0) return "hm, couldn't hand it over";
        if (given < n) { // fewer than agreed: pay for what came over
            price = PriceBook.sellPrice(d.item(), given);
            pay = payFor(price, credit(who));
            dia = (int) pay[0];
        }
        if (dia > 0) move(p, bot, "diamond"::equals, dia);
        setCredit(who, pay[1]);
        noteSale(bot.getName().getString(), price);
        String name = PriceBook.plural(d.item().replace('_', ' '), given);
        return "here's " + given + " " + name + (dia > 0 ? ", thanks for the " + (dia == 1 ? "diamond" : dia + " diamonds") : "")
                + (pay[1] > 1e-6 ? ". " + PriceBook.money(pay[1]) + " on your tab" : "");
    }

    /** The player hands the goods over and gets paid. Server thread. */
    static String settlePurchase(ServerPlayer bot, ServerPlayer p, Deal d) {
        Predicate<String> test = d.item()::equals;
        if (refuses(p, d.item())) return "i only buy that new (and empty), sorry";
        int has = Gathering.countOf(p, test);
        if (has <= 0) return "you don't have the " + PriceBook.plural(d.item().replace('_', ' '), 2) + " anymore";
        int n = Math.min(d.count(), has);
        Storage.makeRoom(bot, Math.max(1, n / 64 + 1));
        int got = move(p, bot, test, n);
        if (got <= 0) return "hm, couldn't take them";
        double price = got == d.count() ? d.price() : PriceBook.buyPrice(d.item(), got);
        String who = p.getName().getString();
        double[] pay = payOut(price, credit(who), diamonds(bot));
        int dia = (int) pay[0];
        int paid = dia > 0 ? move(bot, p, "diamond"::equals, dia) : 0;
        setCredit(who, pay[1] + (dia - paid));
        noteBought(bot.getName().getString(), got);
        return "got the " + PriceBook.plural(d.item().replace('_', ' '), got)
                + (paid > 0 ? ", here's " + paid + " diamond" + (paid == 1 ? "" : "s") : "")
                + (pay[1] + (dia - paid) > 1e-6 ? ". " + PriceBook.money(pay[1] + (dia - paid)) + " on your tab" : "");
    }

    /** Takes things out of its own shop's chests (walking there). Job thread. */
    static int fetchFromShop(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Predicate<String> test, int want)
            throws InterruptedException {
        if (want <= 0) return 0;
        List<BlockPos> chests = onServer(server, () -> {
            List<BlockPos> out = new ArrayList<>();
            City.Town t = City.shopTown(bot.level(), bot.blockPosition(), bot.getName().getString());
            if (t == null) return out;
            for (BlockPos c : shopChests(bot.level(), t, b.name)) {
                if (City.contents(bot.level(), List.of(c)).entrySet().stream().anyMatch(e -> test.test(e.getKey()))) out.add(c);
            }
            return out;
        }, List.of());
        int got = 0;
        for (BlockPos c : chests) {
            if (got >= want || !SurvivalBrain.canContinue(b)) break;
            if (!City.walk(server, bot, b, c, 2.5, 120)) continue;
            final int need = want - got;
            got += settle(server, () -> {
                Container box = HopperBlockEntity.getContainerAt(bot.level(), c);
                if (box == null || bot.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(c)) > 6) return 0;
                int n = Storage.take(bot, box, test, need);
                Storage.note(bot.level(), c, box);
                return n;
            }, 0);
        }
        return got;
    }

    private static void cashOut(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, UUID to) throws InterruptedException {
        if (!reach(server, bot, b, to)) {
            HumanChat.say(server, b.name, "can't get to you, come grab your diamonds");
            return;
        }
        String said = onServer(server, () -> {
            ServerPlayer p = server.getPlayerList().getPlayer(to);
            if (p == null) return null;
            String who = p.getName().getString();
            double c = credit(who);
            int pay = Math.min((int) Math.floor(c + 1e-6), diamonds(bot));
            if (pay <= 0) return "hm, nothing to pay out";
            int given = Gathering.handOver(bot, p, "diamond"::equals, pay);
            setCredit(who, c - given);
            return "here's " + given + " diamond" + (given == 1 ? "" : "s") + " from your tab";
        }, null);
        if (said != null) HumanChat.say(server, b.name, said);
    }

    // ------------------------------------------------------------------------
    // The shop: stock it, empty the till
    // ------------------------------------------------------------------------

    private static final Map<String, Long> NEXT_UPKEEP = new ConcurrentHashMap<>();

    static boolean upkeepDue(String bot) {
        return on() && System.currentTimeMillis() >= NEXT_UPKEEP.getOrDefault(bot, 0L);
    }

    /** Puts its loot on the shelves and takes the diamonds out of the till. Job/brain thread. True if it went. */
    static boolean restock(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, City.Town t) throws InterruptedException {
        String me = b.name;
        NEXT_UPKEEP.put(me, System.currentTimeMillis() + 12 * 60_000L);
        City.Plot shop = t.shopOf(me);
        if (shop == null || !shop.done()) return false;
        int toSell = onServer(server, () -> {
            int n = 0;
            for (int v : shelfStock(bot).values()) n += v;
            return n;
        }, 0);
        boolean checkTill = Math.random() < 0.35;
        if (toSell < 16 && !checkTill) return false;
        BlockPos mid = new BlockPos(shop.midX(), shop.y == City.NO_Y ? t.cy : shop.y, shop.midZ());
        if (!City.walk(server, bot, b, mid, 3, 150)) return false;
        int[] r = onServer(server, () -> {
            ServerLevel level = bot.level();
            int stocked = 0, cash = 0;
            for (BlockPos c : City.containers(level, shop, "chest")) {
                Container box = HopperBlockEntity.getContainerAt(level, c);
                if (box == null || bot.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(c)) > 6) continue;
                stocked += City.putIn(bot, c, shelfStock(bot));
            }
            for (BlockPos c : City.containers(level, shop, "barrel")) {
                Container box = HopperBlockEntity.getContainerAt(level, c);
                if (box == null || bot.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(c)) > 6) continue;
                cash += Storage.take(bot, box, "diamond"::equals, Integer.MAX_VALUE);
                Storage.note(level, c, box);
            }
            return new int[]{stocked, cash};
        }, new int[2]);
        if (r[0] > 0 || r[1] > 0) {
            SurvivalBrain.maybeSay(server, b, (r[0] > 0 ? "put " + r[0] + " things on the shelves" : "checked the shop")
                    + (r[1] > 0 ? ", " + r[1] + " diamond" + (r[1] == 1 ? "" : "s") + " in the till" : ""), 0.6);
        }
        // the shop's full and there's still plenty: the rest goes on a stall in the mall
        City.Plot mall = t.built("mall");
        int left = onServer(server, () -> {
            int n = 0;
            for (int v : shelfStock(bot).values()) n += v;
            return n;
        }, 0);
        if (mall != null && left >= 32 && SurvivalBrain.canContinue(b)) {
            List<BlockPos> stalls = onServer(server, () -> City.containers(bot.level(), mall, "chest"), List.of());
            if (stalls.isEmpty()) {
                City.walk(server, bot, b, new BlockPos(mall.midX(), mall.y == City.NO_Y ? t.cy : mall.y, mall.midZ()), 4, 120);
                stalls = onServer(server, () -> City.containers(bot.level(), mall, "chest"), List.of());
            }
            int put = 0;
            for (BlockPos c : stalls) {
                if (!SurvivalBrain.canContinue(b) || !City.walk(server, bot, b, c, 2.5, 60)) continue;
                put += onServer(server, () -> {
                    return City.putIn(bot, c, shelfStock(bot));
                }, 0);
                if (onServer(server, () -> shelfStock(bot).isEmpty(), true)) break;
            }
            if (put > 0) SurvivalBrain.maybeSay(server, b, "put " + put + " more things on a stall in the mall", 0.5);
        }
        return true;
    }

    // ------------------------------------------------------------------------
    // Buying from the other companions' shops
    // ------------------------------------------------------------------------

    private record Offer(City.Plot shop, List<BlockPos> chests, int count, double dist) {}

    /**
     * Buys up to {@code want} of an item from another companion's shop (walking there, taking it
     * off the shelf and leaving the diamonds in the till), spending at most half its diamonds.
     * Returns how many it bought. Job thread.
     */
    static int buyFromShops(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b, Predicate<String> test, String item, int want)
            throws InterruptedException {
        if (!on() || want <= 0) return 0;
        if (City.towns().isEmpty()) return 0;
        double unit = PriceBook.unit(item);
        if (unit <= 0) return 0;
        String me = b.name;
        Offer best = onServer(server, () -> {
            Offer o = null;
            String dim = Home.dim(bot.level());
            for (City.Town t : City.towns()) {
            if (!t.dim.equals(dim)) continue;
            for (City.Plot p : t.plots) {
                if (!p.kind.equals("shop") || !p.done() || p.owner.isEmpty() || p.owner.equalsIgnoreCase(me)) continue;
                double d = Math.sqrt(bot.blockPosition().distSqr(new BlockPos(p.midX(), p.y == City.NO_Y ? t.cy : p.y, p.midZ())));
                if (d > 200) continue;
                if (City.containers(bot.level(), p, "barrel").isEmpty()) continue; // no till: can't pay
                List<BlockPos> chests = City.containers(bot.level(), p, "chest");
                int n = 0;
                for (Map.Entry<String, Integer> e : City.contents(bot.level(), chests).entrySet()) if (test.test(e.getKey())) n += e.getValue();
                if (n > 0 && (o == null || d < o.dist())) o = new Offer(p, chests, n, d);
            }
            }
            return o;
        }, null);
        if (best == null) return 0;
        int wallet = onServer(server, () -> diamonds(bot), 0);
        double budget = wallet / 2.0 + credit(me);
        int n = Math.min(want, best.count());
        n = (int) Math.min(n, Math.floor(budget / unit + 1e-6));
        if (n <= 0) return 0;
        String owner = best.shop().owner;
        SurvivalBrain.maybeSay(server, b, "gonna buy some " + BlueprintBuilder.label(item) + " at " + owner + "'s shop", 0.8);
        if (!City.walk(server, bot, b, best.chests().get(0), 2.5, 150)) return 0;
        final int buy = n;
        Object[] r = settle(server, () -> {
            ServerLevel level = bot.level();
            // what it can actually pay for right now (whole diamonds plus its tab)
            int afford = (int) Math.floor((diamonds(bot) + credit(me)) / unit + 1e-6);
            final int buyNow = Math.min(buy, afford);
            if (buyNow <= 0) return null;
            int got = 0;
            for (BlockPos c : best.chests()) {
                if (got >= buyNow) break;
                Container box = HopperBlockEntity.getContainerAt(level, c);
                if (box == null || bot.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(c)) > 6) continue;
                got += Storage.take(bot, box, test, buyNow - got);
                Storage.note(level, c, box);
            }
            if (got <= 0) return null;
            double price = PriceBook.sellPrice(item, got);
            double[] pay = payFor(price, credit(me));
            int dia = (int) pay[0]; // affordable: checked above
            int paid = 0;
            for (BlockPos till : City.containers(level, best.shop(), "barrel")) {
                if (paid >= dia) break;
                paid += City.putIn(bot, till, "diamond"::equals, dia - paid);
            }
            if (paid < dia) {
                // the till is full (or out of reach): the diamonds still change hands, onto the owner's tab
                SurvivalBrain.take(bot, "diamond"::equals, dia - paid);
                setCredit(owner, credit(owner) + (dia - paid));
            }
            setCredit(me, pay[1]);
            noteSale(owner, price);
            noteBought(me, got);
            return new Object[]{got, paid};
        }, null);
        if (r == null) return 0;
        int got = (int) r[0], paid = (int) r[1];
        HumanChat.say(server, me, owner + " " + HumanChat.pick("bought " + got + " " + BlueprintBuilder.label(item) + " from your shop",
                "grabbed " + got + " " + BlueprintBuilder.label(item) + " off your shelf")
                + (paid > 0 ? ", left " + paid + " diamond" + (paid == 1 ? "" : "s") + " in the till" : ", it came off my tab"));
        return got;
    }

    private static final String[] FOODS = {"bread", "baked_potato", "cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken",
            "apple", "carrot", "golden_carrot"};

    /** Starving with nothing to eat: buy some food at a shop. Job/brain thread. */
    static boolean buyFood(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        if (!on()) return false;
        for (String f : FOODS) {
            if (buyFromShops(server, bot, b, f::equals, f, 8) > 0) return true;
        }
        return false;
    }

    /** A line for the language model about money. */
    static String persona(String bot) {
        if (!on()) return "";
        double[] bk = books(bot);
        return PriceBook.primer() + (bk[0] > 0 ? " You've made " + PriceBook.money(bk[0]) + " selling so far." : "");
    }
}
