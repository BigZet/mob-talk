package io.github.divineblock.client.weapon;

import io.github.divineblock.weapon.DivineWeapons;
import io.github.divineblock.weapon.NimrodBowItem;
import io.github.divineblock.weapon.SlingItem;
import io.github.divineblock.weapon.WeaponKind;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.item.ModelPredicateProviderRegistry;
import net.minecraft.client.render.entity.FlyingItemEntityRenderer;
import net.minecraft.util.Identifier;

/** The weapons on the client. Their sprites are item models held like the vanilla sword, only bigger; the fiery and the
 * holy carry a second sprite of light drawn at full brightness ({@link WeaponGlowRenderer}); the bow and the sling swap
 * sprites as they are drawn (model predicates); the sling's stone and the thrown spear have their renderers. */
public final class WeaponClient {
    /** Weapons with a glow sprite (the same list as GLOWING in scripts/weapons/resources.py). */
    static final Set<WeaponKind> GLOWING = EnumSet.of(WeaponKind.CHERUB_SWORD, WeaponKind.ARCHISTRATEGOS_SWORD, WeaponKind.WAR_SWORD, WeaponKind.GIDEON_JAR, WeaponKind.GEORGE_SPEAR);

    private WeaponClient() { }

    public static void register() {
        List<Identifier> extra = new ArrayList<>();
        for (WeaponKind kind : GLOWING) {
            extra.add(Identifier.of("divineblock", "item/" + kind.id + "_base"));
            extra.add(Identifier.of("divineblock", "item/" + kind.id + "_glow"));
            BuiltinItemRendererRegistry.INSTANCE.register(DivineWeapons.item(kind), new WeaponGlowRenderer(kind));
        }
        ModelLoadingPlugin.register(context -> context.addModels(extra));
        ModelPredicateProviderRegistry.register(DivineWeapons.NIMROD_BOW, Identifier.ofVanilla("pulling"),
                (stack, world, entity, seed) -> entity != null && entity.isUsingItem() && entity.getActiveItem() == stack ? 1f : 0f);
        ModelPredicateProviderRegistry.register(DivineWeapons.NIMROD_BOW, Identifier.ofVanilla("pull"),
                (stack, world, entity, seed) -> entity == null || entity.getActiveItem() != stack ? 0f : NimrodBowItem.pull(stack.getMaxUseTime(entity) - entity.getItemUseTimeLeft()));
        ModelPredicateProviderRegistry.register(DivineWeapons.DAVID_SLING, Identifier.ofVanilla("pulling"),
                (stack, world, entity, seed) -> entity != null && entity.isUsingItem() && entity.getActiveItem() == stack ? 1f : 0f);
        ModelPredicateProviderRegistry.register(DivineWeapons.DAVID_SLING, Identifier.ofVanilla("pull"),
                (stack, world, entity, seed) -> entity == null || entity.getActiveItem() != stack ? 0f
                        : Math.min(1f, (stack.getMaxUseTime(entity) - entity.getItemUseTimeLeft()) / (float) SlingItem.WIND_TICKS));
        EntityRendererRegistry.register(DivineWeapons.SLING_STONE_ENTITY, context -> new FlyingItemEntityRenderer<>(context, 0.75f, true));
        EntityRendererRegistry.register(DivineWeapons.THROWN_SPEAR, ThrownSpearRenderer::new);
    }
}
