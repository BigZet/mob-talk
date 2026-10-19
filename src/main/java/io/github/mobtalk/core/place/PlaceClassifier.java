package io.github.mobtalk.core.place;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** The place classifier run by hand: a small 3D convolutional net over the 16³ cube around a creature, trained in Python and
 * shipped as a flat binary of folded weights. Plain Java loops, no native libraries: tens of milliseconds on one core, run off
 * the game thread.
 *
 * <p>Format 1 (tools/voxel/train.py): one-hot materials in, one head of generated-scene labels (PIT, HOUSE, ...).
 * Format 2 (tools/voxel/train2.py): one-hot materials and one-hot block families in, a fine head (Russian class names, each with
 * its parent function) and optionally a function head; without one, a function's probability is the sum of its classes'.
 * The flood's and the registry's facts stay in charge; this only guesses what the place is for. */
public final class PlaceClassifier {
    public static final String RESOURCE = "/assets/mobtalk/place/classifier.bin";
    private static final int CUBE = VoxelGrid.CUBE;

    /** One layer of the net; convolutions carry their weights as [cout][cin][k][k][k]. */
    private record Layer(int type, int cin, int cout, int k, int stride, int pad, float[] weights, float[] bias) { }
    /** One output head: labels, the function index of each label, and its linear layer. */
    private record Head(String name, List<String> labels, int[] parents, Layer linear) { }

    private final int version, materials, families;
    private final List<Layer> layers;
    private final List<Head> heads;
    private final List<String> functions;

    private PlaceClassifier(int version, int materials, int families, List<Layer> layers, List<Head> heads, List<String> functions) {
        this.version = version; this.materials = materials; this.families = families; this.layers = layers; this.heads = heads; this.functions = functions;
    }

    public int version() { return version; }
    /** The labels of the fine head (format 2) or the only head (format 1). */
    public List<String> labels() { return heads.getFirst().labels(); }
    /** The functions of places (format 2), empty for format 1. */
    public List<String> functions() { return functions; }
    /** Whether the model carries its own function head. */
    public boolean hasFunctionHead() { return heads.size() > 1; }

    /** Reads the weights file; see train.py (format 1) and train2.py (format 2) for the layout. */
    public static PlaceClassifier read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        byte[] magic = new byte[4]; in.readFully(magic);
        if (!new String(magic, java.nio.charset.StandardCharsets.US_ASCII).equals("MTCL")) throw new IOException("not a classifier file");
        int version = in.readInt();
        if (version == 1) {
            int channels = in.readInt(), cube = in.readInt(), classes = in.readInt();
            if (cube != CUBE) throw new IOException("unsupported cube " + cube);
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < classes; i++) labels.add(in.readUTF());
            List<Layer> all = readLayers(in);
            Layer linear = all.removeLast();
            if (linear.type() != 4) throw new IOException("format 1 must end in a linear layer");
            return new PlaceClassifier(1, channels, 0, all, List.of(new Head("fine", labels, new int[labels.size()], linear)), List.of());
        }
        if (version != 2) throw new IOException("unsupported classifier version " + version);
        int materials = in.readInt(), families = in.readInt(), cube = in.readInt();
        if (cube != CUBE) throw new IOException("unsupported cube " + cube);
        int nf = in.readInt();
        List<String> functions = new ArrayList<>();
        for (int i = 0; i < nf; i++) functions.add(in.readUTF());
        List<Layer> layers = readLayers(in);
        int count = in.readInt();
        List<Head> heads = new ArrayList<>();
        for (int h = 0; h < count; h++) {
            String name = in.readUTF();
            int n = in.readInt();
            List<String> labels = new ArrayList<>(); int[] parents = new int[n];
            for (int i = 0; i < n; i++) { labels.add(in.readUTF()); parents[i] = in.readInt(); }
            int cin = in.readInt(), cout = in.readInt();
            heads.add(new Head(name, labels, parents, new Layer(4, cin, cout, 0, 0, 0, floats(in, cout * cin), floats(in, cout))));
        }
        return new PlaceClassifier(2, materials, families, layers, heads, functions);
    }

    private static List<Layer> readLayers(DataInputStream in) throws IOException {
        int count = in.readInt();
        List<Layer> layers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int type = in.readInt();
            switch (type) {
                case 1 -> {
                    int cin = in.readInt(), cout = in.readInt(), k = in.readInt(), stride = in.readInt(), pad = in.readInt();
                    layers.add(new Layer(1, cin, cout, k, stride, pad, floats(in, cout * cin * k * k * k), floats(in, cout)));
                }
                case 2, 3 -> layers.add(new Layer(type, 0, 0, 0, 0, 0, null, null));
                case 4 -> { int cin = in.readInt(), cout = in.readInt(); layers.add(new Layer(4, cin, cout, 0, 0, 0, floats(in, cout * cin), floats(in, cout))); }
                default -> throw new IOException("unknown layer type " + type);
            }
        }
        return layers;
    }

    private static float[] floats(DataInputStream in, int count) throws IOException {
        byte[] raw = new byte[count * 4]; in.readFully(raw);
        float[] out = new float[count];
        java.nio.ByteBuffer.wrap(raw).asFloatBuffer().get(out);
        return out;
    }

    private static volatile PlaceClassifier bundled;
    private static volatile boolean missing;
    /** The classifier shipped in the JAR, or null when the resource is absent (a build without trained weights). */
    public static PlaceClassifier bundled() {
        if (bundled != null || missing) return bundled;
        synchronized (PlaceClassifier.class) {
            if (bundled != null || missing) return bundled;
            try (InputStream in = PlaceClassifier.class.getResourceAsStream(RESOURCE)) {
                if (in == null) { missing = true; return null; }
                bundled = read(new java.io.BufferedInputStream(in, 1 << 16));
            } catch (IOException e) { missing = true; return null; }
            return bundled;
        }
    }

    /** The pooled features of a cube: materials (and, for format 2, block families) laid out x-major, then y, then z. */
    private float[] features(byte[] cube, byte[] familyCube) {
        int size = CUBE, cells = size * size * size, channels = materials + families;
        float[] x = new float[channels * cells];
        for (int i = 0; i < cells; i++) {
            int m = cube[i] & 0xFF; if (m < materials) x[m * cells + i] = 1f;
            if (families > 0 && familyCube != null) { int f = familyCube[i] & 0xFF; if (f < families) x[(materials + f) * cells + i] = 1f; }
        }
        int c = channels;
        for (Layer layer : layers) {
            switch (layer.type()) {
                case 1 -> { x = conv(x, c, size, layer); c = layer.cout(); size = (size + 2 * layer.pad() - layer.k()) / layer.stride() + 1; }
                case 2 -> { x = pool(x, c, size); size /= 2; }
                case 3 -> { x = gap(x, c, size); size = 1; }
                case 4 -> { x = linear(x, layer); c = layer.cout(); }
                default -> throw new IllegalStateException();
            }
        }
        return x;
    }

    /** Raw scores of the first head for a material cube (format 1, or format 2 without families). */
    public float[] logits(byte[] cube) { return logits(cube, null)[0]; }

    /** Raw scores of every head: [0] the fine head, [1] the function head when there is one. */
    public float[][] logits(byte[] cube, byte[] familyCube) {
        float[] h = features(cube, familyCube);
        float[][] out = new float[heads.size()][];
        for (int i = 0; i < out.length; i++) out[i] = linear(h, heads.get(i).linear());
        return out;
    }

    static float[] softmax(float[] logits) {
        float max = Float.NEGATIVE_INFINITY; for (float v : logits) max = Math.max(max, v);
        double sum = 0; for (float v : logits) sum += Math.exp(v - max);
        float[] out = new float[logits.length];
        for (int i = 0; i < out.length; i++) out[i] = (float) (Math.exp(logits[i] - max) / sum);
        return out;
    }

    /** Probabilities per label of the first head (material cube only). */
    public float[] probabilities(byte[] cube) { return softmax(logits(cube)); }

    /** The best label and its probability (format 1 call shape). */
    public record Guess(String label, int index, float confidence) { }
    public Guess guess(byte[] cube) {
        float[] p = probabilities(cube);
        int best = argmax(p);
        return new Guess(labels().get(best), best, p[best]);
    }

    /** A guess at two levels: what the place is for, and the concrete class. Function is null for format 1. */
    public record Hierarchy(String function, float functionConfidence, String label, float confidence) { }
    public Hierarchy classify(byte[] cube, byte[] familyCube) {
        float[][] logits = logits(cube, familyCube);
        float[] fine = softmax(logits[0]);
        int best = argmax(fine);
        if (functions.isEmpty()) return new Hierarchy(null, 0, labels().get(best), fine[best]);
        float[] function;
        if (logits.length > 1) function = softmax(logits[1]);
        else {
            function = new float[functions.size()];
            int[] parents = heads.getFirst().parents();
            for (int i = 0; i < fine.length; i++) function[parents[i]] += fine[i];
        }
        int f = argmax(function);
        return new Hierarchy(functions.get(f), function[f], labels().get(best), fine[best]);
    }

    /** The function a fine label belongs to, for format 2. */
    public String functionOf(int label) { return functions.isEmpty() ? null : functions.get(heads.getFirst().parents()[label]); }

    private static int argmax(float[] p) { int best = 0; for (int i = 1; i < p.length; i++) if (p[i] > p[best]) best = i; return best; }

    private static float[] conv(float[] x, int cin, int size, Layer layer) {
        int k = layer.k(), pad = layer.pad(), stride = layer.stride(), cout = layer.cout();
        int out = (size + 2 * pad - k) / stride + 1, plane = size * size, cellsIn = size * plane, planeOut = out * out, cellsOut = out * planeOut;
        float[] y = new float[cout * cellsOut];
        float[] w = layer.weights();
        for (int co = 0; co < cout; co++) {
            float bias = layer.bias()[co];
            int yBase = co * cellsOut;
            java.util.Arrays.fill(y, yBase, yBase + cellsOut, bias);
            for (int ci = 0; ci < cin; ci++) {
                int xBase = ci * cellsIn, wBase = (co * cin + ci) * k * k * k;
                for (int kx = 0; kx < k; kx++) for (int ky = 0; ky < k; ky++) for (int kz = 0; kz < k; kz++) {
                    float weight = w[wBase + (kx * k + ky) * k + kz];
                    if (weight == 0f) continue;
                    for (int ox = 0; ox < out; ox++) {
                        int ix = ox * stride - pad + kx; if (ix < 0 || ix >= size) continue;
                        for (int oy = 0; oy < out; oy++) {
                            int iy = oy * stride - pad + ky; if (iy < 0 || iy >= size) continue;
                            int xRow = xBase + ix * plane + iy * size, yRow = yBase + ox * planeOut + oy * out;
                            int zFrom = Math.max(0, pad - kz), zTo = Math.min(out, size + pad - kz);
                            for (int oz = zFrom; oz < zTo; oz++) y[yRow + oz] += weight * x[xRow + oz * stride - pad + kz];
                        }
                    }
                }
            }
        }
        for (int i = 0; i < y.length; i++) if (y[i] < 0) y[i] = 0; // ReLU
        return y;
    }
    private static float[] pool(float[] x, int c, int size) {
        int out = size / 2, cells = size * size * size, cellsOut = out * out * out;
        float[] y = new float[c * cellsOut];
        for (int ch = 0; ch < c; ch++) for (int ox = 0; ox < out; ox++) for (int oy = 0; oy < out; oy++) for (int oz = 0; oz < out; oz++) {
            float best = Float.NEGATIVE_INFINITY;
            for (int dx = 0; dx < 2; dx++) for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++)
                best = Math.max(best, x[ch * cells + ((ox * 2 + dx) * size + oy * 2 + dy) * size + oz * 2 + dz]);
            y[ch * cellsOut + (ox * out + oy) * out + oz] = best;
        }
        return y;
    }
    private static float[] gap(float[] x, int c, int size) {
        int cells = size * size * size;
        float[] y = new float[c];
        for (int ch = 0; ch < c; ch++) { double sum = 0; for (int i = 0; i < cells; i++) sum += x[ch * cells + i]; y[ch] = (float) (sum / cells); }
        return y;
    }
    private static float[] linear(float[] x, Layer layer) {
        float[] y = new float[layer.cout()];
        for (int o = 0; o < layer.cout(); o++) {
            double sum = layer.bias()[o];
            for (int i = 0; i < layer.cin(); i++) sum += layer.weights()[o * layer.cin() + i] * x[i];
            y[o] = (float) sum;
        }
        return y;
    }
}
