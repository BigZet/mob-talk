package io.github.mobtalk.core.place;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** The place classifier run by hand: a small 3D convolutional net over the 16³ cube of material ids, one input channel per
 * material, trained in Python (tools/voxel/train.py) and shipped as a flat binary of folded weights. It names the kind of
 * place (house, farm, trap, …) with a confidence; the flood's facts stay in charge of geometry, this only adds meaning.
 * Plain Java loops, no native libraries: a few tens of milliseconds on one core, run off the game thread. */
public final class PlaceClassifier {
    public static final String RESOURCE = "/assets/mobtalk/place/classifier.bin";
    private static final int CUBE = VoxelGrid.CUBE;

    /** One layer of the net; convolutions carry their weights as [cout][cin][k][k][k]. */
    private record Layer(int type, int cin, int cout, int k, int stride, int pad, float[] weights, float[] bias) { }

    private final List<Layer> layers;
    private final List<String> labels;
    private final int channels;

    private PlaceClassifier(List<Layer> layers, List<String> labels, int channels) { this.layers = layers; this.labels = labels; this.channels = channels; }

    public List<String> labels() { return labels; }

    /** Reads the weights file; see train.py for the layout. */
    public static PlaceClassifier read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        byte[] magic = new byte[4]; in.readFully(magic);
        if (!new String(magic, java.nio.charset.StandardCharsets.US_ASCII).equals("MTCL")) throw new IOException("not a classifier file");
        int version = in.readInt(), channels = in.readInt(), cube = in.readInt(), classes = in.readInt();
        if (version != 1 || cube != CUBE) throw new IOException("unsupported classifier version " + version + " cube " + cube);
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < classes; i++) labels.add(in.readUTF());
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
        return new PlaceClassifier(layers, labels, channels);
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

    /** Raw scores per label for a cube of material ids laid out x-major, then y, then z (see {@link VoxelGrid#cube()}). */
    public float[] logits(byte[] cube) {
        int size = CUBE;
        float[] x = new float[channels * size * size * size];
        int cells = size * size * size;
        for (int i = 0; i < cells; i++) { int c = cube[i] & 0xFF; if (c < channels) x[c * cells + i] = 1f; }
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

    /** Probabilities per label. */
    public float[] probabilities(byte[] cube) {
        float[] logits = logits(cube);
        float max = Float.NEGATIVE_INFINITY; for (float v : logits) max = Math.max(max, v);
        double sum = 0; for (float v : logits) sum += Math.exp(v - max);
        float[] out = new float[logits.length];
        for (int i = 0; i < out.length; i++) out[i] = (float) (Math.exp(logits[i] - max) / sum);
        return out;
    }

    /** The best label and its probability. */
    public record Guess(String label, int index, float confidence) { }
    public Guess guess(byte[] cube) {
        float[] p = probabilities(cube);
        int best = 0; for (int i = 1; i < p.length; i++) if (p[i] > p[best]) best = i;
        return new Guess(labels.get(best), best, p[best]);
    }

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
