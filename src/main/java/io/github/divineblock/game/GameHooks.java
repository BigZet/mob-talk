package io.github.divineblock.game;

import io.github.divineblock.core.GodLedger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/** Server lifecycle: the scheduler's clock, the deed watcher, and forgetting in-flight work when the world closes. */
public final class GameHooks {
    private GameHooks() { }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> { Scheduler.tick(); Tornadoes.tick(); Curses.tick(server); SkyCourt.tick(server); Multiplayer.tick(server); RouletteService.tick(server); });
        ServerLifecycleEvents.SERVER_STARTED.register(SkyCourt::relocateOld);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { Scheduler.clear(); Prayers.clear(); Tornadoes.clear(); Curses.clear(); SkyCourt.clear(); RouletteService.clear(); });
        DeedWatcher.register();
        ChurchArea.register();
        Curses.register();
        Banishment.register();
        Multiplayer.register();
        AdminBridge.register();
        DivineCommands.register();
    }

    /** What the settings screen shows about the player in single player: words, numbers only in debug. */
    public static String describe(MinecraftServer server, java.util.UUID player) {
        if (server == null) return "Мир не открыт.";
        GodLedger.Soul soul = DivineState.of(server).ledger.soul(player);
        long day = DeedWatcher.day(server), now = server.getOverworld().getTime();
        StringBuilder out = new StringBuilder();
        double mood = soul.mood(now);
        out.append("Бог видит в тебе: ").append(soul.righteousness.label()).append(", он к тебе ")
                .append(mood >= 30 ? "благосклонен" : mood <= -30 ? "зол" : "равнодушен").append('.');
        out.append(String.format(java.util.Locale.ROOT, "\n• праведность %.1f, настроение %d", soul.righteousness.score, Math.round(mood)));
        var deeds = soul.righteousness.notable(day, 7, 3);
        if (!deeds.isEmpty()) {
            out.append("\nДела: ");
            for (int i = 0; i < deeds.size(); i++) out.append(i == 0 ? "" : "; ").append(deeds.get(i).describe(day));
        }
        var last = soul.lastVerdict();
        if (last != null) out.append("\nПоследний вердикт:\n").append(last.breakdown());
        return out.toString();
    }
}
