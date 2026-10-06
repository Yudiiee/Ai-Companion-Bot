package io.github.yudiiee.aicompanion.GameAI.planner;

import io.github.yudiiee.aicompanion.GameAI.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2nd-order Markov chain for action sequence prediction.
 * Uses goal-conditioned transitions with context hashing.
 */
public class MarkovChain2 {
    private static final Logger LOGGER = LoggerFactory.getLogger("planner");
    private static final String SAVE_DIR = "markov_data";
    private static final double SMOOTHING_ALPHA = 1.0; // Add-1 smoothing

    // Key = (goalId, contextHash, prev2, prev1) -> Value = action counts
    private final ConcurrentHashMap<MarkovKey, MarkovStats> transitions;

    // Random for sampling
    private final Random random;

    // Shared state for parameter generation
    private final Map<String, Object> sharedState;

    public MarkovChain2() {
        this.transitions = new ConcurrentHashMap<>(10000);
        this.random = new Random();
        this.sharedState = new ConcurrentHashMap<>();

        // Ensure ActionRegistry is initialized
        ActionRegistry.ensureInitialized();

        // Verify registry has actions
        int registeredCount = ActionRegistry.getAllActionBytes().size();
        LOGGER.info("MarkovChain2 initialized with {} registered actions", registeredCount);
        if (registeredCount <= 1) {
            LOGGER.error("⚠ ActionRegistry has very few actions! Planning may fail.");
        }

        // Try to load existing data
        loadFromDisk();
    }


    /**
     * Update shared state with goal-specific context.
     * This allows parameter generation to use relevant data.
     */
    public void updateSharedState(String key, Object value) {
        sharedState.put(key, value);
    }

    /**
     * Get value from shared state.
     */
    public Object getSharedState(String key) {
        return sharedState.get(key);
    }

    /**
     * Clear shared state (useful between different goals).
     */
    public void clearSharedState() {
        sharedState.clear();
    }

    /**
     * Draft a plan using Markov sampling.
     *
     * @param goalId Goal identifier
     * @param context Current game state
     * @param maxLen Maximum sequence length
     * @param epsilon Exploration rate (0-1)
     * @return List of planned steps
     */
    public List<PlannedStep> draftPlan(short goalId, State context, int maxLen, double epsilon) {
        List<PlannedStep> plan = new ArrayList<>();
        int contextHash = computeContextHash(context);

        // Set up goal-specific context in shared state
        String goalName = GoalMapper.getGoalName(goalId);
        if (goalName.equalsIgnoreCase("gather")) {
            // For gather goals, set target block type
            sharedState.put("targetBlockType", "minecraft:oak_log");

            // Clear any previous block locations
            sharedState.remove("foundBlock.x");
            sharedState.remove("foundBlock.y");
            sharedState.remove("foundBlock.z");
        }

        // Get relevant actions for this goal
        List<Byte> relevantActions = ActionRegistry.getRelevantActions(goalId, goalName);

        if (relevantActions.isEmpty()) {
            LOGGER.warn("No relevant actions found for goal: {}", goalName);
            return plan;
        }

        // Filter out any invalid actions (shouldn't happen but safety check)
        relevantActions.removeIf(actionId -> {
            String funcName = ActionRegistry.getFunctionName(actionId);
            boolean isInvalid = funcName.equals("unknown") || funcName.startsWith("unknown_");
            if (isInvalid) {
                LOGGER.warn("Filtered out invalid action byte: {}", actionId & 0xFF);
            }
            return isInvalid;
        });

        if (relevantActions.isEmpty()) {
            LOGGER.error("All relevant actions were invalid for goal: {}", goalName);
            return plan;
        }

        LOGGER.debug("Planning for goal '{}' with {} relevant actions", goalName, relevantActions.size());

        // For gathering goals, ALWAYS start with searchBlocks to find the target
        if (goalId == 6) { // GATHER goal
            byte searchBlocksAction = ActionRegistry.getActionByte("searchBlocks");
            if (searchBlocksAction != ActionRegistry.ACTION_UNKNOWN && relevantActions.contains(searchBlocksAction)) {
                // Start with searchBlocks for gathering
                String params = generateDefaultParams("searchBlocks", context);
                plan.add(new PlannedStep(searchBlocksAction, "searchBlocks", 0.0, params));
                LOGGER.debug("Added searchBlocks as first step for gather goal");
            }
        }

        byte prev2 = plan.size() < 2 ? (byte) 0 : plan.get(plan.size() - 2).actionId;
        byte prev1 = plan.isEmpty() ? (byte) 0 : plan.get(plan.size() - 1).actionId;

        int attempts = 0;
        int maxAttempts = maxLen * 3; // Allow some retries for skipped pointless actions

        while (plan.size() < maxLen && attempts < maxAttempts) {
            attempts++;

            byte nextAction;

            // Epsilon-greedy: explore vs exploit
            if (random.nextDouble() < epsilon) {
                // Random exploration from relevant actions
                nextAction = relevantActions.get(random.nextInt(relevantActions.size()));
            } else {
                // Sample from Markov chain (restricted to relevant actions)
                nextAction = sampleNextAction(goalId, contextHash, prev2, prev1, relevantActions);
            }

            // Verify action is valid before adding
            String actionName = ActionRegistry.getFunctionName(nextAction);
            if (actionName.equals("unknown") || actionName.startsWith("unknown_")) {
                LOGGER.warn("Sampled invalid action byte {}, retrying", nextAction & 0xFF);
                continue;
            }

            // Check if action is pointless in current context
            if (isPointlessAction(context, nextAction)) {
                continue; // Skip and try next
            }

            // Enforce dependencies: can't mine/goto without searching first (for gather goals)
            if (goalId == 6) {
                if (actionName.equalsIgnoreCase("mineBlock") || actionName.equalsIgnoreCase("goTo")) {
                    // Check if we've already searched for blocks
                    boolean hasSearched = plan.stream()
                        .anyMatch(step -> step.actionName.equalsIgnoreCase("searchBlocks"));

                    if (!hasSearched) {
                        // Skip actions that depend on search results
                        LOGGER.debug("Skipping {} - no searchBlocks executed yet", actionName);
                        continue;
                    }
                }
            }

            // Add to plan with context-appropriate parameters
            String params = generateDefaultParams(actionName, context);
            plan.add(new PlannedStep(nextAction, actionName, 0.0, params));

            // Update history
            prev2 = prev1;
            prev1 = nextAction;

            // Early stopping if we hit a terminal action
            if (isTerminalAction(nextAction)) {
                break;
            }
        }

        if (plan.isEmpty()) {
            LOGGER.warn("Failed to generate any valid actions for goal: {}", goalName);
        }

        return plan;
    }

    /**
     * Generate default parameters for an action based on context.
     * Returns a comma-separated string or JSON array format.
     *
     * IMPORTANT: For goal-driven planning, we should use searchBlocks → goTo → mineBlock
     * sequence for gathering tasks.
     */
    private String generateDefaultParams(String actionName, State context) {
        switch (actionName.toLowerCase()) {
            case "goto":
            case "movetocoordinates":
                // Check if we have a found block from searchBlocks
                Object foundX = sharedState.get("foundBlock.x");
                if (foundX != null) {
                    // Use the found block location
                    Object foundY = sharedState.get("foundBlock.y");
                    Object foundZ = sharedState.get("foundBlock.z");
                    return String.format("%s,%s,%s,true", foundX, foundY, foundZ);
                }
                // Use a nearby location from context
                return String.format("%d,%d,%d,true",
                    context.getBotX() + 2, context.getBotY(), context.getBotZ());

            case "mineblock":
            case "breakblock":
                // Check if we have a found block from searchBlocks
                Object targetX = sharedState.get("foundBlock.x");
                if (targetX != null) {
                    Object targetY = sharedState.get("foundBlock.y");
                    Object targetZ = sharedState.get("foundBlock.z");
                    return String.format("%s,%s,%s", targetX, targetY, targetZ);
                }
                // Fallback: position 2 blocks in front of the bot
                return String.format("%d,%d,%d",
                    context.getBotX() + 2, context.getBotY(), context.getBotZ());

            case "searchblocks":
                // For gathering goals, search for wood by default
                // Check shared state for what we're looking for
                Object targetBlock = sharedState.get("targetBlockType");
                String blockType = (targetBlock != null) ? targetBlock.toString() : "minecraft:oak_log";
                // Use expanding radius: start at 10, max 100, increment 20
                return String.format("%s,10,100,20", blockType);

            case "placeblock":
                // Default to dirt block at a valid position
                return String.format("%d,%d,%d,minecraft:dirt",
                    context.getBotX() + 1, context.getBotY() - 1, context.getBotZ());

            case "look":
                // Default: look north
                return "north";

            case "turn":
                // Default: turn right
                return "right";

            case "navigateto":
                // Default: navigate forward
                return String.format("%d,%d,%d",
                    context.getBotX() + 5, context.getBotY(), context.getBotZ());

            case "websearch":
                // For gathering goals, search for relevant info
                return "how to find wood in minecraft";

            case "detectblocks":
                // For gathering goals, detect wood/logs
                // This should be goal-dependent but defaulting to oak_log for now
                return "minecraft:oak_log";

            case "gethungerlevel":
            case "gethealthlevel":
            case "getoxygenlevel":
                // These don't need parameters
                return "";

            default:
                // For unknown/status actions - no params
                return "";
        }
    }

    /**
     * Sample next action from Markov distribution (restricted to relevant actions).
     */
    private byte sampleNextAction(short goalId, int contextHash, byte prev2, byte prev1, List<Byte> relevantActions) {
        if (relevantActions.isEmpty()) {
            LOGGER.error("Cannot sample from empty relevant actions list");
            return ActionRegistry.ACTION_UNKNOWN;
        }

        MarkovKey key = new MarkovKey(goalId, contextHash, prev2, prev1);
        MarkovStats stats = transitions.get(key);

        if (stats == null || stats.total == 0) {
            // No data, return random relevant action
            byte action = relevantActions.get(random.nextInt(relevantActions.size()));

            // Validate before returning
            String funcName = ActionRegistry.getFunctionName(action);
            if (funcName.equals("unknown") || funcName.startsWith("unknown_")) {
                LOGGER.warn("Selected invalid action from relevant list: byte {}", action & 0xFF);
                // Find first valid action
                for (byte a : relevantActions) {
                    String name = ActionRegistry.getFunctionName(a);
                    if (!name.equals("unknown") && !name.startsWith("unknown_")) {
                        return a;
                    }
                }
                LOGGER.error("No valid actions in relevant list!");
                return ActionRegistry.ACTION_UNKNOWN;
            }

            return action;
        }

        // Compute smoothed probabilities (only for relevant actions)
        int vocabSize = relevantActions.size();
        double[] probs = new double[vocabSize];
        double sumProbs = 0.0;

        for (int i = 0; i < vocabSize; i++) {
            byte actionId = relevantActions.get(i);
            int actionIndex = actionId & 0xFF;

            // Safety check for array bounds
            int count = 0;
            if (actionIndex < stats.counts.length) {
                count = stats.counts[actionIndex];
            }

            // Add-1 smoothing: P(action) = (count + alpha) / (total + alpha * vocab_size)
            probs[i] = (count + SMOOTHING_ALPHA) / (stats.total + SMOOTHING_ALPHA * vocabSize);
            sumProbs += probs[i];
        }

        // Sample from distribution
        double rand = random.nextDouble() * sumProbs;
        double cumulative = 0.0;

        for (int i = 0; i < vocabSize; i++) {
            cumulative += probs[i];
            if (rand <= cumulative) {
                byte selectedAction = relevantActions.get(i);

                // Validate before returning
                String funcName = ActionRegistry.getFunctionName(selectedAction);
                if (funcName.equals("unknown") || funcName.startsWith("unknown_")) {
                    LOGGER.warn("Markov sampled invalid action: byte {}, resampling", selectedAction & 0xFF);
                    continue; // Try next in distribution
                }

                return selectedAction;
            }
        }

        // Fallback - find first valid action
        for (byte a : relevantActions) {
            String name = ActionRegistry.getFunctionName(a);
            if (!name.equals("unknown") && !name.startsWith("unknown_")) {
                return a;
            }
        }

        // Ultimate fallback
        LOGGER.error("Failed to find any valid action to sample");
        return ActionRegistry.ACTION_UNKNOWN;
    }

    /**
     * Observe a transition and update counts.
     */
    public void observeTransition(short goalId, int contextHash, byte prev2, byte prev1, byte action) {
        MarkovKey key = new MarkovKey(goalId, contextHash, prev2, prev1);

        transitions.compute(key, (k, stats) -> {
            if (stats == null) {
                // Use 128 to cover all possible byte values (0-127)
                stats = new MarkovStats(128);
            }
            int actionIndex = action & 0xFF;
            if (actionIndex < stats.counts.length) {
                stats.counts[actionIndex]++;
                stats.total++;
            } else {
                LOGGER.warn("Action index {} out of bounds for counts array size {}", actionIndex, stats.counts.length);
            }
            return stats;
        });
    }

    /**
     * Compute context hash from state (bucketized).
     */
    private int computeContextHash(State context) {
        int hash = 17;
        hash = 31 * hash + (context.getBotHealth() / 5); // Health buckets of 5
        hash = 31 * hash + (context.getBotHungerLevel() / 5); // Hunger buckets
        hash = 31 * hash + (context.getTimeOfDay().equals("night") ? 1 : 0);
        hash = 31 * hash + (context.isInDangerousStructure() ? 1 : 0);
        // Add more context features as needed
        return hash;
    }

    /**
     * Check if action is pointless in current context.
     */
    private boolean isPointlessAction(State context, byte actionId) {
        String functionName = ActionRegistry.getFunctionName(actionId);

        // Don't eat if hunger is full
        if (functionName.equals("eat") && context.getBotHungerLevel() >= 19) {
            return true;
        }

        // Don't shield if no enemies nearby
        if (functionName.equals("shield") && context.getNearbyEntities().stream()
                .noneMatch(e -> e.isHostile())) {
            return true;
        }

        // Don't attack if no enemies nearby
        if (functionName.equals("attack") && context.getNearbyEntities().stream()
                .noneMatch(e -> e.isHostile())) {
            return true;
        }

        return false;
    }

    /**
     * Check if action is terminal (ends the sequence).
     */
    private boolean isTerminalAction(byte actionId) {
        // No terminal actions in this system yet
        // Could add "goal_reached" action later
        return false;
    }

    /**
     * Save Markov data to disk.
     */
    public void saveToDisk() {
        try {
            Path saveDir = Paths.get(SAVE_DIR);
            if (!Files.exists(saveDir)) {
                Files.createDirectories(saveDir);
            }

            String filename = String.format("%s/markov_chain_%d.dat", SAVE_DIR, System.currentTimeMillis());
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new FileOutputStream(filename))) {
                oos.writeObject(new HashMap<>(transitions));
                LOGGER.info("Saved Markov chain to: {}", filename);
            }

        } catch (IOException e) {
            LOGGER.error("Failed to save Markov chain", e);
        }
    }

    /**
     * Load Markov data from disk.
     */
    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        try {
            Path saveDir = Paths.get(SAVE_DIR);
            if (!Files.exists(saveDir)) {
                return;
            }

            // Find most recent file
            File[] files = saveDir.toFile().listFiles((dir, name) ->
                    name.startsWith("markov_chain_") && name.endsWith(".dat"));

            if (files == null || files.length == 0) {
                LOGGER.info("No existing Markov data found");
                return;
            }

            // Sort by modification time (most recent first)
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            File latest = files[0];

            try (ObjectInputStream ois = new ObjectInputStream(
                    new FileInputStream(latest))) {
                Map<MarkovKey, MarkovStats> loaded =
                        (Map<MarkovKey, MarkovStats>) ois.readObject();
                transitions.putAll(loaded);
                LOGGER.info("Loaded Markov chain from: {} ({} entries)",
                        latest.getName(), transitions.size());
            }

        } catch (IOException | ClassNotFoundException e) {
            LOGGER.error("Failed to load Markov chain", e);
        }
    }

    /**
     * Get statistics for debugging.
     */
    public String getStats() {
        return String.format("Markov transitions: %d entries, %d actions registered",
                transitions.size(), ActionRegistry.getAllActionBytes().size());
    }

    // ===== INNER CLASSES =====

    /**
     * Markov key: (goalId, contextHash, prev2, prev1).
     */
    private static class MarkovKey implements Serializable {
        private static final long serialVersionUID = 1L;

        final short goalId;
        final int contextHash;
        final byte prev2;
        final byte prev1;

        MarkovKey(short goalId, int contextHash, byte prev2, byte prev1) {
            this.goalId = goalId;
            this.contextHash = contextHash;
            this.prev2 = prev2;
            this.prev1 = prev1;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MarkovKey)) return false;
            MarkovKey key = (MarkovKey) o;
            return goalId == key.goalId &&
                   contextHash == key.contextHash &&
                   prev2 == key.prev2 &&
                   prev1 == key.prev1;
        }

        @Override
        public int hashCode() {
            return Objects.hash(goalId, contextHash, prev2, prev1);
        }
    }

    /**
     * Markov statistics: action counts.
     */
    private static class MarkovStats implements Serializable {
        private static final long serialVersionUID = 1L;

        final int[] counts; // counts[actionId] = frequency
        int total;          // sum of all counts

        MarkovStats(int vocabSize) {
            this.counts = new int[vocabSize];
            this.total = 0;
        }
    }
}

