package io.github.yudiiee.aicompanion.GameAI.planner;

import io.github.yudiiee.aicompanion.GameAI.State;

/**
 * Ultra-lightweight forward simulator for ranking plans.
 * Does NOT guarantee correctness - only estimates relative utility.
 */
public class CheapForward {

    /**
     * Fake state with minimal information for fast simulation.
     */
    public static class FakeState {
        // Inventory signature bits
        boolean hasWood = false;
        boolean hasStone = false;
        boolean hasFoodItem = false;
        boolean hasWeapon = false;
        boolean hasArmor = false;
        boolean hasTorch = false;

        // Resource counters
        int healthBucket = 20; // 0-20
        int hungerBucket = 20; // 0-20
        int timeCost = 0;      // Accumulated time cost

        // Position/environment (very coarse)
        boolean inDanger = false;
        boolean nightTime = false;

        public FakeState copy() {
            FakeState copy = new FakeState();
            copy.hasWood = this.hasWood;
            copy.hasStone = this.hasStone;
            copy.hasFoodItem = this.hasFoodItem;
            copy.hasWeapon = this.hasWeapon;
            copy.hasArmor = this.hasArmor;
            copy.hasTorch = this.hasTorch;
            copy.healthBucket = this.healthBucket;
            copy.hungerBucket = this.hungerBucket;
            copy.timeCost = this.timeCost;
            copy.inDanger = this.inDanger;
            copy.nightTime = this.nightTime;
            return copy;
        }
    }

    /**
     * Initialize fake state from real state.
     */
    public static FakeState initFromState(State realState) {
        FakeState fake = new FakeState();

        // Extract inventory signatures
        String hotbarStr = String.join(",", realState.getHotBarItems());
        fake.hasWood = hotbarStr.contains("log") || hotbarStr.contains("planks");
        fake.hasStone = hotbarStr.contains("stone") || hotbarStr.contains("cobblestone");
        fake.hasFoodItem = hotbarStr.contains("bread") || hotbarStr.contains("meat") ||
                          hotbarStr.contains("apple") || hotbarStr.contains("carrot");
        fake.hasWeapon = hotbarStr.contains("sword") || hotbarStr.contains("axe") ||
                        hotbarStr.contains("bow");
        fake.hasArmor = !realState.getArmorItems().get("helmet").equals("minecraft:air") ||
                       !realState.getArmorItems().get("chestplate").equals("minecraft:air");
        fake.hasTorch = hotbarStr.contains("torch");

        // Health/hunger
        fake.healthBucket = realState.getBotHealth();
        fake.hungerBucket = realState.getBotHungerLevel();

        // Environment
        fake.inDanger = realState.getDistanceToHostileEntity() < 10.0;
        fake.nightTime = realState.getTimeOfDay().equals("night");
        fake.timeCost = 0;

        return fake;
    }

    /**
     * Apply action to fake state (very coarse simulation).
     */
    public static void apply(FakeState fake, PlannedStep step) {
        byte actionId = step.actionId;
        int id = actionId & 0xFF;

        // Movement actions - cost time
        if (id >= 1 && id <= 7) {
            fake.timeCost += 1; // 1 tick per movement action
        }

        // Combat actions
        if (id == 10) { // attack
            fake.timeCost += 2;
            fake.healthBucket -= 1; // Assume some damage taken
        }

        if (id == 11) { // shoot_arrow
            fake.timeCost += 3; // Drawing bow
        }

        if (id == 12) { // use_shield
            fake.timeCost += 1;
        }

        if (id == 13) { // evade
            fake.timeCost += 5; // Evasion takes time
            fake.inDanger = false; // Assume successful evasion
        }

        // Utility actions
        if (id == 20) { // mine_block
            fake.timeCost += 10; // Mining is slow
            // Assume we get resources (very coarse)
            if (step.params != null && step.params.contains("log")) {
                fake.hasWood = true;
            } else if (step.params != null && step.params.contains("stone")) {
                fake.hasStone = true;
            }
        }

        if (id == 21) { // place_block
            fake.timeCost += 2;
        }

        if (id == 22) { // eat_food
            fake.timeCost += 2;
            fake.hungerBucket = Math.min(20, fake.hungerBucket + 6);
        }

        if (id == 23) { // equip_armor
            fake.timeCost += 1;
            fake.hasArmor = true;
        }

        if (id == 24) { // craft_item
            fake.timeCost += 5; // Crafting takes time
            // Assume crafting weapon/tool
            if (step.params != null && step.params.contains("sword")) {
                fake.hasWeapon = true;
            }
        }

        if (id == 25) { // use_torch
            fake.timeCost += 2;
            fake.hasTorch = true;
        }

        // Hotbar switching - fast
        if (id >= 31 && id <= 39) {
            fake.timeCost += 0; // Instant
        }

        // Hunger depletion over time (very coarse)
        if (fake.timeCost % 100 == 0) {
            fake.hungerBucket = Math.max(0, fake.hungerBucket - 1);
        }

        // Clamp values
        fake.healthBucket = Math.max(0, Math.min(20, fake.healthBucket));
        fake.hungerBucket = Math.max(0, Math.min(20, fake.hungerBucket));
    }

    /**
     * Estimate if goal is reached (very coarse heuristic).
     */
    public static boolean goalReached(FakeState fake, short goalId) {
        // Goal IDs (examples):
        // 1 = get_wood
        // 2 = get_stone
        // 3 = survive_night
        // 4 = kill_hostile
        // 5 = eat_food
        // etc.

        switch (goalId) {
            case 1: // get_wood
                return fake.hasWood;

            case 2: // get_stone
                return fake.hasStone;

            case 3: // survive_night
                return !fake.nightTime && fake.healthBucket > 10;

            case 4: // kill_hostile
                return !fake.inDanger;

            case 5: // eat_food
                return fake.hungerBucket >= 15;

            case 6: // craft_weapon
                return fake.hasWeapon;

            case 7: // equip_armor
                return fake.hasArmor;

            default:
                return false; // Unknown goal
        }
    }

    /**
     * Compute goal progress heuristic (0.0 to 1.0).
     */
    public static double goalProgress(FakeState fake, short goalId) {
        switch (goalId) {
            case 1: // get_wood
                return fake.hasWood ? 1.0 : 0.0;

            case 2: // get_stone
                return fake.hasStone ? 1.0 : (fake.hasWood ? 0.5 : 0.0);

            case 3: // survive_night
                double survivalScore = 0.0;
                survivalScore += fake.healthBucket / 20.0 * 0.4;
                survivalScore += fake.hungerBucket / 20.0 * 0.2;
                survivalScore += fake.hasWeapon ? 0.2 : 0.0;
                survivalScore += fake.hasArmor ? 0.2 : 0.0;
                return survivalScore;

            case 4: // kill_hostile
                return !fake.inDanger ? 1.0 : (fake.hasWeapon ? 0.5 : 0.0);

            case 5: // eat_food
                return fake.hungerBucket / 20.0;

            case 6: // craft_weapon
                return fake.hasWeapon ? 1.0 : (fake.hasWood ? 0.5 : 0.0);

            case 7: // equip_armor
                return fake.hasArmor ? 1.0 : 0.0;

            default:
                return 0.0;
        }
    }
}

