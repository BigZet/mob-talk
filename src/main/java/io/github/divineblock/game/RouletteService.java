package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.GodJournal;
import io.github.divineblock.core.Miracle;
import io.github.divineblock.core.MiracleGroup;
import io.github.divineblock.core.Roulette;
import io.github.divineblock.core.RouletteQueue;
import io.github.divineblock.core.RouletteSymbol;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

/** The punishment roulette on the server ({@link Roulette}): it lays out a spin (the symbol, the seed of the strips, the mode,
 * the tease), sends it to the sinner and to those who may watch, says the result in chat at the final and lets the
 * punishment fall exactly when the spin ends, counted in server ticks. A sinner who leaves, dies or goes to another world stops
 * his spin (what happens to the punishment then is the caller's old rule); a pardon jams it into doves. Server thread only. */
public final class RouletteService {
    static final RouletteQueue QUEUE = new RouletteQueue();
    /** What each spin showed and to whom, by spin id: to cancel it on the same screens. */
    private static final Map<Integer, Shown> SHOWN = new HashMap<>();
    private record Shown(UUID sinner, String name, RegistryKey<World> world, List<UUID> viewers, RouletteSymbol symbol, String title) { }

    /** For the self-test's report: every chat line of the roulette. */
    public static volatile java.util.function.Consumer<String> said = line -> { };

    private RouletteService() { }

    /** What the caller does at each moment of the spin. */
    public interface Steps {
        /** The spin starts (perhaps after another one): {@code seconds} until the punishment falls. */
        default void started(double seconds) { }
        /** The third reel stands: the ribbon rolls out, the god may speak. */
        default void fin() { }
        /** The punishment falls now. */
        void act();
        /** The third reel slipped onto a dove: spared by chance, nothing falls (its tails neither); the ladder of punishments
         * stays where it is. */
        default void spared() { }
        /** It does not fall from here. */
        default void cancelled(RouletteQueue.Cancel why) { }
    }

    /** Whether a punishment of this verdict goes through the roulette: wrath (a treasure turned to rubbish is wrath too). */
    static boolean applies(Miracle.Kind inclination, Miracle miracle, DivineConfig config) {
        return config.rouletteEnabled && miracle != null && miracle != Miracle.IGNORE
                && (miracle.kind == Miracle.Kind.WRATH || inclination == Miracle.Kind.WRATH && Roulette.groupOf(miracle) != null);
    }

    /** Spins the roulette for {@code miracle} on {@code sinner}; {@code tease}: null — by the settings' chance; the chance of
     * mercy by the settings too. */
    public static RouletteQueue.Spin spin(ServerPlayerEntity sinner, Miracle miracle, MiracleGroup group, String reason, Roulette.Mode mode, Boolean tease, Steps steps) {
        return spin(sinner, miracle, group, reason, mode, tease, null, steps);
    }

    /** As above; {@code mercy}: null — by the settings' chance ({@link Roulette#mercy}), true — spared (a test), false — never. */
    public static RouletteQueue.Spin spin(ServerPlayerEntity sinner, Miracle miracle, MiracleGroup group, String reason, Roulette.Mode mode, Boolean tease,
                                          Boolean mercy, Steps steps) {
        MinecraftServer server = sinner.getServer();
        DivineConfig config = DivineBlockMod.config();
        int severity = Roulette.severity(miracle, group);
        RouletteSymbol symbol = RouletteSymbol.result(miracle, group);
        boolean spared = mode != Roulette.Mode.MINI && miracle != Miracle.ETERNAL_BAN && (mercy != null ? mercy
                : Roulette.mercy(miracle, group, config.rouletteMercyChance, java.util.concurrent.ThreadLocalRandom.current()));
        boolean teasing = !spared && mode != Roulette.Mode.MINI && (tease != null ? tease && (severity == 3 || severity == 4)
                : Roulette.tease(severity, config.rouletteTease, config.rouletteTeaseChance, java.util.concurrent.ThreadLocalRandom.current()));
        long seed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        Roulette.Timing timing = Roulette.Timing.of(mode, severity);
        String title = miracle == Miracle.ETERNAL_BAN ? "Вечное изгнание" : miracle.title();
        String why = reason == null ? "" : reason.length() > 200 ? reason.substring(0, 200) + "…" : reason;
        UUID id = sinner.getUuid();
        String name = sinner.getName().getString();
        return QUEUE.offer(id, now(server), timing, new RouletteQueue.Hooks() {
            @Override public void started(RouletteQueue.Spin spin) {
                ServerPlayerEntity now = server.getPlayerManager().getPlayer(id);
                ServerPlayerEntity who = now != null ? now : sinner;
                List<ServerPlayerEntity> viewers = viewers(who, config);
                SHOWN.put(spin.id, new Shown(id, name, who.getServerWorld().getRegistryKey(), viewers.stream().map(ServerPlayerEntity::getUuid).toList(), symbol, title));
                RouletteSpinPayload payload = new RouletteSpinPayload(spin.id, id, name, miracle.id(), title, why, symbol.ordinal(), severity, seed,
                        mode.ordinal(), teasing, spin.start, spared);
                for (ServerPlayerEntity viewer : viewers) if (ServerPlayNetworking.canSend(viewer, RouletteSpinPayload.ID)) ServerPlayNetworking.send(viewer, payload);
                DivineBlockMod.LOG.info("Божественный блок: рулетка {} для {} — {} ({}, тяжесть {}, {}{}{})", spin.id, name, miracle.id(), symbol, severity,
                        mode, teasing ? ", почти 777" : "", spared ? ", пощажён случаем" : "");
                steps.started(timing.act() / 20.0);
            }
            @Override public void fin(RouletteQueue.Spin spin) {
                if (spared) {
                    chat(server, spin.id, Roulette.mercyLine(symbol), Formatting.AQUA);
                    Multiplayer.journal(GodJournal.Kind.VERDICT, name, "рулетка: " + Roulette.mercyLine(symbol) + " — пощажён случаем, «" + title + "» не падает");
                } else {
                    chat(server, spin.id, Roulette.chatLine(symbol, title), symbol == RouletteSymbol.SEVEN ? Formatting.GOLD : Formatting.RED);
                    Multiplayer.journal(GodJournal.Kind.VERDICT, name, "рулетка: " + Roulette.chatLine(symbol, title));
                }
                steps.fin();
            }
            @Override public void act(RouletteQueue.Spin spin) {
                SHOWN.remove(spin.id);
                steps.act();
            }
            @Override public void spared(RouletteQueue.Spin spin) {
                SHOWN.remove(spin.id);
                ServerPlayerEntity now = server.getPlayerManager().getPlayer(id);
                if (now != null) history(now, miracle, Roulette.mercyLine(symbol));
                steps.spared();
            }
            @Override public void cancelled(RouletteQueue.Spin spin, RouletteQueue.Cancel why) {
                boolean pardon = why == RouletteQueue.Cancel.PARDON;
                Shown shown = SHOWN.get(spin.id);
                if (shown != null) {
                    RouletteCancelPayload payload = new RouletteCancelPayload(spin.id, pardon, now(server));
                    for (UUID viewer : shown.viewers()) {
                        ServerPlayerEntity player = server.getPlayerManager().getPlayer(viewer);
                        if (player != null && ServerPlayNetworking.canSend(player, RouletteCancelPayload.ID)) ServerPlayNetworking.send(player, payload);
                    }
                    if (pardon) {
                        chat(server, spin.id, Roulette.chatLine(RouletteSymbol.DOVE, "Помилован"), Formatting.AQUA);
                        Multiplayer.journal(GodJournal.Kind.VERDICT, name, "рулетка заклинила: помилован, «" + title + "» не падает");
                    }
                    SHOWN.remove(spin.id);
                }
                DivineBlockMod.LOG.info("Божественный блок: рулетка {} для {} остановлена — {}", spin.id, name, why);
                steps.cancelled(why);
            }
        }, spared);
    }

    /** A punishment's tail (the second wave, an aftershock): by the settings no roulette, one reel for two seconds, or the
     * whole machine; then {@code then}. A tail stopped by a pardon, death or leaving does not fall. */
    static void tail(ServerPlayerEntity sinner, Miracle miracle, Runnable then) {
        DivineConfig config = DivineBlockMod.config();
        if (!config.rouletteEnabled || config.rouletteTails == Roulette.Tails.OFF || sinner.isRemoved()) { then.run(); return; }
        Roulette.Mode mode = config.rouletteTails == Roulette.Tails.FULL ? config.rouletteMode : Roulette.Mode.MINI;
        spin(sinner, miracle, null, "кара продолжается", mode, false, then::run);
    }

    /** A spin that spared the sinner, in the prayer log the web admin's history reads: «пощажён случаем». */
    private static void history(ServerPlayerEntity sinner, Miracle miracle, String line) {
        io.github.divineblock.core.Verdict verdict = new io.github.divineblock.core.Verdict();
        verdict.prayer = "(рулетка кары)"; verdict.miracle = miracle.id(); verdict.power = 0; verdict.inclination = Miracle.Kind.WRATH;
        verdict.decidedBy = "roulette"; verdict.decisionNote = "пощажён случаем: третий барабан встал на голубя, «" + miracle.title() + "» не упала";
        verdict.speech = line;
        io.github.divineblock.core.PrayerLog.shared().add(io.github.divineblock.core.PrayerLog.of(System.currentTimeMillis(), sinner.getUuidAsString(),
                sinner.getName().getString(), verdict, io.github.divineblock.core.PrayerTone.of(""), false, "", 0));
    }

    /** The sinner repented while his reels spin: they jam, the punishment does not fall. Whether a spin was stopped. */
    public static boolean pardon(ServerPlayerEntity sinner) {
        return QUEUE.cancel(sinner.getUuid(), RouletteQueue.Cancel.PARDON) != null;
    }

    public static boolean spinning(ServerPlayerEntity player) { return QUEUE.spinning(player.getUuid()); }

    /** Every tick: spins of sinners who left, died or changed worlds stop; then what is due runs. */
    static void tick(MinecraftServer server) {
        for (RouletteQueue.Spin spin : QUEUE.all()) {
            Shown shown = SHOWN.get(spin.id);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(spin.player);
            RouletteQueue.Cancel why = player == null ? RouletteQueue.Cancel.LEFT : !player.isAlive() ? RouletteQueue.Cancel.DIED
                    : shown != null && player.getServerWorld().getRegistryKey() != shown.world() ? RouletteQueue.Cancel.WORLD : null;
            if (why != null) QUEUE.cancel(spin.player, why);
        }
        QUEUE.tick(now(server));
    }

    static void clear() { QUEUE.clear(); SHOWN.clear(); }

    /** The tick spins are counted in: the overworld's time, the same on every client in every dimension. */
    static long now(MinecraftServer server) { return server.getOverworld().getTime(); }

    /** Who sees this sinner's roulette: him, and by the settings those near him or everyone. */
    private static List<ServerPlayerEntity> viewers(ServerPlayerEntity sinner, DivineConfig config) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        out.add(sinner);
        if (config.rouletteAudience == Roulette.Audience.SINNER) return out;
        double reach = (double) config.rouletteRadius * config.rouletteRadius;
        for (ServerPlayerEntity other : sinner.getServer().getPlayerManager().getPlayerList()) {
            if (other == sinner) continue;
            if (config.rouletteAudience == Roulette.Audience.ALL
                    || other.getServerWorld() == sinner.getServerWorld() && other.squaredDistanceTo(sinner) <= reach) out.add(other);
        }
        return out;
    }

    /** The line that stands in for the picture: everyone who was shown the spin reads it (the sinner's name for the others). */
    private static void chat(MinecraftServer server, int spin, String line, Formatting color) {
        said.accept(line);
        Shown shown = SHOWN.get(spin);
        if (shown == null) return;
        for (UUID viewer : shown.viewers()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(viewer);
            if (player == null) continue;
            Text text = Text.literal(line).formatted(color, Formatting.BOLD);
            if (!viewer.equals(shown.sinner())) text = text.copy().append(Text.literal("  — кара для " + shown.name()).formatted(Formatting.GRAY));
            player.sendMessage(text, false);
        }
    }
}
