package io.github.yudiiee.aicompanion.GameAI.human;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * Writes {@code logs/ai-companion-diagnostics.log} straight to disk (not through the game's
 * logger, which can be gone or not flushed when Java dies): a heartbeat every 15 s with
 * memory use and what each bot is doing, anything that kills a thread, and the shutdown
 * itself. When the game closes with no crash report, this file shows what was happening.
 */
public final class CrashDiagnostics {

    private static volatile Path file;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private CrashDiagnostics() {}

    public static synchronized void start() {
        if (file != null) return;
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("logs");
            Files.createDirectories(dir);
            file = dir.resolve("ai-companion-diagnostics.log");
            Files.writeString(file, "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Throwable t) {
            file = null;
            return;
        }
        write("session start: java " + System.getProperty("java.version") + ", max heap " + mb(Runtime.getRuntime().maxMemory())
                + " MB, " + physical());

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            write("UNCAUGHT in thread '" + thread.getName() + "': " + error + stack(error.getStackTrace()));
            if (previous != null) previous.uncaughtException(thread, error);
            else error.printStackTrace();
        });

        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                StringBuilder sb = new StringBuilder("shutdown hook ran (Java is exiting normally, e.g. System.exit or window closed). ");
                sb.append(memory());
                for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                    boolean exiting = false;
                    for (StackTraceElement el : e.getValue()) {
                        String c = el.getClassName();
                        if (c.equals("java.lang.Shutdown") || c.equals("java.lang.Runtime") || c.equals("java.lang.System")) exiting = true;
                    }
                    if (exiting) sb.append("\n  exit called from thread '").append(e.getKey().getName()).append("':").append(stack(e.getValue()));
                }
                write(sb.toString());
            }, "ai-companion-diagnostics-shutdown"));
        } catch (Throwable ignored) { }

        Thread beat = new Thread(() -> {
            while (true) {
                try { Thread.sleep(15_000); } catch (InterruptedException e) { return; }
                try { write("heartbeat: " + memory() + " | " + bots()); } catch (Throwable ignored) { }
            }
        }, "ai-companion-diagnostics");
        beat.setDaemon(true);
        beat.start();
    }

    /** Appends a line (thread-safe, flushed immediately). */
    public static synchronized void write(String line) {
        Path f = file;
        if (f == null) return;
        try {
            Files.writeString(f, "[" + LocalTime.now().format(TIME) + "] " + line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            if (Files.size(f) > 4_000_000L) { // keep it small
                Files.writeString(f, "[" + LocalTime.now().format(TIME) + "] (trimmed)" + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }
        } catch (Throwable ignored) { }
    }

    static String stack(StackTraceElement[] st) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(60, st.length); i++) sb.append("\n    at ").append(st[i]);
        return sb.toString();
    }

    private static long mb(long bytes) { return bytes / (1024 * 1024); }

    private static String memory() {
        Runtime rt = Runtime.getRuntime();
        return "heap used " + mb(rt.totalMemory() - rt.freeMemory()) + " / committed " + mb(rt.totalMemory())
                + " / max " + mb(rt.maxMemory()) + " MB; " + physical();
    }

    private static String physical() {
        try {
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                return "RAM free " + mb(sun.getFreeMemorySize()) + " of " + mb(sun.getTotalMemorySize())
                        + " MB, swap free " + mb(sun.getFreeSwapSpaceSize()) + " of " + mb(sun.getTotalSwapSpaceSize()) + " MB";
            }
        } catch (Throwable ignored) { }
        return "RAM unknown";
    }

    private static String bots() {
        MinecraftServer server = io.github.yudiiee.aicompanion.AICompanion.serverInstance;
        if (server == null || !server.isRunning()) return "no world";
        StringBuilder sb = new StringBuilder();
        try {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!HumanBehavior.isAiBot(p)) continue;
                UUID id = p.getUUID();
                sb.append(p.getName().getString()).append(" at ").append(p.blockPosition().getX()).append(',')
                        .append(p.blockPosition().getY()).append(',').append(p.blockPosition().getZ())
                        .append(" job=").append(SurvivalBrain.jobName(p))
                        .append(BotPathing.isActive(id) ? " pathing" : "")
                        .append(io.github.yudiiee.aicompanion.PlayerUtils.MiningTool.isMining(id) ? " mining" : "")
                        .append(PvpController.isFighting(id) ? " fighting" : "").append("; ");
            }
        } catch (Throwable t) {
            sb.append("(busy)");
        }
        return sb.length() == 0 ? "no bots" : sb.toString();
    }
}
