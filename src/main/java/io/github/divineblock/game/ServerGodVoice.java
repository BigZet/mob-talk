package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.ApiKeys;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.GodVoice;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/** The god speaks through the server on a dedicated server: the server synthesises his line with its own key (and proxy)
 * and streams it to everyone near the praying player, so a friend without a key of his own hears the god, everyone around
 * hears him too, and the act still waits for the last word (the praying player's client answers as for its own voice).
 * Without a server key, or in single player, each client voices the line itself as before ({@link GodLinePayload}). */
final class ServerGodVoice {
    private ServerGodVoice() { }
    /** How far from the praying player the god is heard (and his words are read in chat). */
    static final double RADIUS = 64;
    private static final ExecutorService WORKER = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "divineblock-server-voice"); t.setDaemon(true); return t; });
    private static int nextStream = 1;

    /** Whether this server voices the god itself: a dedicated server with an OpenRouter key and the voice on. */
    static boolean available(MinecraftServer server, DivineConfig config) {
        return server != null && server.isDedicated() && config.voice
                && ApiKeys.status(DivineBlockMod.configDirectory(), ApiKeys.Provider.OPENROUTER).source() != ApiKeys.Source.NONE;
    }

    /** The players who hear a line said to {@code speaker}: him and everyone in his world within {@link #RADIUS}. */
    static List<ServerPlayerEntity> listeners(ServerPlayerEntity speaker) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        out.add(speaker);
        for (ServerPlayerEntity other : speaker.getServerWorld().getPlayers())
            if (other != speaker && other.squaredDistanceTo(speaker) <= RADIUS * RADIUS) out.add(other);
        return out;
    }

    /** Streams {@code line} to the listeners; the speaker's client acknowledges with {@code ack}. {@code failed} runs on the
     * server thread when not a single piece could be made (no key after all, the service down): the caller falls back. */
    static void speak(ServerPlayerEntity speaker, int ack, String line, Runnable failed) {
        MinecraftServer server = speaker.getServer();
        DivineConfig config = DivineBlockMod.config();
        int stream = nextStream++;
        List<UUID> hearers = listeners(speaker).stream().map(ServerPlayerEntity::getUuid).toList();
        WORKER.execute(() -> {
            int seq = 0;
            try (InputStream in = new GodVoice(config, DivineBlockMod.configDirectory()).stream(line)) {
                byte[] piece = new byte[GodAudioPayload.CHUNK];
                int filled = 0, read;
                while ((read = in.read(piece, filled, piece.length - filled)) > 0) {
                    filled += read;
                    if (filled < piece.length) continue;
                    send(server, hearers, speaker.getUuid(), stream, ack, seq++, false, piece.clone());
                    filled = 0;
                }
                send(server, hearers, speaker.getUuid(), stream, ack, seq, true, Arrays.copyOf(piece, filled & ~1));
            } catch (Exception failure) {
                DivineBlockMod.LOG.info("Божественный блок: сервер не озвучил бога: {}", failure.getMessage());
                if (seq == 0) server.execute(failed);
                else send(server, hearers, speaker.getUuid(), stream, ack, seq, true, new byte[0]);
            }
        });
    }

    private static void send(MinecraftServer server, List<UUID> hearers, UUID speaker, int stream, int ack, int seq, boolean last, byte[] pcm) {
        server.execute(() -> {
            for (UUID id : hearers) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
                if (player == null || !ServerPlayNetworking.canSend(player, GodAudioPayload.ID)) continue;
                ServerPlayNetworking.send(player, new GodAudioPayload(stream, id.equals(speaker) ? ack : 0, seq, last, pcm));
            }
        });
    }
}
