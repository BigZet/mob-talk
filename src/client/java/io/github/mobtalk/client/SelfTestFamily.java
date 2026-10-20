package io.github.mobtalk.client;

import io.github.mobtalk.client.game.ChildController;
import io.github.mobtalk.core.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** Этапы самотеста трека N на своей площадке: жена (или муж, если носит игрок) — брак, «давай заведём ребёнка», дом с свечами,
 * цветами, ковром, пластинкой и тортом, особый вечер (уют, живая реплика о том, что она видит), ночь вместе с затемнением и
 * шёпотом, утро «Наутро…» с сердечками и живой репликой; срок по дням — новость, строка под именем, живот на фигурке, экран семьи,
 * тяжёлая походка; роды — схватки, затемнение, малыш в дереве семьи. Каждая сцена доходит до конца, даже если модель молчит.
 * Снимки экрана — в screenshots. */
final class SelfTestFamily {
    private static final String TAG = "mobtalk_n";
    private static final long DAY = 24_000;
    private final SelfTest test;
    private final int fx, fy, fz;
    private UUID spouse;
    private Pregnancy.Carrier carrier;
    private final List<UUID> babies = new ArrayList<>();
    private MobTalkConfig saved;
    private Runnable next = () -> { };

    SelfTestFamily(SelfTest test, int stageX, int stageY, int stageZ) { this.test = test; this.fx = stageX - 60; this.fy = stageY; this.fz = stageZ; }

    private UUID playerId() { return test.owner.client.player.getUuid(); }
    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private VillagerEntity entity(UUID id) { return id != null && world().getEntity(id) instanceof VillagerEntity v ? v : null; }
    private String at(int dx, int dy, int dz) { return (fx + dx) + " " + (fy + dy) + " " + (fz + dz); }
    private UUID carrierId() { return carrier == Pregnancy.Carrier.PLAYER ? playerId() : spouse; }
    private ChildController children() { return test.bridge().children; }
    private ChildLedger.Pregnant pregnant() { return test.bridge().memory.children().pregnancies().stream().filter(p -> p.villager().equals(spouse)).findFirst().orElse(null); }

    void run(Runnable then) {
        next = then;
        test.log("family stages (track N): a special night, the term, the birth, the family screen");
        saved = test.bridge().config.copy();
        test.bridge().config.weddingChance = 0; test.bridge().config.birthChance = 0; // Деревня сама ничего не затевает.
        test.command("fill " + at(-8, -1, -8) + " " + at(8, -1, 8) + " minecraft:smooth_stone");
        test.command("fill " + at(-8, 0, -8) + " " + at(8, 5, 8) + " minecraft:air");
        for (String wall : List.of(at(-8, 0, -8) + " " + at(8, 1, -8), at(-8, 0, 8) + " " + at(8, 1, 8), at(-8, 0, -8) + " " + at(-8, 1, 8), at(8, 0, -8) + " " + at(8, 1, 8)))
            test.command("fill " + wall + " minecraft:barrier");
        test.command("time set 1000"); test.command("weather clear");
        test.command("tp " + name() + " " + at(0, 0, 4));
        for (int i = 0; i < 12; i++) test.command("summon minecraft:villager " + at(-6 + (i % 6) * 2, 0, -4 + (i / 6) * 2) + " {PersistenceRequired:1b,Tags:[\"" + TAG + "\"]}");
        test.await("twelve villagers for track N", 15, () -> tagged().size() >= 12, this::cast);
    }
    private List<VillagerEntity> tagged() {
        return world().getEntitiesByClass(VillagerEntity.class, new Box(fx - 9, fy - 2, fz - 9, fx + 9, fy + 6, fz + 9), v -> v.isAlive() && v.getCommandTags().contains(TAG));
    }

    /** Жительница, которую тянет к игроку и которая понесёт сама; иначе житель, от которого понесёт игрок. Остальные уходят. */
    private void cast() {
        Gender player = Gender.ofPlayer(test.owner.config().playerGender);
        for (VillagerEntity v : tagged()) test.log("candidate " + v.getUuid() + ": " + SocialProfile.genderOf(v.getUuid()) + ", drawn to the player "
                + SocialProfile.drawnTo(v.getUuid(), player) + ", carrier " + Pregnancy.carrier(SocialProfile.genderOf(v.getUuid()), player));
        for (Pregnancy.Carrier wanted : List.of(Pregnancy.Carrier.VILLAGER, Pregnancy.Carrier.PLAYER))
            for (VillagerEntity v : tagged())
                if (spouse == null && SocialProfile.drawnTo(v.getUuid(), player) && Pregnancy.carrier(SocialProfile.genderOf(v.getUuid()), player) == wanted) { spouse = v.getUuid(); carrier = wanted; }
        if (spouse == null) { test.fail("no villager among twelve can carry or father a child with this player's sex"); finish(); return; }
        for (VillagerEntity v : tagged()) if (!v.getUuid().equals(spouse)) v.discard();
        long now = System.currentTimeMillis();
        test.bridge().memory.recordEvent(spouse, "minecraft:villager", "Житель", "Самотест: знакомство (трек N)", now);
        test.check(test.bridge().memory.children().selfTestMarry(spouse, playerId(), name()), "the spouse is married to the player (no ceremony: track L tests that)");
        test.check(test.bridge().memory.jealousy().partners(playerId()).stream().anyMatch(p -> p.id().equals(spouse) && p.married()), "the couple is married in the world memory");
        test.bridge().memory.social().applyGesture(spouse, playerId(), name(), new GestureIntent(Gesture.CHILD, Gesture.Outcome.ACCEPTED, 5, "", ""), now);
        test.check(test.bridge().memory.children().trying(spouse, playerId()), "after «давай заведём ребёнка» the couple is trying");
        test.check(test.bridge().memory.children().carrier(spouse) == carrier, "the carrier is the woman of the couple: " + carrier);
        test.log("spouse " + test.bridge().memory.displayName(spouse) + " (" + SocialProfile.genderOf(spouse) + "), carrier " + carrier);
        home();
    }

    /** Дом: две кровати, свечи, цветы в горшках, ковёр, проигрыватель с пластинкой, торт. */
    private void home() {
        test.command("setblock " + at(-1, 0, -6) + " minecraft:red_bed[part=head,facing=north]");
        test.command("setblock " + at(-1, 0, -5) + " minecraft:red_bed[part=foot,facing=north]");
        test.command("setblock " + at(1, 0, -6) + " minecraft:red_bed[part=head,facing=north]");
        test.command("setblock " + at(1, 0, -5) + " minecraft:red_bed[part=foot,facing=north]");
        test.command("setblock " + at(-3, 0, -6) + " minecraft:candle[candles=3,lit=true]");
        test.command("setblock " + at(3, 0, -6) + " minecraft:candle[candles=2,lit=true]");
        test.command("setblock " + at(-3, 0, -3) + " minecraft:potted_poppy");
        test.command("setblock " + at(3, 0, -3) + " minecraft:potted_blue_orchid");
        test.command("fill " + at(-2, 0, -4) + " " + at(2, 0, -4) + " minecraft:pink_carpet");
        test.command("setblock " + at(4, 0, -1) + " minecraft:jukebox[has_record=true]");
        test.command("setblock " + at(-4, 0, -1) + " minecraft:cake");
        test.command("tp " + spouse + " " + at(0, 0, 0));
        test.command("tp " + name() + " " + at(0, 0, 2) + " facing " + at(0, 1, -6));
        test.await("pause: the home is ready", 2, () -> false, this::evening);
    }

    /** «У меня для тебя сюрприз»: особый вечер, уют посчитан, она идёт за игроком и замечает, что он приготовил (живая реплика). */
    private void evening() {
        int lines = test.owner.initiatives.lastLines.size();
        test.owner.initiatives.lastLines.remove(spouse);
        CompletableFuture<String> started = test.bridge().startEvening(spouse, playerId());
        test.await("the special evening started", 5, started::isDone, () -> {
            String message = started.isCompletedExceptionally() ? "" : started.join();
            test.log("evening: " + message);
            test.check(!message.isBlank(), "the spouse accepted the special evening");
            ChildLedger.Evening evening = test.bridge().memory.children().evening(spouse);
            test.check(evening != null && evening.cozy().score() >= 5, "the home is cosy: " + (evening == null ? "no evening" : evening.cozy().words()));
            test.await("the spouse speaks of what she sees (live)", 60, () -> test.owner.initiatives.lastLines.containsKey(spouse), () -> {
                String line = test.owner.initiatives.lastLines.getOrDefault(spouse, "");
                test.log("evening line: " + line);
                if (line.isBlank()) test.log("… no live line (network or a quiet verdict): the evening goes on");
                else test.check(!SpecialEvening.explicit(line), "the evening line stays tender");
                shot("mobtalk-family-evening.png", false, this::night);
            });
        });
    }

    /** Ночь: игрок ложится, пара ложится рядом, экран уходит в тень, ночь держится, пока шепчутся; шанс малыша. */
    private void night() {
        test.command("time add 12000"); // 1000 → 13000, вечер.
        test.command("tp " + name() + " " + at(-1, 0, -3));
        // Ночь для сна игра видит со следующего тика (тьма неба пересчитывается), поэтому ложимся после паузы.
        test.await("pause: night falls", 3, () -> false, this::lieDown);
    }
    private void lieDown() {
        BlockPos bed = new BlockPos(fx - 1, fy, fz - 6);
        CompletableFuture<String> slept = test.bridge().onServer(() -> test.serverPlayer().trySleep(bed).map(problem -> "no: " + problem, ok -> "ok").toString());
        test.await("the player lay down", 5, slept::isDone, () -> {
            test.log("trySleep: " + (slept.isCompletedExceptionally() ? slept.exceptionNow().getMessage() : slept.join()));
            test.await("the spouse lies down beside the player", 20, () -> { VillagerEntity w = entity(spouse); return w != null && w.isSleeping(); }, () -> {
                ChildLedger.Evening evening = test.bridge().memory.children().evening(spouse);
                test.check(evening != null && evening.dark(), "the evening went dark: they lay down together");
                ChildController.Fade fade = children().fade(playerId());
                test.check(fade != null && fade.kind() == ChildController.FadeKind.NIGHT, "the screen fades into the night");
                test.await("pause: whispering in the dark", 8, () -> false, () -> {
                    long time = world().getTimeOfDay() % DAY;
                    test.check(time >= 12_000L, "the special night waits while they whisper (time " + time + ")");
                    shot("mobtalk-family-night.png", false, () -> {
                        if (pregnant() == null) {
                            test.log("the night's roll missed; conceiving with a sure roll to go on");
                            test.bridge().onServer(() -> test.bridge().memory.children().night(spouse, playerId(), world().getServer().getOverworld().getTimeOfDay(), 0.0)).join();
                        }
                        test.check(pregnant() != null, "a child is on the way after the special night");
                        morning();
                    });
                });
            });
        });
    }

    /** Утро: «Наутро…», сердечки, смущённая тёплая реплика (живая). */
    private void morning() {
        int before = test.bridge().memory.relationship(spouse, playerId());
        test.owner.initiatives.lastLines.remove(spouse);
        test.command("time add 11500"); // 13000 → 24500: утро следующего дня.
        CompletableFuture<Void> up = test.bridge().onServer(() -> { test.serverPlayer().wakeUp(false, true); return null; });
        test.await("the player got up", 5, up::isDone, () -> test.await("the morning after", 5, () -> test.bridge().memory.children().evening(spouse) == null, () -> {
            ChildController.Fade fade = children().fade(playerId());
            test.check(fade != null && fade.kind() == ChildController.FadeKind.MORNING, "«Наутро…» on the screen");
            int after = test.bridge().memory.relationship(spouse, playerId());
            test.check(after > before || after >= 100, "the special night warmed the spouse (" + before + " → " + after + ")");
            shot("mobtalk-family-morning.png", false, () -> test.await("the morning line (live)", 60, () -> test.owner.initiatives.lastLines.containsKey(spouse), () -> {
                test.log("morning line: " + test.owner.initiatives.lastLines.getOrDefault(spouse, "(none)"));
                term();
            }));
        }));
    }

    /** Срок: на следующий день о малыше знают — строка под именем; к середине виден живот; экран семьи; поздний срок — медленнее. */
    private void term() {
        test.command("time add " + DAY);
        test.await("the news: the term under the name", 20, () -> ChildView.status(carrierId()).contains("ждёт малыша") && pregnant() != null && pregnant().news() >= 1, () -> {
            test.log("status: " + ChildView.status(carrierId()));
            ChildLedger.Pregnant p = pregnant();
            test.check(p != null && p.news() >= 1, "the news was told (stage " + (p == null ? -1 : p.news()) + ")");
            test.command("time add " + DAY);
            test.await("mid-term: the belly shows", 20, () -> ChildView.belly(carrierId()) > 0, () -> {
                test.log("belly " + ChildView.belly(carrierId()) + ", status " + ChildView.status(carrierId()));
                if (carrier == Pregnancy.Carrier.VILLAGER) {
                    // Сбоку, на уровне пояса: жительница замерла лицом на юг, камера с востока — округлость видна в профиль.
                    test.command("tp " + spouse + " " + (fx + 0.5) + " " + fy + " " + (fz - 0.5) + " 0 0");
                    test.command("data merge entity " + spouse + " {NoAI:1b}");
                    test.command("tp " + name() + " " + (fx + 3.0) + " " + fy + " " + (fz - 0.5) + " facing " + (fx + 0.5) + " " + (fy + 1.1) + " " + (fz - 0.5));
                }
                shot("mobtalk-family-belly-label.png", carrier == Pregnancy.Carrier.PLAYER, () -> {
                    test.owner.client.options.hudHidden = true;
                    shot("mobtalk-family-belly.png", carrier == Pregnancy.Carrier.PLAYER, () -> {
                        test.owner.client.options.hudHidden = false;
                        if (carrier == Pregnancy.Carrier.VILLAGER) test.command("data merge entity " + spouse + " {NoAI:0b}");
                        test.command("tp " + name() + " " + at(0, 0, 2));
                        screen();
                    });
                });
            });
        });
    }
    private void screen() {
        test.owner.openFamily(null);
        test.await("the family screen reads the world", 10, () -> test.owner.client.currentScreen instanceof FamilyScreen s && s.page() != null, () -> {
            if (test.owner.client.currentScreen instanceof FamilyScreen s && s.page() != null) {
                ChildLedger.Overview o = s.page().overview();
                test.check(o.spouses().size() == 1 && !o.spouses().getFirst().pregnancy().isBlank(), "the family screen shows the spouse and the term: "
                        + (o.spouses().isEmpty() ? "-" : o.spouses().getFirst().pregnancy()));
            }
            test.await("pause: the screen draws", 1, () -> false, () -> {
                ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, "mobtalk-family-screen.png", test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
                test.owner.client.setScreen(null);
                late();
            });
        });
    }
    private void late() {
        test.command("time add " + 2 * DAY); // День 5 из 5: поздний срок.
        test.await("late term", 12, () -> { ChildLedger.Pregnant p = pregnant(); return p != null && p.term(world().getTimeOfDay()) == Pregnancy.Term.LATE; }, () -> {
            test.await("pause: the game sees the late term", 6, () -> false, () -> {
                if (carrier == Pregnancy.Carrier.VILLAGER) {
                    VillagerEntity w = entity(spouse);
                    var speed = w == null ? null : w.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
                    test.check(speed != null && speed.hasModifier(Identifier.of("mobtalk", "pregnancy_slow")), "late in the term she walks slower");
                }
                test.check(ChildView.status(carrierId()).contains("скоро роды"), "the last day says «скоро роды»: " + ChildView.status(carrierId()));
                birth();
            });
        });
    }

    /** Роды: схватки, затемнение у игрока рядом, малыш в дереве семьи, беременность закончена. */
    private void birth() {
        int childrenBefore = test.bridge().memory.children().overview(playerId(), 0, 0, System.currentTimeMillis()).children().size();
        test.command("time add " + DAY);
        test.await("labour began", 15, () -> { ChildLedger.Pregnant p = pregnant(); return p != null && p.news() >= 4; }, () ->
            test.await("the baby is born", 80, () -> test.bridge().memory.children().overview(playerId(), 0, 0, System.currentTimeMillis()).children().size() > childrenBefore, () -> {
                ChildController.Fade fade = children().fade(playerId());
                test.check(fade != null && fade.kind() == ChildController.FadeKind.BIRTH, "the birth fades the screen of the player nearby");
                ChildLedger.Overview o = test.bridge().memory.children().overview(playerId(), 0, world().getTime(), System.currentTimeMillis());
                test.check(o.children().size() > childrenBefore, "the baby is in the family tree: " + o.children().stream().map(c -> c.word() + " " + c.stage()).toList());
                for (ChildLedger.Child c : o.children()) babies.add(c.id());
                test.check(pregnant() == null, "the pregnancy is over");
                test.check(o.children().stream().anyMatch(c -> "младенец".equals(c.stage())), "the newborn is an infant");
                test.await("pause: the fade at birth", 1, () -> false, () -> {
                    ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, "mobtalk-family-birth.png", test.owner.client.getFramebuffer(), text -> { });
                    test.await("pause: the fade clears", 7, () -> false, () -> shot("mobtalk-family-baby.png", false, this::finish));
                });
            }));
    }

    /** Снимок с экрана как есть (HUD с подписями оставлен); {@code thirdPerson} — игрок спереди. */
    private void shot(String file, boolean thirdPerson, Runnable then) {
        if (thirdPerson) test.owner.client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
        test.await("pause: the camera settles", 2, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            if (thirdPerson) test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
            test.await("pause: after the screenshot", 1, () -> false, then);
        });
    }

    private void finish() {
        if (saved != null) test.bridge().config = saved;
        for (UUID id : babies) { VillagerEntity baby = entity(id); if (baby != null) baby.discard(); }
        test.command("kill @e[tag=" + TAG + "]");
        test.command("fill " + at(-8, 0, -8) + " " + at(8, 5, 8) + " minecraft:air");
        test.command("time set day");
        test.command("tp " + name() + " " + (fx + 60) + " " + fy + " " + fz);
        test.await("pause: track N stage cleaned up", 2, () -> false, next);
    }
}
