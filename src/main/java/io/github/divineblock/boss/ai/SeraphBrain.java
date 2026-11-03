package io.github.divineblock.boss.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** The seraph's v2 net in plain Java: a small pre-norm transformer over the last ticks, attention over the projectiles in
 * the air, and heads for the action and the predictions. Mirrors {@code ml/seraph_dodge/v2/model.py} operation for
 * operation; weights come from {@code export.py}. About 0.6 M multiply-adds a decision. */
public final class SeraphBrain {
    /** 'SRF2' (v2/v3: 23 features, 8 actions) and 'SRF3' (v4: 27, 12); the sizes themselves come from the tensors. */
    private static final int MAGIC = 0x32465253, MAGIC_V4 = 0x33465253;
    private final Map<String, float[]> w = new HashMap<>();
    private final Map<String, int[]> shape = new HashMap<>();
    final int d, layers, heads = 4, T, tokDim, itemDim, act, aux;

    private SeraphBrain(ByteBuffer buf) {
        int count = buf.getInt();
        for (int i = 0; i < count; i++) {
            byte[] name = new byte[buf.getInt()]; buf.get(name);
            int[] dims = new int[buf.getInt()];
            int size = 1;
            for (int k = 0; k < dims.length; k++) { dims[k] = buf.getInt(); size *= dims[k]; }
            float[] data = new float[size];
            buf.asFloatBuffer().get(data); buf.position(buf.position() + size * 4);
            String key = new String(name, StandardCharsets.UTF_8);
            w.put(key, data); shape.put(key, dims);
        }
        T = shape.get("pos")[0]; d = shape.get("pos")[1];
        int l = 0; while (w.containsKey("blocks." + l + ".qkv.weight")) l++;
        layers = l;
        tokDim = shape.get("emb.weight")[1]; itemDim = shape.get("it1.weight")[1];
        act = shape.get("mu.weight")[0]; aux = shape.get("aux.weight")[0];
    }

    /** Reads the net from the classpath, or null when it is missing or broken (the seraph then flies as before). */
    public static SeraphBrain load(String resource) {
        try (InputStream in = SeraphBrain.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            ByteBuffer buf = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            int magic = buf.getInt();
            if (magic != MAGIC && magic != MAGIC_V4) return null;
            return new SeraphBrain(buf);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    /** tokens [T][tokDim] oldest first, items [P][itemDim] (last column 1 = present). Returns the action means then the
     * predictions: {@code act} values, then {@code aux} values. */
    public float[] forward(float[][] tokens, float[][] items) {
        float[][] x = new float[T][];
        float[] pos = w.get("pos");
        for (int t = 0; t < T; t++) {
            x[t] = linear("emb", tokens[t]);
            for (int k = 0; k < d; k++) x[t][k] += pos[t * d + k];
        }
        for (int l = 0; l < layers; l++) x = block("blocks." + l + ".", x);
        float[] h = layerNorm("lnf", x[T - 1]);
        // attention over the projectiles, with an always-present «null» one
        int P = items.length;
        float[][] vals = new float[P + 1][];
        float[] scores = new float[P + 1];
        float[] q = linear("wq", h);
        float[] nul = w.get("null");
        float scale = (float) (1 / Math.sqrt(d));
        for (int p = -1; p < P; p++) {
            float[] e;
            if (p < 0) e = nul;
            else {
                if (items[p][itemDim - 1] < 0.5f) { scores[p + 1] = Float.NEGATIVE_INFINITY; vals[p + 1] = new float[d]; continue; }
                e = linear("it2", relu(linear("it1", items[p])));
            }
            scores[p + 1] = dot(q, linear("wk", e)) * scale;
            vals[p + 1] = linear("wv", e);
        }
        softmax(scores);
        float[] pooled = new float[d];
        for (int p = 0; p <= P; p++) if (scores[p] > 0) for (int k = 0; k < d; k++) pooled[k] += scores[p] * vals[p][k];
        float[] cat = new float[2 * d];
        System.arraycopy(h, 0, cat, 0, d); System.arraycopy(pooled, 0, cat, d, d);
        float[] z = relu(linear("fc2", relu(linear("fc1", cat))));
        float[] mu = linear("mu", z), ax = linear("aux", z);
        float[] out = new float[act + aux];
        System.arraycopy(mu, 0, out, 0, act); System.arraycopy(ax, 0, out, act, aux);
        return out;
    }

    private float[][] block(String p, float[][] x) {
        int dh = d / heads;
        float[][] qkv = new float[T][];
        for (int t = 0; t < T; t++) qkv[t] = linear(p + "qkv", layerNorm(p + "ln1", x[t]));
        float[][] y = new float[T][d];
        float scale = (float) (1 / Math.sqrt(dh));
        float[] att = new float[T];
        for (int hd = 0; hd < heads; hd++) {
            int qo = hd * dh, ko = d + hd * dh, vo = 2 * d + hd * dh;
            for (int i = 0; i < T; i++) {
                for (int j = 0; j < T; j++) {
                    float s = 0;
                    for (int k = 0; k < dh; k++) s += qkv[i][qo + k] * qkv[j][ko + k];
                    att[j] = s * scale;
                }
                softmax(att);
                for (int j = 0; j < T; j++) for (int k = 0; k < dh; k++) y[i][qo + k] += att[j] * qkv[j][vo + k];
            }
        }
        float[][] out = new float[T][];
        for (int t = 0; t < T; t++) {
            float[] a = linear(p + "proj", y[t]);
            float[] r = new float[d];
            for (int k = 0; k < d; k++) r[k] = x[t][k] + a[k];
            float[] f = linear(p + "ff2", relu(linear(p + "ff1", layerNorm(p + "ln2", r))));
            for (int k = 0; k < d; k++) r[k] += f[k];
            out[t] = r;
        }
        return out;
    }

    private float[] linear(String name, float[] in) {
        float[] wt = w.get(name + ".weight"), b = w.get(name + ".bias");
        int outN = shape.get(name + ".weight")[0], inN = shape.get(name + ".weight")[1];
        float[] out = new float[outN];
        for (int o = 0; o < outN; o++) {
            float s = b[o];
            int row = o * inN;
            for (int i = 0; i < inN; i++) s += wt[row + i] * in[i];
            out[o] = s;
        }
        return out;
    }

    private float[] layerNorm(String name, float[] in) {
        float[] g = w.get(name + ".weight"), b = w.get(name + ".bias");
        double mean = 0, var = 0;
        for (float v : in) mean += v;
        mean /= in.length;
        for (float v : in) var += (v - mean) * (v - mean);
        var /= in.length;
        double inv = 1 / Math.sqrt(var + 1e-5);
        float[] out = new float[in.length];
        for (int i = 0; i < in.length; i++) out[i] = (float) ((in[i] - mean) * inv) * g[i] + b[i];
        return out;
    }

    private static float[] relu(float[] v) { for (int i = 0; i < v.length; i++) if (v[i] < 0) v[i] = 0; return v; }

    private static float dot(float[] a, float[] b) { float s = 0; for (int i = 0; i < a.length; i++) s += a[i] * b[i]; return s; }

    private static void softmax(float[] v) {
        float max = Float.NEGATIVE_INFINITY;
        for (float x : v) max = Math.max(max, x);
        double sum = 0;
        for (int i = 0; i < v.length; i++) { v[i] = v[i] == Float.NEGATIVE_INFINITY ? 0 : (float) Math.exp(v[i] - max); sum += v[i]; }
        for (int i = 0; i < v.length; i++) v[i] /= sum;
    }
}
