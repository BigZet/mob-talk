package io.github.mobtalk.core;

/** Громкость голоса моба по расстоянию до игрока. Голос звучит из моба: вблизи (до {@link Curve#full}) в полную силу, дальше плавно
 * стихает и к {@link Curve#silent} сходит на нет — без обрыва на границе дальности разговора. Кривая квадратичная: на слух
 * затихание ровнее линейного (громкость воспринимается логарифмически), а у края она подходит к нулю без ступеньки.
 * Чистая логика без Minecraft: её проверяют юнит-тесты, а {@code MobSpeechSound} каждый тик берёт отсюда громкость. */
public final class VoiceFalloff {
    private VoiceFalloff() { }

    /** За один тик громкость меняется не больше чем на столько: телепорт или резкий поворот не щёлкают в динамиках. */
    public static final double STEP_PER_TICK = 0.08;
    /** Тише этого голос считается замолкшим: за краем слышимости его можно остановить, и это не будет слышно как обрыв. */
    public static final double INAUDIBLE = 0.01;

    /** {@code full} — до этого расстояния голос в полную силу, {@code silent} — здесь он стихает совсем (блоки). */
    public record Curve(double full, double silent) {
        public Curve {
            if (!Double.isFinite(full) || full < 0) full = 0;
            if (!Double.isFinite(silent) || silent <= full) silent = full + 1;
        }
        public static Curve of(MobTalkConfig config) { return new Curve(config.voiceFullRange, config.voiceSilentRange); }

        /** Громкость 0..1 на таком расстоянии. */
        public double gain(double distance) {
            if (!(distance > full)) return 1; // NaN — как вплотную: лучше слышно, чем пропало.
            if (distance >= silent) return 0;
            double t = (distance - full) / (silent - full);
            return (1 - t) * (1 - t);
        }
        /** Дальше края слышимости. */
        public boolean beyond(double distance) { return distance >= silent; }
    }

    /** Шаг от текущей громкости к нужной, не больше {@code maxStep}. */
    public static double approach(double current, double target, double maxStep) {
        if (!Double.isFinite(current)) return target;
        double delta = target - current;
        if (Math.abs(delta) <= maxStep) return target;
        return current + Math.signum(delta) * maxStep;
    }
}
