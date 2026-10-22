package io.github.mobtalk.server.web;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** Where the admin map's tiles come from: squaremap's files when the server has it, the admin's own renderer otherwise.
 * Zoom in the API: 0 is 1 px = 1 block, each step up halves the detail; a tile at zoom z covers {@code tileSize()·2^z} blocks. */
public interface MapSource {
    int tileSize();
    int maxZoom();
    /** The PNG, or null when there is no such tile. */
    byte[] tile(String world, int zoom, int tx, int tz) throws IOException;
    /** When the tile last changed, 0 when there is none. */
    long modified(String world, int zoom, int tx, int tz) throws IOException;
    List<String> worlds() throws IOException;
    /** {minX, minZ, maxX, maxZ} in blocks, by whole tiles of zoom 0; null with no tiles. */
    int[] bounds(String world) throws IOException;
    /** Called with {"world","zoom","tx","tz"} when a tile changes. */
    void listen(Consumer<JsonObject> listener);
    /** A name for the admin page: "squaremap" or "mobtalk". */
    String name();
    /** Looks for changed tiles that nobody announced (squaremap writes them itself); called every few seconds while a page is open. */
    default void poll() { }

    /** squaremap when its tiles are there (it may start rendering after the admin), else the fallback; asked on every call. */
    static MapSource choose(SquaremapTiles squaremap, MapSource fallback) {
        return new MapSource() {
            private MapSource now() { return squaremap != null && squaremap.available() ? squaremap : fallback; }
            @Override public int tileSize() { return now().tileSize(); }
            @Override public int maxZoom() { return now().maxZoom(); }
            @Override public byte[] tile(String world, int zoom, int tx, int tz) throws IOException { return now().tile(world, zoom, tx, tz); }
            @Override public long modified(String world, int zoom, int tx, int tz) throws IOException { return now().modified(world, zoom, tx, tz); }
            @Override public List<String> worlds() throws IOException { return now().worlds(); }
            @Override public int[] bounds(String world) throws IOException { return now().bounds(world); }
            @Override public void listen(Consumer<JsonObject> listener) {
                if (squaremap != null) squaremap.listen(listener);
                fallback.listen(tile -> { if (now() == fallback) listener.accept(tile); });
            }
            @Override public String name() { return now().name(); }
            @Override public void poll() { if (squaremap != null && squaremap.available()) squaremap.scan(); }
        };
    }
}
