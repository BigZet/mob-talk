# RoadWeaver in Mob Talk (roads between villages)

Mob Talk's village roads (`core/RoadPlan`, `game/RoadBuilder`, `game/RoadNetwork`) are its own code. **RoadWeaver**
(https://github.com/shiroha-233/RoadWeaver), Copyright (c) 2025 shiroha-233, MIT License, served as a reference for ideas:

- the street lamp's shape (a wall base, a fence post and an arm with a hanging lantern) follows RoadWeaver's `LamppostDecoration`;
- finding villages from the structure placement grid before their chunks exist, and laying a road chunk by chunk as the chunks load.

No RoadWeaver source file is included. Reference revision: `7b80dc8` of the RoadWeaver repository.
The license text is included as `licenses/RoadWeaver-MIT.txt`.
