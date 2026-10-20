package io.github.mobtalk.client;

import io.github.mobtalk.audio.PcmWav;
import io.github.mobtalk.client.game.WeddingController;
import io.github.mobtalk.client.game.WeddingItems;
import io.github.mobtalk.core.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.Item;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Этапы самотеста трека L. Своя открытая площадка с колоколом (без потолка: салют рвётся высоко). 1) Живая свадьба: предложение
 * помолвочным кольцом словами, свидетель словами, «идём на свадьбу», сбор у колокола, наряд и арка, клятва голосом через Whisper,
 * обмен обручальными кольцами, брак, пожелания голосами, салют, подарки, уборка. 2) Сбои: модель, озвучка и Whisper «падают»;
 * игрок уходит — свадьба отложена, помолвка цела; потом свадьба без связи, в молчании, доходит до брака на запасных текстах.
 * 3) Свадьба двух жителей сценой; вторая прерывается гибелью жениха и убирает за собой. Снимки экрана — в screenshots. */
final class SelfTestWedding {
    private static final String TAG = "mobtalk_w";
    private final SelfTest test;
    private final int wx, wy, wz;
    private UUID bride, witness, cleric, bride2;
    private final List<UUID> guests = new ArrayList<>();
    private Runnable next = () -> { };
    private int speechBefore;
    private boolean fireworks;
    private MobTalkConfig savedBridgeConfig;
    /** Собеседник до этапа: следующие этапы самотеста рассчитывают на него. */
    private UUID selectedBefore;

    SelfTestWedding(SelfTest test, int stageX, int stageY, int stageZ) { this.test = test; this.wx = stageX + 60; this.wy = stageY; this.wz = stageZ; }

    private UUID playerId() { return test.owner.client.player.getUuid(); }
    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private VillagerEntity entity(UUID id) { return id != null && world().getEntity(id) instanceof VillagerEntity v ? v : null; }
    private WeddingController.Scene scene() { return test.bridge().wedding.scene(); }
    private String at(int dx, int dz) { return (wx + dx) + " " + wy + " " + (wz + dz); }
    private Gender playerGender() { return Gender.ofPlayer(test.owner.config().playerGender); }

    void run(Runnable then) {
        next = then;
        selectedBefore = test.owner.selected;
        test.log("wedding stages (track L): a live wedding, a wedding without network, villagers' weddings");
        savedBridgeConfig = test.bridge().config.copy();
        test.command("fill " + (wx - 16) + " " + (wy - 1) + " " + (wz - 16) + " " + (wx + 16) + " " + (wy - 1) + " " + (wz + 16) + " minecraft:smooth_stone");
        test.command("fill " + (wx - 16) + " " + wy + " " + (wz - 16) + " " + (wx + 16) + " " + (wy + 6) + " " + (wz + 16) + " minecraft:air");
        for (String wall : List.of(
                (wx - 16) + " " + wy + " " + (wz - 16) + " " + (wx + 16) + " " + (wy + 1) + " " + (wz - 16),
                (wx - 16) + " " + wy + " " + (wz + 16) + " " + (wx + 16) + " " + (wy + 1) + " " + (wz + 16),
                (wx - 16) + " " + wy + " " + (wz - 16) + " " + (wx - 16) + " " + (wy + 1) + " " + (wz + 16),
                (wx + 16) + " " + wy + " " + (wz - 16) + " " + (wx + 16) + " " + (wy + 1) + " " + (wz + 16)))
            test.command("fill " + wall + " minecraft:barrier");
        test.command("setblock " + at(0, 0) + " minecraft:bell[attachment=floor,facing=north]");
        test.command("time set 1000"); test.command("weather clear");
        test.command("effect give " + name() + " minecraft:resistance 1200 255 true");
        // В мире «stream 1» игрок — изгнанник деревни внизу: изгнаннику на свадьбу приходит только родня. Для этапа — прощение.
        test.log("forgive: " + test.bridge().memory.forgiveVillage(world().getRegistryKey().getValue().toString(), wx, wz, playerId()));
        test.command("tp " + name() + " " + at(0, 8));
        for (int i = 0; i < 12; i++) test.command("summon minecraft:villager " + at(-6 + (i % 6) * 2, 4 + (i / 6) * 2) + " {PersistenceRequired:1b,Tags:[\"" + TAG + "\"]}");
        test.command("summon minecraft:villager " + at(6, 6) + " {PersistenceRequired:1b,Tags:[\"" + TAG + "\",\"cleric\"],VillagerData:{profession:\"minecraft:cleric\",level:2,type:\"minecraft:plains\"}}");
        test.await("thirteen villagers for track L", 15, () -> tagged().size() >= 13, this::cast);
    }
    private List<VillagerEntity> tagged() {
        return world().getEntitiesByClass(VillagerEntity.class, new Box(wx - 17, wy - 2, wz - 17, wx + 17, wy + 8, wz + 17), v -> v.isAlive() && v.getCommandTags().contains(TAG));
    }

    /** Две невесты, которых тянет к игроку, свидетель другого пола, чем невеста, священник и гости. */
    private void cast() {
        List<VillagerEntity> all = tagged();
        for (VillagerEntity v : all) {
            UUID id = v.getUuid();
            if (v.getCommandTags().contains("cleric")) { cleric = id; continue; }
            if (bride == null && SocialProfile.drawnTo(id, playerGender())) { bride = id; continue; }
            if (bride2 == null && SocialProfile.drawnTo(id, playerGender())) { bride2 = id; continue; }
            if (witness == null && bride != null && SocialProfile.genderOf(id) != SocialProfile.genderOf(bride)) { witness = id; continue; }
            guests.add(id);
        }
        if (bride == null || bride2 == null || witness == null || cleric == null) { test.fail("could not cast the wedding: " + bride + " " + bride2 + " " + witness + " " + cleric); finish(); return; }
        long now = System.currentTimeMillis();
        for (UUID id : List.of(bride, bride2, witness)) test.bridge().memory.recordEvent(id, "minecraft:villager", "Житель", "Самотест: знакомство (трек L)", now);
        // Сердечки «подарком»: невеста на 95, свидетель на 60.
        test.bridge().memory.social().applyGesture(bride, playerId(), name(), new GestureIntent(Gesture.GIFT, Gesture.Outcome.BEST, 95, "minecraft:diamond", ""), now);
        test.bridge().memory.social().applyGesture(witness, playerId(), name(), new GestureIntent(Gesture.GIFT, Gesture.Outcome.BEST, 60, "minecraft:emerald", ""), now);
        test.log("bride " + test.bridge().memory.displayName(bride) + " (" + test.bridge().memory.relationship(bride, playerId()) + "), witness " + test.bridge().memory.displayName(witness));
        propose();
    }

    // ---- 1. Живая свадьба. ----

    private void propose() {
        test.command("tp " + bride + " " + at(0, 6));
        test.command("item replace entity " + name() + " weapon.mainhand with mobtalk:engagement_ring");
        test.await("pause: the ring in hand", 2, () -> false, () -> {
            test.owner.select(MobSpeechSound.find(test.owner.world, bride));
            int turns = turns(bride);
            test.check(test.owner.say("Выходи за меня замуж! Вот кольцо, оно твоё."), "the proposal is said");
            test.await("the bride answered the proposal", 120, () -> turns(bride) > turns && !test.owner.busyTalking(), () -> {
                boolean engaged = test.bridge().memory.wedding().engaged(bride, playerId());
                test.check(engaged, "a ring in hand makes an engagement (not yet a marriage): " + lastReply(bride));
                test.check(test.owner.client.player.getMainHandStack().isEmpty(), "the engagement ring left the hand");
                test.check(test.bridge().memory.jealousy().partners(playerId()).stream().noneMatch(JealousyLedger.Partner::married), "not married before the wedding");
                if (!engaged) test.bridge().memory.social().applyGesture(bride, playerId(), name(),
                        new GestureIntent(Gesture.PROPOSE, Gesture.Outcome.ACCEPTED, 10, Romance.ENGAGEMENT_RING, ""), System.currentTimeMillis());
                askWitness();
            });
        });
    }
    private void askWitness() {
        test.command("tp " + witness + " " + at(2, 6));
        test.owner.select(MobSpeechSound.find(test.owner.world, witness));
        int turns = turns(witness);
        test.check(test.owner.say("Будь моим свидетелем на свадьбе!"), "the witness is asked");
        test.await("the witness answered", 120, () -> turns(witness) > turns && !test.owner.busyTalking(), () -> {
            test.await("the witness appointed", 5, () -> Arrays.asList(test.bridge().memory.wedding().witnesses(bride, playerId())).contains(witness), () -> {
                test.check(Arrays.asList(test.bridge().memory.wedding().witnesses(bride, playerId())).contains(witness),
                        "a friend asked in words becomes a witness: " + lastReply(witness));
                callToWedding();
            });
        });
    }
    private void callToWedding() {
        test.command("item replace entity " + name() + " hotbar.1 with mobtalk:wedding_ring 2");
        test.owner.select(MobSpeechSound.find(test.owner.world, bride));
        int turns = turns(bride);
        speechBefore = test.owner.wedding.voiced;
        test.check(test.owner.say("Идём на свадьбу, любимая! Все к колоколу!"), "the call to the wedding is said");
        test.await("the wedding began", 120, () -> scene() != null, () -> {
            WeddingController.Scene s = scene();
            test.log("bride said: " + lastReply(bride));
            Wedding.Cast cast = s.ceremony.cast();
            test.check(cast.officiant() != null && cast.officiant().id().equals(cleric), "the village priest leads: " + (cast.officiant() == null ? "nobody" : cast.officiant().name()));
            test.check(witness.equals(cast.witnessMan() == null ? null : cast.witnessMan().id()) || witness.equals(cast.witnessWoman() == null ? null : cast.witnessWoman().id()),
                    "the asked witness stands as witness");
            test.check(cast.guests().size() >= 3, "the village came: " + cast.guests().size() + " guests");
            test.check(s.archSize() >= 10, "a flower arch of item displays: " + s.archSize());
            VillagerEntity b = entity(bride);
            Item expected = cast.spouse().gender() == Gender.FEMALE ? WeddingItems.BRIDAL_VEIL : WeddingItems.WEDDING_CROWN;
            test.check(b != null && b.getEquippedStack(EquipmentSlot.HEAD).isOf(expected), "the bride wears " + expected);
            test.check(b != null && !b.getMainHandStack().isEmpty(), "the bride holds flowers");
            Vec3d spot = s.playerSpot();
            if (spot != null) test.command("tp " + name() + " " + spot.x + " " + spot.y + " " + spot.z);
            test.await("everyone gathered and the service began", 110, () -> scene() != null && scene().ceremony.phase() == Wedding.Ceremony.Phase.SERVICE, () -> {
                test.check(scene() != null, "the service runs");
                shoot("mobtalk-wedding-gathered.png", () -> shoot("mobtalk-wedding-close.png", this::waitVow));
            });
        });
    }
    /** Снимок сцены спереди, без интерфейса; потом игрок возвращается на своё место. */
    private void shoot(String file, Runnable then) {
        WeddingController.Scene s = scene();
        if (s == null) { then.run(); return; }
        boolean close = file.contains("close");
        // Со стороны колокола: пара лицом к камере, гости за ней. Крупно — сбоку от ведущего.
        Vec3d right = new Vec3d(-s.forward().z, 0, s.forward().x);
        Vec3d eye = close ? s.couple().subtract(s.forward().multiply(1.8)).add(right.multiply(1.6)).add(0, 0.05, 0)
                : s.couple().subtract(s.forward().multiply(10)).add(0, 3, 0);
        Vec3d look = s.couple().add(0, close ? 1.7 : 1.5, 0);
        Vec3d back = s.playerSpot();
        test.command("tp " + name() + " " + eye.x + " " + eye.y + " " + eye.z + " facing " + look.x + " " + look.y + " " + look.z);
        test.owner.client.options.hudHidden = true;
        test.await("pause: the camera settles", 2, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.owner.client.options.hudHidden = false;
            if (back != null) test.command("tp " + name() + " " + back.x + " " + back.y + " " + back.z + " facing " + look.x + " " + look.y + " " + look.z);
            test.await("pause: back in place", 1, () -> false, then);
        });
    }
    private void waitVow() {
        test.await("the officiant asked for the vow", 150, () -> scene() == null || scene().ceremony.awaitingVow(), () -> {
            if (scene() == null) { test.fail("the wedding ended before the vow"); afterLive(); return; }
            test.check(test.owner.wedding.voiced > speechBefore, "the officiant's lines were voiced");
            test.check(test.owner.wedding.takesVow(), "the client takes the next words as the vow");
            MobTalkConfig config = test.owner.config().copy();
            CompletableFuture<byte[]> wav = CompletableFuture.supplyAsync(() -> {
                try {
                    byte[] pcm24 = SpeechPreview.synthesize(config, test.owner.configDirectory, config.defaultVoice,
                            "Да, я согласен. Клянусь быть с тобой в радости и в беде, в ссоре и в мире, и заботиться о тебе всю жизнь.");
                    int samples = pcm24.length / 2 * 2 / 3;
                    byte[] pcm16 = new byte[samples * 2];
                    for (int i = 0; i < samples; i++) { int j = (int) ((long) i * 3 / 2) * 2; pcm16[i * 2] = pcm24[j]; pcm16[i * 2 + 1] = pcm24[j + 1]; }
                    return PcmWav.encode(pcm16);
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            test.await("the vow voiced by TTS", 60, wav::isDone, () -> {
                if (wav.isCompletedExceptionally()) { test.fail("could not voice the vow: " + wav.exceptionNow().getMessage()); test.owner.say("Да, клянусь любить тебя всегда!"); }
                else test.check(test.owner.sayWav(wav.join()), "the vow goes through Whisper like a held V");
                test.await("the vow was heard", 120, () -> scene() == null || !scene().ceremony.awaitingVow(), () -> {
                    String vow = scene() == null ? "" : scene().ceremony.vowText();
                    test.log("vow heard: " + vow);
                    test.check(!vow.isBlank() && vow.toLowerCase().contains("клян"), "Whisper heard the vow");
                    test.await("the couple is married", 240, () -> test.bridge().memory.jealousy().partners(playerId()).stream().anyMatch(p -> p.id().equals(bride) && p.married()), () -> {
                        test.check(test.bridge().memory.jealousy().partners(playerId()).stream().anyMatch(p -> p.id().equals(bride) && p.married()), "married at the bell");
                        int rings = count(WeddingItems.WEDDING_RING);
                        test.check(rings == 1, "one wedding ring went to the spouse (" + rings + " left)");
                        test.check(test.bridge().memory.wedding().ring(bride, playerId()).equals(Romance.WEDDING_RING), "the spouse keeps the wedding ring");
                        fireworks = false;
                        test.await("the feast", 240, () -> { spotFireworks(); return scene() == null || scene().ceremony.over(); }, () -> {
                            afterFeast();
                        });
                    });
                });
            });
        });
    }
    private void spotFireworks() {
        if (!fireworks) fireworks = !world().getEntitiesByClass(FireworkRocketEntity.class, new Box(wx - 30, wy - 2, wz - 30, wx + 30, wy + 60, wz + 30), r -> true).isEmpty();
    }
    private void afterFeast() {
        test.check(fireworks, "fireworks went up");
        test.check(test.owner.wedding.voiced - speechBefore >= 6, "officiant, bride and guests spoke in their voices: " + (test.owner.wedding.voiced - speechBefore) + " voiced phrases");
        test.await("pause: the last effects", 3, () -> false, () -> {
            test.check(test.bridge().memory.events(witness).stream().anyMatch(e -> e.contains("свидетел") && e.contains("свадьб")), "the witness remembers the wedding");
            long guestMemories = guests.stream().filter(g -> test.bridge().memory.events(g).stream().anyMatch(e -> e.contains("свадьб"))).count();
            test.check(guestMemories >= 2, "guests remember the wedding: " + guestMemories);
            VillagerEntity b = entity(bride);
            test.check(b != null && b.getMainHandStack().isEmpty(), "the flowers left the bride's hands");
            test.check(b != null && WeddingItems.attire(b.getEquippedStack(EquipmentSlot.HEAD).getItem()), "the bride keeps the veil for a day");
            test.check(world().getEntitiesByClass(DisplayEntity.ItemDisplayEntity.class, new Box(wx - 20, wy - 2, wz - 20, wx + 20, wy + 10, wz + 20), d -> true).isEmpty(), "the arch is gone");
            test.check(WeddingItems.attire(test.serverPlayer().getEquippedStack(EquipmentSlot.HEAD).getItem()) || count(WeddingItems.WEDDING_CROWN) + count(WeddingItems.BRIDAL_VEIL) > 0,
                    "the player got the crown or veil to keep");
            int gifts = world().getEntitiesByClass(ItemEntity.class, test.serverPlayer().getBoundingBox().expand(8), i -> true).size();
            test.log("gifts lying around the player: " + gifts);
            test.owner.client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
            test.await("pause: third person", 2, () -> false, () -> {
                test.owner.client.options.hudHidden = true;
                test.await("pause: hud hidden", 1, () -> false, () -> {
                    ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, "mobtalk-wedding-player.png", test.owner.client.getFramebuffer(), text -> { });
                    test.owner.client.options.hudHidden = false;
                    test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
                    afterLive();
                });
            });
        });
    }
    private void afterLive() { offline(); }

    // ---- 2. Без связи: отложить, потом сыграть в молчании. ----

    private void offline() {
        long now = System.currentTimeMillis();
        test.bridge().memory.social().applyGesture(bride, playerId(), name(), new GestureIntent(Gesture.DIVORCE, Gesture.Outcome.ACCEPTED, -40, "", Courtship.MARRIED.name()), now);
        test.bridge().memory.social().applyGesture(bride2, playerId(), name(), new GestureIntent(Gesture.GIFT, Gesture.Outcome.BEST, 95, "minecraft:diamond", ""), now);
        test.bridge().memory.social().applyGesture(bride2, playerId(), name(), new GestureIntent(Gesture.PROPOSE, Gesture.Outcome.ACCEPTED, 12, "minecraft:diamond", ""), now);
        test.check(test.bridge().memory.wedding().engaged(bride2, playerId()), "engaged to the second bride with a diamond");
        test.command("time set 1000");
        WeddingFaults.llm = true; WeddingFaults.tts = true; WeddingFaults.whisper = true;
        test.bridge().config.weddingGatherSeconds = 20; test.bridge().config.weddingVowWaitSeconds = 10;
        test.command("tp " + name() + " " + at(0, 8));
        test.command("tp " + bride2 + " " + at(1, 6));
        test.await("pause: the second bride is near", 2, () -> false, () -> {
            CompletableFuture<String> started = test.bridge().startWedding(bride2, playerId());
            test.await("the second wedding began", 10, () -> scene() != null, () -> {
                test.log("start: '" + (started.isDone() ? started.join() : "?") + "'");
                test.command("tp " + name() + " " + (wx - 60) + " " + wy + " " + wz); // На первую площадку: дальше 40 блоков от колокола.
                test.await("the wedding is put off when the player leaves", 10, () -> scene() == null, () -> {
                    test.check(scene() == null, "leaving the bell puts the wedding off");
                    test.check(test.bridge().memory.wedding().engaged(bride2, playerId()), "the engagement survives a put-off wedding");
                    VillagerEntity b = entity(bride2);
                    test.check(b != null && b.getEquippedStack(EquipmentSlot.HEAD).isEmpty() && b.getMainHandStack().isEmpty(), "a put-off wedding takes the attire and flowers back");
                    test.check(world().getEntitiesByClass(DisplayEntity.ItemDisplayEntity.class, new Box(wx - 20, wy - 2, wz - 20, wx + 20, wy + 10, wz + 20), d -> true).isEmpty(), "and the arch");
                    silentWedding();
                });
            });
        });
    }
    private void silentWedding() {
        test.command("tp " + name() + " " + at(0, 8));
        test.await("pause: back at the bell", 2, () -> false, () -> {
            test.bridge().startWedding(bride2, playerId());
            test.await("the wedding without network began", 10, () -> scene() != null, () -> {
                Vec3d spot = scene().playerSpot();
                if (spot != null) test.command("tp " + name() + " " + spot.x + " " + spot.y + " " + spot.z);
                int voiced = test.owner.wedding.voiced;
                long started = System.currentTimeMillis();
                test.await("the vow is awaited", 120, () -> scene() == null || scene().ceremony.awaitingVow(), () -> {
                    // A spoken vow while Whisper is down: the client says it was not heard; then the player keeps silent.
                    test.owner.sayWav(PcmWav.encode(new byte[32_000]));
                    test.await("married without network, in silence", 240, () -> test.bridge().memory.jealousy().partners(playerId()).stream().anyMatch(p -> p.id().equals(bride2) && p.married()), () -> {
                        test.check(test.bridge().memory.jealousy().partners(playerId()).stream().anyMatch(p -> p.id().equals(bride2) && p.married()),
                                "no model, no voice, no Whisper, no vow: still married on the stand-in texts");
                        test.await("the second wedding ends", 120, () -> scene() == null, () -> {
                            test.check(test.owner.wedding.voiced == voiced, "with speech down nothing of the wedding was voiced, the lines went as text");
                            test.log("the wedding without network took " + (System.currentTimeMillis() - started) / 1000 + " s");
                            WeddingFaults.clear();
                            villageWeddings();
                        });
                    });
                });
            });
        });
    }

    // ---- 3. Свадьба жителей сценой; гибель жениха прерывает вторую. ----

    private void villageWeddings() {
        List<UUID> free = new ArrayList<>(guests);
        UUID a = null, b = null, c = null, d = null;
        List<UUID> men = free.stream().filter(x -> SocialProfile.genderOf(x) == Gender.MALE).toList();
        List<UUID> women = free.stream().filter(x -> SocialProfile.genderOf(x) == Gender.FEMALE).toList();
        if (!men.isEmpty() && !women.isEmpty()) { a = men.get(0); b = women.get(0); }
        if (men.size() > 1 && women.size() > 1) { c = men.get(1); d = women.get(1); }
        if (a == null) { test.log("no man and woman among the guests: the villagers' wedding scene is skipped"); finish(); return; }
        UUID groom = a, wife = b, groom2 = c, wife2 = d;
        test.command("time set 1000"); // Свадьбы играют днём, а этапы идут долго.
        WeddingFaults.llm = true; WeddingFaults.tts = true; // Быстро: на запасных текстах.
        CompletableFuture<Boolean> began = test.bridge().onServer(() -> test.bridge().wedding.startVillageForTest(world(), groom, wife, test.serverPlayer()));
        test.await("the villagers' scene starts", 10, began::isDone, () -> {
        test.check(!began.isCompletedExceptionally() && began.join(), "two villagers marry as a scene at the bell");
        test.await("the villagers are married", 240, () -> scene() == null, () -> {
            test.check(wife.equals(test.bridge().memory.social().household(groom).spouse()), "the village scene ended in a marriage");
            if (groom2 == null) { WeddingFaults.clear(); finish(); return; }
            CompletableFuture<Boolean> again = test.bridge().onServer(() -> test.bridge().wedding.startVillageForTest(world(), groom2, wife2, test.serverPlayer()));
            test.await("its service began", 90, () -> again.isDone() && (scene() == null || scene().ceremony.phase() == Wedding.Ceremony.Phase.SERVICE), () -> {
                test.check(!again.isCompletedExceptionally() && again.join(), "a second villagers' wedding");
                test.command("kill " + groom2);
                test.await("the wedding is called off by a death", 10, () -> scene() == null, () -> {
                    test.check(scene() == null, "a groom's death ends the wedding");
                    test.check(test.bridge().memory.social().household(wife2).spouse() == null, "no marriage with the dead");
                    VillagerEntity w = entity(wife2);
                    test.check(w != null && w.getEquippedStack(EquipmentSlot.HEAD).isEmpty(), "the bride's attire came off");
                    WeddingFaults.clear();
                    finish();
                });
            });
        });
        });
    }

    private int count(Item item) {
        var inventory = test.serverPlayer().getInventory();
        int n = 0;
        for (int i = 0; i < inventory.size(); i++) if (inventory.getStack(i).isOf(item)) n += inventory.getStack(i).getCount();
        return n;
    }
    private int turns(UUID mob) { MobCard card = test.bridge().memory.card(mob, playerId()); return card == null ? 0 : card.turns().size(); }
    private String lastReply(UUID mob) { MobCard card = test.bridge().memory.card(mob, playerId()); return card == null || card.turns().isEmpty() ? "(none)" : card.turns().getLast().assistant(); }

    private void finish() {
        WeddingFaults.clear();
        MobTalkConfig restored = savedBridgeConfig;
        if (restored != null) { test.bridge().config.weddingGatherSeconds = restored.weddingGatherSeconds; test.bridge().config.weddingVowWaitSeconds = restored.weddingVowWaitSeconds; }
        test.owner.client.options.hudHidden = false;
        test.command("kill @e[tag=" + TAG + "]");
        test.command("item replace entity " + name() + " armor.head with minecraft:air");
        test.command("time set day");
        test.command("tp " + name() + " " + (wx - 60) + " " + wy + " " + wz);
        test.await("pause: track L stage cleaned up", 2, () -> false, () -> {
            var before = MobSpeechSound.find(test.owner.world, selectedBefore);
            if (before != null) test.owner.select(before);
            next.run();
        });
    }
}
