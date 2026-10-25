package io.github.divineblock.boss;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.fabricmc.loader.api.FabricLoader;

import io.github.divineblock.DivineBlockMod;

/** «Лог боя Серафима» (setting {@code seraphLog}): one fight as two CSV files in {@code seraph-logs/} of the game folder, with
 * the same columns as {@code ml/seraph_dodge/v2/eval_v2.py --log}, so the trainer and the game compare line by line.
 * {@code seraph-fight-*.csv} has a row per tick (what the net saw and wanted, what was done or refused and why),
 * {@code seraph-events-*.csv} a row per shot, hit, sweep, window, phase and death. The game thread only appends to a buffer;
 * the disk is written on a worker, a second's worth at a time. */
final class SeraphFightLog {
    /** The net's own inputs (SeraphMind.token / items, duel.py Duel._tokens / _items_for("s")), in their order. */
    static final String[] TOKEN = { "vx", "vy", "vz", "height", "dash_ready", "deflect_ready", "attack_ready", "cast", "sweep",
            "eye_x", "eye_y", "eye_z", "foe_vx", "foe_vy", "foe_vz", "drawing", "pull", "look_x", "look_y", "look_z", "aim_off",
            "foe_cool", "dashing", "mini_ready", "mini", "tele_ready", "orbit_dir" };
    static final String[] ITEM = { "rx", "ry", "rz", "vx", "vy", "vz", "arrow", "reflected", "feather", "tca", "on" };
    static final String FIGHT_HEADER = "tick,hp,max_hp,phase,exposed,recovering,dist,height,dash_cd,deflect_cd,attack_cd,casting,dashing,"
            + "deflecting,foe_drawing,foe_pull,out_sx,out_lift,out_sz,out_dash,out_deflect,out_feathers,out_aim_side,out_aim_up,"
            + "out_shot_soon,did_dash,did_deflect,did_feathers,refused," + features();
    static final String EVENTS_HEADER = "tick,event,amount,raw,detail";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "divineblock-seraph-log");
        thread.setDaemon(true);
        return thread;
    });
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path fight, events;
    private final StringBuilder fightRows = new StringBuilder(), eventRows = new StringBuilder();
    private int rows;

    private SeraphFightLog(Path fight, Path events) {
        this.fight = fight;
        this.events = events;
    }

    /** A new pair of files named by the time and the boss's id; the headers go out with the first flush. */
    static SeraphFightLog open(int entityId) {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("seraph-logs");
        String name = LocalDateTime.now().format(STAMP) + "-" + entityId + ".csv";
        SeraphFightLog log = new SeraphFightLog(dir.resolve("seraph-fight-" + name), dir.resolve("seraph-events-" + name));
        log.fightRows.append(FIGHT_HEADER).append('\n');
        log.eventRows.append(EVENTS_HEADER).append('\n');
        return log;
    }

    void tick(String row) {
        fightRows.append(row).append('\n');
        if (++rows % 20 == 0) flush();
    }

    void event(int tick, String event, double amount, double raw, String detail) {
        eventRows.append(tick).append(',').append(event).append(',').append(num(amount)).append(',').append(num(raw)).append(',')
                .append(detail == null ? "" : detail.replace(',', ';')).append('\n');
    }

    /** Hands what is buffered to the worker. */
    void flush() {
        if (fightRows.isEmpty() && eventRows.isEmpty()) return;
        String f = fightRows.toString(), e = eventRows.toString();
        fightRows.setLength(0);
        eventRows.setLength(0);
        WORKER.execute(() -> {
            try {
                Files.createDirectories(fight.getParent());
                if (!f.isEmpty()) Files.writeString(fight, f, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (!e.isEmpty()) Files.writeString(events, e, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException failure) {
                DivineBlockMod.LOG.warn("Божественный блок: лог боя Серафима не записан", failure);
            }
        });
    }

    private static String features() {
        StringBuilder out = new StringBuilder();
        for (String name : TOKEN) out.append(out.isEmpty() ? "" : ",").append("tok_").append(name);
        for (int k = 0; k < 8; k++) for (String name : ITEM) out.append(",it").append(k).append('_').append(name);
        return out.append(",out_mini,out_side,out_radius,out_tele,did_mini,did_flip,did_tele,radius,orbit_dir").toString();
    }

    /** Three decimals, a dot for the point; empty for a value the game does not know. */
    static String num(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.3f", value);
    }
}
