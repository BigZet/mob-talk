package io.github.mobtalk.game.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.structure.pool.StructurePool;
import net.minecraft.structure.pool.StructurePoolElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The casino joins the villages' house pools (CasinoWorldgen): the pool draws from this list, one entry per unit of weight. */
@Mixin(StructurePool.class)
public interface CasinoPoolAccessor {
    @Accessor("elements") ObjectArrayList<StructurePoolElement> mobtalk$casinoElements();
}
