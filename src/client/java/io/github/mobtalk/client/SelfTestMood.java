package io.github.mobtalk.client;

import io.github.mobtalk.core.MobState;
import io.github.mobtalk.core.MoodSpell;
import io.github.mobtalk.net.MobStateS2C;
import java.util.*;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** Villagers' moods ({@code -Only mood}): three acquainted villagers on a floor in the sky. A thunderstorm and a zombie nearby
 * become reasons the store knows (the world watch, every ten seconds); a death in the family brings grief, three threats dread,
 * a romance love — and each long mood shows on the nameplate. Then the info screen of the grieving one. Screenshots in
 * screenshots. No LLM, no voice. */
final class SelfTestMood {
    private static final String TAG = "mobtalk_mood";
    private final SelfTest test;
    private final int fx, fy, fz;
    private final List<UUID> villagers = new ArrayList<>();
    private Runnable next = () -> { };

    SelfTestMood(SelfTest test, int stageX, int stageY, int stageZ) { this.test = test; this.fx = stageX + 300; this.fy = stageY; this.fz = stageZ; }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private String at(int dx, int dy, int dz) { return (fx + dx) + " " + (fy + dy) + " " + (fz + dz); }
    private <T> T onServer(java.util.function.Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }
    private MobState.View state(int i) { return test.bridge().memory.stateView(villagers.get(i), test.owner.client.player.getUuid()); }
    private boolean reason(int i, String text) { MobState.View v = state(i); return v != null && v.reasons().stream().anyMatch(r -> r.text().startsWith(text)); }
    private String label(int i) { MobStateS2C s = test.owner.views.state(villagers.get(i)); return s == null ? "" : s.word(); }

    void run(Runnable then) {
        next = then;
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.log("mood stages: the world as reasons, long moods on the nameplates");
        test.command("time set 1000"); test.command("weather clear");
        test.command("gamerule doDaylightCycle false"); test.command("gamerule doWeatherCycle false"); test.command("gamerule doMobSpawning false");
        test.command("gamemode creative " + name());
        test.command("effect give " + name() + " minecraft:slow_falling 600 1 true");
        test.command("tp " + name() + " " + at(0, 0, 6) + " 180 10");
        test.await("pause: the stage chunks load", 3, () -> false, this::build);
    }

    private void build() {
        test.command("fill " + at(-10, -1, -10) + " " + at(10, -1, 10) + " minecraft:grass_block");
        test.command("fill " + at(-10, 0, -10) + " " + at(10, 5, 10) + " minecraft:air");
        String[] jobs = { "farmer", "librarian", "cleric" };
        for (int i = 0; i < jobs.length; i++)
            test.command("summon minecraft:villager " + at(-3 + i * 3, 0, 0) + " {PersistenceRequired:1b,NoAI:1b,Rotation:[0f,0f],Tags:[\"" + TAG + "\"],VillagerData:{profession:\"minecraft:"
                    + jobs[i] + "\",type:\"minecraft:plains\",level:2},Xp:20}");
        test.await("three villagers on the stage", 20, () -> {
            List<VillagerEntity> found = onServer(() -> world().getEntitiesByClass(VillagerEntity.class, new Box(new BlockPos(fx, fy, fz)).expand(8), e -> e.getCommandTags().contains(TAG)));
            if (found.size() < 3) return false;
            villagers.clear();
            found.stream().sorted(Comparator.comparingDouble(VillagerEntity::getX)).forEach(v -> villagers.add(v.getUuid()));
            return true;
        }, () -> {
            long now = System.currentTimeMillis();
            for (UUID id : villagers) {
                test.bridge().memory.recordEvent(id, "minecraft:villager", "Житель", "Самотест: знакомство (настроение)", now);
                test.bridge().memory.setBackstory(id, "", "Ты живёшь в маленькой деревне в небе (самотест).", "", "", "");
            }
            test.command("tp " + name() + " " + at(0, 0, 6) + " 180 10");
            test.await("pause: the player stands on the stage", 2, () -> false, this::storm);
        });
    }

    // The thunderstorm: the world watch makes it a lasting reason, and the nerves go down.
    private void storm() {
        int calm = state(0).calm();
        test.command("weather thunder");
        test.await("the storm is a reason", 30, () -> reason(0, "гроза"), () -> {
            test.check(reason(0, "гроза"), "the storm is among the reasons: " + state(0).reasons());
            test.check(state(0).calm() < calm, "the storm frays the nerves (" + calm + " → " + state(0).calm() + ")");
            shot("mobtalk-mood-1-storm.png", this::monster);
        });
    }

    // A zombie beside them (frozen): "monsters near".
    private void monster() {
        test.command("summon minecraft:zombie " + at(0, 0, -4) + " {NoAI:1b,PersistenceRequired:1b,Tags:[\"" + TAG + "\"]}");
        test.await("the zombie is a reason", 30, () -> reason(1, "монстры рядом"), () -> {
            test.check(reason(1, "монстры рядом"), "a monster near is a reason: " + state(1).reasons());
            test.command("kill @e[type=minecraft:zombie,tag=" + TAG + "]");
            test.command("weather clear");
            spells();
        });
    }

    // Long moods: grief after a death in the family, dread after three threats, love after a romance; each on the nameplate.
    private void spells() {
        long now = System.currentTimeMillis();
        var memory = test.bridge().memory;
        memory.recordMood(villagers.get(0), MobState.Event.BEREAVED, "Ольга", now);
        for (int i = 0; i < 3; i++) memory.recordMood(villagers.get(1), MobState.Event.THREAT, name(), now + i);
        memory.recordMood(villagers.get(2), MobState.Event.ROMANCE, name(), now);
        test.check(state(0).spell() == MoodSpell.GRIEF, "a death in the family brings grief: " + state(0));
        test.check(state(1).spell() == MoodSpell.DREAD, "three threats bring dread: " + state(1));
        test.check(state(2).spell() == MoodSpell.LOVE, "a romance brings love: " + state(2));
        test.check(state(1).refusesDeals() && !state(0).keen(), "the scared one trades with nobody, the grieving one does not come up");
        test.log("prompt block of the grieving one: " + state(0).describe().replace('\n', ' '));
        test.await("the nameplates show the long moods", 30, () -> "grief".equals(label(0)) && "dread".equals(label(1)) && "in_love".equals(label(2)), () -> {
            test.check("grief".equals(label(0)) && "dread".equals(label(1)) && "in_love".equals(label(2)),
                    "nameplates: " + label(0) + ", " + label(1) + ", " + label(2));
            shot("mobtalk-mood-2-nameplates.png", this::info);
        });
    }

    // The info screen of the grieving villager: the long mood, what helps, the reasons.
    private void info() {
        MobEntity mob = null;
        for (var entity : test.owner.client.world.getEntities())
            if (entity instanceof MobEntity m && m.getUuid().equals(villagers.get(0))) { mob = m; break; }
        if (mob == null) { test.fail("the grieving villager is not on the client"); finish(); return; }
        test.owner.select(mob);
        test.owner.openInfo(null);
        test.await("pause: the info loads", 4, () -> false, () -> shot("mobtalk-mood-3-info.png", () -> {
            test.owner.client.setScreen(null);
            finish();
        }));
    }

    private void shot(String file, Runnable then) {
        test.await("pause: the camera settles", 2, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, then);
        });
    }

    private void finish() {
        test.command("kill @e[tag=" + TAG + "]");
        test.command("gamerule doDaylightCycle true"); test.command("gamerule doWeatherCycle true");
        next.run();
    }
}
