# Brief v2: rework the boss models (huge, beautiful, detailed)

Feedback from Dmitriy: the models are too small, boxy, crude and undetailed, and the Archangel looks like a robot. These are divine
bosses, so they must be HUGE and BEAUTIFUL. The quality bar is the reworked **Архангел** in this branch:
- `scripts/bosses/archangel.py` and `ArchangelModel.java`
- screenshots: `C:\Users\bigze\Documents\GH\divine-block\artifacts\bosses\v2\boss-showcase-archangel-front.png` and `-side.png`

Look at those first. Every boss you rework must reach that bar or better.

Read the v1 brief too (`docs/bosses-agent-brief.md`, same rules: your files only, build, commit in Russian with the
Co-Authored-By line, never push).

## Generator (scripts/bosses/boxtex.py)
New in v2:
- **Density:** `build(..., size=256, density=4)`. The texture is declared at `size` units, but the PNG is size×density pixels, which
  gives 4 painted pixels per model unit. Use density 4 for every boss.
- **Volume shading:** applied to every face automatically: lit tops, shadowed undersides, a vertical gradient and edge occlusion.
  Pass `Box(..., flat=True)` to skip it, for glowing or painted planes.
- **Transparency:** `p.put(f, u, v, None)` leaves a pixel transparent, which gives ragged feathers, halos and lace.
- **Styles:**
  - `gold_leaf()`
  - `drapery(base, highlight, hem, hem_width)` for icon-style cloth with пробела highlights
  - `scale_mail(base, rim)`
  - `feather(base, tip, glow_tip)`
  - `hair(color)`
  - `icon_face(skin, hair, eye, stern, glow_eyes)` for a real face: eyes with whites, brows, nose, mouth, modelling
  - `halo_disc()`
  - `flame_blade()`
  - plus the old `cloth/metal/solid/fire/candle/gold/eyes_face`
- **Builders:** these make organic shapes out of many small rotated boxes:
  - `wing(name, pivot, rotation, span, rows, feathers, base, tip, side)`: individual feathers in layers. The bone runs along +X, or
    -X with `side=-1`, and the feathers hang along +Y.
  - `skirt(name, pivot, top_radius, bottom_radius, height, panels, style)`: a bell-shaped robe made of tilted panels.
  - `halo(name, pivot, radius)`
  - `curls(name, pivot, count, radius, length, color)`
- Write more builders for your own boss, in your own script, when you need them: tapering limbs made of stacked segments, chains,
  ribs, scales made of small plates, serpent segments, horns made of rotated pieces, and so on.

## Rules learned on the Archangel (save yourself the iterations)
- **Rotation order and signs:** ModelPart applies rotation in Z·Y·X order. Model space is y down, the front is -Z, and +Z is back.
  - Pitch +θ tilts a part's top (its -Y end) FORWARD toward -Z.
  - Roll +θ swings a -X-pointing bone UP (toward -Y). For a +X bone, use roll -θ to swing up.
  - Yaw +θ swings a -X bone toward +Z (back).
- **Animate by adding (`+=`, `-=`) to the rest pose**, because `resetTransform()` restores the generated rotations every frame.
  Absolute assignment (`=`) destroys the rest pose of rotated parts.
- **Avoid boxes inside boxes.** A weapon held along a hanging arm disappears inside the arm, so rotate it out (see the sword).
- **Build big and render small:** build the model at a reasonable unit size (20–60 units tall), then scale it in the renderer
  (BossClient, your own line: `new SacredBossRenderer<>(…, "id", shadow, scale)`). The Archangel is 46 units at scale 2.5, about
  7.2 blocks. Texel density stays high because of density 4.
- **Update the hitbox** in `DivineBosses` (your own line: width, height). Eye height is 0.85×height automatically. Then fix every
  hard-coded height and reach in your Entity class: use `getHeight() * k`, and make `meleeReach()` fit the new size. Keep fights
  fair, telegraphed and capped.
- **The client model has no collision**, so models can be much bigger than the hitbox (wings, tails), but keep the hitbox
  representative of the body.
- **Size targets:**
  - humanoids 4–6 blocks (Голиаф 6–7);
  - winged angels with wingspans of 8–12;
  - Левиафан, Бегемот and Зверь 8–12 blocks long, hitbox width at most 5;
  - Всадники on large horses, about 4–5 blocks tall.
- **Church guardians** (black_schemnik, seraph, faceless_icon, cherub) must fit in the church nave: 14 wide, about 11 high. Keep
  them at most 8 tall and the hitbox at most 4 wide.
- **Style:** dark Orthodox iconography. The palette is deep red, ochre, gold, black, white and bone, with candlelight and fire as
  glow. Use flowing robes with folds and layered hems, hoods, real faces, halos, individual feathers, plated armour, gold trims,
  crosses, chains, candles and censers. No vanilla-cube look: bevels, tapering, angled plates and thin planes.
- **Animation:** idle breathing, wing beats with tips lagging, cloth sway, head tracking, attack/ult poses (`castState()` 1/2,
  `syncedPhase()`), and walk cycles that suit the creature.

## See your work: showcase screenshots in runClient (your own worktree has its own run/ folder)
From your worktree root in PowerShell, for your ids:
```
Remove-Item -Recurse run\saves\divineblock-bosstest -ErrorAction SilentlyContinue
$env:JAVA_TOOL_OPTIONS='-Ddivineblock.bosstest=true -Ddivineblock.bosstest.showcase=true -Ddivineblock.bosstest.only=<id>,<id>'
.\gradlew.bat runClient --console=plain *> "$env:TEMP\showcase-<yourgroup>.txt"
```
This creates a world, poses each boss next to an armour stand of player size, saves `run\screenshots\boss-showcase-<id>-front.png`
and `-side.png`, and quits by itself in about 2–4 minutes. Look at them with the Read tool. Crop and zoom with PIL if needed,
writing to your own temp folder. Iterate until it looks great: at least 2 rounds of look and fix.

At the end, copy your final two screenshots per boss to `C:\Users\bigze\Documents\GH\divine-block\artifacts\bosses\v2\` with the
same file names, then commit. Don't run more than one game at a time yourself, and never stop other java processes.

Before you finish, also run the normal fight test for your ids, so attacks and ultimates still work at the new size:
`-Ddivineblock.bosstest=true -Ddivineblock.bosstest.only=<ids>` without showcase. Then read
`%LOCALAPPDATA%\DivineBlock\bosstest\report.txt` (it is shared and the last run wins, so read it right after yours finishes) and fix
any FAIL that is yours.
