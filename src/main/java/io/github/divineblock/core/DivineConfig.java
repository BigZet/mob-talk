package io.github.divineblock.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Settings from config/divineblock.json. Never holds keys: those live in {@link ApiKeys}. Every value is clamped on load. */
public final class DivineConfig {
    public enum Personality {
        MERCIFUL("милостивый", 1, 0), CAPRICIOUS("капризный", 0, 1), OLD_TESTAMENT("ветхозаветный", -1, 1), TRICKSTER("трикстер", 0, 0);
        public final String label;
        /** A standing favor bonus and a standing force bonus: a named reason in every verdict. */
        public final int favor, force;
        Personality(String label, int favor, int force) { this.label = label; this.favor = favor; this.force = force; }
    }
    public enum Provider { OPENROUTER, VENICE }

    public Personality personality = Personality.CAPRICIOUS;
    /** The strongest act the god may perform, 0..4. */
    public int maxTier = 4;
    public boolean allowWither = false;
    /** Ender dragons outside the End (one, or up to five in great fury). */
    public boolean allowDragon = true;
    /** Destructive acts (fire, meteors) spare blocks entirely; off lets meteors leave craters outside the protected radius. */
    public boolean protectBlocks = false;
    /** No block changes within this many blocks of the player's bed (the altar itself is always spared). */
    public int protectRadius = 16;
    /** The weight of the god's named whims (storm, moon); 0 makes him fully predictable. */
    public int whimWeight = 1;
    public int cooldownSeconds = 20;
    public boolean useJev = true;
    public Provider provider = Provider.OPENROUTER;
    public String chatModel = "openai/gpt-6-luna";
    public String veniceModel = "deepseek-v4-1-flash";
    public int speechTimeoutSeconds = 20;
    /** The god speaks aloud (OpenRouter text to speech); off leaves subtitles only. */
    public boolean voice = true;
    /** The voice rings as in a temple: a big reverb and a quiet echo ({@link VoiceEffects}). */
    public boolean voiceTemple = true;
    /** The reverb and echo knobs, 0..100 (echo time in milliseconds); the temple preset by default. */
    public int voiceRoomSize = VoiceEffects.Preset.CHAPEL.settings.roomSize(), voiceReverb = VoiceEffects.Preset.CHAPEL.settings.reverbLevel(),
            voiceEchoMs = VoiceEffects.Preset.CHAPEL.settings.echoMs(), voiceEchoLevel = VoiceEffects.Preset.CHAPEL.settings.echoLevel(),
            voiceEchoRepeats = VoiceEffects.Preset.CHAPEL.settings.echoRepeats();

    /** The reverb and echo as {@link VoiceEffects} takes them (clamped there). */
    public VoiceEffects.Settings voiceEffects() { return new VoiceEffects.Settings(voiceRoomSize, voiceReverb, voiceEchoMs, voiceEchoLevel, voiceEchoRepeats); }
    public void voiceEffects(VoiceEffects.Settings settings) {
        voiceRoomSize = settings.roomSize(); voiceReverb = settings.reverbLevel(); voiceEchoMs = settings.echoMs();
        voiceEchoLevel = settings.echoLevel(); voiceEchoRepeats = settings.echoRepeats();
    }
    /** Church chants (quiet singing near the altar, music under a blessing or a punishment, always below the god's voice):
     * percent of their already quiet default, 0..200; 0 switches them off. */
    public int chantVolume = 100;
    /** The god's drawn effects (own particles, rays of light, bolts, meteors): 0 off (vanilla particles), 1 low, 2 medium, 3 high. */
    public int effects = 3;
    /** The camera shakes under the blows of wrath. */
    public boolean cameraShake = true;
    public String voiceId = GodVoice.VOICES[0][0];
    public String ttsModel = "elevenlabs/eleven-flash-v2.5";
    /** Prayers by voice: the local Whisper helper (shared with Mob Talk) and the microphone; "" is the system default. */
    public String whisperUrl = "http://127.0.0.1:63919";
    public boolean whisperAutoStart = true;
    public String microphoneName = "";
    /** Each punishment in a row, unforgiven, makes the next one a rung worse. */
    public boolean escalation = true;
    /** Curses that stay (blight, Cain, darkness, blood water, the flood) for {@link #curseMinutes}; off makes them pass in three minutes. */
    public boolean lingeringCurses = true;
    /** How many real minutes a lingering curse lasts, 1..20 ({@link CurseTerm}: no curse ever lasts longer). It replaced
     * {@code curseDays}: an old file's days are ignored and the default term applies. */
    public int curseMinutes = CurseTerm.DEFAULT_MINUTES;
    /** The eternal ban (group К6): off by default. When off, the god never bans and a world with a ban lets the player in. */
    public boolean allowEternalBan = false;
    /** Copy the world into saves/ before the ban falls. */
    public boolean banBackup = true;
    /** Every deed is told to Mob Talk's villages ({@link VillageNews}), when Mob Talk is there: they talk, fear and blame. */
    public boolean tellVillages = true;
    /** The god names the sinner aloud: the villages know whose sins brought the punishment even without seeing it fall. */
    public boolean nameSinner = true;
    /** Punishments that change the land (craters, rifts, floods…) may fall — at the sinner's home, never by a church; off
     * puts another act of the same group in their place. */
    public boolean landscapeWrath = true;
    /** The punishment roulette ({@link Roulette}): three reels spin before a punishment falls and stop on its symbol. On a
     * server the switch, the length, who sees it, the tease and the tails are the admin's; showing it and its sound are the player's. */
    public boolean rouletteEnabled = true;
    public Roulette.Mode rouletteMode = Roulette.Mode.NORMAL;
    public Roulette.Audience rouletteAudience = Roulette.Audience.RADIUS;
    /** How far others see a sinner's roulette («Кара для …»), 16..256 blocks. */
    public int rouletteRadius = 64;
    /** «Почти 777» for К3–К4: the third reel crawls past the seven, with this chance in percent. */
    public boolean rouletteTease = true;
    public int rouletteTeaseChance = 25;
    /** The roulette before a punishment's tail (the second wave, an aftershock). */
    public Roulette.Tails rouletteTails = Roulette.Tails.MINI;
    /** «Пощажён случаем»: the chance in percent (0..25) that the third reel slips onto a dove and the punishment does not fall
     * (never for the eternal ban). */
    public int rouletteMercyChance = 5;
    /** Punishments sent from the web admin spin the roulette too. */
    public boolean rouletteAdmin = true;
    /** Personal: draw the roulette on this screen (off leaves the chat line), its sound in percent 0..200, and the golden flash
     * with the camera shaking on 777. */
    public boolean rouletteShow = true;
    public int rouletteVolume = 100;
    public boolean rouletteJackpotFx = true;
    /** Personal: how much show the roulette puts on (rays, chase lights, sparks, coins, the big words), 0 plain .. 3 full. */
    public int rouletteFx = 3;
    /** Ids of acts the player switched off. */
    public List<String> disabled = new ArrayList<>();
    /** Orthodox churches with an altar inside appear in newly generated chunks; off generates none (old ones stay). */
    public boolean churches = true;
    /** How rare churches are: one per this many chunks square at most, 32..256 (the vanilla village is 34). */
    public int churchSpacing = 64;
    /** Which church new chunks get: "" mixes all three, else STONE, WOOD or SLAVIC (KAIZEN87's) only — to compare them. */
    public String churchVariant = "";
    /** A new world starts at the Slavic Church: its world spawn is moved to the path before the porch (ChurchSpawn). */
    public boolean spawnAtChurch = true;
    /** The church guardian rises from the seal under a church's altar when a mortal comes near (off by default: the owner wants
     * no guardian inside a church; the church at the world spawn never has one). */
    public boolean churchGuardians = false;
    public boolean debug = false;
    /** Sacred bosses ({@code io.github.divineblock.boss}): ids switched off (a buffed vanilla mob comes instead), strength in
     * percent (50..300) and whether their ultimates may break glass, put out lights and set fires. */
    public List<String> disabledBosses = new ArrayList<>();
    public int bossDifficulty = 100;
    public boolean bossBlockDamage = true;
    /** «Бог на стриме» ({@link StreamOverlay}): the OBS overlay may read the god at all; with the main reasons of a verdict; the
     * mercy of every soul online, not only the streamer's; the count of sins; and the ids of the punishments and mercies it
     * names only «Тайное деяние». */
    public boolean overlayEnabled = true, overlayReasons = true, overlayOthersMercy = true, overlaySins = true;
    public List<String> overlayHidden = new ArrayList<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static DivineConfig load(Path file) {
        DivineConfig config = null;
        try { if (Files.isRegularFile(file)) config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), DivineConfig.class); }
        catch (IOException | RuntimeException broken) { config = null; }
        return (config == null ? new DivineConfig() : config).sanitized();
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(sanitized()), StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    public DivineConfig sanitized() {
        if (personality == null) personality = Personality.CAPRICIOUS;
        if (provider == null) provider = Provider.OPENROUTER;
        maxTier = clamp(maxTier, 0, 4);
        effects = clamp(effects, 0, 3);
        protectRadius = clamp(protectRadius, 0, 256);
        whimWeight = clamp(whimWeight, 0, 3);
        cooldownSeconds = clamp(cooldownSeconds, 0, 600);
        speechTimeoutSeconds = clamp(speechTimeoutSeconds, 3, 60);
        if (chatModel == null || chatModel.isBlank()) chatModel = new DivineConfig().chatModel;
        if (veniceModel == null || veniceModel.isBlank()) veniceModel = new DivineConfig().veniceModel;
        if (disabled == null) disabled = new ArrayList<>();
        if (!GodVoice.validVoiceId(voiceId)) voiceId = GodVoice.VOICES[0][0];
        if (ttsModel == null || ttsModel.isBlank()) ttsModel = new DivineConfig().ttsModel;
        try { LocalWhisper.local(whisperUrl); } catch (IllegalArgumentException invalid) { whisperUrl = new DivineConfig().whisperUrl; }
        if (microphoneName == null) microphoneName = "";
        chantVolume = clamp(chantVolume, 0, 200);
        if (disabledBosses == null) disabledBosses = new ArrayList<>();
        if (overlayHidden == null) overlayHidden = new ArrayList<>();
        bossDifficulty = clamp(bossDifficulty, 50, 300);
        churchSpacing = clamp(churchSpacing, 32, 256);
        if (churchVariant == null || !java.util.List.of("", "STONE", "WOOD", "SLAVIC").contains(churchVariant)) churchVariant = "";
        curseMinutes = CurseTerm.minutes(curseMinutes);
        if (rouletteMode == null || rouletteMode == Roulette.Mode.MINI) rouletteMode = Roulette.Mode.NORMAL;
        if (rouletteAudience == null) rouletteAudience = Roulette.Audience.RADIUS;
        if (rouletteTails == null) rouletteTails = Roulette.Tails.MINI;
        rouletteRadius = clamp(rouletteRadius, 16, 256);
        rouletteTeaseChance = clamp(rouletteTeaseChance, 0, 100);
        rouletteMercyChance = clamp(rouletteMercyChance, 0, 25);
        rouletteVolume = clamp(rouletteVolume, 0, 200);
        rouletteFx = clamp(rouletteFx, 0, 3);
        voiceEffects(voiceEffects());
        return this;
    }

    public DivineConfig copy() { return GSON.fromJson(GSON.toJson(this), DivineConfig.class).sanitized(); }

    /** Whether the settings let the god perform this act at all. */
    public boolean allows(Miracle miracle) {
        if (miracle == null || disabled.contains(miracle.id())) return false;
        if (miracle == Miracle.WITHER && !allowWither) return false;
        if (miracle == Miracle.DRAGON && !allowDragon) return false;
        if (miracle == Miracle.ETERNAL_BAN && !allowEternalBan) return false;
        if (miracle.landscape() && !landscapeWrath) return false;
        return miracle.minTier <= maxTier;
    }

    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
}
