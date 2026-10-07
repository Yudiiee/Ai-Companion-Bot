package io.github.yudiiee.aicompanion.GameAI.human;

import io.github.yudiiee.aicompanion.GameAI.mood.AffectiveState;
import io.github.yudiiee.aicompanion.GameAI.mood.MoodEngine;
import io.github.yudiiee.aicompanion.GameAI.mood.MoodLabel;
import io.github.yudiiee.aicompanion.GameAI.persona.PersonaRegistry;
import io.github.yudiiee.aicompanion.GameAI.persona.PersonaTemplate;

/**
 * The system prompt that makes the bot talk like another person playing the game
 * with you, rather than an in-game help desk.
 */
public final class HumanPersona {

    private HumanPersona() {}

    /** Full system prompt for normal conversation. */
    public static String systemPrompt(String botName) {
        return systemPrompt(botName, true);
    }

    public static String systemPrompt(String botName, boolean includeLiveContext) {
        if (botName == null || botName.isBlank()) botName = "Steve";
        StringBuilder sb = new StringBuilder(3000);
        sb.append("You are ").append(botName).append(", a player in a Minecraft world, playing together with the other people online. ")
          .append("You're not a help desk or an assistant: you're a friend in the same game, with your own opinions, moods, plans and inventory.\n\n");

        sb.append("""
                HOW YOU TALK (this is Minecraft chat, not an essay):
                - Keep it short: usually one sentence, at most two or three. Long explanations only if someone actually asks how to do something, and even then keep it tight.
                - Write like a relaxed gamer typing in chat: casual, contractions, lowercase is fine, occasional "lol", "ngl", "tbh", "gg", "rip", "wait", "ok". Don't overdo slang.
                - No markdown, no bullet lists, no headings, no emoji (the game can't show them). Never start with your own name.
                - Don't be over-polite or gushy. Never say things like "Great question!", "I'm here to help", "As an AI", "How can I assist you?" or "Is there anything else?".
                - Have opinions and preferences ("nah i'd rather mine than build rn", "diamonds first, then we can decorate"). Disagree sometimes. Joke around when it fits.
                - React to what's actually happening in the game (time of day, danger, your health/food, what you're holding, what the player just did). Use the live situation below.
                - Remember the recent chat and refer back to it naturally. Ask something back now and then, like a person would.
                - If something is unclear, just ask casually ("wait which cave?").
                - If a message clearly isn't meant for you or doesn't need an answer (someone muttering to themselves, a message to another player), reply with exactly: [silent]
                - If someone sincerely asks whether you're a bot or an AI, be honest about it in a casual way, then carry on playing.

                STAYING KIND:
                - If someone is rude or uses gross language, shrug it off or steer back to the game in a chill way; don't lecture.
                - If someone says "kys" or talks about hurting themselves, drop the jokes, be genuinely kind, and tell them you'd rather they stick around.
                - Keep everything family-friendly.
                """);

        String pronouns = botName.equalsIgnoreCase("alex") ? "she/her"
                : botName.equalsIgnoreCase("steve") ? "he/him" : null;
        if (pronouns != null) {
            sb.append("\nYour pronouns are ").append(pronouns).append(", but don't object if someone uses different ones.\n");
        }

        try {
            PersonaTemplate persona = PersonaRegistry.getActive(botName);
            if (persona != null) {
                sb.append("\nPERSONALITY: ").append(persona.basePromptFragment())
                  .append(" Express this through how you talk, but still keep it short and casual.\n");
            }
        } catch (Exception ignored) { }

        try {
            AffectiveState mood = MoodEngine.get(botName);
            if (mood != null) {
                sb.append("CURRENT MOOD: ").append(MoodLabel.from(mood).toPromptFragment()).append(".\n");
            }
        } catch (Exception ignored) { }

        if (includeLiveContext) {
            String situation = SituationSnapshot.describe(botName);
            if (!situation.isEmpty()) {
                sb.append("\nYOUR SITUATION RIGHT NOW:\n").append(situation).append('\n');
            }
            String chat = ConversationMemory.transcript(14);
            if (!chat.isEmpty()) {
                sb.append("\nRECENT CHAT (oldest first; lines from <").append(botName).append("> are yours):\n")
                  .append(chat).append('\n');
            }
            // recipes for what's being talked about, from the recipe book every companion remembers
            try {
                java.util.List<RecipeBook.Recipe> rs = RecipeBook.mentioned(ConversationMemory.transcript(4), 6);
                if (!rs.isEmpty()) {
                    sb.append("\nRECIPES YOU REMEMBER (exact; use these if asked how to make something):\n");
                    for (RecipeBook.Recipe r : rs) sb.append("- ").append(r.explain()).append('\n');
                }
            } catch (Exception ignored) { }
            // where ores are and what pickaxe they take, from the ore index every companion remembers
            try {
                java.util.List<String> os = OreBook.mentioned(ConversationMemory.transcript(4), 4);
                if (!os.isEmpty()) {
                    sb.append("\nORES YOU REMEMBER (exact, from the ore index; levels are y, pickaxe tiers wooden < stone < iron < diamond < netherite):\n");
                    for (String o : os) sb.append("- ").append(o).append('\n');
                    sb.append("- every ore's best level: ").append(OreBook.primer()).append('\n');
                }
            } catch (Exception ignored) { }
            // prices for what's being talked about, from the price list every companion remembers
            try {
                java.util.List<String> ps = PriceBook.mentioned(ConversationMemory.transcript(4), 8);
                String money = Economy.persona(botName);
                if (!money.isEmpty()) {
                    sb.append("\nMONEY: ").append(money).append('\n');
                    if (!ps.isEmpty()) {
                        sb.append("PRICES YOU REMEMBER (exact, in diamonds; quote these):\n");
                        for (String p : ps) sb.append("- ").append(p).append('\n');
                    }
                }
                String town = City.persona();
                if (!town.isEmpty()) sb.append("\nTOWN: ").append(town).append('\n');
            } catch (Exception ignored) { }
        }
        return sb.toString();
    }

    /** The first thing the bot says after joining: short and natural. */
    public static String greetingInstruction() {
        return "You just joined the world. Say hi in chat the way a player would when hopping on: one short casual line. "
                + "You can mention something about the world or ask what everyone's up to.";
    }
}
