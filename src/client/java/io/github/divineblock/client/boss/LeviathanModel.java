package io.github.divineblock.client.boss;

import io.github.divineblock.boss.LeviathanEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Левиафан: a travelling wave runs from the shoulders down fourteen rings of armour to the tail fin (faster and wider while
 * it swims), with a slower swell up and down; the fore and hind fins row, the crests ripple, the head follows the foe. The
 * maw opens for every cast and gapes for «Пучина», when the neck rears and the body coils harder. Posed without a mind (the
 * showcase), it surfaces: the back breaks the water in humps and the head rises over it. Motion is added to the rest pose. */
public final class LeviathanModel extends BossModel<LeviathanEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "leviathan"), "main");
    private static final int SEGMENTS = 14;
    private final ModelPart serpent, trunk, neck, head, jaw, tailFin, foreLeft, foreRight, hindLeft, hindRight, frillLeft, frillRight;
    private final ModelPart[] body = new ModelPart[SEGMENTS], crests = new ModelPart[SEGMENTS];

    public LeviathanModel(ModelPart root) {
        super(root);
        serpent = part("serpent"); trunk = part("trunk"); neck = part("neck"); head = part("head"); jaw = part("jaw"); tailFin = part("tail_fin");
        foreLeft = part("fin_fore_l"); foreRight = part("fin_fore_r"); hindLeft = part("fin_hind_l"); hindRight = part("fin_hind_r");
        frillLeft = part("frill_l"); frillRight = part("frill_r");
        for (int i = 0; i < SEGMENTS; i++) { body[i] = part("seg" + (i + 1)); crests[i] = part("seg" + (i + 1) + "_crest"); }
    }

    /** «Тяжесть глубин» sinks the player on the client already ({@link BossClient}); nothing more to hook. */
    static void registerClient() { }

    @Override protected void animate(LeviathanEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        int cast = boss.castState();
        boolean posed = boss.isAiDisabled();
        float move = Math.min(1f, limbDistance * 2f);
        float speed = (boss.isTouchingWater() ? 0.11f : 0.07f) + move * 0.1f;
        float amp = 0.07f + move * 0.07f;
        if (cast == 2) { speed = 0.24f; amp = 0.16f; }

        // The wave: each ring turns a little against the one before, so the bend travels down the chain to the tail.
        trunk.yaw += MathHelper.sin(age * speed + 0.6f) * amp * 0.5f;
        for (int i = 0; i < SEGMENTS; i++) {
            float lag = i * 0.55f, grow = 0.7f + i * 0.04f;
            body[i].yaw += MathHelper.sin(age * speed - lag) * amp * grow;
            body[i].pitch += MathHelper.sin(age * speed * 0.6f - i * 0.5f) * 0.025f;
            crests[i].roll += MathHelper.sin(age * 0.2f - i * 0.7f) * 0.06f;
        }
        tailFin.yaw += MathHelper.sin(age * speed - SEGMENTS * 0.55f) * amp * 1.4f;

        // The neck counters the wave so the head stays on the foe.
        neck.yaw -= MathHelper.sin(age * speed + 0.6f) * amp * 0.5f;
        head.yaw += MathHelper.clamp(headYaw, -0.8f, 0.8f) * 0.7f;
        head.pitch += MathHelper.clamp(headPitch, -0.6f, 0.6f) * 0.5f;
        neck.pitch += MathHelper.sin(age * 0.05f) * 0.03f;
        jaw.pitch += MathHelper.sin(age * 0.06f) * 0.04f;

        // Fins row; the frills flare with each breath.
        float row = MathHelper.sin(age * (0.12f + move * 0.15f)) * (0.18f + move * 0.2f);
        foreLeft.roll += row; foreRight.roll -= row;
        foreLeft.yaw -= row * 0.4f; foreRight.yaw += row * 0.4f;
        float hind = MathHelper.sin(age * 0.14f - 1.2f) * 0.15f;
        hindLeft.roll += hind; hindRight.roll -= hind;
        float flare = MathHelper.sin(age * 0.08f) * 0.08f;
        frillLeft.yaw += flare; frillRight.yaw -= flare;

        if (posed) {
            // Surfaced for the portrait: the back rides on the water in humps, the neck rears, the maw is open.
            serpent.pivotY -= 15.5f;
            for (int i = 0; i < SEGMENTS; i++) body[i].pitch += MathHelper.sin(i * 0.85f + 0.4f) * 0.16f;
            neck.pitch -= 0.42f;
            head.pitch += 0.22f;
            jaw.pitch += 0.32f;
            frillLeft.yaw += 0.2f; frillRight.yaw -= 0.2f;
        }
        if (cast == 1) {
            jaw.pitch += 0.45f + MathHelper.sin(age * 0.5f) * 0.06f;
            neck.pitch -= 0.12f;
            head.pitch -= 0.08f;
            frillLeft.yaw += 0.25f; frillRight.yaw -= 0.25f;
        }
        if (cast == 2) {
            jaw.pitch += 0.75f;
            neck.pitch -= 0.4f;
            head.pitch -= 0.2f;
            frillLeft.yaw += 0.35f; frillRight.yaw -= 0.35f;
            for (int i = 0; i < SEGMENTS; i++) body[i].yaw += 0.07f;
        }
        if (handSwingProgress > 0) {
            float bite = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            jaw.pitch += bite * 0.7f;
            neck.pitch += bite * 0.2f;
            head.pitch -= bite * 0.25f;
        }
    }
}
