package io.github.rusflora.hogweed;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.rusflora.RusFlora;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Settings of the hogweed in config/rusflora-hogweed.json, written with the defaults on first start. Everything a server owner
 * may want to soften lives here: whether it spreads at all, how far, how dense a thicket gets, whether it kills crops, burns. */
public final class HogweedConfig {
    /** Mature plants scatter seeds and take over the ground around them. */
    public boolean spread = true;
    /** How far a seed flies, in blocks; twice as far near water. */
    public int spreadRadius = 6;
    /** Seeds thrown per random tick of a plant with ripe seed heads. */
    public int seedsPerTick = 2;
    /** No new seedling where this many hogweed plants already stand in the 7x7 square around it. */
    public int maxPlantsNearby = 16;
    /** At most this many hogweed plants in one chunk. */
    public int maxPlantsPerChunk = 48;
    /** At most this many in the 7x7 chunks (112 by 112 blocks) around a landing seed: a big thicket, but a bounded one. */
    public int maxPlantsPerRegion = 400;
    /** Villagers mow hogweed within 16 blocks of themselves, so villages stay clear. */
    public boolean villagersMow = true;
    /** Cows walk to hogweed within 10 blocks and eat it. */
    public boolean cowsEat = true;
    /** Shade under the leaves smothers grass, flowers, ferns and saplings. */
    public boolean suppressPlants = true;
    /** The shade also withers crops on farmland. */
    public boolean suppressCrops = true;
    /** Sap on the skin burns in sunlight. */
    public boolean burns = true;
    /** Seconds between touching the plant and the burn showing. */
    public int burnDelaySeconds = 30;
    /** Growth speed: 1 is the normal pace, 2 twice as fast, 0.5 half. */
    public double growthSpeed = 1.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static HogweedConfig current = new HogweedConfig();

    public static HogweedConfig get() { return current; }

    /** A detached copy to edit in the settings screen. */
    public HogweedConfig copy() { return GSON.fromJson(GSON.toJson(this), HogweedConfig.class); }

    /** Makes edited settings the current ones and writes them to the file. In single player they apply at once. */
    public static void apply(HogweedConfig edited) {
        edited.clamp();
        current = edited;
        try { Files.writeString(file(), GSON.toJson(edited), StandardCharsets.UTF_8); }
        catch (IOException failure) { RusFlora.LOG.warn("Настройки борщевика не сохранены", failure); }
    }

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("rusflora-hogweed.json"); }

    static void load() {
        Path file = file();
        try {
            if (Files.exists(file)) current = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), HogweedConfig.class);
            if (current == null) current = new HogweedConfig();
            current.clamp();
            Files.writeString(file, GSON.toJson(current), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException failure) {
            RusFlora.LOG.warn("Настройки борщевика не прочитаны, беру значения по умолчанию", failure);
            current = new HogweedConfig();
        }
    }

    private void clamp() {
        spreadRadius = Math.clamp(spreadRadius, 1, 16);
        seedsPerTick = Math.clamp(seedsPerTick, 1, 8);
        maxPlantsNearby = Math.clamp(maxPlantsNearby, 1, 49);
        maxPlantsPerChunk = Math.clamp(maxPlantsPerChunk, 1, 256);
        maxPlantsPerRegion = Math.clamp(maxPlantsPerRegion, 1, 12544);
        burnDelaySeconds = Math.clamp(burnDelaySeconds, 0, 600);
        growthSpeed = Math.clamp(growthSpeed, 0.05, 20);
    }
}
