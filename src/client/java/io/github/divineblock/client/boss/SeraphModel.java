package io.github.divineblock.client.boss;

import io.github.divineblock.boss.SeraphEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Шестикрылый серафим: hovers bobbing, the two flight wings beat, the covering wings tremble over face and feet; when it
 * casts the flight wings sweep back, for the ultimate all six open wide and the halo turns; on the ground the covering
 * wings part and bare the face. */
public final class SeraphModel extends BossModel<SeraphEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "seraph"), "main");
    private final ModelPart body, halo, upperLeft, upperRight, left, right, lowerLeft, lowerRight, flames;

    public SeraphModel(ModelPart root) {
        super(root);
        body = part("body"); halo = part("halo"); flames = part("flames");
        upperLeft = part("upper_left_wing"); upperRight = part("upper_right_wing");
        left = part("left_wing"); right = part("right_wing");
        lowerLeft = part("lower_left_wing"); lowerRight = part("lower_right_wing");
    }

    /** «Опаление» is server-side (healing burns): nothing to hook on the client. */
    static void registerClient() { }

    @Override protected void animate(SeraphEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        boolean down = boss.isDescending();
        int cast = boss.castState();
        float beat = MathHelper.sin(age * (down ? 0.12f : 0.3f));
        body.pivotY += MathHelper.sin(age * 0.08f) * 1.2f;
        body.pitch = headPitch * 0.3f;
        left.roll += beat * 0.45f;
        right.roll -= beat * 0.45f;
        float tremble = MathHelper.sin(age * 0.9f) * 0.03f;
        upperLeft.roll += tremble; upperRight.roll -= tremble;
        lowerLeft.roll -= tremble; lowerRight.roll += tremble;
        flames.yaw = age * 0.2f;
        flames.pivotY += MathHelper.sin(age * 0.5f) * 0.4f;
        halo.roll = age * 0.02f;
        if (down) {
            left.roll -= 0.6f; right.roll += 0.6f;
            upperLeft.roll -= 0.6f; upperRight.roll += 0.6f;
            lowerLeft.roll -= 0.25f; lowerRight.roll += 0.25f;
        }
        if (cast == 1) { left.yaw -= 0.5f; right.yaw += 0.5f; }
        if (cast == 2) {
            left.roll -= 0.4f; right.roll += 0.4f;
            upperLeft.roll -= 0.8f; upperRight.roll += 0.8f;
            lowerLeft.roll -= 0.4f; lowerRight.roll += 0.4f;
            halo.roll = age * 0.15f;
        }
    }
}
