package io.github.yudiiee.aicompanion.GameAI.human;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import io.github.yudiiee.aicompanion.Network.configNetworkManager;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * {@code /humanlike} — shows and toggles the human-like behaviour options.
 * <pre>
 *   /humanlike                         show current settings
 *   /humanlike typing on|off           typing delay before messages
 *   /humanlike chatstyle on|off        &lt;Name&gt; chat vs. /say broadcasts
 *   /humanlike unnamed on|off          answer chat that doesn't use the bot's name
 *   /humanlike body on|off             look at people, crouch back, fidgets
 *   /humanlike autoplay on|off         play on its own (wood, tools, mining, exploring)
 *   /humanlike reactions on|off        "gg", "rip", "ow" etc.
 *   /humanlike smalltalk on|off        unprompted remarks when it's quiet
 *   /humanlike statuslines on|off      hide robotic status lines
 *   /humanlike reload                  re-read config/ai-companion-humanlike.json
 * </pre>
 */
public final class HumanLikeCommand {

    private HumanLikeCommand() {}

    public static void register() {
        PvpController.registerCommand();
        BotPathing.registerCommand();
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("humanlike")
                    .requires(HumanLikeCommand::allowed)
                    .executes(HumanLikeCommand::show);
            root.then(toggle("typing", d -> d.typingDelay, (d, v) -> d.typingDelay = v));
            root.then(toggle("chatstyle", d -> d.playerStyleChat, (d, v) -> d.playerStyleChat = v));
            root.then(toggle("unnamed", d -> d.answerWithoutName, (d, v) -> d.answerWithoutName = v));
            root.then(toggle("body", d -> d.bodyLanguage, (d, v) -> d.bodyLanguage = v));
            root.then(toggle("autoplay", d -> d.autoPlay, (d, v) -> d.autoPlay = v));
            root.then(toggle("reactions", d -> d.reactions, (d, v) -> d.reactions = v));
            root.then(toggle("smalltalk", d -> d.idleChatter, (d, v) -> d.idleChatter = v));
            root.then(toggle("statuslines", d -> !d.hideRobotStatusLines, (d, v) -> d.hideRobotStatusLines = !v));
            root.then(toggle("store", d -> d.autoStore, (d, v) -> d.autoStore = v));
            root.then(toggle("home", d -> d.autoHome, (d, v) -> d.autoHome = v));
            root.then(Commands.literal("reload").executes(ctx -> {
                HumanConfig.load();
                reply(ctx, "Reloaded human-like settings.");
                return 1;
            }));
            dispatcher.register(root);
        });
    }

    private static boolean allowed(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return true; // console / command block
        if (configNetworkManager.canManageServerConfig(player)) return true;
        MinecraftServer server = source.getServer();
        return server != null && !server.isDedicatedServer() && server.isSingleplayerOwner(player.nameAndId());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> toggle(String name,
                                                                     Function<HumanConfig.Data, Boolean> getter,
                                                                     BiConsumer<HumanConfig.Data, Boolean> setter) {
        return Commands.literal(name)
                .executes(ctx -> {
                    reply(ctx, name + " is " + (getter.apply(HumanConfig.get()) ? "on" : "off"));
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> set(ctx, name, setter, true)))
                .then(Commands.literal("off").executes(ctx -> set(ctx, name, setter, false)));
    }

    private static int set(CommandContext<CommandSourceStack> ctx, String name,
                           BiConsumer<HumanConfig.Data, Boolean> setter, boolean value) {
        setter.accept(HumanConfig.get(), value);
        HumanConfig.save();
        reply(ctx, name + " turned " + (value ? "on" : "off") + ".");
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        HumanConfig.Data d = HumanConfig.get();
        reply(ctx, "Human-like settings: typing=" + onOff(d.typingDelay)
                + ", chatstyle=" + onOff(d.playerStyleChat)
                + ", unnamed=" + onOff(d.answerWithoutName)
                + ", body=" + onOff(d.bodyLanguage)
                + ", autoplay=" + onOff(d.autoPlay)
                + ", reactions=" + onOff(d.reactions)
                + ", smalltalk=" + onOff(d.idleChatter)
                + ", statuslines=" + onOff(!d.hideRobotStatusLines)
                + ", store=" + onOff(d.autoStore)
                + ", home=" + onOff(d.autoHome));
        return 1;
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
    }
}
