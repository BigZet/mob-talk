# NiftyCarts in Mob Talk (carts and wagons on the village roads)

The cart and wagon models of the village roads come from **NiftyCarts** (https://github.com/jmb05/nifty-carts),
Copyright (c) 2019 MennoMax, Copyright (c) 2023 jmb19905, MIT License. Reference revision: `1aaa4e9`.

Ported files, under `src/client/java/io/github/mobtalk/client/roads/`:

- `EasyMeshBuilder`, `CartModel`, `AnimalCartModel`, `WagonModel`: NiftyCarts' model code, moved from Mojang to yarn mappings
  (Fabric 1.21.1). The wagon's chests are left out, and its roof is shown half rolled up so the passengers can be seen.
- `CartTextures`: new code. It builds the textures at runtime from the vanilla plank, log and wool textures, in place of
  NiftyCarts' texture files (which are not included).
- `RoadCartRenderer`: new code after NiftyCarts' cart renderer.

Mob Talk's own `game/RoadCartEntity` takes its seat positions from NiftyCarts' `AnimalCartEntity` and `WagonEntity`.
The license text is included as `licenses/NiftyCarts-MIT.txt`.
