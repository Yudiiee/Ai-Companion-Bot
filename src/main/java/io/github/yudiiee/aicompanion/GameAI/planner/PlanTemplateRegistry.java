package io.github.yudiiee.aicompanion.GameAI.planner;

import io.github.yudiiee.aicompanion.GameAI.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Pre-compiled plan templates keyed by GoalMapper goal IDs.
 *
 * Each template is an ordered list of {@link TemplateStep} objects.  A
 * {@link TemplateStep} knows which tool to call and how to resolve its
 * parameters from (a) the raw player input and (b) the live SharedState
 * that tools populate as they run.
 *
 * Calling {@link #compile(short, String, State, Map)} is pure Java and
 * returns a ready-to-execute {@link Plan} in well under 5 ms — no threads,
 * no network, no LLM.
 *
 * To add support for a new goal:
 *  1. Add a constant in {@link GoalMapper}.
 *  2. Register a template list in the static block below.
 *  3. Optionally add new synonyms in {@link SynonymMap}.
 */
public class PlanTemplateRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("PlanTemplateRegistry");

    // ── Template storage ──────────────────────────────────────────────────────
    private static final Map<Short, List<TemplateStep>> TEMPLATES = new HashMap<>();

    static {
        // ── MINE (goal 1) ─────────────────────────────────────────────────────
        // "mine iron", "chop oak wood", "dig some coal"
        // Steps: searchBlocks → goTo(foundBlock) → mineBlock(foundBlock)
        TEMPLATES.put(GoalMapper.GOAL_MINE, List.of(
            new TemplateStep("searchBlocks", (input, state, shared) -> {
                String block = EntityExtractor.extractBlockType(input);
                return block + ",10,100,20";
            }),
            new TemplateStep("goTo", (input, state, shared) -> {
                Object x = shared.get("foundBlock.x");
                Object y = shared.get("foundBlock.y");
                Object z = shared.get("foundBlock.z");
                if (x != null && y != null && z != null)
                    return x + "," + y + "," + z + ",true";
                return state.getBotX() + "," + state.getBotY() + "," + state.getBotZ() + ",true";
            }),
            new TemplateStep("mineBlock", (input, state, shared) -> {
                Object x = shared.get("foundBlock.x");
                Object y = shared.get("foundBlock.y");
                Object z = shared.get("foundBlock.z");
                if (x != null && y != null && z != null)
                    return x + "," + y + "," + z;
                return state.getBotX() + 2 + "," + state.getBotY() + "," + state.getBotZ();
            })
        ));

        // ── GATHER (goal 6) ───────────────────────────────────────────────────
        // Same pipeline as MINE — search, navigate, break.
        TEMPLATES.put(GoalMapper.GOAL_GATHER, List.of(
            new TemplateStep("searchBlocks", (input, state, shared) -> {
                String block = EntityExtractor.extractBlockType(input);
                return block + ",10,100,20";
            }),
            new TemplateStep("goTo", (input, state, shared) -> {
                Object x = shared.get("foundBlock.x");
                Object y = shared.get("foundBlock.y");
                Object z = shared.get("foundBlock.z");
                if (x != null && y != null && z != null)
                    return x + "," + y + "," + z + ",true";
                return state.getBotX() + "," + state.getBotY() + "," + state.getBotZ() + ",true";
            }),
            new TemplateStep("mineBlock", (input, state, shared) -> {
                Object x = shared.get("foundBlock.x");
                Object y = shared.get("foundBlock.y");
                Object z = shared.get("foundBlock.z");
                if (x != null) return x + "," + y + "," + z;
                return state.getBotX() + 2 + "," + state.getBotY() + "," + state.getBotZ();
            })
        ));

        // ── NAVIGATE (goal 4) ─────────────────────────────────────────────────
        // "go to 100 64 200", "walk to 50 70 -30"
        TEMPLATES.put(GoalMapper.GOAL_NAVIGATE, List.of(
            new TemplateStep("goTo", (input, state, shared) -> {
                int[] coords = EntityExtractor.extractCoords(input);
                if (coords != null)
                    return coords[0] + "," + coords[1] + "," + coords[2] + ",true";
                // Fallback: move 20 blocks ahead
                return (state.getBotX() + 20) + "," + state.getBotY() + "," + state.getBotZ() + ",true";
            })
        ));

        // ── BUILD (goal 2) ────────────────────────────────────────────────────
        // "place stone at 10 64 20", "build a wall here"
        TEMPLATES.put(GoalMapper.GOAL_BUILD, List.of(
            new TemplateStep("goTo", (input, state, shared) -> {
                int[] coords = EntityExtractor.extractCoords(input);
                if (coords != null)
                    return coords[0] + "," + coords[1] + "," + coords[2] + ",true";
                return (state.getBotX() + 1) + "," + state.getBotY() + "," + state.getBotZ() + ",true";
            }),
            new TemplateStep("placeBlock", (input, state, shared) -> {
                int[] coords = EntityExtractor.extractCoords(input);
                String block = EntityExtractor.extractBlockType(input, "minecraft:cobblestone");
                if (coords != null)
                    return coords[0] + "," + coords[1] + "," + coords[2] + "," + block;
                return (state.getBotX() + 1) + "," + state.getBotY() + "," + state.getBotZ() + "," + block;
            })
        ));

        // ── EXPLORE (goal 7) ──────────────────────────────────────────────────
        // "find a village", "search for diamonds"
        TEMPLATES.put(GoalMapper.GOAL_EXPLORE, List.of(
            new TemplateStep("look", (input, state, shared) ->
                EntityExtractor.extractDirection(input)),
            new TemplateStep("searchBlocks", (input, state, shared) -> {
                String block = EntityExtractor.extractBlockType(input, "minecraft:stone");
                return block + ",10,200,30";
            }),
            new TemplateStep("goTo", (input, state, shared) -> {
                Object x = shared.get("foundBlock.x");
                Object y = shared.get("foundBlock.y");
                Object z = shared.get("foundBlock.z");
                if (x != null) return x + "," + y + "," + z + ",false";
                return (state.getBotX() + 50) + "," + state.getBotY() + "," + state.getBotZ() + ",false";
            })
        ));

        // ── CRAFT (goal 3) ────────────────────────────────────────────────────
        // Placeholder: crafting requires table proximity.
        // Steps: navigate to crafting table area (from shared state or nearby)
        TEMPLATES.put(GoalMapper.GOAL_CRAFT, List.of(
            new TemplateStep("searchBlocks", (input, state, shared) ->
                "minecraft:crafting_table,10,50,10"),
            new TemplateStep("goTo", (input, state, shared) -> {
                Object x = shared.get("foundBlock.x");
                Object y = shared.get("foundBlock.y");
                Object z = shared.get("foundBlock.z");
                if (x != null) return x + "," + y + "," + z + ",true";
                return state.getBotX() + "," + state.getBotY() + "," + state.getBotZ() + ",true";
            })
        ));

        // ── FARM (goal 8) ─────────────────────────────────────────────────────
        TEMPLATES.put(GoalMapper.GOAL_FARM, List.of(
            new TemplateStep("searchBlocks", (input, state, shared) ->
                "minecraft:farmland,10,100,20"),
            new TemplateStep("goTo", (input, state, shared) -> {
                Object x = shared.get("foundBlock.x");
                if (x != null) return x + "," + shared.get("foundBlock.y") + "," + shared.get("foundBlock.z") + ",true";
                return state.getBotX() + "," + state.getBotY() + "," + state.getBotZ() + ",true";
            }),
            new TemplateStep("mineBlock", (input, state, shared) -> {
                Object x = shared.get("foundBlock.x");
                if (x != null) return x + "," + shared.get("foundBlock.y") + "," + shared.get("foundBlock.z");
                return state.getBotX() + 1 + "," + state.getBotY() + "," + state.getBotZ();
            })
        ));

        // ── COMBAT (goal 5) ───────────────────────────────────────────────────
        // Minimal template: check health, then look toward threat.
        TEMPLATES.put(GoalMapper.GOAL_COMBAT, List.of(
            new TemplateStep("getHealthLevel",   (input, state, shared) -> "None"),
            new TemplateStep("look",              (input, state, shared) ->
                EntityExtractor.extractDirection(input))
        ));

        // ── TRADE (goal 9) ────────────────────────────────────────────────────
        TEMPLATES.put(GoalMapper.GOAL_TRADE, List.of(
            new TemplateStep("searchBlocks", (input, state, shared) ->
                "minecraft:lectern,10,100,20"),
            new TemplateStep("goTo", (input, state, shared) -> {
                Object x = shared.get("foundBlock.x");
                if (x != null) return x + "," + shared.get("foundBlock.y") + "," + shared.get("foundBlock.z") + ",true";
                return state.getBotX() + "," + state.getBotY() + "," + state.getBotZ() + ",true";
            })
        ));
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Compile a ready-to-execute {@link Plan} for the given goal.
     *
     * @param goalId   Resolved goal ID from {@link GoalMapper}
     * @param rawInput Original player chat message
     * @param state    Current bot/game state
     * @param shared   Live shared-state map (populated by previous tool executions)
     * @return A {@link Plan} with pre-resolved parameters, or {@code null} if no
     *         template is registered for this goal.
     */
    public static Plan compile(short goalId, String rawInput, State state, Map<String, Object> shared) {
        List<TemplateStep> template = TEMPLATES.get(goalId);

        if (template == null || template.isEmpty()) {
            LOGGER.warn("No template registered for goalId={}. Cannot compile plan.", goalId);
            return null;
        }

        List<PlannedStep> steps = new ArrayList<>();
        for (TemplateStep ts : template) {
            byte actionId = ActionRegistry.getActionByte(ts.toolName());
            String params  = ts.resolveParams(rawInput, state, shared);
            steps.add(new PlannedStep(actionId, ts.toolName(), 0.0, params));
            LOGGER.debug("Compiled step: {}({})", ts.toolName(), params);
        }

        Plan plan = new Plan(UUID.randomUUID(), goalId, steps);
        LOGGER.info("✓ PlanTemplateRegistry compiled {} steps for goalId={} in < 1 ms",
            steps.size(), goalId);
        return plan;
    }

    /** Returns true if a template exists for this goal. */
    public static boolean hasTemplate(short goalId) {
        return TEMPLATES.containsKey(goalId);
    }

    // ── Inner: TemplateStep ───────────────────────────────────────────────────

    /**
     * One step within a plan template.
     * Holds the tool name and a functional parameter resolver.
     */
    public record TemplateStep(
        String toolName,
        ParamResolver resolver
    ) {
        String resolveParams(String input, State state, Map<String, Object> shared) {
            try {
                return resolver.resolve(input, state, shared);
            } catch (Exception e) {
                LOGGER.warn("Param resolution failed for tool '{}': {}", toolName, e.getMessage());
                return "";
            }
        }
    }

    /** Functional interface for parameter resolution. */
    @FunctionalInterface
    public interface ParamResolver {
        String resolve(String rawInput, State state, Map<String, Object> shared);
    }
}
