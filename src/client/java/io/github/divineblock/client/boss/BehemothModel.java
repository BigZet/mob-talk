package io.github.divineblock.client.boss;

import io.github.divineblock.boss.BehemothEntity;
import io.github.divineblock.boss.BossEffects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Бегемот: a heavy four-legged gait, the cedar tail swaying, the head lowered to cast and to charge (a gallop then); for
 * «Сила чресл его» he rears on his hind legs and paws the air. The client also shakes the camera while the earth trembles. */
public final class BehemothModel extends BossModel<BehemothEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "behemoth"), "main");
    private final ModelPart body, head, jaw, tail, tailTip, frontRight, frontLeft, backRight, backLeft;
    private static float shakeYaw, shakePitch;

    public BehemothModel(ModelPart root) {
        super(root);
        body = part("body"); head = part("head"); jaw = part("jaw"); tail = part("tail"); tailTip = part("tail_tip");
        frontRight = part("leg_front_right"); frontLeft = part("leg_front_left");
        backRight = part("leg_back_right"); backLeft = part("leg_back_left");
    }

    /** The earth shakes: near a Behemoth that rears for its ultimate or charges, and for a moment after the quake catches you. */
    static void registerClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientPlayerEntity player = client.player;
            if (player == null || client.world == null || client.isPaused()) return;
            float strength = 0;
            for (BehemothEntity boss : client.world.getEntitiesByClass(BehemothEntity.class, player.getBoundingBox().expand(24), BehemothEntity::isAlive))
                if (boss.castState() == 2 || boss.isCharging()) strength = Math.max(strength, 1f - boss.distanceTo(player) / 24f);
            StatusEffectInstance dust = player.getStatusEffect(BossEffects.DUST);
            if (dust != null && dust.getDuration() > 20 * 15 - 30) strength = 1f;
            float yaw = strength * MathHelper.sin(player.age * 2.3f) * 1.2f, pitch = strength * MathHelper.cos(player.age * 1.9f) * 0.9f;
            if (yaw == shakeYaw && pitch == shakePitch) return;
            player.setYaw(player.getYaw() + yaw - shakeYaw);
            player.setPitch(MathHelper.clamp(player.getPitch() + pitch - shakePitch, -90f, 90f));
            shakeYaw = yaw;
            shakePitch = pitch;
        });
    }

    @Override protected void animate(BehemothEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        float amp = Math.min(1f, limbDistance);
        float step = MathHelper.cos(limbAngle * 0.5f) * 0.6f * amp;
        boolean charging = boss.isCharging();
        if (charging) step = MathHelper.cos(age * 0.9f) * 0.8f;
        frontRight.pitch = step;
        backLeft.pitch = step;
        frontLeft.pitch = -step;
        backRight.pitch = -step;
        body.roll = step * 0.04f;
        head.yaw = MathHelper.clamp(headYaw, -0.6f, 0.6f) * 0.6f;
        head.pitch = MathHelper.clamp(headPitch, -0.5f, 0.5f) * 0.4f + MathHelper.sin(age * 0.05f) * 0.03f;
        jaw.pitch = 0.04f + MathHelper.sin(age * 0.05f) * 0.03f;
        tail.yaw = MathHelper.sin(age * 0.06f) * 0.2f;
        tailTip.yaw = MathHelper.sin(age * 0.06f - 0.8f) * 0.25f;
        tail.pitch += MathHelper.sin(age * 0.04f) * 0.05f;
        if (charging) { head.pitch += 0.35f; body.pitch = 0.05f; tail.pitch -= 0.2f; }
        int cast = boss.castState();
        if (cast == 1) {
            head.pitch += 0.25f;
            jaw.pitch = 0.4f + MathHelper.sin(age * 0.4f) * 0.06f;
            tail.pitch -= 0.25f;
            tail.yaw = MathHelper.sin(age * 0.25f) * 0.45f;
        }
        if (cast == 2) {
            body.pitch = -0.28f + MathHelper.sin(age * 0.3f) * 0.04f;
            frontRight.pitch = -0.7f + MathHelper.sin(age * 0.5f) * 0.4f;
            frontLeft.pitch = -0.7f - MathHelper.sin(age * 0.5f) * 0.4f;
            head.pitch -= 0.2f;
            jaw.pitch = 0.6f;
            tail.pitch -= 0.3f;
        }
        if (handSwingProgress > 0) {
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            head.pitch += swing * 0.45f;
            jaw.pitch = Math.max(jaw.pitch, swing * 0.5f);
        }
    }
}
