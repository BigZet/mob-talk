package io.github.rusflora.mixin;

import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the mod give cows the goal of eating hogweed. */
@Mixin(MobEntity.class)
public interface MobEntityAccessor {
    @Accessor("goalSelector") GoalSelector rusflora$goalSelector();
}
