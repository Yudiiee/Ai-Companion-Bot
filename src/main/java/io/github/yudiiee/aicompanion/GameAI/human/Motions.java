package io.github.yudiiee.aicompanion.GameAI.human;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import java.lang.reflect.Method;

/**
 * Small visual actions whose Minecraft signatures change between versions.
 * Looked up reflectively so a signature change can never crash the server
 * (26.3 changed {@code swing(hand)} to {@code swing(hand, SwingAnimation, boolean)}).
 */
public final class Motions {
    private static volatile boolean resolved;
    private static Method swing3;
    private static Object swingDefault;
    private static Method swing1;

    private Motions() {}

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> anim = Class.forName("net.minecraft.world.item.component.SwingAnimation");
            swingDefault = anim.getField("DEFAULT").get(null);
            swing3 = findMethod(ServerPlayer.class, "swing", InteractionHand.class, anim, boolean.class);
        } catch (Throwable ignored) { }
        if (swing3 == null) {
            try { swing1 = findMethod(ServerPlayer.class, "swing", InteractionHand.class); } catch (Throwable ignored) { }
        }
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... params) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    /** Swing the main arm (crafting, fidgeting). Silently does nothing if unsupported. */
    public static void swingArm(ServerPlayer bot) {
        resolve();
        try {
            if (swing3 != null) swing3.invoke(bot, InteractionHand.MAIN_HAND, swingDefault, true);
            else if (swing1 != null) swing1.invoke(bot, InteractionHand.MAIN_HAND);
        } catch (Throwable ignored) { }
    }
}
