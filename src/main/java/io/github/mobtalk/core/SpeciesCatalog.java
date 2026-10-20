package io.github.mobtalk.core;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bundled species personalities and public-library metadata, without any claim of paid TTS validation. */
public final class SpeciesCatalog {
    public record Voice(String id, String name, String description, String previewUrl, String language, String gender, String assignedSpecies) {
        public Voice(String id, String name, String description, String previewUrl, String language) { this(id, name, description, previewUrl, language, "", ""); }
        /** The library's sex for the voice, or null when unknown. */
        public Gender sex() { return gender == null ? null : "female".equalsIgnoreCase(gender) ? Gender.FEMALE : "male".equalsIgnoreCase(gender) ? Gender.MALE : null; }
    }
    public record Archetype(String name, String prompt, Voice voice) { }
    /** quirks: speech habits and trademarks; desire: what this creature wants from life; secret: something it hides and
     * lets slip only to a friend. All three are optional text and may be blank for species not yet written up. */
    public record Species(String id, String name, String prompt, String interests, String fears,
                          boolean hostile, List<Archetype> variants, String quirks, String desire, String secret) {
        public Species {
            variants = List.copyOf(variants);
            quirks = quirks == null ? "" : quirks; desire = desire == null ? "" : desire; secret = secret == null ? "" : secret;
        }
        public Species(String id, String name, String prompt, String interests, String fears, boolean hostile, List<Archetype> variants) {
            this(id, name, prompt, interests, fears, hostile, variants, "", "", "");
        }
    }

    /** Blank entries keep bundled values. Array shape is validated when preferences are saved. */
    public static class Override {
        public String prompt = "";
        public String[] archetypePrompts = new String[]{"", "", ""};
        public String[] voiceIds = new String[]{"", "", ""};
    }

    private static final Voice UNASSIGNED = new Voice("", "Не назначен",
            "Для этого варианта ещё не подобран публичный ID; генерация не проверена.", "", "ru");

    private static final Map<String, Voice> VOICES;
    private static final Map<String, Species> SPECIES;
    private static final List<Voice> RESERVES;
    private static final Map<String, Voice> SPECIES_RESERVES;
    private static final Map<VoiceAge, List<Voice>> VILLAGER_POOL;
    private static final List<Species> ALL;

    static {
        VoiceDocument voices = read("voices.json", VoiceDocument.class);
        Map<String, Voice> voiceMap = new LinkedHashMap<>();
        if (voices.voices == null) throw invalid("Missing voice metadata");
        for (Voice voice : voices.voices) {
            if (voice == null || blank(voice.id()) || blank(voice.name()) || blank(voice.description())
                    || blank(voice.previewUrl()) || blank(voice.language())
                    || voiceMap.putIfAbsent(voice.id(), voice) != null)
                throw invalid("Incomplete or duplicate voice metadata");
        }
        VOICES = Collections.unmodifiableMap(voiceMap);
        var reserves = new ArrayList<Voice>();
        if (voices.reserveIds != null) for (String id : voices.reserveIds) {
            Voice voice = voiceMap.get(id);
            if (voice == null || reserves.contains(voice)) throw invalid("Invalid reserve voice");
            reserves.add(voice);
        }
        RESERVES = List.copyOf(reserves);
        SpeciesDocument data = read("species.json", SpeciesDocument.class);
        if (data.species == null || data.species.size() != 82) throw invalid("Expected 82 vanilla mob species");
        Map<String, Species> speciesMap = new LinkedHashMap<>();
        var assignedVoices = new LinkedHashSet<String>();
        for (SpeciesData row : data.species) {
            if (row == null || blank(row.id) || blank(row.name) || blank(row.prompt)
                    || blank(row.interests) || blank(row.fears) || row.variants == null || row.variants.size() != 3)
                throw invalid("Incomplete species profile");
            var variants = new ArrayList<Archetype>();
            var names = new LinkedHashSet<String>();
            for (ArchetypeData variant : row.variants) {
                if (variant == null || blank(variant.name) || blank(variant.prompt) || !names.add(variant.name))
                    throw invalid("Incomplete species archetype: " + row.id);
                Voice voice = blank(variant.voiceId) ? UNASSIGNED : voiceMap.get(variant.voiceId);
                if (voice == null || (!blank(voice.id()) && !assignedVoices.add(voice.id())) || reserves.contains(voice))
                    throw invalid("Missing or reused species voice: " + row.id);
                variants.add(new Archetype(variant.name, variant.prompt, voice));
            }
            String id = normalized(row.id);
            Species species = new Species(id, row.name, row.prompt, row.interests, row.fears, row.hostile, variants, row.quirks, row.desire, row.secret);
            if (speciesMap.putIfAbsent(id, species) != null) throw invalid("Duplicate species: " + id);
        }
        SPECIES = Collections.unmodifiableMap(speciesMap);
        var speciesReserves = new LinkedHashMap<String, Voice>();
        var reservedIds = new LinkedHashSet<String>();
        if (voices.speciesReserves != null) for (var entry : voices.speciesReserves.entrySet()) {
            String type = normalized(entry.getKey());
            Voice reserve = voiceMap.get(entry.getValue());
            if (!speciesMap.containsKey(type) || reserve == null || !reserves.contains(reserve)
                    || assignedVoices.contains(reserve.id()) || !reservedIds.add(reserve.id())
                    || speciesReserves.putIfAbsent(type, reserve) != null)
                throw invalid("Invalid species reserve: " + type);
        }
        SPECIES_RESERVES = Collections.unmodifiableMap(speciesReserves);
        // Villagers' own voices: no species archetype or reserve holds them, and each has a known sex.
        var pool = new java.util.EnumMap<VoiceAge, List<Voice>>(VoiceAge.class);
        var pooled = new LinkedHashSet<String>();
        for (VoiceAge age : VoiceAge.values()) {
            var list = new ArrayList<Voice>();
            List<String> ids = voices.villagerPool == null ? null : voices.villagerPool.get(age.key);
            if (ids != null) for (String id : ids) {
                Voice voice = voiceMap.get(id);
                if (voice == null || voice.sex() == null || reserves.contains(voice) || assignedVoices.contains(id) || !pooled.add(id))
                    throw invalid("Invalid villager pool voice: " + id);
                list.add(voice);
            }
            pool.put(age, List.copyOf(list));
        }
        VILLAGER_POOL = Collections.unmodifiableMap(pool);
        ALL = List.copyOf(speciesMap.values());
    }

    private SpeciesCatalog() { }

    public static List<Species> all() { return ALL; }

    public static Species get(String type) {
        String id = normalized(type);
        Species known = SPECIES.get(id);
        if (known != null) return known;
        return new Species(id, id.substring(id.indexOf(':') + 1).replace('_', ' '),
                "Ты моб неизвестного вида — пришелец из какого-то мода в ванильном мире. Кто ты такой, видно только из снимка: "
                        + "своё здоровье, место, соседей и игрока. Ты сам слегка в шоке от этого мира, но держишься с гонором "
                        + "и судишь обо всём по тому, что видишь прямо сейчас.",
                "Безопасное место, понятные соседи, уважение.",
                "Незнакомые угрозы, резкие удары, лава.", false,
                List.of(new Archetype("Осторожный", "Ты подозрительный и нервный: сначала выясняешь, чего от тебя хотят, "
                                + "и огрызаешься на резкие движения. Примеры реплик: «Стоп. Ты кто и зачем с мечом?» «Ага, щас, разбежался.»", UNASSIGNED),
                        new Archetype("Любопытный", "Ты восторженный турист в чужом мире: всё трогаешь, обо всём спрашиваешь "
                                + "и комментируешь вслух. Примеры реплик: «Это что, квадратное солнце? Серьёзно?» «Слушай, а алмазы правда вниз копать?»", UNASSIGNED),
                        new Archetype("Практичный", "Ты деловой и прямой: говоришь коротко, считаешь выгоду и не любишь пустой болтовни. "
                                + "Примеры реплик: «Конкретнее. Что надо?» «Без брони ночью гуляешь — твои проблемы.»", UNASSIGNED)));
    }

    /** Known IDs return their real metadata; unknown user IDs remain explicitly unverified. */
    public static Voice voice(String id) {
        if (blank(id)) return UNASSIGNED;
        String clean = id.trim();
        Voice known = VOICES.get(clean);
        return known != null ? known : new Voice(clean, "Пользовательский голос",
                "Пользовательский ID; библиотечные метаданные и генерация не проверены.", "", "ru");
    }

    /** A voice of the given sex for a creature of this species, deterministic for its UUID: first the species' own voices of that sex
     * (its archetypes), then any Russian library voice of that sex that no species reserve holds; UNASSIGNED when the library has none. */
    public static Voice voiceFor(String type, Gender gender, java.util.UUID seed) {
        if (gender == null) return UNASSIGNED;
        String id = normalized(type);
        List<Voice> own = new ArrayList<>(), any = new ArrayList<>();
        for (Voice voice : VOICES.values()) {
            if (voice.sex() != gender || !"ru".equalsIgnoreCase(voice.language()) || RESERVES.contains(voice)) continue;
            if (id.equals(voice.assignedSpecies() == null ? "" : normalized(voice.assignedSpecies()))) own.add(voice); else any.add(voice);
        }
        List<Voice> pool = own.isEmpty() ? any : own;
        if (pool.isEmpty()) return UNASSIGNED;
        long hash = seed == null ? 0 : seed.getMostSignificantBits() ^ Long.rotateLeft(seed.getLeastSignificantBits(), 23);
        return pool.get((int) Math.floorMod(hash, (long) pool.size()));
    }

    /** The villagers' own voices of this age and sex, in catalog order; empty when the catalog has none. */
    public static List<Voice> villagerPool(VoiceAge age, Gender gender) {
        if (age == null || gender == null) return List.of();
        return VILLAGER_POOL.getOrDefault(age, List.of()).stream().filter(voice -> voice.sex() == gender).toList();
    }

    /** Whether the catalog knows this ID; anything else is a voice the player typed in. */
    public static boolean known(String id) { return !blank(id) && VOICES.containsKey(id.trim()); }

    /** A dedicated, disjoint reserve for this species; never borrow another species' voice. */
    public static Voice reserve(String type) {
        return SPECIES_RESERVES.getOrDefault(normalized(type), UNASSIGNED);
    }

    public static Species effective(String type, MobTalkConfig config) {
        Species base = get(type);
        Override override = findOverride(base.id(), config);
        if (override == null) return base;
        var variants = new ArrayList<Archetype>();
        for (int slot = 0; slot < 3; slot++) {
            Archetype original = base.variants().get(slot);
            String voiceId = entry(override.voiceIds, slot);
            variants.add(new Archetype(original.name(), orDefault(entry(override.archetypePrompts, slot), original.prompt()),
                    blank(voiceId) ? original.voice() : voice(voiceId)));
        }
        return new Species(base.id(), base.name(), orDefault(override.prompt, base.prompt()),
                base.interests(), base.fears(), base.hostile(), variants, base.quirks(), base.desire(), base.secret());
    }

    public static void validateOverrides(MobTalkConfig config) throws IOException {
        if (config == null || config.speciesOverrides == null) return;
        var normalizedKeys = new LinkedHashSet<String>();
        for (var entry : config.speciesOverrides.entrySet()) {
            if (blank(entry.getKey()) || !entry.getKey().matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+"))
                throw new IOException("Некорректный ID вида в настройках Mob Talk.");
            String id = normalized(entry.getKey());
            if (!normalizedKeys.add(id)) throw new IOException("Два изменения одного вида: " + id);
            Override value = entry.getValue();
            if (value == null || value.archetypePrompts == null || value.voiceIds == null
                    || value.archetypePrompts.length != 3 || value.voiceIds.length != 3)
                throw new IOException("Для " + id + " нужны ровно три характера и три голоса.");
            if (value.prompt != null && value.prompt.length() > 8000)
                throw new IOException("Слишком длинный характер вида " + id);
            for (String prompt : value.archetypePrompts)
                if (prompt != null && prompt.length() > 4000) throw new IOException("Слишком длинный вариант " + id);
            for (String voiceId : value.voiceIds)
                if (!blank(voiceId) && !voiceId.trim().matches("[A-Za-z0-9_-]{1,100}"))
                    throw new IOException("Некорректный ID голоса для " + id);
        }
        var owners = new LinkedHashMap<String, String>();
        var checkedTypes = new LinkedHashSet<>(SPECIES.keySet());
        checkedTypes.addAll(normalizedKeys);
        for (String type : checkedTypes) {
            Species species = effective(type, config);
            for (Archetype variant : species.variants()) {
                if (blank(variant.voice().id())) continue;
                if (SPECIES_RESERVES.values().stream().anyMatch(reserve -> reserve.id().equals(variant.voice().id())))
                    throw new IOException("Голос " + variant.voice().id() + " уже выделен как отдельный видовой резерв.");
                String owner = owners.putIfAbsent(variant.voice().id(), type + "/" + variant.name());
                if (owner != null)
                    throw new IOException("Голос " + variant.voice().id() + " повторяется у " + owner + " и " + type);
            }
        }
    }

    /** Explicit final casting gate; incomplete voices never prevent text conversations or menu editing. */
    public static void validateCompleteCatalog() throws IOException {
        var assigned = new LinkedHashSet<String>();
        for (Species species : ALL) for (Archetype variant : species.variants()) {
            Voice voice = variant.voice();
            if (blank(voice.id()))
                throw new IOException("Голос ещё не назначен: " + species.id() + "/" + variant.name());
            if (!assigned.add(voice.id()) || !"ru".equals(voice.language())
                    || !voice.previewUrl().startsWith("https://"))
                throw new IOException("Неполные или повторные библиотечные метаданные голоса " + voice.id());
        }
        if (assigned.size() != 246 || SPECIES_RESERVES.size() != 82
                || SPECIES_RESERVES.values().stream().anyMatch(voice -> assigned.contains(voice.id())))
            throw new IOException("Нужны 246 уникальных голосов и 82 отдельных видовых резерва.");
    }

    private static Override findOverride(String id, MobTalkConfig config) {
        if (config == null || config.speciesOverrides == null) return null;
        Override exact = config.speciesOverrides.get(id);
        if (exact != null) return exact;
        for (var entry : config.speciesOverrides.entrySet())
            if (id.equals(normalized(entry.getKey()))) return entry.getValue();
        return null;
    }

    private static String normalized(String type) {
        String clean = blank(type) ? "minecraft:unknown" : type.trim().toLowerCase(Locale.ROOT);
        return clean.indexOf(':') >= 0 ? clean : "minecraft:" + clean;
    }

    private static String entry(String[] array, int slot) {
        return array != null && slot < array.length ? array[slot] : "";
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String orDefault(String value, String fallback) { return blank(value) ? fallback : value.trim(); }
    private static IllegalStateException invalid(String message) { return new IllegalStateException("Mob Talk catalog: " + message); }

    private static <T> T read(String file, Class<T> type) {
        try (var input = SpeciesCatalog.class.getResourceAsStream("/assets/mobtalk/" + file)) {
            if (input == null) throw invalid("Missing " + file);
            T data = new Gson().fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), type);
            if (data == null) throw invalid("Empty " + file);
            return data;
        } catch (IOException | JsonParseException invalid) {
            throw new IllegalStateException("Mob Talk catalog: cannot read " + file, invalid);
        }
    }

    private static final class VoiceDocument { List<Voice> voices; List<String> reserveIds; Map<String, String> speciesReserves; Map<String, List<String>> villagerPool; }
    private static final class SpeciesDocument { List<SpeciesData> species; }
    private static final class SpeciesData {
        String id, name, prompt, interests, fears, quirks, desire, secret;
        boolean hostile;
        List<ArchetypeData> variants;
    }
    private static final class ArchetypeData { String name, prompt, voiceId; }
}
