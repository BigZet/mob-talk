package io.github.rusflora.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.rusflora.block.ShrubKind;
import java.util.Locale;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.FeatureConfig;
import net.minecraft.world.gen.feature.util.FeatureContext;

/** Places one shrub on the ground at the feature origin (which a heightmap placement puts just above the surface). */
public final class ShrubFeature extends Feature<ShrubFeature.Config> {
    public record Config(ShrubKind kind) implements FeatureConfig {
        private static final Codec<ShrubKind> KIND = Codec.STRING.xmap(id -> ShrubKind.valueOf(id.toUpperCase(Locale.ROOT)), kind -> kind.id);
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(KIND.fieldOf("kind").forGetter(Config::kind)).apply(i, Config::new));
    }

    public ShrubFeature() { super(Config.CODEC); }

    @Override public boolean generate(FeatureContext<Config> context) {
        ShrubKind kind = context.getConfig().kind();
        BlockPos ground = context.getOrigin().down();
        if ((kind == ShrubKind.LILAC || kind == ShrubKind.WHITE_LILAC) && !clearOfBuildings(context.getWorld(), ground)) return false;
        return ShrubShapes.grow(context.getWorld(), ground, kind, context.getRandom(), true);
    }

    /** Lilac by a village: not on a path, field or in a doorway; nothing built within three blocks. */
    private static boolean clearOfBuildings(StructureWorldAccess world, BlockPos ground) {
        for (BlockPos pos : BlockPos.iterate(ground.add(-3, 0, -3), ground.add(3, 4, 3))) {
            BlockState state = world.getBlockState(pos);
            if (pos.getY() == ground.getY() ? !state.isIn(BlockTags.DIRT) || state.isOf(Blocks.FARMLAND) : !ShrubShapes.free(state)) return false;
        }
        return true;
    }
}
