package io.github.mobtalk.game;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import io.github.mobtalk.MobTalkMod;
import io.github.mobtalk.game.mixin.BarPoolAccessor;
import io.github.mobtalk.server.ServerConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.pool.StructurePool;
import net.minecraft.structure.pool.StructurePoolElement;
import net.minecraft.structure.processor.StructureProcessor;
import net.minecraft.structure.processor.StructureProcessorList;
import net.minecraft.structure.processor.StructureProcessorType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;
import java.util.ArrayList;
import java.util.List;

/** The village bar in worldgen: each vanilla village type's houses pool gets its bar (data/mobtalk/structure/bar/&lt;type&gt;.nbt,
 * made by tools/bar/bar.py), and a village has at most one ({@link UniqueVillagePieces}). The bar's weight is twice the
 * pool's, so the first house plots try it first and most villages get one. Its jukebox becomes the Alice speaker when that mod is
 * installed ({@code barAliceSpeaker}). */
public final class BarWorldgen {
    private BarWorldgen() { }
    public static final List<String> KINDS = List.of("plains", "desert", "savanna", "snowy", "taiga");
    /** The Alice speaker's block (the separate alicespeaker mod). */
    static final Identifier ALICE_SPEAKER = Identifier.of("alicespeaker", "speaker");
    private static StructureProcessorType<MusicProcessor> MUSIC;

    public static void register() {
        for (String kind : KINDS) UniqueVillagePieces.register(template(kind));
        BarCommands.register();
        MUSIC = Registry.register(Registries.STRUCTURE_PROCESSOR, Identifier.of("mobtalk", "bar_music"), () -> MusicProcessor.CODEC);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            Registry<StructurePool> pools = server.getRegistryManager().get(RegistryKeys.TEMPLATE_POOL);
            boolean on = ServerConfig.get().barsInVillages;
            int added = 0;
            for (String kind : KINDS) {
                StructurePool pool = pools.get(Identifier.ofVanilla("village/" + kind + "/houses"));
                if (pool != null && apply(pool, kind, on)) added++;
            }
            MobTalkMod.LOGGER.info("Mob Talk: бары в деревнях — {} ({} типов деревень)", on ? "включены" : "выключены", added);
        });
    }

    static Identifier template(String kind) { return Identifier.of("mobtalk", "bar/" + kind); }

    /** Puts the bar into one houses pool (or takes it out); true when the pool now has it. */
    private static boolean apply(StructurePool pool, String kind, boolean on) {
        BarPoolAccessor access = (BarPoolAccessor) pool;
        Identifier id = template(kind);
        access.mobtalk$elements().removeIf(element -> id.equals(UniqueVillagePieces.template(element)));
        List<Pair<StructurePoolElement, Integer>> counts = new ArrayList<>();
        int total = 0;
        for (Pair<StructurePoolElement, Integer> pair : access.mobtalk$elementCounts()) {
            if (id.equals(UniqueVillagePieces.template(pair.getFirst()))) continue;
            counts.add(pair);
            total += pair.getSecond();
        }
        if (on) {
            RegistryEntry<StructureProcessorList> processors = RegistryEntry.of(new StructureProcessorList(List.of(MusicProcessor.INSTANCE)));
            StructurePoolElement bar = StructurePoolElement.ofProcessedSingle(id.toString(), processors).apply(StructurePool.Projection.RIGID);
            int weight = Math.max(1, total * 2);
            counts.add(Pair.of(bar, weight));
            for (int i = 0; i < weight; i++) access.mobtalk$elements().add(bar);
        }
        access.mobtalk$setElementCounts(List.copyOf(counts));
        return on;
    }

    /** The bar's music: its jukebox, or the Alice speaker in its place when that mod is installed and the setting allows. */
    static final class MusicProcessor extends StructureProcessor {
        static final MusicProcessor INSTANCE = new MusicProcessor();
        static final MapCodec<MusicProcessor> CODEC = MapCodec.unit(INSTANCE);

        @Override public StructureTemplate.StructureBlockInfo process(WorldView world, BlockPos pos, BlockPos pivot, StructureTemplate.StructureBlockInfo original,
                                                                   StructureTemplate.StructureBlockInfo current, StructurePlacementData data) {
            if (current == null || !current.state().isOf(Blocks.JUKEBOX) || !aliceSpeaker()) return current;
            Block speaker = Registries.BLOCK.get(ALICE_SPEAKER);
            return speaker == Blocks.AIR ? current : new StructureTemplate.StructureBlockInfo(current.pos(), speaker.getDefaultState(), null);
        }
        @Override protected StructureProcessorType<?> getType() { return MUSIC; }
    }

    /** The Alice speaker stands in the bar instead of the jukebox. */
    static boolean aliceSpeaker() {
        return ServerConfig.get().barAliceSpeaker && FabricLoader.getInstance().isModLoaded("alicespeaker") && Registries.BLOCK.containsId(ALICE_SPEAKER);
    }
}
