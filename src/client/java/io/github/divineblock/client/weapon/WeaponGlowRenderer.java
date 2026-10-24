package io.github.divineblock.client.weapon;

import io.github.divineblock.weapon.WeaponKind;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/** A weapon whose sprite carries light: its base sprite is drawn as any item is, and over it the «glow» sprite (flame,
 * holy light, blood) at full brightness, so it shines in the dark and from any angle. The item's own model is
 * {@code builtin/entity} with the hand-held transforms, already applied when this is called; the two sprites are plain
 * models {@code <id>_base} and {@code <id>_glow} with identity transforms, loaded as extra models. */
public final class WeaponGlowRenderer implements BuiltinItemRendererRegistry.DynamicItemRenderer {
    private final ModelIdentifier base, glow;

    WeaponGlowRenderer(WeaponKind kind) {
        base = extra(kind, "_base");
        glow = extra(kind, "_glow");
    }

    static ModelIdentifier extra(WeaponKind kind, String suffix) {
        // Fabric's variant for models added through ModelLoadingPlugin.Context.addModels.
        return new ModelIdentifier(Identifier.of("divineblock", "item/" + kind.id + suffix), "fabric_resource");
    }

    @Override public void render(ItemStack stack, ModelTransformationMode mode, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay) {
        MinecraftClient client = MinecraftClient.getInstance();
        ItemRenderer items = client.getItemRenderer();
        BakedModel baseModel = client.getBakedModelManager().getModel(base);
        BakedModel glowModel = client.getBakedModelManager().getModel(glow);
        matrices.push();
        // The item renderer has already moved the origin to the model's corner for us; renderItem moves it again for
        // each sprite, so step back to the centre first, or the sprite hangs half a block off the hand.
        matrices.translate(0.5f, 0.5f, 0.5f);
        items.renderItem(stack, mode, false, matrices, vertexConsumers, light, overlay, baseModel);
        // A hair toward the viewer so the light lies over the sprite, not inside it.
        matrices.translate(0, 0, 0.001f);
        items.renderItem(stack, mode, false, matrices, vertexConsumers, LightmapTextureManager.MAX_LIGHT_COORDINATE, overlay, glowModel);
        matrices.pop();
    }
}
