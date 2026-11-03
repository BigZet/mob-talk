package io.github.mobtalk.client;

import io.github.mobtalk.game.FurnitureBlocks;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.EmptyEntityRenderer;

/** The client side of the flat furniture: lace and glass cut out, net curtains see-through, the seat entity drawn as nothing. */
final class FurnitureClient {
    private FurnitureClient() { }

    static void register() {
        FurnitureBlocks.CUTOUT.forEach(b -> BlockRenderLayerMap.INSTANCE.putBlock(b, RenderLayer.getCutout()));
        FurnitureBlocks.TRANSLUCENT.forEach(b -> BlockRenderLayerMap.INSTANCE.putBlock(b, RenderLayer.getTranslucent()));
        EntityRendererRegistry.register(FurnitureBlocks.SEAT, EmptyEntityRenderer::new);
    }
}
