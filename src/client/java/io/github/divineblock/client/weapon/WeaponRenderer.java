package io.github.divineblock.client.weapon;

import io.github.divineblock.weapon.NimrodBowItem;
import io.github.divineblock.weapon.WeaponKind;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;

/** Draws a weapon as a vanilla-style flat sprite (models/item/weapon/, from scripts/weapons/sprites.py) wherever its item is
 * shown; the hand-held transforms come from the item's own model JSON. Fire and holy light are a second, animated sprite
 * drawn over it at full brightness. The bow shows its string drawn back with the arrow while it is pulled, the sling its
 * stone swung out while it is wound. */
public final class WeaponRenderer implements BuiltinItemRendererRegistry.DynamicItemRenderer {
    /** A plain stack for the glow pass, so an enchanted weapon's glint is not drawn twice. */
    private static final ItemStack PLAIN = new ItemStack(Items.STICK);
    /** The blades with a 32 px hand sprite of their own (*_hand), as the trident and the spyglass have a hand model. */
    static final Set<WeaponKind> BIG = EnumSet.of(WeaponKind.CHERUB_SWORD, WeaponKind.ARCHISTRATEGOS_SWORD, WeaponKind.WAR_SWORD,
            WeaponKind.GEORGE_SPEAR, WeaponKind.ASPEN_STAKE, WeaponKind.SAMSON_JAWBONE);
    /** Where the hand holds a sword sprite, from the item's centre (pixel 3.5, 12.5 of 16). */
    private static final float GRIP = 3.5f / 16f - 0.5f;
    private final WeaponKind kind;

    WeaponRenderer(WeaponKind kind) { this.kind = kind; }

    static Identifier sprite(String name) { return Identifier.of("divineblock", "item/weapon/" + name); }

    /** Every sprite model the renderers ask for: WeaponClient has them loaded besides the items' own models. */
    static List<Identifier> sprites() {
        List<Identifier> out = new ArrayList<>();
        for (WeaponKind kind : WeaponKind.values()) {
            out.add(sprite(kind.id));
            if (kind.glow) out.add(sprite(kind.id + "_glow"));
            if (BIG.contains(kind)) out.add(sprite(kind.id + "_hand"));
            if (BIG.contains(kind) && kind.glow) out.add(sprite(kind.id + "_hand_glow"));
        }
        for (int i = 0; i < 3; i++) out.add(sprite("nimrod_bow_pulling_" + i));
        out.add(sprite("david_sling_wound"));
        return out;
    }

    static BakedModel baked(String name) { return MinecraftClient.getInstance().getBakedModelManager().getModel(sprite(name)); }

    @Override public void render(ItemStack stack, ModelTransformationMode mode, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay) {
        MinecraftClient client = MinecraftClient.getInstance();
        boolean big = BIG.contains(kind) && inHand(mode);
        String name = big ? kind.id + "_hand" : name(stack, mode);
        matrices.push();
        matrices.translate(0.5f, 0.5f, 0.5f); // the item renderer moved to the corner; the sprite's own pass moves back
        if (big) { // the 32 px hand sprite at twice the size about the grip, so the hand keeps hold of it; in one's own
            // first person at a vanilla sword's size (finer pixels), as a bigger blade runs off the screen there
            float size = mode.isFirstPerson() ? 1f : 2f;
            matrices.translate(GRIP, GRIP, 0f);
            matrices.scale(size, size, 1f);
            matrices.translate(-GRIP, -GRIP, 0f);
        }
        client.getItemRenderer().renderItem(stack, ModelTransformationMode.NONE, false, matrices, vertexConsumers, light, overlay, baked(name));
        if (kind.glow)
            client.getItemRenderer().renderItem(PLAIN, ModelTransformationMode.NONE, false, matrices, vertexConsumers, LightmapTextureManager.MAX_LIGHT_COORDINATE, overlay,
                    baked((big ? kind.id + "_hand" : kind.id) + "_glow"));
        matrices.pop();
    }

    private static boolean inHand(ModelTransformationMode mode) {
        return mode.isFirstPerson() || mode == ModelTransformationMode.THIRD_PERSON_LEFT_HAND || mode == ModelTransformationMode.THIRD_PERSON_RIGHT_HAND;
    }

    /** Which sprite: the bow's pull and the sling's swing in the local player's hand, otherwise the plain one. */
    private String name(ItemStack stack, ModelTransformationMode mode) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (!inHand(mode) || player == null || !player.isUsingItem() || player.getActiveItem() != stack) return kind.id;
        int useTicks = stack.getMaxUseTime(player) - player.getItemUseTimeLeft();
        return switch (kind) {
            case NIMROD_BOW -> {
                float pull = NimrodBowItem.pull(useTicks);
                yield "nimrod_bow_pulling_" + (pull >= 0.9f ? 2 : pull >= 0.65f ? 1 : 0);
            }
            case DAVID_SLING -> "david_sling_wound";
            default -> kind.id;
        };
    }
}
