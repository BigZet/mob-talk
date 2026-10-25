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
- A road stops where the village's own streets begin, at the first piece of the village structure. If it meets the village on a
  bridge or in a tunnel, it ends where it last stood on the ground. Where that is gets worked out from the village's
  structure start before any chunk of the road is laid.
- The road is paved five blocks wide, with a curb on each side. Ground above a 45° line from the curb is dug away, and ground
  below one is banked up with turf. Trees over the road are cleared. A bridge deck stands on piers every 14 blocks, with a wall
  for a railing. A tunnel is dug under a stepped brick vault with brick walls. A column with anything built in the way (a house,
  a chest, a player's build) is left alone. Lamp posts and lanterns are never dug away by a later chunk.
- Where two roads overlap at about the same height (within 2 blocks), neither lays its curb, railing, bank or lamp on the other's
  paving and curb, so one paved way is left with no railing across it.
- A trunk the road cut through or dug the ground from under is felled: every cluster of logs next to a block the road cleared
  that stands on nothing (and is no house's beam) is taken away. Leaves left hanging are then taken away at once, in and up to
  8 blocks round the chunk, so they do not rot and drop saplings on the road.
- The road always ends before the village. A bridge or tunnel that meets the village ends on the last ground up to 24 points back.
- Water or lava next to a block the road dug out is walled off with the road's lining stone, so it cannot run onto the road.
- Grass, flowers and seeds the earthworks knock loose do not lie about as items: items dropped that very tick in and around the
  chunk are removed.
- A lamp stands every `roadLampSpacing` blocks: a wall base, a fence post and an arm with a hanging lantern (after RoadWeaver's
  `LamppostDecoration`). The arm is the post's neighbour straight toward the road, never a diagonal one (a fence does not join
  across a corner). Bridges have lanterns on the railing, and tunnels have them under the vault.

## Villages (`game/RoadVillages`)

The village's houses are never touched. Once the chunk of each road's last point is laid and the whole village is loaded:

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
  with the slope (at most 15°). At each end the trip goes into the village's station and is out of sight for 30 to 60 seconds; then it
  sets out back the other way. Trips do not queue at the ends.
- A trip waits while a player stands ahead in its lane, or while another trip is just ahead of it.
- Villagers sit in the carts: two in a cart, four on the benches of a wagon. They sit as low as a player would (a villager has no
  seat offset of its own). The horseman is a villager with leather armour and
  an iron sword. He takes the `mobtalk:cavalry` profession when village defence registers it.
- The cart and wagon models are ported from NiftyCarts (MIT, `third_party/nifty-carts/NOTICE.md`). Their textures are built at
  runtime from vanilla plank, log and wool textures.

## Settings

- F7 / F9, group «Дороги между деревнями»: `roadScanRadius`, `roadMaxLength`, `roadLampSpacing`, `roadTraffic`.

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
night views of the lamps. It checks that every lamp's arm joins its post and that no log hangs in the air by the road. Then,
in the world's own villages, it holds a village two or more roads come to and one only one road comes to until their streets
and station are done, and photographs each from the road and from above. It uses no LLM and no voice.

In the cloud the same test runs under `xvfb-run` with CTOV and Lithostitched in `run/mods` on a world made from Dmitriy's seed,
to see the roads meet CTOV villages (their streets are the `roads` pool).
