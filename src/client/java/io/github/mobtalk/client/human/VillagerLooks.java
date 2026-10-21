package io.github.mobtalk.client.human;

import io.github.mobtalk.client.game.mixin.LivingEntityRendererInvoker;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.VillagerEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/** Трек M: рендер жителя с переключателем. Держит оба — ванильный и человеческий ({@link HumanVillagerRenderer}) — и рисует
 * тем, что выбран в F7 ({@link HumanLooks#enabled}); переключение мгновенное, без перезагрузки ресурсов. Fabric сам раздаёт
 * слои только рендерам из своей таблицы, поэтому событие слоёв жителя здесь посылается обоим: живот трека N и слои других
 * модов ложатся на ту модель, что сейчас видна. */
public final class VillagerLooks extends EntityRenderer<VillagerEntity> {
    private final VillagerEntityRenderer vanilla;
    private final HumanVillagerRenderer human;

    public static void register() { EntityRendererRegistry.register(EntityType.VILLAGER, VillagerLooks::new); }

    private VillagerLooks(EntityRendererFactory.Context context) {
        super(context);
        vanilla = new VillagerEntityRenderer(context);
        human = new HumanVillagerRenderer(context);
        features(vanilla, context);
        features(human, context);
        shadowRadius = 0.5f;
    }

    private static void features(LivingEntityRenderer<?, ?> renderer, EntityRendererFactory.Context context) {
        LivingEntityFeatureRendererRegistrationCallback.EVENT.invoker().registerRenderers(EntityType.VILLAGER, renderer,
                new LivingEntityFeatureRendererRegistrationCallback.RegistrationHelper() {
                    @SuppressWarnings({ "unchecked", "rawtypes" })
                    @Override public <T extends LivingEntity> void register(FeatureRenderer<T, ? extends EntityModel<T>> feature) {
                        ((LivingEntityRendererInvoker) renderer).mobtalk$addFeature((FeatureRenderer) feature);
                    }
                }, context);
    }

    private EntityRenderer<VillagerEntity> pick() { return HumanLooks.enabled ? human : vanilla; }
    public static boolean human(EntityRenderer<?> renderer) { return renderer instanceof VillagerLooks && HumanLooks.enabled; }

    @Override public void render(VillagerEntity villager, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider consumers, int light) {
        pick().render(villager, yaw, tickDelta, matrices, consumers, light);
    }
    @Override public boolean shouldRender(VillagerEntity villager, Frustum frustum, double x, double y, double z) { return pick().shouldRender(villager, frustum, x, y, z); }
    @Override public Vec3d getPositionOffset(VillagerEntity villager, float tickDelta) { return pick().getPositionOffset(villager, tickDelta); }
    @Override public Identifier getTexture(VillagerEntity villager) { return pick().getTexture(villager); }
    @Override protected float getShadowRadius(VillagerEntity villager) { return (villager.isBaby() ? 0.25f : 0.5f) * villager.getScale(); }
}
