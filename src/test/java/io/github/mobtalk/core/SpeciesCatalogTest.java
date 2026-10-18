package io.github.mobtalk.core;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpeciesCatalogTest {
    private static final Set<String> VANILLA_TYPES = new HashSet<>(Arrays.asList((
            "allay armadillo axolotl bat bee blaze bogged breeze camel cat cave_spider chicken cod cow creeper "
            + "dolphin donkey drowned elder_guardian ender_dragon enderman endermite evoker fox frog ghast giant "
            + "glow_squid goat guardian hoglin horse husk illusioner iron_golem llama magma_cube mooshroom mule "
            + "ocelot panda parrot phantom pig piglin piglin_brute pillager polar_bear pufferfish rabbit ravager "
            + "salmon sheep shulker silverfish skeleton skeleton_horse slime sniffer snow_golem spider squid "
            + "stray strider tadpole trader_llama tropical_fish turtle vex villager vindicator wandering_trader "
            + "warden witch wither wither_skeleton wolf zoglin zombie zombie_horse zombie_villager zombified_piglin")
            .split(" ")));
    // A missing species or reused voice would make the promised species-specific pools incomplete.
    @Test void vanillaCatalogHasEightyTwoCompleteSpeciesAndTwoHundredFortySixIndependentSlots() {
        assertEquals(82, SpeciesCatalog.all().size());
        var speciesIds = new HashSet<String>();
        var voiceIds = new HashSet<String>();
        var prompts = new HashSet<String>();
        for (var species : SpeciesCatalog.all()) {
            assertTrue(speciesIds.add(species.id()), species.id());
            assertTrue(species.id().startsWith("minecraft:"));
            assertTrue(prompts.add(species.prompt()), species.id());
            assertFalse(species.interests().isBlank(), species.id());
            assertFalse(species.fears().isBlank(), species.id());
            // 0.3 personality: without quirks, a desire and a secret the mob falls back to sounding like a shopkeeper.
            assertFalse(species.quirks().isBlank(), species.id());
            assertFalse(species.desire().isBlank(), species.id());
            assertFalse(species.secret().isBlank(), species.id());
            assertEquals(3, species.variants().size(), species.id());
            var variantNames = new HashSet<String>();
            for (var variant : species.variants()) {
                assertTrue(variantNames.add(variant.name()), species.id());
                assertFalse(variant.prompt().isBlank(), species.id());
                assertNotNull(variant.voice(), species.id());
                assertFalse(variant.voice().description().isBlank());
                assertEquals("ru", variant.voice().language());
                if (!variant.voice().id().isBlank()) {
                    assertTrue(voiceIds.add(variant.voice().id()), species.id());
                    assertTrue(variant.voice().previewUrl().startsWith("https://"));
                } else {
                    assertEquals("Не назначен", variant.voice().name());
                    assertTrue(variant.voice().previewUrl().isBlank());
                }
                assertEquals(variant.voice(), SpeciesCatalog.voice(variant.voice().id()));
            }
        }
        assertEquals(246, SpeciesCatalog.all().stream().mapToInt(species -> species.variants().size()).sum());
        assertEquals(VANILLA_TYPES, new HashSet<>(SpeciesCatalog.all().stream()
                .map(species -> species.id().substring("minecraft:".length())).toList()));
    }

    // The explicit casting gate is stricter than runtime loading; this still does not claim inference success.
    @Test
    void fullCastingRequiresTwoHundredFortySixRealDistinctIdsAndUnassignedReserves() throws Exception {
        SpeciesCatalog.validateCompleteCatalog();
        var voices = new HashSet<String>();
        for (var species : SpeciesCatalog.all()) {
            species.variants().forEach(variant -> assertTrue(voices.add(variant.voice().id())));
            assertTrue(voices.add(SpeciesCatalog.reserve(species.id()).id()), species.id());
        }
        assertEquals(328, voices.size());
        assertTrue(SpeciesCatalog.reserve("example:unknown").id().isBlank());
    }

    // Namespace normalization and unknown modded mobs must not silently become a vanilla cow.
    @Test void normalizesVanillaIdsAndProvidesAnExplicitGenericFallback() {
        assertEquals(SpeciesCatalog.get("minecraft:cow"), SpeciesCatalog.get("cow"));
        var unknown = SpeciesCatalog.get("example:custom_mob");
        assertEquals("example:custom_mob", unknown.id());
        assertEquals(3, unknown.variants().size());
        assertFalse(unknown.hostile());
        assertTrue(unknown.prompt().contains("неизвестного"));
    }

    // Overriding a draft must not mutate the shared species defaults or lose the two untouched slots.
    @Test void appliesOnlyNonBlankOverridesWithoutChangingTheBaseCatalog() throws Exception {
        var config = new MobTalkConfig();
        var override = new SpeciesCatalog.Override();
        override.prompt = "Своя основа";
        override.archetypePrompts[1] = "Свой второй характер";
        override.voiceIds[1] = "customVoiceOnlyForCow";
        config.speciesOverrides = Map.of("cow", override);
        SpeciesCatalog.validateOverrides(config);
        var base = SpeciesCatalog.get("cow");
        var actual = SpeciesCatalog.effective("minecraft:cow", config);
        assertEquals("Своя основа", actual.prompt());
        assertEquals("Свой второй характер", actual.variants().get(1).prompt());
        assertEquals("customVoiceOnlyForCow", actual.variants().get(1).voice().id());
        assertEquals(base.variants().get(0), actual.variants().get(0));
        assertEquals(base.variants().get(2), actual.variants().get(2));
        assertNotEquals(actual.prompt(), SpeciesCatalog.get("cow").prompt());
    }

    // Invalid arrays must be rejected during save and remain safe if an in-memory draft is displayed.
    @Test void rejectsMalformedOverrideArraysWhileEffectiveRemainsSafe() {
        var config = new MobTalkConfig();
        var override = new SpeciesCatalog.Override();
        override.voiceIds = new String[]{"a", "b", "c", "d"};
        override.archetypePrompts = null;
        config.speciesOverrides = Map.of("minecraft:cow", override);
        assertThrows(IOException.class, () -> SpeciesCatalog.validateOverrides(config));
        assertDoesNotThrow(() -> SpeciesCatalog.effective("cow", config));
        assertEquals(3, SpeciesCatalog.effective("cow", config).variants().size());
    }

    // A user override cannot reuse another species' voice or collapse the three voices of its own pool.
    @Test void rejectsVoiceCollisionsAcrossSpeciesAndSlots() {
        var config = new MobTalkConfig();
        var override = new SpeciesCatalog.Override();
        override.voiceIds[0] = "sameCustomVoice";
        var pig = new SpeciesCatalog.Override();
        pig.voiceIds[2] = "sameCustomVoice";
        config.speciesOverrides = Map.of("cow", override, "pig", pig);
        assertThrows(IOException.class, () -> SpeciesCatalog.validateOverrides(config));
        config.speciesOverrides = Map.of("cow", override);
        override.voiceIds[1] = "sameCustomVoice";
        assertThrows(IOException.class, () -> SpeciesCatalog.validateOverrides(config));
    }

    // Different spellings of the same species must not create ambiguous overrides.
    @Test void rejectsDuplicateNormalizedSpeciesKeys() {
        var config = new MobTalkConfig();
        config.speciesOverrides = Map.of("cow", new SpeciesCatalog.Override(),
                "minecraft:cow", new SpeciesCatalog.Override());
        assertThrows(IOException.class, () -> SpeciesCatalog.validateOverrides(config));
    }
}
