package io.github.mobtalk.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** M1 for the flats' furniture models (testing-strategy.md): UVs, empty boxes, z-fighting, textures; variants keep their base's boxes. */
class FurnitureModelsTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets/mobtalk");
    private static final Path MODELS = ASSETS.resolve("models/block/city2/furniture");
    private static final Path TEXTURES = ASSETS.resolve("textures/block/city2/furniture");
    private static final String[] FACES = {"north", "south", "west", "east", "down", "up"};
    private static final int[][] AXIS = {{2, 0}, {2, 1}, {0, 0}, {0, 1}, {1, 0}, {1, 1}};

    private static JsonObject read(Path p) throws IOException {
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    private static double[] vec(JsonElement e) {
        JsonArray a = e.getAsJsonArray();
        return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    private static List<Path> models() throws IOException {
        try (Stream<Path> s = Files.list(MODELS)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void everyModelIsWellFormed() throws IOException {
        List<String> errors = new ArrayList<>();
        for (Path p : models()) {
            String name = p.getFileName().toString();
            JsonObject m = read(p);
            JsonObject textures = m.has("textures") ? m.getAsJsonObject("textures") : new JsonObject();
            for (var t : textures.entrySet()) {
                String id = t.getValue().getAsString();
                if (id.startsWith("mobtalk:") && !Files.exists(ASSETS.resolve("textures").resolve(id.substring(8) + ".png"))) {
                    errors.add(name + ": missing texture " + id);
                }
            }
            JsonArray els = m.has("elements") ? m.getAsJsonArray("elements") : new JsonArray();
            for (int i = 0; i < els.size(); i++) {
                JsonObject e = els.get(i).getAsJsonObject();
                double[] f = vec(e.get("from")), t = vec(e.get("to"));
                boolean rotated = e.has("rotation");
                for (int a = 0; a < 3; a++) {
                    if (t[a] - f[a] < 0) errors.add(name + " #" + i + ": inverted on axis " + a);
                    if (!rotated && (f[a] < -16 || t[a] > 32)) errors.add(name + " #" + i + ": outside -16..32 on axis " + a);
                }
                if (t[0] == f[0] && t[1] == f[1] && t[2] == f[2]) errors.add(name + " #" + i + ": zero box");
                for (var face : e.getAsJsonObject("faces").entrySet()) {
                    JsonObject fc = face.getValue().getAsJsonObject();
                    String ref = fc.get("texture").getAsString();
                    if (!ref.startsWith("#") || !textures.has(ref.substring(1))) errors.add(name + " #" + i + ": unbound texture " + ref);
                    if (fc.has("uv")) {
                        for (JsonElement v : fc.getAsJsonArray("uv")) {
                            double d = v.getAsDouble();
                            if (d < -32 || d > 48) errors.add(name + " #" + i + " " + face.getKey() + ": uv " + d + " outside -32..48");
                        }
                    }
                }
            }
            for (int i = 0; i < els.size(); i++) {
                for (int j = i + 1; j < els.size(); j++) {
                    JsonObject a = els.get(i).getAsJsonObject(), b = els.get(j).getAsJsonObject();
                    if (a.has("rotation") || b.has("rotation")) continue;
                    double[] af = vec(a.get("from")), at = vec(a.get("to")), bf = vec(b.get("from")), bt = vec(b.get("to"));
                    for (int k = 0; k < 6; k++) {
                        if (!a.getAsJsonObject("faces").has(FACES[k]) || !b.getAsJsonObject("faces").has(FACES[k])) continue;
                        int ax = AXIS[k][0];
                        double pa = AXIS[k][1] == 1 ? at[ax] : af[ax], pb = AXIS[k][1] == 1 ? bt[ax] : bf[ax];
                        if (Math.abs(pa - pb) > 1e-6) continue;
                        boolean overlap = true;
                        for (int o = 0; o < 3; o++) {
                            if (o != ax && Math.min(at[o], bt[o]) - Math.max(af[o], bf[o]) <= 1e-6) overlap = false;
                        }
                        if (overlap) errors.add(name + ": z-fighting " + FACES[k] + " faces of #" + i + " and #" + j);
                    }
                }
            }
        }
        assertTrue(errors.isEmpty(), errors.size() + " problems:\n" + String.join("\n", errors.subList(0, Math.min(40, errors.size()))));
    }

    @Test
    void textureTilesAreSquare32() throws IOException {
        List<String> errors = new ArrayList<>();
        try (Stream<Path> s = Files.list(TEXTURES)) {
            for (Path p : s.filter(q -> q.toString().endsWith(".png")).toList()) {
                var img = ImageIO.read(p.toFile());
                if (img == null || img.getWidth() != 32 || img.getHeight() != 32) errors.add(p.getFileName() + ": not 32x32");
            }
        }
        assertTrue(errors.isEmpty(), String.join("\n", errors));
    }

    @Test
    void variantsKeepTheirBaseBoxes() throws IOException {
        JsonObject v = JsonParser.parseString(Files.readString(Path.of("tools/city2/variants.json"))).getAsJsonObject();
        int checked = 0;
        for (var base : v.getAsJsonObject("bases").entrySet()) {
            for (var fam : base.getValue().getAsJsonObject().entrySet()) {
                for (var tag : fam.getValue().getAsJsonObject().entrySet()) {
                    String id = tag.getValue().getAsString();
                    try (Stream<Path> s = Files.list(MODELS)) {
                        for (Path p : s.filter(q -> q.getFileName().toString().startsWith(id)).toList()) {
                            Path twin = MODELS.resolve(p.getFileName().toString().replace(id, base.getKey()));
                            assertTrue(Files.exists(twin), p.getFileName() + " has no base twin " + twin.getFileName());
                            assertEquals(boxes(twin), boxes(p), p.getFileName() + " differs from its base in boxes");
                            checked++;
                        }
                    }
                }
            }
        }
        assertTrue(checked > 100, "variants checked: " + checked);
    }

    private static List<String> boxes(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        JsonObject m = read(p);
        if (m.has("elements")) {
            for (JsonElement e : m.getAsJsonArray("elements")) {
                JsonObject o = e.getAsJsonObject();
                out.add(o.get("from") + "-" + o.get("to") + "-" + o.getAsJsonObject("faces").entrySet().stream().map(f -> f.getKey() + f.getValue().getAsJsonObject().get("uv")).toList() + (o.has("rotation") ? o.get("rotation") : ""));
            }
        }
        return out;
    }
}
