/*
 * Mob Talk — human villagers (track M).
 * Derived from MCA Reborn's Genetics (net.conczin.mca.entity.ai.Genetics), Copyright (C) the MCA Reborn contributors,
 * licensed under the GNU General Public License v3.0. Modified for Mob Talk: genes are not stored in the world but derived
 * from the villager's UUID (and its parents' genes for a child born in the family tree), so old worlds and vanilla
 * villagers get a stable look without any saved data.
 */
package io.github.mobtalk.human;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Inborn look of a human villager: body size, skin and hair pigments, face, eye brightness, and picks of hair style and
 * clothes. Every value is in [0, 1]. The same UUID always gives the same genes. */
public record HumanGenes(float size, float width, float breast, float melanin, float hemoglobin, float eumelanin, float pheomelanin,
                         float skin, float face, float eyeBrightness, float hair, float clothes) {
    /** Chance that a villager's skin ignores the climate of its village (MCA's "immigrant"). */
    static final float IMMIGRANT_CHANCE = 0.2f;

    /** Genes of a villager met in the world. {@code temperature} is the climate of its villager type (see {@link #temperature}). */
    public static HumanGenes of(UUID id, float temperature) {
        Random random = random(id, 0x4D43414CL);
        if (random.nextFloat() < IMMIGRANT_CHANCE) temperature = random.nextFloat() * 2 - 0.5f;
        float melanin = clamp(climate(random, temperature));
        float hemoglobin = clamp(climate(random, temperature) * 0.5f);
        return new HumanGenes(centered(random), centered(random), random.nextFloat(), melanin, hemoglobin, random.nextFloat(), random.nextFloat(),
                random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat());
    }

    /** A child of two parents: each inheritable gene lies between the parents' with a small mutation; hair style and clothes are
     * its own. Seeded by the child's UUID, so the same child always looks the same. */
    public static HumanGenes child(UUID id, HumanGenes mother, HumanGenes father) {
        Random random = random(id, 0x4B494EL);
        HumanGenes own = of(id, 0.8f);
        return new HumanGenes(mix(random, mother.size, father.size), mix(random, mother.width, father.width), mix(random, mother.breast, father.breast),
                mix(random, mother.melanin, father.melanin), mix(random, mother.hemoglobin, father.hemoglobin),
                mix(random, mother.eumelanin, father.eumelanin), mix(random, mother.pheomelanin, father.pheomelanin),
                mix(random, mother.skin, father.skin), mix(random, mother.face, father.face), mix(random, mother.eyeBrightness, father.eyeBrightness),
                own.hair, own.clothes);
    }

    /** Climate of a vanilla villager type, as MCA reads the biome temperature: hot types get darker skin. */
    public static float temperature(String villagerType) {
        return switch (villagerType) {
            case "desert" -> 2.0f;
            case "savanna" -> 1.6f;
            case "jungle" -> 1.0f;
            case "snow" -> 0.0f;
            case "taiga" -> 0.25f;
            default -> 0.8f; // plains, swamp and modded types
        };
    }

    /** Picks one of {@code pool} by a gene (MCA's index rule). */
    public static <T> T pick(List<T> pool, float gene) {
        if (pool.isEmpty()) return null;
        return pool.get((int) Math.min(pool.size() - 1, Math.max(0, gene * pool.size())));
    }

    /** Height and width factors of the figure: a little variety around the vanilla size (MCA's range is far wider; the hitbox
     * of a vanilla villager does not change, so the figure stays close to it). */
    public float heightScale() { return 0.94f + size * 0.10f; }
    public float widthScale() { return 0.95f + width * 0.08f; }

    private static Random random(UUID id, long salt) {
        return new Random((id.getMostSignificantBits() * 31 + id.getLeastSignificantBits()) ^ salt);
    }
    /** Between 0 and 1, weighted at 0.5. */
    private static float centered(Random random) { return clamp((random.nextFloat() - 0.5f) * (random.nextFloat() - 0.5f) + 0.5f); }
    private static float climate(Random random, float temperature) { return (random.nextFloat() - 0.5f) * 0.35f + temperature * 0.4f + 0.1f; }
    private static float mix(Random random, float m, float f) {
        float t = random.nextFloat();
        float mutation = (random.nextFloat() - 0.5f) * 0.2f;
        return clamp(m * t + f * (1 - t) + mutation);
    }
    private static float clamp(float v) { return Math.min(1f, Math.max(0f, v)); }
}
