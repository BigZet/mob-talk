package io.github.divineblock;

import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.JevGodDecider;
import io.github.divineblock.game.AltarBlock;
import io.github.divineblock.game.GameHooks;
import io.github.divineblock.game.PrayPayload;
import io.github.divineblock.game.Prayers;
import io.github.divineblock.world.ChurchWorldgen;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers the altar, the prayer packet and the god's watchers. The altar and the acts live on the server (the integrated
 * one in single player, or a dedicated one), the screens on the client. */
public final class DivineBlockMod implements ModInitializer {
    public static final String ID = "divineblock";
    public static final Logger LOG = LoggerFactory.getLogger("divineblock");
    public static final Block ALTAR = new AltarBlock(AbstractBlock.Settings.create().mapColor(MapColor.GOLD).strength(5f, 1200f)
            .luminance(state -> 12).sounds(BlockSoundGroup.AMETHYST_BLOCK).requiresTool());
    public static final Item ALTAR_ITEM = new BlockItem(ALTAR, new Item.Settings().rarity(Rarity.EPIC));
    private static volatile DivineConfig config;

    @Override public void onInitialize() {
        Registry.register(Registries.BLOCK, Identifier.of(ID, "altar"), ALTAR);
        Registry.register(Registries.ITEM, Identifier.of(ID, "altar"), ALTAR_ITEM);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(ALTAR_ITEM));
        io.github.divineblock.cemetery.CemeteryBlocks.register();
        PayloadTypeRegistry.playC2S().register(PrayPayload.ID, PrayPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(io.github.divineblock.game.PrayAudioPayload.ID, io.github.divineblock.game.PrayAudioPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(io.github.divineblock.game.GodLinePayload.ID, io.github.divineblock.game.GodLinePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(io.github.divineblock.game.BeamsPayload.ID, io.github.divineblock.game.BeamsPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(io.github.divineblock.game.FlipPayload.ID, io.github.divineblock.game.FlipPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(io.github.divineblock.game.TornadoPayload.ID, io.github.divineblock.game.TornadoPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(io.github.divineblock.game.FxPayload.ID, io.github.divineblock.game.FxPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(io.github.divineblock.game.CursePayload.ID, io.github.divineblock.game.CursePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(io.github.divineblock.game.VoiceStartPayload.ID, io.github.divineblock.game.VoiceStartPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(io.github.divineblock.game.SettingsPayload.ID, io.github.divineblock.game.SettingsPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(io.github.divineblock.game.SettingsPatchPayload.ID, io.github.divineblock.game.SettingsPatchPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(io.github.divineblock.game.VoiceStartPayload.ID,
                (payload, context) -> Prayers.voiceStarted(context.player(), payload.id(), payload.seconds()));
        io.github.divineblock.game.ServerPrayerVoice.register(); // Молитва голосом через распознавание Mob Talk на сервере.
        ServerPlayNetworking.registerGlobalReceiver(PrayPayload.ID, (payload, context) -> {
            if (payload.why()) Prayers.explain(context.player(), payload.altar());
            else Prayers.handle(context.player(), payload.altar(), payload.text());
        });
        JevGodDecider.diagnostics = line -> LOG.info("Божественный блок: {}", line);
        GameHooks.register();
        io.github.divineblock.game.ChurchSpawn.register(); // Новый мир — у Славянской церкви.
        io.github.divineblock.boss.DivineBosses.register();
        ChurchWorldgen.register();
        config(); // world generation reads the church settings from its own threads
        LOG.info("Божественный блок загружен");
    }

    public static Path configDirectory() { return FabricLoader.getInstance().getConfigDir(); }
    public static Path configFile() { return configDirectory().resolve("divineblock.json"); }

    public static DivineConfig config() {
        DivineConfig current = config;
        if (current == null) { current = loaded(); config = current; ChurchWorldgen.apply(current); }
        return current.copy();
    }
    /** The settings screen saved a new config: the next prayer reads it. */
    public static void reloadConfig() { DivineConfig loaded = loaded(); config = loaded; ChurchWorldgen.apply(loaded); }

    /** The server's settings a client got from a dedicated server (null in single player and on the server itself): laid over
     * the player's own file, so his game plays with the server's god and his own voice, microphone and effects. */
    private static volatile String serverValues;
    public static void serverValues(String json) { serverValues = json; reloadConfig(); }
    public static boolean onRemoteServer() { return serverValues != null; }
    private static DivineConfig loaded() {
        DivineConfig own = DivineConfig.load(configFile());
        String server = serverValues;
        return server == null ? own : io.github.divineblock.core.DivineSettings.overlay(own, server);
    }
}
