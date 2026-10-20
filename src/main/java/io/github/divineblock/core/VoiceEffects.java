package io.github.divineblock.core;

/** The god's voice in a stone temple: a big reverb (a Freeverb-style hall of parallel damped combs and serial allpasses,
 * with a short pre-delay) and a quiet echo behind it. Works on 16-bit mono PCM at any rate; adds a tail so the hall rings
 * out after the last word, and keeps the peak below clipping. Pure Java, runs on the voice worker. */
public final class VoiceEffects {
    /** Hall reverb: comb lengths in milliseconds (wide and long for a temple), feedback and damping. */
    private static final double[] COMBS_MS = { 50.3, 56.1, 61.7, 67.9, 73.3, 79.1 };
    private static final double[] ALLPASS_MS = { 5.1, 12.6, 1.7 };
    static final double COMB_FEEDBACK = 0.86, DAMPING = 0.3, ALLPASS_GAIN = 0.55, PREDELAY_MS = 35;
    static final double DRY = 0.85, WET = 0.45;
    /** The quiet echo: one repeat every 420 ms, each fainter and darker. */
    static final double ECHO_MS = 420, ECHO_FEEDBACK = 0.3, ECHO_LEVEL = 0.16;
    static final double TAIL_SECONDS = 3.0;

    private VoiceEffects() { }

    public static byte[] temple(byte[] pcm, int sampleRate) {
        int n = pcm.length / 2;
        if (n == 0) return pcm;
        int tail = (int) (TAIL_SECONDS * sampleRate), total = n + tail;
        double[] dry = new double[total];
        for (int i = 0; i < n; i++) dry[i] = (short) ((pcm[2 * i] & 255) | (pcm[2 * i + 1] << 8)) / 32768.0;

        // Reverb: pre-delay, parallel damped combs, then allpasses that smear the echoes into a hall.
        int predelay = (int) (PREDELAY_MS * sampleRate / 1000);
        double[] wet = new double[total];
        for (double ms : COMBS_MS) {
            int length = Math.max(1, (int) (ms * sampleRate / 1000));
            double[] buffer = new double[length];
            double store = 0;
            int index = 0;
            for (int i = 0; i < total; i++) {
                double in = i - predelay >= 0 ? dry[i - predelay] : 0;
                double out = buffer[index];
                store = out * (1 - DAMPING) + store * DAMPING;
                buffer[index] = in + store * COMB_FEEDBACK;
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

        // The quiet echo: a feedback delay, low-passed so each repeat sounds farther away.
        int echo = (int) (ECHO_MS * sampleRate / 1000);
        double[] delayLine = new double[Math.max(1, echo)];
        double tone = 0;
        int index = 0;
        double[] mix = new double[total];
        double peak = 0;
        for (int i = 0; i < total; i++) {
            double repeat = delayLine[index];
            tone = tone * 0.55 + repeat * 0.45;
            delayLine[index] = dry[i] + tone * ECHO_FEEDBACK;
            if (++index == delayLine.length) index = 0;
            mix[i] = dry[i] * DRY + wet[i] * WET * 2.5 + tone * ECHO_LEVEL;
            peak = Math.max(peak, Math.abs(mix[i]));
        }

        double gain = peak > 0.95 ? 0.95 / peak : 1;
        // Trim silence at the end of the tail so the sound does not linger as nothing.
        int last = total - 1;
        while (last > n && Math.abs(mix[last] * gain) < 1.0 / 32768 * 4) last--;
        byte[] out = new byte[(last + 1) * 2];
        for (int i = 0; i <= last; i++) {
            int v = (int) Math.round(Math.max(-1, Math.min(1, mix[i] * gain)) * 32767);
            out[2 * i] = (byte) v; out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }
}
