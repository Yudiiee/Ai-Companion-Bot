# AI Companion - Changelog

## 1.3.0 (Minecraft 26.3)

- Starter houses: every companion builds one of six medieval houses (from a village by ajmed) as its home. The
  first bot takes the cottage, the next the townhouse, then the inn, workshop, hall and manor. It moves in once
  60% is up (inside, the middle to come home to, the barrels/chests and the bed come from the design; chests and a
  bed are added if the design has none) and keeps building in the daytime until it's done. In a treeless spot it
  puts up the small classic house to live in meanwhile. Bots that already had the classic house build one too.
- Wood is exact now: spruce is spruce, dark oak is dark oak. The bot chops the right kind of tree for each and
  says which woods a design needs and what colour they are. When a kind can't be had (warped and crimson only grow
  in the Nether, no dark oak forest nearby), it picks the closest-looking wood it can get (by colour, the way the
  eye sees it) and says so; same for other blocks it can't get (prismarine, calcite, stained glass). Bring the real
  thing and it swaps it in. Flowers, leaves, vines and carpets are decoration: put in when it has them.
- Trees: "what kind of tree is that", "where do i find dark oak", "what woods are there".
- More recipes: barrels, smokers, cauldrons, coal blocks, mossy stone, stairs/slabs/walls of every common stone.

## 1.2.0 (Minecraft 26.3)

- New bundled design: a villager-pod iron farm (by F3deMatt25). "build the iron farm"; bring 3 villagers to the
  beds at the top when it's done.
- The builder crafts building blocks itself: wooden stairs, slabs, fences, gates, trapdoors, doors, signs, stripped
  logs, stone bricks and their slabs/stairs/walls, glass panes, lanterns, chains, hoppers, campfires and redstone
  parts, making the ingredients (planks, sticks, stone, iron, nuggets) as it goes. Any kind of wood counts.
- A design's side file can have a `note:` that the bot says when the build is done.

## 1.1.0 (Minecraft 26.3)

- New: building from schematics. Drop `.schem`, `.nbt` or `.litematic` files in `config/ai-companion/schematics` and
  say "build the iron farm". The bot picks a spot next to the house (or "here", or "at x y z"), gathers what it can,
  clears the area and builds it in order, then lists anything it couldn't get ("continue the build" to finish).
- Starter designs: sugar cane farm, cactus farm, bamboo farm. The bot harvests and replants farms it built.
- "what can you build", "what do you need for the X", "harvest the X", "forget the X".
- Finished builds are protected from the bots' own mining and pathfinding; gathering never digs up a build.

## 1.0.0 (Minecraft 26.3)

First release of AI Companion: companions that play survival like real people.


- New human-like layer (`GameAI/human`): player-style chat with typing time, persona prompt with live situation and
  recent chat, answering without the bot's name when it's obviously being addressed, instant stance commands and
  small talk, smooth idle head movement, crouch-back greetings, fidgets, quick reactions to server events,
  occasional small talk, and a `/humanlike` command plus `config/ai-companion-humanlike.json`. See `FEATURES.md`.
- Bed sleeping reads the bed rule through the environment-attribute system.
- Fix: crash `NoSuchMethodError: ServerPlayer.swing(InteractionHand)` on 26.3 (the method gained parameters);
  arm swings now go through `Motions.swingArm`, which finds the right signature at runtime.
- No griefing: wood only from natural trees, nothing mined near villagers/golems/traders or placed blocks,
  cobblestone no longer counts as minable stone.
- New: "mine/get/collect [N] <block>" finds, paths or tunnels to, and mines any block (mindcraft-style aliases);
  falls back to branch mining for ores that aren't in sight. New: "strip mine [at y N] [for X]".
- Tools: mining equips the best tool from the whole inventory (not just the hotbar) and uses a bare hand instead of
  a stick; the bot crafts axes and shovels (wood on demand, stone as upgrades) and replaces broken ones.
- LAN: guests who name the bot now get AI replies.
- Fix: a killed bot now leaves the game (the 26.3 fake connection never closed, so it stayed as a ghost).
- Fix: strip mining made a spiral staircase and stopped; steps are now walked with the movement keys, only placed
  blocks themselves (not everything within 3 blocks of a torch) are off-limits, and the tunnel follows the direction
  the asking player faces.
- New: PvP (`/bot pvp <bot> <player> [kit]`, "fight me").
- New pathfinder: modified 3D A* with action-based movement primitives (walk, diagonal, ascend, descend/fall,
  tunnel, dig down, pillar, bridge, swim) whose edge costs are execution time in ticks; incremental search on the
  server thread; key-driven executor with re-planning. Used by come/follow/stay, jobs, deliveries and PvP.
- Fix: "come here" did nothing when the bot was in a hole or underground; it now digs/pillars its way out.
- Fix: mining kept attacking whatever was under the crosshair when the target wasn't visible, digging random
  holes; it now aims at a visible face each tick and stops when obstructed.
- Fix: reasoning models (qwen3 etc.) spent all 1024 tokens thinking and returned nothing; the client now retries
  with thinking disabled and never speaks the model's reasoning.
- Fix: items gathered for a player were crafted away (coal into torches) before delivery; requested items are
  now reserved, and handed items appear at the player's feet instead of falling short and being picked up again.
- Fix: "give me 1 oak log" didn't understand the number.
- Safety: crafting loops are bounded (a full inventory could loop forever), and a watchdog logs what the server
  thread is doing if it stops ticking for 15 seconds.
- Pathfinder uses primitive hash maps (far less garbage per search) and a 40k node budget.
- Diagnostics: logs/ai-companion-diagnostics.log is written directly to disk every 15 s (memory, RAM, what each bot
  is doing), plus uncaught errors and the shutdown, so an exit without a crash report can be traced.
- New: fells whole trees (bottom to top, branches too), comes down from its pillar, replants a sapling.
- Fix: ore drops were sometimes left behind; it now collects each drop right after mining it.
- Fix: after a PvP fight the other player stayed marked as hostile (the bot froze near them).
- Diagnostics: logs what is happening if Java shuts down with a world open, or if the game window freezes.
- New: smelting with a real furnace ("smelt 4 iron", "get me 5 iron ingots", "cook the beef").
- Fix: handed-over items now go straight into the player's inventory (thrown items fell short or under a flying
  player, so "give me" often seemed to do nothing); "give me iron" means ingots/raw iron, not iron tools.
- Fix: no "bro why" / "what's up" chatter from the bot while it's in a PvP fight.
- New: any player can ask for materials ("get me 10 iron", "make me a chest", "i need 32 planks"); the bot
  gathers/crafts them and brings them to that player.
- Building: `build a house` makes a 5×5 starter house (cobblestone or plank walls, plank roof, door facing you,
  crafting table, chest and torch inside), gathering wood and crafting the door/chest/table first. Blocks are
  placed like a player: sneak-click against a solid neighbour.
- Storage: remembers storage chests (placed by it, or `use this chest`), puts mined stuff away after mining jobs
  and when its inventory is nearly full, adds a chest when they're full, and fetches things back out
  (`get me iron from the chest`). `/humanlike store on|off`.
- Pathfinding goes through wooden doors, opening them on the way and closing them behind.
- The mine: "dig down" digs a lit staircase (1 wide, 3 high, torch every 6 steps) from a fixed entrance to y -58,
  clears a lit 3x3x3 landing and waits for what to mine. Ore jobs go down the same stairs to the ore's level
  (diamond/redstone -58, gold -16, lapis 0, iron 14, copper 48, coal in the hills, emerald in mountain biomes only,
  ancient debris 15 in the Nether) and strip mine: a trunk off the stairs, branches every 3 blocks, 24 long, both
  sides, torches every 6. Caves it breaks into are lit, or sealed if there's water/lava. Next trip carries on where it
  stopped; it walks back out the same way. Saved per bot, per world.
- Lit house: wall torches at head height all round the inside and a light-level check (8+) on every floor spot.
- Food farm: a 9x9 plot beside the house with water under a slab, torches all round, carrots/potatoes, else wheat,
  else beetroot; harvests ripe crops, replants, bakes bread, keeps ~32 food and stores the rest.
- Reflexes: out of lava first, into water when on fire, digs out of a block in its head; below 7 health it breaks
  off a fight, blocks the mob off and goes home to heal. Eats at food 14, or when hurt and not full.
- Fix: asking for lapis never worked ("lapis" lost its "s" and matched nothing).
- Creepers and skeletons are dealt with on sight: within 12 blocks and in view, the bot goes after them instead of
  waiting to be shot or blown up. Creepers get hit-and-run (sprint in, one hit, back off out of blast range, repeat;
  never with bare hands, and it sidesteps rather than backing off a ledge or into lava). Against skeletons it raises
  its shield while they draw and sprint-jumps in. Every other mob is still left alone until it attacks.
- Homes are per bot as well as per world: every new bot, in every new world, sets up its own base (`homes.txt` in
  the world folder). All bots can still use each other's chests.
- Fix: bot walked hundreds of blocks and drowned in a new world: home and chest locations were saved globally, so it
  headed for a base from another world across the ocean. They're now saved per world (in the world folder) and it
  won't walk to a base more than 300 blocks away. Swimming: it prefers walking round water, keeps its head up, won't
  fight in water, breaks off a fight for air, and swims to the nearest shore when it's been in the water too long.
- Fix: bot swam off after trees and drowned. It now comes up for air (any time it's under water and low on air it
  stops and swims up, then re-plans), the pathfinder keeps swims on the surface instead of diving, and gathering
  stays within 64 blocks of where it started instead of chasing tree after tree into the distance.
- Bigger houses: 9×9 (7×7 inside, 4 high), with two double chests, a furnace, a crafting table, a bed (wool from
  sheep if needed) and torches inside; the bot walks around inside to stay within reach while building.
- Mining: no more "inventory's full" from junk (it tosses dirt/gravel/diorite/extra cobble and carries on; it empties
  its pockets at home before a strip-mining trip); "mine diamonds at y -58" strip mines; the staircase down stays
  straight and sidesteps water/lava/caves instead of giving up. "give me all the cobble stone you have" works.
- The pathfinder no longer digs through log pillars of buildings (only natural tree logs).
- Chest-aware: the bot remembers what's in its chests and uses it: pockets first, then chests, then mining or
  gathering (house materials, crafting, smelting input/fuel/ingots, deliveries, "give me"). "what's in the chest".
  Smelting in a 1-wide tunnel digs a nook for the furnace instead of giving up.
- Fix: "build a house" never got built. With full pockets the crafted crafting table/chest was dropped, which
  looked like "missing wood", so it chopped trees forever. Crafting now checks for room first, the bot throws
  out junk to make room, and asking again no longer restarts the job. The house now has log corner pillars, a
  cobblestone base row, windows, a slab roof overhang and cap, and torches by the door.
- Home base: on its own the bot first builds a house, then lives there: gathers around it, keeps its loot in chests
  inside (adding chests as they fill), goes home at night and when badly hurt, returns if it strays far.
  `go home`, `/humanlike home on|off`. Wood for the house is gathered in one trip; no more random chests.
- Mobs: no more "hold up, mobs" or stopping for mobs/lava nearby. The bot ignores mobs until one attacks it, then
  fights back with the PvP combat and resumes its job afterwards; it flees home when too hurt.
- Fix: bot stuck wandering deep underground. It now digs a staircase back up to the surface (towards the players)
  after strip mining and mining jobs, before delivering, on "come here", while following, and when regrouping.

---
