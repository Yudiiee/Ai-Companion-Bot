<p align="center"><img src="src/main/resources/assets/ai-companion/icon.png" width="128" alt="AI Companion"></p>

<h1 align="center">AI Companion</h1>

<p align="center">A Minecraft mod that adds companions who play like real people.</p>

<p align="center">Minecraft 26.3 · Fabric · by <a href="https://github.com/Yudiiee">Udit (Yudiiee)</a></p>

---

AI Companion adds bots that play survival with you the way a friend would. They chat like a
person, build and live in a house, farm their own food, dig a proper mine and go strip mining for
whatever ore you ask for, fight off creepers and skeletons, and look after themselves. They work
on their own out of the box, and you can hook them up to an LLM for real conversations.

## Features

- **Talks like a player.** Short lowercase chat with typing time, reactions to what happens, small
  talk now and then, and replies without needing its name when it's clearly being spoken to.
- **Home base.** Builds a lit 9×9 house (log pillars, windows, slab roof, door, bed, crafting table,
  furnace, two double chests). It keeps its loot there, goes home at night and when hurt, and
  checks every spot inside is bright enough that nothing spawns.
- **Chests it remembers.** It uses what's in its pockets first, then the chests, and only then
  goes out to gather.
- **A real mine.** `dig down` makes a lit staircase from a fixed entrance to y -58 with a landing
  at the bottom. Ore jobs go down the same stairs to the right level for each ore and strip mine
  there, with branches every 3 blocks and torches every 6. Caves it breaks into get lit, or sealed
  off if there's water or lava in them. Each trip carries on where the last one stopped.
- **Food farm.** A 9×9 plot beside the house with water, torches and crops (carrots/potatoes, then
  wheat, then beetroot). It harvests, replants, bakes bread and keeps enough food on hand.
- **A house of its own.** Each companion builds one of six medieval houses as its home (cottage, townhouse,
  inn, workshop, hall, manor), moves in once it's mostly up and keeps working on it until it's finished.
- **Knows its trees.** Spruce is spruce and dark oak is dark oak: it chops the right trees for a build, knows
  where each grows and what colour it comes out, and when a wood can't be had it picks the closest-looking one
  and tells you.
- **Remembers its recipes.** Every companion knows the recipes in `config/ai-companion/recipes.txt` (171 to start,
  add your own): ask "how do i make a hopper", or "craft me 2 hoppers" and it makes them from scratch.
- **Builds from schematics.** Drop WorldEdit `.schem`, structure-block `.nbt` or Litematica `.litematic` files in
  `config/ai-companion/schematics` and say `build the iron farm`. It gathers what it can, builds it in the right
  order with pistons, observers and redstone pointing the right way, crafting the stairs, slabs, trapdoors, stone
  bricks and hoppers it needs, and tells you what's still missing. Comes with a sugar cane farm, a cactus farm, a
  bamboo farm and an iron farm, and keeps the farms it builds harvested.
- **Fights smart.** It goes after creepers (hit and back off) and skeletons (shield up, close in)
  on sight, and fights any other mob once it's attacked. Below 7 health it blocks the mob off
  and retreats to heal.
- **Stays alive.** It gets out of lava, puts out fire in water, swims to shore instead of drowning,
  and digs out of gravel that falls on it.
- **Gets around.** An action-based pathfinder that walks, jumps, swims, bridges, pillars, tunnels
  and opens doors, without breaking anyone's builds.
- **PvP.** `/bot pvp <bot> <player> [iron|diamond|netherite]` or just say "fight me".

The full list of what the companions do is in [FEATURES.md](FEATURES.md).

## Requirements

- Minecraft **26.3**, Java **25**
- [Fabric Loader](https://fabricmc.net/) 0.19.5 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Carpet](https://github.com/gnembon/fabric-carpet) for 26.3 (the companions are Carpet-style players)

## Getting started

1. Put `ai-companion-<version>.jar`, Fabric API and Carpet in your `mods` folder.
2. In a world, spawn a companion:
   ```
   /bot spawn Bro play
   ```
3. Talk to it in chat. A few things to try:
   - `build a house`, `go home`, `what's in the chest`, `store your stuff`
   - `get me 10 iron`, `mine diamonds`, `dig down`, `strip mine for gold`
   - `build a farm`, `harvest the crops`
   - `what can you build`, `build a cactus farm`, `build the iron farm here`, `continue the build`
   - `how do i make a crafter`, `craft me 2 lanterns`, `what kind of tree is that`
   - `follow me`, `stay here`, `come here`, `stop`

It plays on its own when you leave it be. `/humanlike` shows the settings (chat, autoplay, home,
auto-store, and more).

## Talking to it with an LLM (optional)

AI Companion works without any AI service. For free-form conversation it can use any
OpenAI-compatible endpoint (OpenRouter, Groq, TogetherAI, LM Studio, llama.cpp and others):

1. Add `-Daicompanion.llmMode=custom` to your launcher's JVM arguments.
2. In game run `/configMan`, open **API Keys**, and set the **Custom API URL** (for example
   `https://openrouter.ai/api/v1` or `http://localhost:1234/v1`) and an API key if the service
   needs one.
3. Save, refresh the model list, pick a model and save again.

More detail in [CUSTOM_PROVIDERS.md](CUSTOM_PROVIDERS.md).

## Building from source

```
./gradlew build
```

The jar ends up in `build/libs/`.

## Credits and license

AI Companion is by Udit ([@Yudiiee](https://github.com/Yudiiee)). It is based on
[AI-Player](https://github.com/shasankp000/AI-Player) by shasankp000, and like it is licensed under
the GNU General Public License v2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
