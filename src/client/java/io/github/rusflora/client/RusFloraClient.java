package io.github.rusflora.client;

import io.github.rusflora.RusFlora;
import io.github.rusflora.block.ShrubKind;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.render.RenderLayer;

/** Leaves, stems and saplings are drawn with see-through gaps. */
public final class RusFloraClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        for (ShrubKind kind : ShrubKind.values()) {
            BlockRenderLayerMap.INSTANCE.putBlock(RusFlora.leaves(kind), RenderLayer.getCutoutMipped());
            BlockRenderLayerMap.INSTANCE.putBlock(RusFlora.stem(kind), RenderLayer.getCutout());
            BlockRenderLayerMap.INSTANCE.putBlock(RusFlora.sapling(kind), RenderLayer.getCutout());
        }
        ShrubSelfTest.register();
    }
}
