package io.github.yudiiee.aicompanion.GameAI.human;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread factory for the mod's background pools. Daemon threads never keep the
 * game process alive after you quit, which previously made the client shutdown
 * watchdog kill the game with a crash report.
 */
public final class DaemonThreads {
    private DaemonThreads() {}

    public static ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
