package io.github.mobtalk.client;

import io.github.mobtalk.core.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** Этап самотеста трека J без LLM: три жителя на площадке, двое одного пола становятся парой игрока и соперником; разговор
 * с соперником на глазах у пары (через ту же доставку ответа, что и в игре) даёт ревность; ночью игрок ложится в кровать, а пара
 * ложится в свободную кровать рядом в ванильной позе, отношение растёт, ревность гаснет. Утренняя реплика уходит в очередь
 * инициативы и здесь не ждётся. */
final class SelfTestJealousy {
    private static final String TAG = "mobtalk_j";
    private final SelfTest test;
    private final int sx, sy, sz;
    private UUID wife, rival;
    private Runnable next = () -> { };

    SelfTestJealousy(SelfTest test, int stageX, int stageY, int stageZ) { this.test = test; this.sx = stageX; this.sy = stageY; this.sz = stageZ; }

    private String at(int dx, int dz) { return (sx + dx) + " " + sy + " " + (sz + dz); }
    private UUID playerId() { return test.owner.client.player.getUuid(); }
    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private VillagerEntity entity(UUID id) { return id != null && world().getEntity(id) instanceof VillagerEntity v ? v : null; }

    void run(Runnable then) {
        next = then;
        test.log("jealousy stages (track J): a rival seen, a night together");
        for (int dx : new int[]{-6, -4, -2}) test.command("summon minecraft:villager " + at(dx, 4) + " {PersistenceRequired:1b,Tags:[\"" + TAG + "\"]}");
        test.await("three villagers for track J", 10, () -> tagged().size() >= 3, this::pair);
    }
    private List<VillagerEntity> tagged() {
        return world().getEntitiesByClass(VillagerEntity.class, new Box(sx - 12, sy - 2, sz - 12, sx + 12, sy + 6, sz + 12), v -> v.isAlive() && v.getCommandTags().contains(TAG));
    }

    /** Из трёх двое всегда одного пола: первая — пара игрока, вторая — соперница (или соперник). */
    private void pair() {
        List<VillagerEntity> three = new ArrayList<>(tagged());
        for (int i = 0; i < three.size() && wife == null; i++)
            for (int j = i + 1; j < three.size() && wife == null; j++)
                if (SocialProfile.genderOf(three.get(i).getUuid()) == SocialProfile.genderOf(three.get(j).getUuid())) { wife = three.get(i).getUuid(); rival = three.get(j).getUuid(); }
        if (wife == null) { test.fail("no two villagers of one sex among three"); finish(); return; }
        long now = System.currentTimeMillis();
        for (UUID id : List.of(wife, rival)) test.bridge().memory.recordEvent(id, "minecraft:villager", "Житель", "Самотест: знакомство (трек J)", now);
        test.bridge().memory.social().applyGesture(wife, playerId(), name(), new GestureIntent(Gesture.DATE, Gesture.Outcome.ACCEPTED, 5, "minecraft:poppy", ""), now);
        test.check(test.bridge().memory.jealousy().partner(wife, playerId()), "the first villager now dates the player");
        test.command("tp " + wife + " " + at(-4, 1)); test.command("tp " + rival + " " + at(-2, 1));
        test.bridge().attend(wife, playerId(), 60);
        test.await("pause: the pair stands two blocks apart", 2, () -> false, this::talkToRival);
    }

    /** Ответ соперника доставляется тем же путём, что в игре: пара видит разговор и ревнует. */
    private void talkToRival() {
        String dimension = world().getRegistryKey().getValue().toString();
        int before = test.bridge().memory.jealousy().level(wife, playerId(), System.currentTimeMillis());
        var applied = test.bridge().capture(rival, playerId(), dimension)
                .thenCompose(snapshot -> test.bridge().apply(rival, playerId(), dimension, ActionIntent.none(), snapshot, -1, () -> true));
        test.await("reply of the rival delivered", 20, applied::isDone, () -> {
            if (applied.isCompletedExceptionally()) test.fail("rival delivery failed: " + applied.exceptionNow().getMessage());
            int after = test.bridge().memory.jealousy().level(wife, playerId(), System.currentTimeMillis());
            test.check(after > before, "talking with a same-sex villager in sight makes the partner jealous (" + before + " → " + after + ")");
            String line = test.bridge().memory.social().describe(wife, playerId());
            test.check(line.contains("ревность"), "the state line shows the jealousy: " + line);
            test.await("pause: the listening ends", 6, () -> false, this::night);
        });
    }

    /** Две кровати рядом, ночь, игрок ложится — пара ложится во вторую кровать. */
    private void night() {
        BlockPos head = new BlockPos(sx - 4, sy, sz - 6), second = head.add(2, 0, 0);
        for (BlockPos bed : List.of(head, second)) {
            test.command("setblock " + bed.getX() + " " + bed.getY() + " " + (bed.getZ() + 1) + " minecraft:red_bed[part=foot,facing=north]");
            test.command("setblock " + bed.getX() + " " + bed.getY() + " " + bed.getZ() + " minecraft:red_bed[part=head,facing=north]");
        }
        test.command("time set 13000");
        test.command("tp " + name() + " " + (sx - 4) + " " + sy + " " + (sz - 4));
        test.command("tp " + wife + " " + at(-2, -3));
        int relationBefore = test.bridge().memory.relationship(wife, playerId());
        int jealousBefore = test.bridge().memory.jealousy().level(wife, playerId(), System.currentTimeMillis());
        // Как в игре: «пойдём спать» даёт приглашение и следование за игроком — следование не должно мешать лечь.
        String dimension = world().getRegistryKey().getValue().toString();
        test.bridge().inviteToBed(wife, playerId());
        var follow = test.bridge().capture(wife, playerId(), dimension)
                .thenCompose(snapshot -> test.bridge().apply(wife, playerId(), dimension, new ActionIntent(ActionType.FOLLOW, "player"), snapshot, -1, () -> true));
        test.await("pause: beds placed, night fell, the partner follows", 3, () -> false, () -> {
            test.check(follow.isDone() && !follow.isCompletedExceptionally(), "the partner was told to follow the player");
            CompletableFuture<String> slept = test.bridge().onServer(() -> test.serverPlayer().trySleep(head).map(problem -> "no: " + problem, ok -> "ok").toString());
            test.await("the player lay down", 5, slept::isDone, () -> {
                test.log("trySleep: " + (slept.isCompletedExceptionally() ? slept.exceptionNow().getMessage() : slept.join()));
                test.await("the partner lies down beside the player", 8, () -> { VillagerEntity w = entity(wife); return w != null && w.isSleeping(); }, () -> {
                    VillagerEntity w = entity(wife);
                    Optional<BlockPos> where = w == null ? Optional.empty() : w.getSleepingPosition();
                    test.check(where.isPresent() && where.get().getManhattanDistance(head) <= 2, "the partner sleeps in a bed within two blocks: " + where);
                    test.check(test.bridge().memory.relationship(wife, playerId()) > relationBefore, "a night together raised the relationship ("
                            + relationBefore + " → " + test.bridge().memory.relationship(wife, playerId()) + ")");
                    test.check(test.bridge().memory.jealousy().level(wife, playerId(), System.currentTimeMillis()) < jealousBefore || jealousBefore == 0, "a night together soothed the jealousy");
                    test.await("pause: longer than the vanilla 5 s in bed", 8, () -> false, () -> {
                        long time = world().getTimeOfDay() % 24_000L;
                        test.check(time >= 12_000L, "the night waits while the partner lies beside the player (time " + time + ")");
                        CompletableFuture<Void> up = test.bridge().onServer(() -> { test.serverPlayer().wakeUp(false, true); return null; });
                        test.await("the player got up", 5, up::isDone, this::finish);
                    });
                });
            });
        });
    }

    private void finish() {
        test.command("time set day");
        test.command("fill " + (sx - 5) + " " + sy + " " + (sz - 6) + " " + (sx - 1) + " " + sy + " " + (sz - 5) + " minecraft:air");
        test.command("tp " + name() + " " + at(0, 0));
        test.command("kill @e[tag=" + TAG + "]");
        test.await("pause: track J stage cleaned up", 2, () -> false, next);
    }
}
