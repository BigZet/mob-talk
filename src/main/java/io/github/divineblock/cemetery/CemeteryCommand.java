package io.github.divineblock.cemetery;

import io.github.divineblock.DivineBlockMod;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

/** /divineblock cemetery: places the cemetery where the player stands, for trying it out. Outside the church's biomes it still
 * places it but warns that a cemetery would never grow there on its own. */
public final class CemeteryCommand {
    private static final TagKey<Biome> CHURCH_BIOMES = TagKey.of(RegistryKeys.BIOME, Identifier.of(DivineBlockMod.ID, "has_structure/orthodox_church"));

    private CemeteryCommand() { }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(CommandManager.literal("divineblock")
                .then(CommandManager.literal("cemetery").requires(source -> source.hasPermissionLevel(2)).executes(context -> place(context.getSource())))));
    }

    private static int place(ServerCommandSource source) {
        BlockPos pos = BlockPos.ofFloored(source.getPosition());
        if (!source.getWorld().getBiome(pos).isIn(CHURCH_BIOMES))
            source.sendFeedback(() -> Text.literal("Здесь церковь с кладбищем сама не появится: биом не русский. Ставлю всё равно.")
                    .formatted(Formatting.YELLOW), false);
        source.getServer().getCommandManager().executeWithPrefix(source,
                String.format("place structure %s:cemetery %d %d %d", DivineBlockMod.ID, pos.getX(), pos.getY(), pos.getZ()));
        return 1;
    }
}
