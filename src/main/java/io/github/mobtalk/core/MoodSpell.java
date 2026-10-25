package io.github.mobtalk.core;

import java.util.Locale;

/** A long mood: what a creature falls into when one feeling holds for long (gloom turns into depression, fear into dread) or
 * after a big event (a death in the family, a wedding, a miracle). While it lasts it moves the rest point of the axes, so the
 * mob does not just snap back in ten minutes; it changes how the mob talks ({@link #prompt}, {@link #voice}, how long its
 * replies are) and how it deals. It ends no sooner than {@code minMinutes} and only once the opposite kind of impressions
 * reached {@code cure} points (kind words cure a depression, a blow breaks a joy), or at {@code maxMinutes} in any case; an
 * exhaustion ends with a good sleep. The {@code key} names the lang word on the nameplate ({@code mobtalk.state.<key>}). */
public enum MoodSpell {
    GRIEF("grief", "в трауре", 7, -1, -20, 0, -10, -10, 40, 90, 120, 60, Deals.HARDER, Boolean.FALSE,
            "Ты в трауре: недавно умер близкий тебе человек (см. память). Это горе, а не пустота: тебе больно, ты то и дело вспоминаешь "
                    + "умершего, говоришь о нём охотнее, чем о делах, голос дрожит, можешь заплакать. Шутки неуместны и задевают, дела и торговля "
                    + "сейчас не важны, работаешь через силу. Сочувствие принимаешь с благодарностью.",
            "траур: тихо, скупо, мыслями с умершим", "сочувствие, доброе слово, время"),
    DEPRESSION("depressed", "в депрессии", 8, -1, -25, 0, -20, -15, 45, 180, 150, 40, Deals.HARDER, Boolean.FALSE,
            "У тебя депрессия: давняя тяжёлая тоска. Это не горе о ком-то, а серость во всём: ты винишь себя, считаешь себя никчёмн{ым|ой} "
                    + "и лишн{им|ей}, будущее кажется безнадёжным, свои желания и цели ты забросил{|а}, ходишь медленно. Отвечаешь вяло, коротко "
                    + "и с горечью, будто ничего не имеет смысла; на шутки не реагируешь, можешь попросить оставить "
                    + "тебя в покое. Торговаться нет сил, уступать — тем более. Если игрок искренне поддержит, ты чуть оттаиваешь, но не сразу и не до конца.",
            "депрессия: вяло, коротко, с горечью, всё безнадёжно", "доброе слово, подарки, помощь, Божья милость"),
    DREAD("dread", "запуган{|а}", 9, -1, -5, -25, 0, -10, 20, 60, 80, 50, Deals.REFUSES, Boolean.FALSE,
            "Ты запуган{|а}: на тебя за последнее время слишком много навалилось — удары, угрозы, нападения. Вздрагиваешь, озираешься, "
                    + "говоришь сбивчиво и рвёшься уйти в дом; ни на что не соглашаешься и боишься любого резкого слова.",
            "запуган{|а}: сбивчиво, озираешься, хочешь уйти", "покой и безопасность, извинение, доброе слово"),
    APATHY("apathetic", "в апатии", 16, -1, -10, 5, -30, -35, 30, 180, 140, 25, Deals.NORMAL, Boolean.FALSE,
            "Ты в апатии: внутри пусто. Это не грусть и не горе — чувств нет вовсе, ни плохих, ни хороших. Тебе всё равно: на игрока, "
                    + "на подарки, на цены, на угрозы, даже на себя. Ты лежишь в кровати днём и ночью и не встаёшь; работа, еда и соседи не "
                    + "интересуют. Отвечаешь одним-двумя словами, ровно и равнодушно, без жалоб и без слёз, каждый раз другими словами; "
                    + "на вопросы часто не отвечаешь по существу, о помощи не просишь и не благодаришь. Растормошить тебя может только "
                    + "настойчивая забота: еда, разговоры, подарки раз за разом.",
            "апатия: одно-два слова, ровно, без чувств, всё равно", "еда, разговоры и подарки раз за разом"),
    ANGER("embittered", "озлоблен{|а}", 10, -1, -10, -15, 0, 0, 15, 45, 60, 70, Deals.HARDER, null,
            "Ты озлоблен{|а}: накопилось. Огрызаешься, ворчишь, срываешься даже из-за мелочи, цену не уступаешь и хочешь, чтобы от тебя отстали.",
            "озлоблен{|а}: огрызаешься, ворчишь, не уступаешь", "извинение, подарок, время"),
    EXHAUSTION("exhausted", "вымотан{|а}", 11, -1, -8, 0, -25, -5, 10, 120, 400, 60, Deals.NORMAL, Boolean.FALSE,
            "Ты вымотан{|а} до предела: еле держишься на ногах, зеваешь, теряешь нить разговора, отвечаешь коротко и невпопад и думаешь "
                    + "только о том, как бы прилечь.",
            "вымотан{|а}: зеваешь, коротко, мысли о сне", "сон в своей кровати, еда"),
    LONELY("lonely", "тоскует", 12, -1, -8, 0, 0, 20, 10, 90, 5, 110, Deals.NORMAL, Boolean.TRUE,
            "Тебе одиноко: ты давно ни с кем толком не говорил{|а}. Рад{|а} любому собеседнику, сам{|а} цепляешься за разговор, расспрашиваешь, "
                    + "не хочешь отпускать и можешь пожаловаться на одиночество.",
            "одиночество: цепляешься за разговор, расспрашиваешь", "разговор"),
    JOY("happy", "счастлив{|а}", 13, 1, 15, 5, 10, 5, 20, 60, 80, 110, Deals.GENEROUS, Boolean.TRUE,
            "Ты счастлив{|а}: жизнь удалась. Говоришь охотно и тепло, шутишь, всех хвалишь, щедр{|а} на уступки и мелкие подарки, делишься радостью.",
            "счастье: тепло, шутишь, щедр{|а}", ""),
    LOVE("in_love", "влюбл{ён|ена}", 14, 1, 12, 0, 5, 10, 30, 90, 80, 100, Deals.GENEROUS, null,
            "Ты влюбл{ён|ена}: мысли то и дело улетают к избраннику (см. семью и память), ты мечтательно улыбаешься, рассеян{|а}, и всё вокруг кажется прекрасным.",
            "влюблённость: мечтательно, рассеянно, светло", ""),
    GRACE("grace", "в благодати", 15, 1, 12, 20, 5, 0, 30, 60, 80, 100, Deals.GENEROUS, null,
            "На тебе благодать: ты недавно видел{|а} Божье чудо или милость. Ты умиротвор{ён|ена} и крот{ок|ка}, говоришь светло, благодаришь Бога, "
                    + "прощаешь обиды и склон{ен|на} к милосердию.",
            "благодать: кротко, светло, прощаешь", "");

    /** How the long mood changes trading. */
    public enum Deals { NORMAL, HARDER, REFUSES, GENEROUS }

    /** Texts with {@code {he|she}} endings ({@link MoodWords}); read them through {@link #word(boolean)} and the like. */
    public final String key, word, prompt, voice, help;
    /** The face over the head: glyph {@code \uE000 + glyph} of the font {@code mobtalk:mood}. */
    public final int glyph;
    public final int sign, mood, calm, energy, interest, minMinutes, maxMinutes, cure, lengthPercent;
    public final Deals deals;
    /** True: turns to the player on its own; false: never does; null: as the interest says. */
    public final Boolean keen;

    MoodSpell(String key, String word, int glyph, int sign, int mood, int calm, int energy, int interest, int minMinutes, int maxMinutes, int cure,
              int lengthPercent, Deals deals, Boolean keen, String prompt, String voice, String help) {
        this.key = key; this.word = word; this.glyph = glyph; this.sign = sign; this.mood = mood; this.calm = calm; this.energy = energy; this.interest = interest;
        this.minMinutes = minMinutes; this.maxMinutes = maxMinutes; this.cure = cure; this.lengthPercent = lengthPercent; this.deals = deals; this.keen = keen;
        this.prompt = prompt; this.voice = voice; this.help = help;
    }

    public String word(boolean female) { return MoodWords.form(word, female); }
    public String prompt(boolean female) { return MoodWords.form(prompt, female); }
    public String voice(boolean female) { return MoodWords.form(voice, female); }
    /** Apathy keeps the creature in bed day and night. */
    public boolean bedridden() { return this == APATHY; }

    int delta(MobState.Axis axis) {
        return switch (axis) { case MOOD -> mood; case CALM -> calm; case ENERGY -> energy; case INTEREST -> interest; case TRUST -> 0; };
    }
    /** A grief or a fear outweighs a joy: a spell only gives way to a stronger one (lower ordinal) or ends by itself. */
    boolean yieldsTo(MoodSpell other) { return other != null && other.ordinal() < ordinal(); }

    /** By its lang key ({@code "in_love"}), as the state packet carries it; null for none or unknown. */
    public static MoodSpell byKey(String key) {
        for (MoodSpell spell : values()) if (spell.key.equals(key)) return spell;
        return null;
    }

    static MoodSpell of(String name) {
        if (name == null) return null;
        try { return valueOf(name.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException unknown) { return null; }
    }
}
