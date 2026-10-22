package io.github.divineblock.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.divineblock.DivineBlockMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.processor.StructureProcessor;
import net.minecraft.structure.processor.StructureProcessorType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;

/** Stands a cemetery template on its levelled ground ({@link CemeteryGround}): a plot piece (trunk = -1) column by column,
 * its layer y 0 on the target ground; a tree (trunk = its crown radius, the trunk stands at (r, 0, r) of the template) whole,
 * its foot one above the ground under the trunk. Where no cemetery is planned (a bare /place template) nothing moves. */
public final class CemeteryGroundProcessor extends StructureProcessor {
    public static final MapCodec<CemeteryGroundProcessor> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(Codec.INT.optionalFieldOf("trunk", -1).forGetter(p -> p.trunk)).apply(instance, CemeteryGroundProcessor::new));
    public static final StructureProcessorType<CemeteryGroundProcessor> TYPE = () -> CODEC;
    private final int trunk;

    public CemeteryGroundProcessor(int trunk) { this.trunk = trunk; }

    static void register() { Registry.register(Registries.STRUCTURE_PROCESSOR, Identifier.of(DivineBlockMod.ID, "cemetery_ground"), TYPE); }

    @Override public StructureTemplate.StructureBlockInfo process(WorldView world, BlockPos pos, BlockPos pivot, StructureTemplate.StructureBlockInfo original,
            StructureTemplate.StructureBlockInfo current, StructurePlacementData data) {
        BlockPos at = current.pos();
        int y;
        if (trunk < 0) {
            int ground = CemeteryGround.target(at.getX(), at.getZ());
            if (ground == Integer.MIN_VALUE) return current;
            y = ground + (at.getY() - pos.getY());
        } else {
            BlockPos foot = StructureTemplate.transform(data, new BlockPos(trunk, 0, trunk)).add(pos);
            int ground = CemeteryGround.target(foot.getX(), foot.getZ());
            if (ground == Integer.MIN_VALUE) return current;
            y = at.getY() + (ground + 1 - pos.getY());
        }
        if (y == at.getY()) return current;
        return new StructureTemplate.StructureBlockInfo(new BlockPos(at.getX(), y, at.getZ()), current.state(), current.nbt());
    }

    @Override protected StructureProcessorType<?> getType() { return TYPE; }
}
