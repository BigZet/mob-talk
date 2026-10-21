# MCA Reborn in Mob Talk (track M, human villagers)

Mob Talk draws vanilla villagers as people with a renderer ported from **MCA Reborn**
(Minecraft Comes Alive Reborn, https://github.com/Luke100000/minecraft-comes-alive),
Copyright (C) WildBamaBoy, Luke100000 (Conczin) and the MCA Reborn contributors
(credits: Cleora, WildBamaBoy, SheWolfDeadly, ntzrmtthihu777, ko2fan, Akjosch, Innectic, Sollace, CDAGaming and many more).
MCA Reborn is licensed under the GNU General Public License v3.0; Mob Talk is GPL-3.0 as well (see `LICENSE`).

Reference revision: `0e9094f` of the MCA Reborn repository.

## Code (`src/`)

Ported to Yarn mappings and the vanilla `VillagerEntity`, then changed for Mob Talk; every file says which MCA classes it is derived
from and how it was modified:

- `src/main/java/io/github/mobtalk/human/HumanGenes.java` — from `net.conczin.mca.entity.ai.Genetics`.
- `src/client/java/io/github/mobtalk/client/human/HumanModel.java` — from `VillagerEntityBaseModelMCA`, `VillagerEntityModelMCA`, `CommonVillagerModel`.
- `src/client/java/io/github/mobtalk/client/human/HumanLayers.java` — from `VillagerLayer`, `SkinLayer`, `FaceLayer`, `ClothingLayer`, `HairLayer`.
- `src/client/java/io/github/mobtalk/client/human/HumanTextures.java` — from the texture choice in those layers, `FaceList`, `ColorPalette`, `EyeTextureLayers`.
- `src/client/java/io/github/mobtalk/client/human/HumanVillagerRenderer.java` — from `VillagerLikeEntityMCARenderer`, `VillagerEntityMCARenderer`.
- `src/client/java/io/github/mobtalk/client/human/HumanLook.java` — glue for the above.

## Textures (`src/client/resources/assets/mobtalk/human/`)

Copied unchanged from `common/src/main/resources/assets/mca` of MCA Reborn:

- `skin/{female,male}/0–4.png` ← `skins/skin/…` (the "classic" variants are left out);
- `face/normal/*.png` ← `skins/face/normal/…`;
- `hair/{female,male}/*.png` ← `skins/hair/…`;
- `clothing/{female,male,neutral}/<profession>/*.png` ← `skins/clothing/normal/…` (vanilla professions, `none`, `nitwit`, `child`, `toddler`; MCA-only professions are left out);
- `colormap/skin.png`, `colormap/hair.png` ← `textures/colormap/villager_skin.png`, `villager_hair.png`.

Mob Talk's own glue (the switch between the vanilla and the human model, the lineage copy from the world memory) lives in
`src/client/java/io/github/mobtalk/client/human/` of the main tree and is Mob Talk's code.
