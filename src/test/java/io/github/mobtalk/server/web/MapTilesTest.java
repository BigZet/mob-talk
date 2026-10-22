package io.github.mobtalk.server.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** The admin map's tiles: a chunk lands on the right pixels, coarser zooms average their children, unexplored stays empty. */
class MapTilesTest {
    @TempDir Path dir;

    private static int[] fill(int argb) { int[] colors = new int[256]; Arrays.fill(colors, argb); return colors; }

    private static BufferedImage image(byte[] png) throws IOException { return ImageIO.read(new ByteArrayInputStream(png)); }

    @Test void chunkPaintsItsPixelsAndZoomsAverage() throws IOException {
        MapTiles map = new MapTiles(dir, message -> fail(message));
        List<String> changed = new CopyOnWriteArrayList<>();
        map.listen(tile -> changed.add(Json.string(tile, "zoom") + "/" + Json.string(tile, "tx") + "/" + Json.string(tile, "tz")));
        map.update("minecraft:overworld", 0, 0, fill(0xFFFF0000));
        map.update("minecraft:overworld", 1, 0, fill(0xFF0000FF));
        map.update("minecraft:overworld", -1, -1, fill(0xFF00FF00));
        map.flush();

        BufferedImage zero = image(map.tile("minecraft:overworld", 0, 0, 0));
        assertEquals(MapTiles.SIZE, zero.getWidth());
        assertEquals(0xFFFF0000, zero.getRGB(0, 0));
        assertEquals(0xFF0000FF, zero.getRGB(16, 15));
        assertEquals(0, zero.getRGB(40, 40) >>> 24, "неисследованное прозрачно");
        BufferedImage negative = image(map.tile("minecraft:overworld", 0, -1, -1));
        assertEquals(0xFF00FF00, negative.getRGB(255, 255), "чанк -1,-1 в правом нижнем углу тайла -1,-1");

        BufferedImage one = image(map.tile("minecraft:overworld", 1, 0, 0));
        assertEquals(0xFFFF0000, one.getRGB(0, 0));
        assertEquals(0xFF0000FF, one.getRGB(8, 0), "блок x=16 на зуме 1 — пиксель 8");
        assertTrue(changed.containsAll(List.of("0/0/0", "1/0/0", "4/0/0", "0/-1/-1", "4/-1/-1")), changed.toString());
        for (int zoom = 0; zoom <= MapTiles.MAX_ZOOM; zoom++) assertNotNull(map.tile("minecraft:overworld", zoom, 0, 0), "зум " + zoom);
        map.close();
    }

    @Test void halfKnownPixelsAverageOnlyTheKnownOnes() throws IOException {
        MapTiles map = new MapTiles(dir, message -> fail(message));
        int[] colors = new int[256];
        colors[0] = 0xFF646464; // only (0,0) of the 2×2 block (0..1, 0..1) is known
        map.update("minecraft:overworld", 0, 0, colors);
        map.flush();
        assertEquals(0xFF646464, image(map.tile("minecraft:overworld", 1, 0, 0)).getRGB(0, 0));
        assertEquals(0, image(map.tile("minecraft:overworld", 1, 0, 0)).getRGB(1, 0) >>> 24);
        map.close();
    }

    @Test void emptyTilesBoundsAndWorlds() throws IOException {
        MapTiles map = new MapTiles(dir, message -> fail(message));
        assertNull(map.tile("minecraft:overworld", 0, 0, 0));
        assertNull(map.bounds("minecraft:overworld"));
        map.update("minecraft:overworld", 16, -17, fill(0xFF112233));
        map.update("minecraft:the_nether", 0, 0, fill(0xFF701414));
        map.flush();
        assertArrayEquals(new int[]{256, -512, 511, -257}, map.bounds("minecraft:overworld"));
        assertEquals(List.of("minecraft:overworld", "minecraft:the_nether"), map.worlds());
        assertNull(map.tile("../../etc", 0, 0, 0), "чужой путь — не тайл");
        assertThrows(ApiException.class, () -> map.tile("minecraft:overworld", 5, 0, 0));
        map.close();
    }

    @Test void laterUpdatesPaintOverTilesFromAnEarlierRun() throws IOException {
        MapTiles first = new MapTiles(dir, message -> fail(message));
        first.update("minecraft:overworld", 0, 0, fill(0xFFFF0000));
        first.close();
        MapTiles second = new MapTiles(dir, message -> fail(message));
        second.update("minecraft:overworld", 1, 0, fill(0xFF0000FF));
        second.flush();
        BufferedImage tile = image(second.tile("minecraft:overworld", 0, 0, 0));
        assertEquals(0xFFFF0000, tile.getRGB(0, 0), "старое не стёрто");
        assertEquals(0xFF0000FF, tile.getRGB(16, 0));
        second.close();
    }

    @Test void brokenTileIsRepaintedNotFatal() throws IOException {
        Path broken = dir.resolve("minecraft~overworld").resolve("0").resolve("0_0.png");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "не картинка");
        List<String> log = new CopyOnWriteArrayList<>();
        MapTiles map = new MapTiles(dir, log::add);
        map.update("minecraft:overworld", 0, 0, fill(0xFFFF0000));
        map.close();
        assertEquals(0xFFFF0000, image(map.tile("minecraft:overworld", 0, 0, 0)).getRGB(3, 3));
    }

    @Test void thousandsOfChunksStayCheapForTheCaller() {
        MapTiles map = new MapTiles(dir, message -> fail(message));
        long start = System.nanoTime();
        for (int cz = -40; cz < 40; cz++) for (int cx = -40; cx < 40; cx++) map.update("minecraft:overworld", cx, cz, fill(0xFF7FB238));
        long micros = (System.nanoTime() - start) / 1000 / 6400;
        assertTrue(micros < 200, "update() стоит игровому потоку " + micros + " мкс на чанк");
        map.close();
        assertDoesNotThrow(() -> assertNotNull(map.tile("minecraft:overworld", 4, -1, -1)));
    }
}
