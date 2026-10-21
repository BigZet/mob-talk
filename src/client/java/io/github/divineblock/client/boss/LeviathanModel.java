package io.github.divineblock.client.boss;

import io.github.divineblock.boss.LeviathanEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Левиафан: a wave runs down the six segments of the body (faster and wider while it swims), the side fins row, the maw
 * opens for every cast and gapes for «Пучина», when the head rears back and the whole body coils. */
public final class LeviathanModel extends BossModel<LeviathanEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "leviathan"), "main");
    private final ModelPart neck, head, jaw, finLeft, finRight;
    private final ModelPart[] body;

    public LeviathanModel(ModelPart root) {
        super(root);
        neck = part("neck"); head = part("head"); jaw = part("jaw"); finLeft = part("fin_left"); finRight = part("fin_right");
        body = new ModelPart[] { part("seg1"), part("seg2"), part("seg3"), part("seg4"), part("seg5"), part("tail") };
    }

    /** «Тяжесть глубин» sinks the player on the client already ({@link BossClient}); nothing more to hook. */
    static void registerClient() { }

    @Override protected void animate(LeviathanEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        int cast = boss.castState();
        float move = Math.min(1f, limbDistance * 2f);
        float speed = (boss.isTouchingWater() ? 0.14f : 0.08f) + move * 0.12f;
        float amp = 0.1f + move * 0.16f;
        if (cast == 2) { speed = 0.3f; amp = 0.32f; }
        for (int i = 0; i < body.length; i++) {
            body[i].yaw = MathHelper.sin(age * speed - i * 0.9f) * amp;
            body[i].pitch = MathHelper.sin(age * speed * 0.5f - i * 0.7f) * 0.05f;
        }
        neck.yaw = MathHelper.sin(age * speed + 0.9f) * amp * 0.3f;
        head.yaw = MathHelper.clamp(headYaw, -0.7f, 0.7f) * 0.8f - neck.yaw;
        head.pitch = MathHelper.clamp(headPitch, -0.6f, 0.6f) * 0.5f;
        jaw.pitch = 0.06f + MathHelper.sin(age * 0.06f) * 0.04f;
        finLeft.roll = MathHelper.sin(age * 0.2f) * 0.3f;
        finRight.roll = -finLeft.roll;
        if (cast == 1) {
            jaw.pitch = 0.55f + MathHelper.sin(age * 0.5f) * 0.08f;
            head.pitch -= 0.2f;
        }
        if (cast == 2) {
            jaw.pitch = 0.85f;
            head.pitch = -0.45f;
            neck.pitch = -0.18f;
        }
        if (handSwingProgress > 0) {
            float bite = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            jaw.pitch = Math.max(jaw.pitch, bite * 0.8f);
            head.pitch -= bite * 0.25f;
        }
    }
}
