# Player Interaction Feature Checklist

This document tracks the planned player-facing features for the AI Companion mod,
ordered by implementation priority.  Each item links to the relevant existing
code it builds on and describes the expected design approach.

---

## Feature 1 — Companion Stances (Follow / Stay / Wander)

> **Goal:** Give players direct, immediate control over where the bot goes and
> what it does without having to type a full chat sentence.

### Sub-tasks

- [ ] **1.1 — `BotStance` enum**
  - Create `io.github.yudiiee.aicompanion.GameAI.companion.BotStance` with values
    `FOLLOW`, `STAY`, `WANDER`.
  - Default on spawn: `WANDER` (autonomous goal engine runs freely).

- [ ] **1.2 — `CompanionController` class**
  - Holds the active stance per bot in a `ConcurrentHashMap<String, BotStance>`.
  - `setStance(botName, stance)` — changes stance and applies immediate
    side-effects (see 1.3–1.5).
  - `getStance(botName)` — queried each autonomous loop tick.

- [ ] **1.3 — FOLLOW mode**
  - On stance change to `FOLLOW`: record the issuing player's UUID.
  - Every 2 s (scheduled via `AutonomousScheduler`'s drift-check thread):
    calculate distance to that player; if > 5 blocks, inject a
    `WORLD_EVENT` goal `"navigate to player <name>"` into
    `AutonomousGoalEngine` at priority 15 (above world-events, below
    hard interrupts).
  - Uses existing `PathFinder` + `HybridPlanner` for navigation.
  - Cancel pending navigation goals if player comes within 3 blocks.

- [ ] **1.4 — STAY mode**
  - On stance change to `STAY`: record current `BlockPos`.
  - Pause `AutonomousGoalEngine` (call `setPlayerControlled(true)` — reuses
    existing pause gate, which already blocks the goal loop cleanly).
  - If the bot drifts more than 2 blocks from the recorded position (e.g.
    knocked back), inject a single `"return to <x,y,z>"` goal at priority 20.

- [ ] **1.5 — WANDER mode**
  - Resume normal autonomous operation: call `setPlayerControlled(false)`.
  - `AutonomousScheduler` continues its normal replan/drift-check cycle.

- [ ] **1.6 — Chat trigger parsing**
  - Hook into `ollamaClient.routeIntent()` / `FunctionCallerV2`.
  - Detect phrases: `"follow me"` / `"follow <player>"` → `FOLLOW`;
    `"stay here"` / `"stop moving"` → `STAY`;
    `"wander"` / `"go explore"` / `"do your thing"` → `WANDER`.
  - Add entries to `GoalMapper`'s `SynonymMap` for these phrases so they
    don't fall through to the LLM edge fallback.

- [ ] **1.7 — `/bot stance <botName> <follow|stay|wander> [playerName]` command**
  - Register in `modCommandRegistry`.
  - `follow` requires an optional `[playerName]` argument (defaults to the
    command sender).
  - Confirm stance change with a chat message from the bot.

---

## Feature 2 — Mood System

> **Goal:** Make the bot feel emotionally alive by tracking a continuous mood
> state that shifts with in-game events and influences chat tone, ambient
> particles, and system prompt construction.

### Design approach — affective vector, not enum

Instead of a discrete enum, mood is represented as a **2-D affective vector**
`(valence, arousal)` in the range `[-1.0, +1.0]` each, matching the
[Russell circumplex model](https://en.wikipedia.org/wiki/Circumplex_model_of_affect):

| Valence | Arousal | Named mood   | Emoji |
|---------|---------|--------------|-------|
|  +high  |  +high  | Excited      | 🎉    |
|  +high  |  -low   | Content      | 😊    |
|  -low   |  +high  | Nervous      | 😰    |
|  -low   |  -low   | Bored/Sad    | 😔    |

This avoids hard enum transitions and lets the mood shift smoothly over time.
The named mood label is derived by quadrant for display / system-prompt use.

### Sub-tasks

- [ ] **2.1 — `AffectiveState` record**
  - `record AffectiveState(double valence, double arousal)` in
    `io.github.yudiiee.aicompanion.GameAI.mood`.
  - `clamp()` helper — keeps both dimensions in `[-1, +1]`.
  - `namedMood()` — returns `MoodLabel` enum by quadrant.
  - `toPromptFragment()` — returns a short string injected into the LLM system
    prompt, e.g. `"You currently feel excited and energetic."`

- [ ] **2.2 — `MoodLabel` enum**
  - Values: `EXCITED`, `CONTENT`, `NERVOUS`, `BORED`.
  - Each carries a `particleType` (Minecraft particle ID) and a `soundEvent`
    for ambient feedback (see 2.6).

- [ ] **2.3 — `MoodEngine` class**
  - Holds one `AffectiveState` per bot in a `ConcurrentHashMap`.
  - `applyDelta(botName, dValence, dArousal)` — adds a signed delta and
    clamps; uses exponential decay toward `(0, 0)` (neutral) over time so
    moods don't stay extreme forever.
  - Decay rate configurable via `-Daicompanion.mood.decayRate` (default `0.05`
    per minute).

- [ ] **2.4 — RL reward → mood delta mapping**
  - Hook into `BotEventHandler.detectAndReact()` after `calculateReward()`.
  - Positive reward (≥ +1.0)  →  `applyDelta(+0.15, +0.10)` (more
    positive + slightly more aroused).
  - Death / large negative reward (≤ -3.0)  →  `applyDelta(-0.40, +0.35)`
    (scared/nervous spike).
  - Neutral reward  →  `applyDelta(0, -0.05)` (gradual arousal decay = calm).

- [ ] **2.5 — World-event → mood delta mapping**
  - Hook into `WorldEventListener.process()` after a pattern fires.
  - Player joins  →  `applyDelta(+0.20, +0.15)` (happy to see someone).
  - Player dies nearby  →  `applyDelta(-0.10, +0.20)` (alarmed).
  - Bot achieves advancement (via `AutonomousGoalEngine` goal completion
    callback)  →  `applyDelta(+0.30, +0.25)` (proud/excited).
  - Long idle (> 10 min with empty goal queue)  →  `applyDelta(-0.05, -0.10)`
    (boredom creep).

- [ ] **2.6 — Ambient feedback (particles + sound)**
  - When `MoodLabel` changes quadrant, play a short sound and spawn particles
    around the bot via `ServerWorld.spawnParticles()` / `ServerWorld.playSound()`.
  - Threshold: only trigger if the mood label changes (not on every small delta)
    to avoid spamming.
  - Examples: `NERVOUS` → red particles + fast heartbeat sound;
    `EXCITED` → firework burst particles + chime sound.

- [ ] **2.7 — System prompt injection**
  - Modify `ollamaClient.generateSystemPrompt()` and
    `LLMServiceHandler.buildSystemPrompt()` (if separate) to call
    `MoodEngine.getState(botName).toPromptFragment()` and append it to the
    prompt.  This influences every LLM response going forward.

- [ ] **2.8 — `/bot mood <botName>` read-only status command**
  - Prints the current `(valence, arousal)` vector, named mood label, and
    a one-line description to the player who runs the command.
  - Register in `modCommandRegistry`.

---

## Feature 3 — Persona System (integrated with Mood)

> **Goal:** Let end-users customise the bot's personality archetype, which
> combines with the live mood vector to produce a unique, consistent voice.

### Design approach

A **persona** is a static personality baseline stored in config; **mood** is
the dynamic emotional layer on top.  The final system prompt = base persona
template + live mood fragment.  This keeps personality consistent while still
allowing emotional variation.

### Sub-tasks

- [ ] **3.1 — `PersonaTemplate` record**
  - Fields: `id` (String slug), `displayName`, `basePromptFragment`,
    `defaultValence` (double), `defaultArousal` (double).
  - The `defaultValence/Arousal` seeds `MoodEngine` on bot spawn, so a
    "cheerful" persona starts with a positive baseline.

- [ ] **3.2 — Built-in persona library**
  - Ship 4 built-in personas in a static map:

  | ID          | Display name  | Flavour                                      | Default mood       |
  |-------------|---------------|----------------------------------------------|--------------------|
  | `cheerful`  | Cheerful      | Upbeat, uses humour, celebrates small wins   | (+0.6, +0.3)       |
  | `serious`   | Serious       | Terse, task-focused, minimal small-talk      | (+0.1, -0.1)       |
  | `sarcastic` | Sarcastic     | Dry wit, self-aware, occasionally complains  | (-0.1, +0.2)       |
  | `cautious`  | Cautious      | Worried, over-explains risks, hesitant       | (-0.2, +0.4)       |

- [ ] **3.3 — Persona persistence**
  - Store `selectedPersona` per bot name in `ManualConfig` (the existing
    config system). Defaults to `cheerful`.
  - `ManualConfig.getPersona(botName)` / `setPersona(botName, id)`.

- [ ] **3.4 — System prompt construction pipeline**
  - `PromptBuilder.build(botName)` — single static method that:
    1. Loads base identity text (current hardcoded block in
       `ollamaClient.generateSystemPrompt()`).
    2. Appends `PersonaTemplate.basePromptFragment` for the selected persona.
    3. Appends `MoodEngine.getState(botName).toPromptFragment()` (Feature 2.7).
    4. Returns the combined string.
  - Replace all direct calls to `generateSystemPrompt()` with
    `PromptBuilder.build(botName)`.

- [ ] **3.5 — `/bot persona <botName> <personaId>` command**
  - Lists available persona IDs on tab-complete.
  - Saves selection via `ManualConfig.setPersona()`.
  - Re-seeds `MoodEngine` with the new persona's default valence/arousal.
  - Bot sends a short in-character confirmation message matching the new
    persona's voice.
  - Register in `modCommandRegistry`.

- [ ] **3.6 — Custom persona support (stretch goal)**
  - Allow a player/server admin to define a custom persona in
    `config/aicompanion/personas.json5` with the same fields as
    `PersonaTemplate`.
  - `PersonaRegistry.load()` merges built-ins + custom file at startup.
  - No code changes needed for new personas after this is implemented.

---

## Feature 4 — Smart Item Handoff (Equip UX)

> **Goal:** Make giving the bot items feel natural and intelligent rather than
> requiring players to remember command syntax.

### Context — what already exists

- `armorUtils.autoEquipArmor(bot)` — scans inventory and equips the best
  available armour pieces into armour slots.  Already wired into:
  - `BotEventHandler.executeAction()` (`EQUIP_ARMOR` RL action).
  - `modCommandRegistry` as a `/bot equip <botName>` subcommand.
- Players can already **throw** items at the bot (vanilla item pickup).
- `WeaponUtils.equipBestMeleeWeapon(bot)` — selects best melee weapon from
  hotbar during combat.

### Sub-tasks

- [ ] **4.1 — Item pickup detection event**
  - Register a `ServerLivingEntityEvents.ALLOW_PICKUP_ITEM` listener in
    `AICompanion.onInitialize()`.
  - When the bot picks up an item, classify it:
    - Armour piece  → call `armorUtils.autoEquipArmor(bot)` immediately.
    - Weapon (sword/axe/trident/bow/crossbow)  → call
      `WeaponUtils.equipBestMeleeWeapon(bot)` or hotbar-slot the weapon.
    - Food  → move to hotbar so `USE_ITEM` RL action can eat it.
    - Tool (pickaxe/axe/shovel)  → move to hotbar slot 2–5.
    - Other  → leave in inventory as-is.
  - Send a brief bot chat message confirming the equip:
    e.g. `"Steve: Thanks! I equipped the diamond sword."`

- [ ] **4.2 — `ItemClassifier` utility class**
  - `ItemClassifier.classify(ItemStack)` returns a `ItemRole` enum:
    `ARMOR`, `MELEE_WEAPON`, `RANGED_WEAPON`, `FOOD`, `TOOL`, `MISC`.
  - Backed by Minecraft's item tag system (`ItemTags.ARMOR`, etc.) — no
    hard-coded item lists.

- [ ] **4.3 — Hotbar slot policy**
  - Define a preferred hotbar layout enforced after any pickup:
    - Slot 1: best melee weapon
    - Slot 2: best ranged weapon / bow
    - Slot 3: best tool (pickaxe tier)
    - Slot 4: best food
    - Slots 5–9: free
  - `HotbarPolicy.rebalance(bot)` — applies the policy non-destructively
    (only moves items if the preferred slot is empty or holds an inferior
    item).

- [ ] **4.4 — `/bot equip <botName>` command upgrade**
  - Current behaviour: only equips armour.
  - Extend to call both `armorUtils.autoEquipArmor(bot)` AND
    `HotbarPolicy.rebalance(bot)` so a single command fully equips the bot.
  - Update command help text accordingly.

- [ ] **4.5 — Mood integration**
  - Receiving a notably good item (diamond/netherite tier) triggers
    `MoodEngine.applyDelta(+0.20, +0.15)` (happy and a bit excited).
  - Bot chat message reflects mood:
    - `EXCITED` mood: `"Oh wow, a netherite sword! Let's go!"`
    - `CONTENT` mood: `"Nice, I'll put this to good use."`
    - `NERVOUS` mood: `"I hope I'm good enough to use this…"`

---

## Implementation Order

```
Feature 1 (Companion Stances)
  └─ 1.1 BotStance enum
  └─ 1.2 CompanionController
  └─ 1.3 FOLLOW mode
  └─ 1.4 STAY mode
  └─ 1.5 WANDER mode
  └─ 1.6 Chat trigger parsing
  └─ 1.7 /bot stance command

Feature 2 (Mood System)
  └─ 2.1 AffectiveState record
  └─ 2.2 MoodLabel enum
  └─ 2.3 MoodEngine
  └─ 2.4 RL reward → mood delta
  └─ 2.5 World-event → mood delta
  └─ 2.6 Ambient feedback
  └─ 2.7 System prompt injection   ← depends on 3.4 (PromptBuilder)
  └─ 2.8 /bot mood command

Feature 3 (Persona System)          ← implement alongside Feature 2
  └─ 3.1 PersonaTemplate record
  └─ 3.2 Built-in persona library
  └─ 3.3 Persona persistence
  └─ 3.4 PromptBuilder              ← unblocks 2.7
  └─ 3.5 /bot persona command
  └─ 3.6 Custom persona support (stretch)

Feature 4 (Smart Item Handoff)
  └─ 4.1 Item pickup detection event
  └─ 4.2 ItemClassifier utility
  └─ 4.3 HotbarPolicy
  └─ 4.4 /bot equip upgrade
  └─ 4.5 Mood integration           ← depends on Feature 2
```

---

*Last updated: 2026-05-30*
