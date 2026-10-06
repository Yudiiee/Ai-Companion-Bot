package io.github.yudiiee.aicompanion.GameAI.human;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import io.github.yudiiee.aicompanion.FilingSystem.LLMClientFactory;
import io.github.yudiiee.aicompanion.GameAI.autonomous.AutonomousGoalEngine;
import io.github.yudiiee.aicompanion.GameAI.autonomous.AutonomousManager;
import io.github.yudiiee.aicompanion.GameAI.companion.BotStance;
import io.github.yudiiee.aicompanion.GameAI.companion.CompanionController;
import io.github.yudiiee.aicompanion.ServiceLLMClients.LLMClient;
import io.github.yudiiee.aicompanion.ServiceLLMClients.LLMServiceHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side listener for player chat.
 *
 * <ul>
 *   <li>Keeps a short transcript so replies can refer back to the conversation.</li>
 *   <li>Answers reflexive small talk ("hi", "ty", "gg") instantly without the LLM.</li>
 *   <li>Understands "follow me", "stay here", "come here", "go explore" whether or
 *       not the bot's name is used.</li>
 *   <li>When nobody is named but the bot is clearly the one being spoken to (it's
 *       close by, or you were just talking with it, or it's the only other player),
 *       it replies anyway, the way a person would.</li>
 * </ul>
 *
 * <p>Messages that name a bot are still handled by the original client-side path on
 * single-player/LAN worlds; on a dedicated server (no client code) this listener
 * handles them too.
 */
public final class HumanChatListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-humanlike");

    private HumanChatListener() {}

    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, boundChatType) -> {
            try {
                onChat(sender.level().getServer(), sender, message.signedContent());
            } catch (Exception e) {
                LOGGER.warn("[humanlike] chat handling failed: {}", e.getMessage());
            }
        });
        LOGGER.info("[humanlike] chat listener registered");
    }

    // ------------------------------------------------------------------------
    // Commands understood locally
    // ------------------------------------------------------------------------

    public enum Local { FOLLOW, STAY, WANDER, COME, GIVE, INVENTORY, CRAFT, PVP, DIG, STRIP, COLLECT, FARM, ORE, WOOD, MINE, PLAY, STOP, HOUSE, CHEST, TAKE, STORE, HOME, CHESTS, BLUEPRINT, SMALL_TALK }

    private static final Pattern FOLLOW = Pattern.compile("\\b(follow me|come with me|let'?s go|stick with me|tag along)\\b");
    private static final Pattern STAY = Pattern.compile("\\b(stay here|stay there|stay put|wait here|wait there|stop following|don'?t move|stop moving)\\b");
    private static final Pattern WANDER = Pattern.compile("\\b(go explore|go wander|wander around|do your (own )?thing|you'?re free|roam around|go have fun)\\b");
    private static final Pattern COME = Pattern.compile("\\b(come here|come to me|get over here|over here|come back)\\b");
    private static final Pattern GIVE = Pattern.compile("\\b(give me|gimme|toss me|throw me|pass me|drop me|can i have|can i get|hand me|give)\\b\\s*(.*)");
    private static final Pattern INVENTORY = Pattern.compile("\\b(what do you have|what have you got|what'?s in your inv\\w*|your inventory|inventory|what did you (get|find)|show me your (stuff|items))\\b");
    private static final Pattern CRAFT = Pattern.compile("\\b(craft|make (a |some |yourself a )?(pick|pickaxe|sword|tools?|torch(es)?))\\b");
    private static final Pattern ORE = Pattern.compile("\\b(get|find|mine|look for|go for|collect|dig for|search for)\\b.*?\\b(coal|iron|copper|diamonds?|gold|redstone|lapis|emeralds?|ores?)\\b");
    private static final Pattern WOOD = Pattern.compile("\\b(chop|lumber|((get|cut|collect|gather|punch|grab)\\b.*?\\b(wood|logs?|trees?)))\\b");
    private static final Pattern MINE = Pattern.compile("(^(go |start |keep |let'?s |pls |please |can you |could you |you )*(mine|dig)\\b)|\\bmining\\b|\\b(get|collect|grab)\\b.*?\\b(stone|cobble|cobblestone)\\b");
    private static final Pattern PLAY = Pattern.compile("^(play|go play|start playing|keep playing|do something|do stuff|help|help me|get to work|get working|go do something|do whatever|survive|start)[!.\\s]*$");
    private static final Pattern PVP = Pattern.compile("\\b(fight me|pvp me|1v1 me|duel me|attack me|let'?s (fight|pvp|1v1|duel)|wanna (fight|pvp|1v1|duel)|(fight|pvp|1v1|duel) with me)\\b");
    private static final Pattern HOME = Pattern.compile(
            "^(?:(?:ok|okay|now|pls|please|hey|yo|bro|you|u|can you|could you|let'?s|lets)\\s+)*(go|head|run|get)\\s+(back\\s+)?(home|to (the |our |your )?(base|house|home))\\b");
    private static final Pattern CHESTS = Pattern.compile(
            "\\b((what'?s|what is|whats) (in|inside) (the|our|your|ur) (chests?|storage|base)|what do (we|you) have (in|at) (the |our )?(chests?|storage|base)"
            + "|check (the|our) (chests?|storage))\\b");
    private static final Pattern STOP = Pattern.compile("^(stop|stop it|stop that|chill|relax|take a break|wait|hold on|nvm|never mind|cancel|"
            + "stop (mining|digging|chopping|working|collecting|strip mining|that job)|that'?s enough|enough)[!.\\s]*$");

    /**
     * Classifies a message the bot handles itself (stance changes, reflexive small talk).
     * Also used by the client-side path so the same message isn't sent to the LLM twice.
     */
    public static Local classifyLocal(String message, String botName) {
        String m = HumanReactions.normalise(message, botName);
        if (m.isEmpty()) return null;
        if (m.length() <= 60) {
            if (STAY.matcher(m).find()) return Local.STAY;
            if (STOP.matcher(m).find()) return Local.STOP;
            if (HOME.matcher(m).find()) return Local.HOME;
            if (CHESTS.matcher(m).find()) return Local.CHESTS;
            if (Storage.CHEST_HERE.matcher(m).find()) return Local.CHEST;
            if (Storage.parseTake(m) != null) return Local.TAKE;
            if (Blueprints.parse(m, null, null) != null) return Local.BLUEPRINT;
            if (House.request(m, null) != null) return Local.HOUSE;
            if (Storage.STORE.matcher(m).find()) return Local.STORE;
            if (FOLLOW.matcher(m).find()) return Local.FOLLOW;
            if (COME.matcher(m).find()) return Local.COME;
            if (WANDER.matcher(m).find()) return Local.WANDER;
            if (INVENTORY.matcher(m).find()) return Local.INVENTORY;
            if (GIVE.matcher(m).find()) return Local.GIVE;
            if (PVP.matcher(m).find()) return Local.PVP;
            if (MineHub.parseDigDown(m, null) != null) return Local.DIG;
            if (Farm.request(m) != null) return Local.FARM;
            if (MiningSkills.parseStrip(m) != null) return Local.STRIP;
            if (MiningSkills.parseCollect(m) != null) return Local.COLLECT;
            if (CRAFT.matcher(m).find()) return Local.CRAFT;
            if (ORE.matcher(m).find()) return Local.ORE;
            if (WOOD.matcher(m).find()) return Local.WOOD;
            if (MINE.matcher(m).find()) return Local.MINE;
            if (PLAY.matcher(m).find()) return Local.PLAY;
        }
        if (HumanReactions.quickReply(botName, "", message) != null) return Local.SMALL_TALK;
        return null;
    }

    // ------------------------------------------------------------------------
    // Main handler (server thread)
    // ------------------------------------------------------------------------

    private static void onChat(MinecraftServer server, ServerPlayer sender, String text) {
        if (server == null || sender == null || text == null || text.isBlank()) return;
        if (HumanBehavior.isBot(sender)) return;
        if (text.startsWith("/")) return;

        ConversationMemory.recordPlayer(sender.getName().getString(), text);
        if (PvpController.onOpponentChat(server, sender, text)) return; // "gg", "i give up"...
        HumanBehavior.onPlayerChat(sender);

        List<ServerPlayer> bots = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (HumanBehavior.isAiBot(p) && p.isAlive()) bots.add(p);
        }
        if (bots.isEmpty()) return;

        ServerPlayer named = mentionedBot(text, bots);
        ServerPlayer target = named != null ? named : impliedBot(server, sender, text, bots);
        if (target == null) return;

        String botName = target.getName().getString();
        boolean ongoing = ConversationMemory.sinceExchange(sender.getUUID()) <= 90_000L
                && botName.equals(ConversationMemory.lastPartner(sender.getUUID()));

        Local local = classifyLocal(text, botName);
        if (local != null) {
            engage(target, sender);
            handleLocal(server, target, sender, text, local);
            return;
        }

        // On a single-player / LAN world the host's own named messages are already sent to
        // the LLM by the host's client-side listener; don't answer those twice. Messages from
        // LAN guests never reach that listener, so they're handled here like on a server.
        if (named != null && !server.isDedicatedServer() && isHost(server, sender)) {
            engage(target, sender);
            return;
        }
        // Without a name, only answer what a person would take as meant for them:
        // questions, "you/we/let's" talk, or anything mid-conversation.
        if (named == null && (!HumanConfig.get().answerWithoutName || (!ongoing && !looksDirected(text)))) return;

        engage(target, sender);
        askLlm(target, sender, text);
    }

    private static boolean isHost(MinecraftServer server, ServerPlayer player) {
        try {
            return server.isSingleplayerOwner(player.nameAndId());
        } catch (Throwable t) {
            return true;
        }
    }

    private static void engage(ServerPlayer bot, ServerPlayer player) {
        ConversationMemory.markExchange(player.getUUID(), bot.getName().getString());
        HumanBehavior.payAttention(bot, player, 100);
    }

    private static void handleLocal(MinecraftServer server, ServerPlayer bot, ServerPlayer sender, String text, Local local) {
        String botName = bot.getName().getString();
        CompanionController companion = CompanionController.getInstance();
        switch (local) {
            case FOLLOW -> companion.setStance(botName, BotStance.FOLLOW, sender);
            case STAY -> companion.setStance(botName, BotStance.STAY, null);
            case WANDER -> companion.setStance(botName, BotStance.WANDER, null);
            case COME -> {
                if (Surface.shouldClimb(server, bot, sender.getUUID())) {
                    // down in a mine: dig up first, like a player would
                    MiningSkills.Request req = Surface.comeUp(sender.getUUID(), sender.getName().getString());
                    SurvivalBrain.startJob(bot, req.label(), true, req.job());
                    HumanChat.say(server, botName, req.ack());
                    return;
                }
                if (companion.getStance(botName) == BotStance.STAY) {
                    companion.setStanceQuiet(botName, BotStance.WANDER, null);
                }
                SurvivalBrain.pause(bot, 60_000L); // hang around once there
                companion.comeTo(botName, sender);
                HumanChat.say(server, botName, HumanReactions.comeAck());
            }
            case GIVE -> {
                Matcher gm = GIVE.matcher(HumanReactions.normalise(text, botName));
                String what = gm.find() ? gm.group(2) : "";
                String reply = SurvivalBrain.giveTo(bot, sender, what);
                if (reply != null && reply.contains("don't have") && !what.isBlank()
                        && Storage.stockOf(bot.level(), Storage.matcherFor(what)) > 0) {
                    // not on me, but it's in the chest: go get it
                    Matcher cm = Pattern.compile("^(\\d+)").matcher(what.trim());
                    int count = cm.find() ? Integer.parseInt(cm.group(1)) : 0;
                    String item = Storage.itemName(what);
                    SurvivalBrain.startJob(bot, "get " + item + " from the chest", true,
                            (s, bt, bb) -> Storage.fetchFor(s, bt, bb, what, count, sender.getUUID()));
                    HumanChat.say(server, botName, HumanChat.pick("don't have any on me, but there's some in the chest. one sec",
                            "it's in the chest, gonna grab it for you"));
                    return;
                }
                HumanChat.say(server, botName, reply);
            }
            case CHESTS -> HumanChat.say(server, botName, Storage.describe(bot));
            case INVENTORY -> HumanChat.say(server, botName, SurvivalBrain.inventorySummary(bot));
            case CRAFT -> HumanChat.say(server, botName, SurvivalBrain.craftNow(bot));
            case PVP -> HumanChat.say(server, botName, PvpController.start(bot, sender, null));
            case CHEST -> HumanChat.say(server, botName, Storage.designate(bot, sender));
            case HOME -> {
                MiningSkills.Request req = Home.goHomeRequest();
                SurvivalBrain.startJob(bot, req.label(), true, req.job());
                HumanChat.say(server, botName, req.ack());
            }
            case HOUSE, TAKE, STORE -> {
                String m = HumanReactions.normalise(text, botName);
                MiningSkills.Request req = local == Local.HOUSE ? House.request(m, sender.getUUID()) : Storage.request(m, sender.getUUID());
                if (req == null) return;
                String doing = SurvivalBrain.jobName(bot);
                if (req.label().equals(doing) || (local == Local.HOUSE && "set up a base".equals(doing))) {
                    // asking again doesn't start it over (that just threw away the progress)
                    HumanChat.say(server, botName, HumanChat.pick("already on it", "yep, working on it",
                            "on it, just getting everything ready first"));
                    return;
                }
                SurvivalBrain.startJob(bot, req.label(), true, req.job());
                HumanChat.say(server, botName, req.ack());
            }
            case BLUEPRINT -> {
                Blueprints.Ask a = Blueprints.parse(HumanReactions.normalise(text, botName), sender, bot);
                if (a == null) return;
                if (a.job() == null) {
                    String line = a.say() == null ? null : a.say().get();
                    if (line != null && !line.isBlank()) HumanChat.say(server, botName, line);
                    return;
                }
                MiningSkills.Request req = a.job();
                if (req.label().equals(SurvivalBrain.jobName(bot))) {
                    HumanChat.say(server, botName, HumanChat.pick("already on it", "yep, working on it"));
                    return;
                }
                SurvivalBrain.startJob(bot, req.label(), true, req.job());
                HumanChat.say(server, botName, req.ack());
            }
            case DIG, FARM -> {
                String m = HumanReactions.normalise(text, botName);
                MiningSkills.Request req = local == Local.DIG ? MineHub.parseDigDown(m, sender.getYRot()) : Farm.request(m);
                if (req == null) return;
                if (req.label().equals(SurvivalBrain.jobName(bot))) {
                    HumanChat.say(server, botName, HumanChat.pick("already on it", "yep, working on it"));
                    return;
                }
                SurvivalBrain.startJob(bot, req.label(), true, req.job());
                HumanChat.say(server, botName, req.ack());
            }
            case STRIP, COLLECT -> {
                String m = HumanReactions.normalise(text, botName);
                MiningSkills.Request req = local == Local.STRIP ? MiningSkills.parseStrip(m, sender.getYRot()) : MiningSkills.parseCollect(m, sender.getUUID());
                if (req == null) return;
                SurvivalBrain.startJob(bot, req.label(), true, req.job());
                HumanChat.say(server, botName, req.ack());
            }
            case ORE -> {
                Matcher om = ORE.matcher(HumanReactions.normalise(text, botName));
                String ore = om.find() ? om.group(2).replaceAll("s$", "") : "ore";
                if (ore.equals("diamond")) ore = "diamond";
                SurvivalBrain.command(bot, SurvivalBrain.Task.ORE, ore);
                HumanChat.say(server, botName, ore.equals("ore")
                        ? HumanChat.pick("ok, going ore hunting", "sure, i'll look for ores")
                        : HumanChat.pick("ok, looking for " + ore, "on it, " + ore + " incoming (hopefully)", "sure, i'll find some " + ore));
            }
            case WOOD -> {
                SurvivalBrain.command(bot, SurvivalBrain.Task.WOOD, null);
                HumanChat.say(server, botName, HumanChat.pick("ok getting wood", "sure, i'll chop some trees", "on it"));
            }
            case MINE -> {
                SurvivalBrain.command(bot, SurvivalBrain.Task.STONE, null);
                HumanChat.say(server, botName, HumanChat.pick("ok let's mine", "sure, going mining", "on it, gonna dig"));
            }
            case PLAY -> {
                SurvivalBrain.play(bot);
                HumanChat.say(server, botName, HumanChat.pick("ok let's get going", "alright, i'll get some stuff done", "sure, gonna go do my thing", "bet"));
            }
            case STOP -> {
                PvpController.stop(bot);
                SurvivalBrain.stopTask(bot);
                HumanChat.say(server, botName, HumanChat.pick("ok ok, stopping", "alright", "k, taking a break"));
            }
            case SMALL_TALK -> {
                String reply = HumanReactions.quickReply(botName, sender.getName().getString(), text);
                if (reply != null && !reply.isEmpty()) HumanChat.say(server, botName, reply);
            }
        }
    }

    private static final Pattern DIRECTED = Pattern.compile(
            "\\?|\\b(you|u|ur|your|you'?re|we|us|our|let'?s|lets|wanna|want to|can you|could you|help|anyone|someone|bot)\\b");

    /** Heuristic: does this unnamed message expect an answer from whoever is around? */
    static boolean looksDirected(String text) {
        String m = text.toLowerCase(Locale.ROOT).trim();
        return DIRECTED.matcher(m).find();
    }

    private static void askLlm(ServerPlayer bot, ServerPlayer sender, String text) {
        String provider = System.getProperty("aicompanion.llmMode", "custom");
        if ("player2".equals(provider)) return; // needs the client-side callback
        try {
            LLMClient client = LLMClientFactory.createClient(provider);
            if (client == null) {
                noLlmReply(bot, sender);
                return;
            }
            LLMServiceHandler.runFromChat(text, bot.getName().getString(), sender.getUUID(), client);
        } catch (Exception e) {
            LOGGER.warn("[humanlike] could not reach the language model: {}", e.getMessage());
        }
    }

    private static volatile long lastNoLlmNotice = 0L;

    /** Without an AI provider the bot can't really converse; answer like a busy player would. */
    private static void noLlmReply(ServerPlayer bot, ServerPlayer sender) {
        MinecraftServer server = bot.level().getServer();
        String name = bot.getName().getString();
        if (HumanReactions.cooldown(name + ":nollm-reply", 20_000L)) {
            HumanChat.say(server, name, HumanChat.pick("huh?", "lol idk", "hm?", "not sure what you mean, try \"get wood\" or \"go mining\"",
                    "idk man, want me to get wood or mine?"));
        }
        long now = System.currentTimeMillis();
        if (now - lastNoLlmNotice > 10 * 60_000L) {
            lastNoLlmNotice = now;
            HumanChat.systemNotice(server, "For real conversations, set up an AI provider with /configMan. Without one, "
                    + name + " understands: follow me, stay here, come here, get wood, mine 10 iron, get diamonds, collect sand, "
                    + "strip mine (at y -58), go mining, craft a pickaxe, build a house, store your stuff, use this chest, "
                    + "get me iron from the chest, what do you have, give me <item>, play, stop.");
        }
    }

    // ------------------------------------------------------------------------
    // Who is being talked to?
    // ------------------------------------------------------------------------

    private static ServerPlayer mentionedBot(String text, List<ServerPlayer> bots) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (ServerPlayer b : bots) {
            String n = b.getName().getString().toLowerCase(Locale.ROOT);
            Matcher m = Pattern.compile("(^|[^a-z0-9_])@?" + Pattern.quote(n) + "([^a-z0-9_]|$)").matcher(lower);
            if (m.find()) return b;
        }
        return null;
    }

    /**
     * Picks the bot this message is obviously meant for, or null if it could be for
     * someone else. Mirrors how people decide whether a message in chat is for them.
     */
    private static ServerPlayer impliedBot(MinecraftServer server, ServerPlayer sender, String text, List<ServerPlayer> bots) {
        // Addressed to another (human) player by name? Then it's not for us.
        String lower = text.toLowerCase(Locale.ROOT);
        int humansOnline = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (HumanBehavior.isBot(p)) continue;
            humansOnline++;
            if (p == sender) continue;
            String n = p.getName().getString().toLowerCase(Locale.ROOT);
            if (n.length() >= 3 && lower.contains(n)) return null;
        }

        // Continuing a recent conversation with a specific bot
        if (ConversationMemory.sinceExchange(sender.getUUID()) < 90_000L) {
            String partner = ConversationMemory.lastPartner(sender.getUUID());
            for (ServerPlayer b : bots) if (b.getName().getString().equals(partner)) return b;
        }

        // Only one bot, and it's just the two of you — or it's standing right there
        ServerPlayer nearest = null;
        double best = Double.MAX_VALUE;
        for (ServerPlayer b : bots) {
            if (b.level() != sender.level()) continue;
            double d = b.distanceToSqr(sender);
            if (d < best) { best = d; nearest = b; }
        }
        if (bots.size() == 1 && humansOnline == 1) return bots.get(0);
        if (nearest != null && best <= 16 * 16) return nearest;
        return null;
    }
}
