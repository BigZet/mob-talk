package io.github.divineblock.game;

import com.mojang.authlib.GameProfile;
import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DeferredWrath;
import io.github.divineblock.core.GodJournal;
import io.github.divineblock.core.Verdict;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** The god on a server with several mortals: who the streamer is, the punishment that waits for a sinner who logged out
 * before it fell ({@link DeferredWrath}), and the journal the web admin reads ({@link GodJournal}). */
public final class Multiplayer {
    /** How long after a sinner's return his waiting punishment falls: the world must load around him first. */
    static final int RETURN_DELAY_TICKS = 20 * 8;
    private static int ticks;

    private Multiplayer() { }

    static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> returned(handler.player)));
        // One journal per open world: another world in the same game starts a fresh one.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> GodJournal.shared().clear());
    }

    // ---- The streamer. ----

    /** Sets the streamer (null clears him). */
    public static void streamer(MinecraftServer server, UUID player, String name) {
        DivineState state = DivineState.of(server);
        state.streamer = player == null ? "" : player.toString();
        state.streamerName = player == null || name == null ? "" : name;
        state.markDirty();
        DivineBlockMod.LOG.info("Божественный блок: стример {}", player == null ? "снят" : state.streamerName);
    }
    public static String streamerName(MinecraftServer server) { return DivineState.of(server).streamerName; }
    public static boolean isStreamer(MinecraftServer server, UUID player) { return player != null && player.toString().equals(DivineState.of(server).streamer); }

    /** The profile of a player by name: online first, then the server's cache of those who played here. */
    static Optional<GameProfile> profile(MinecraftServer server, String name) {
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(name);
        if (online != null) return Optional.of(online.getGameProfile());
        return server.getUserCache() == null ? Optional.empty() : server.getUserCache().findByName(name);
    }

    /** Fills in who is on the stream before the god's words are written. */
    static void stamp(MinecraftServer server, ServerPlayerEntity asker, Verdict verdict) {
        verdict.streamer = streamerName(server);
        verdict.byStreamer = !verdict.streamer.isEmpty() && isStreamer(server, asker.getUuid());
    }

    // ---- The punishment that waits. ----

    /** Whether the mortal logged out (and did not merely die or change worlds), even if he is already back under a new
     * entity: a disconnect unloads the player with his reason, a respawn discards him. */
    static boolean left(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        if (player.getRemovalReason() == net.minecraft.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER) return true;
        return player.isRemoved() && player.getHealth() > 0 && server.getPlayerManager().getPlayer(player.getUuid()) == null;
    }

    /** The mortal left before the god's last word: a punishment waits for his return, anything else is cancelled. */
    static void heldBack(ServerPlayerEntity player, ServerWorld world, BlockPos altar, Verdict verdict) {
        MinecraftServer server = world.getServer();
        String name = player.getName().getString();
        DivineState state = DivineState.of(server);
        boolean kept = state.ledger.deferred.hold(player.getUuid(), name, verdict, world.getRegistryKey().getValue().toString(),
                new int[] { altar.getX(), altar.getY(), altar.getZ() }, server.getOverworld().getTime());
        var soul = state.ledger.soul(player.getUuid());
        if (kept && soul.lastVerdict() != verdict) soul.remember(verdict); // the ban reads its reasons from here
        state.markDirty();
        String act = verdict.chosen().id();
        DivineBlockMod.LOG.info("Божественный блок: {} ушёл до конца речи — {} {}", name, act, kept ? "ждёт его возвращения" : "отменено");
        journal(GodJournal.Kind.VERDICT, name, kept ? "ушёл до кары «" + act + "»: она ждёт его входа до игрового дня" : "ушёл, «" + act + "» отменено");
        // Already back (he left and came in again while the god was still speaking): it falls now.
        ServerPlayerEntity back = server.getPlayerManager().getPlayer(player.getUuid());
        if (kept && back != null) returned(back);
    }

    /** A mortal came back: what waited for him falls after a short while, or is written off when its day has passed. */
    private static void returned(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        DivineState state = DivineState.of(server);
        DeferredWrath.Held held = state.ledger.deferred.claim(player.getUuid());
        if (held == null) return;
        state.markDirty();
        if (held.expired(server.getOverworld().getTime())) { writtenOff(held); return; }
        UUID id = player.getUuid();
        Scheduler.later(RETURN_DELAY_TICKS, () -> {
            ServerPlayerEntity now = server.getPlayerManager().getPlayer(id);
            if (now == null) { // Gone again at once: the punishment keeps waiting from where it was.
                state.ledger.deferred.waiting.put(id.toString(), held); state.markDirty(); return;
            }
            if (!now.isAlive()) return;
            journal(GodJournal.Kind.VERDICT, held.name, "вернулся — кара «" + held.verdict.chosen().id() + "» падает");
            Prayers.strikeReturned(now, altar(now, held), held.verdict);
        });
    }

    /** The altar he prayed at when he stands in that world and it is still there, else where he stands now. */
    private static BlockPos altar(ServerPlayerEntity player, DeferredWrath.Held held) {
        ServerWorld world = player.getServerWorld();
        if (held.altar == null || held.altar.length != 3) return player.getBlockPos();
        BlockPos pos = new BlockPos(held.altar[0], held.altar[1], held.altar[2]);
        RegistryKey<net.minecraft.world.World> key = RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(held.world == null || held.world.isBlank() ? "minecraft:overworld" : held.world));
        boolean same = world.getRegistryKey().equals(key) && world.isChunkLoaded(pos) && world.getBlockState(pos).getBlock() instanceof AltarBlock;
        return same ? pos : player.getBlockPos();
    }

    private static void writtenOff(DeferredWrath.Held held) {
        DivineBlockMod.LOG.info("Божественный блок: кара {} для {} списана — прошёл игровой день", held.verdict.chosen().id(), held.name);
        journal(GodJournal.Kind.VERDICT, held.name, "кара «" + held.verdict.chosen().id() + "» списана: не вернулся за игровой день");
    }

    /** Once a minute: punishments whose day has passed are written off. */
    static void tick(MinecraftServer server) {
        if (++ticks % 1200 != 0) return;
        DivineState state = DivineState.of(server);
        if (state.ledger.deferred.waiting.isEmpty()) return;
        var over = state.ledger.deferred.expire(server.getOverworld().getTime());
        if (over.isEmpty()) return;
        state.markDirty();
        over.forEach(Multiplayer::writtenOff);
    }

    // ---- The journal. ----

    public static void journal(GodJournal.Kind kind, String player, String text) { GodJournal.shared().add(kind, player, text); }
}
