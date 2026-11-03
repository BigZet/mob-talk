package io.github.mobtalk.game.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The chunk holders the game keeps, for the roads' shutdown check (a holder whose reference count never comes back to zero keeps
 * the world from ever finishing its unloading on the way out). */
@Mixin(ServerChunkLoadingManager.class)
public interface ChunkHoldersAccessor {
    @Accessor("currentChunkHolders") Long2ObjectLinkedOpenHashMap<ChunkHolder> mobtalk$currentChunkHolders();
}
