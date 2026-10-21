package io.github.divineblock.client.boss;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

/** The layers of the god: the figure cut out and unlit ({@link #figure}); the sky with smooth filtering so the soft puffs have no pixel stair-steps: {@link #clouds} is
 * ordinary see-through (drawn back to front, writing no depth), {@link #light} adds light (the glow in the bank, the god
 * rays). Neither culls: billboards may be seen from either side. */
final class GodLayers extends RenderLayer {
    private GodLayers() { super("divineblock_god", VertexFormats.POSITION_COLOR_TEXTURE_LIGHT, VertexFormat.DrawMode.QUADS, 256, false, true, () -> { }, () -> { }); }

    /** The figure itself, cut out and unlit: no shading by the world's light directions, so seen from below he is never a
     * dark silhouette — the painted texture carries his volume, and he shines in a storm and at night. */
    static RenderLayer figure(Identifier texture) {
        return of("divineblock_god_figure", VertexFormats.POSITION_COLOR_TEXTURE_LIGHT, VertexFormat.DrawMode.QUADS, 4096, true, false,
                MultiPhaseParameters.builder().program(POSITION_COLOR_TEXTURE_LIGHTMAP_PROGRAM).texture(new Texture(texture, false, false))
                        .transparency(NO_TRANSPARENCY).cull(DISABLE_CULLING).lightmap(ENABLE_LIGHTMAP).build(true));
    }

    static RenderLayer clouds(Identifier texture) {
        return of("divineblock_god_clouds", VertexFormats.POSITION_COLOR_TEXTURE_LIGHT, VertexFormat.DrawMode.QUADS, 4096, false, true,
                MultiPhaseParameters.builder().program(POSITION_COLOR_TEXTURE_LIGHTMAP_PROGRAM).texture(new Texture(texture, true, false))
                        .transparency(TRANSLUCENT_TRANSPARENCY).writeMaskState(COLOR_MASK).cull(DISABLE_CULLING).lightmap(ENABLE_LIGHTMAP).build(false));
    }

    static RenderLayer light(Identifier texture) {
        return of("divineblock_god_light", VertexFormats.POSITION_COLOR_TEXTURE_LIGHT, VertexFormat.DrawMode.QUADS, 1024, false, true,
                MultiPhaseParameters.builder().program(POSITION_COLOR_TEXTURE_LIGHTMAP_PROGRAM).texture(new Texture(texture, true, false))
                        .transparency(LIGHTNING_TRANSPARENCY).writeMaskState(COLOR_MASK).cull(DISABLE_CULLING).lightmap(ENABLE_LIGHTMAP).build(false));
    }
}
