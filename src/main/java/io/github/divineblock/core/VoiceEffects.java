package io.github.divineblock.core;

import java.io.ByteArrayOutputStream;

/** The god's voice in a stone temple: a big reverb (a Freeverb-style hall of parallel damped combs and serial allpasses,
 * with a short pre-delay) and a quiet echo behind it. Streaming: a {@link Hall} takes the speech chunk by chunk as it
 * arrives and gives the processed sound back at once, so the god speaks while his words are still being synthesised; its
 * tail lets the hall ring out after the last word. A soft limiter keeps the peak below clipping. Pure Java, on the voice
 * worker. */
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
    /** Above this the limiter bends the wave softly toward the ceiling instead of clipping it. */
    static final double KNEE = 0.85, CEILING = 0.95;

    private VoiceEffects() { }

    public static byte[] temple(byte[] pcm, int sampleRate) { return apply(pcm, sampleRate, Preset.TEMPLE.settings); }

    /** The whole sound at once: the speech through the hall, then the hall's tail. */
    public static byte[] apply(byte[] pcm, int sampleRate, Settings settings) {
        if (pcm.length < 2) return pcm;
        Hall hall = new Hall(sampleRate, settings);
        ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + (int) (TAIL_SECONDS * sampleRate * 2));
        out.writeBytes(hall.process(pcm, 0, pcm.length & ~1));
        out.writeBytes(hall.finish());
        return out.toByteArray();
    }

    /** One voice's pass through the temple, sample by sample, keeping its state between chunks. */
    public static final class Hall {
        private final double dryLevel, wetLevel, echoLevel, echoFeedback, feedback;
        private final double[][] combs, allpasses;
        private final int[] combIndex, allpassIndex;
        private final double[] combStore;
        private final double[] predelay, echo;
        private int predelayIndex, echoIndex;
        private double tone;
        private final int sampleRate;
        private int carry = -1; // An odd byte left over from the previous chunk.

        public Hall(int sampleRate, Settings settings) {
            this.sampleRate = sampleRate;
            // Room size stretches the combs and lengthens the decay; the level sets how much hall is mixed in.
            double scale = 0.6 + settings.roomSize() / 100.0 * 0.9;
            feedback = 0.70 + settings.roomSize() / 100.0 * 0.22;
            wetLevel = settings.reverbLevel() / 100.0 * 1.3;
            dryLevel = 1.0 - settings.reverbLevel() / 100.0 * 0.35;
            echoLevel = settings.echoLevel() / 100.0;
            echoFeedback = settings.echoRepeats() / 100.0 * 0.7;
            combs = new double[COMBS_MS.length][];
            for (int i = 0; i < combs.length; i++) combs[i] = new double[Math.max(1, (int) (COMBS_MS[i] * scale * sampleRate / 1000))];
            allpasses = new double[ALLPASS_MS.length][];
            for (int i = 0; i < allpasses.length; i++) allpasses[i] = new double[Math.max(1, (int) (ALLPASS_MS[i] * sampleRate / 1000))];
            combIndex = new int[combs.length]; combStore = new double[combs.length]; allpassIndex = new int[allpasses.length];
            predelay = new double[Math.max(1, (int) (PREDELAY_MS * sampleRate / 1000))];
            echo = new double[Math.max(1, settings.echoMs() * sampleRate / 1000)];
        }

        /** Processes 16-bit little-endian mono bytes; returns as many processed bytes as whole samples came in. */
        public byte[] process(byte[] pcm, int offset, int length) {
            int start = offset, end = offset + length;
            int samples = (length + (carry >= 0 ? 1 : 0)) / 2;
            byte[] out = new byte[samples * 2];
            int written = 0;
            if (carry >= 0 && start < end) {
                written = put(out, written, step((short) ((carry & 255) | (pcm[start] << 8)) / 32768.0));
                start++; carry = -1;
            }
            for (int i = start; i + 1 < end; i += 2) written = put(out, written, step((short) ((pcm[i] & 255) | (pcm[i + 1] << 8)) / 32768.0));
            if (((end - start) & 1) == 1) carry = pcm[end - 1] & 255;
            return written == out.length ? out : java.util.Arrays.copyOf(out, written);
        }

        /** The hall ringing on after the last word, cut where it falls silent. */
        public byte[] finish() {
            int tail = (int) (TAIL_SECONDS * sampleRate);
            byte[] out = new byte[tail * 2];
            int written = 0, quiet = 0;
            for (int i = 0; i < tail; i++) {
                double value = step(0);
                written = put(out, written, value);
                quiet = Math.abs(value) < 4.0 / 32768 ? quiet + 1 : 0;
                if (quiet > sampleRate / 5) break; // A fifth of a second of near silence: the hall has gone quiet.
            }
            return java.util.Arrays.copyOf(out, written);
        }

        private double step(double in) {
            double pre = predelay[predelayIndex];
            predelay[predelayIndex] = in;
            if (++predelayIndex == predelay.length) predelayIndex = 0;
            double wet = 0;
            if (wetLevel > 0) {
                for (int c = 0; c < combs.length; c++) {
                    double[] buffer = combs[c];
                    double out = buffer[combIndex[c]];
                    combStore[c] = out * (1 - DAMPING) + combStore[c] * DAMPING;
                    buffer[combIndex[c]] = pre + combStore[c] * feedback;
                    if (++combIndex[c] == buffer.length) combIndex[c] = 0;
                    wet += out / combs.length;
                }
                for (int a = 0; a < allpasses.length; a++) {
                    double[] buffer = allpasses[a];
                    double delayed = buffer[allpassIndex[a]];
                    double out = -wet * ALLPASS_GAIN + delayed;
                    buffer[allpassIndex[a]] = wet + delayed * ALLPASS_GAIN;
                    if (++allpassIndex[a] == buffer.length) allpassIndex[a] = 0;
                    wet = out;
                }
            }
            double repeat = echo[echoIndex];
            tone = tone * 0.55 + repeat * 0.45;
            echo[echoIndex] = in + tone * echoFeedback;
            if (++echoIndex == echo.length) echoIndex = 0;
            return limit(in * dryLevel + wet * wetLevel + tone * echoLevel);
        }

        private static int put(byte[] out, int at, double value) {
            int v = (int) Math.round(value * 32767);
            out[at] = (byte) v; out[at + 1] = (byte) (v >> 8);
            return at + 2;
        }
    }

    /** A soft limiter: untouched below the knee, bent smoothly toward the ceiling above it, never past it. */
    static double limit(double x) {
        double magnitude = Math.abs(x);
        if (magnitude <= KNEE) return x;
        double bent = KNEE + (CEILING - KNEE) * Math.tanh((magnitude - KNEE) / (CEILING - KNEE));
        return Math.copySign(bent, x);
    }

    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
}
