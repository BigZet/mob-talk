package io.github.mobtalk.core;

import java.util.*;

/** Исповедь в памяти мира: когда игрок последний раз исповедовался (одна полная исповедь за игровой день), его епитимья (вид, у
 * кого, отложенные грехи, срок, сколько сделано, исполнена ли) и у какого священника он бывал. Слов исповеди здесь нет и быть не
 * может. Время — тики мира. Каждый метод под замком {@link ConversationStore}. */
public final class ConfessionLedger {
    private final ConversationStore store;
    ConfessionLedger(ConversationStore store) { this.store = store; }

    /** Сохраняемая часть: по игроку. */
    static final class Book {
        Map<String, Record> players = new LinkedHashMap<>();
        void sanitize() {
            if (players == null) players = new LinkedHashMap<>();
            players.values().removeIf(Objects::isNull);
            for (Record r : players.values()) { if (r.lastPriest == null) r.lastPriest = ""; if (r.penance != null && !r.penance.valid()) r.penance = null; }
        }
        Book copy() {
            Book c = new Book();
            players.forEach((key, r) -> c.players.put(key, r.copy()));
            return c;
        }
    }
    static final class Record {
        long lastTick = -1;
        int count;
        String lastPriest = "";
        Assigned penance;
        Record copy() { Record c = new Record(); c.lastTick = lastTick; c.count = count; c.lastPriest = lastPriest; c.penance = penance == null ? null : penance.copy(); return c; }
    }
    static final class Assigned {
        String kind = "", priest = "", priestId = "", place = "", dimension = "";
        int x, y, z;
        boolean anchored, done;
        List<Long> deeds = new ArrayList<>();
        long assignedTick, dueTick, assignedMillis, fastSince = -1;
        int progress, baseline = -1;
        boolean valid() { return Confession.Penance.of(kind) != null && deeds != null && priest != null && priestId != null && place != null && dimension != null; }
        Assigned copy() {
            Assigned c = new Assigned();
            c.kind = kind; c.priest = priest; c.priestId = priestId; c.place = place; c.dimension = dimension; c.x = x; c.y = y; c.z = z; c.anchored = anchored; c.done = done;
            c.deeds = new ArrayList<>(deeds); c.assignedTick = assignedTick; c.dueTick = dueTick; c.assignedMillis = assignedMillis; c.fastSince = fastSince;
            c.progress = progress; c.baseline = baseline;
            return c;
        }
    }

    /** Епитимья игрока как её видят сцена, HUD и админка. {@code anchored} — у неё есть место (аналой, у которого её дали). */
    public record Penance(UUID player, Confession.Penance kind, String priest, UUID priestId, Confession.Place place, String dimension, int x, int y, int z,
                          boolean anchored, List<Long> deeds, long assignedTick, long dueTick, long assignedMillis, int progress, boolean done,
                          long fastSince, int baseline) {
        /** Строка HUD: что сделать и сколько сделано, а исполнив — к кому вернуться. */
        public String hud(long nowTick) {
            if (done) return "Епитимья исполнена — вернись к батюшке (" + priest + ")";
            String count = kind.target > 1 ? " " + progress + "/" + kind.target : "";
            String fast = kind == Confession.Penance.FAST && fastSince >= 0 ? " ещё " + Math.max(1, (fastSince + 24_000 - nowTick + 1199) / 1200) + " мин" : "";
            long days = Math.max(0, dueTick - nowTick) / 24_000;
            return "Епитимья: " + kind.hud + count + fast + " (срок " + (days >= 1 ? days + " дн." : Math.max(1, (dueTick - nowTick) / 1200) + " мин") + ")";
        }
    }

    public long lastFull(UUID player) {
        synchronized (store) { Record r = store.state.confessions.players.get(player.toString()); return r == null ? -1 : r.lastTick; }
    }
    /** Полная исповедь состоялась: откат пошёл. Священник помнит только факт (без слов) и епитимью. */
    public void confessed(UUID player, String playerName, UUID priest, String priestName, String type, long tick, Confession.Penance penance, long now) {
        synchronized (store) {
            Record r = store.state.confessions.players.computeIfAbsent(player.toString(), k -> new Record());
            r.lastTick = tick; r.count++; r.lastPriest = priestName == null ? "" : priestName;
            store.scheduleSave();
        }
        store.recordEvent(priest, type, priestName, "Принял исповедь игрока " + playerName + (penance == null ? "" : "; наложил епитимью: " + penance.title)
                + ". Что сказано на исповеди — тайна, ты никому её не открываешь.", now);
    }
    /** Наложить епитимью: отложенные грехи ждут её исполнения и возвращения к священнику. */
    public void assign(UUID player, Confession.Penance kind, List<Long> deeds, UUID priest, String priestName, Confession.Place place, String dimension,
                       int x, int y, int z, boolean anchored, long tick, long dueTick, long now) {
        synchronized (store) {
            Record r = store.state.confessions.players.computeIfAbsent(player.toString(), k -> new Record());
            Assigned a = new Assigned();
            a.kind = kind.id(); a.priest = priestName; a.priestId = priest.toString(); a.place = place.id(); a.dimension = dimension;
            a.x = x; a.y = y; a.z = z; a.anchored = anchored; a.deeds = new ArrayList<>(deeds); a.assignedTick = tick; a.dueTick = dueTick; a.assignedMillis = now;
            r.penance = a;
            store.scheduleSave();
        }
    }
    public Penance penance(UUID player) {
        synchronized (store) {
            Record r = store.state.confessions.players.get(player.toString());
            return r == null || r.penance == null ? null : view(player, r.penance);
        }
    }
    /** Все наложенные епитимьи (для сцены и админки). */
    public List<Penance> penances() {
        synchronized (store) {
            List<Penance> out = new ArrayList<>();
            store.state.confessions.players.forEach((id, r) -> { if (r.penance != null) try { out.add(view(UUID.fromString(id), r.penance)); } catch (IllegalArgumentException skip) { } });
            return out;
        }
    }
    /** Сделано ещё (поклон, принесённый камень): возвращает новый счёт; дошёл до цели — исполнена. */
    public int progress(UUID player, int add) {
        synchronized (store) {
            Assigned a = assigned(player);
            if (a == null || a.done) return a == null ? 0 : a.progress;
            Confession.Penance kind = Confession.Penance.of(a.kind);
            a.progress = Math.min(kind.target, a.progress + Math.max(0, add));
            if (a.progress >= kind.target) a.done = true;
            store.scheduleSave();
            return a.progress;
        }
    }
    public void done(UUID player) {
        synchronized (store) { Assigned a = assigned(player); if (a != null && !a.done) { a.done = true; a.progress = Confession.Penance.of(a.kind).target; store.scheduleSave(); } }
    }
    /** Пост: когда начался (и с какого счёта съеденного мяса); нарушен — начинается заново. */
    public void fast(UUID player, long since, int baseline) {
        synchronized (store) { Assigned a = assigned(player); if (a != null) { a.fastSince = since; a.baseline = baseline; store.scheduleSave(); } }
    }
    /** Снять епитимью (исполнена и разрешена, срок вышел, админ снял); возвращает её отложенные грехи. */
    public List<Long> clear(UUID player) {
        synchronized (store) {
            Record r = store.state.confessions.players.get(player.toString());
            if (r == null || r.penance == null) return List.of();
            List<Long> deeds = new ArrayList<>(r.penance.deeds);
            r.penance = null;
            store.scheduleSave();
            return deeds;
        }
    }
    private Assigned assigned(UUID player) { Record r = store.state.confessions.players.get(player.toString()); return r == null ? null : r.penance; }
    private static Penance view(UUID player, Assigned a) {
        UUID priest;
        try { priest = UUID.fromString(a.priestId); } catch (IllegalArgumentException bad) { priest = new UUID(0, 0); }
        return new Penance(player, Confession.Penance.of(a.kind), a.priest, priest, "temple".equals(a.place) ? Confession.Place.TEMPLE : Confession.Place.VILLAGE,
                a.dimension, a.x, a.y, a.z, a.anchored, List.copyOf(a.deeds), a.assignedTick, a.dueTick, a.assignedMillis, a.progress, a.done, a.fastSince, a.baseline);
    }
}
