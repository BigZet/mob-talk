package io.github.divineblock.mixin;

import net.minecraft.entity.projectile.PersistentProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether an arrow is stuck in a block: it keeps its last velocity there, so the seraph's mind must not take it for one in flight. */
@Mixin(PersistentProjectileEntity.class)
public interface ProjectileGroundAccessor {
    @Accessor("inGround")
    boolean divineblock$inGround();
}
