package io.github.mobtalk.game.mixin;

import java.util.List;
import net.minecraft.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets {@link io.github.mobtalk.game.CityWorldgen} add the cities to the villages' structure set. */
@Mixin(StructureSet.class)
public interface StructureSetAccessor {
    @Mutable @Accessor("structures") void mobtalk$setStructures(List<StructureSet.WeightedEntry> structures);
}
