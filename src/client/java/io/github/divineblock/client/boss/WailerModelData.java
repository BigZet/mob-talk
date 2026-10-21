package io.github.divineblock.client.boss;

import net.minecraft.client.model.*;

/** Stub until scripts/bosses/wailer.py generates the real one. */
public final class WailerModelData {
    private WailerModelData() { }

    public static TexturedModelData create() {
        ModelData data = new ModelData();
        data.getRoot().addChild("body", ModelPartBuilder.create().uv(0, 0).cuboid(-8F, -32F, -8F, 16F, 32F, 16F), ModelTransform.pivot(0F, 24F, 0F));
        return TexturedModelData.of(data, 128, 128);
    }
}
