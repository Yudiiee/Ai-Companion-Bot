package io.github.yudiiee.aicompanion.GameAI.planner;

import io.github.yudiiee.aicompanion.GameAI.RLAgent;
import io.github.yudiiee.aicompanion.GameAI.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Analyzes risk of action sequences using RLAgent's risk estimation
 * combined with cheap forward simulation.
 */
public class SequenceRiskAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger("planner");

    // Risk weights (tunable hyperparameters)
    private static final double W_DEATH_RISK = 50.0;
    private static final double W_DAMAGE = 5.0;
    private static final double W_TIME_COST = 0.1;
    private static final double W_Q_BONUS = -10.0; // Negative = bonus
    private static final double W_GOAL_PROGRESS = -20.0; // Reward for progress

    private final RLAgent rlAgent;

    public SequenceRiskAnalyzer(RLAgent rlAgent) {
        this.rlAgent = rlAgent;
    }

    /**
     * Score an action sequence (lower is better for safety).
     *
     * @param plan Action sequence to evaluate
     * @param initialState Starting state
     * @param goalId Target goal
     * @return Risk score (lower = safer/better)
     */
    public double scorePlan(List<PlannedStep> plan, State initialState, short goalId) {
        if (plan == null || plan.isEmpty()) {
            return Double.MAX_VALUE; // Invalid plan
        }

        double totalScore = 0.0;
        double accumulatedDeathRisk = 0.0;
        double accumulatedDamage = 0.0;

        // Initialize fake state for simulation
        CheapForward.FakeState fakeState = CheapForward.initFromState(initialState);
        State currentState = initialState;

        // Evaluate each step
        for (int i = 0; i < plan.size(); i++) {
            PlannedStep step = plan.get(i);

            // Get risk estimate from RL agent
            RLAgent.RiskEstimate riskEst = rlAgent.estimateRisk(currentState, step.actionName);

            // Accumulate death risk (exponential penalty)
            accumulatedDeathRisk += riskEst.deathProbability;
            accumulatedDamage += riskEst.expectedDamage;

            // Get Q-value bonus (if action has been learned as good)
            double qBonus = rlAgent.getQValue(currentState, step.actionName);

            // Apply action to cheap forward simulator
            CheapForward.apply(fakeState, step);

            // Store estimated risk in step
            step.estimatedRisk = riskEst.totalRisk;

            // Check for death risk spike (early termination)
            if (accumulatedDeathRisk > 0.8) {
                LOGGER.debug("Plan has high death risk ({}), penalizing heavily", accumulatedDeathRisk);
                return 1000.0 + accumulatedDeathRisk * 500.0;
            }

            // Check for excessive time cost
            if (fakeState.timeCost > 500) {
                LOGGER.debug("Plan too slow (time: {}), penalizing", fakeState.timeCost);
                totalScore += 100.0;
            }
        }

        // Compute goal progress bonus
        double goalProgress = CheapForward.goalProgress(fakeState, goalId);

        // Final score calculation
        totalScore += W_DEATH_RISK * accumulatedDeathRisk;
        totalScore += W_DAMAGE * accumulatedDamage;
        totalScore += W_TIME_COST * fakeState.timeCost;
        totalScore += W_Q_BONUS * getAverageQValue(plan, currentState);
        totalScore += W_GOAL_PROGRESS * goalProgress;

        // Penalty for dying (hunger/health)
        if (fakeState.healthBucket <= 0) {
            totalScore += 500.0;
        }
        if (fakeState.hungerBucket <= 0) {
            totalScore += 100.0;
        }

        // Penalty for incomplete goal
        if (!CheapForward.goalReached(fakeState, goalId)) {
            totalScore += 50.0 * (1.0 - goalProgress);
        }

        return totalScore;
    }

    /**
     * Get average Q-value of actions in plan.
     */
    private double getAverageQValue(List<PlannedStep> plan, State state) {
        double sum = 0.0;
        int count = 0;

        for (PlannedStep step : plan) {
            double qVal = rlAgent.getQValue(state, step.actionName);
            if (!Double.isNaN(qVal) && !Double.isInfinite(qVal)) {
                sum += qVal;
                count++;
            }
        }

        return count > 0 ? sum / count : 0.0;
    }

    /**
     * Detailed breakdown of plan risk (for debugging).
     */
    public String explainScore(List<PlannedStep> plan, State initialState, short goalId) {
        if (plan == null || plan.isEmpty()) {
            return "Invalid plan";
        }

        double deathRisk = 0.0;
        double damage = 0.0;
        int timeCost = 0;

        CheapForward.FakeState fakeState = CheapForward.initFromState(initialState);
        State currentState = initialState;

        for (PlannedStep step : plan) {
            RLAgent.RiskEstimate riskEst = rlAgent.estimateRisk(currentState, step.actionName);
            deathRisk += riskEst.deathProbability;
            damage += riskEst.expectedDamage;

            CheapForward.apply(fakeState, step);
        }

        timeCost = fakeState.timeCost;
        double goalProgress = CheapForward.goalProgress(fakeState, goalId);
        double avgQ = getAverageQValue(plan, currentState);

        return String.format(
            "Plan Analysis:\n" +
            "  Steps: %d\n" +
            "  Death Risk: %.2f%%\n" +
            "  Expected Damage: %.1f\n" +
            "  Time Cost: %d ticks\n" +
            "  Goal Progress: %.1f%%\n" +
            "  Avg Q-Value: %.2f\n" +
            "  Final Health: %d\n" +
            "  Final Hunger: %d",
            plan.size(),
            deathRisk * 100,
            damage,
            timeCost,
            goalProgress * 100,
            avgQ,
            fakeState.healthBucket,
            fakeState.hungerBucket
        );
    }

    /**
     * Check if plan is safe enough to execute.
     */
    public boolean isSafe(List<PlannedStep> plan, State initialState, short goalId, double threshold) {
        double score = scorePlan(plan, initialState, goalId);
        return score < threshold;
    }
}

