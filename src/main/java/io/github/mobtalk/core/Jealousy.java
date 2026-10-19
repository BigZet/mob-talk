package io.github.mobtalk.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Ревность влюблённого или супруга-жителя к игроку (трек J): сколько игрок болтает с жителями того же пола, что и ревнующий,
 * спит рядом с ними, выполняет их поручения и дарит им подарки. Уровень 0..100 хранится в {@link Rapport} этой пары, гаснет сам
 * со временем (полураспад {@link MobTalkConfig#jealousyHalfLifeMinutes}) и от внимания к ревнующему; последний соперник и повод
 * идут в промпт («опять с Фёклой болтал»). Здесь же отметка о ночи, проведённой вместе (сильнейшее внимание), и флаг утренней
 * реплики. Чистая Java, реальное время, все изменения под замком хранилища. */
public final class Jealousy {
    /** Повод ревновать. */
    public enum Cause { TALK, SLEEP, FAVOR, GIFT }
    /** Как ревнующий узнал: видел сам или услышал от деревни. */
    public enum Source { SAW, RUMOR }

    /** Один и тот же соперник по тому же поводу засчитывается не чаще: разговор — раз в две минуты, сон рядом — раз в десять. */
    public static final long TALK_COOLDOWN_MILLIS = 2 * 60_000L, SLEEP_COOLDOWN_MILLIS = 10 * 60_000L;
    /** Ночь вместе засчитывается раз в столько реального времени (игровая ночь со сном длится секунды, но повтор не копится). */
    public static final long NIGHT_COOLDOWN_MILLIS = 5 * 60_000L;
    /** Сколько после ночи вместе житель помнит её в разговоре. */
    public static final long NIGHT_FRESH_MILLIS = 20 * 60_000L;
    /** С какого уровня ревность попадает в реплики; ниже — житель её не показывает. */
    public static final int HINT_FROM = 10;
    public static final int MAX = 100;
    /** Сколько отметок «соперник/повод → когда» помнится для пауз. */
    private static final int NOTICED_LIMIT = 16;

    double level; long at;
    /** Последний соперник: UUID, имя, пол (имя рода для «с ней/с ним»). */
    String rivalId = "", rival = "", rivalGender = "";
    /** Последний повод ({@link Cause} по имени), подробность (подаренный предмет), видел ли сам. */
    String cause = "", detail = ""; boolean seen; long causeAt = -1;
    /** Когда каждый соперник по каждому поводу засчитан последний раз. */
    Map<String, Long> noticed = new LinkedHashMap<>();
    /** Последняя ночь вместе (реальные миллисекунды), −1 — не было; {@code morning}: утренняя реплика ещё не сказана. */
    long sleptAt = -1; boolean morning;

    /** Уровень сейчас, после ленивого затухания. */
    public int level(long now) { return (int) Math.round(decayed(now)); }
    private double decayed(long now) {
        if (level <= 0) return 0;
        long half = Math.max(1, Tuning.get().jealousyHalfLifeMinutes) * 60_000L;
        long elapsed = Math.max(0, now - at);
        return level * Math.pow(0.5, (double) elapsed / half);
    }
    private void settle(long now) { level = decayed(now); at = now; }

    /** Сила повода до записи: база повода (подарок — плюс его ценность в сердечках), видел вблизи — полностью, вдали — до половины,
     * по слуху — {@link MobTalkConfig#jealousyRumorPercent}; в браке — {@link MobTalkConfig#jealousyMarriedPercent}. */
    public static int amount(Cause cause, int giftHearts, Source source, double distance, double sight, boolean married, MobTalkConfig c) {
        if (cause == null || c == null) return 0;
        double base = switch (cause) {
            case TALK -> c.jealousyTalk;
            case SLEEP -> c.jealousySleep;
            case FAVOR -> c.jealousyFavor;
            // Подарок — отдельный сильный повод: чем ценнее (сердечки, которые он принёс сопернику), тем больнее.
            case GIFT -> c.jealousyGift == 0 ? 0 : c.jealousyGift + 2.0 * Math.max(0, giftHearts);
        };
        double heard = source == Source.RUMOR ? c.jealousyRumorPercent / 100.0
                : 1.0 - 0.5 * Math.min(1.0, Math.max(0, distance) / Math.max(1.0, sight));
        double wed = married ? c.jealousyMarriedPercent / 100.0 : 1.0;
        return (int) Math.round(Math.max(0, base * heard * wed));
    }

    /** Ключ паузы для соперника и повода. */
    private static String key(String rivalId, Cause cause) { return cause.name() + ":" + rivalId; }
    /** Засчитан ли этот повод сейчас или ещё идёт пауза (только разговор и сон рядом: подарок и услуга — каждый раз отдельное дело). */
    public boolean coolingDown(String rivalId, Cause cause, long now) {
        long pause = cause == Cause.TALK ? TALK_COOLDOWN_MILLIS : cause == Cause.SLEEP ? SLEEP_COOLDOWN_MILLIS : 0;
        Long last = noticed.get(key(rivalId, cause));
        return pause > 0 && last != null && now >= last && now - last < pause;
    }

    /** Ревнующий узнал о поводе: уровень растёт (до {@link #MAX}), соперник и повод запоминаются. Возвращает, на сколько вырос
     * уровень (0 — пауза или нулевая сила). */
    public int feel(Cause cause, String rivalId, String rivalName, Gender rivalGender, String detail, int amount, Source source, long now) {
        if (cause == null || amount <= 0 || rivalId == null || coolingDown(rivalId, cause, now)) return 0;
        settle(now);
        double before = level;
        level = Math.min(MAX, level + amount);
        noticed.remove(key(rivalId, cause));
        noticed.put(key(rivalId, cause), now);
        while (noticed.size() > NOTICED_LIMIT) noticed.remove(noticed.keySet().iterator().next());
        this.rivalId = rivalId; this.rival = rivalName == null ? "" : TextBounds.characters(rivalName, 60);
        this.rivalGender = rivalGender == null ? "" : rivalGender.name();
        this.cause = cause.name(); this.detail = detail == null ? "" : TextBounds.characters(detail, 80);
        this.seen = source == Source.SAW; this.causeAt = now;
        return (int) Math.round(level - before);
    }

    /** Внимание к ревнующему (разговор, подарок, ночь вместе) гасит ревность; возвращает, на сколько она упала. */
    public int soothe(int amount, long now) {
        if (amount <= 0) return 0;
        settle(now);
        double before = level;
        level = Math.max(0, level - amount);
        if (level < 1) { level = 0; rivalId = ""; rival = ""; rivalGender = ""; cause = ""; detail = ""; }
        return (int) Math.round(before - level);
    }

    /** Ночь рядом с игроком: false, если эта ночь уже засчитана. */
    public boolean night(long now) {
        if (sleptAt >= 0 && now >= sleptAt && now - sleptAt < NIGHT_COOLDOWN_MILLIS) return false;
        sleptAt = now; morning = true;
        return true;
    }
    /** Утренняя реплика: true один раз после ночи вместе. */
    public boolean takeMorning() { boolean was = morning; morning = false; return was; }
    public boolean nightFresh(long now) { return sleptAt >= 0 && now >= sleptAt && now - sleptAt < NIGHT_FRESH_MILLIS; }

    public String rival() { return rival; }
    public Cause cause() { try { return cause.isBlank() ? null : Cause.valueOf(cause); } catch (IllegalArgumentException unknown) { return null; } }

    /** Что игрок сделал с соперником, в словах ревнующего: «опять с ней болтал», «подарил ей minecraft:diamond». */
    public static String deed(Cause cause, Gender rivalGender, Gender playerGender, String detail) {
        boolean she = rivalGender != Gender.MALE;
        String a = playerGender == Gender.FEMALE ? "а" : "";
        String with = she ? "с ней" : "с ним", her = she ? "её" : "его", to = she ? "ей" : "ему";
        return switch (cause) {
            case TALK -> "опять " + with + " болтал" + a;
            case SLEEP -> "спал" + a + " рядом " + with;
            case FAVOR -> "выполнял" + a + " " + her + " поручения";
            case GIFT -> "подарил" + a + " " + to + (detail == null || detail.isBlank() ? " подарок" : " " + detail);
        };
    }
    /** Строка для промпта: ревность с соперником по имени и поводом; "" ниже {@link #HINT_FROM} или без соперника. */
    public String hint(Gender self, Gender playerGender, boolean married, long now) {
        int value = level(now);
        Cause last = cause();
        if (value < HINT_FROM || last == null || rival.isBlank()) return "";
        Gender rg = Gender.of(rivalGender);
        String him = playerGender == Gender.FEMALE ? "её" : "его";
        String how = value >= 60 ? "сильно ревнуешь " + him + " и обижен" + (self == Gender.FEMALE ? "а" : "") : value >= 30 ? "ревнуешь " + him : "слегка ревнуешь " + him;
        String knew = seen ? (self == Gender.FEMALE ? "ты видела это сама" : "ты видел это сам") : "тебе донесли в деревне";
        return "ты " + how + " (ревность " + value + " из 100" + (married ? ", вы же в браке" : "") + "): "
                + (rg == Gender.MALE ? "соперник" : "соперница") + " — " + rival + ", игрок " + deed(last, rg, playerGender, detail) + " — " + knew
                + ". Дай это почувствовать по характеру — колкость, упрёк, обида, имя соперника к слову, — но не в каждой реплике; "
                + "если игрок извиняется, дарит тебе что-то или уделяет тебе время — понемногу оттаивай.";
    }
    /** Строка для промпта после ночи вместе; "" если её не было недавно. */
    public String nightHint(long now) { return nightFresh(now) ? "этой ночью вы спали рядом — тебе от этого тепло, можно к слову вспомнить." : ""; }

    /** Для /mobtalk state: «ревность 34 (соперник: Фёкла)»; "" без ревности. */
    public String describe(long now) {
        int value = level(now);
        return value <= 0 ? "" : "ревность " + value + (rival.isBlank() ? "" : " (соперник: " + rival + ")");
    }

    void sanitize() {
        if (!Double.isFinite(level)) level = 0;
        level = Math.max(0, Math.min(MAX, level));
        if (rivalId == null) rivalId = ""; if (rival == null) rival = ""; if (rivalGender == null) rivalGender = "";
        if (cause == null) cause = ""; if (detail == null) detail = "";
        if (noticed == null) noticed = new LinkedHashMap<>();
        noticed.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        while (noticed.size() > NOTICED_LIMIT) noticed.remove(noticed.keySet().iterator().next());
    }
    Jealousy copy() {
        Jealousy c = new Jealousy(); c.level = level; c.at = at; c.rivalId = rivalId; c.rival = rival; c.rivalGender = rivalGender;
        c.cause = cause; c.detail = detail; c.seen = seen; c.causeAt = causeAt; c.noticed = new LinkedHashMap<>(noticed);
        c.sleptAt = sleptAt; c.morning = morning;
        return c;
    }
}
