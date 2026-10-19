package io.github.mobtalk.client.game;

import io.github.mobtalk.core.*;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Игровая сторона ревности (трек J) на встроенном сервере: разговор, подарок или услуга жителю того же пола, что и пара игрока,
 * и сон рядом с ним ночью становятся поводами; кто из пар видел (в поле зрения, по расстоянию до соперника), решает здесь,
 * остальное — {@link JealousyLedger}. Внимание к самой паре гасит её ревность. Только серверный поток. */
final class JealousyController {
    /** Проверка «спит рядом с соперником» раз в секунду. */
    private static final long PERIOD = 20, PHASE = 7;
    private final MobGameBridge bridge;
    /** Уже засчитанные ночи «рядом с соперником»: игрок, соперник, игровой день. */
    private final Set<String> sleptNear = new LinkedHashSet<>();
    JealousyController(MobGameBridge bridge) { this.bridge = bridge; }

    private boolean enabled() { return bridge.config.enabled && bridge.config.socialLayer; }
    private static boolean adultVillager(MobEntity mob) { return mob instanceof VillagerEntity villager && !villager.isBaby() && villager.isAlive(); }

    /** Доставлен ответ жителя: разговор с парой её успокаивает, с жителем её пола — повод ревновать. Под замком памяти. */
    void talked(MobEntity mob, ServerPlayerEntity player) {
        if (!enabled() || !adultVillager(mob)) return;
        long now = System.currentTimeMillis();
        JealousyLedger ledger = bridge.memory.jealousy();
        if (ledger.partner(mob.getUuid(), player.getUuid())) ledger.attention(mob.getUuid(), player.getUuid(), bridge.config.jealousyAttention, now);
        else notice((VillagerEntity) mob, player, Jealousy.Cause.TALK, 0, "", now);
    }
    /** Житель принял подарок (в разговоре, из рук или брошенный): паре — внимание, сопернику — сильный повод по ценности. */
    void gift(MobEntity mob, ServerPlayerEntity player, GestureIntent gift) {
        if (!enabled() || gift == null || gift.gesture() != Gesture.GIFT || !gift.takesItem() || !adultVillager(mob)) return;
        long now = System.currentTimeMillis();
        JealousyLedger ledger = bridge.memory.jealousy();
        int hearts = Math.max(0, gift.hearts());
        if (ledger.partner(mob.getUuid(), player.getUuid())) ledger.attention(mob.getUuid(), player.getUuid(), Math.max(bridge.config.jealousyAttention, 2 * hearts), now);
        else notice((VillagerEntity) mob, player, Jealousy.Cause.GIFT, hearts, gift.item(), now);
    }
    /** Игрок выполнил поручение жителя. */
    void favor(MobEntity mob, ServerPlayerEntity player) {
        if (!enabled() || !adultVillager(mob)) return;
        long now = System.currentTimeMillis();
        JealousyLedger ledger = bridge.memory.jealousy();
        if (ledger.partner(mob.getUuid(), player.getUuid())) ledger.attention(mob.getUuid(), player.getUuid(), 2 * bridge.config.jealousyAttention, now);
        else notice((VillagerEntity) mob, player, Jealousy.Cause.FAVOR, 0, "", now);
    }

    /** Очевидцы среди пар игрока: бодрствуют, в поле зрения и не дальше дальности взгляда от соперника. */
    private void notice(VillagerEntity rival, ServerPlayerEntity player, Jealousy.Cause cause, int hearts, String detail, long now) {
        JealousyLedger ledger = bridge.memory.jealousy();
        List<JealousyLedger.Partner> partners = ledger.partners(player.getUuid());
        if (partners.isEmpty() || !(rival.getWorld() instanceof ServerWorld world)) return;
        Map<UUID, Double> seen = new HashMap<>();
        double sight = bridge.config.witnessSightRange;
        for (JealousyLedger.Partner partner : partners) {
            if (partner.id().equals(rival.getUuid()) || !(world.getEntity(partner.id()) instanceof VillagerEntity watcher) || !watcher.isAlive() || watcher.isSleeping()) continue;
            double distance = watcher.distanceTo(rival);
            if (distance <= sight && watcher.canSee(rival)) seen.put(partner.id(), distance);
        }
        List<JealousyLedger.Notice> notices = ledger.noticed(rival.getUuid(), player.getUuid(), MobGameBridge.playerName(player), cause, hearts, detail, seen, now);
        // Подарок сопернику на глазах — пара не молчит: одна реплика через очередь инициативы.
        for (JealousyLedger.Notice notice : notices) {
            if (notice.source() != Jealousy.Source.SAW || cause != Jealousy.Cause.GIFT && cause != Jealousy.Cause.SLEEP) continue;
            String name = bridge.memory.displayName(notice.jealous());
            bridge.initiative.raise(new Initiative.Cue(notice.jealous(), name, Initiative.Trigger.GIFT, CoSleep.jealousCue(notice.rival(), notice.deed()), List.of(), now));
            break;
        }
    }

    /** Ночью: игрок спит, а в 3 блоках от его кровати спит другой житель — повод для пар того же пола, раз за ночь на соперника. */
    void tick(ServerWorld world) {
        if (!enabled() || world.getTime() % PERIOD != PHASE) return;
        long day = world.getTimeOfDay() / 24_000L, now = System.currentTimeMillis();
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isSleeping() || player.getSleepingPosition().isEmpty()) continue;
            BlockPos bed = player.getSleepingPosition().get();
            Vec3d center = Vec3d.ofCenter(bed);
            double reach = CoSleep.NEAR_SLEEPER + 1;
            for (VillagerEntity sleeper : world.getEntitiesByClass(VillagerEntity.class, new net.minecraft.util.math.Box(bed).expand(reach),
                    v -> v.isAlive() && !v.isBaby() && v.isSleeping() && v.getSleepingPosition().isPresent())) {
                BlockPos other = sleeper.getSleepingPosition().get();
                if (Vec3d.ofCenter(other).distanceTo(center) > CoSleep.NEAR_SLEEPER) continue;
                if (bridge.memory.jealousy().partner(sleeper.getUuid(), player.getUuid())) continue;
                String key = player.getUuid() + ":" + sleeper.getUuid() + ":" + day;
                if (!sleptNear.add(key)) continue;
                while (sleptNear.size() > 256) sleptNear.remove(sleptNear.iterator().next());
                notice(sleeper, player, Jealousy.Cause.SLEEP, 0, "", now);
            }
        }
    }

    void clearRuntime() { sleptNear.clear(); }
}
