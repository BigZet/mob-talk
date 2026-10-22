package io.github.mobtalk.server;

import io.github.mobtalk.net.MobTalkNet;
import io.github.mobtalk.net.SpeechClipS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Пока трек B не подключил озвучку: синтез даёт пустой клип (реплика идёт без звука), а рассылка уже режет клип на чанки и шлёт
 * игрокам в радиусе — этой частью трек B может пользоваться как есть. */
public final class StubSpeech implements SpeechService {
    @Override public CompletableFuture<Optional<Clip>> synthesize(String voiceId, String text) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override public void broadcast(ServerWorld world, Entity source, double range, Clip clip) {
        if (clip == null || clip.data() == null || clip.data().length == 0) return;
        double limit = range * range;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.squaredDistanceTo(source) > limit || !ServerPlayNetworking.canSend(player, SpeechClipS2C.ID)) continue;
            for (SpeechClipS2C chunk : chunks(clip)) ServerPlayNetworking.send(player, chunk);
        }
    }

    /** Клип по чанкам не больше {@link MobTalkNet#MAX_CHUNK}; клиент собирает их по {@code index} из {@code count}. */
    public static SpeechClipS2C[] chunks(Clip clip) {
        byte[] data = clip.data();
        int count = Math.max(1, (data.length + MobTalkNet.MAX_CHUNK - 1) / MobTalkNet.MAX_CHUNK);
        SpeechClipS2C[] result = new SpeechClipS2C[count];
        for (int index = 0; index < count; index++) {
            int start = index * MobTalkNet.MAX_CHUNK;
            result[index] = new SpeechClipS2C(clip.id(), index, count, clip.format(),
                    Arrays.copyOfRange(data, start, Math.min(start + MobTalkNet.MAX_CHUNK, data.length)));
        }
        return result;
    }
}
