package io.github.divineblock.client.boss;

import io.github.divineblock.boss.DestroyingAngelEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;

/** Stub: the real model comes in this boss's own change. */
public final class DestroyingAngelModel extends BossModel<DestroyingAngelEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "destroying_angel"), "main");

    public DestroyingAngelModel(ModelPart root) { super(root); }

    /** Client-side hooks for this boss's affliction (called once from {@link BossClient#register}). */
    static void registerClient() { }

    @Override protected void animate(DestroyingAngelEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) { }
}
