package io.github.mobtalk.server.web;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** squaremap's files behind the admin map: zoom renumbering, worlds, bounds, change events, and falling back to the own renderer. */
class SquaremapTilesTest {
    @TempDir Path dir;

    private Path tiles() throws Exception {
        Path tiles = dir.resolve("squaremap").resolve("web").resolve("tiles");
        Path world = tiles.resolve("minecraft_overworld");
        Files.createDirectories(world.resolve("3"));
        Files.createDirectories(world.resolve("1"));
        Files.writeString(world.resolve("settings.json"), "{\"spawn\":{\"x\":0,\"z\":0},\"zoom\":{\"max\":3,\"def\":3,\"extra\":2}}");
        Files.write(world.resolve("3").resolve("0_0.png"), new byte[]{1});
        Files.write(world.resolve("3").resolve("-2_1.png"), new byte[]{2});
        Files.write(world.resolve("1").resolve("0_0.png"), new byte[]{3});
        Files.createDirectories(tiles.resolve("minecraft_the_nether").resolve("3"));
        return tiles;
    }

    @Test void readsSquaremapTilesWithTheApiZoom() throws Exception {
        Path tiles = tiles();
        assertEquals(tiles, SquaremapTiles.find(dir, ""));
        SquaremapTiles map = new SquaremapTiles(tiles, message -> fail(message));
        assertTrue(map.available());
        assertEquals(512, map.tileSize("minecraft:overworld"));
        assertEquals(3, map.maxZoom("minecraft:overworld"));
        assertArrayEquals(new byte[]{1}, map.tile("minecraft:overworld", 0, 0, 0), "zoom 0 = самая подробная папка squaremap (3)");
        assertArrayEquals(new byte[]{3}, map.tile("minecraft:overworld", 2, 0, 0), "zoom 2 = папка 1");
        assertNull(map.tile("minecraft:overworld", 0, 5, 5));
        assertNull(map.tile("minecraft:overworld", 5, 0, 0), "мельче, чем рисует squaremap");
        assertNull(map.tile("../x:y", 0, 0, 0));
        assertEquals(List.of("minecraft:overworld", "minecraft:the_nether"), map.worlds());
        assertArrayEquals(new int[]{-1024, 0, 511, 1023}, map.bounds("minecraft:overworld"));
        assertTrue(map.modified("minecraft:overworld", 0, 0, 0) > 0);
    }

    @Test void changedTilesBecomeEvents() throws Exception {
        Path tiles = tiles();
        SquaremapTiles map = new SquaremapTiles(tiles, message -> fail(message));
        List<JsonObject> events = new CopyOnWriteArrayList<>();
        map.listen(events::add);
        assertEquals(0, map.scan(), "первый просмотр только запоминает");
        Path tile = tiles.resolve("minecraft_overworld").resolve("1").resolve("0_0.png");
        Files.write(tile, new byte[]{9});
        Files.setLastModifiedTime(tile, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertEquals(1, map.scan());
        assertEquals(2, events.get(0).get("zoom").getAsInt());
        assertEquals("minecraft:overworld", events.get(0).get("world").getAsString());
    }

    @Test void withoutSquaremapTheOwnRendererServes() throws Exception {
        MapTiles own = new MapTiles(dir.resolve("own"), message -> fail(message));
        int[] colors = new int[256];
        Arrays.fill(colors, 0xFF7FB238);
        own.update("minecraft:overworld", 0, 0, colors);
        own.flush();
        SquaremapTiles absent = new SquaremapTiles(SquaremapTiles.find(dir, ""), message -> fail(message));
        MapSource map = MapSource.choose(absent, own);
        assertEquals("mobtalk", map.name("minecraft:overworld"));
        assertEquals(256, map.tileSize("minecraft:overworld"));
        assertNotNull(map.tile("minecraft:overworld", 0, 0, 0));
        tiles();
        assertEquals("squaremap", map.name("minecraft:overworld"), "squaremap начал рисовать — карта переключилась");
        assertEquals(512, map.tileSize("minecraft:overworld"));
        assertEquals("mobtalk", map.name("minecraft:the_end"), "энд squaremap не рисует — свой рендер");
        assertFalse(absent.has("minecraft:the_nether"), "пустая папка мира — не нарисовано");
        own.close();
    }
}
