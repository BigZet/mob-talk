# Roads between villages

Mob Talk joins nearby villages with roads. The roads are mostly straight with gentle bends, and lamps light them day and night.
Where the land rises or falls steeply, the road goes through a tunnel or over a bridge. Horse-drawn carts and covered wagons
with villagers in them, and horsemen, travel back and forth along them. It all works in single player and on the server.

## Planning (`core/RoadPlan`)

- The planner tries several centrelines between the two villages: a straight line and gently bent Catmull-Rom curves
  (`Rules.variants`). It keeps the one with the least earthwork.
- The height profile follows the ground, smoothed and limited to a grade of 0.33. The road keeps both ends at their village's
  level: each point is clamped into the range reachable from both ends at that grade, so a village on a plateau is reached at
  its own height and not in a tunnel below it.
- A point lies in a **tunnel** when the ground is at least 10 blocks above the road. It lies on a **bridge** over water, or
  when the road is at least 7 blocks above the ground. Small dips and bumps are filled or cut.
- A ravine or a deep hollow is crossed at the height of its rims: where the straight line from rim to rim (within 96 blocks and
  the grade) runs at least 4 blocks, and a tenth of its length, above the road, the road is lifted to it, onto a bridge or a bank.
- Where the road goes into a tunnel anyway, it does not climb the hill first: the lowest straight line through the hill (its ends up
  to 192 blocks before and after the tunnel, the tunnel no longer than allowed) lowers the road over the hump.
- A road is refused when it is longer than 900 blocks, has a bridge longer than 240, or a tunnel longer than 200.
- The road goes **round** what stands in its way: the two villages' own boxes and every structure start along the straight line
  between its ends (an outpost, a ruined portal, a hut; never a village, which the network knows already, and never one far
  underground or vaster than 160 blocks). The straight way is bent at the corners of the first box it enters, 8 blocks off the
  box (up to 16 corners), and the centrelines are curved through those corners. A line that still crosses a box costs a thousand
  blocks of earthwork more, so it is kept only when no other will do. The chunks where a structure set could start something
  along the line are read at the structure stage (made if need be) on the game thread, three per tick, while the planning thread
  waits, each kept at that stage by a ten-second ticket (`mobtalk_structures`) so the game's loader finishes with it in good
  order; never while the server stops — a plan the shutdown interrupts is simply drawn up again next time.
- **The way out.** The game of 1.21.1 can lose count of its claims on a chunk dropped half-made (a chunk loader that never runs
  its last step leaves the holder's reference count above zero, or its saving future never done), and then its unloading on the
  way out waits for ever at "Saving worlds". The roads' chunk reading and the villages held for the self-test's views make this
  likely, so two mixins stand guard (`ChunkClaimsAtShutdownMixin`, `ChunkSavingAtShutdownMixin`): while the server stops, a
  holder still stuck twenty seconds after it was first found so is let go — its count reads as zero and it is reported savable,
  the chunk is saved and dropped — with a warning `чанк x,z отпущен при остановке` in the log. The count itself is left alone, so
  a late release neither fails nor matters. In normal play the mixins do nothing.
- A road between junctions is held at the junction's height at both ends: the first and last stretch is level, on the ground.
- The planner is pure code with no Minecraft types, tested in `RoadPlanTest`.

## Network and laying (`game/RoadNetwork`, `game/RoadBuilder`)

- The network finds villages from the structure placement grid before their chunks exist (an idea from RoadWeaver,
  `third_party/roadweaver/NOTICE.md`), around players within `roadScanRadius`. It plans roads on a background thread and
  saves them in the world (`mobtalk_roads`).
- A structure set holds several villages for one spot and the world picks one of them by its seed (CTOV adds its own villages
  to the vanilla set, and they take the vanilla ones' place). Which one stands there, and whether one at all, is read from the
  chunk's structure stage, made on the spot if need be, as `/locate` does. A village key that names a village the world did not
  put there resolves to whichever village stands in that chunk.
- A road is laid chunk by chunk, once all nine chunks around a chunk are loaded. `/roads load` holds the chunks loaded,
  nine at a time, until the road is done.
- Each road has one palette, picked by the biomes it crosses (stone and cobblestone, or sandstone in deserts).
- A road runs from junction to junction (below) and is laid to its very ends. A road laid before the junctions came (or with
  `roadBypass` off) stops where the village's own streets begin, at the first piece of the village structure; if it meets the
  village on a bridge or in a tunnel, it ends where it last stood on the ground. Where that is gets worked out from the village's
  structure start before any chunk of the road is laid.
- The road is paved five blocks wide, with a curb on each side. Ground above a 45° line from the curb is dug away, and ground
  below one is banked up with turf; a hollow that holds water is left as it is (the road's own fill makes its quay). Trees
  over the road are cleared. The road's columns are found from rows across the road every half block along it (a row through
  each point alone left single blocks out on a diagonal, and pillars of ground stood in tunnels and cuttings). A bridge deck stands on piers every 14 blocks, with a wall
  for a railing. A tunnel is dug under a stepped brick vault with brick walls. A column with anything built in the way (a house,
  a chest, a player's build) is left alone; what worldgen made is the road's to cut, including a taiga's mossy boulders, stone
  with silverfish in it, a geode's buds, fossils and raw ore. Lamp posts and lanterns are never dug away by a later chunk.
- Where two roads overlap at about the same height (within 2 blocks), neither lays its curb, railing, bank or lamp on the other's
  paving and curb, so one paved way is left with no railing across it.
- The strip beside the road is cleared of trees: every trunk (logs, mushroom stems and caps) whose foot stands within
  `roadTreeStrip` blocks of the curb, not far above or below the road, is felled whole, with its bee nest, unless it is part of
  a village piece or something built leans on it. Trees over a tunnel's roof stay.
- A lump of ground or stone left standing over the road (a pillar the digging went round, the stub of a hill between the road and
  its cut) is taken away: any body of natural blocks above the road's feet, within the road's reach, that touches nothing outside
  the chunk and its margin, no built block and no village piece, and is not bigger than 400 blocks. Where ground still hangs over
  the paving at the clearance height, a brick ceiling is laid so nothing falls.
- A trunk the road cut through or dug the ground from under is felled too: every cluster of logs next to a block the road cleared
  that stands on nothing (and is no house's beam) is taken away. Leaves left hanging are then taken away at once, in and up to
  8 blocks round the chunk, so they do not rot and drop saplings on the road.
- A bee nest counts as part of the tree, not as something built: the road is laid under it and the nest goes with the trunk.
- A bank is never built under ground that is taller than the road's reach, and the ground over a column is only dug down to the
  sky where no tree stands in the heightmap's way (the tree's trunk is felled first).
- The road always ends before the village. A bridge or tunnel that meets the village ends on the last ground up to 24 points back.
- Water or lava next to a block the road dug out is walled off with the road's lining stone, so it cannot run onto the road.
- Grass, flowers and seeds the earthworks knock loose do not lie about as items: items dropped that very tick in and around the
  chunk are removed.
- Odds and ends lie on the strip beside the road (`game/RoadDecor`, blocks in `game/RoadContent`; off with `roadDecor`): about
  one piece every 7 blocks on one side or the other, chosen for the biome of the spot — grey pebbles, mossy pebbles in forests
  and swamps, sandy pebbles and dead bushes in deserts, badlands and savannas, a cairn in the mountains and the snow, a fallen
  log of the forest's wood (two or three blocks, along the road) — and a striped verst post («верстовой столб») every hundred
  blocks on the right. Each piece lies on the bank's turf; the paved way, the curb, the lamps and the junction's square are kept
  clear. A log, a cairn or a post wants open ground round it (a log wedged into a terrace looked like a stray block): where the
  bank is stepped, pebbles lie instead. The models are built of vanilla block textures (the post's stripes are the mod's own); players can place the pieces too.
- A lamp stands every `roadLampSpacing` blocks: a wall base, a fence post and an arm with a hanging lantern (after RoadWeaver's
  `LamppostDecoration`). The arm is the post's neighbour straight toward the road, never a diagonal one (a fence does not join
  across a corner). Bridges have lanterns on the railing, and tunnels have them under the vault.

## Villages (`game/RoadVillages`)

The village's houses are never touched. Since 2026-10-25 (Dmitriy: «дорога не упирается прям в деревню, но проходит близко к
ней … есть сход в деревню — тропа») the roads do not run into the village:

- Each village has a **junction** («развязка») 12 blocks out from its box, chosen with its first road: round the village from the
  side facing that road outward, on dry and level ground, with a street of the village within 64 blocks and near its height, not
  in another village. Every road of the village ends there, at the junction's height, and the planner routes it round the village's
  box. The spot is saved with the village (`mobtalk_roads`), so later roads end at the same one.
- Once the junction's chunk is laid by each of its roads and the land to the nearest street is loaded: a paved square 11 wide with
  a curb round it (open where a road comes in), a lean-to shelter with a bench and lanterns on the side no road or trail leaves by,
  a hitching post and hay across from it, a lamp at each corner; and the **trail** («тропа»): the shortest walk over open natural
  ground from the square to the nearest street (a step of one block at most, round the houses), paved three wide with a dirt path
  down the middle and gravel and coarse dirt at the sides (sandstone in the desert), a lantern every 12 blocks beside it.
- Trips change roads at the junction or turn round there.

A village the roads were laid into before the junctions came (or with `roadBypass` off) keeps the earlier way. Once the chunk of
each road's last point is laid and the whole village is loaded:

- From each road's end a way 3 wide is paved over open natural ground to the village's nearest street (up to 40 blocks). A
  street is a piece of the village's `streets` pool (vanilla) or `roads` pool (CTOV), and its blocks are the path, sandstone,
  cobbles, bricks, mud, coarse dirt and the like, read off the ground under the heightmap; the grass CTOV mixes into a road
  is crossed as open ground.
- A village that two or more roads come to has its own streets paved with the road's stone on the shortest way between the
  entries, the whole width of each street, with a street lamp every 20 blocks beside it. The highway runs through the village along
  its main street, and the village's lanes turn off it.
- A village only one road comes to gets a **station** where the road ends: a paved square 9 blocks long, a lean-to shelter with a
  bench and lanterns on one side, a hitching post, a hay bale and a lamp on the other.
- It is done again when another road comes to the village (`mobtalk_roads`, `villageWork`).

## Branches

A village that a road passes within 220 blocks of, and whose own roads do not come within 60 blocks of that spot, gets a branch:
a road from the village to the nearest plain stretch of the passing road (at least 8 points of plain road each way, away from its
ends). The branch's end is held at the passing road's height and kept flat for the last 5 blocks, and it is laid up to that
road's curb, so the highway itself is not touched. Its last 6 points have no lamps. Branches carry no traffic.

## Traffic (`game/RoadTraffic`, `game/RoadCartEntity`)

- Each road carries `roadTraffic` trips (0 turns traffic off). A trip is a two-wheeled cart, a covered wagon or a horseman. A
  wagon may have a horseman as escort. Trips are virtual and saved in `mobtalk_road_traffic`.
- A trip takes shape only within 96 blocks of a player and on a laid chunk. It vanishes beyond 128 blocks and goes on moving
  virtually. Its entities carry the tag `mobtalk_road`, are not saved with the chunk, and cannot be hurt or pushed.
- The horse walks in the right-hand lane, 1.2 blocks from the centre. The cart follows at the length of its shafts and tilts
  with the slope (at most 15°). At a junction the trip goes on along another of the village's roads (one laid somewhere already),
  picked at random; where there is none, or at a village the road runs into, it is out of sight for 30 to 60 seconds and then sets
  out back the other way. Trips do not queue at the ends. A road gets no more trips than `roadTraffic` per road of the network.
- A trip waits while a player stands ahead in its lane, or while another trip is just ahead of it.
- Villagers sit in the carts: two in a cart, four on the benches of a wagon. They sit as low as a player would (a villager has no
  seat offset of its own). The horseman is a villager with leather armour and
  an iron sword. He takes the `mobtalk:cavalry` profession when village defence registers it.
- The cart and wagon models are ported from NiftyCarts (MIT, `third_party/nifty-carts/NOTICE.md`). Their textures are built at
  runtime from vanilla plank, log and wool textures.
- A villager or horse travelling with a trip takes no harm from a wall: the trip is drawn along the road's line, and where
  the line passes through a block the road left standing the passengers would otherwise smother in it.

## Settings

- F7 / F9, group «Дороги между деревнями»: `roadScanRadius`, `roadMaxLength`, `roadLampSpacing`, `roadTraffic`,
  `roadTreeStrip` (how far from the curb trees are felled, 0–8, default 4) and the toggle `roadBypass` («Трасса обходит
  деревню»: junctions and trails; off, the roads run into the villages as before) and the toggle `roadDecor` («Мелочи у
  дорог»: the pieces beside the road).

## Commands (operators)

- `/roads status`, `/roads list`, `/roads near`: the roads, the nearest road, and its first bridge and tunnel.
- `/roads scan [x z]`: look for villages now. `/roads build x1 z1 x2 z2`: plan a road between two spots.
- `/roads load <id>`: build a road now, loading its chunks. `/roads relay`: lay the loaded chunks of the roads again.
- `/roads traffic`: the trips on the roads. `/roads traffic bring <id> [cart|wagon|rider [x z]]`: bring a trip to the road's
  point nearest you (or to x z).

## Self-test

`scripts/run-selftest.ps1 -Only roads` (`client/SelfTestRoads`) plans a road in the test world, lays it, and photographs it:
from above, along the road, and at its first bridge or tunnel. Then it brings a wagon, a cart and a horseman, and checks that
each one has passengers, that the horse walks ahead at the shafts' length, and that the trip moves at a walk. It also takes
night views of the lamps. It checks that every lamp's arm joins its post, that no log hangs in the air by the road, that nothing
stands in the paved way (no pillar, no stub of ground, no trunk) and that no tree is left in the strip beside it. Then, in the
world's own villages, it holds a village two or more roads come to and one only one road comes to until their junction, trail and
shelter are done (or, for roads laid into the village, their streets and station), checks the junction stands outside the village
and the trail reaches a street, and photographs each from the road, from above and along the trail. Then
`-Dmobtalk.selftest.roads=x1,z1,x2,z2;…` names further lines to plan, lay and check the same way (through mountains, a swamp,
the badlands), each photographed from above and along at its first tunnel or bridge. A line the planner refuses (a bridge
longer than it allows) is logged as refused, not counted as a failure. The vehicle checks wait for a few server ticks after the
vehicle takes shape (a second of the clock may hold no tick on a lagging server). The checks of the paved way and of the strip take the
road's columns from the builder's own sampling, so they look at exactly the blocks the road claimed. It uses no LLM and no voice.

In the cloud the same test runs under `xvfb-run` with CTOV and Lithostitched in `run/mods` on a world made from Dmitriy's seed,
to see the roads meet CTOV villages (their streets are the `roads` pool).
