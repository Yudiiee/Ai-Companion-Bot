package io.github.yudiiee.aicompanion.GameAI;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StateTransition tracks transitions between states for learning from experience.
 * This class enables the bot to look ahead and learn from past state sequences,
 * especially those that led to death or critical outcomes.
 */
public class StateTransition implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final State fromState;
    private final State toState;
    private final StateActions.Action action;
    private final double reward;
    private final double podValue;
    private final long timestamp;
    private final boolean ledToDeath;
    private final int stepsUntilDeath; // -1 if didn't lead to death

    public StateTransition(State fromState, State toState, StateActions.Action action,
                           double reward, double podValue, boolean ledToDeath, int stepsUntilDeath) {
        this.fromState = fromState;
        this.toState = toState;
        this.action = action;
        this.reward = reward;
        this.podValue = podValue;
        this.timestamp = System.currentTimeMillis();
        this.ledToDeath = ledToDeath;
        this.stepsUntilDeath = stepsUntilDeath;
    }

    public State getFromState() { return fromState; }
    public State getToState() { return toState; }
    public StateActions.Action getAction() { return action; }
    public double getReward() { return reward; }
    public double getPodValue() { return podValue; }
    public long getTimestamp() { return timestamp; }
    public boolean ledToDeath() { return ledToDeath; }
    public int getStepsUntilDeath() { return stepsUntilDeath; }

    /**
     * History tracker for state transitions
     */
    public static class TransitionHistory implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private final LinkedList<StateTransition> transitionChain = new LinkedList<>();
        private final int maxHistorySize;
        private final Map<String, List<StateTransition>> deathPatternCache = new ConcurrentHashMap<>();

        public TransitionHistory(int maxHistorySize) {
            this.maxHistorySize = maxHistorySize;
        }

        /**
         * Add a transition to the history
         */
        public void addTransition(StateTransition transition) {
            if (transitionChain.size() >= maxHistorySize) {
                transitionChain.removeFirst();
            }
            transitionChain.addLast(transition);
        }

        /**
         * Mark the last N transitions as leading to death and cache the pattern
         */
        public void markDeathSequence(int stepsBeforeDeath) {
            if (transitionChain.isEmpty()) return;

            int actualSteps = Math.min(stepsBeforeDeath, transitionChain.size());
            List<StateTransition> deathSequence = new ArrayList<>();

            // Mark transitions leading to death
            for (int i = transitionChain.size() - actualSteps; i < transitionChain.size(); i++) {
                StateTransition original = transitionChain.get(i);
                int stepsUntilDeath = transitionChain.size() - i;

                StateTransition markedTransition = new StateTransition(
                        original.getFromState(),
                        original.getToState(),
                        original.getAction(),
                        original.getReward(),
                        original.getPodValue(),
                        true,
                        stepsUntilDeath
                );

                transitionChain.set(i, markedTransition);
                deathSequence.add(markedTransition);
            }

            // Cache the death pattern for quick lookup
            String patternKey = generatePatternKey(deathSequence);
            deathPatternCache.put(patternKey, new ArrayList<>(deathSequence));
            System.out.println("📊 Death sequence cached: " + actualSteps + " steps, pattern key: " + patternKey);
        }

        /**
         * Generate a simplified key for similar death patterns
         */
        private String generatePatternKey(List<StateTransition> sequence) {
            StringBuilder key = new StringBuilder();
            for (StateTransition t : sequence) {
                State s = t.getFromState();
                key.append(String.format("H%d_E%d_A%s_P%.1f|",
                        s.getBotHealth() / 5, // Bucket health by 5
                        s.getNearbyEntities().stream().filter(e -> e.isHostile()).count(),
                        t.getAction().name().substring(0, Math.min(3, t.getAction().name().length())),
                        t.getPodValue()
                ));
            }
            return key.toString();
        }

        /**
         * Get recent transitions (for analysis)
         */
        public List<StateTransition> getRecentTransitions(int count) {
            int startIdx = Math.max(0, transitionChain.size() - count);
            return new ArrayList<>(transitionChain.subList(startIdx, transitionChain.size()));
        }

        /**
         * Check if current situation matches a known death pattern
         */
        public boolean matchesDeathPattern(State currentState, StateActions.Action proposedAction) {
            if (deathPatternCache.isEmpty()) return false;

            // Quick check: compare current state to cached death patterns
            for (List<StateTransition> deathPattern : deathPatternCache.values()) {
                if (deathPattern.isEmpty()) continue;

                StateTransition firstStep = deathPattern.get(0);

                if (State.isStateConsistent(firstStep.getFromState(), currentState) &&
                        firstStep.getAction() == proposedAction) {
                    System.out.println("⚠ Warning: Current action matches a known death pattern!");
                    return true;
                }
            }
            return false;
        }

        /**
         * Get all transitions that led to death
         */
        public List<StateTransition> getDeathTransitions() {
            return transitionChain.stream()
                    .filter(StateTransition::ledToDeath)
                    .toList();
        }

        /**
         * Clear old transitions (memory management)
         */
        public void clearOldTransitions(long maxAgeMs) {
            long cutoffTime = System.currentTimeMillis() - maxAgeMs;
            transitionChain.removeIf(t -> t.getTimestamp() < cutoffTime);
        }

        public List<StateTransition> getAllTransitions() {
            return new ArrayList<>(transitionChain);
        }

        public void clear() {
            transitionChain.clear();
            deathPatternCache.clear();
        }
    }

    @Override
    public String toString() {
        return String.format("Transition[%s -> %s via %s, reward=%.2f, pod=%.2f, death=%s, steps=%d]",
                fromState.getBotHealth() + "HP",
                toState.getBotHealth() + "HP",
                action.name(),
                reward,
                podValue,
                ledToDeath,
                stepsUntilDeath
        );
    }
}
