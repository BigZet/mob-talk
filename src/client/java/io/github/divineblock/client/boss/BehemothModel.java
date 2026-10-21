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

/** Бегемот: a slow, heavy walk on four pillar legs (diagonal pairs, the knees lifting, the barrel dipping and rolling at
 * every footfall, the ground shaking under the player nearby); the cedar tail sways stiffly, piece by piece; the head
 * follows the foe. To cast he tosses his head and opens his jaws; to charge he drops his head, horns first, and gallops;
 * for «Сила чресл его» he rears on his hind legs around the hips, paws the air and roars. Motion is added to the rest pose. */
public final class BehemothModel extends BossModel<BehemothEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "behemoth"), "main");
    private static final float STRIDE = 0.38f;
    private final ModelPart body, head, jaw, frontRight, frontLeft, backRight, backLeft, shinFrontRight, shinFrontLeft, shinBackRight, shinBackLeft;
    private final ModelPart[] tail = new ModelPart[5];
    private static float shakeYaw, shakePitch;

    public BehemothModel(ModelPart root) {
        super(root);
        body = part("body"); head = part("head"); jaw = part("jaw");
        frontRight = part("leg_front_right"); frontLeft = part("leg_front_left"); backRight = part("leg_back_right"); backLeft = part("leg_back_left");
        shinFrontRight = part("shin_front_right"); shinFrontLeft = part("shin_front_left"); shinBackRight = part("shin_back_right"); shinBackLeft = part("shin_back_left");
        for (int i = 0; i < tail.length; i++) tail[i] = part("tail_" + i);
    }

    /** The earth shakes: at every footfall of a walking Behemoth close by, hard near one that rears for its ultimate or
     * charges, and for a moment after the quake catches you. */
    static void registerClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientPlayerEntity player = client.player;
            if (player == null || client.world == null || client.isPaused()) return;
            float strength = 0;
            for (BehemothEntity boss : client.world.getEntitiesByClass(BehemothEntity.class, player.getBoundingBox().expand(24), BehemothEntity::isAlive)) {
                float near = 1f - boss.distanceTo(player) / 24f;
                if (boss.castState() == 2 || boss.isCharging()) strength = Math.max(strength, near);
                else if (player.isOnGround()) {
                    // A footfall lands twice per stride; the jolt fades quickly after it.
                    float step = Math.abs(MathHelper.cos(boss.limbAnimator.getPos() * STRIDE));
                    float walk = Math.min(1f, boss.limbAnimator.getSpeed() * 1.4f);
                    strength = Math.max(strength, near * walk * (float) Math.pow(step, 12) * 0.35f);
                }
            }
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
        int cast = boss.castState();
        boolean charging = boss.isCharging();
        float amp = Math.min(1f, limbDistance * 1.4f);
        float phase = limbAngle * STRIDE;
        if (charging) { phase = age * 0.55f; amp = 1.3f; }
        float swing = MathHelper.sin(phase), lift = MathHelper.cos(phase);

        // Diagonal pairs; a leg lifts its knee only while it swings forward.
        frontRight.pitch += swing * 0.36f * amp;
        backLeft.pitch += swing * 0.32f * amp;
        frontLeft.pitch -= swing * 0.36f * amp;
        backRight.pitch -= swing * 0.32f * amp;
        shinFrontRight.pitch += Math.max(0, -lift) * 0.55f * amp;
        shinBackLeft.pitch += Math.max(0, -lift) * 0.4f * amp;
        shinFrontLeft.pitch += Math.max(0, lift) * 0.55f * amp;
        shinBackRight.pitch += Math.max(0, lift) * 0.4f * amp;

        // The barrel dips at each footfall and rolls from side to side; he breathes slowly.
        float breath = MathHelper.sin(age * 0.06f);
        body.pivotY += lift * lift * 1.1f * amp + breath * 0.3f;
        body.roll += swing * 0.035f * amp;
        body.pitch += breath * 0.008f;

        head.yaw += MathHelper.clamp(headYaw, -0.6f, 0.6f) * 0.6f;
        head.pitch += MathHelper.clamp(headPitch, -0.5f, 0.5f) * 0.4f + breath * 0.03f + MathHelper.sin(phase * 2f) * 0.05f * amp;
        jaw.pitch += Math.max(0, MathHelper.sin(age * 0.05f)) * 0.06f;

        // The cedar tail: stiff, slow, each piece a little behind the one before.
        for (int i = 0; i < tail.length; i++) {
            tail[i].yaw += MathHelper.sin(age * 0.045f - i * 0.5f) * (0.06f + 0.02f * i) + swing * 0.04f * amp;
            tail[i].pitch += MathHelper.sin(age * 0.03f - i * 0.4f) * 0.025f;
        }

        if (charging) {
            head.pitch += 0.45f;
            body.pitch += 0.06f;
            tail[0].pitch += 0.35f;
            jaw.pitch += 0.15f;
        }
        if (cast == 1) {
            head.pitch -= 0.22f + MathHelper.sin(age * 0.4f) * 0.05f;
            jaw.pitch += 0.4f + MathHelper.sin(age * 0.4f) * 0.06f;
            tail[0].pitch += 0.2f;
            for (int i = 0; i < tail.length; i++) tail[i].yaw += MathHelper.sin(age * 0.25f - i * 0.6f) * 0.12f;
        }
        if (cast == 2) {
            // He rears around the hips, the forelegs paw the air, the tail braces on the ground.
            float paw = MathHelper.sin(age * 0.5f);
            body.pitch -= 0.55f + MathHelper.sin(age * 0.3f) * 0.04f;
            frontRight.pitch -= 0.75f + paw * 0.45f;
            frontLeft.pitch -= 0.75f - paw * 0.45f;
            shinFrontRight.pitch += 0.7f + paw * 0.2f;
            shinFrontLeft.pitch += 0.7f - paw * 0.2f;
            backRight.pitch += 0.2f;
            backLeft.pitch += 0.2f;
            head.pitch += 0.4f;
            jaw.pitch += 0.6f;
            tail[0].pitch -= 0.3f;
        }
        if (handSwingProgress > 0) {
            float butt = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            head.pitch += butt * 0.55f;
            jaw.pitch += butt * 0.3f;
            body.pitch += butt * 0.05f;
        }
    }
}
