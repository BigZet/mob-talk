package io.github.divineblock.client.fx;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

/** The two layers the god's effects are drawn in, both from one atlas of the mod's own sprites with smooth filtering:
 * {@link #GLOW} adds light (bright where sprites overlap, never darkens), {@link #SOFT} is ordinary see-through
 * (feathers, confetti, ash, smoke). Neither writes depth, so overlapping sprites need no sorting. */
final class FxLayers extends RenderLayer {
    static final Identifier ATLAS = Identifier.of("divineblock", "textures/effect/fx.png");
    static final RenderLayer GLOW = of("divineblock_fx_glow", VertexFormats.POSITION_COLOR_TEXTURE_LIGHT, VertexFormat.DrawMode.QUADS, 1536,
            false, true, MultiPhaseParameters.builder().program(POSITION_COLOR_TEXTURE_LIGHTMAP_PROGRAM).texture(new Texture(ATLAS, true, false))
                    .transparency(LIGHTNING_TRANSPARENCY).writeMaskState(COLOR_MASK).cull(DISABLE_CULLING).lightmap(ENABLE_LIGHTMAP).build(false));
    static final RenderLayer SOFT = of("divineblock_fx_soft", VertexFormats.POSITION_COLOR_TEXTURE_LIGHT, VertexFormat.DrawMode.QUADS, 1536,
            false, true, MultiPhaseParameters.builder().program(POSITION_COLOR_TEXTURE_LIGHTMAP_PROGRAM).texture(new Texture(ATLAS, true, false))
                    .transparency(TRANSLUCENT_TRANSPARENCY).writeMaskState(COLOR_MASK).cull(DISABLE_CULLING).lightmap(ENABLE_LIGHTMAP).build(false));

    private FxLayers() { super("divineblock_fx", VertexFormats.POSITION_COLOR_TEXTURE_LIGHT, VertexFormat.DrawMode.QUADS, 256, false, true, () -> { }, () -> { }); }
}
