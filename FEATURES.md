# AI Companion: features

AI Companion targets **Minecraft Java 26.3** (Fabric Loader ≥ 0.19.5, Fabric API ≥ 0.160.5, Carpet 26.3, Java 25)
and makes each companion feel like another person playing with you.

## What changes in game

**Chat**
- Bot messages look like normal player chat (`<Steve> hey`) instead of rainbow `[Steve]` `/say` broadcasts.
- Each message appears after a realistic delay: a short pause to "read", then roughly the time it takes to type it.
  Long answers arrive as a few separate chat lines.
- Markdown, emoji the Minecraft font can't draw, and "Steve:" prefixes are removed.
- Robot status lines are hidden or reworded ("Running web search...." → "one sec, checking",
  "Terminating all current tasks due to threat detections" → "hold up, mobs"). Connection/status info for the
  owner is shown as a grey `[AI Companion]` notice instead.
- The language model is told it's a friend playing the same game, not an assistant: short casual replies,
  its own opinions, no "How can I assist you?". Every prompt includes what the bot can see right now
  (time, weather, health, food, held item, inventory, nearby players and mobs, what it's doing) plus the recent
  chat, so replies follow the conversation. The bot can choose not to answer (`[silent]`), and it is told to be honest if
  someone sincerely asks whether it's an AI.

**Plays on its own (no AI provider needed)**
- A built-in brain makes the bot actually play: picks up items, punches trees, crafts planks, sticks, a crafting
  table, a wooden then stone pickaxe, a stone sword and torches (crafting happens directly in its inventory with
  vanilla amounts), mines stone and any ores it can see and can harvest, eats when hungry, explores nearby,
  takes the odd break, and stays in the same area as you.
- Chat commands that work without an AI provider: `follow me`, `stay here`, `come here`, `get wood`, `go mining`,
  `mine 10 iron`, `get diamonds`, `collect sand`, `strip mine`, `craft a pickaxe`, `build a house`,
  `store your stuff`, `use this chest`, `get me iron from the chest`, `what do you have`, `give me <item>`, `play`, `stop`.
- Doesn't grief: wood only comes from real trees (natural soil under the trunk, leaves on top), never from
  village houses, log cabins or stripped/bark blocks; nothing is mined within ~28 blocks of villagers, iron golems or
  wandering traders, or right next to placed blocks (planks, doors, glass, beds, chests, torches, paths, farmland…);
  "stone" means natural stone, never cobblestone.

**Mining on command**
- `mine/get/collect/find [N] <block>`: works out which blocks you mean (`iron` → iron ore + deepslate iron ore,
  `wood` → natural logs, `cobble` → stone, `dirt` → dirt + grass, or any block id like `sand`, `clay`, `obsidian`),
  finds the nearest one (searching outward up to 48 blocks, preferring ones it can see), walks there, and if it's
  buried digs a 1×2 tunnel or staircase to it. Then it mines, follows the vein and picks up the drops.
  Default amounts: 6 ores, 16 logs/stone/dirt, 8 other blocks; `a`/`one` = 1, `a stack` = 64.
- Needs a pickaxe? It gets wood (and stone) and crafts one first. Iron or better it tells you it can't craft.
- Mines only what it can see: it aims at a visible face of the block every tick and stops if something moves in
  the way, instead of digging whatever happens to be under the crosshair (this is what used to make it dig holes).
- Full pockets while mining: it throws out junk (dirt, gravel, diorite, tuff, seeds, flowers, extra cobble and
  deepslate) and keeps going, instead of stopping with "inventory's full".
- Uses the right tool: before each block it takes the fastest tool from anywhere in its inventory (moving it to the
  hotbar if needed) and mines bare-handed rather than with a stick when nothing helps. It crafts an axe for wood
  jobs and a shovel for dirt/sand/gravel jobs, upgrades to stone axe/shovel once it has a stone pickaxe, and makes
  a new one when a tool breaks.
- Ores: it only grabs ones lying in the open within ~16 blocks; otherwise it goes down its mine for them (below).
  No more random holes towards buried ore.

**The mine (dig down, then strip mine by ore)**
- `dig down` / `dig a staircase` / `make a mine` / `dig to bedrock` / `dig down to y -40`: it fixes the spot it's
  standing on as the mine entrance (walking out of the house first) and digs a staircase down, 1 wide and 3 high,
  one block down per step, in the direction *you* face, with a torch on the wall at head height every 6 steps. At
  the bottom (y -58 by default) it clears a lit 3×3×3 landing, remembers it, says what it found on the way and
  asks what to mine, and waits there (up to 15 minutes) for the answer.
- Each ore has its level: diamonds and redstone y -58, gold -16, lapis 0, iron 14, copper 48, coal up in the hills
  (96, or just under the surface where it's lower), emeralds only in mountain biomes (peaks, slopes, windswept hills,
  meadows, groves; it tells you to take it to some mountains otherwise), ancient debris y 15 in the Nether only
  (needs a diamond pickaxe).
- `mine diamonds`, `get iron`, `strip mine for gold`, `mine diamonds at y -58`, `strip mine at y 20`: it empties its
  pockets at home if they're filling up, walks to its mine, down the same stairs to that level (digging more steps if
  they don't go that deep yet), then strip mines: a straight 1×2 trunk off to the side of the stairs, with 1×2
  branches every 3 blocks, 24 long, both sides, and a torch every 6 blocks in the trunk and every branch. It mines
  every ore that shows in the walls, then walks back out along the trunk and up the stairs, and puts the loot away.
- Next trip it carries on where the tunnel ended last time. If a tunnel is blocked for good (lava, water) it starts
  a new one on the other side of the stairs next time.
- Caves: when a step breaks into a cave it stops, lights the dark spots at the opening (not in its own way), and
  carries on; if the cave has water or lava in it, it blocks the openings up with cobblestone first (it can dig its
  own seals later, it never digs anyone else's blocks).
- Torches: it takes some along (pockets, then chests, then crafts them from coal/charcoal and sticks, digging a bit
  of coal it can see nearby if needed). Out of torches down there: it says so and carries on.
- The mine is saved per bot, per world (`<world>/ai-companion/mines.txt`). A level above the mine's entrance (a hill,
  "at y 100") gets a one-off staircase instead.
- Safety: never digs a block touching lava or (in tunnels) water, never the block it stands on, never below y -59,
  never bedrock/spawners/chests; re-mines gravel and sand that falls in; clears the line of sight before mining so it
  never breaks something behind the target; waits out fights, eats, and stops when hurt, full or out of pickaxes.
- `stop` / `stop mining` cancels the job.
- With an AI provider, plans from the language model take priority; the brain fills the idle time.
- `/humanlike autoplay off` turns it off.

**Getting around (action-based A\*)**
- The bot plans with a modified 3D A\* over block positions whose edges are movement primitives a player can do:
  walk, sprint diagonally, jump up a block, drop down (3 blocks, or further into water), tunnel through blocks, dig
  straight down, pillar up (jump + place a block under itself), bridge over gaps, and swim. Each edge costs the
  number of game ticks it takes: vanilla walking/sprinting/swimming speeds, vanilla fall time, and for digging the
  real dig time with the best tool it carries. So it takes the fastest route, and only digs through a hill when
  walking round would take longer. Lava, fire, cactus, magma, powder snow and deadly drops are never used; blocks
  next to water or lava, placed blocks and anything in a village are never dug.
- Paths are executed with the movement keys (Carpet action pack): it sprints on straight runs, jumps, digs with
  the right tool, places dirt/cobble to pillar and bridge, swims, and re-plans when something changes. Long trips
  are searched in segments, a few milliseconds per tick, so the server doesn't lag.
- Water: swimming costs more than walking, so it walks round lakes unless that's much longer; in the water it keeps
  its head up, never starts a fight (drowned), breaks off a fight to get air, and if it's been treading water with
  nowhere to go it swims to the nearest shore. Boats aren't used: a boat is steered by the game client, which a
  server-side bot doesn't have, so it can't paddle one.
- Water: it swims across on the surface rather than diving, and whenever it's under water and running low on air it
  stops and swims straight up (then carries on), so it doesn't drown following a path.
- Used for "come here" (it digs or pillars out of holes and caves), following, mining jobs, bringing you things
  and chasing in PvP.
- Getting back up: when it's deep underground (rock overhead) and the people it's with are well above it, it digs a
  staircase up towards them like a player, turning when it hits water, lava or a cave and letting the pathfinder
  find a way up (pillaring, walking round, swimming) when the staircase can't continue. It does this after strip
  mining and mining jobs, before bringing you things, when you say "come here" from the surface, while following
  you, and when it has wandered too far from everyone. If it really can't get out it asks for help. `/bot path <bot> <x y z>` sends it somewhere; `/bot path <bot> stop` stops it.

**Doing things for you (any player)**
- `get me 10 iron`, `bring me wood`, `i need 32 planks`, `make me a chest`, `craft some torches`,
  `can you make a stone pickaxe`: it gathers what's needed (logs, stone, coal), crafts planks, sticks, a crafting
  table, chests, torches, furnaces, ladders and wooden/stone tools, then walks back to whoever asked and throws it
  to them ("here, 10 iron"). If you've left, it holds on to it.

**Trees and drops**
- Chops whole trees: bottom log first, then up the trunk and every branch log (pillaring up for tall trees and
  digging back down), picks up the logs and saplings, and replants a sapling where the tree stood.
- Picks up what it mines: after each ore it waits for the drop and walks onto it, digging a block if the drop fell
  into the hole the ore left.

**Smelting**
- `smelt 4 iron`, `smelt my iron`, `get me 5 iron ingots`, `cook the beef`, `make some glass`: it mines the ore if it
  has to, gets fuel (coal, else wood), crafts a furnace from cobblestone if it has none, puts the furnace down,
  loads it, waits while the real furnace cooks (10 s per item), takes the results, picks the furnace back up and
  hands you the ingots. Works for iron, gold and copper ingots, glass, smooth stone, charcoal, bricks and food.
- `give me iron` gives ingots if it has any, otherwise raw iron, never its iron tools or armour.

**Building a house**
- `build a house` (also hut, shelter, base, cabin): a proper 9×9 house (7×7 inside, walls 4 high) near whoever
  asked: oak-log pillars at the corners, a cobblestone bottom row, plank walls, up to 4 glass windows if it carries
  glass, a plank roof with a slab overhang all round and a raised slab cap, and a wooden door facing you with a torch
  either side. Inside: two double chests on the back wall with a furnace between them, a crafting table, a bed and
  torches. It walks around inside while building so every block is placed from within a player's reach.
- For the bed it uses one it has or one from the chests, otherwise it gets 3 wool by hunting grown, unnamed,
  unleashed sheep nearby (pets and pen sheep are left alone). No sheep: the house goes up without the bed.
- Gets what it needs first, in one go: works out the logs for everything (about 12 for the pillars plus the
  planks and slabs), chops trees only if it's short, then crafts the door, chest, table, planks and slabs. If its
  pockets are full it throws out junk (seeds, flowers, rotten flesh, diorite, extra saplings/dirt) to make room.
- Asking again while it's already building doesn't restart it ("already on it"), and the language model's own
  plan steps can't interrupt it.
- Picks a flat spot within ~14 blocks: not on water, not in a tree, not in a village, nothing somebody built in the
  way. Levels it (digs bumps, fills holes with dirt/cobble), then builds standing in the middle where every block
  is in reach, placing blocks one at a time like a player (sneak-clicking against the block next to it).
- Doors: the pathfinder walks through wooden doors now; the bot opens them on the way and shuts them behind it.
- Lit inside: torches on the inside walls at head height about every 4 blocks (8 in all, clear of the windows and
  the door), then it checks every floor spot inside has block light 8 or more and puts a torch down at the darkest
  one until it does. Existing houses get the same check when it's home for the night.

**Home base**
- When it's playing on its own and has no base yet, the first thing it does is build one: the starter house above,
  near you (or where it is). It works out all the wood the house needs first and gets it in one trip.
- From then on the house is home: it gathers and explores around it, takes its loot back to the chests inside when
  its pockets fill up (not every couple of minutes for one item), heads home when night falls and stays inside
  (door shut) until morning, and goes home to heal when it's badly hurt and has eaten enough to regenerate. If
  it wanders more than ~96 blocks away it walks back.
- Chests go inside the house: the one it builds with, then more along the walls when they fill up (five in all).
  It no longer keeps crafting chests and dropping them wherever you are.
- `go home` / `go back to base` sends it home. `build a house` builds a new one, which becomes home.
- Every bot has its own home, per world: saved in `<world save>/ai-companion/homes.txt` (per bot and dimension). A new
  bot, or any bot in a new world, starts with no home and builds its own base; a base in one world is never "home"
  in another; it won't walk to a base more than 300 blocks away. `/humanlike home off` turns all of this off.

**Food farm**
- Once it has a house, in the daytime it builds a 9×9 farm beside it (never in front of the door): levels it, digs a
  hole in the middle for water from a bucket (made from 3 iron: ingots or raw iron from its pockets or the chests,
  smelted) with a slab on top so nobody falls in, hoes the other 80 blocks into farmland, and puts torches on the
  corners and sides so crops grow at night and nothing spawns on it. No iron: the farm goes in without water.
- Plants carrots or potatoes first (no crafting), then wheat, then beetroot, from its pockets or the chests; no
  seeds: it pulls up grass for wheat seeds.
- Every few minutes (when there's enough ripe) it harvests fully grown crops (wheat/carrots/potatoes age 7,
  beetroot 3), picks them up, re-hoes trampled spots and replants, and bakes bread from the wheat (3 wheat each).
- It keeps about 32 food on it and puts the rest in the chests. Starving with nothing to eat: it goes to the farm.
- `build a farm` / `make a farm`, `harvest the crops` / `tend the farm` / `plant some seeds`.
- Eating: when food is 14 or lower, or when it's hurt and not full (so health comes back).
- Saved per bot, per world (`<world>/ai-companion/farms.txt`). Seeds aren't thrown out as junk once there's a farm.

**Recipe book**
- Every companion remembers the recipes in `config/ai-companion/recipes.txt` (171 to start with: 26.3's poplar
  wood and straw bed, wool stairs, slabs and cushions, workstations, redstone, tools, armour, netherite smithing,
  smelting, blasting and smoking, rails and minecarts, food, end-game items). One per line,
  `RECIPE | ID: ... | TYPE: ... | WORKSTATION: ... | INPUTS: [...] | OUTPUT: 4x minecraft:...`; add your own and
  they're picked up within seconds. It understands grids, `item*3`, `2x2:item`, `1,1,1:item`, `ring:`, `cross:`,
  `... around ...`, `ring:x with center:y`, and the tags `#planks`, `#logs`, `#wooden_slabs`, `#stone_crafting`.
- "how do i make a hopper" / "what's the recipe for a crafter": what goes in, where it's made and the layout.
- "craft me 2 hoppers", "make me an iron pickaxe": it makes it from scratch (gathers, smelts and crafts every part,
  makes a smithing table for smithing) and hands it over, or tells you what it couldn't get.
- Builds use the same recipes, and the language model gets the exact recipes for whatever the chat mentions.

**Starter houses** (six medieval designs)
- Each companion builds its own house from the starter designs (side file `starter: yes`): the first bot the
  medieval cottage, the next the townhouse, then the inn, workshop, hall and manor (from a village by ajmed).
- It picks a flat spot (trees on it get chopped), builds it like any design, and moves in once 60% is up. The
  design itself says where inside is, the middle of the ground floor (where it goes at night), where the barrels
  and chests are and where the bed is; it adds two chests and a bed if the design has none. It keeps building in
  the daytime until the house is done. Can't get far (no trees around)? It lives in the small classic house
  meanwhile. A bot that already had the classic house gets a designed one too.
- `build a house` builds the bot's starter house.

**Trees and wood**
- It knows the twelve woods: which tree each comes from, where it grows and what colour the planks, bark and
  stripped logs are. Ask "what kind of tree is that" (it looks around you), "where do i find cherry wood",
  "what woods are there".
- Builds use the exact wood: it chops spruce for spruce, dark oak for dark oak, and says what a design needs
  ("the wood is spruce (medium brown) and dark oak (deep chocolate brown)").
- When a wood can't be had (crimson and warped only grow in the Nether; no tree of that kind near), it uses the
  closest-looking one it can get (colour compared the way the eye sees it), says so, and swaps the real wood in
  when you put some in a chest. Same for blocks it can't make (prismarine -> mossy or plain stone bricks, calcite ->
  diorite, stained glass -> glass). Flowers, leaves, vines and carpets are decoration: put in if it has them, and a
  build without them still counts as done.

**Building from schematics** (farms and anything else)
- Every `.schem` (WorldEdit, Sponge v1-3), structure-block `.nbt` and `.litematic` in
  `config/ai-companion/schematics` is something the companions can build. Four designs come with the mod: a sugar
  cane farm, a cactus farm, a bamboo farm and a villager-pod iron farm by F3deMatt25 (deleting one keeps it gone).
  For the iron farm, bring 3 villagers to the beds at the top once it's built (the bot reminds you).
- `build the iron farm` picks a flat, free spot beside the house with the design's front towards it (or around the
  bot when there's no house); `build it here` puts it two blocks in front of you, facing you; `build it at x y z`
  (optionally `facing north`) puts the ground layer's corner there.
- It works out what's missing and gets it: pockets, then the chests, then crafting, smelting (stone, glass, iron)
  and digging or cutting (dirt, sand, gravel, cobblestone, coal, logs, sugar cane, cactus, bamboo). It knows the
  recipes for the usual building blocks: planks, stripped logs, wooden stairs, slabs, fences, gates, trapdoors,
  doors, signs, buttons and pressure plates, stone bricks and their slabs, stairs, walls and chiseled bricks, glass
  panes, chests, ladders, torches, lanterns, chains, hoppers, campfires, and redstone parts (torches, repeaters,
  comparators, pistons, observers, droppers, levers). Any kind of wood will do for a wooden block. What it can't
  get (beds, wool, banners, nether blocks...) it lists, builds the rest, and finishes later.
- `note:` in the side file is said when the build is finished (the iron farm uses it for the villagers).
- It clears the area top down, then places the solid parts bottom up, then water and lava (filling a bucket at the
  nearest water, or at its own farm once that refills by itself), then torches, redstone, rails and plants. Plain
  blocks go in with a right-click; anything that points a certain way (pistons, observers, hoppers, stairs,
  repeaters...) uses the item up and is set exactly as the design says. Doors, beds and tall flowers get both halves.
- It won't tear down someone's build or empty a container: if a new build would, it says so and leaves it.
- Harvests what it built: cane, cactus and bamboo above the bottom block, ripe crops, melons and pumpkins, then
  replants. It does it by itself in the daytime when enough has grown, or when asked (`harvest the cane farm`).
- Chat: `what can you build`, `build a cactus farm`, `what do you need for the iron farm`, `continue the build`,
  `harvest the bamboo`, `forget the iron farm`.
- A `<name>.txt` next to a file can set `name:`, `aliases:`, `about:`, `ground:` (which layer sits at ground level)
  and `front:` (north/east/south/west, default south).
- Builds are saved per world (`<world>/ai-companion/builds.txt`); finished ones aren't mined or tunnelled through.

**Prices and money** (diamonds)
- Every companion remembers the price list that ships with the mod (273 items in ten sections: 26.3 additions, wood,
  stone and terrain, ores and metals, workstations, redstone, mob drops, food, tools and armour, smithing templates
  and trophies). Your own prices go in `config/ai-companion/prices.txt`, one per line in the same format
  (`PRICE | ID: minecraft:bread | RATIO: 16 = 1 DIA | UNIT_DIA: 0.0625 | RARITY: T3_REFINED | CAT: food`); a line there
  wins over the mod's for that item, and changes are picked up within seconds. Anything not listed is worth
  its ingredients (from the builder's recipes and the recipe book) plus 10%.
- "how much is a hopper", "what are diamonds worth", "price of an elytra", "how much for 64 cobblestone".
- "sell me 16 bread" / "can i buy a stack of iron" / "i want to buy 3 ender pearls": it quotes the list price
  (from its pockets and its shop's stock); say "deal" (or "no") within two minutes. It fetches the goods from its
  shop if need be, comes over, takes the diamonds from you and hands the goods over.
- "buy my 64 cobblestone" / "do you want to buy my iron" / "i want to sell 10 gold": it offers 80% of the list price,
  and on "deal" takes the items (the real stacks, enchantments and all) and pays. It won't buy used gear or shulker
  boxes. A quote is for the player who asked; anyone else's "ok" goes on as normal chat.
- Only whole diamonds change hands; the fractions go on your tab (any companion honours it): "what's my tab",
  "cash out" pays out the whole diamonds. "what do you sell" lists its stock and prices; "how many diamonds do you
  have".
- The companions keep their diamonds on them as their wallet (`/humanlike economy off` turns all of this off).
- The language model gets the exact price of anything the chat mentions.

**The town** (built together)
- When every companion has a finished house (and they've been around for 20 minutes), one of them proposes a town,
  picks a dry, flattish spot clear of everyone's builds near their homes, names it and lays it out; the others
  chime in with what they'll do. Or tell them: "let's build a city", "build a town here", "build a city called
  Stonehaven". `/humanlike city off` stops them doing it by themselves.
- The layout: a 15x15 plaza with a fountain in the middle, four avenues (5 wide, stone brick edges, cobblestone,
  street lamps every 8 blocks, following the ground a block at a time, bridging water), a warehouse, a shop for
  each companion (at least three), three community farms, a temple at the end of the north avenue, a mall at the
  end of the east one, an amphitheatre at the end of the south one, and four town houses. Every building faces the
  road. It goes up in that order, each road reaching a building before the building does.
- Each companion takes the next piece nobody's working on (its own shop is its to build) and builds it like any
  design. What it can't get, it asks the others for in chat ("Ovi, J1mbers: anyone got 96 stone bricks? need it for
  the temple"); a free companion takes the request, gets or makes it and hands it over or leaves it in the
  warehouse. The warehouse's chests are shared storage once it's built.
- The town's farms are tended by whoever's around (one at a time). A farm counts as built once all that's left is
  planting; it gets seeds by pulling up grass.
- The temple has an offering chest on the altar. Once a day every companion takes some food (bread, wheat, apples,
  carrots, cooked meat...) up to it, keeping enough to eat. "go make an offering" sends one now.
- Shops: every 12 minutes or so a companion puts its loot on its shelves (ores, ingots, food, mob drops, gear it
  doesn't use; not the building blocks the town needs, and never what it keeps on hand to eat), puts any overflow
  on a stall in the mall (the mall is a shared market: whichever companion sells from it keeps the takings) and takes the
  diamonds out of the till (the barrel on the counter). A starving companion with no food, or one short of
  something for a build, buys it off another companion's shelf (spending at most half its diamonds) and leaves the
  diamonds in the till.
- Chat: "how's the town going", "where's the temple / mall / your shop", "work on the town" (also gives pieces that
  got skipped another go), "forget the town" (what's built stays; they won't start another one by themselves).
- Saved per world: `<world>/ai-companion/city.txt` (the plan and how far along it is), `economy.txt` (tabs, takings).
  The designs are `city_plaza`, `city_temple`, `city_amphitheatre`, `city_shop`, `city_mall`, `city_farm` and
  `city_warehouse` in the schematics folder (made by `tools/make_city_schematics.py`; edit them and new towns use
  your versions).

**Chests / storage**
- The chest in the house becomes the storage chest. `use this chest` (while looking at a chest or barrel) makes that
  one storage. Storage chests are remembered per world, in `<world save>/ai-companion/chests.txt`.
- After a mining job (collecting for itself, strip mining) and whenever its inventory gets nearly full (30 of 36
  slots) while playing on its own, it walks to the storage chest and puts everything away except what a player
  keeps on them: tools, weapons, armour, food, torches, buckets, a crafting table, a furnace, a chest, and a few
  blocks, sticks, coal and planks. No chest yet? It crafts one and puts it down near you. Chest full? It places
  another next to it (making a double chest).
- `store your stuff` / `put your items in the chest` / `empty your inventory`: does it now.
- `get me 5 iron from the chest`, `grab the diamonds out of our chest`, `bring me a stack of cobblestone from
  storage`: takes it out and hands it to you.
- It knows what's in its chests (it looks whenever it's near them and remembers the rest) and uses that stock:
  whenever it needs something (blocks and wood for a house, planks/cobblestone/coal for crafting, raw iron and
  fuel for smelting, items you ask for) it checks its pockets first, then the chests, and only goes out to mine,
  chop or gather what's still missing. "get me 3 iron ingots" brings the ingots from the chest if there are
  some instead of smelting new ones; "give me X" fetches it from the chest when it isn't carrying any.
- `what's in the chest` / `check the chests`: tells you what's stored.
- `/humanlike store off` turns automatic storing off (asking still works).

**Staying alive (checked every tick, before anything else)**
- In lava: drops everything and gets out towards the nearest solid ground, sprinting and jumping.
- On fire: runs into water if there's some within 8 blocks, otherwise steps out of the fire.
- A block in its head (gravel fell on it): mines it out.
- Under 7 health (35%) in a fight: breaks off, throws a block or two between itself and the mob (not underground,
  where that would wall up its own tunnel), and heads home to heal (or just away).

**Mobs**
- Creepers and skeletons are the exception: when one is within 12 blocks and it can see it, the bot goes after it
  (quietly). Creepers: sprint in, one hit, back off 5+ blocks, repeat; it won't take one on without a sword or axe,
  and sidesteps instead of backing off a drop or into lava. Skeletons: shield up while they draw the bow, sprint-jump
  in, hit between shots. A creeper or skeleton it can't reach is left for 30 s.
- Other mobs: it doesn't react to them, or talk about them, just because they're around. The moment one hits it (a zombie
  swings, a skeleton's arrow lands, a spider jumps it) it fights back: puts on armour, pulls out its best weapon,
  closes in and fights like in PvP (timed hits, crits, strafing, shield), then goes back to what it was doing.
  Whatever it was doing waits during the fight and carries on after.
- If it can't reach the mob (behind a fence, across water) and nothing's happened for 15 s, it lets it go. When
  it's badly hurt with nothing to eat it breaks off and runs home (or just away) instead of dying.
- The old "threat detected, stopping everything" reaction to lava or mobs nearby is gone.

**PvP**
- `/bot pvp <bot> <player> [none|iron|diamond|netherite]` starts a fight (the optional kit hands the bot a sword,
  axe, armour, shield, steak and golden apples); `/bot pvp <bot> stop` ends it. In chat: `fight me`, `pvp me`,
  `1v1 me`, `duel me`; `gg`, `i give up` or `stop` ends it.
- Fights like a player: puts on its best armour (totem or shield in the off hand), holds its best sword/axe, sprints
  in, strafes, hits on the weapon's cooldown, sprint-resets for knockback, jumps for crits, blocks with a shield
  between hits, backs off to eat when low, follows you if you run, says gg at the end. Two bots can fight each other.
- A bot that dies drops its stuff and leaves the game (spawn it again with `/bot spawn`).

**Being talked to**
- Works without saying the bot's name when it's obviously meant for the bot: you're standing near it, it's just
  the two of you on the server, or you were already talking. Unnamed messages are answered only if they look
  directed (questions, "you/we/let's…", or mid-conversation).
- "follow me", "stay here", "come here", "go explore" work with or without the name, instantly.
- Reflex small talk ("hi", "ty", "gg", "bye", "sorry") gets an instant reply without calling the language model.
- Works on dedicated servers too (the original only listened on the client).

**Body language** (only while the bot is idle, so it never fights navigation, mining, combat or eating)
- Smooth head turns with a fast start and slow settle, plus a slight idle sway, instead of snapping 30×/second.
- Looks at whoever is talking to it, otherwise at nearby players most of the time, glancing away at moving mobs
  or the scenery now and then; looks around when alone.
- Crouch at it twice and it crouches back.
- Occasional fidgets: a small hop, an arm swing, a glance down.
- Turns to look at you if you hit it ("ow wtf").

**Reactions and small talk** (rate-limited and a little random, like a person)
- Quick lines for joins/leaves, deaths (tailored to lava, falls, creepers…), advancements, the Nether,
  finding diamonds, gifts, nightfall, rain, low health and hunger.
- Occasional unprompted small talk when a player is near and chat has been quiet for a while.
- Following keeps a comfortable ~6 block distance instead of walking into you.

## Settings

`config/ai-companion-humanlike.json`, or in game (op / single-player owner):

```
/humanlike                     show settings
/humanlike typing on|off       typing delay
/humanlike chatstyle on|off    <Name> chat (off = old /say style)
/humanlike unnamed on|off      answer chat that doesn't use the bot's name
/humanlike body on|off         head movement, crouching back, fidgets
/humanlike reactions on|off    "gg", "rip", "ow"...
/humanlike smalltalk on|off    unprompted remarks
/humanlike statuslines on|off  show the raw robot status lines again
/humanlike store on|off        put mined stuff in the storage chest automatically
/humanlike home on|off         build a base, keep loot there, go home at night
/humanlike city on|off         found and build a town together by themselves
/humanlike economy on|off      diamonds as money: trading, shops, prices
/humanlike reload              re-read the config file
```

## Code

The companion behaviour lives in `io.github.yudiiee.aicompanion.GameAI.human`:
`HumanChat` (outbound chat), `HumanChatListener` (server-side chat input), `HumanBehavior` (per-tick body language),
`HumanReactions` (quick lines), `HumanPersona` (system prompt), `SituationSnapshot`, `ConversationMemory`,
`HumanConfig`, `HumanLikeCommand`, `SurvivalBrain` (plays on its own, runs jobs), `MiningSkills` (collect any block,
tunnelling, strip mining), `Protection` (no-grief rules), `Motions` (arm swing that works across 26.x versions).

It hooks into: `AICompanion` (registration, hit reaction), `AICompanionClient` (skip local commands),
`ChatUtils` (all bot speech goes through `HumanChat`), `AutoFaceEntity` (idle gaze handed over),
`WorldEventListener` (quick reactions instead of LLM goals), `CompanionController`, `ProximityTracker`,
`ItemHandoffHandler` (casual lines), `LLMServiceHandler` / `RAG2` / `PromptBuilder` (persona prompt),
`NearbyBedSleepController` (reads the bed rule through the environment attribute system).

Town and money: `PriceBook` (the price list), `Economy` (trading, tabs, shops), `CityPlan` (the layout), `City` (the
town: founding, building it together, helping each other, the temple).

Build: `./gradlew build` → `build/libs/ai-companion-1.5.0+26.3.jar`.
