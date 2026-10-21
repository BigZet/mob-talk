package io.github.mobtalk.client;

import io.github.mobtalk.client.game.WeddingItems;
import io.github.mobtalk.client.human.HumanLook;
import io.github.mobtalk.client.human.HumanLooks;
import io.github.mobtalk.client.human.HumanTextures;
import io.github.mobtalk.client.human.VillagerLooks;
import io.github.mobtalk.core.SocialProfile;
import java.util.*;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

/** Этапы самотеста трека M ({@code -Only looks}): ряд жителей разных профессий, климатов и возрастов в облике людей — спереди и
 * в профиль; пара в фате и венце с цветами в руках; спящий житель; тот же ряд ванильной моделью после выключения в F7.
 * Проверяет, что у каждого есть кожа, лицо, волосы и одежда; остальное — снимки экрана в screenshots. ИИ и голос не нужны. */
final class SelfTestLooks {
    private static final String TAG = "mobtalk_m";
    private static final String[][] LINEUP = {
            { "farmer", "plains" }, { "librarian", "desert" }, { "cleric", "snow" }, { "armorer", "taiga" }, { "fisherman", "savanna" },
            { "none", "jungle" }, { "nitwit", "swamp" }, { "shepherd", "plains" }, { "butcher", "desert" }, { "cartographer", "snow" } };
    private final SelfTest test;
    private final int fx, fy, fz;
    private Runnable next = () -> { };

    SelfTestLooks(SelfTest test, int stageX, int stageY, int stageZ) { this.test = test; this.fx = stageX - 90; this.fy = stageY; this.fz = stageZ; }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private String at(int dx, int dy, int dz) { return (fx + dx) + " " + (fy + dy) + " " + (fz + dz); }
    private List<VillagerEntity> tagged() {
        return world().getEntitiesByClass(VillagerEntity.class, new Box(fx - 12, fy - 2, fz - 9, fx + 12, fy + 6, fz + 9), v -> v.isAlive() && v.getCommandTags().contains(TAG));
    }
    private List<VillagerEntity> clientTagged() {
        Set<UUID> ids = new HashSet<>(); for (VillagerEntity v : tagged()) ids.add(v.getUuid());
        return test.owner.client.world.getEntitiesByClass(VillagerEntity.class, test.owner.client.player.getBoundingBox().expand(24), v -> ids.contains(v.getUuid()));
    }

    void run(Runnable then) {
        next = then;
        test.log("looks stages (track M): human villagers, wedding attire, sleep, the vanilla fallback");
        test.owner.config().humanVillagers = true;
        test.command("fill " + at(-12, -1, -8) + " " + at(12, -1, 8) + " minecraft:smooth_stone");
        test.command("fill " + at(-12, 0, -8) + " " + at(12, 6, 8) + " minecraft:air");
        for (String wall : List.of(at(-12, 0, -8) + " " + at(12, 1, -8), at(-12, 0, 8) + " " + at(12, 1, 8), at(-12, 0, -8) + " " + at(-12, 1, 8), at(12, 0, -8) + " " + at(12, 1, 8)))
            test.command("fill " + wall + " minecraft:barrier"); // Только стены: в полу из барьера жители задохнулись бы.
        test.command("time set 6000"); test.command("weather clear");
        test.command("tp " + name() + " " + at(0, 0, 6) + " 180 10");
        test.await("pause: the stage chunks load", 3, () -> false, () -> {
            for (int i = 0; i < LINEUP.length; i++)
                test.command("summon minecraft:villager " + at(-9 + i * 2, 0, -3) + " {NoAI:1b,PersistenceRequired:1b,Rotation:[0f,0f],Tags:[\"" + TAG
                        + "\"],VillagerData:{profession:\"minecraft:" + LINEUP[i][0] + "\",type:\"minecraft:" + LINEUP[i][1] + "\",level:2}}");
            for (int i = 0; i < 3; i++)
                test.command("summon minecraft:villager " + at(-2 + i * 2, 0, -1) + " {NoAI:1b,PersistenceRequired:1b,Age:-24000,Rotation:[0f,0f],Tags:[\"" + TAG
                        + "\"],VillagerData:{profession:\"minecraft:none\",type:\"minecraft:plains\",level:1}}");
            test.await("thirteen villagers for track M", 40, () -> clientTagged().size() >= LINEUP.length + 3, this::lineup);
        });
    }

    private void lineup() {
        if (clientTagged().isEmpty()) { test.fail("no villagers on the stage"); finish(); return; }
        test.check(test.owner.client.getEntityRenderDispatcher().getRenderer(clientTagged().getFirst()) instanceof VillagerLooks, "villagers are drawn by the switchable renderer");
        test.command("effect give @e[tag=" + TAG + "] minecraft:resistance 600 255 true");
        test.await("pause: the looks settle", 2, () -> false, () -> {
            if (clientTagged().isEmpty()) { test.fail("the lineup is gone"); finish(); return; }
            test.check(VillagerLooks.human(test.owner.client.getEntityRenderDispatcher().getRenderer(clientTagged().getFirst())), "the human model is on");
            int complete = 0, female = 0, babies = 0;
            for (VillagerEntity v : clientTagged()) {
                HumanLook look = HumanLooks.of(v);
                boolean ok = HumanTextures.face(look) != null && HumanTextures.hair(look) != null && HumanTextures.clothes(look) != null;
                if (ok) complete++;
                if (look.female()) female++;
                if (v.isBaby()) babies++;
                test.log("villager " + v.getUuid() + ": " + look.gender() + " " + look.profession() + " " + look.age() + " skin " + HumanTextures.skin(look).getPath()
                        + " hair " + HumanTextures.hair(look) + " clothes " + HumanTextures.clothes(look) + " skin #" + Integer.toHexString(HumanTextures.skinColor(look))
                        + " hair #" + Integer.toHexString(HumanTextures.hairColor(look)) + " eyes #" + Integer.toHexString(HumanTextures.eyeColor(look)));
                test.check(look.female() == (SocialProfile.genderOf(v.getUuid()) == io.github.mobtalk.core.Gender.FEMALE), "the look has the villager's sex from the social layer");
            }
            test.check(complete == clientTagged().size(), "every villager has a face, hair and clothes (" + complete + "/" + clientTagged().size() + ")");
            test.check(babies == 3, "three children among them");
            test.log("women " + female + " of " + clientTagged().size());
            test.owner.client.options.hudHidden = true;
            test.check(clientTagged().stream().noneMatch(v -> !HumanLooks.of(v).outfit().isEmpty()), "nobody wears a wedding outfit before the attire");
            shot("mobtalk-looks-lineup.png", () -> side(() -> wedding(this::sleep)));
        });
    }

    /** Вполоборота: игрок сбоку и чуть выше ряда. */
    private void side(Runnable then) {
        test.command("tp " + name() + " " + at(10, 1, 2) + " 125 15");
        test.await("pause: side view", 2, () -> false, () -> shot("mobtalk-looks-side.png", () -> { test.command("tp " + name() + " " + at(0, 0, 6) + " 180 10"); then.run(); }));
    }

    /** Фата и венец (как их надевает свадьба трека L) и цветы в руках у двоих посередине ряда; снимок вблизи. */
    private void wedding(Runnable then) {
        VillagerEntity bride = null, groom = null, maid = null;
        for (VillagerEntity v : tagged()) {
            if (v.isBaby()) continue;
            boolean woman = SocialProfile.genderOf(v.getUuid()) == io.github.mobtalk.core.Gender.FEMALE;
            if (woman && bride == null) bride = v; else if (woman && maid == null) maid = v; else if (!woman && groom == null) groom = v;
        }
        if (maid != null) { VillagerEntity w = maid; test.server().execute(() -> { // Подружка в венке: праздничное платье по убору.
            w.equipStack(EquipmentSlot.HEAD, new ItemStack(WeddingItems.FLOWER_WREATH)); w.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.ALLIUM));
            w.refreshPositionAndAngles(fx - 2.5, fy, fz + 1.5, 0, 0); }); }
        if (bride == null || groom == null) { test.fail("no bride and groom among the lineup"); then.run(); return; }
        VillagerEntity b = bride, g = groom;
        test.server().execute(() -> {
            b.equipStack(EquipmentSlot.HEAD, new ItemStack(WeddingItems.BRIDAL_VEIL)); b.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.PEONY));
            g.equipStack(EquipmentSlot.HEAD, new ItemStack(WeddingItems.WEDDING_CROWN)); g.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.POPPY));
            b.refreshPositionAndAngles(fx - 0.5, fy, fz + 2.5, 0, 0); g.refreshPositionAndAngles(fx + 1.5, fy, fz + 2.5, 0, 0);
            b.setHeadYaw(0); b.setBodyYaw(0); g.setHeadYaw(0); g.setBodyYaw(0);
        });
        test.command("tp " + name() + " " + at(0, 0, 5) + " 180 15");
        test.await("pause: the couple dressed", 3, () -> false, () -> {
            for (VillagerEntity v : clientTagged()) if (!HumanLooks.of(v).outfit().isEmpty()) test.log("outfit " + HumanLooks.of(v).outfit() + ": " + HumanTextures.clothes(HumanLooks.of(v)));
            test.check(clientTagged().stream().anyMatch(v -> HumanLooks.of(v).outfit().equals("bride") && HumanTextures.clothes(HumanLooks.of(v)).getPath().contains("wedding/bride")), "the bride wears a wedding dress");
            test.check(clientTagged().stream().anyMatch(v -> HumanLooks.of(v).outfit().equals("groom") && HumanTextures.clothes(HumanLooks.of(v)).getPath().contains("wedding/groom")), "the groom wears a suit");
            shot("mobtalk-looks-wedding.png", () -> {
            test.owner.client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
            test.command("tp " + name() + " " + at(0, 0, 4) + " 0 15");
            test.await("pause: the couple from behind", 2, () -> false, () -> shot("mobtalk-looks-wedding-back.png", () -> {
                test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
                then.run();
            }));
            });
        });
    }

    /** Житель в кровати: поза сна ванильная, глаза закрыты. */
    private void sleep() {
        test.command("setblock " + at(6, 0, 3) + " minecraft:red_bed[facing=north,part=foot]");
        test.command("setblock " + at(6, 0, 2) + " minecraft:red_bed[facing=north,part=head]");
        VillagerEntity sleeper = tagged().stream().filter(v -> !v.isBaby() && v.getEquippedStack(EquipmentSlot.HEAD).isEmpty()).findFirst().orElse(null);
        test.await("pause: the bed is placed", 1, () -> false, () -> {
            if (sleeper != null) test.server().execute(() -> sleeper.sleep(new net.minecraft.util.math.BlockPos(fx + 6, fy, fz + 2)));
            test.command("tp " + name() + " " + at(8, 1, 5) + " 135 30");
            test.await("a villager sleeps", 5, () -> sleeper != null && sleeper.isSleeping(), () -> {
                test.check(sleeper != null && sleeper.isSleeping(), "the villager lies in the bed");
                shot("mobtalk-looks-sleep.png", this::belly);
            });
        });
    }

    /** Живот трека N на человеке: у одной жительницы срок как в конце, снимок в профиль; позже тот же живот на ванильной модели. */
    private UUID mother;
    private void belly() {
        mother = tagged().stream().filter(v -> !v.isBaby() && v.getEquippedStack(EquipmentSlot.HEAD).isEmpty() && !v.isSleeping()
                && SocialProfile.genderOf(v.getUuid()) == io.github.mobtalk.core.Gender.FEMALE).map(VillagerEntity::getUuid).findFirst().orElse(null);
        if (mother == null) { test.fail("no woman left for the belly"); vanilla(); return; }
        ChildView.forceBelly(Map.of(mother, 1f));
        test.command("tp " + mother + " " + (fx + 0.5) + " " + fy + " " + (fz + 4.5) + " 0 0"); // Лицом на юг.
        test.command("tp " + name() + " " + (fx + 3.5) + " " + fy + " " + (fz + 4.5) + " 90 10"); // Сбоку от неё: она смотрит на юг, игрок на запад.
        test.await("pause: the camera beside her", 2, () -> false, () -> shot("mobtalk-looks-belly.png", this::vanilla));
    }

    /** Выключено в F7 — тот же ряд ванильной моделью, без перезагрузки. */
    private void vanilla() {
        test.command("tp " + name() + " " + at(0, 0, 6) + " 180 10");
        test.owner.config().humanVillagers = false;
        test.await("the vanilla model is back", 3, () -> !HumanLooks.enabled, () -> {
            test.check(!clientTagged().isEmpty() && !VillagerLooks.human(test.owner.client.getEntityRenderDispatcher().getRenderer(clientTagged().getFirst())), "turned off in F7, villagers are vanilla again");
            shot("mobtalk-looks-vanilla.png", () -> {
                ChildView.forceBelly(Map.of());
                test.owner.config().humanVillagers = true;
                test.await("the human model is back", 3, () -> HumanLooks.enabled, this::finish);
            });
        });
    }

    private void shot(String file, Runnable then) {
        test.await("pause: the camera settles", 2, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, then);
        });
    }

    private void finish() {
        ChildView.forceBelly(Map.of());
        test.owner.client.options.hudHidden = false;
        test.command("kill @e[tag=" + TAG + "]");
        test.command("fill " + at(-12, 0, -8) + " " + at(12, 6, 8) + " minecraft:air");
        test.command("tp " + name() + " " + (fx + 90) + " " + fy + " " + fz);
        test.await("pause: track M stage cleaned up", 2, () -> false, next);
    }
}
