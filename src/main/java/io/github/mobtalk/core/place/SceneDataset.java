package io.github.mobtalk.core.place;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** Writes generated scenes as a flat binary dataset the Python trainer reads with numpy: a header, then for every sample the
 * 16³ cube of material ids (x-major, then y, then z) followed by one label byte. Labels are balanced and the order is shuffled.
 * A sidecar {@code <out>.fam} holds the same cubes as {@link BlockFamily} ids, one 4096-byte cube per sample.
 * <pre>java -cp build/classes/java/main io.github.mobtalk.core.place.SceneDataset out.mtvx 60000 [seed]</pre> */
public final class SceneDataset {
    /** File magic, then the counts, as big-endian int32: version, samples, cube edge, material count, label count, then the
     * label names as modified UTF-8 strings. */
    public static final String MAGIC = "MTVX";
    public static final int VERSION = 1;

    private SceneDataset() { }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        int count = Integer.parseInt(args[1]);
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 20261019L;
        long started = System.nanoTime();
        write(out, count, seed);
        System.out.println("wrote " + count + " samples to " + out + " in " + (System.nanoTime() - started) / 1_000_000 + " ms");
    }

    public static void write(Path out, int count, long seed) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20); DataOutputStream data = new DataOutputStream(raw)) {
            data.writeBytes(MAGIC);
            data.writeInt(VERSION); data.writeInt(count); data.writeInt(VoxelGrid.CUBE); data.writeInt(Material.COUNT); data.writeInt(SceneGenerator.Label.ALL.length);
            for (SceneGenerator.Label label : SceneGenerator.Label.ALL) data.writeUTF(label.name());
            Random order = new Random(seed ^ 0x5DEECE66DL);
            SceneGenerator generator = new SceneGenerator(seed);
            int[] labels = new int[count];
            for (int i = 0; i < count; i++) labels[i] = i % SceneGenerator.Label.ALL.length;
            for (int i = count - 1; i > 0; i--) { int j = order.nextInt(i + 1); int t = labels[i]; labels[i] = labels[j]; labels[j] = t; }
            // The block families go to a sidecar file in the same order, so version-1 readers keep working.
            try (OutputStream famRaw = new BufferedOutputStream(Files.newOutputStream(Path.of(out + ".fam")), 1 << 20)) {
                for (int i = 0; i < count; i++) {
                    SceneGenerator.Scene scene = generator.generate(SceneGenerator.Label.ALL[labels[i]]);
                    data.write(scene.grid().cube());
                    data.writeByte(labels[i]);
                    famRaw.write(scene.grid().familyCube());
                }
            }
        }
    }
}
