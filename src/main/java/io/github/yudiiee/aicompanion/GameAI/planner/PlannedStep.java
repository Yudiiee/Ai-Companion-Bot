package io.github.yudiiee.aicompanion.GameAI.planner;

import java.io.Serializable;

/**
 * Represents a single planned action step.
 */
public class PlannedStep implements Serializable {
    private static final long serialVersionUID = 1L;

    public final byte actionId;        // Compact action identifier (0-39)
    public final String actionName;    // Human-readable action name
    public double estimatedRisk;       // Risk estimate from SequenceRiskAnalyzer
    public String params;              // Optional parameters (JSON or simple string)

    public PlannedStep(byte actionId, String actionName, double estimatedRisk, String params) {
        this.actionId = actionId;
        this.actionName = actionName;
        this.estimatedRisk = estimatedRisk;
        this.params = params;
    }

    /**
     * Create step without parameters.
     */
    public PlannedStep(byte actionId, String actionName) {
        this(actionId, actionName, 0.0, null);
    }

    /**
     * Check if this is a movement action.
     */
    public boolean isMovement() {
        int id = actionId & 0xFF;
        return id >= 1 && id <= 7;
    }

    /**
     * Check if this is a combat action.
     */
    public boolean isCombat() {
        int id = actionId & 0xFF;
        return id >= 10 && id <= 13;
    }

    /**
     * Check if this is a utility action.
     */
    public boolean isUtility() {
        int id = actionId & 0xFF;
        return id >= 20 && id <= 25;
    }

    /**
     * Check if this is a hotbar action.
     */
    public boolean isHotbarSwitch() {
        int id = actionId & 0xFF;
        return id >= 31 && id <= 39;
    }

    /**
     * Get risk level category.
     */
    public String getRiskLevel() {
        if (estimatedRisk < 5.0) return "LOW";
        if (estimatedRisk < 15.0) return "MEDIUM";
        if (estimatedRisk < 30.0) return "HIGH";
        return "CRITICAL";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(actionName);

        if (params != null && !params.isEmpty()) {
            sb.append("(").append(params).append(")");
        }

        sb.append(" [risk: ").append(String.format("%.1f", estimatedRisk)).append("]");

        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlannedStep)) return false;

        PlannedStep that = (PlannedStep) o;
        return actionId == that.actionId &&
               actionName.equals(that.actionName);
    }

    @Override
    public int hashCode() {
        return 31 * actionId + actionName.hashCode();
    }
}

