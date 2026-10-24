package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.Miracle;
import io.github.divineblock.core.MiracleScope;
import io.github.divineblock.core.VillageNews;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** The god's deeds told to Mob Talk's villages ({@link VillageNews}): each act that fell, the warning thunder, a curse's end,
 * a forgiveness. An event is pushed to every {@code mobtalk-events} entrypoint (Mob Talk's, found by name: no shared code) and
 * kept for the admin op {@code events}. Without Mob Talk nothing listens and the game is as before; F9 «Сообщать жителям Mob
 * Talk» stops it all. Server thread only. */
public final class VillageEvents {
    private VillageEvents() { }

    /** Who asked for the act and why: a prayer (with the verdict's reasons, the first the culprit's sin), or a command or the admin
     * (unprompted, no sin named: the villages say «за грехи» and the reason itself would read as one). */
    public record Context(boolean prompted, String reason, List<String> reasons) {
        static final Context NONE = new Context(true, "", List.of());
    }
    private static Context context = Context.NONE;
    /** The act being performed now: its event id, so a curse it lays carries the same id to its end. */
    private record Open(String id, Miracle miracle) { }
    private static Open open;
    /** The last punishment of each player, for the curses a punishment lays later (the plagues come one after another). */
    private record Last(String id, Miracle miracle, long at) { }
    private static final Map<UUID, Last> LAST = new HashMap<>();
    private static final long LATER_MILLIS = 10 * 60_000L;
    private static List<Consumer<String>> listeners;

    /** Runs {@code act} with this context for the events it gives. */
    public static <T> T within(Context with, java.util.function.Supplier<T> act) {
        Context outer = context;
        context = with;
        try { return act.get(); } finally { context = outer; }
    }

    /** The act begins: returns what was open before, for {@link #end}. */
    static Object begin(Miracle miracle) {
        Open outer = open;
        open = new Open(VillageNews.newId(), miracle);
        return outer;
    }
    static void end(Object outer) { open = (Open) outer; }

    /** The id and act a curse laid now belongs to: the act being performed, else this player's last punishment. */
    static String[] curseOwner(UUID player) {
        if (open != null) return new String[] { open.id(), open.miracle().id() };
        Last last = LAST.get(player);
        if (last != null && System.currentTimeMillis() - last.at() <= LATER_MILLIS) return new String[] { last.id(), last.miracle().id() };
        return new String[] { "", "" };
    }

    /** The open act fell on {@code player}. */
    static void performed(Miracle miracle, int power, ServerPlayerEntity player, BlockPos altar) {
        VillageNews.Kind kind = VillageNews.kind(miracle);
        if (kind == null || open == null) return;
        DivineConfig config = DivineBlockMod.config();
        MinecraftServer server = player.getServer();
        long now = System.currentTimeMillis();
        if (kind == VillageNews.Kind.WRATH || kind == VillageNews.Kind.BOSS) LAST.put(player.getUuid(), new Last(open.id(), miracle, now));
        if (!config.tellVillages || server == null) return;
        ServerWorld world = player.getServerWorld();
        String dim = world.getRegistryKey().getValue().toString();
        BlockPos at = player.getBlockPos();
        int radius = VillageNews.radius(miracle), seconds = 0;
        // A curse it laid says where (the blight lies on the home) and for how long.
        for (Curses.Curse curse : DivineState.of(server).curses.active) {
            if (!open.id().equals(curse.event)) continue;
            seconds = Math.max(seconds, (int) ((curse.until - curse.start) / 20));
            if (MiracleScope.of(miracle) == MiracleScope.WORLD) radius = Math.max(radius, curse.radius);
            if (curse.dimension.equals(dim)) at = curse.center();
        }
        boolean mercy = kind == VillageNews.Kind.MIRACLE, wrath = kind == VillageNews.Kind.WRATH || kind == VillageNews.Kind.BOSS || kind == VillageNews.Kind.BAN;
        Context why = context;
        VillageNews.Soul soul = new VillageNews.Soul(player.getUuid(), player.getName().getString(), mercy ? "" : why.reason());
        emit(new VillageNews.Event(open.id(), VillageNews.shared().stamp(now), kind, miracle.id(), miracle.title(), VillageNews.group(miracle),
                mercy ? Math.max(0, Math.min(4, power)) : 0, MiracleScope.of(miracle) == MiracleScope.WORLD, mercy ? null : soul, mercy ? soul : null,
                wrath && config.nameSinner, why.prompted(), new VillageNews.Place(dim, at.getX(), at.getY(), at.getZ()), radius, seconds,
                altar(world, altar), why.reasons(), List.of()));
    }

    /** These curses ended (ran out or were lifted): a punishment's end once the last curse it laid is gone. */
    static void cursesEnded(MinecraftServer server, List<Curses.Curse> ended, String reason) {
        if (!DivineBlockMod.config().tellVillages) return;
        Set<String> still = new LinkedHashSet<>();
        for (Curses.Curse curse : DivineState.of(server).curses.active) if (!ended.contains(curse)) still.add(curse.event);
        Set<String> told = new LinkedHashSet<>();
        for (Curses.Curse curse : ended) {
            if (curse.event == null || curse.event.isBlank() || still.contains(curse.event) || !told.add(curse.event)) continue;
            Miracle miracle = Miracle.byId(curse.deed);
            UUID player;
            try { player = UUID.fromString(curse.player); } catch (IllegalArgumentException bad) { continue; }
            BlockPos at = curse.center();
            emit(new VillageNews.Event(curse.event, VillageNews.shared().stamp(System.currentTimeMillis()), VillageNews.Kind.CURSE_END,
                    curse.deed, miracle == null ? curse.type.label : miracle.title(), miracle == null ? 0 : VillageNews.group(miracle), 0,
                    miracle != null && MiracleScope.of(miracle) == MiracleScope.WORLD, new VillageNews.Soul(player, name(server, player, curse.name), reason),
                    null, false, false, new VillageNews.Place(curse.dimension, at.getX(), at.getY(), at.getZ()), curse.radius, 0, null, List.of(), List.of(curse.event)));
        }
    }

    /** Sincere repentance was heard at the altar: the god forgave, and the punishments {@code lifted} are over. */
    static void forgiven(ServerPlayerEntity player, BlockPos altar, List<String> lifted) {
        if (!DivineBlockMod.config().tellVillages) return;
        ServerWorld world = player.getServerWorld();
        BlockPos at = player.getBlockPos();
        emit(new VillageNews.Event(VillageNews.newId(), VillageNews.shared().stamp(System.currentTimeMillis()), VillageNews.Kind.FORGIVEN, "", "прощение",
                0, 0, false, new VillageNews.Soul(player.getUuid(), player.getName().getString(), "покаялся"), null, DivineBlockMod.config().nameSinner, true,
                new VillageNews.Place(world.getRegistryKey().getValue().toString(), at.getX(), at.getY(), at.getZ()), 0, 0, altar(world, altar), List.of(),
                lifted));
    }

    private static VillageNews.Place altar(ServerWorld world, BlockPos altar) {
        if (altar == null || !(world.getBlockState(altar).getBlock() instanceof AltarBlock)) return null;
        return new VillageNews.Place(world.getRegistryKey().getValue().toString(), altar.getX(), altar.getY(), altar.getZ());
    }
    private static String name(MinecraftServer server, UUID id, String saved) {
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(id);
        return online != null ? online.getName().getString() : saved == null ? "" : saved;
    }

    private static void emit(VillageNews.Event event) {
        VillageNews.shared().add(event);
        String json = event.json().toString();
        for (Consumer<String> listener : listeners()) {
            try { listener.accept(json); }
            catch (Throwable failure) { DivineBlockMod.LOG.warn("Божественный блок: Mob Talk не принял событие {}: {}", event.kind().id(), failure.toString()); }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Consumer<String>> listeners() {
        if (listeners != null) return listeners;
        List<Consumer<String>> found = new ArrayList<>();
        try {
            for (var entry : FabricLoader.getInstance().getEntrypointContainers("mobtalk-events", Consumer.class)) {
                try { found.add((Consumer<String>) entry.getEntrypoint()); }
                catch (Throwable failure) { DivineBlockMod.LOG.warn("Божественный блок: mobtalk-events от {} не загрузился: {}", entry.getProvider().getMetadata().getId(), failure.toString()); }
            }
        } catch (Throwable failure) { DivineBlockMod.LOG.warn("Божественный блок: mobtalk-events недоступны: {}", failure.toString()); }
        listeners = List.copyOf(found);
        return listeners;
    }

    /** Forgets the per-world memory when the server stops (the buffer stays: the admin may still read it). */
    static void clear() { LAST.clear(); open = null; context = Context.NONE; }
}
