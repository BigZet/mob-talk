package io.github.parrotcages;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.GlobalPos;

/**
 * Клетки для попугаев трёх размеров. Птица остаётся обычной живой сущностью внутри
 * клетки: стенки держат её физически, а блок-сущность возвращает её, если она
 * оказалась снаружи. Другие моды узнают пленника по тегу {@link CageLinks#CAGED_TAG}.
 */
public final class ParrotCages implements ModInitializer {
	public static final String MOD_ID = "parrotcages";

	public static final ParrotCageBlock SMALL_CAGE = registerCage("small_parrot_cage", 1, 2, MapColor.OAK_TAN);
	public static final ParrotCageBlock MEDIUM_CAGE = registerCage("medium_parrot_cage", 2, 2, MapColor.SPRUCE_BROWN);
	public static final ParrotCageBlock LARGE_CAGE = registerCage("large_parrot_cage", 3, 3, MapColor.BROWN);

	public static final BlockEntityType<ParrotCageBlockEntity> CAGE_BLOCK_ENTITY = Registry.register(
			Registries.BLOCK_ENTITY_TYPE, id("parrot_cage"),
			BlockEntityType.Builder.create(ParrotCageBlockEntity::new, SMALL_CAGE, MEDIUM_CAGE, LARGE_CAGE).build());

	/** Где стоит клетка пленника: по ней проверяется, что тег не устарел. */
	public static final AttachmentType<GlobalPos> CAGE_LINK = AttachmentRegistry.<GlobalPos>builder()
			.persistent(GlobalPos.CODEC).buildAndRegister(id("cage_link"));

	public static Identifier id(String path) { return Identifier.of(MOD_ID, path); }

	private static ParrotCageBlock registerCage(String path, int width, int height, MapColor color) {
		ParrotCageBlock block = Registry.register(Registries.BLOCK, id(path), new ParrotCageBlock(
				AbstractBlock.Settings.create().mapColor(color).strength(1.5f).sounds(BlockSoundGroup.WOOD)
						.nonOpaque().pistonBehavior(PistonBehavior.BLOCK).allowsSpawning((s, w, p, t) -> false)
						.solidBlock((s, w, p) -> false).suffocates((s, w, p) -> false).blockVision((s, w, p) -> false),
				width, height));
		Registry.register(Registries.ITEM, id(path), new BlockItem(block, new Item.Settings()));
		return block;
	}

	@Override
	public void onInitialize() {
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> {
			entries.add(SMALL_CAGE);
			entries.add(MEDIUM_CAGE);
			entries.add(LARGE_CAGE);
		});
		ServerEntityEvents.ENTITY_LOAD.register(CageLinks::onEntityLoad);
		ServerEntityEvents.ENTITY_UNLOAD.register(CageLinks::onEntityUnload);
		ServerTickEvents.END_SERVER_TICK.register(CageLinks::validateTagged);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> CageLinks.clear());
	}

	static Block[] cages() { return new Block[] {SMALL_CAGE, MEDIUM_CAGE, LARGE_CAGE}; }
}
