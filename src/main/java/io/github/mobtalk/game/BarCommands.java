package io.github.mobtalk.game;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.PoolStructurePiece;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.structure.Structure;

/** /bars — the bars found around players and their evenings; /bars sample &lt;village type&gt; &lt;count&gt; — lays out that many villages of
 * the type (without building them) and counts their bars, to check the bar's share in worldgen. Operators only. */
public final class BarCommands {
    private BarCommands() { }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(CommandManager.literal("bars")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> {
                    ServerWorld world = context.getSource().getWorld();
                    MobGameBridge bridge = MobGameBridge.active(context.getSource().getServer());
                    var lines = bridge == null ? java.util.List.<String>of() : bridge.barStatus(world);
                    context.getSource().sendFeedback(() -> Text.literal(lines.isEmpty() ? "Баров рядом с игроками нет" : String.join("\n", lines)), false);
                    return lines.size();
                })
                .then(CommandManager.literal("sample").then(CommandManager.argument("type", StringArgumentType.word())
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 500)).executes(context ->
                                sample(context.getSource(), StringArgumentType.getString(context, "type"), IntegerArgumentType.getInteger(context, "count"))))))));
    }

    private static int sample(ServerCommandSource source, String type, int count) {
        int[] histogram = sample(source.getWorld(), type, count);
        if (histogram == null) { source.sendError(Text.literal("Нет деревни village_" + type)); return 0; }
        source.sendFeedback(() -> Text.literal("village_" + type + ": деревень " + (histogram[0] + histogram[1] + histogram[2]) + ", без бара " + histogram[0]
                + ", с одним " + histogram[1] + ", с двумя и больше " + histogram[2]), false);
        return histogram[1];
    }

    /** Lays out {@code count} villages of the type at random spots (without building them): how many have no bar, one, two or more;
     * null when there is no such village type. */
    public static int[] sample(ServerWorld world, String type, int count) {
        Structure structure = world.getRegistryManager().get(RegistryKeys.STRUCTURE).get(Identifier.ofVanilla("village_" + type));
        if (structure == null) return null;
        int[] histogram = new int[3];
        java.util.Random random = new java.util.Random(type.hashCode());
        for (int i = 0; i < count; i++) {
            ChunkPos chunk = new ChunkPos(random.nextInt(20000) - 10000, random.nextInt(20000) - 10000);
            StructureStart start = structure.createStructureStart(world.getRegistryManager(), world.getChunkManager().getChunkGenerator(),
                    world.getChunkManager().getChunkGenerator().getBiomeSource(), world.getChunkManager().getNoiseConfig(), world.getStructureTemplateManager(),
                    world.getSeed() + i, chunk, 0, world, biome -> true);
            if (!start.hasChildren()) continue;
            int bars = 0;
            for (StructurePiece piece : start.getChildren())
                if (piece instanceof PoolStructurePiece pool && UniqueVillagePieces.unique(pool.getPoolElement())
                        && UniqueVillagePieces.template(pool.getPoolElement()).getPath().startsWith("bar/")) bars++; // the casino is unique too
            histogram[Math.min(2, bars)]++;
        }
        return histogram;
    }
}
