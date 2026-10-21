package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.Verdict;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

/** The eternal ban, the last of the god's punishments. Only when the player switched it on in the settings: the god copies
 * the world aside, then throws the mortal out with his sentence on the screen, and the world refuses him from then on
 * (on a dedicated server it is a real ban). Three ways back, so a mistake never locks a world for good: switch the ban off
 * in the settings, put {@code config/divineblock-unban.txt} next to the config, or start the game with
 * {@code -Ddivineblock.unban=1}; and {@code /divineblock unban} for an operator. */
public final class Banishment {
    /** One sentence, saved with the world. Plain data for Gson. */
    public static final class Ban {
        public String name = "", reason = "", speech = "", backup = "";
        public int blasphemies;
        public long day, at;
    }
    public static final class Book { public Map<String, Ban> souls = new HashMap<>(); }

    static final String UNBAN_PROPERTY = "divineblock.unban", UNBAN_FILE = "divineblock-unban.txt";
    /** How long the mortal hangs in the air while the world is copied before the sentence: at least this, in ticks. */
    static final int SENTENCE_TICKS = 20 * 6;

    private Banishment() { }

    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> { if (unbanRequested()) unban(server, null, "ключ восстановления при запуске"); });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            ServerPlayerEntity player = handler.player;
            Ban ban = DivineState.of(server).banned.get(player.getUuidAsString());
            if (ban == null) return;
            if (!DivineBlockMod.config().allowEternalBan) {
                DivineBlockMod.LOG.info("Божественный блок: {} изгнан, но изгнание выключено в настройках — впускаю", player.getName().getString());
                return;
            }
            DivineBlockMod.LOG.info("Божественный блок: мир не впускает изгнанного {}", player.getName().getString());
            player.networkHandler.disconnect(refusal(player.getName().getString(), ban));
        }));
    }

    /** Whether the player asked to lift every ban in the world he opens (a JVM flag or a file in the config folder). */
    static boolean unbanRequested() {
        String flag = System.getProperty(UNBAN_PROPERTY, "");
        if (flag.equals("1") || flag.equalsIgnoreCase("true")) return true;
        return Files.exists(DivineBlockMod.configDirectory().resolve(UNBAN_FILE));
    }

    /** Lifts the ban of one player (null: of all) in this world. Returns how many were lifted. */
    public static int unban(MinecraftServer server, UUID player, String why) {
        DivineState state = DivineState.of(server);
        int lifted = 0;
        for (String id : new java.util.ArrayList<>(state.banned.keySet())) {
            if (player != null && !id.equals(player.toString())) continue;
            Ban ban = state.banned.remove(id);
            lifted++;
            if (server.isDedicated()) {
                var profile = new com.mojang.authlib.GameProfile(UUID.fromString(id), ban.name);
                server.getPlayerManager().getUserBanList().remove(profile);
            }
            DivineBlockMod.LOG.info("Божественный блок: изгнание {} снято ({})", ban.name, why);
        }
        if (lifted > 0) state.markDirty();
        // The file is a one-time key: once used, it is gone, so the next ban is real again.
        Path file = DivineBlockMod.configDirectory().resolve(UNBAN_FILE);
        try { if (Files.deleteIfExists(file)) DivineBlockMod.LOG.info("Божественный блок: файл {} использован и удалён", UNBAN_FILE); }
        catch (IOException failure) { DivineBlockMod.LOG.warn("Божественный блок: не удалось удалить {}", UNBAN_FILE, failure); }
        return lifted;
    }

    public static boolean banned(MinecraftServer server, UUID player) { return DivineState.of(server).banned.containsKey(player.toString()); }

    /** The act: returns at once; the mortal hangs in judgement while the world is copied, then is cast out. */
    static String banish(ServerWorld world, ServerPlayerEntity player, BlockPos altar, DivineConfig config) {
        if (!config.allowEternalBan) return null;
        MinecraftServer server = world.getServer();
        if (banned(server, player.getUuid())) return null;
        Verdict last = DivineState.of(server).ledger.soul(player.getUuid()).lastVerdict();
        Ban ban = new Ban();
        ban.name = player.getName().getString();
        ban.blasphemies = last == null ? 0 : last.blasphemiesBefore + 1;
        ban.reason = last == null || last.mainReasons(1).isEmpty() ? "хула на бога" : last.mainReasons(1).get(0).text();
        ban.speech = last == null || last.speech == null ? "" : last.speech;
        ban.day = DeedWatcher.day(server);

        // The sentence is read: the mortal rises a little, the bells toll, the light goes out of the world around him.
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, SENTENCE_TICKS + 40, 20));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("Врата этого мира закрываются за тобой").formatted(Formatting.RED)));
        player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("Изгнан навеки").formatted(Formatting.DARK_RED, Formatting.BOLD)));
        Miracles.effect(player, StatusEffects.LEVITATION, SENTENCE_TICKS + 200, 0);
        Miracles.effect(player, StatusEffects.SLOWNESS, SENTENCE_TICKS + 200, 4);
        Miracles.effect(player, StatusEffects.RESISTANCE, SENTENCE_TICKS + 200, 4);
        Scheduler.repeat(20, SENTENCE_TICKS / 20, i -> {
            if (player.isRemoved()) return;
            Miracles.sound(world, player.getBlockPos(), SoundEvents.BLOCK_BELL_USE, 3f, 0.5f);
            world.spawnParticles(ParticleTypes.SOUL, player.getX(), player.getY() + 1, player.getZ(), 30, 1.2, 1.2, 1.2, 0.02);
            world.spawnParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1, player.getZ(), 60, 0.6, 1.2, 0.6, 0.05);
        });
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_WITHER_DEATH, 2f, 0.4f);

        // The sentence hangs over him for real seconds, not ticks: after a long save the server races through missed ticks.
        long due = System.currentTimeMillis() + SENTENCE_TICKS * 50L;
        if (!config.banBackup) { Scheduler.later(SENTENCE_TICKS, () -> castOut(server, player, ban)); return "приговор: вечное изгнание (без копии мира)"; }
        // The copy is the way back if the god was wrong: saved now, copied off the game thread, and no copy — no ban.
        server.saveAll(true, true, true);
        Path worldDir = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        Thread copier = new Thread(() -> {
            try {
                Path backup = backup(worldDir);
                ban.backup = backup.getFileName().toString();
                long wait = due - System.currentTimeMillis();
                if (wait > 0) Thread.sleep(wait);
                server.execute(() -> castOut(server, player, ban));
            } catch (IOException | RuntimeException | InterruptedException failure) {
                DivineBlockMod.LOG.warn("Божественный блок: копия мира не сделана — изгнание отменено", failure);
                server.execute(() -> {
                    if (player.isRemoved()) return;
                    player.removeStatusEffect(StatusEffects.LEVITATION);
                    Miracles.bolt(world, player.getBlockPos(), false);
                    Prayers.hint(player, "Копия мира не удалась — изгнание отменено");
                });
            }
        }, "divineblock-backup");
        copier.setDaemon(true);
        copier.start();
        return "приговор: вечное изгнание";
    }

    private static void castOut(MinecraftServer server, ServerPlayerEntity player, Ban ban) {
        if (player.isRemoved()) return;
        ban.at = System.currentTimeMillis();
        DivineState state = DivineState.of(server);
        state.banned.put(player.getUuidAsString(), ban);
        state.markDirty();
        Text sentence = sentence(ban);
        if (server.isDedicated())
            server.getPlayerManager().getUserBanList().add(new BannedPlayerEntry(player.getGameProfile(), null, "Божественный блок", null, ban.reason));
        DivineBlockMod.LOG.info("Божественный блок: {} изгнан навеки (копия мира: {})", ban.name, ban.backup.isEmpty() ? "нет" : ban.backup);
        Prayers.performed.accept("eternal_ban");
        player.networkHandler.disconnect(sentence);
    }

    /** The screen the mortal is thrown out to. */
    static Text sentence(Ban ban) {
        MutableText out = Text.literal("ПРИГОВОР\n\n").formatted(Formatting.DARK_RED, Formatting.BOLD);
        out.append(Text.literal("Смертный " + ban.name + ".\n").formatted(Formatting.RED));
        int n = ban.blasphemies, tens = n % 100 / 10, last = n % 10;
        String times = tens != 1 && last >= 2 && last <= 4 ? " раза" : " раз";
        out.append(Text.literal((n > 1 ? "Ты хулил меня " + n + times : "Ты хулил меня") + " — " + ban.reason + ".\n")
                .formatted(Formatting.RED));
        out.append(Text.literal("Изгнан из мира сего навеки. Врата его для тебя закрыты.\n\n").formatted(Formatting.GOLD));
        if (!ban.speech.isBlank()) out.append(Text.literal("«" + trim(ban.speech, 240) + "»").formatted(Formatting.GRAY, Formatting.ITALIC));
        return out;
    }

    /** The screen the world shows when the banished one knocks again. */
    static Text refusal(String name, Ban ban) {
        MutableText out = Text.literal("Врата закрыты\n\n").formatted(Formatting.DARK_RED, Formatting.BOLD);
        out.append(Text.literal("Этот мир проклял тебя, " + name + ": " + ban.reason + ".\nИзгнанному нет сюда дороги.\n\n").formatted(Formatting.RED));
        out.append(Text.literal("Снять изгнание: выключить «Вечное изгнание» в настройках мода, положить файл config/" + UNBAN_FILE
                + " или запустить игру с -D" + UNBAN_PROPERTY + "=1" + (ban.backup.isEmpty() ? "" : ". Копия мира до изгнания: " + ban.backup))
                .formatted(Formatting.DARK_GRAY));
        return out;
    }

    /** Copies the world folder next to itself (without its lock) and names the copy so it is told apart in the world list. */
    static Path backup(Path worldDir) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path target = worldDir.resolveSibling(worldDir.getFileName() + "-before-ban-" + stamp);
        try (Stream<Path> files = Files.walk(worldDir)) {
            for (Path source : (Iterable<Path>) files::iterator) {
                if (source.getFileName().toString().equals("session.lock")) continue;
                Path to = target.resolve(worldDir.relativize(source).toString());
                if (Files.isDirectory(source)) Files.createDirectories(to);
                else { Files.createDirectories(to.getParent()); Files.copy(source, to, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
        Path level = target.resolve("level.dat");
        if (Files.isRegularFile(level)) {
            NbtCompound root = NbtIo.readCompressed(level, NbtSizeTracker.ofUnlimitedBytes());
            NbtCompound data = root.getCompound("Data");
            data.putString("LevelName", data.getString("LevelName") + " (до изгнания " + stamp.substring(9, 11) + ":" + stamp.substring(11, 13) + ")");
            root.put("Data", data);
            NbtIo.writeCompressed(root, level);
        }
        DivineBlockMod.LOG.info("Божественный блок: копия мира перед изгнанием — {}", target);
        return target;
    }

    private static String trim(String text, int limit) { return text.length() <= limit ? text : text.substring(0, limit - 1) + "…"; }
}
