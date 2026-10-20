package io.github.parrotcages;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.GlobalPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Связь «попугай — клетка». Клетка хранит UUID пленника, пленник — тег
 * {@link #CAGED_TAG} и позицию клетки. Тег — публичный контракт для других модов:
 * Viewer Parrots по нему не уводит птицу из клетки командами перемещения.
 * Позиция нужна, чтобы снять устаревший тег, если клетку сломали без пленника рядом.
 */
public final class CageLinks {
	public static final String CAGED_TAG = "parrotcages.caged";
	private static final int VALIDATE_INTERVAL = 40;

	/** Загруженные пленники: UUID → мир, в котором их видели. */
	private static final Map<UUID, ServerWorld> TAGGED = new HashMap<>();

	private CageLinks() { }

	static void clear() { TAGGED.clear(); }

	public static boolean isCaged(Entity entity) { return entity.getCommandTags().contains(CAGED_TAG); }

	static void link(Entity bird, ServerWorld world, BlockPos cage) {
		bird.addCommandTag(CAGED_TAG);
		bird.setAttached(ParrotCages.CAGE_LINK, GlobalPos.create(world.getRegistryKey(), cage.toImmutable()));
		TAGGED.put(bird.getUuid(), world);
	}

	static boolean isLinkedTo(Entity bird, ServerWorld world, BlockPos cage) {
		GlobalPos link = bird.getAttached(ParrotCages.CAGE_LINK);
		return isCaged(bird) && link != null && link.dimension().equals(world.getRegistryKey()) && link.pos().equals(cage);
	}

	static void unlink(Entity bird) {
		bird.removeCommandTag(CAGED_TAG);
		bird.removeAttached(ParrotCages.CAGE_LINK);
		TAGGED.remove(bird.getUuid());
	}

	static void onEntityLoad(Entity entity, ServerWorld world) {
		if (entity instanceof ParrotEntity && isCaged(entity)) TAGGED.put(entity.getUuid(), world);
	}

	/** Смерть или удаление пленника сразу освобождает клетку, не дожидаясь таймаута. */
	static void onEntityUnload(Entity entity, ServerWorld world) {
		if (!isCaged(entity)) return;
		TAGGED.remove(entity.getUuid());
		Entity.RemovalReason reason = entity.getRemovalReason();
		if (reason == null || !reason.shouldDestroy()) return;
		GlobalPos link = entity.getAttached(ParrotCages.CAGE_LINK);
		ServerWorld cageWorld = link == null ? null : world.getServer().getWorld(link.dimension());
		if (cageWorld != null && cageWorld.isChunkLoaded(ChunkPos.toLong(link.pos()))
				&& cageWorld.getBlockEntity(link.pos()) instanceof ParrotCageBlockEntity cage) {
			cage.forget(entity.getUuid());
		}
	}

	/** Снимает тег с попугая, чья клетка уже сломана или держит другую птицу. */
	static void validateTagged(MinecraftServer server) {
		if (server.getTicks() % VALIDATE_INTERVAL != 0 || TAGGED.isEmpty()) return;
		for (Iterator<Map.Entry<UUID, ServerWorld>> it = TAGGED.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, ServerWorld> entry = it.next();
			Entity bird = entry.getValue().getEntity(entry.getKey());
			if (bird == null || bird.isRemoved()) { it.remove(); continue; }
			if (!isCaged(bird)) { it.remove(); continue; }
			GlobalPos link = bird.getAttached(ParrotCages.CAGE_LINK);
			ServerWorld cageWorld = link == null ? null : server.getWorld(link.dimension());
			if (link == null || cageWorld == null) { release(bird); it.remove(); continue; }
			if (!cageWorld.isChunkLoaded(ChunkPos.toLong(link.pos()))) continue;
			boolean held = cageWorld.getBlockEntity(link.pos()) instanceof ParrotCageBlockEntity cage
					&& cage.occupant().filter(bird.getUuid()::equals).isPresent();
			if (!held) { release(bird); it.remove(); }
		}
	}

	private static void release(Entity bird) {
		bird.removeCommandTag(CAGED_TAG);
		bird.removeAttached(ParrotCages.CAGE_LINK);
		if (bird instanceof ParrotEntity parrot && parrot.isTamed()) {
			parrot.setSitting(false);
			parrot.setInSittingPose(false);
		}
	}
}
