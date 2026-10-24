package io.github.divineblock.client.weapon;

import io.github.divineblock.weapon.DivineWeapons;
import io.github.divineblock.weapon.WeaponKind;
import java.util.EnumMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.FlyingItemEntityRenderer;

/** The weapons on the client: each one's generated model drawn in the hand, in the inventory and on the ground by
 * {@link WeaponRenderer}, the sling's stone as a flying item, the thrown spear by {@link ThrownSpearRenderer}. */
public final class WeaponClient {
    private static final Map<WeaponKind, WeaponRenderer> RENDERERS = new EnumMap<>(WeaponKind.class);

    private WeaponClient() { }

    public static void register() {
        for (WeaponKind kind : WeaponKind.values()) {
            WeaponRenderer renderer = new WeaponRenderer(kind, model(kind));
            RENDERERS.put(kind, renderer);
            BuiltinItemRendererRegistry.INSTANCE.register(DivineWeapons.item(kind), renderer);
        }
        EntityRendererRegistry.register(DivineWeapons.SLING_STONE_ENTITY, context -> new FlyingItemEntityRenderer<>(context, 0.75f, true));
        EntityRendererRegistry.register(DivineWeapons.THROWN_SPEAR, ThrownSpearRenderer::new);
    }

    /** A fresh part tree of a weapon's generated model. */
    static ModelPart model(WeaponKind kind) {
        TexturedModelData data = switch (kind) {
            case DAVID_SLING -> DavidSlingModelData.create();
            case CHERUB_SWORD -> CherubSwordModelData.create();
            case ARCHISTRATEGOS_SWORD -> ArchistrategosSwordModelData.create();
            case GEORGE_SPEAR -> GeorgeSpearModelData.create();
            case NIMROD_BOW -> NimrodBowModelData.create();
            case SAMSON_JAWBONE -> SamsonJawboneModelData.create();
            case GIDEON_JAR -> GideonJarModelData.create();
            case WAR_SWORD -> WarSwordModelData.create();
            case JERICHO_HORN -> JerichoHornModelData.create();
            case ASPEN_STAKE -> AspenStakeModelData.create();
        };
        return data.createModel();
    }

    static WeaponRenderer renderer(WeaponKind kind) { return RENDERERS.get(kind); }
}
