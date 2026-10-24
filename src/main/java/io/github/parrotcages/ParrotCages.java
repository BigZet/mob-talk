package io.github.parrotcages;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.GlobalPos;

/**
 * Клетки для попугаев трёх размеров. Птица остаётся обычной живой сущностью внутри
 * клетки: стенки держат её физически, а блок-сущность возвращает её, если она
 * оказалась снаружи. Другие моды узнают пленника по тегу {@link CageLinks#CAGED_TAG}.
 * Попугай-зритель (Viewer Parrots) в клетке — узник со сроком: {@link Jail}.
 */
public final class ParrotCages implements ModInitializer {
	public static final String MOD_ID = "parrotcages";

	public static final ParrotCageBlock SMALL_CAGE = registerCage("small_parrot_cage", 1, 2, false, MapColor.IRON_GRAY);
	public static final ParrotCageBlock MEDIUM_CAGE = registerCage("medium_parrot_cage", 2, 2, false, MapColor.IRON_GRAY);
	public static final ParrotCageBlock LARGE_CAGE = registerCage("large_parrot_cage", 3, 3, false, MapColor.IRON_GRAY);
	public static final ParrotCageBlock SMALL_GOLDEN_CAGE = registerCage("small_golden_parrot_cage", 1, 2, true, MapColor.GOLD);
	public static final ParrotCageBlock MEDIUM_GOLDEN_CAGE = registerCage("medium_golden_parrot_cage", 2, 2, true, MapColor.GOLD);
	public static final ParrotCageBlock LARGE_GOLDEN_CAGE = registerCage("large_golden_parrot_cage", 3, 3, true, MapColor.GOLD);

	public static final BlockEntityType<ParrotCageBlockEntity> CAGE_BLOCK_ENTITY = Registry.register(
			Registries.BLOCK_ENTITY_TYPE, id("parrot_cage"),
			BlockEntityType.Builder.create(ParrotCageBlockEntity::new, cages()).build());

	/** Где стоит клетка пленника: по ней проверяется, что тег не устарел. */
	public static final AttachmentType<GlobalPos> CAGE_LINK = AttachmentRegistry.<GlobalPos>builder()
			.persistent(GlobalPos.CODEC).buildAndRegister(id("cage_link"));

	public static Identifier id(String path) { return Identifier.of(MOD_ID, path); }

	private static ParrotCageBlock registerCage(String path, int width, int height, boolean golden, MapColor color) {
		ParrotCageBlock block = Registry.register(Registries.BLOCK, id(path), new ParrotCageBlock(
				AbstractBlock.Settings.create().mapColor(color).strength(1.5f).sounds(golden ? BlockSoundGroup.METAL : BlockSoundGroup.LANTERN)
						.nonOpaque().pistonBehavior(PistonBehavior.BLOCK).allowsSpawning((s, w, p, t) -> false)
						.solidBlock((s, w, p) -> false).suffocates((s, w, p) -> false).blockVision((s, w, p) -> false),
				width, height, golden));
		Registry.register(Registries.ITEM, id(path), new BlockItem(block, new Item.Settings()));
		return block;
	}

	@Override
	public void onInitialize() {
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> {
			for (Block cage : cages()) entries.add(cage);
		});
		ServerEntityEvents.ENTITY_LOAD.register(CageLinks::onEntityLoad);
		ServerEntityEvents.ENTITY_UNLOAD.register(CageLinks::onEntityUnload);
		ServerTickEvents.END_SERVER_TICK.register(CageLinks::validateTagged);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> { CageLinks.clear(); Jail.stopped(); });
		ServerLifecycleEvents.SERVER_STARTED.register(Jail::started);
		JailSettings.load();
		Jail.install();
		net.fabricmc.loader.api.FabricLoader.getInstance().getObjectShare().put(AdminBridge.SHARED, new AdminBridge());
		JailCommands.register();
		// Клетку с узником ломают только стример и администратор.
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) -> {
			if (!(state.getBlock() instanceof ParrotCageBlock) || !(world instanceof ServerWorld server)
					|| !(world.getBlockEntity(ParrotCageBlock.origin(pos, state)) instanceof ParrotCageBlockEntity cage) || !cage.jailed()) return true;
			if (player instanceof ServerPlayerEntity sp && Roles.warden(sp)) return true;
			cage.refuse(server, player);
			return false;
		});
	}

	static ParrotCageBlock[] cages() {
		return new ParrotCageBlock[] {SMALL_CAGE, MEDIUM_CAGE, LARGE_CAGE, SMALL_GOLDEN_CAGE, MEDIUM_GOLDEN_CAGE, LARGE_GOLDEN_CAGE};
	}
}
