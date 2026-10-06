package io.github.yudiiee.aicompanion;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Carries settings over from the mod's earlier name: config files and folders (LLM settings,
 * downloaded language models, companion settings) and each world's saved bot data are moved to
 * their new names the first time AI Companion starts. Nothing is overwritten.
 */
public final class DataMigration {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-companion-migration");
    // (built from parts so the old names are easy to tell apart from the new ones)
    private static final String OLD_DASH = "ai-" + "player", OLD_UNDER = "ai_" + "player";
    private static final String NEW_DASH = "ai-companion", NEW_UNDER = "ai_companion";

    private DataMigration() {}

    static String renamed(String name) {
        return name.replace(OLD_DASH, NEW_DASH).replace(OLD_UNDER, NEW_UNDER);
    }

    /** Config folder entries with the old name get the new one (once, at startup). */
    public static void run() {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir();
            if (!Files.isDirectory(dir)) return;
            List<Path> old = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.contains(OLD_DASH) || n.contains(OLD_UNDER);
                }).forEach(old::add);
            }
            for (Path p : old) move(p, p.resolveSibling(renamed(p.getFileName().toString())));
        } catch (Throwable t) {
            LOGGER.warn("[migration] couldn't move old config files: {}", t.toString());
        }
    }

    /** A world's saved bot data folder ({@code <world>/<old name>}) moves to {@code newDir} if that doesn't exist yet. */
    public static void worldFolder(Path newDir) {
        try {
            Path old = newDir.resolveSibling(OLD_DASH);
            if (Files.isDirectory(old) && !Files.exists(newDir)) move(old, newDir);
        } catch (Throwable t) {
            LOGGER.warn("[migration] couldn't move old world data: {}", t.toString());
        }
    }

    private static void move(Path from, Path to) {
        if (from.equals(to) || Files.exists(to)) return;
        try {
            Files.move(from, to);
            LOGGER.info("[migration] {} -> {}", from.getFileName(), to.getFileName());
        } catch (Exception e) {
            LOGGER.warn("[migration] couldn't move {}: {}", from, e.toString());
        }
    }
}
