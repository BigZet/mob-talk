/*
 * Mob Talk — human villagers (track M).
 * Derived from MCA Reborn's VillagerLayer, SkinLayer, FaceLayer, ClothingLayer and HairLayer
 * (net.conczin.mca.client.render.layer), Copyright (C) the MCA Reborn contributors, licensed under the GNU General Public
 * License v3.0. Modified for Mob Talk: ported to Yarn mappings and the vanilla VillagerEntity; the look comes from
 * {@link HumanLooks} instead of MCA's tracked villager data; dyes, traits, layered hair and the skin library are left out.
 */
package io.github.mobtalk.client.human;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.Identifier;

/** The four visible layers of a human villager, bottom to top: tinted skin, face with coloured eyes, clothes, tinted hair.
 * Each has its own model (a slightly larger shell each time) posed like the renderer's model. */
final class HumanLayers {
    private HumanLayers() { }

    abstract static class Layer extends FeatureRenderer<VillagerEntity, HumanModel> {
        final HumanModel model;
        Layer(FeatureRendererContext<VillagerEntity, HumanModel> context, HumanModel model) { super(context); this.model = model; }

        /** Which parts this layer shows. */
        abstract void visibility();
        abstract void draw(MatrixStack matrices, VertexConsumerProvider consumers, int light, int overlay, VillagerEntity villager, HumanLook look,
                           boolean visible, boolean glowing);
        boolean translucent() { return false; }

        @Override public void render(MatrixStack matrices, VertexConsumerProvider consumers, int light, VillagerEntity villager, float limbAngle,
                                     float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
            model.copyFrom(getContextModel());
            visibility();
            boolean visible = !villager.isInvisible();
            boolean glowing = MinecraftClient.getInstance().hasOutline(villager);
            draw(matrices, consumers, light, LivingEntityRenderer.getOverlay(villager, 0), villager, HumanLooks.of(villager), visible, glowing);
        }

        void part(MatrixStack matrices, VertexConsumerProvider consumers, int light, int overlay, Identifier texture, int color, boolean visible, boolean glowing) {
            if (texture == null) return;
            RenderLayer layer = visible ? (translucent() ? RenderLayer.getItemEntityTranslucentCull(texture) : model.getLayer(texture))
                    : glowing ? RenderLayer.getOutline(texture) : null;
            if (layer != null) model.render(matrices, consumers.getBuffer(layer), light, overlay, color);
        }
    }

    static final class Skin extends Layer {
        Skin(FeatureRendererContext<VillagerEntity, HumanModel> context) { super(context, HumanModel.bake(HumanModel.body(net.minecraft.client.model.Dilation.NONE))); }
        @Override void visibility() { model.setVisible(true); hideWear(model); }
        @Override void draw(MatrixStack m, VertexConsumerProvider c, int light, int overlay, VillagerEntity v, HumanLook look, boolean visible, boolean glowing) {
            part(m, c, light, overlay, HumanTextures.skin(look), HumanTextures.skinColor(look), visible, glowing);
        }
    }

    static final class Face extends Layer {
        Face(FeatureRendererContext<VillagerEntity, HumanModel> context) { super(context, HumanModel.bake(HumanModel.body(new net.minecraft.client.model.Dilation(0.01f)))); }
        @Override boolean translucent() { return true; }
        @Override void visibility() { model.setVisible(false); model.head.visible = true; }
        @Override void draw(MatrixStack m, VertexConsumerProvider c, int light, int overlay, VillagerEntity v, HumanLook look, boolean visible, boolean glowing) {
            if (blinking(v, look)) { part(m, c, light, overlay, HumanTextures.BLINK, 0xFFFFFFFF, visible, glowing); return; }
            Identifier face = HumanTextures.face(look);
            if (face == null) return;
            part(m, c, light, overlay, HumanTextures.eyes(face, HumanTextures.EyePart.SCLERA), 0xFFFFFFFF, visible, glowing);
            part(m, c, light, overlay, HumanTextures.eyes(face, HumanTextures.EyePart.DETAILS), HumanTextures.DETAILS_TINT, visible, glowing);
            part(m, c, light, overlay, HumanTextures.eyes(face, HumanTextures.EyePart.IRIS), HumanTextures.eyeColor(look), visible, glowing);
        }
        private static boolean blinking(VillagerEntity v, HumanLook look) {
            int time = v.age / 2 + (int) (look.genes().hemoglobin() * 65536);
            return time % 50 == 1 || time % 57 == 1 || v.isSleeping() || v.isDead();
        }
    }

    static final class Clothes extends Layer {
        Clothes(FeatureRendererContext<VillagerEntity, HumanModel> context) { super(context, HumanModel.bake(HumanModel.body(new net.minecraft.client.model.Dilation(0.0625f)))); }
        @Override void visibility() { model.setVisible(true); model.breastplate.visible = true; }
        @Override void draw(MatrixStack m, VertexConsumerProvider c, int light, int overlay, VillagerEntity v, HumanLook look, boolean visible, boolean glowing) {
            Identifier clothes = HumanTextures.clothes(look);
            part(m, c, light, overlay, clothes, 0xFFFFFFFF, visible, glowing);
        }
    }

    static final class Hair extends Layer {
        Hair(FeatureRendererContext<VillagerEntity, HumanModel> context) { super(context, HumanModel.bake(HumanModel.hair(new net.minecraft.client.model.Dilation(0.125f)))); }
        @Override void visibility() {
            model.setVisible(true); model.leftLeg.visible = false; model.rightLeg.visible = false; model.leftPants.visible = false; model.rightPants.visible = false;
            model.breasts.visible = false; model.breastplate.visible = false;
        }
        @Override void draw(MatrixStack m, VertexConsumerProvider c, int light, int overlay, VillagerEntity v, HumanLook look, boolean visible, boolean glowing) {
            Identifier hair = HumanTextures.hair(look);
            part(m, c, light, overlay, hair, HumanTextures.hairColor(look), visible, glowing);
            part(m, c, light, overlay, HumanTextures.overlay(hair), 0xFFFFFFFF, visible, glowing);
        }
    }

    private static void hideWear(HumanModel model) {
        model.hat.visible = false; model.jacket.visible = false; model.leftSleeve.visible = false; model.rightSleeve.visible = false;
        model.leftPants.visible = false; model.rightPants.visible = false; model.breastplate.visible = false;
    }
}
