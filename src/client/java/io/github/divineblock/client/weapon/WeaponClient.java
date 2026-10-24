package io.github.divineblock.client.weapon;

import io.github.divineblock.weapon.DivineWeapons;
import io.github.divineblock.weapon.WeaponKind;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.entity.FlyingItemEntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Vector3f;

/** The weapons on the client: each one's sprite drawn in the hand, in the inventory and on the ground by
 * {@link WeaponRenderer}, the sling's stone as a flying item, the thrown spear by {@link ThrownSpearRenderer}, and the
 * held weapons' own airs: the cherub's sword sheds flame, the archistrategos' sword holy sparks, the sword of War drips
 * red, Gideon's torch smoulders. */
public final class WeaponClient {
    private static final DustParticleEffect BLOOD = new DustParticleEffect(new Vector3f(0.55f, 0.04f, 0.04f), 0.9f);

    private WeaponClient() { }

    public static void register() {
        ModelLoadingPlugin.register(context -> context.addModels(WeaponRenderer.sprites()));
        for (WeaponKind kind : WeaponKind.values()) BuiltinItemRendererRegistry.INSTANCE.register(DivineWeapons.item(kind), new WeaponRenderer(kind));
        EntityRendererRegistry.register(DivineWeapons.SLING_STONE_ENTITY, context -> new FlyingItemEntityRenderer<>(context, 0.75f, true));
        EntityRendererRegistry.register(DivineWeapons.THROWN_SPEAR, ThrownSpearRenderer::new);
        ClientTickEvents.END_CLIENT_TICK.register(WeaponClient::tick);
    }

    private static void tick(MinecraftClient client) {
        if (client.world == null || client.isPaused()) return;
        Entity camera = client.getCameraEntity();
        if (camera == null) return;
        for (PlayerEntity player : client.world.getPlayers()) {
            if (player.isInvisible() || player.squaredDistanceTo(camera) > 48 * 48) continue;
            // In one's own first person the particles would hang before the eye; the sprite's glow does it there.
            if (player == client.player && client.options.getPerspective() == Perspective.FIRST_PERSON) continue;
            for (Hand hand : Hand.values()) {
                WeaponKind kind = DivineWeapons.kindOf(player.getStackInHand(hand));
                if (kind != null) air(client, player, hand, kind);
            }
        }
    }

    private static void air(MinecraftClient client, PlayerEntity player, Hand hand, WeaponKind kind) {
        Random random = player.getRandom();
        ParticleEffect effect;
        double every, up;
        switch (kind) {
            case CHERUB_SWORD -> { effect = random.nextInt(5) == 0 ? ParticleTypes.SMOKE : ParticleTypes.FLAME; every = 0.7; up = 0.02; }
            case ARCHISTRATEGOS_SWORD -> { effect = ParticleTypes.END_ROD; every = 0.12; up = 0.01; }
            case WAR_SWORD -> { effect = BLOOD; every = 0.35; up = -0.02; }
            case GIDEON_JAR -> { effect = random.nextInt(4) == 0 ? ParticleTypes.SMOKE : ParticleTypes.SMALL_FLAME; every = 0.3; up = 0.02; }
            default -> { return; }
        }
        if (random.nextDouble() > every) return;
        Vec3d at = hold(player, hand, random.nextDouble());
        client.world.addParticle(effect, at.x, at.y, at.z, (random.nextDouble() - 0.5) * 0.01, up, (random.nextDouble() - 0.5) * 0.01);
    }

    /** About where the weapon is, at the hand by the body's turn; {@code along} runs from the grip (0) toward the point (1). */
    private static Vec3d hold(PlayerEntity player, Hand hand, double along) {
        boolean right = (player.getMainArm() == Arm.RIGHT) == (hand == Hand.MAIN_HAND);
        float side = right ? 1f : -1f;
        float yaw = player.bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        Vec3d sideVec = new Vec3d(-MathHelper.cos(yaw), 0, -MathHelper.sin(yaw)).multiply(side * 0.38);
        Vec3d forward = new Vec3d(-MathHelper.sin(yaw), 0, MathHelper.cos(yaw)).multiply(0.25 + 0.5 * along);
        return player.getPos().add(sideVec).add(forward).add(0, (player.isInSneakingPose() ? 0.55 : 0.75) + 0.35 * along, 0);
    }
}
