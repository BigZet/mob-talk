package io.github.mobtalk.client;

import io.github.mobtalk.client.human.HumanLooks;
import io.github.mobtalk.client.human.HumanTextures;
import io.github.mobtalk.core.Gender;
import io.github.mobtalk.core.Gesture;
import io.github.mobtalk.core.GestureIntent;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.SocialProfile;
import io.github.mobtalk.game.GlamLook;
import java.util.*;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.math.Box;

/** Самотест меркантильных горожанок ({@code -Dmobtalk.selftest.only=glam}): все жительницы на площадке меркантильны (доля в деревне
 * на время — 100%), игра сама ставит им флаг наряда, клиент одевает их в {@code clothing/glam}; мужчины остаются в рабочем.
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
        test.log("glam stages: mercantile city women get glam outfits");
        original = test.owner.config().copy();
        MobTalkConfig draft = original.copy();
        draft.humanVillagers = true; draft.mercantile = true; draft.mercOutfit = true; draft.mercVillagePercent = 100; draft.mercCityPercent = 100;
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

    /** Игра раз в пять секунд решает, кто меркантильна, и ставит флаг наряда: ждём, пока он дойдёт до клиента у всех женщин. */
    private void bags() {
        test.command("effect give @e[tag=" + TAG + "] minecraft:resistance 600 255 true");
        test.await("every woman dressed up", 20, () -> villagers().stream().filter(SelfTestGlam::woman).allMatch(GlamLook::of), () -> {
            List<VillagerEntity> all = villagers();
            long women = all.stream().filter(SelfTestGlam::woman).count();
            long glam = all.stream().filter(GlamLook::of).count();
            test.check(women > 0, "women on the stage: " + women + " of " + all.size());
            test.check(glam == women, "every woman, and no man, is dressed up: " + glam + "/" + women);
            Set<String> outfits = new TreeSet<>();
            for (VillagerEntity v : all) {
                if (!woman(v)) { test.check(HumanLooks.of(v).outfit().isEmpty(), "a man keeps his work clothes"); continue; }
                test.check(HumanLooks.of(v).outfit().equals("glam"), "a mercantile woman wears glam");
                test.check(v.getEquippedStack(EquipmentSlot.OFFHAND).isEmpty(), "nothing in her hands");
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
        test.await("pause: side view", 2, () -> false, () -> shot("mobtalk-glam-side.png", () -> courtship(then)));
    }

    /** Ухаживание: игрок встречается с меркантильной уже час и ничего не дарил. Она сама клянчит (это «годовщина»), а через минуту
     * уходит к мастеру-оружейнику рядом и выходит за него. */
    private void courtship(Runnable then) {
        test.owner.client.options.hudHidden = false;
        VillagerEntity lady = villagers().stream().filter(GlamLook::of).findFirst().orElse(null);
        if (lady == null) { test.fail("no mercantile woman for the courtship stage"); then.run(); return; }
        UUID her = lady.getUuid(), player = test.owner.client.player.getUuid();
        UUID rich = UUID.randomUUID();
        while (SocialProfile.genderOf(rich) != Gender.MALE) rich = UUID.randomUUID();
        merchant = rich;
        long hour = (test.owner.config().mercLeaveMinutes + 1) * 60_000L;
        test.bridge().memory.social().applyGesture(her, player, name(), new GestureIntent(Gesture.DATE, Gesture.Outcome.ACCEPTED, 5, "minecraft:poppy", ""),
                System.currentTimeMillis() - hour);
        test.check(test.bridge().memory.jealousy().partner(her, player), "the player dates a mercantile woman");
        test.command("summon minecraft:villager " + at(0, 0, 4) + " {NoAI:1b,PersistenceRequired:1b,Tags:[\"" + TAG + "\"],UUID:" + uuid(rich)
                + ",VillagerData:{profession:\"minecraft:weaponsmith\",type:\"minecraft:plains\",level:5}}");
        test.command("tp " + her + " " + at(0, 0, 3));
        test.command("tp " + name() + " " + at(0, 0, 6) + " 180 10");
        test.log("courtship: " + test.bridge().memory.displayName(her) + " dates the player for an hour without gifts; merchant " + rich);
        int before = test.bridge().memory.relationship(her, player);
        test.await("she begs for a gift herself (the anniversary)", 15, () -> test.bridge().memory.mercantileBegAt(her, player) >= 0, () ->
                test.await("she leaves the stingy player for the master weaponsmith", 90, () -> merchant.equals(test.bridge().memory.spouseOf(her)), () -> {
                    test.check(!test.bridge().memory.jealousy().partner(her, player), "the stingy player is not her partner any more");
                    int after = test.bridge().memory.relationship(her, player);
                    test.check(after < before, "she thinks less of him: " + before + " → " + after);
                    then.run();
                }));
    }
    private UUID merchant;
    private static String uuid(UUID id) {
        long m = id.getMostSignificantBits(), l = id.getLeastSignificantBits();
        return "[I;" + (int) (m >> 32) + "," + (int) m + "," + (int) (l >> 32) + "," + (int) l + "]";
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
