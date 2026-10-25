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
  when the road is at least 9 blocks above the ground. Small dips and bumps are filled or cut.
- A road is refused when it is longer than 900 blocks, has a bridge longer than 240, or a tunnel longer than 200.
- The planner is pure code with no Minecraft types, tested in `RoadPlanTest`.

## Network and laying (`game/RoadNetwork`, `game/RoadBuilder`)

- The network finds villages from the structure placement grid before their chunks exist (an idea from RoadWeaver,
  `third_party/roadweaver/NOTICE.md`), around players within `roadScanRadius`. It plans roads on a background thread and
  saves them in the world (`mobtalk_roads`).
- A road is laid chunk by chunk, once all nine chunks around a chunk are loaded. `/roads load` holds the chunks loaded,
  nine at a time, until the road is done.
- Each road has one palette, picked by the biomes it crosses (stone and cobblestone, or sandstone in deserts).
- A road stops where the village's own streets begin, at the first piece of the village structure. If it meets the village on a
  bridge or in a tunnel, it continues to the first point on the ground.
- The road is paved five blocks wide, with a curb on each side. Ground above a 45° line from the curb is dug away, and ground
  below one is banked up with turf. Trees over the road are cleared. A bridge deck stands on piers every 14 blocks, with a wall
  for a railing. A tunnel is dug under a stepped brick vault with brick walls. A column with anything built in the way (a house,
  a chest, a player's build) is left alone.
- A lamp stands every `roadLampSpacing` blocks: a wall base, a fence post and an arm with a hanging lantern (after RoadWeaver's
  `LamppostDecoration`). Bridges have lanterns on the railing, and tunnels have them under the vault.

## Traffic (`game/RoadTraffic`, `game/RoadCartEntity`)

- Each road carries `roadTraffic` trips (0 turns traffic off). A trip is a two-wheeled cart, a covered wagon or a horseman. A
  wagon may have a horseman as escort. Trips are virtual and saved in `mobtalk_road_traffic`.
- A trip takes shape only within 96 blocks of a player and on a laid chunk. It vanishes beyond 128 blocks and goes on moving
  virtually. Its entities carry the tag `mobtalk_road`, are not saved with the chunk, and cannot be hurt or pushed.
- The horse walks in the right-hand lane, 1.2 blocks from the centre. The cart follows at the length of its shafts and tilts
  with the slope (at most 15°). At each end the trip rests for 30 seconds, then turns round on a half circle.
- A trip waits while a player stands ahead in its lane, or while another trip is just ahead of it.
- Villagers sit in the carts: two in a cart, four on the benches of a wagon. The horseman is a villager with leather armour and
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
night views of the lamps. It uses no LLM and no voice.
