package io.github.divineblock.boss.ai;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A small trained network (tanh hidden layers, linear output clamped to [-1, 1]) read from the weights file that
 * {@code ml/seraph_dodge/export.py} writes. Plain Java, no libraries: a few thousand multiplications a call. */
public final class DodgeNet {
    private static final int MAGIC = 0x53455241;
    private final float[][] weights;
    private final float[][] biases;
    private final int[] inputs;
    private final int[] outputs;

    private DodgeNet(float[][] weights, float[][] biases, int[] inputs, int[] outputs) {
        this.weights = weights; this.biases = biases; this.inputs = inputs; this.outputs = outputs;
    }

    /** Reads a net from the classpath, or returns null when the file is missing or broken (the boss then flies as before). */
    public static DodgeNet load(String resource) {
        try (InputStream in = DodgeNet.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            ByteBuffer buf = ByteBuffer.wrap(new DataInputStream(in).readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            if (buf.getInt() != MAGIC) return null;
            int layers = buf.getInt();
            float[][] w = new float[layers][], b = new float[layers][];
            int[] ins = new int[layers], outs = new int[layers];
            for (int l = 0; l < layers; l++) {
                ins[l] = buf.getInt(); outs[l] = buf.getInt();
                w[l] = new float[ins[l] * outs[l]]; b[l] = new float[outs[l]];
                buf.asFloatBuffer().get(w[l]); buf.position(buf.position() + w[l].length * 4);
                buf.asFloatBuffer().get(b[l]); buf.position(buf.position() + b[l].length * 4);
                if (l > 0 && ins[l] != outs[l - 1]) return null;
            }
            return new DodgeNet(w, b, ins, outs);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    public int inputSize() { return inputs[0]; }
    public int outputSize() { return outputs[outputs.length - 1]; }

    public float[] forward(float[] x) {
        float[] h = x;
        for (int l = 0; l < weights.length; l++) {
            float[] w = weights[l], b = biases[l], out = new float[outputs[l]];
            int in = inputs[l];
            for (int o = 0; o < out.length; o++) {
                float sum = b[o];
                int row = o * in;
                for (int i = 0; i < in; i++) sum += w[row + i] * h[i];
                out[o] = l < weights.length - 1 ? (float) Math.tanh(sum) : Math.max(-1f, Math.min(1f, sum));
            }
            h = out;
        }
        return h;
    }
}
