package io.github.divineblock.client.fx;

/** Cells of the effects atlas (textures/effect/fx.png, 4×4 cells of 128 px, drawn by scripts in the mod's own style). */
enum FxSprite {
    GLOW, SPARK, STAR, HALO, FEATHER, CONFETTI, STRIP, ASH, SMOKE, BEAM, FLARE, EMBER, SMOKE_2, SMOKE_3, SMOKE_4;

    private static final FxSprite[] SMOKES = { SMOKE, SMOKE_2, SMOKE_3, SMOKE_4 };
    /** One of the four billows of smoke, so a cloud of them does not repeat itself. */
    static FxSprite smoke(java.util.Random random) { return SMOKES[random.nextInt(SMOKES.length)]; }

    /** A hair inside the cell, so smooth filtering never bleeds the neighbour in. */
    private static final float INSET = 0.5f / 512f;
    final float u0, v0, u1, v1;

    FxSprite() {
        int index = ordinal();
        u0 = (index % 4) / 4f + INSET; v0 = (index / 4) / 4f + INSET;
        u1 = (index % 4 + 1) / 4f - INSET; v1 = (index / 4 + 1) / 4f - INSET;
    }
}
