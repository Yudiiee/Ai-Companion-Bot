package io.github.yudiiee.aicompanion.GameAI;

import io.github.yudiiee.aicompanion.Database.QTable;
import io.github.yudiiee.aicompanion.Database.StateActionPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * LookaheadLearning enables the bot to learn from state transitions and past experiences.
 * It analyzes sequences of actions that led to death or other critical outcomes
 * and updates Q-values retroactively to avoid similar mistakes.
 */
public class LookaheadLearning {
    private static final Logger LOGGER = LoggerFactory.getLogger("lookahead-learning");

    private static final double LOOKAHEAD_DISCOUNT = 0.8; // Discount for propagating negative rewards backward
    private static final double DEATH_PENALTY_BASE = -100.0; // Base penalty for death
    private static final int MAX_LOOKAHEAD_STEPS = 10; // How many steps to look back

    // Parallel processing pool (synchronous but parallel)
    private static final ForkJoinPool parallelPool =
        new ForkJoinPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));

    /**
     * Analyze a death sequence and update Q-values retroactively
     * This runs in parallel but synchronously returns results
     */
    public static void learnFromDeath(StateTransition.TransitionHistory history,
                                     QTable qTable,
                                     RLAgent rlAgent) {
        List<StateTransition> deathSequence = history.getRecentTransitions(MAX_LOOKAHEAD_STEPS);

        if (deathSequence.isEmpty()) {
            LOGGER.warn("No transitions to learn from");
            return;
        }

        LOGGER.info("🔍 Analyzing death sequence: {} transitions", deathSequence.size());

        // Mark the death sequence
        history.markDeathSequence(deathSequence.size());

        // Process transitions in parallel (but wait for completion before returning)
        try {
            parallelPool.submit(() -> {
                // Parallel stream for synchronous parallel processing
                deathSequence.parallelStream().forEach(transition -> {
                    analyzeAndUpdateTransition(transition, qTable, rlAgent);
                });
            }).get(); // Wait for completion (synchronous)

            LOGGER.info("✓ Death sequence analysis complete");
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Error during lookahead learning", e);
        }
    }

    /**
     * Analyze a single transition and update its Q-value based on lookahead
     */
    private static void analyzeAndUpdateTransition(StateTransition transition,
                                                   QTable qTable,
                                                   RLAgent rlAgent) {
        State fromState = transition.getFromState();
        StateActions.Action action = transition.getAction();
        double originalReward = transition.getReward();
        double podValue = transition.getPodValue();
        int stepsUntilDeath = transition.getStepsUntilDeath();

        // Calculate death penalty proportional to proximity to death
        double deathPenalty = 0;
        if (transition.ledToDeath() && stepsUntilDeath > 0) {
            // Earlier mistakes get less penalty, immediate mistakes get full penalty
            double decayFactor = Math.pow(LOOKAHEAD_DISCOUNT, stepsUntilDeath - 1);
            deathPenalty = DEATH_PENALTY_BASE * decayFactor;

            LOGGER.info("  Step -{}: {} | Penalty: {}",
                stepsUntilDeath, action.name(), String.format("%.1f", deathPenalty));
        }

        // Calculate adjusted reward
        double adjustedReward = originalReward + deathPenalty;

        // Add penalty based on PoD (Probability of Death)
        if (podValue > 0.5) {
            adjustedReward -= (podValue * 20); // High PoD = additional penalty
        }

        // Update Q-value with adjusted reward
        StateActionPair pair = new StateActionPair(fromState, action);
        double currentQValue = qTable.getEntry(pair) != null ?
            qTable.getEntry(pair).getQValue() : 0.0;

        // Use exponential moving average to update Q-value gradually
        double learningRate = 0.3; // Higher learning rate for death experiences
        double newQValue = currentQValue + learningRate * (adjustedReward - currentQValue);

        // Update the Q-table
        qTable.addEntry(fromState, action, newQValue, transition.getToState());

        if (transition.ledToDeath()) {
            LOGGER.debug("    Updated Q[{}, {}]: {} -> {} (penalty: {})",
                fromState.getBotHealth(), action.name(),
                String.format("%.2f", currentQValue),
                String.format("%.2f", newQValue),
                String.format("%.2f", deathPenalty));
        }
    }

    /**
     * Analyze current state against known death patterns
     * Returns additional risk penalty if patterns match
     */
    public static double analyzeDeathRisk(State currentState,
                                         StateActions.Action proposedAction,
                                         StateTransition.TransitionHistory history) {
        if (history.matchesDeathPattern(currentState, proposedAction)) {
            LOGGER.warn("⚠ Action {} matches known death pattern! Adding risk penalty.", proposedAction);
            return 50.0; // High risk penalty
        }

        // Analyze similar past failures in parallel
        List<StateTransition> deathTransitions = history.getDeathTransitions();
        if (deathTransitions.isEmpty()) {
            return 0.0;
        }

        try {
            // Parallel similarity analysis
            Double totalRisk = parallelPool.submit(() ->
                deathTransitions.parallelStream()
                    .filter(dt -> dt.getAction() == proposedAction)
                    .map(dt -> calculateStateSimilarity(currentState, dt.getFromState()))
                    .filter(similarity -> similarity > 0.7) // Only consider high similarity
                    .mapToDouble(similarity -> similarity * 30.0) // Risk penalty proportional to similarity
                    .sum()
            ).get();

            if (totalRisk > 0) {
                LOGGER.info("  📊 Similar death pattern detected, risk penalty: {}",
                    String.format("%.1f", totalRisk));
            }

            return totalRisk;
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Error analyzing death risk", e);
            return 0.0;
        }
    }

    /**
     * Calculate similarity between two states (0.0 to 1.0)
     */
    private static double calculateStateSimilarity(State s1, State s2) {
        double similarity = 0.0;
        int factors = 0;

        // Health similarity (most important)
        double healthSim = 1.0 - Math.abs(s1.getBotHealth() - s2.getBotHealth()) / 20.0;
        similarity += healthSim * 3; // Weight 3x
        factors += 3;

        // Hostile entity count similarity
        long hostiles1 = s1.getNearbyEntities().stream().filter(e -> e.isHostile()).count();
        long hostiles2 = s2.getNearbyEntities().stream().filter(e -> e.isHostile()).count();
        double hostileSim = 1.0 - Math.abs(hostiles1 - hostiles2) / 10.0;
        similarity += Math.max(0, hostileSim) * 2; // Weight 2x
        factors += 2;

        // Distance similarity (spatial context)
        double distance = Math.sqrt(
            Math.pow(s1.getBotX() - s2.getBotX(), 2) +
            Math.pow(s1.getBotY() - s2.getBotY(), 2) +
            Math.pow(s1.getBotZ() - s2.getBotZ(), 2)
        );
        double distanceSim = 1.0 - Math.min(1.0, distance / 50.0);
        similarity += distanceSim;
        factors += 1;

        return similarity / factors;
    }

    /**
     * Perform periodic reflection on past experiences
     * This consolidates learning from multiple state sequences
     */
    public static void periodicReflection(StateTransition.TransitionHistory history,
                                         QTable qTable,
                                         RLAgent rlAgent) {
        LOGGER.info("🧠 Starting periodic reflection on past experiences...");

        List<StateTransition> allTransitions = history.getAllTransitions();
        if (allTransitions.isEmpty()) {
            LOGGER.info("No transitions to reflect on");
            return;
        }

        try {
            // Group transitions by outcome in parallel
            Map<Boolean, List<StateTransition>> groupedByOutcome = parallelPool.submit(() ->
                allTransitions.parallelStream()
                    .collect(Collectors.groupingByConcurrent(StateTransition::ledToDeath))
            ).get();

            List<StateTransition> deathTransitions = groupedByOutcome.getOrDefault(true, List.of());
            List<StateTransition> survivalTransitions = groupedByOutcome.getOrDefault(false, List.of());

            LOGGER.info("  Deaths: {}, Survivals: {}", deathTransitions.size(), survivalTransitions.size());

            // Analyze patterns in parallel
            parallelPool.submit(() -> {
                // Reinforce good patterns (survival with high PoD situations)
                survivalTransitions.parallelStream()
                    .filter(t -> t.getPodValue() > 0.6 && t.getToState().getBotHealth() >= t.getFromState().getBotHealth())
                    .forEach(t -> reinforceGoodDecision(t, qTable, rlAgent));

                // Further penalize bad patterns
                deathTransitions.parallelStream()
                    .filter(t -> t.getStepsUntilDeath() <= 3) // Focus on immediate causes
                    .forEach(t -> reinforceBadDecision(t, qTable, rlAgent));
            }).get();

            LOGGER.info("✓ Periodic reflection complete");

        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Error during periodic reflection", e);
        }
    }

    /**
     * Reinforce a good decision that led to survival in high-risk situation
     */
    private static void reinforceGoodDecision(StateTransition transition,
                                             QTable qTable,
                                             RLAgent rlAgent) {
        StateActionPair pair = new StateActionPair(transition.getFromState(), transition.getAction());
        double currentQ = qTable.getEntry(pair) != null ? qTable.getEntry(pair).getQValue() : 0.0;

        // Bonus for surviving dangerous situation
        double bonus = 20.0 * transition.getPodValue();
        double newQ = currentQ + 0.1 * bonus; // Small incremental boost

        qTable.addEntry(transition.getFromState(), transition.getAction(), newQ, transition.getToState());

        LOGGER.debug("  ✓ Reinforced survival: {} (bonus: +{})",
            transition.getAction().name(), String.format("%.1f", bonus));
    }

    /**
     * Further penalize a decision that led to death
     */
    private static void reinforceBadDecision(StateTransition transition,
                                            QTable qTable,
                                            RLAgent rlAgent) {
        StateActionPair pair = new StateActionPair(transition.getFromState(), transition.getAction());
        double currentQ = qTable.getEntry(pair) != null ? qTable.getEntry(pair).getQValue() : 0.0;

        // Additional penalty for critical mistakes
        double penalty = -30.0 / Math.max(1, transition.getStepsUntilDeath());
        double newQ = currentQ + 0.1 * penalty;

        qTable.addEntry(transition.getFromState(), transition.getAction(), newQ, transition.getToState());

        LOGGER.debug("  ✗ Reinforced avoidance: {} (penalty: {})",
            transition.getAction().name(), String.format("%.1f", penalty));
    }

    /**
     * Clean up old transitions to manage memory
     */
    public static void cleanupOldTransitions(StateTransition.TransitionHistory history) {
        long maxAge = TimeUnit.MINUTES.toMillis(30); // Keep last 30 minutes
        history.clearOldTransitions(maxAge);
        LOGGER.debug("Cleaned up old transitions (>30min)");
    } // Added missing brace for method
}

