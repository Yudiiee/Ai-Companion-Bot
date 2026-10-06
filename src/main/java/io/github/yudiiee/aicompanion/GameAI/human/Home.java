package io.github.yudiiee.aicompanion.GameAI.human;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.onServer;

/**
 * The bot's base: the house it built. Like a player's first base it's where the loot goes
 * (the chests inside), where it heads when night falls or it's badly hurt, and the middle
 * of the area it gathers in. Every bot has its own, per world: saved in
 * {@code <world save>/ai-companion/homes.txt}.
 */
public final class Home {

    private Home() {}

    /** A house and the dimension it's in. */
    record Base(String dim, House.Site site, String design) {

        Base(String dim, House.Site site) { this(dim, site, null); }

        /** A starter house from a design: its layout (null for the small classic house). */
        StarterHouse.Layout layout() { return StarterHouse.layout(design); }

        BlockPos middle() {
            StarterHouse.Layout l = layout();
            return l != null ? l.middle() : site.middle();
        }

        /** Standing inside the house? */
        boolean inside(BlockPos feet) {
            StarterHouse.Layout l = layout();
            if (l != null) return l.isInside(feet);
            int x0 = site.x0(), z0 = site.z0(), y = site.y(), m = site.size() - 2;
            return feet.getX() >= x0 + 1 && feet.getX() <= x0 + m && feet.getZ() >= z0 + 1 && feet.getZ() <= z0 + m
                    && feet.getY() >= y + 1 && feet.getY() <= y + 2;
        }

        /**
         * Where chests go inside, in order: the first one (placed with the house), then along
         * the walls, leaving the path from the door to the middle and the crafting table free.
         */
        List<BlockPos> chestSpots() {
            StarterHouse.Layout l = layout();
            if (l != null) return l.chestSpots();
            if (site.size() >= 7) {
                // the two double chests on the back wall, then extra single chests beside them
                int back = site.size() - 2, right = site.size() - 2;
                return List.of(site.at(1, 1, back), site.at(2, 1, back), site.at(right - 1, 1, back), site.at(right, 1, back),
                        site.at(1, 1, back - 1), site.at(right, 1, back - 1));
            }
            return List.of(site.at(3, 1, 3), site.at(3, 1, 2), site.at(1, 1, 2), site.at(1, 1, 1), site.at(2, 1, 3));
        }
    }

    /**
     * Each bot has its own base, one per dimension, per world: key "botname|dimension". A new
     * bot, or any bot in a new world, starts with no home and sets up its own.
     */
    private static final java.util.Map<String, Base> HOMES = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean loaded;
    /** Per bot: don't keep trying to build a base every few seconds when it can't. */
    private static final java.util.Map<String, Long> NEXT_BUILD_TRY = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Integer> BUILD_FAILURES = new java.util.concurrent.ConcurrentHashMap<>();
    /** Per bot: a base is being built right now, don't start a second one. */
    private static final java.util.Map<String, Long> BUILDING_SINCE = new java.util.concurrent.ConcurrentHashMap<>();
    /** Per bot: couldn't get home (blocked in, lost), leave it a few minutes before trying again. */
    private static final java.util.Map<String, Long> SKIP_HOME_UNTIL = new java.util.concurrent.ConcurrentHashMap<>();

    /** Per bot: when to next check the house for dark corners. */
    private static final java.util.Map<String, Long> NEXT_LIGHT_CHECK = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean lightCheckDue(ServerPlayer bot, long now) {
        String k = key(bot);
        if (now < NEXT_LIGHT_CHECK.getOrDefault(k, 0L)) return false;
        NEXT_LIGHT_CHECK.put(k, now + 20 * 60_000L);
        return true;
    }

    private static String who(ServerPlayer bot) {
        return bot.getName().getString().toLowerCase(java.util.Locale.ROOT);
    }

    private static String key(ServerPlayer bot) {
        return who(bot) + "|" + dim(bot.level());
    }

    static boolean isBuilding(ServerPlayer bot) {
        return System.currentTimeMillis() - BUILDING_SINCE.getOrDefault(who(bot), 0L) < 30 * 60_000L;
    }

    /** Free to start building a base now (not already at it, not failed a moment ago, in the Overworld)? */
    static boolean canStartBuilding(ServerPlayer bot) {
        return !isBuilding(bot) && System.currentTimeMillis() >= NEXT_BUILD_TRY.getOrDefault(who(bot), 0L)
                && overworld(bot.level());
    }

    /** A build attempt is starting: the next one waits (longer after each failure). */
    static void noteBuildAttempt(ServerPlayer bot) {
        int fails = BUILD_FAILURES.getOrDefault(who(bot), 0);
        NEXT_BUILD_TRY.put(who(bot), System.currentTimeMillis() + Math.min(60, 10L << Math.min(3, fails)) * 60_000L);
    }

    /**
     * Where the bot's notes about a world live: in that world's save folder
     * ({@code <world>/ai-companion/<name>}), so a base in one world isn't "home" in another.
     */
    static Path worldFile(String name) {
        MinecraftServer s = io.github.yudiiee.aicompanion.AICompanion.serverInstance;
        if (s != null && s != dirServer) {
            try {
                Path dir = s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("ai-companion");
                io.github.yudiiee.aicompanion.DataMigration.worldFolder(dir); // bases saved under the mod's old name
                Files.createDirectories(dir);
                worldDir = dir;
            } catch (Throwable t) {
                worldDir = null;
            }
            dirServer = s;
        }
        Path dir = s != null ? worldDir : null;
        return dir != null ? dir.resolve(name) : FabricLoader.getInstance().getConfigDir().resolve("ai-companion-noworld-" + name);
    }

    private static volatile MinecraftServer dirServer;
    private static volatile Path worldDir;

    private static volatile Path loadedFrom;

    private static Path file() {
        return worldFile("homes.txt");
    }

    static String dim(ServerLevel level) {
        return String.valueOf(level.dimension()).replaceAll("[^A-Za-z0-9_:/.]", "");
    }

    static boolean overworld(ServerLevel level) {
        return dim(level).contains("overworld");
    }

    private static synchronized void load() {
        Path f0 = file();
        if (loaded && f0.equals(loadedFrom)) return;
        // a different world (or the first time): forget the old one's bases
        HOMES.clear();
        NEXT_BUILD_TRY.clear();
        BUILD_FAILURES.clear();
        BUILDING_SINCE.clear();
        SKIP_HOME_UNTIL.clear();
        loaded = true;
        loadedFrom = f0;
        try {
            if (!Files.exists(f0)) return;
            for (String line : Files.readAllLines(f0, StandardCharsets.UTF_8)) {
                // file order: bot dim x0 y z0 door size
                String[] p = line.trim().split(" ");
                if (p.length != 7 && p.length != 8) continue;
                Direction door = switch (p[5]) {
                    case "SOUTH" -> Direction.SOUTH;
                    case "EAST" -> Direction.EAST;
                    case "WEST" -> Direction.WEST;
                    default -> Direction.NORTH;
                };
                HOMES.put(p[0] + "|" + p[1], new Base(p[1], new House.Site(Integer.parseInt(p[2]), Integer.parseInt(p[4]),
                        Integer.parseInt(p[3]), door, 0, Integer.parseInt(p[6])), p.length == 8 ? p[7] : null));
            }
        } catch (Exception ignored) { }
    }

    private static synchronized void save() {
        try {
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, Base> e : HOMES.entrySet()) {
                String bot = e.getKey().substring(0, e.getKey().indexOf('|'));
                Base h = e.getValue();
                House.Site s = h.site();
                String dir = s.door() == Direction.SOUTH ? "SOUTH" : s.door() == Direction.EAST ? "EAST"
                        : s.door() == Direction.WEST ? "WEST" : "NORTH";
                sb.append(bot).append(' ').append(h.dim()).append(' ').append(s.x0()).append(' ').append(s.y())
                        .append(' ').append(s.z0()).append(' ').append(dir).append(' ').append(s.size());
                if (h.design() != null && !h.design().contains(" ")) sb.append(' ').append(h.design());
                sb.append('\n');
            }
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
    }

    /** This bot's home in the dimension it's in, or null. */
    static Base get(ServerPlayer bot) {
        load();
        return HOMES.get(key(bot));
    }

    /** Every bot's base in this dimension (their chests are everyone's storage). */
    static List<Base> allIn(ServerLevel level) {
        load();
        String d = dim(level);
        List<Base> out = new java.util.ArrayList<>();
        for (Base h : HOMES.values()) if (h.dim().equals(d)) out.add(h);
        return out;
    }

    /** Every bot's base, any dimension. */
    static List<Base> all() {
        load();
        return new java.util.ArrayList<>(HOMES.values());
    }

    static void set(ServerPlayer bot, House.Site site) {
        set(bot, site, null);
    }

    /** {@code design}: a starter house built from a design ({@link StarterHouse.Design#encode()}). */
    static void set(ServerPlayer bot, House.Site site, String design) {
        load();
        HOMES.put(key(bot), new Base(dim(bot.level()), site, design));
        BUILD_FAILURES.remove(who(bot));
        save();
    }

    static void clear(ServerPlayer bot) {
        load();
        HOMES.remove(key(bot));
        save();
    }

    /**
     * Starts building a base as the bot's own job, unless it's already at it or failed
     * recently (waits longer after each failure). True if it started.
     */
    static boolean startBuilding(ServerPlayer bot) {
        if (!canStartBuilding(bot)) return false;
        String name = who(bot);
        BUILDING_SINCE.put(name, System.currentTimeMillis());
        noteBuildAttempt(bot);
        // (counts as a "player" job so the language model's own plan steps don't cut it short)
        SurvivalBrain.startJob(bot, "set up a base", true, (s, bt, bb) -> {
            try {
                StarterHouse.build(s, bt, bb, null);
            } finally {
                BUILDING_SINCE.remove(name);
                if (onServer(s, () -> get(bt), null) == null) BUILD_FAILURES.merge(name, 1, Integer::sum);
            }
        });
        return true;
    }

    private static boolean night(ServerLevel level) {
        long t = level.getDefaultClockTime() % 24000L;
        return t >= 12500L && t < 23300L;
    }

    // ------------------------------------------------------------------------
    // Going home
    // ------------------------------------------------------------------------

    /** Walks (digging up out of a mine if needed) into the house. True once inside. Job thread. */
    static boolean goHome(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        Base h = onServer(server, () -> get(bot), null);
        if (h == null) return false;
        BlockPos mid = h.middle();
        // a base hundreds of blocks away isn't somewhere to walk to on a whim (and the way may be an ocean)
        if (onServer(server, () -> mid.distSqr(bot.blockPosition()) > 300.0 * 300.0, true)) return false;
        for (int attempt = 0; attempt < 3 && SurvivalBrain.canContinue(b); attempt++) {
            SurvivalBrain.waitWhileFighting(bot);
            if (onServer(server, () -> h.inside(BotPathing.feet(bot)), false)) {
                // inside: finish in the middle, where every chest is within reach
                if (onServer(server, () -> !BotPathing.feet(bot).equals(mid), false)) {
                    BotPathing.Options w = BotPathing.Options.walkOnly();
                    w.timeoutTicks = 20 * 10;
                    BotPathing.goToBlocking(bot, ActionPathfinder.near(mid.getX(), mid.getY(), mid.getZ(), 0.5), w, 12_000L);
                }
                return true;
            }
            Surface.climbToward(server, bot, b, mid);
            BotPathing.Options o = BotPathing.Options.full();
            o.timeoutTicks = 20 * 150;
            BotPathing.goToBlocking(bot, ActionPathfinder.near(mid.getX(), mid.getY(), mid.getZ(), 0.5), o, 155_000L);
        }
        return onServer(server, () -> h.inside(BotPathing.feet(bot)), false);
    }

    /**
     * Too hurt to keep fighting: run home if there is one, otherwise just away from the mob.
     * Server thread, doesn't wait.
     */
    static void retreat(ServerPlayer bot, LivingEntity from) {
        PvpController.flee(bot.getUUID(), 25_000L);
        BotPathing.Options o = BotPathing.Options.full();
        o.fight = true; // this trip is the escape itself, don't pause it
        o.timeoutTicks = 20 * 25;
        Base h = get(bot);
        if (h != null && h.middle().distSqr(bot.blockPosition()) < 80 * 80) {
            BlockPos mid = h.middle();
            BotPathing.goTo(bot, ActionPathfinder.near(mid.getX(), mid.getY(), mid.getZ(), 0.5), o);
            return;
        }
        Vec3 away = from == null ? new Vec3(1, 0, 0) : bot.position().subtract(from.position());
        double len = Math.sqrt(away.x * away.x + away.z * away.z);
        if (len < 0.01) { away = new Vec3(1, 0, 0); len = 1; }
        BlockPos to = BlockPos.containing(bot.getX() + away.x / len * 18, bot.getY(), bot.getZ() + away.z / len * 18);
        BotPathing.goTo(bot, ActionPathfinder.near(to.getX(), to.getY(), to.getZ(), 4.0), o);
    }

    // ------------------------------------------------------------------------
    // Living at the base (the brain calls this when it's free)
    // ------------------------------------------------------------------------

    private record Now(boolean inside, boolean night, float health, int food, boolean hasFood, int used, int storable,
                       double dist, boolean overworld) {}

    /**
     * What a player does around their base: build one first, go home at night or when badly
     * hurt and stay inside, take the loot home when the pockets fill up, don't wander too far.
     * True if it did something this step. Brain thread.
     */
    static boolean tick(MinecraftServer server, ServerPlayer bot, SurvivalBrain.Brain b) throws InterruptedException {
        if (!HumanConfig.get().autoHome) return false;
        Base h = onServer(server, () -> get(bot), null);
        Now n = onServer(server, () -> {
            BlockPos f = BotPathing.feet(bot);
            return new Now(h != null && h.inside(f), night(bot.level()), bot.getHealth(), bot.getFoodData().getFoodLevel(),
                    io.github.yudiiee.aicompanion.PlayerUtils.FoodConsumptionTool.hasSafeFood(bot), Storage.usedSlots(bot),
                    Storage.storableSlots(bot), h == null ? 0 : Math.sqrt(h.middle().distSqr(f)), overworld(bot.level()));
        }, null);
        if (n == null) return false;
        long now = System.currentTimeMillis();

        if (h == null) {
            if (!canStartBuilding(bot)) return false;
            boolean started = onServer(server, () -> startBuilding(bot), false);
            if (started) SurvivalBrain.maybeSay(server, b, HumanChat.pick("gonna build us a base first",
                    "let's set up a little base", "first things first, a house"), 1.0);
            return started;
        }

        // Hurt: eat up (health only comes back with a full-ish hunger bar). Resting at home only
        // makes sense if it will actually heal; with nothing to eat it gets on with things.
        boolean hurt = n.health() <= 8f;
        if (hurt && n.hasFood() && n.food() < 20) {
            io.github.yudiiee.aicompanion.PlayerUtils.FoodConsumptionTool.consumeBestFood(bot);
            return true;
        }
        // starving with nothing on it: the farm, if there's anything ripe
        if (n.food() <= 6 && !n.hasFood() && n.overworld() && Farm.feedYourself(server, bot, b)) return true;
        boolean willHeal = hurt && n.food() >= 18;

        boolean canGoHome = now >= SKIP_HOME_UNTIL.getOrDefault(who(bot), 0L);
        if ((n.night() || willHeal) && (n.inside() || canGoHome)) {
            if (!n.inside()) {
                SurvivalBrain.maybeSay(server, b, n.night()
                        ? HumanChat.pick("getting dark, heading home", "night's coming, going back to base", "gonna head inside for the night")
                        : HumanChat.pick("i'm hurt, going home to heal up", "gonna go rest at the base"), 0.8);
                if (!goHome(server, bot, b)) SKIP_HOME_UNTIL.put(who(bot), System.currentTimeMillis() + 3 * 60_000L);
                return true;
            }
            if (HumanConfig.get().autoStore && n.storable() > 0 && now >= b.lastStore + 120_000L && now >= Storage.backoffUntil) {
                b.lastStore = now;
                Storage.storeAll(server, bot, b, false);
                return true;
            }
            if (lightCheckDue(bot, now) && onServer(server, () -> House.hasDarkSpot(bot.level(), h), false)) {
                SurvivalBrain.maybeSay(server, b, HumanChat.pick("bit dark in here, putting up some torches", "gonna light this place up"), 0.6);
                House.lightUp(server, bot, b, h);
                return true;
            }
            b.restUntil = now + 15_000L; // safe inside: wait for morning / to heal
            return true;
        }
        // Pockets filling up with loot: take it home
        if (HumanConfig.get().autoStore && now >= b.lastStore + 120_000L && now >= Storage.backoffUntil
                && (n.storable() >= 12 || (n.used() >= 30 && n.storable() > 0))) {
            b.lastStore = now;
            Storage.storeAll(server, bot, b, false);
            return true;
        }
        // daytime: the house first if it isn't finished
        if (!n.night() && n.overworld() && StarterHouse.tick(server, bot, b)) return true;
        // daytime: build the farm once, then keep it harvested and replanted
        if (!n.night() && n.overworld() && Farm.tick(server, bot, b)) return true;
        // and any farm it built from a schematic (cane, cactus, bamboo, crops)
        if (!n.night() && BlueprintBuilder.tick(server, bot, b)) return true;
        if (n.dist() > 96 && canGoHome) {
            SurvivalBrain.maybeSay(server, b, HumanChat.pick("heading back to base", "going back home"), 0.5);
            if (!goHome(server, bot, b)) SKIP_HOME_UNTIL.put(who(bot), System.currentTimeMillis() + 3 * 60_000L);
            return true;
        }
        return false;
    }

    /** Where it wanders and gathers around: the base, if there is one (server thread). */
    static Vec3 center(ServerPlayer bot) {
        if (!HumanConfig.get().autoHome) return null;
        Base h = get(bot);
        return h == null ? null : Vec3.atBottomCenterOf(h.middle());
    }

    /** "go home" */
    static MiningSkills.Request goHomeRequest() {
        return new MiningSkills.Request("go home", HumanChat.pick("ok, heading home", "going back to base", "omw home"),
                (server, bot, b) -> {
                    if (onServer(server, () -> get(bot), null) == null) {
                        HumanChat.say(server, b.name, "we don't have a base yet. say \"build a house\" and i'll make one");
                        return;
                    }
                    if (goHome(server, bot, b)) {
                        SurvivalBrain.pause(bot, 60_000L);
                    } else {
                        HumanChat.say(server, b.name, "can't find a way home from here, hm");
                    }
                });
    }
}
