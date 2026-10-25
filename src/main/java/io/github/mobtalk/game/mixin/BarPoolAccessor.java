package io.github.mobtalk.game.mixin;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.structure.pool.StructurePool;
import net.minecraft.structure.pool.StructurePoolElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;

/** A village houses pool's pieces, for {@link io.github.mobtalk.game.BarWorldgen} to add the bar. */
@Mixin(StructurePool.class)
public interface BarPoolAccessor {
    @Accessor("elements") ObjectArrayList<StructurePoolElement> mobtalk$elements();
    @Accessor("elementCounts") List<Pair<StructurePoolElement, Integer>> mobtalk$elementCounts();
    @Mutable @Accessor("elementCounts") void mobtalk$setElementCounts(List<Pair<StructurePoolElement, Integer>> counts);
}
