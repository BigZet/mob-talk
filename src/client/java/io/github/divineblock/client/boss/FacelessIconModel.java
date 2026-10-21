package io.github.divineblock.client.boss;

import io.github.divineblock.boss.FacelessIconEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;

/** Stub: the real model comes in this boss's own change. */
public final class FacelessIconModel extends BossModel<FacelessIconEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "faceless_icon"), "main");

    public FacelessIconModel(ModelPart root) { super(root); }

    /** Client-side hooks for this boss's affliction (called once from {@link BossClient#register}). */
    static void registerClient() { }

    @Override protected void animate(FacelessIconEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) { }
}
