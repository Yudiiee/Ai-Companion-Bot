package io.github.yudiiee.aicompanion.GameAI.planner;

/**
 * Information about a single action type.
 */
public class ActionInfo {
    public final byte id;
    public final String name;
    public final String description;
    public final String[] paramNames;
    public final double estimatedTime; // seconds
    public final boolean isTerminal; // Ends the plan if successful
    public final int priority; // Higher = more important

    public ActionInfo(byte id, String name, String description,
                     String[] paramNames, double estimatedTime,
                     boolean isTerminal, int priority) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.paramNames = paramNames;
        this.estimatedTime = estimatedTime;
        this.isTerminal = isTerminal;
        this.priority = priority;
    }

    /**
     * Get default parameters (empty strings).
     */
    public String[] getDefaultParams() {
        String[] defaults = new String[paramNames.length];
        for (int i = 0; i < defaults.length; i++) {
            defaults[i] = "";
        }
        return defaults;
    }

    @Override
    public String toString() {
        return String.format("Action[%d:%s, time:%.1fs, priority:%d]",
            id & 0xFF, name, estimatedTime, priority);
    }
}

