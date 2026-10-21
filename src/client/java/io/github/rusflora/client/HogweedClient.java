package io.github.rusflora.client;

import io.github.rusflora.hogweed.Hogweed;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.render.RenderLayer;

/** The hogweed's leaves, umbels and the dry umbel are drawn with see-through gaps; the self-test starts here when asked for. */
final class HogweedClient {
    private HogweedClient() { }

    static void register() {
        BlockRenderLayerMap.INSTANCE.putBlock(Hogweed.BLOCK, RenderLayer.getCutout());
        BlockRenderLayerMap.INSTANCE.putBlock(Hogweed.DRY_UMBEL, RenderLayer.getCutout());
        HogweedSelfTest.register();
    }
}
