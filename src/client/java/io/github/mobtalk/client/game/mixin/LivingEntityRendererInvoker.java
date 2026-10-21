package io.github.mobtalk.client.game.mixin;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Трек M: слои от события Fabric на рендеры жителя внутри {@link io.github.mobtalk.client.human.VillagerLooks}. */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererInvoker {
    @Invoker("addFeature") boolean mobtalk$addFeature(FeatureRenderer<?, ?> feature);
}
