package io.github.yudiiee.aicompanion.GameAI.human;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings for the "feels like a real player" layer.
 *
 * <p>Stored in {@code config/ai-companion-humanlike.json}. Every option can also be
 * flipped in-game with {@code /humanlike <option> <on|off>}.
 */
public final class HumanConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-humanlike");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Data data = new Data();

    /** Plain data holder serialised to JSON. */
    public static final class Data {
        /** Bot chat looks like normal player chat ({@code <Name> hi}) instead of /say. */
        public boolean playerStyleChat = true;
        /** Wait a realistic "reading + typing" time before each message appears. */
        public boolean typingDelay = true;
        /** Reply to chat that isn't addressed by name when the bot is obviously the one being talked to. */
        public boolean answerWithoutName = true;
        /** Play on its own when not told to do something: chop trees, craft tools, mine, explore. */
        public boolean autoPlay = true;
        /** Look at people, glance around, crouch back when crouched at, etc. */
        public boolean bodyLanguage = true;
        /** Quick casual reactions to deaths, advancements, joins, getting hit... */
        public boolean reactions = true;
        /** Occasional unprompted small talk when a player is nearby and it's quiet. */
        public boolean idleChatter = true;
        /** Minimum minutes between unprompted remarks. */
        public int idleChatterMinMinutes = 4;
        /** Hide technical status lines ("Running web search....") from public chat. */
        public boolean hideRobotStatusLines = true;
        /** Typing speed used for the delay, in characters per second. */
        public double typingCharsPerSecond = 11.0;
        /** Put mined stuff away in the storage chest after a job and when its inventory fills up. */
        public boolean autoStore = true;
        /** Builds itself a base (a small house with a chest) and lives there: loot goes home, it goes home at night. */
        public boolean autoHome = true;
    }

    private HumanConfig() {}

    public static Data get() {
        return data;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ai-companion-humanlike.json");
    }

    public static synchronized void load() {
        Path p = path();
        try {
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p)) {
                    Data loaded = GSON.fromJson(r, Data.class);
                    if (loaded != null) data = loaded;
                }
            }
            save(); // writes defaults for any options that were missing
            LOGGER.info("[humanlike] Loaded settings from {}", p);
        } catch (Exception e) {
            LOGGER.warn("[humanlike] Could not read {} — using defaults ({})", p, e.getMessage());
        }
    }

    public static synchronized void save() {
        Path p = path();
        try {
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p)) {
                GSON.toJson(data, w);
            }
        } catch (Exception e) {
            LOGGER.warn("[humanlike] Could not save {}: {}", p, e.getMessage());
        }
    }
}
