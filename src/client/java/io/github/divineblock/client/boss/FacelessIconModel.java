package io.github.divineblock.client.boss;

import io.github.divineblock.boss.FacelessIconEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Безликий лик: the icon floats and bobs, tilts a little toward its foe's height, its lampada swings; while casting it
 * trembles, and for the ultimate it rises and shakes. */
public final class FacelessIconModel extends BossModel<FacelessIconEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "faceless_icon"), "main");
    private final ModelPart icon, lampada;

    public FacelessIconModel(ModelPart root) {
        super(root);
        icon = part("icon"); lampada = part("lampada");
    }

    /** «Взор лика» on the client lives in {@link BossClient}: nothing more to hook. */
    static void registerClient() { }

    @Override protected void animate(FacelessIconEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        icon.pivotY += MathHelper.sin(age * 0.08f) * 1.2f;
        icon.roll = MathHelper.sin(age * 0.03f) * 0.03f;
        icon.pitch = headPitch * 0.25f;
        lampada.roll = MathHelper.sin(age * 0.09f) * 0.12f - icon.roll;
        lampada.pitch = MathHelper.cos(age * 0.07f) * 0.08f - icon.pitch;
        int cast = boss.castState();
        if (cast == 1) {
            icon.roll += MathHelper.sin(age * 1.7f) * 0.02f;
            icon.pitch -= 0.08f;
        } else if (cast == 2) {
            icon.pivotY -= 4f;
            icon.roll += MathHelper.sin(age * 2.3f) * 0.04f;
            lampada.roll += MathHelper.sin(age * 0.9f) * 0.25f;
        }
    }
}
