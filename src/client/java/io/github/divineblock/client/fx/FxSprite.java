package io.github.divineblock.client.fx;

/** Cells of the effects atlas (textures/effect/fx.png, 4×4 cells of 64 px, drawn by scripts in the mod's own style). */
enum FxSprite {
    GLOW, SPARK, STAR, HALO, FEATHER, CONFETTI, STRIP, ASH, SMOKE, BEAM, FLARE, EMBER;

    /** A hair inside the cell, so smooth filtering never bleeds the neighbour in. */
    private static final float INSET = 0.5f / 256f;
    final float u0, v0, u1, v1;

    FxSprite() {
        int index = ordinal();
        u0 = (index % 4) / 4f + INSET; v0 = (index / 4) / 4f + INSET;
        u1 = (index % 4 + 1) / 4f - INSET; v1 = (index / 4 + 1) / 4f - INSET;
    }
}
