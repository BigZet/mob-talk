package io.github.mobtalk.game.mixin;

import com.mojang.datafixers.util.Either;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.pool.SinglePoolElement;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The template a village piece is built from, for {@link io.github.mobtalk.game.UniqueVillagePieces}. */
@Mixin(SinglePoolElement.class)
public interface SinglePoolElementAccessor {
    @Accessor("location") Either<Identifier, StructureTemplate> mobtalk$location();
}
