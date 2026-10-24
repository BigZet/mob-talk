package io.github.mobtalk.core;

import java.util.*;

/** A village's alarm (docs: /mnt/project-files/village-defense/design.md §3): one record per village with a level, a cause, the
 * players it is raised against and how the fight went. Level 1 is a threat (monsters at the village, a hated player): the golem
 * goes for it, nobody hides. Level 2 is an attack (a blow at a villager or the golem, a raid, a crowd of monsters): the bell rings,
 * people hide, trade stops, the village's players hear of it. A player the alarm is raised against is an enemy at once, with no
 * warning (Dmitriy, 2026-10-24); they drop out of it after {@link Rules#playerClearTicks} away from the village or on death, and
 * the alarm ends once nothing has been seen for {@link Rules#clearTicks}. Pure Java, game ticks for time; runtime only — an alarm
 * does not outlive the session, what it left behind lives in the villagers' memories. Not thread-safe: the server thread owns it. */
public final class VillageAlarm {
    public enum Level { NONE, THREAT, ATTACK }

    /** The numbers from the settings, in ticks and counts. */
    public record Rules(long clearTicks, long playerClearTicks, int attackThreats) {
        public static Rules from(MobTalkConfig config) {
            return new Rules(config.alarmClearSeconds * 20L, config.alarmPlayerClearMinutes * 1200L, config.alarmAttackThreats);
        }
    }

    /** A player the alarm is raised against: when they were last seen at the village and why the alarm is on them. */
    public static final class Target {
        public final UUID id; public final String name; public String cause; public long lastSeen;
        Target(UUID id, String name, String cause, long lastSeen) { this.id = id; this.name = name; this.cause = cause; this.lastSeen = lastSeen; }
    }

    /** One village's alarm. */
    public static final class Alarm {
        public final String village;
        public Level level = Level.NONE;
        public String cause = "";
        public final long started;
        /** The last tick a threat (monster or target player) was seen at the village. */
        public long lastThreat;
        /** Monsters seen at once at the last scan, the most seen in this alarm, monsters killed at the village while it lasted. */
        public int threats, mostThreats, killed;
        /** Villagers and golems lost while it lasted, by name. */
        public final List<String> lost = new ArrayList<>();
        public final Map<UUID, Target> targets = new LinkedHashMap<>();
        Alarm(String village, long now) { this.village = village; this.started = now; this.lastThreat = now; }
        public boolean against(UUID player) { return targets.containsKey(player); }
        public boolean attack() { return level == Level.ATTACK; }
        /** «Отбились, убито 5 монстров; погибли: Пётр», for the memory of those who were there. */
        public String summary() {
            StringBuilder out = new StringBuilder(lost.isEmpty() ? "Отбились без потерь" : "Отбились, но погибли: " + String.join(", ", lost));
            if (killed > 0) out.append("; убито монстров: ").append(killed);
            if (!targets.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Target target : targets.values()) names.add(target.name);
                out.append("; тревогу поднимали против игрока ").append(String.join(", ", names));
            }
            return out.toString();
        }
    }

    /** What a call changed, so the game knows whether to ring, shout and tell the players. */
    public enum Change { NONE, RAISED, ESCALATED, NEW_TARGET }

    private final Map<String, Alarm> alarms = new LinkedHashMap<>();

    public Alarm get(String village) { return village == null ? null : alarms.get(village); }
    public Collection<Alarm> all() { return Collections.unmodifiableCollection(alarms.values()); }
    public boolean isEmpty() { return alarms.isEmpty(); }

    /** Monsters seen at the village by a resident: a threat, an attack from {@link Rules#attackThreats} of them at once. Zero
     * monsters only keeps the clock of an alarm that is already on. */
    public Change monsters(String village, int count, String cause, long now, Rules rules) {
        if (count <= 0) return Change.NONE;
        Alarm alarm = alarms.get(village);
        boolean fresh = alarm == null;
        if (fresh) { alarm = new Alarm(village, now); alarms.put(village, alarm); }
        alarm.threats = count; alarm.mostThreats = Math.max(alarm.mostThreats, count); alarm.lastThreat = now;
        Level level = count >= rules.attackThreats() ? Level.ATTACK : Level.THREAT;
        return raise(alarm, level, cause, fresh);
    }

    /** A resident or the golem was struck by a monster, or a raid is on: an attack. */
    public Change attacked(String village, String cause, long now) {
        Alarm alarm = alarms.get(village);
        boolean fresh = alarm == null;
        if (fresh) { alarm = new Alarm(village, now); alarms.put(village, alarm); }
        alarm.lastThreat = now;
        return raise(alarm, Level.ATTACK, cause, fresh);
    }

    /** The alarm against a player: {@code level} THREAT for a hated or exiled player seen in the village, ATTACK for a blow. A player
     * already a target only gets their clock reset (and the level raised if this is worse). */
    public Change player(String village, UUID player, String name, Level level, String cause, long now) {
        Alarm alarm = alarms.get(village);
        boolean fresh = alarm == null;
        if (fresh) { alarm = new Alarm(village, now); alarms.put(village, alarm); }
        alarm.lastThreat = now;
        Target target = alarm.targets.get(player);
        boolean newTarget = target == null;
        if (newTarget) alarm.targets.put(player, new Target(player, name, cause, now));
        else { target.lastSeen = now; if (level.ordinal() > alarm.level.ordinal()) target.cause = cause; }
        Change change = raise(alarm, level, cause, fresh);
        return change == Change.NONE && newTarget ? Change.NEW_TARGET : change;
    }

    /** A target player is still at the village (or a monster still about): the alarm's clocks are kept. */
    public void seen(String village, UUID player, long now) {
        Alarm alarm = alarms.get(village);
        if (alarm == null) return;
        alarm.lastThreat = now;
        if (player != null) { Target target = alarm.targets.get(player); if (target != null) target.lastSeen = now; }
    }

    /** A monster died at the village while its alarm was on. */
    public void killed(String village) { Alarm alarm = alarms.get(village); if (alarm != null) alarm.killed++; }
    /** A resident or golem died while the alarm was on. */
    public void lost(String village, String name) { Alarm alarm = alarms.get(village); if (alarm != null && name != null && !name.isBlank()) alarm.lost.add(name); }

    /** The player is off every alarm: they died, or the village forgave them. Returns the villages they were dropped from. */
    public List<String> release(UUID player) {
        List<String> released = new ArrayList<>();
        for (Alarm alarm : alarms.values()) if (alarm.targets.remove(player) != null) released.add(alarm.village);
        return released;
    }
    /** One village lets the player go. */
    public boolean release(String village, UUID player) { Alarm alarm = alarms.get(village); return alarm != null && alarm.targets.remove(player) != null; }

    /** The ticks pass: targets away too long drop out, and alarms with nothing seen for {@link Rules#clearTicks} and no target left
     * end. Only villages whose key starts with {@code prefix} (one world's), since each world has its own clock. Returns the alarms
     * that ended, for the all-clear. */
    public List<Alarm> tick(String prefix, long now, Rules rules) {
        List<Alarm> ended = new ArrayList<>();
        for (Iterator<Alarm> it = alarms.values().iterator(); it.hasNext(); ) {
            Alarm alarm = it.next();
            if (!alarm.village.startsWith(prefix)) continue;
            alarm.targets.values().removeIf(target -> now - target.lastSeen >= rules.playerClearTicks());
            if (alarm.targets.isEmpty() && now - alarm.lastThreat >= rules.clearTicks()) { it.remove(); ended.add(alarm); }
        }
        return ended;
    }

    public void clear() { alarms.clear(); }

    private static Change raise(Alarm alarm, Level level, String cause, boolean fresh) {
        if (level.ordinal() <= alarm.level.ordinal()) return fresh ? Change.RAISED : Change.NONE;
        alarm.level = level;
        if (cause != null && !cause.isBlank()) alarm.cause = cause;
        return fresh ? Change.RAISED : Change.ESCALATED;
    }
}
