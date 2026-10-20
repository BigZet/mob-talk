package io.github.mobtalk.core;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Casting a villager's voice from the villagers' own pool: one fixed age per villager, and among the voices of its sex and age
 * the one the fewest other villagers already speak with, so a voice repeats only once every fitting voice is taken. */
final class VillagerVoices {
    private VillagerVoices() { }

    /** The villager's age: a child sounds young; a grown villager gets 30% young, 10% old, the rest middle-aged, fixed by its UUID. */
    static VoiceAge age(UUID id, SocialProfile social) {
        if (social != null && (social.bornTick >= 0 || child(social.stage))) return VoiceAge.YOUNG;
        long seed = id == null ? 0 : id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 17) ^ 0x5DEECE66DL;
        return VoiceAge.roll(new Random(seed));
    }

    /** The least-used pool voice of this sex and age among the other profiles; ties break by a hash of the villager's UUID, so two
     * villagers met at once do not take the same voice in the same order. "" when the pool has none. */
    static String pick(Gender gender, VoiceAge age, UUID self, Collection<ConversationStore.Profile> others) {
        List<SpeciesCatalog.Voice> pool = SpeciesCatalog.villagerPool(age, gender);
        if (pool.isEmpty()) return "";
        Map<String, Integer> uses = new HashMap<>();
        for (SpeciesCatalog.Voice voice : pool) uses.put(voice.id(), 0);
        if (others != null) for (ConversationStore.Profile other : others) {
            if (other == null || other.voiceId == null || (self != null && self.equals(other.id))) continue;
            uses.computeIfPresent(other.voiceId, (id, n) -> n + 1);
        }
        long salt = self == null ? 0 : self.getLeastSignificantBits();
        return pool.stream().min(Comparator.<SpeciesCatalog.Voice>comparingInt(voice -> uses.get(voice.id()))
                .thenComparingLong(voice -> mix(salt ^ voice.id().hashCode()))).map(SpeciesCatalog.Voice::id).orElse("");
    }

    /** Whether this voice already suits a villager of this sex and age. */
    static boolean fits(String voiceId, Gender gender, VoiceAge age) {
        return voiceId != null && SpeciesCatalog.villagerPool(age, gender).stream().anyMatch(voice -> voice.id().equals(voiceId));
    }

    private static boolean child(String stage) {
        return stage != null && !stage.isBlank() && !ChildGrowth.Stage.ADULT.name().equalsIgnoreCase(stage);
    }
    private static long mix(long x) {
        x ^= x >>> 33; x *= 0xff51afd7ed558ccdL; x ^= x >>> 33; x *= 0xc4ceb9fe1a85ec53L; return x ^ (x >>> 33);
    }
}
