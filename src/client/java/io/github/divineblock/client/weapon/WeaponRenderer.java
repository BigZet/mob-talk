package io.github.divineblock.client.weapon;

import io.github.divineblock.weapon.CherubSwordItem;
import io.github.divineblock.weapon.NimrodBowItem;
import io.github.divineblock.weapon.SlingItem;
import io.github.divineblock.weapon.WeaponKind;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/** Draws a weapon's generated box model wherever its item is shown. The model stands upright with its point toward -Y and
 * is laid here on the sword's diagonal (grip low left, point high right), so the vanilla hand-held transforms in the item
 * model JSON place it as they do a sword. Fire and holy light go over it at full brightness from the glow texture. Motion
 * comes from the clock: the cherub's blade turns and its flames breathe, the sling spins while it is wound, the bow's
 * limbs bend and its string draws back with the arrow, the pennant of the spear flutters. */
public final class WeaponRenderer implements BuiltinItemRendererRegistry.DynamicItemRenderer {
    private final WeaponKind kind;
    private final ModelPart root;
    private final Identifier texture, glow;
    private final ModelPart[] tongues = new ModelPart[6];
    private final ModelPart blade, pouchStone, limbUpper, limbLower, stringUpper, stringLower, pennant, flameTongue0, flameTongue1;

    WeaponRenderer(WeaponKind kind, ModelPart root) {
        this.kind = kind; this.root = root;
        texture = Identifier.of("divineblock", "textures/entity/weapon/" + kind.id + ".png");
        glow = kind.glow ? Identifier.of("divineblock", "textures/entity/weapon/" + kind.id + "_glow.png") : null;
        for (int i = 0; i < tongues.length; i++) tongues[i] = part("blade_flame_" + i);
        blade = part("blade"); pouchStone = part("stone");
        limbUpper = part("limb_upper"); limbLower = part("limb_lower"); stringUpper = part("limb_upper_string"); stringLower = part("limb_lower_string");
        pennant = part("pennant"); flameTongue0 = part("flame_tongue_0"); flameTongue1 = part("flame_tongue_1");
    }

    /** A part anywhere in the tree by name, or an empty part when there is none (so animation never crashes). */
    private ModelPart part(String name) {
        return root.traverse().filter(p -> p.hasChild(name)).findFirst().map(p -> p.getChild(name)).orElseGet(() -> new ModelPart(java.util.List.of(), java.util.Map.of()));
    }

    @Override public void render(ItemStack stack, ModelTransformationMode mode, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay) {
        matrices.push();
        matrices.translate(0.5f, 0.5f, 0.5f);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-45f));
        float s = kind.scale / 16f;
        matrices.scale(-s, -s, s);
        root.traverse().forEach(ModelPart::resetTransform);
        animate(stack, mode);
        root.render(matrices, vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(texture)), light, overlay);
        if (glow != null) root.render(matrices, vertexConsumers.getBuffer(RenderLayer.getEyes(glow)), LightmapTextureManager.MAX_LIGHT_COORDINATE, overlay);
        matrices.pop();
    }

    private void animate(ItemStack stack, ModelTransformationMode mode) {
        float time = (System.nanoTime() % 100_000_000_000L) / 50_000_000f; // ticks, smooth
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        boolean inHand = mode.isFirstPerson() || mode == ModelTransformationMode.THIRD_PERSON_LEFT_HAND || mode == ModelTransformationMode.THIRD_PERSON_RIGHT_HAND;
        boolean using = inHand && player != null && player.isUsingItem() && player.getActiveItem() == stack;
        int useTicks = using ? player.getActiveItem().getMaxUseTime(player) - player.getItemUseTimeLeft() : 0;
        switch (kind) {
            case CHERUB_SWORD -> {
                boolean turning = player != null && CherubSwordItem.orbiting(player.getUuid()) > 0;
                blade.yaw += time * (turning ? 0.5f : 0.06f);
                for (int i = 0; i < tongues.length; i++) {
                    tongues[i].yScale = 0.75f + 0.35f * (0.5f + 0.5f * MathHelper.sin(time * 0.5f + i * 1.3f));
                    tongues[i].roll += MathHelper.sin(time * 0.27f + i) * 0.12f;
                }
            }
            case DAVID_SLING -> {
                if (using) {
                    float speed = Math.min(1f, useTicks / (float) SlingItem.WIND_TICKS);
                    root.roll += time * speed * 1.2f;
                }
                pouchStone.visible = player == null || player.isCreative() || !SlingItem.ammo(player).isEmpty() || !inHand;
            }
            case NIMROD_BOW -> {
                float draw = using ? NimrodBowItem.pull(useTicks) : 0f;
                limbUpper.pitch += -0.22f * draw; limbLower.pitch += 0.22f * draw;
                stringUpper.pitch += -0.5f * draw; stringLower.pitch += 0.5f * draw;
            }
            case GEORGE_SPEAR -> pennant.yaw += MathHelper.sin(time * 0.3f) * 0.25f + MathHelper.sin(time * 0.9f) * 0.08f;
            case GIDEON_JAR -> {
                flameTongue0.yScale = 0.8f + 0.3f * (0.5f + 0.5f * MathHelper.sin(time * 0.6f));
                flameTongue1.yScale = 0.8f + 0.3f * (0.5f + 0.5f * MathHelper.sin(time * 0.6f + 1.7f));
            }
            default -> { }
        }
    }
}
