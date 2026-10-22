package io.github.divineblock.world;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import net.minecraft.loot.LootTable;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.structure.StructurePieceType;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.chunk.placement.StructurePlacementType;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureType;

/** Registers the church structure (data: worldgen/structure/orthodox_church, worldgen/structure_set/orthodox_churches) and
 * keeps the two settings world generation reads on its own threads: whether churches appear and how far apart. */
public final class ChurchWorldgen {
    public static final int DEFAULT_SPACING = 64;
    public static final StructureType<ChurchStructure> CHURCH_TYPE = () -> ChurchStructure.CODEC;
    public static final StructurePieceType CHURCH_PIECE = ChurchPiece::new, CEMETERY_PIECE = CemeteryPiece::new, CLEARING_PIECE = ClearingPiece::new,
            LEVELING_PIECE = LevelingPiece::new;
    public static final StructurePlacementType<ChurchPlacement> PLACEMENT_TYPE = () -> ChurchPlacement.CODEC;
    public static final RegistryKey<Structure> STRUCTURE = RegistryKey.of(RegistryKeys.STRUCTURE, Identifier.of(DivineBlockMod.ID, "orthodox_church"));
    public static final RegistryKey<LootTable> LOOT = RegistryKey.of(RegistryKeys.LOOT_TABLE, Identifier.of(DivineBlockMod.ID, "chests/orthodox_church"));

    private static volatile boolean enabled = true;
    private static volatile int spacing = DEFAULT_SPACING;

    private ChurchWorldgen() { }

    public static void register() {
        Registry.register(Registries.STRUCTURE_TYPE, Identifier.of(DivineBlockMod.ID, "orthodox_church"), CHURCH_TYPE);
        Registry.register(Registries.STRUCTURE_PIECE, Identifier.of(DivineBlockMod.ID, "church"), CHURCH_PIECE);
        Registry.register(Registries.STRUCTURE_PIECE, Identifier.of(DivineBlockMod.ID, "church_cemetery"), CEMETERY_PIECE);
        Registry.register(Registries.STRUCTURE_PIECE, Identifier.of(DivineBlockMod.ID, "church_clearing"), CLEARING_PIECE);
        Registry.register(Registries.STRUCTURE_PIECE, Identifier.of(DivineBlockMod.ID, "cemetery_leveling"), LEVELING_PIECE);
        Registry.register(Registries.STRUCTURE_TYPE, Identifier.of(DivineBlockMod.ID, "cemetery"), CemeteryStructure.TYPE);
        CemeteryGroundProcessor.register();
        Registry.register(Registries.STRUCTURE_PLACEMENT, Identifier.of(DivineBlockMod.ID, "church_spread"), PLACEMENT_TYPE);
    }

    /** The settings were (re)loaded. */
    public static void apply(DivineConfig config) { enabled = config.churches; spacing = config.churchSpacing; }

    public static boolean enabled() { return enabled; }
    public static int spacing() { return spacing; }
    /** The guaranteed gap inside a cell: a third of the spacing (vanilla keeps separation below spacing). */
    public static int separation(int spacing) { return Math.max(4, spacing / 3); }
}
