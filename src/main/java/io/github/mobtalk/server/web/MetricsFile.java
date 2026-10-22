package io.github.mobtalk.server.web;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One day of metric points in a compact binary file, no database: {@code MTM1}, then blocks. {@code H} names the series of the
 * points after it (short count, then short-length UTF-8 names), {@code P} is a point (long seconds, one float per named series).
 * A new series list just starts a new {@code H}, so older files stay readable; a torn or foreign tail ends the reading, nothing else. */
public final class MetricsFile {
    private MetricsFile() { }

    static final byte[] MAGIC = {'M', 'T', 'M', '1'};

    /** A point in this run's series order: values[i] belongs to {@link MetricSeries#values()}[i], NaN where the file had none. */
    public record Point(long t, float[] values) { }

    /** Appends points; writes the magic and a header when the file is new or its last header names other series. */
    public static void append(Path file, List<Point> points) throws IOException {
        if (points.isEmpty()) return;
        Files.createDirectories(file.toAbsolutePath().getParent());
        List<String> names = names();
        boolean fresh = !Files.isRegularFile(file) || Files.size(file) == 0;
        boolean header = fresh || !names.equals(lastHeader(file));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            if (fresh) out.write(MAGIC);
            if (header) {
                out.writeByte('H');
                out.writeShort(names.size());
                for (String name : names) { byte[] utf = name.getBytes(StandardCharsets.UTF_8); out.writeShort(utf.length); out.write(utf); }
            }
            for (Point point : points) {
                out.writeByte('P');
                out.writeLong(point.t());
                for (float value : point.values()) out.writeFloat(value);
            }
        }
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            bytes.writeTo(out);
        }
    }

    /** Every whole point in the file, mapped onto this run's series; a broken file gives what was readable before the damage. */
    public static List<Point> read(Path file) throws IOException {
        List<Point> points = new ArrayList<>();
        if (!Files.isRegularFile(file)) return points;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (!Arrays.equals(in.readNBytes(4), MAGIC)) return points;
            int[] map = null;
            int count = 0;
            while (true) {
                int block = in.read();
                if (block == -1) break;
                if (block == 'H') {
                    count = in.readUnsignedShort();
                    map = new int[count];
                    for (int i = 0; i < count; i++) {
                        MetricSeries series = MetricSeries.byId(readName(in));
                        map[i] = series == null ? -1 : series.ordinal();
                    }
                } else if (block == 'P' && map != null) {
                    long t = in.readLong();
                    float[] values = new float[MetricSeries.values().length];
                    Arrays.fill(values, Float.NaN);
                    for (int i = 0; i < count; i++) { float value = in.readFloat(); if (map[i] >= 0) values[map[i]] = value; }
                    points.add(new Point(t, values));
                } else break;
            }
        } catch (EOFException torn) {
            // A point cut in half by a crash: keep the whole ones before it.
        }
        return points;
    }

    /** Cuts a torn tail (a crash mid-write) so the next append starts on a whole block; returns the bytes removed. A file without
     * the magic is moved aside as {@code .broken} and a fresh one starts. */
    public static long repair(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return 0;
        long size = Files.size(file), good = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (!Arrays.equals(in.readNBytes(4), MAGIC)) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".broken"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return size;
            }
            good = 4;
            int count = -1;
            while (true) {
                int block = in.read();
                if (block == 'H') {
                    int n = in.readUnsignedShort();
                    long length = 3;
                    for (int i = 0; i < n; i++) length += 2 + readName(in).getBytes(StandardCharsets.UTF_8).length;
                    count = n; good += length;
                } else if (block == 'P' && count >= 0) { in.skipNBytes(8 + 4L * count); good += 1 + 8 + 4L * count; }
                else break;
            }
        } catch (EOFException torn) {
            // good stays at the end of the last whole block.
        }
        if (good >= size) return 0;
        try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) { channel.truncate(good); }
        return size - good;
    }

    static List<String> names() { return Arrays.stream(MetricSeries.values()).map(series -> series.id).toList(); }

    private static List<String> lastHeader(Path file) throws IOException {
        List<String> last = null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (!Arrays.equals(in.readNBytes(4), MAGIC)) return null;
            int count = 0;
            while (true) {
                int block = in.read();
                if (block == -1) break;
                if (block == 'H') {
                    count = in.readUnsignedShort();
                    last = new ArrayList<>();
                    for (int i = 0; i < count; i++) last.add(readName(in));
                } else if (block == 'P') in.skipNBytes(8 + 4L * count);
                else break;
            }
        } catch (EOFException torn) { return null; }
        return last;
    }

    private static String readName(InputStream in) throws IOException {
        int length = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        byte[] utf = in.readNBytes(length);
        if (utf.length != length) throw new EOFException();
        return new String(utf, StandardCharsets.UTF_8);
    }
}
