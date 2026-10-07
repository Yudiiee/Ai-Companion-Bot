package io.github.yudiiee.aicompanion.GameAI.human;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import io.github.yudiiee.aicompanion.AICompanion;
import io.github.yudiiee.aicompanion.GameAI.companion.BotStance;
import io.github.yudiiee.aicompanion.GameAI.companion.CompanionController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A short first-person description of what the bot can see and feel right now,
 * so its chat replies are grounded in the game ("it's getting dark", "I only have
 * a stone pick") instead of sounding like a generic assistant.
 */
public final class SituationSnapshot {

    private SituationSnapshot() {}

    /**
     * Safe to call from worker threads: the world is read on the server thread
     * (waiting at most half a second), falling back to an empty description.
     */
    public static String describe(String botName) {
        var server = AICompanion.serverInstance;
        if (server == null || botName == null) return "";
        java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerPlayer bot = server.getPlayerList().getPlayerByName(botName);
                result.complete(bot == null ? "" : describe(bot));
            } catch (Exception e) {
                result.complete("");
            }
        });
        try {
            return result.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    public static String describe(ServerPlayer bot) {
        try {
            StringBuilder sb = new StringBuilder();
            String name = bot.getName().getString();

            long t = bot.level().getDefaultClockTime() % 24000L;
            String time = t < 1000 ? "early morning" : t < 6000 ? "morning" : t < 11000 ? "afternoon"
                    : t < 13000 ? "sunset" : t < 23000 ? "night" : "dawn";
            sb.append("- Time of day: ").append(time);
            if (bot.level().isRaining()) sb.append(", raining");
            sb.append('\n');

            String dim = bot.level().dimension().identifier().getPath();
            sb.append("- Dimension: ").append(dim.replace('_', ' ')).append('\n');

            sb.append("- Your health: ").append(Math.round(bot.getHealth())).append("/20, food: ")
              .append(bot.getFoodData().getFoodLevel()).append("/20\n");

            ItemStack held = bot.getMainHandItem();
            sb.append("- Holding: ").append(held.isEmpty() ? "nothing" : held.getHoverName().getString()).append('\n');

            // Rough inventory summary (top items by count)
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
                ItemStack s = bot.getInventory().getItem(i);
                if (s.isEmpty()) continue;
                counts.merge(s.getHoverName().getString(), s.getCount(), Integer::sum);
            }
            if (!counts.isEmpty()) {
                List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
                entries.sort((a, b) -> b.getValue() - a.getValue());
                sb.append("- Inventory: ");
                for (int i = 0; i < Math.min(8, entries.size()); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(entries.get(i).getValue()).append("x ").append(entries.get(i).getKey());
                }
                sb.append('\n');
            } else {
                sb.append("- Inventory: empty\n");
            }

            // Who and what is around
            List<String> people = new ArrayList<>();
            for (Entity e : bot.level().getEntities(bot, bot.getBoundingBox().inflate(24.0))) {
                if (e instanceof ServerPlayer p && !HumanBehavior.isBot(p)) {
                    people.add(p.getName().getString() + " (" + Math.round(Math.sqrt(p.distanceToSqr(bot))) + " blocks away)");
                }
            }
            sb.append("- Players near you: ").append(people.isEmpty() ? "none" : String.join(", ", people)).append('\n');
            // Mobs only matter once one attacks (don't narrate every zombie in the distance)
            String fighting = PvpController.mobName(bot.getUUID());
            if (fighting != null) sb.append("- Fighting a ").append(fighting).append(" that attacked you\n");
            Home.Base home = Home.get(bot);
            if (home != null) {
                sb.append("- Your base: a small house at ").append(home.middle().getX()).append(' ')
                        .append(home.middle().getY()).append(' ').append(home.middle().getZ())
                        .append(home.inside(bot.blockPosition()) ? " (you're inside it)" : "").append('\n');
            }

            String team = Stock.teamSummary(name);
            if (!team.isEmpty()) sb.append("- Team stock: ").append(team).append('\n');
            String depot = Depot.summary(bot.level());
            if (!depot.isEmpty()) sb.append("- Community depot (shared chests anyone can use for builds): ").append(depot).append('\n');

            BotStance stance = CompanionController.getInstance().getStance(name);
            sb.append("- What you're doing: ").append(switch (stance) {
                case FOLLOW -> "following a player around";
                case STAY -> "staying put where you were asked to wait";
                default -> "doing your own thing / exploring";
            }).append('\n');
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }
}
