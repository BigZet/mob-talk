package io.github.divineblock.core;

/** The god's voice in a stone temple: a big reverb (a Freeverb-style hall of parallel damped combs and serial allpasses,
 * with a short pre-delay) and a quiet echo behind it. Works on 16-bit mono PCM at any rate; adds a tail so the hall rings
 * out after the last word, and keeps the peak below clipping. Pure Java, runs on the voice worker. */
public final class VoiceEffects {
    /** The knobs, as the settings store them: reverb size and level, echo time, level and repeats, all 0..100 except the time. */
    public record Settings(int roomSize, int reverbLevel, int echoMs, int echoLevel, int echoRepeats) {
        public Settings {
            roomSize = clamp(roomSize, 0, 100); reverbLevel = clamp(reverbLevel, 0, 100);
            echoMs = clamp(echoMs, 80, 1000); echoLevel = clamp(echoLevel, 0, 100); echoRepeats = clamp(echoRepeats, 0, 100);
        }
    }
    /** Named starting points; the settings can tune any of them further. */
    public enum Preset {
        CHAPEL("часовня", new Settings(35, 35, 300, 0, 0)),
        TEMPLE("храм", new Settings(70, 55, 420, 16, 30)),
        CATHEDRAL("собор", new Settings(90, 70, 520, 14, 35)),
        CANYON("ущелье", new Settings(55, 40, 330, 35, 50)),
        HEAVENS("небеса", new Settings(100, 85, 650, 22, 55));
        public final String label;
        public final Settings settings;
        Preset(String label, Settings settings) { this.label = label; this.settings = settings; }
    }

    private static final double[] COMBS_MS = { 50.3, 56.1, 61.7, 67.9, 73.3, 79.1 };
    private static final double[] ALLPASS_MS = { 5.1, 12.6, 1.7 };
    static final double DAMPING = 0.3, ALLPASS_GAIN = 0.55, PREDELAY_MS = 35;
    public static final double TAIL_SECONDS = 4.0;

    private VoiceEffects() { }

    public static byte[] temple(byte[] pcm, int sampleRate) { return apply(pcm, sampleRate, Preset.TEMPLE.settings); }

    public static byte[] apply(byte[] pcm, int sampleRate, Settings settings) {
        int n = pcm.length / 2;
        if (n == 0) return pcm;
        // Room size stretches the combs and lengthens the decay; the level sets how much hall is mixed in.
        double scale = 0.6 + settings.roomSize() / 100.0 * 0.9;
        double feedback = 0.70 + settings.roomSize() / 100.0 * 0.22;
        double wetLevel = settings.reverbLevel() / 100.0 * 1.3, dryLevel = 1.0 - settings.reverbLevel() / 100.0 * 0.35;
        double echoLevel = settings.echoLevel() / 100.0, echoFeedback = settings.echoRepeats() / 100.0 * 0.7;
        int tail = (int) (TAIL_SECONDS * sampleRate), total = n + tail;
        double[] dry = new double[total];
        for (int i = 0; i < n; i++) dry[i] = (short) ((pcm[2 * i] & 255) | (pcm[2 * i + 1] << 8)) / 32768.0;

        int predelay = (int) (PREDELAY_MS * sampleRate / 1000);
        double[] wet = new double[total];
        if (wetLevel > 0) {
            for (double ms : COMBS_MS) {
                int length = Math.max(1, (int) (ms * scale * sampleRate / 1000));
                double[] buffer = new double[length];
                double store = 0;
                int index = 0;
                for (int i = 0; i < total; i++) {
                    double in = i - predelay >= 0 ? dry[i - predelay] : 0;
                    double out = buffer[index];
                    store = out * (1 - DAMPING) + store * DAMPING;
                    buffer[index] = in + store * feedback;
                    if (++index == length) index = 0;
                    wet[i] += out / COMBS_MS.length;
                }
            }
            for (double ms : ALLPASS_MS) {
                int length = Math.max(1, (int) (ms * sampleRate / 1000));
                double[] buffer = new double[length];
                int index = 0;
                for (int i = 0; i < total; i++) {
                    double delayed = buffer[index];
                    double out = -wet[i] * ALLPASS_GAIN + delayed;
                    buffer[index] = wet[i] + delayed * ALLPASS_GAIN;
                    if (++index == length) index = 0;
                    wet[i] = out;
                }
            }
        }

        // The echo: a feedback delay, low-passed so each repeat sounds farther away.
        int echo = Math.max(1, settings.echoMs() * sampleRate / 1000);
        double[] delayLine = new double[echo];
        double tone = 0, peak = 0;
        int index = 0;
        double[] mix = new double[total];
        for (int i = 0; i < total; i++) {
            double repeat = delayLine[index];
            tone = tone * 0.55 + repeat * 0.45;
            delayLine[index] = dry[i] + tone * echoFeedback;
            if (++index == echo) index = 0;
            mix[i] = dry[i] * dryLevel + wet[i] * wetLevel + tone * echoLevel;
            peak = Math.max(peak, Math.abs(mix[i]));
        }

        double gain = peak > 0.95 ? 0.95 / peak : 1;
        int last = total - 1;
        while (last > n && Math.abs(mix[last] * gain) < 4.0 / 32768) last--;
        byte[] out = new byte[(last + 1) * 2];
        for (int i = 0; i <= last; i++) {
            int v = (int) Math.round(Math.max(-1, Math.min(1, mix[i] * gain)) * 32767);
            out[2 * i] = (byte) v; out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
}
