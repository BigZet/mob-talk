package io.github.mobtalk.client;

import io.github.mobtalk.client.human.HumanLooks;
import io.github.mobtalk.client.human.HumanTextures;
import io.github.mobtalk.core.Gender;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.SocialProfile;
import io.github.mobtalk.game.MercantileController;
import java.util.*;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.math.Box;

/** Самотест меркантильных горожанок ({@code -Dmobtalk.selftest.only=glam}): все жительницы на площадке меркантильны (доля в деревне
 * на время — 100%), игра сама даёт им сумочки, клиент одевает их в наряды {@code clothing/glam}; мужчины остаются в рабочем.
 * Снимки спереди, вблизи и сбоку. ИИ и голос не нужны. */
final class SelfTestGlam {
    private static final String TAG = "mobtalk_glam";
    private static final int COUNT = 18;
    private final SelfTest test;
    private final int fx, fy, fz;
    private Runnable next = () -> { };
    private MobTalkConfig original;

    SelfTestGlam(SelfTest test, int stageX, int stageY, int stageZ) { this.test = test; this.fx = stageX - 90; this.fy = stageY; this.fz = stageZ + 40; }

    private String name() { return test.owner.client.player.getName().getString(); }
    private String at(int dx, int dy, int dz) { return (fx + dx) + " " + (fy + dy) + " " + (fz + dz); }
    private List<VillagerEntity> villagers() {
        Box box = new Box(fx - 14, fy - 2, fz - 9, fx + 14, fy + 6, fz + 9);
        return test.owner.client.world.getEntitiesByClass(VillagerEntity.class, box, v -> v.isAlive() && v.isAiDisabled());
    }
    private static boolean woman(VillagerEntity v) { return SocialProfile.genderOf(v.getUuid()) == Gender.FEMALE; }

    void run(Runnable then) {
        next = then;
        test.log("glam stages: mercantile city women get handbags and glam outfits");
        original = test.owner.config().copy();
        MobTalkConfig draft = original.copy();
        draft.humanVillagers = true; draft.mercantile = true; draft.mercHandbag = true; draft.mercVillagePercent = 100; draft.mercCityPercent = 100;
        test.owner.saveConfig(draft, message -> { if (message != null) test.fail("could not switch the mercantile share: " + message); });
        test.await("settings: every woman mercantile", 30, () -> test.owner.config().mercVillagePercent == 100 && !test.owner.reloading, this::stage);
    }

    private void stage() {
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.command("time set 6000"); test.command("weather clear");
        test.command("effect give " + name() + " minecraft:slow_falling 20 1 true");
        test.command("tp " + name() + " " + at(0, 0, 7) + " 180 10");
        test.await("pause: the stage chunks load", 3, () -> false, () -> {
            test.command("fill " + at(-14, -1, -8) + " " + at(14, -1, 8) + " minecraft:polished_andesite");
            test.command("fill " + at(-14, 0, -8) + " " + at(14, 6, 8) + " minecraft:air");
            for (String wall : List.of(at(-14, 0, -8) + " " + at(14, 1, -8), at(-14, 0, 8) + " " + at(14, 1, 8), at(-14, 0, -8) + " " + at(-14, 1, 8), at(14, 0, -8) + " " + at(14, 1, 8)))
                test.command("fill " + wall + " minecraft:barrier");
            test.command("tp " + name() + " " + at(0, 0, 7) + " 180 10");
            for (int i = 0; i < COUNT; i++)
                test.command("summon minecraft:villager " + at(-12 + (i % 9) * 3, 0, i < 9 ? -4 : 0) + " {NoAI:1b,PersistenceRequired:1b,Rotation:[0f,0f],Tags:[\"" + TAG
                        + "\"],VillagerData:{profession:\"minecraft:none\",type:\"minecraft:plains\",level:1}}");
            test.await("pause: the villagers are summoned", 40, () -> villagers().size() >= COUNT - 3, this::bags);
        });
    }

    /** Игра раз в пять секунд решает, кто меркантильна, и даёт сумочку: ждём, пока у всех женщин она в руке. */
    private void bags() {
        test.command("effect give @e[tag=" + TAG + "] minecraft:resistance 600 255 true");
        test.await("every woman got a handbag", 20, () -> villagers().stream().filter(SelfTestGlam::woman)
                .allMatch(v -> MercantileController.handbag(v.getEquippedStack(EquipmentSlot.OFFHAND))), () -> {
            List<VillagerEntity> all = villagers();
            long women = all.stream().filter(SelfTestGlam::woman).count();
            long withBags = all.stream().filter(v -> MercantileController.handbag(v.getEquippedStack(EquipmentSlot.OFFHAND))).count();
            test.check(women > 0, "women on the stage: " + women + " of " + all.size());
            test.check(withBags == women, "every woman, and no man, carries a handbag: " + withBags + "/" + women);
            Set<String> outfits = new TreeSet<>();
            for (VillagerEntity v : all) {
                if (!woman(v)) { test.check(HumanLooks.of(v).outfit().isEmpty(), "a man keeps his work clothes"); continue; }
                test.check(HumanLooks.of(v).outfit().equals("glam"), "a woman with a handbag dresses up");
                String clothes = HumanTextures.clothes(HumanLooks.of(v)).getPath();
                test.check(clothes.contains("clothing/glam/"), "glam clothes: " + clothes);
                outfits.add(clothes);
            }
            test.log("glam outfits worn: " + outfits);
            test.owner.client.options.hudHidden = true;
            shot("mobtalk-glam-lineup.png", () -> close(() -> side(this::finish)));
        });
    }

    private void close(Runnable then) {
        test.command("tp " + name() + " " + at(0, 0, 2) + " 180 15");
        test.await("pause: close view", 2, () -> false, () -> shot("mobtalk-glam-close.png", then));
    }
    private void side(Runnable then) {
        test.command("tp " + name() + " " + at(13, 1, 3) + " 120 15");
        test.await("pause: side view", 2, () -> false, () -> shot("mobtalk-glam-side.png", () -> bag(then)));
    }
    /** Сумочка вблизи: игрок встаёт перед жительницей с сумочкой, в профиль к её левой руке. */
    private void bag(Runnable then) {
        VillagerEntity lady = villagers().stream().filter(v -> MercantileController.handbag(v.getEquippedStack(EquipmentSlot.OFFHAND))).findFirst().orElse(null);
        if (lady == null) { then.run(); return; }
        test.command("tp " + name() + " " + (lady.getX() + 1.4) + " " + (lady.getY() + 0.1) + " " + (lady.getZ() + 1.4) + " 135 25");
        test.await("pause: the handbag close up", 2, () -> false, () -> shot("mobtalk-glam-bag.png", then));
    }

    private void shot(String file, Runnable then) {
        test.await("pause: the camera settles", 2, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, then);
        });
    }

    private void finish() {
        test.owner.client.options.hudHidden = false;
        test.command("kill @e[tag=" + TAG + "]");
        test.command("fill " + at(-14, 0, -8) + " " + at(14, 6, 8) + " minecraft:air");
        test.owner.saveConfig(original, message -> { if (message != null) test.fail("could not restore the settings: " + message); });
        test.await("settings restored", 30, () -> test.owner.config().mercVillagePercent == original.mercVillagePercent && !test.owner.reloading, next);
    }
}
