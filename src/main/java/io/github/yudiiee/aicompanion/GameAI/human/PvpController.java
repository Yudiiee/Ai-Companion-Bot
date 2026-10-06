package io.github.yudiiee.aicompanion.GameAI.human;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.helpers.EntityPlayerActionPack.Action;
import carpet.helpers.EntityPlayerActionPack.ActionType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import io.github.yudiiee.aicompanion.GameAI.companion.BotStance;
import io.github.yudiiee.aicompanion.GameAI.companion.CompanionController;
import io.github.yudiiee.aicompanion.PathFinding.NavigationOptions;
import io.github.yudiiee.aicompanion.PathFinding.NavigationService;
import io.github.yudiiee.aicompanion.PlayerUtils.FoodConsumptionTool;
import io.github.yudiiee.aicompanion.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PvP: the bot fights a player the way a decent player would. It closes the distance
 * sprinting, strafes, times its hits to the weapon's attack cooldown, sprint-resets for
 * knockback, jumps for crits, blocks with a shield between hits, backs off to eat when
 * low, and says gg at the end.
 *
 * <pre>
 *   /bot pvp &lt;bot&gt; &lt;player&gt; [none|iron|diamond|netherite]   start (optionally hand out a kit)
 *   /bot pvp &lt;bot&gt; stop
 *   chat: "fight me", "pvp me", "1v1 me", "duel me"; "stop" / "gg" / "i give up" ends it
 * </pre>
 *
 * Everything runs on the server thread, once per tick.
 */
public final class PvpController {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-pvp");
    private static final Random RNG = new Random();
    private static final ExecutorService EATER = Executors.newSingleThreadExecutor(DaemonThreads.named("ai-companion-pvp-eat"));

    private static final class Fight {
        final UUID bot;
        final UUID target;
        final String targetName;
        long lastAttack = -100;
        long strafeUntil = 0;
        float strafe = 0.7f;
        long sprintResetUntil = 0;
        long blockUntil = 0;
        long nextNav = 0;
        long nextGear = 0;
        boolean eating = false;
        /** Last tick a hit landed either way (mob fights end when nothing happens for a while). */
        long lastExchange = tick;
        /** Set when it's a mob that attacked the bot (not a PvP match). */
        final LivingEntity mob;
        /** The bot went after it on sight (a creeper or skeleton), rather than being hit first. */
        boolean proactive = false;
        /** Creeper: backing off after a hit until this tick. */
        long backOffUntil = 0;
        /** Skeleton: shield held up while walking in. */
        boolean shieldUp = false;
        Fight(UUID bot, UUID target, String targetName) { this(bot, target, targetName, null); }
        Fight(UUID bot, UUID target, String targetName, LivingEntity mob) {
            this.bot = bot; this.target = target; this.targetName = targetName; this.mob = mob;
        }
    }

    private static final Map<UUID, Fight> FIGHTS = new ConcurrentHashMap<>();
    private static long tick = 0;

    private PvpController() {}

    public static boolean isFighting(UUID botId) {
        return botId != null && FIGHTS.containsKey(botId);
    }

    private static final Map<UUID, Long> FLEEING = new ConcurrentHashMap<>();

    /** Running away (too hurt to fight): don't turn round and fight, other trips wait. */
    static void flee(UUID botId, long millis) {
        FLEEING.merge(botId, System.currentTimeMillis() + millis, Math::max); // never shortens a longer escape
    }

    /** Fighting or running away: jobs wait. */
    public static boolean isBusy(UUID botId) {
        if (isFighting(botId)) return true;
        Long until = botId == null ? null : FLEEING.get(botId);
        if (until == null) return false;
        if (until > System.currentTimeMillis()) return true;
        FLEEING.remove(botId);
        return false;
    }

    // ------------------------------------------------------------------------
    // Start / stop
    // ------------------------------------------------------------------------

    /** Starts a fight. {@code kit} = null/"none" to use what the bot has. Server thread. */
    public static String start(ServerPlayer bot, ServerPlayer target, String kit) {
        if (bot == null || target == null) return "no one to fight";
        if (bot == target) return "can't fight myself lol";
        if (target.isSpectator() || target.gameMode.isCreative()) return "you're in creative, that's not fair";
        SurvivalBrain.stopTask(bot);
        MinecraftServer server = bot.level().getServer();
        NavigationService.cancel(server, bot.getUUID(), "PvP");
        MiningTool.cancelFor(server, bot.getUUID(), "PvP");
        CompanionController companion = CompanionController.getInstance();
        String name = bot.getName().getString();
        if (companion.getStance(name) != BotStance.WANDER) companion.setStanceQuiet(name, BotStance.WANDER, null);

        if (kit != null && !kit.equalsIgnoreCase("none")) giveKit(bot, kit.toLowerCase(Locale.ROOT));
        wearArmor(bot);
        equipWeapon(bot);
        FIGHTS.put(bot.getUUID(), new Fight(bot.getUUID(), target.getUUID(), target.getName().getString()));

        // two bots: the other one fights back
        if (HumanBehavior.isAiBot(target) && !isFighting(target.getUUID())) {
            wearArmor(target);
            equipWeapon(target);
            FIGHTS.put(target.getUUID(), new Fight(target.getUUID(), bot.getUUID(), name));
        }
        LOGGER.info("[pvp] {} vs {}", name, target.getName().getString());
        return HumanChat.pick("ok 1v1, let's go", "bet. you're going down", "alright, fight me then", "ez, let's go");
    }

    public static void stop(ServerPlayer bot) {
        Fight f = FIGHTS.remove(bot.getUUID());
        if (f != null) release(bot, f);
    }

    /** Fighting a mob that hit it (as opposed to a PvP match)? */
    public static boolean isFightingMob(UUID botId) {
        Fight f = botId == null ? null : FIGHTS.get(botId);
        return f != null && f.mob != null;
    }

    /** Name of the mob it's fighting, or null. */
    static String mobName(UUID botId) {
        Fight f = botId == null ? null : FIGHTS.get(botId);
        return f != null && f.mob != null ? f.targetName : null;
    }

    /**
     * A mob hit the bot: fight back, like a player would. Mobs that haven't attacked are
     * left alone. Whatever job the bot was doing waits until the fight is over. Server thread.
     */
    public static void defend(ServerPlayer bot, LivingEntity mob) {
        attack(bot, mob, false);
    }

    /** Goes after a mob (fighting back, or hunting a sheep for wool). {@code quiet}: no "ow". Server thread. */
    static void attack(ServerPlayer bot, LivingEntity mob, boolean quiet) {
        if (bot == null || mob == null || !mob.isAlive() || mob == bot) return;
        if (bot.gameMode.isCreative() || bot.isSpectator()) return;
        // in the water (a drowned, a guardian): fighting there is how you drown. keep swimming instead
        if (bot.isInWater() && !quiet) return;
        Long fleeing = FLEEING.get(bot.getUUID());
        if (fleeing != null && fleeing > System.currentTimeMillis()) return; // running, not turning round
        Fight current = FIGHTS.get(bot.getUUID());
        if (current != null) {
            if (current.mob == mob) { current.lastExchange = tick; return; }
            if (current.mob == null) return; // in a PvP match: stay on the player
            if (current.mob.isAlive() && !current.mob.isRemoved()
                    && current.mob.distanceToSqr(bot) <= mob.distanceToSqr(bot) + 4) return; // keep hitting the one we're on
        }
        MinecraftServer server = bot.level().getServer();
        MiningTool.cancelFor(server, bot.getUUID(), "Attacked"); // the job's trip just pauses
        wearArmor(bot);
        equipWeapon(bot);
        String name = mobLabel(mob);
        FIGHTS.put(bot.getUUID(), new Fight(bot.getUUID(), mob.getUUID(), name, mob));
        LOGGER.info("[fight] {} attacked by {}, fighting back", bot.getName().getString(), name);
        if (!quiet && current == null && RNG.nextFloat() < 0.5f
                && HumanReactions.cooldown(bot.getName().getString() + ":mobhit", 45_000L)) {
            HumanChat.say(server, bot.getName().getString(), HumanChat.pick("ow", "bruh", "hey!", "oh you want some?",
                    "ow, a " + name, "not today " + name));
        }
    }

    // ------------------------------------------------------------------------
    // Creepers and skeletons: dealt with on sight
    // ------------------------------------------------------------------------

    /** How far away a creeper or skeleton gets noticed and gone after. */
    static final double ENGAGE_RANGE = 12.0;
    /** Mobs it gave up on (couldn't reach): mob UUID -> tick when it may try again. */
    private static final Map<UUID, Long> GAVE_UP = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Integer> KIND = new ConcurrentHashMap<>();
    private static final int OTHER = 0, CREEPER = 1, SKELETON = 2;

    /** True if {@code c} or one of its superclasses is called {@code simpleName}. */
    static boolean classNamed(Class<?> c, String simpleName) {
        for (; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getSimpleName().equals(simpleName)) return true;
        }
        return false;
    }

    /** Creeper, bow skeleton (skeleton, stray, bogged...) or anything else. Wither skeletons count as "other". */
    static int kindOf(Class<?> c) {
        return KIND.computeIfAbsent(c, k -> classNamed(k, "Creeper") ? CREEPER
                : classNamed(k, "AbstractSkeleton") && !classNamed(k, "WitherSkeleton") ? SKELETON : OTHER);
    }

    static boolean isCreeper(LivingEntity e) { return e != null && kindOf(e.getClass()) == CREEPER; }
    static boolean isSkeleton(LivingEntity e) { return e != null && kindOf(e.getClass()) == SKELETON; }

    /**
     * Creepers and skeletons within {@link #ENGAGE_RANGE} that it can see: go after them
     * before they get a shot off or blow up. Every other mob is left alone until it hits
     * the bot ({@link #defend}). Server thread, a few times a second.
     */
    static void engageNearby(ServerPlayer bot) {
        try {
            if (bot.gameMode.isCreative() || bot.isSpectator() || bot.isSleeping()) return;
            if (bot.isInWater() || bot.getHealth() < 10f) return; // not in a state to go looking for trouble
            Long fleeing = FLEEING.get(bot.getUUID());
            if (fleeing != null && fleeing > System.currentTimeMillis()) return;
            Fight current = FIGHTS.get(bot.getUUID());
            if (current != null && (current.mob == null || current.proactive)) return; // PvP, or already on one
            LivingEntity best = null;
            double bd = ENGAGE_RANGE * ENGAGE_RANGE;
            for (net.minecraft.world.entity.Entity e : bot.level().getEntities(bot, bot.getBoundingBox().inflate(ENGAGE_RANGE))) {
                if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isRemoved()) continue;
                int kind = kindOf(le.getClass());
                if (kind == OTHER) continue;
                if (kind == CREEPER && !armed(bot)) continue; // punching a creeper is how you die
                if (Math.abs(le.getY() - bot.getY()) > 8) continue;
                double d = le.distanceToSqr(bot);
                if (d > bd) continue;
                Long g = GAVE_UP.get(le.getUUID());
                if (g != null && g > tick) continue;
                if (!canSee(bot, le)) continue; // behind a wall or underground: not a threat yet
                best = le;
                bd = d;
            }
            if (best == null) return;
            attack(bot, best, true);
            Fight f = FIGHTS.get(bot.getUUID());
            if (f != null && f.mob == best) f.proactive = true;
        } catch (Throwable t) {
            LOGGER.debug("[fight] scan failed: {}", t.toString());
        }
    }

    /** Carrying a sword, axe or trident. */
    private static boolean armed(ServerPlayer bot) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            if (weaponScore(SurvivalBrain.itemPath(inv.getItem(i))) > 0) return true;
        }
        return false;
    }

    private static void giveUp(Fight f) {
        if (f.proactive && f.mob != null) GAVE_UP.put(f.mob.getUUID(), tick + 20L * 30);
        if (GAVE_UP.size() > 64) GAVE_UP.values().removeIf(t -> t <= tick);
    }

    /** Creeper: sprint in, hit once, back off out of blast range, repeat. Never stand next to it. */
    private static void creeperTick(ServerPlayer bot, EntityPlayerActionPack ap, Fight f, LivingEntity target, double dist) {
        int cooldown = cooldownTicks(bot.getMainHandItem());
        boolean ready = tick - f.lastAttack >= cooldown;
        boolean stuck = bot.horizontalCollision && bot.onGround();
        boolean canBack = safeBehind(bot);
        if (tick < f.backOffUntil && dist < 6.0) {
            ap.setSprinting(false);
            // a drop or lava behind: sidestep instead of backing off the edge
            ap.setForward(canBack ? -1f : 0f);
            ap.setStrafing(canBack ? f.strafe * 0.4f : (f.strafe >= 0 ? 1f : -1f));
            if (stuck) ap.start(ActionType.JUMP, Action.once());
            return;
        }
        if (!ready) { // wait at a safe distance for the swing to recharge
            ap.setSprinting(false);
            ap.setStrafing(0f);
            ap.setForward(dist < 4.5 ? (canBack ? -1f : 0f) : (dist > 6.5 ? 0.5f : 0f));
            return;
        }
        ap.setStrafing(0f);
        ap.setForward(1f);
        ap.setSprinting(dist > 2.0); // a sprinting hit knocks it well back
        if (stuck) ap.start(ActionType.JUMP, Action.once());
        if (dist <= 3.0 && canSee(bot, target)) {
            ap.start(ActionType.ATTACK, Action.once());
            f.lastAttack = tick;
            f.lastExchange = tick;
            f.backOffUntil = tick + 30;
        }
    }

    /** The ground one and two blocks behind the bot is safe to back onto: no lava, no drop of more than 3. */
    private static boolean safeBehind(ServerPlayer bot) {
        try {
            var level = bot.level();
            double yaw = Math.toRadians(bot.getYRot());
            double bx = Math.sin(yaw), bz = -Math.cos(yaw); // opposite of where it faces
            for (int k = 1; k <= 2; k++) {
                BlockPos p = BlockPos.containing(bot.getX() + bx * k, bot.getY() + 0.1, bot.getZ() + bz * k);
                if (!level.getFluidState(p).isEmpty() && level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA)) return false;
                boolean floor = false;
                for (int d = 1; d <= 3 && !floor; d++) {
                    BlockPos under = p.offset(0, -d, 0);
                    if (level.getFluidState(under).is(net.minecraft.tags.FluidTags.LAVA)) return false;
                    floor = Building.isSolid(level, under);
                }
                if (!floor) return false;
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private static String mobLabel(LivingEntity mob) {
        try {
            return mob.getName().getString().toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return "mob";
        }
    }

    /** The bot died: fight over. */
    static void onDeath(ServerPlayer bot) {
        Fight f = FIGHTS.remove(bot.getUUID());
        if (f == null) return;
        MinecraftServer server = bot.level().getServer();
        // whoever it was fighting (if it's a bot too) stops as well
        ServerPlayer other = server.getPlayerList().getPlayer(f.target);
        if (other != null && FIGHTS.containsKey(other.getUUID())) {
            Fight of = FIGHTS.remove(other.getUUID());
            release(other, of);
            HumanChat.say(server, other.getName().getString(), HumanChat.pick("gg", "gg ez", "ggs"));
        }
    }

    /** Chat from the opponent: "gg", "i give up", "stop"... ends the fight. */
    static boolean onOpponentChat(MinecraftServer server, ServerPlayer sender, String text) {
        String m = text.toLowerCase(Locale.ROOT).trim();
        if (!m.matches(".*\\b(gg|ggs|i give up|i surrender|truce|stop|enough|you win|ok you win|mercy|chill)\\b.*")) return false;
        boolean any = false;
        for (Fight f : FIGHTS.values()) {
            if (!f.target.equals(sender.getUUID())) continue;
            ServerPlayer bot = server.getPlayerList().getPlayer(f.bot);
            FIGHTS.remove(f.bot);
            if (bot != null) {
                release(bot, f);
                HumanChat.say(server, bot.getName().getString(), HumanChat.pick("gg", "gg wp", "ggs, good fight"));
            }
            any = true;
        }
        return any;
    }

    private static void release(ServerPlayer bot, Fight f) {
        // it was a friendly fight: don't keep treating the other player as an enemy
        if (f == null || f.mob == null) try {
            for (ServerPlayer p : bot.level().getServer().getPlayerList().getPlayers()) {
                io.github.yudiiee.aicompanion.PlayerUtils.PlayerRetaliationTracker.clearHostileStatus(bot, p);
                if (p != bot) io.github.yudiiee.aicompanion.PlayerUtils.PlayerRetaliationTracker.clearHostileStatus(p, bot);
            }
        } catch (Throwable ignored) { }
        try {
            if (bot instanceof ServerPlayerInterface spi) {
                EntityPlayerActionPack ap = spi.getActionPack();
                ap.setForward(0f);
                ap.setStrafing(0f);
                ap.setSprinting(false);
                ap.start(ActionType.USE, null);
                ap.start(ActionType.ATTACK, null);
            }
            NavigationService.cancel(bot.level().getServer(), bot.getUUID(), "Fight over");
            if (f != null && f.mob != null) BotPathing.cancelFightTrip(bot); // the job's own trip carries on
            else BotPathing.cancel(bot);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------------
    // Per tick
    // ------------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        tick++;
        if (FIGHTS.isEmpty()) return;
        for (Fight f : FIGHTS.values()) {
            try {
                tickFight(server, f);
            } catch (Throwable t) {
                LOGGER.debug("[pvp] tick failed: {}", t.toString());
            }
        }
    }

    private static void end(MinecraftServer server, Fight f, ServerPlayer bot, String line) {
        FIGHTS.remove(f.bot);
        if (bot != null) {
            release(bot, f);
            if (line != null) HumanChat.say(server, bot.getName().getString(), line);
        }
    }

    private static void tickFight(MinecraftServer server, Fight f) {
        ServerPlayer bot = server.getPlayerList().getPlayer(f.bot);
        if (bot == null || !bot.isAlive() || bot.hasDisconnected()) { FIGHTS.remove(f.bot); return; }
        if (!(bot instanceof ServerPlayerInterface spi)) { FIGHTS.remove(f.bot); return; }
        EntityPlayerActionPack ap = spi.getActionPack();

        LivingEntity target;
        if (f.mob != null) {
            target = f.mob;
            if (!target.isAlive() || target.isRemoved()) {
                boolean blewUp = f.targetName.contains("creeper");
                end(server, f, bot, !blewUp && RNG.nextFloat() < 0.3f ? HumanChat.pick("got it", "ez", "bye " + f.targetName) : null);
                return;
            }
            if (bot.distanceToSqr(target) > 24 * 24) { // (a mob changing dimension is removed)
                giveUp(f);
                end(server, f, bot, null); // it's gone, back to work
                return;
            }
            // can't get at it (behind a fence, across water...) and it isn't hurting us: let it be
            if (tick - f.lastExchange > 20 * 15) {
                giveUp(f);
                end(server, f, bot, null);
                return;
            }
            // under 35% health (7): drop the fight, block it off, get back to the base (or away) and heal
            if (bot.getHealth() < 7f) {
                end(server, f, bot, HumanChat.pick("nope, too hurt. backing off", "i'm out, heading home to heal"));
                wall(bot, target);
                Home.retreat(bot, target);
                return;
            }
        } else {
            ServerPlayer player = server.getPlayerList().getPlayer(f.target);
            target = player;
            if (player == null || player.hasDisconnected()) {
                end(server, f, bot, HumanChat.pick("lol they left", "ragequit?", "gg i guess"));
                return;
            }
            if (!player.isAlive()) {
                end(server, f, bot, HumanChat.pick("gg", "gg ez", "ggs", "get good lol jk gg"));
                return;
            }
            if (player.isSpectator() || player.gameMode.isCreative()) {
                end(server, f, bot, "creative mode? ok i'm done");
                return;
            }
            if (player.level() != bot.level()) {
                end(server, f, bot, HumanChat.pick("where'd you go", "ran away huh"));
                return;
            }
        }
        double dist = Math.sqrt(bot.distanceToSqr(target));
        if (dist > 64) {
            end(server, f, bot, HumanChat.pick("ran away lol", "ok you escaped, gg"));
            return;
        }
        // under water and out of breath: forget the fight, get air
        if (bot.isUnderWater() && bot.getAirSupply() < 150) {
            end(server, f, bot, null);
            ap.look(bot.getYRot(), -60f);
            ap.start(ActionType.JUMP, Action.once());
            return;
        }
        if (tick >= f.nextGear) {
            f.nextGear = tick + 40;
            wearArmor(bot);
            if (!f.eating) equipWeapon(bot);
        }

        // Far away: path over there
        if (dist > (f.mob != null ? 6 : 12)) {
            ap.setForward(0f);
            ap.setStrafing(0f);
            if (tick >= f.nextNav) {
                f.nextNav = tick + 30;
                BlockPos tp = BlockPos.containing(target.getX(), target.getY(), target.getZ());
                BotPathing.Options o = BotPathing.Options.walkOnly();
                o.allowPlace = true;
                o.fight = true;
                BotPathing.goTo(bot, ActionPathfinder.near(tp.getX(), tp.getY(), tp.getZ(), f.mob != null ? 2.0 : 3.0), o);
            }
            return;
        }
        if (BotPathing.isActive(bot.getUUID())) BotPathing.cancelFightTrip(bot);

        // Aim at the chest, with a little human wobble
        Vec3 eye = bot.getEyePosition();
        Vec3 aim = target.position().add(0, target.getBbHeight() * 0.6, 0);
        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)) + (float) (RNG.nextGaussian() * 1.5);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz)) + (float) (RNG.nextGaussian() * 1.0);
        ap.look(yaw, pitch);

        // Low health: back off and eat
        boolean consuming = FoodConsumptionTool.isConsumptionInProgress(bot.getUUID());
        if (f.eating && !consuming && tick > f.blockUntil) f.eating = false;
        if (!f.eating && bot.getHealth() <= 8f && FoodConsumptionTool.hasSafeFood(bot)) {
            f.eating = true;
            f.blockUntil = tick + 10; // grace period for eating to start
            ap.start(ActionType.USE, null);
            EATER.submit(() -> {
                try { FoodConsumptionTool.consumeBestFood(bot); } catch (Throwable ignored) { }
            });
        }
        if (f.eating) {
            ap.setSprinting(dist < 6);
            ap.setForward(dist < 8 ? -1f : 0f);
            ap.setStrafing(f.strafe);
            if (bot.horizontalCollision && bot.onGround()) ap.start(ActionType.JUMP, Action.once());
            return;
        }

        // Strafe left/right, changing direction every so often
        if (tick >= f.strafeUntil) {
            f.strafe = (RNG.nextBoolean() ? 1f : -1f) * (0.5f + RNG.nextFloat() * 0.5f);
            f.strafeUntil = tick + 10 + RNG.nextInt(25);
        }

        if (f.mob != null && isCreeper(f.mob)) {
            creeperTick(bot, ap, f, target, dist);
            return;
        }
        // Skeleton: shield up while it draws its bow and we walk in; otherwise sprint-jump at it
        boolean skeleton = f.mob != null && isSkeleton(f.mob);
        if (skeleton && dist > 3.2 && hasShield(bot) && f.mob.isUsingItem()) {
            if (!f.shieldUp) {
                ap.start(ActionType.USE, Action.continuous());
                f.shieldUp = true;
            }
            ap.setSprinting(false);
            ap.setForward(1f);
            ap.setStrafing(f.strafe * 0.5f);
            if (bot.horizontalCollision && bot.onGround()) ap.start(ActionType.JUMP, Action.once());
            return;
        }
        if (f.shieldUp) {
            ap.start(ActionType.USE, null);
            f.shieldUp = false;
        }
        if (skeleton && dist > 3.5 && bot.onGround() && RNG.nextFloat() < 0.2f) {
            ap.start(ActionType.JUMP, Action.once()); // sprint-jumping closes the gap faster
        }
        boolean blocking = tick < f.blockUntil;
        float forward;
        if (dist > 3.2) forward = 1f;
        else if (dist > 2.2) forward = 0.5f;
        else forward = -0.4f;
        ap.setForward(blocking ? Math.min(forward, 0.3f) : forward);
        ap.setStrafing(dist < 5 ? f.strafe : 0f);
        ap.setSprinting(!blocking && dist > 2.5 && tick >= f.sprintResetUntil);
        if (bot.horizontalCollision && bot.onGround()) ap.start(ActionType.JUMP, Action.once());

        // Attack timing
        int cooldown = cooldownTicks(bot.getMainHandItem());
        long since = tick - f.lastAttack;
        boolean inReach = dist <= 3.1 && canSee(bot, target);
        if (blocking) {
            if (tick + 1 >= f.blockUntil) ap.start(ActionType.USE, null);
            return;
        }
        if (inReach) {
            boolean falling = !bot.onGround() && bot.getDeltaMovement().y < -0.08;
            if (since >= cooldown && (falling || bot.onGround())) {
                ap.start(ActionType.ATTACK, Action.once());
                f.lastAttack = tick;
                f.lastExchange = tick;
                f.sprintResetUntil = tick + 3; // w-tap: more knockback on the next hit
                // sometimes put the shield up between hits
                if (hasShield(bot) && RNG.nextFloat() < 0.35f && cooldown >= 10) {
                    f.blockUntil = tick + 4 + RNG.nextInt(Math.max(1, cooldown - 8));
                    ap.start(ActionType.USE, Action.continuous());
                }
            } else if (bot.onGround() && since >= cooldown - 5 && since < cooldown && RNG.nextFloat() < 0.35f) {
                ap.start(ActionType.JUMP, Action.once()); // crit: hit on the way down
            }
        }
    }

    /** Throws a block (or two) down between the bot and the mob to buy time. Server thread. */
    private static void wall(ServerPlayer bot, LivingEntity mob) {
        try {
            double dx = mob.getX() - bot.getX(), dz = mob.getZ() - bot.getZ();
            int sx = Math.abs(dx) >= Math.abs(dz) ? (int) Math.signum(dx) : 0;
            int sz = sx == 0 ? (int) Math.signum(dz) : 0;
            if (sx == 0 && sz == 0) return;
            BlockPos feet = BotPathing.feet(bot);
            // underground this would wall up its own tunnel or stairs: just run
            if (Surface.underground(bot.level(), feet)) return;
            for (BlockPos p : new BlockPos[]{feet.offset(sx, 0, sz), feet.offset(sx, 1, sz)}) {
                String blk = Building.firstItem(bot, LevelPathWorld::isThrowaway);
                if (blk == null) return;
                if (Building.isFree(bot.level(), p) && bot.level().getFluidState(p).isEmpty() && Building.placeAt(bot, p, blk)) {
                    MiningSkills.ownBlock(p);
                }
            }
        } catch (Throwable ignored) { }
    }

    private static boolean canSee(ServerPlayer bot, LivingEntity target) {
        try {
            var hit = bot.level().clip(new ClipContext(bot.getEyePosition(), target.getEyePosition(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
            return hit.getType() == HitResult.Type.MISS;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Ticks between full-strength hits for what's in hand (vanilla attack speeds). */
    static int cooldownTicks(ItemStack held) {
        String p = SurvivalBrain.itemPath(held);
        if (p.endsWith("_sword")) return 13;
        if (p.endsWith("_axe")) {
            if (p.startsWith("wooden_") || p.startsWith("stone_")) return 25;
            if (p.startsWith("iron_")) return 23;
            return 20;
        }
        if (p.equals("trident")) return 18;
        if (p.equals("mace")) return 34;
        if (p.endsWith("_pickaxe")) return 17;
        if (p.endsWith("_shovel")) return 20;
        return 5; // fist
    }

    // ------------------------------------------------------------------------
    // Gear
    // ------------------------------------------------------------------------

    static int weaponScore(String p) {
        int tier = SurvivalBrain.toolTier(p);
        if (p.endsWith("_sword")) return 20 + tier * 3;
        if (p.endsWith("_axe")) return 10 + tier * 3;
        if (p.equals("trident")) return 30;
        return 0;
    }

    /** Best sword/axe into the hotbar and in hand. */
    static void equipWeapon(ServerPlayer bot) {
        Inventory inv = bot.getInventory();
        int size = Math.min(36, inv.getContainerSize());
        int best = -1, bestScore = 0;
        for (int i = 0; i < size; i++) {
            int sc = weaponScore(SurvivalBrain.itemPath(inv.getItem(i)));
            if (sc > bestScore || (sc == bestScore && sc > 0 && i < 9 && best >= 9)) { bestScore = sc; best = i; }
        }
        if (best < 0) return;
        if (best < 9) { inv.setSelectedSlot(best); return; }
        int slot = 0;
        for (int i = 0; i < 9; i++) if (inv.getItem(i).isEmpty()) { slot = i; break; }
        ItemStack w = inv.getItem(best);
        inv.setItem(best, inv.getItem(slot));
        inv.setItem(slot, w);
        inv.setSelectedSlot(slot);
        inv.setChanged();
    }

    private static int armorScore(String p) {
        int t = SurvivalBrain.toolTier(p);
        if (p.startsWith("leather_")) t = 1;
        if (p.startsWith("chainmail_")) t = 2;
        if (p.startsWith("turtle_")) t = 2;
        return t;
    }

    /** Puts on the best armour it carries, and a shield or totem in the off hand. */
    static void wearArmor(ServerPlayer bot) {
        Inventory inv = bot.getInventory();
        int size = Math.min(36, inv.getContainerSize());
        String[][] parts = {{"_helmet", "HEAD"}, {"_chestplate", "CHEST"}, {"_leggings", "LEGS"}, {"_boots", "FEET"}};
        for (String[] part : parts) {
            EquipmentSlot slot = EquipmentSlot.valueOf(part[1]);
            int worn = armorScore(SurvivalBrain.itemPath(bot.getItemBySlot(slot)));
            if (part[0].equals("_helmet") && SurvivalBrain.itemPath(bot.getItemBySlot(slot)).equals("turtle_helmet")) worn = 2;
            int best = -1, bestScore = worn;
            for (int i = 0; i < size; i++) {
                String p = SurvivalBrain.itemPath(inv.getItem(i));
                if (!p.endsWith(part[0])) continue;
                int sc = armorScore(p);
                if (sc > bestScore) { bestScore = sc; best = i; }
            }
            if (best < 0) continue;
            ItemStack newPiece = inv.getItem(best);
            ItemStack old = bot.getItemBySlot(slot);
            inv.setItem(best, old.isEmpty() ? ItemStack.EMPTY : old);
            bot.setItemSlot(slot, newPiece);
        }
        // off hand: totem beats shield
        ItemStack off = bot.getItemBySlot(EquipmentSlot.OFFHAND);
        String offPath = SurvivalBrain.itemPath(off);
        if (!offPath.equals("totem_of_undying")) {
            int totem = -1, shield = -1;
            for (int i = 0; i < size; i++) {
                String p = SurvivalBrain.itemPath(inv.getItem(i));
                if (p.equals("totem_of_undying") && totem < 0) totem = i;
                if (p.equals("shield") && shield < 0) shield = i;
            }
            int pick = totem >= 0 ? totem : (offPath.isEmpty() ? shield : -1);
            if (pick >= 0) {
                ItemStack item = inv.getItem(pick);
                inv.setItem(pick, off.isEmpty() ? ItemStack.EMPTY : off);
                bot.setItemSlot(EquipmentSlot.OFFHAND, item);
            }
        }
        inv.setChanged();
    }

    private static boolean hasShield(ServerPlayer bot) {
        return SurvivalBrain.itemPath(bot.getItemBySlot(EquipmentSlot.OFFHAND)).equals("shield");
    }

    /** Hands the bot a full set: "iron", "diamond" or "netherite". */
    static void giveKit(ServerPlayer bot, String kit) {
        String m = switch (kit) {
            case "iron", "diamond", "netherite" -> kit;
            default -> null;
        };
        if (m == null) return;
        for (String part : new String[]{"sword", "axe", "helmet", "chestplate", "leggings", "boots"}) {
            SurvivalBrain.give(bot, m + "_" + part, 1);
        }
        SurvivalBrain.give(bot, "shield", 1);
        SurvivalBrain.give(bot, "cooked_beef", 16);
        SurvivalBrain.give(bot, "golden_apple", m.equals("iron") ? 2 : 4);
    }

    // ------------------------------------------------------------------------
    // /bot pvp
    // ------------------------------------------------------------------------

    public static void registerCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(
                Commands.literal("bot").then(Commands.literal("pvp")
                        .then(Commands.argument("bot", EntityArgument.player())
                                .then(Commands.literal("stop").executes(ctx -> {
                                    ServerPlayer bot = EntityArgument.getPlayer(ctx, "bot");
                                    stop(bot);
                                    reply(ctx, bot.getName().getString() + " stopped fighting.");
                                    return 1;
                                }))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(ctx -> startFromCommand(ctx, null))
                                        .then(Commands.argument("kit", StringArgumentType.word())
                                                .executes(ctx -> startFromCommand(ctx, StringArgumentType.getString(ctx, "kit")))))))));
    }

    private static int startFromCommand(CommandContext<CommandSourceStack> ctx, String kit) {
        try {
            ServerPlayer bot = EntityArgument.getPlayer(ctx, "bot");
            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
            if (!HumanBehavior.isAiBot(bot)) {
                reply(ctx, bot.getName().getString() + " isn't an AI bot.");
                return 0;
            }
            if (kit != null && !kit.matches("(?i)none|iron|diamond|netherite")) {
                reply(ctx, "Kit must be none, iron, diamond or netherite.");
                return 0;
            }
            String line = start(bot, target, kit);
            HumanChat.say(bot.level().getServer(), bot.getName().getString(), line);
            reply(ctx, bot.getName().getString() + " is fighting " + target.getName().getString()
                    + (kit == null || kit.equalsIgnoreCase("none") ? "" : " with a " + kit.toLowerCase(Locale.ROOT) + " kit") + ".");
            return 1;
        } catch (Exception e) {
            reply(ctx, "Couldn't start the fight: " + e.getMessage());
            return 0;
        }
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
    }
}
