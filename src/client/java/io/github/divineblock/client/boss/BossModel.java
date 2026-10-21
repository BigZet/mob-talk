package io.github.divineblock.client.boss;

import io.github.divineblock.boss.SacredBoss;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.model.SinglePartEntityModel;

/** A boss model: the generated part tree ({@code <Name>ModelData}) plus hand-written animation. Every frame starts from the
 * rest pose, so {@link #animate} only adds motion. */
public abstract class BossModel<T extends SacredBoss> extends SinglePartEntityModel<T> {
    protected final ModelPart root;

    protected BossModel(ModelPart root) { super(RenderLayer::getEntityCutoutNoCull); this.root = root; }

    @Override public ModelPart getPart() { return root; }

    /** A part anywhere in the tree by name, or an empty part when the model has none (so animation never crashes). */
    protected ModelPart part(String name) {
        return getChild(name).orElseGet(() -> new ModelPart(java.util.List.of(), java.util.Map.of()));
    }

    @Override public final void setAngles(T boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        root.traverse().forEach(ModelPart::resetTransform);
        animate(boss, limbAngle, limbDistance, age, headYaw * 0.017453292f, headPitch * 0.017453292f);
    }

    /** {@code castState} on the boss: 0 idle, 1 casting, 2 casting the ultimate; angles here are in radians. */
    protected abstract void animate(T boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch);
}
