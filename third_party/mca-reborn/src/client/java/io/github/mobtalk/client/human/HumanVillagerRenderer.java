/*
 * Mob Talk — human villagers (track M).
 * Derived from MCA Reborn's VillagerLikeEntityMCARenderer and VillagerEntityMCARenderer (net.conczin.mca.client.render),
 * Copyright (C) the MCA Reborn contributors, licensed under the GNU General Public License v3.0. Modified for Mob Talk:
 * ported to Yarn mappings and the vanilla VillagerEntity; MCA's name-tag, infection and editor hooks are left out.
 */
package io.github.mobtalk.client.human;

import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.feature.HeadFeatureRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.Identifier;

/** The vanilla villager drawn as a person. The renderer's own model only animates (its render layer is null, so it draws no
 * geometry); the skin, face, clothes and hair layers draw it. Vanilla features (held item, head item, armour) stay on top. */
public final class HumanVillagerRenderer extends BipedEntityRenderer<VillagerEntity, HumanModel> {
    public HumanVillagerRenderer(EntityRendererFactory.Context context) {
        super(context, HumanModel.bake(HumanModel.body(Dilation.NONE)), 0.5f);
        features.replaceAll(feature -> feature instanceof HeadFeatureRenderer<VillagerEntity, HumanModel> head ? new HeadOnHuman(this, head) : feature);
        features.add(0, new HumanLayers.Skin(this));
        features.add(1, new HumanLayers.Face(this));
        features.add(2, new HumanLayers.Clothes(this));
        features.add(3, new HumanLayers.Hair(this));
        addFeature(new ArmorFeatureRenderer<>(this, new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
                new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
    }

    @Override public void render(VillagerEntity villager, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider consumers, int light) {
        HumanLook look = HumanLooks.of(villager);
        model.setLook(look.female(), look.genes().breast());
        super.render(villager, yaw, tickDelta, matrices, consumers, light);
    }

    /** Ванильный слой головы поднимает предмет на голове жителя на 3 пикселя (голова ванильного жителя выше). У человека голова
     * обычная: на время слоя голова опускается на те же 3 пикселя, и фата, венец, венок и тыква садятся как на игроке. */
    private static final class HeadOnHuman extends FeatureRenderer<VillagerEntity, HumanModel> {
        private final HeadFeatureRenderer<VillagerEntity, HumanModel> vanilla;
        HeadOnHuman(FeatureRendererContext<VillagerEntity, HumanModel> context, HeadFeatureRenderer<VillagerEntity, HumanModel> vanilla) { super(context); this.vanilla = vanilla; }
        @Override public void render(MatrixStack matrices, VertexConsumerProvider consumers, int light, VillagerEntity villager, float limbAngle, float limbDistance,
                                     float tickDelta, float animationProgress, float headYaw, float headPitch) {
            ModelPart head = getContextModel().head;
            float pivot = head.pivotY;
            head.pivotY = pivot + 3;
            try { vanilla.render(matrices, consumers, light, villager, limbAngle, limbDistance, tickDelta, animationProgress, headYaw, headPitch); }
            finally { head.pivotY = pivot; }
        }
    }

    @Override protected void scale(VillagerEntity villager, MatrixStack matrices, float tickDelta) {
        HumanLook look = HumanLooks.of(villager);
        float base = 0.9375f;
        float h = villager.isBaby() ? base : base * look.genes().heightScale(), w = villager.isBaby() ? base : base * look.genes().widthScale();
        matrices.scale(w, h, w);
    }

    /** Null: the base model only animates; the layers draw the body. */
    @Override protected RenderLayer getRenderLayer(VillagerEntity villager, boolean showBody, boolean translucent, boolean showOutline) { return null; }

    @Override protected float getShadowRadius(VillagerEntity villager) { return villager.isBaby() ? 0.25f : 0.5f; }

    /** The skin: what other features (the pregnancy belly) take as the body texture. */
    @Override public Identifier getTexture(VillagerEntity villager) { return HumanTextures.skin(HumanLooks.of(villager)); }
}
