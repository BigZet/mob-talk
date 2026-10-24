package io.github.mobtalk.client;

import io.github.mobtalk.audio.PcmWav;
import io.github.mobtalk.core.SelfTestSpeech;
import io.github.mobtalk.core.ServerFeatures;
import io.github.mobtalk.net.MobLabelS2C;
import io.github.mobtalk.net.MobSpeechS2C;
import io.github.mobtalk.net.MobStateS2C;
import io.github.mobtalk.net.SelfTestLine;
import io.github.mobtalk.net.TalkAudioC2S;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Клиент сквозного самотеста сервера ({@code -Dmobtalk.selftest.play.client}, трек G): играет роль по репликам сервера-дирижёра
 * ({@link SelfTestLine}) — выбирает моба, говорит текстом, дарит, открывает сделку, молится у алтаря, пишет команды, выходит — своими
 * настоящими пакетами, и докладывает серверу всё, что получил: возможности, реплики, состояние, подписи, статусы, чат, экраны.
 * Без Whisper и без платной озвучки; снимки — в screenshots. В обычной игре не активен. */
final class SelfTestServer {
    static final String PROPERTY = "mobtalk.selftest.play.client";
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk-selftest");
    private static SelfTestServer active;
    private final MobTalkClient owner;
    /** Последнее доложенное состояние моба: шлём только перемены (сервер обновляет HUD раз в секунду). */
    private final Map<UUID, String> states = new HashMap<>();
    private long ticks, connectedAt = -1, lostAt = -1;
    private boolean quitting;
    private String pendingFind;
    private long findUntil, sneakUntil = -1;

    private SelfTestServer(MobTalkClient owner) { this.owner = owner; }

    static boolean on() { return active != null; }

    static void install(MobTalkClient owner) {
        if (System.getProperty(PROPERTY) == null) return;
        SelfTestServer test = new SelfTestServer(owner);
        active = test;
        ClientPlayNetworking.registerGlobalReceiver(SelfTestLine.ID, (payload, context) -> test.cue(payload.line()));
        // The action bar (Mob Talk's HUD line every second) is not chat: only real chat lines are reported.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) test.send("chat", Formatting.strip(message.getString())); });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> test.send("chat", Formatting.strip(message.getString())));
        // The release jar keeps intermediary class names: the merchant screen is named by type, not by its class.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof MerchantScreen) test.send("screen", "MerchantScreen");
            else if (screen instanceof HandledScreen<?>) test.send("screen", "HandledScreen");
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> { test.connectedAt = test.ticks; test.lostAt = -1; test.log("joined the server"); test.ownNames(); });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { test.lostAt = test.ticks; test.log("disconnected"); });
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> test.tick());
        LOGGER.info("[selftest] server play client armed");
    }

    private void log(String text) { LOGGER.info("[selftest] {}", text); }

    /** The test copies inherit the pack owner's personal names ("Дима"): each client is called by its own nick instead, so
     * villagers can tell the two players apart in memory and rumours. Sent after ClientNet's own settings on join. */
    private void ownNames() {
        var config = owner.config();
        String nick = System.getProperty(PROPERTY, "");
        if (config == null || nick.isBlank()) return;
        config.playerNamesHostile = nick; config.playerNamesNeutral = nick; config.playerNamesClose = nick;
        ClientNet.sendSettings(owner);
        log("personal names set to " + nick);
    }

    /** Сторож: без сервера дольше 10 минут после старта или 15 секунд после разрыва — игра закрывается, слот освобождается. */
    private void tick() {
        ticks++;
        if (quitting) return;
        pollFind();
        if (sneakUntil >= 0 && ticks >= sneakUntil) { owner.client.options.sneakKey.setPressed(false); sneakUntil = -1; log("sneak released"); }
        if (connectedAt < 0 && ticks > 20 * 600) { log("never joined the server in 10 minutes: quitting"); quit(); }
        if (lostAt >= 0 && ticks - lostAt > 20 * 15) { log("lost the server: quitting"); quit(); }
    }
    private void quit() { quitting = true; owner.client.scheduleStop(); }

    // ---- Доклады серверу. ----

    private void send(String kind, String... fields) {
        if (!owner.connected() || !ClientPlayNetworking.canSend(SelfTestLine.ID)) return;
        StringBuilder line = new StringBuilder(kind);
        for (String field : fields) line.append('\t').append(field == null ? "" : field.replace('\t', ' ').replace('\n', ' '));
        String text = line.length() > 4000 ? line.substring(0, 4000) : line.toString();
        ClientPlayNetworking.send(new SelfTestLine(text));
    }
    static void features(ServerFeatures features) {
        if (active != null) active.send("features", features.level(), String.valueOf(features.dialogue()), String.join(",", features.sttPaths()));
    }
    static void speech(MobSpeechS2C p) {
        if (active != null) active.send("speech", String.valueOf(p.toYou()), p.kind(), String.valueOf(p.mob()), p.speaker(), p.text(), p.heard(), p.asker());
    }
    static void state(MobStateS2C p) {
        if (active == null) return;
        String line = p.relationship() + "\t" + p.bargain();
        if (line.equals(active.states.put(p.mob(), line))) return;
        active.send("state", String.valueOf(p.mob()), String.valueOf(p.relationship()), p.bargain());
    }
    static void label(MobLabelS2C p) { if (active != null) active.send("label", String.valueOf(p.mob()), p.name()); }
    static void status(String text, boolean busy, String error) {
        if (active != null) active.send("status", String.valueOf(busy), error, error.isEmpty() ? text : error);
    }
    static void notice(String text) { if (active != null) active.send("chat", text); }

    // ---- Реплики дирижёра. ----

    private void cue(String line) {
        String[] f = line.split("\t", -1);
        log("cue " + line.replace('\t', ' '));
        try {
            switch (f[0]) {
                case "find" -> find(f[1]);
                case "say" -> { MobEntity mob = mob(f[1]); if (mob != null) { look(mob); owner.select(mob); log("say " + f[2] + ": " + owner.say(f[2])); } }
                case "gift" -> { MobEntity mob = mob(f[1]); if (mob != null) { look(mob); owner.select(mob); owner.giftFromHand(); } }
                case "trade" -> { MobEntity mob = mob(f[1]); if (mob != null) { look(mob); owner.select(mob); owner.openTrade(); } }
                case "close" -> { if (owner.client.player != null) owner.client.player.closeHandledScreen(); owner.client.setScreen(null); }
                case "cmd" -> owner.client.getNetworkHandler().sendChatCommand(f[1]);
                case "pray" -> pray(new BlockPos(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3])), f[4]);
                case "audio" -> audio(f[1]);
                case "probe" -> send("probe", "rain=" + (owner.client.world != null && owner.client.world.isRaining()),
                        "poison=" + (owner.client.player != null && owner.client.player.hasStatusEffect(StatusEffects.POISON)),
                        "dim=" + (owner.client.world == null ? "" : owner.client.world.getRegistryKey().getValue()));
                case "shot" -> ScreenshotRecorder.saveScreenshot(owner.client.runDirectory, f[1], owner.client.getFramebuffer(), text -> log("screenshot: " + text.getString()));
                case "sneak" -> { owner.client.options.sneakKey.setPressed(true); sneakUntil = ticks + 20L * Math.max(1, Integer.parseInt(f[1])); log("sneaking"); }
                case "hud" -> send("hud", PenanceHud.current());
                case "quit" -> { log("quit by the conductor"); quit(); }
                default -> log("unknown cue " + f[0]);
            }
        } catch (RuntimeException failure) { LOGGER.error("[selftest] FAIL: cue {} failed: {}", f[0], failure.toString()); }
    }

    private MobEntity mob(String id) {
        Entity entity = owner.client.world == null ? null : owner.client.world.getEntityById(Integer.parseInt(id));
        if (entity instanceof MobEntity mob) return mob;
        log("no mob " + id + " in this client's world");
        return null;
    }
    /** Ждёт, пока все жители сцены появятся в мире клиента: дирижёр шлёт реплики, только когда клиент их видит. */
    private void find(String ids) { pendingFind = ids; findUntil = ticks + 20 * 20; }
    private void pollFind() {
        String ids = pendingFind;
        if (ids == null) return;
        boolean all = owner.client.world != null && Arrays.stream(ids.split(",")).allMatch(id -> owner.client.world.getEntityById(Integer.parseInt(id)) instanceof MobEntity);
        if (all) { send("found", ids); pendingFind = null; }
        else if (ticks > findUntil) { log("mobs " + ids + " never appeared"); pendingFind = null; }
    }
    private void look(MobEntity mob) {
        if (owner.client.player != null) owner.client.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, mob.getEyePos());
    }

    /** Молитва текстом тем же пакетом, что шлёт экран алтаря Божественного блока (класс берётся из сборки, Mob Talk от него не зависит). */
    private void pray(BlockPos altar, String text) {
        try {
            Class<?> type = Class.forName("io.github.divineblock.game.PrayPayload");
            CustomPayload payload = (CustomPayload) type.getConstructor(BlockPos.class, String.class).newInstance(altar, text);
            ClientPlayNetworking.send(payload);
            log("prayed at " + altar.toShortString());
        } catch (ReflectiveOperationException missing) { LOGGER.error("[selftest] FAIL: no Divine Block prayer packet: {}", missing.toString()); }
    }

    /** Голос без Whisper: заранее озвученная фраза из кэша самотестов уходит серверу на распознавание ({@link TalkAudioC2S}). */
    private void audio(String id) {
        MobEntity mob = mob(id);
        if (mob == null) return;
        look(mob); owner.select(mob);
        String phrase = "Как тебя зовут и что ты здесь делаешь?";
        byte[] pcm24;
        try {
            pcm24 = SelfTestSpeech.fixture(SelfTest.fixtures(), owner.config().defaultVoice, phrase, () -> { throw new java.io.IOException("фраза не озвучена заранее"); });
        } catch (Exception missing) { send("audio-skip", missing.getMessage()); return; }
        int samples = pcm24.length / 2 * 2 / 3;
        byte[] pcm16 = new byte[samples * 2];
        for (int i = 0; i < samples; i++) { int j = (int) ((long) i * 3 / 2) * 2; pcm16[i * 2] = pcm24[j]; pcm16[i * 2 + 1] = pcm24[j + 1]; }
        byte[] wav = PcmWav.encode(pcm16);
        ClientPlayNetworking.send(new TalkAudioC2S(mob.getId(), "wav16k", Math.max(1, wav.length / 32_000), wav));
        log("sent " + wav.length + " bytes of audio for the server to recognise");
    }
}
