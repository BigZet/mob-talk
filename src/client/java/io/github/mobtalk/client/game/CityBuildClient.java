package io.github.mobtalk.client.game;

import io.github.mobtalk.game.CityBuild;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.render.RenderLayer;

/** Render layers of the city houses' blocks: glass in windows and doors is translucent, railings and aerials are cut out. */
public final class CityBuildClient {
    private CityBuildClient() { }

    public static void register() {
        CityBuild.register();
        for (String id : CityBuild.translucent()) BlockRenderLayerMap.INSTANCE.putBlock(CityBuild.BLOCKS.get(id), RenderLayer.getTranslucent());
        for (String id : CityBuild.cutout()) BlockRenderLayerMap.INSTANCE.putBlock(CityBuild.BLOCKS.get(id), RenderLayer.getCutout());
    }
}
