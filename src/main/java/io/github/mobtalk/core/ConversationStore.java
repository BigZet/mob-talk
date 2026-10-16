package io.github.mobtalk.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** World-local profiles and short conversation history. Used only by the conversation worker. */
final class ConversationStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private State state;
    private final MobTalkConfig config;

    ConversationStore(Path worldDirectory, MobTalkConfig config) throws IOException {
        this.file = worldDirectory.resolve("data/mobtalk/conversations.json");
        this.config = config;
        state = new State();
        if (Files.exists(file)) {
            try {
                state = JSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), State.class);
                if (state == null || state.profiles == null) throw new IllegalArgumentException();
                for (var entry : state.profiles.entrySet()) {
                    java.util.UUID.fromString(entry.getKey());
                    Profile profile = entry.getValue();
                    if (profile == null || profile.personality == null || profile.voiceId == null || profile.turns == null)
                        throw new IllegalArgumentException();
                    profile.memory = TextBounds.characters(profile.memory, memoryLimit());
                    while (profile.turns.size() > historyLimit()) profile.turns.removeFirst();
                }
            } catch (IOException | RuntimeException failure) {
                throw new IOException("Не удалось прочитать память Mob Talk в папке мира.");
            }
        }
    }

    Profile profile(MobSnapshot snapshot) throws IOException {
        String id = snapshot.id().toString();
        Profile profile = state.profiles.get(id);
        if (profile != null) return profile;
        profile = new Profile();
        profile.type = TextBounds.characters(snapshot.type(), 100);
        profile.name = TextBounds.characters(snapshot.name(), 120);
        profile.personality = personality(profile.type, snapshot.id().hashCode());
        profile.voiceId = voice(profile.type, config.defaultVoice);
        state.profiles.put(id, profile);
        save();
        return profile;
    }

    void remember(Profile profile, String user, String reply, String memory) throws IOException {
        profile.turns.add(new Turn(user, reply));
        while (profile.turns.size() > historyLimit()) profile.turns.removeFirst();
        profile.memory = TextBounds.characters(memory, memoryLimit());
        save();
    }

    private int historyLimit() { return Math.max(1, Math.min(6, config.historyTurns)); }
    private int memoryLimit() { return Math.max(1, Math.min(1200, config.maxMemoryChars)); }

    private void save() throws IOException {
        Path temporary = file.resolveSibling("conversations.json.tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temporary, JSON.toJson(state), StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException failure) {
            throw new IOException("Не удалось сохранить память Mob Talk в папке мира.");
        }
    }

    private static String voice(String type, String defaultVoice) {
        return switch (bareType(type)) {
            case "zombie", "husk", "drowned", "zombie_villager" -> "jtE6dbPUTt2kchN89Uej";
            case "skeleton", "stray", "wither_skeleton", "bogged" -> "NxGA8X3YhTrnf3TRQf6Q";
            case "cow", "mooshroom" -> "7S3KNdLDL7aRgBVRQb1z";
            case "sheep" -> "i4CzbCVWoqvD0P1QJCUL";
            case "villager", "wandering_trader" -> "FF7KdobWPaiR0vkcALHF";
            case "creeper", "parrot" -> defaultVoice == null || defaultVoice.isBlank() ? "rSfuQoQ3FY8SVKeraMAp" : defaultVoice;
            default -> "qNkzaJoHLLdpvgh5tISm";
        };
    }

    private static String personality(String type, int seed) {
        String base = switch (bareType(type)) {
            case "zombie", "husk", "drowned", "zombie_villager" -> "Немного сонный зомби: простые фразы, ворчание о голоде и свете.";
            case "skeleton", "stray", "wither_skeleton", "bogged" -> "Сухой, язвительный скелет с коротким мрачным юмором.";
            case "cow", "mooshroom" -> "Спокойная добродушная корова, любит траву и безопасный луг.";
            case "sheep" -> "Мягкая осторожная овца, ценит стадо и свою шерсть.";
            case "creeper" -> "Тихий напряжённый крипер, неловко шутит о личном пространстве.";
            case "parrot" -> "Любопытный разговорчивый попугай, любит звуки и компанию.";
            case "villager", "wandering_trader" -> "Практичный деревенский житель, думает о доме и обмене.";
            default -> "Любопытный обитатель Minecraft, говорит от лица своего вида.";
        };
        String[] traits = { "Особенность: застенчивый.", "Особенность: самоуверенный.", "Особенность: мечтатель.", "Особенность: ворчун.", "Особенность: дружелюбный.", "Особенность: любитель коротких шуток." };
        return base + " " + traits[Math.floorMod(seed, traits.length)];
    }

    private static String bareType(String type) {
        int colon = type.indexOf(':');
        return colon < 0 ? type : type.substring(colon + 1);
    }

    static final class Profile {
        String type;
        String name;
        String personality;
        String voiceId;
        String memory = "";
        List<Turn> turns = new ArrayList<>();
    }
    record Turn(String user, String assistant) { }
    private static final class State {
        int version = 1;
        Map<String, Profile> profiles = new LinkedHashMap<>();
    }
}
