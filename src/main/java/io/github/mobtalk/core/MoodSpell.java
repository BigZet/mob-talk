package io.github.mobtalk.core;

import java.util.Locale;

/** A long mood: what a creature falls into when one feeling holds for long (gloom turns into depression, fear into dread) or
 * after a big event (a death in the family, a wedding, a miracle). While it lasts it moves the rest point of the axes, so the
 * mob does not just snap back in ten minutes; it changes how the mob talks ({@link #prompt}, {@link #voice}, how long its
 * replies are) and how it deals. It ends no sooner than {@code minMinutes} and only once the opposite kind of impressions
 * reached {@code cure} points (kind words cure a depression, a blow breaks a joy), or at {@code maxMinutes} in any case; an
 * exhaustion ends with a good sleep. The {@code key} names the lang word on the nameplate ({@code mobtalk.state.<key>}). */
public enum MoodSpell {
    GRIEF("grief", "в трауре", -1, -20, 0, -10, -10, 40, 90, 120, 60, Deals.HARDER, Boolean.FALSE,
            "Ты в трауре: недавно умер близкий тебе (см. память). Говоришь тихо и мало, мыслями то и дело возвращаешься к умершему, "
                    + "шутки неуместны и задевают, дела и торговля сейчас не важны. Сочувствие принимаешь с благодарностью.",
            "траур: тихо, скупо, мыслями с умершим", "сочувствие, доброе слово, время"),
    DEPRESSION("depressed", "в депрессии", -1, -25, 0, -20, -15, 45, 180, 150, 40, Deals.HARDER, Boolean.FALSE,
            "У тебя депрессия: тебе давно и глубоко плохо. Ничего не хочется, всё серое и бессмысленно, свои желания и цели ты забросил. "
                    + "Отвечаешь вяло и коротко, без восклицаний: «не знаю», «какая разница», «как хочешь»; на шутки не реагируешь, о будущем "
                    + "говоришь безнадёжно, можешь попросить оставить тебя в покое. Торговаться нет сил, уступать — тем более. Если игрок искренне "
                    + "поддержит, ты чуть оттаиваешь, но не сразу и не до конца.",
            "депрессия: вяло, коротко, безнадёжно, ничего не хочется", "доброе слово, подарки, помощь, Божья милость"),
    DREAD("dread", "запуган", -1, -5, -25, 0, -10, 20, 60, 80, 50, Deals.REFUSES, Boolean.FALSE,
            "Ты запуган: на тебя за последнее время слишком много навалилось — удары, угрозы, нападения. Вздрагиваешь, озираешься, "
                    + "говоришь сбивчиво и рвёшься уйти в дом; ни на что не соглашаешься и боишься любого резкого слова.",
            "запуган: сбивчиво, озираешься, хочешь уйти", "покой и безопасность, извинение, доброе слово"),
    ANGER("embittered", "озлоблен", -1, -10, -15, 0, 0, 15, 45, 60, 70, Deals.HARDER, null,
            "Ты озлоблен: накопилось. Огрызаешься, ворчишь, срываешься даже из-за мелочи, цену не уступаешь и хочешь, чтобы от тебя отстали.",
            "озлоблен: огрызаешься, ворчишь, не уступаешь", "извинение, подарок, время"),
    EXHAUSTION("exhausted", "вымотан", -1, -8, 0, -25, -5, 10, 120, 400, 60, Deals.NORMAL, Boolean.FALSE,
            "Ты вымотан до предела: еле держишься на ногах, зеваешь, теряешь нить разговора, отвечаешь коротко и невпопад и думаешь "
                    + "только о том, как бы прилечь.",
            "вымотан: зеваешь, коротко, мысли о сне", "сон в своей кровати, еда"),
    LONELY("lonely", "тоскует", -1, -8, 0, 0, 20, 10, 90, 5, 110, Deals.NORMAL, Boolean.TRUE,
            "Тебе одиноко: ты давно ни с кем толком не говорил. Рад любому собеседнику, сам цепляешься за разговор, расспрашиваешь, "
                    + "не хочешь отпускать и можешь пожаловаться на одиночество.",
            "одиночество: цепляешься за разговор, расспрашиваешь", "разговор"),
    JOY("happy", "счастлив", 1, 15, 5, 10, 5, 20, 60, 80, 110, Deals.GENEROUS, Boolean.TRUE,
            "Ты счастлив: жизнь удалась. Говоришь охотно и тепло, шутишь, всех хвалишь, щедр на уступки и мелкие подарки, делишься радостью.",
            "счастье: тепло, шутишь, щедр", ""),
    LOVE("in_love", "влюблён", 1, 12, 0, 5, 10, 30, 90, 80, 100, Deals.GENEROUS, null,
            "Ты влюблён: мысли то и дело улетают к избраннику (см. семью и память), ты мечтательно улыбаешься, рассеян, и всё вокруг кажется прекрасным.",
            "влюблённость: мечтательно, рассеянно, светло", ""),
    GRACE("grace", "в благодати", 1, 12, 20, 5, 0, 30, 60, 80, 100, Deals.GENEROUS, null,
            "На тебе благодать: ты недавно видел Божье чудо или милость. Ты умиротворён и кроток, говоришь светло, благодаришь Бога, "
                    + "прощаешь обиды и склонен к милосердию.",
            "благодать: кротко, светло, прощаешь", "");

    /** How the long mood changes trading. */
    public enum Deals { NORMAL, HARDER, REFUSES, GENEROUS }

    public final String key, word, prompt, voice, help;
    public final int sign, mood, calm, energy, interest, minMinutes, maxMinutes, cure, lengthPercent;
    public final Deals deals;
    /** True: turns to the player on its own; false: never does; null: as the interest says. */
    public final Boolean keen;

    MoodSpell(String key, String word, int sign, int mood, int calm, int energy, int interest, int minMinutes, int maxMinutes, int cure,
              int lengthPercent, Deals deals, Boolean keen, String prompt, String voice, String help) {
        this.key = key; this.word = word; this.sign = sign; this.mood = mood; this.calm = calm; this.energy = energy; this.interest = interest;
        this.minMinutes = minMinutes; this.maxMinutes = maxMinutes; this.cure = cure; this.lengthPercent = lengthPercent; this.deals = deals; this.keen = keen;
        this.prompt = prompt; this.voice = voice; this.help = help;
    }

    int delta(MobState.Axis axis) {
        return switch (axis) { case MOOD -> mood; case CALM -> calm; case ENERGY -> energy; case INTEREST -> interest; case TRUST -> 0; };
    }
    /** A grief or a fear outweighs a joy: a spell only gives way to a stronger one (lower ordinal) or ends by itself. */
    boolean yieldsTo(MoodSpell other) { return other != null && other.ordinal() < ordinal(); }

    static MoodSpell of(String name) {
        if (name == null) return null;
        try { return valueOf(name.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException unknown) { return null; }
    }
}
