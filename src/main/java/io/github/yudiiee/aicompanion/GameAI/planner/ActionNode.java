package io.github.yudiiee.aicompanion.GameAI.planner;

import java.util.*;

/**
 * Represents an action in the action graph.
 * Each node has:
 * - Semantic embedding (for similarity matching)
 * - Preconditions (what must be true to execute)
 * - Effects (what becomes true after execution)
 * - Base risk/cost estimates
 */
public class ActionNode {
    private final byte actionId;
    private final String actionName;
    private final float[] embedding;
    private final Set<String> preconditions;
    private final Set<String> effects;
    private final double baseRisk;
    private final double estimatedTimeCost; // in seconds
    private final Map<ActionNode, Double> neighbors; // Edges to other actions

    @SuppressWarnings("unused")
    public ActionNode(byte actionId, String actionName, float[] embedding) {
        this.actionId = actionId;
        this.actionName = actionName;
        this.embedding = embedding;
        this.preconditions = new HashSet<>();
        this.effects = new HashSet<>();
        this.neighbors = new HashMap<>();

        // Default values
        this.baseRisk = 10.0;
        this.estimatedTimeCost = 1.0;
    }

    public ActionNode(byte actionId, String actionName, float[] embedding,
                     Set<String> preconditions, Set<String> effects,
                     double baseRisk, double timeCost) {
        this.actionId = actionId;
        this.actionName = actionName;
        this.embedding = embedding;
        this.preconditions = new HashSet<>(preconditions);
        this.effects = new HashSet<>(effects);
        this.neighbors = new HashMap<>();
        this.baseRisk = baseRisk;
        this.estimatedTimeCost = timeCost;
    }

    // Getters
    public byte getActionId() {
        return actionId;
    }

    public String getActionName() {
        return actionName;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public double getBaseRisk() {
        return baseRisk;
    }

    public double getEstimatedTimeCost() {
        return estimatedTimeCost;
    }

    public Set<ActionNode> getNeighbors() {
        return neighbors.keySet();
    }

    public double getEdgeWeight(ActionNode neighbor) {
        return neighbors.getOrDefault(neighbor, Double.MAX_VALUE);
    }

    // Graph operations
    public void addNeighbor(ActionNode neighbor, double weight) {
        neighbors.put(neighbor, weight);
    }

    public boolean preconditionsSatisfied(Set<String> currentConditions) {
        return currentConditions.containsAll(preconditions);
    }

    public Set<String> getEffects() {
        return new HashSet<>(effects);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ActionNode that = (ActionNode) o;
        return actionId == that.actionId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(actionId);
    }

    @Override
    public String toString() {
        return "ActionNode{" +
                "id=" + actionId +
                ", name='" + actionName + '\'' +
                ", risk=" + baseRisk +
                ", time=" + estimatedTimeCost +
                '}';
    }
}

