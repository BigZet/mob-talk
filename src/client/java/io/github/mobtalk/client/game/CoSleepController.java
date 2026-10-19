package io.github.mobtalk.client.game;

import io.github.mobtalk.core.*;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Сон вместе (трек J, часть 2) на встроенном сервере. Игрок спит как в ванили: анимация сна и пропуск ночи — ванильные, здесь их
 * никто не трогает. Когда игрок лёг, житель-пара в {@link MobTalkConfig#coSleepRange} блоках (позванная словами «пойдём спать» —
 * вдвое дальше) идёт к свободной кровати в 1–2 блоках от его кровати, а если такой нет — к его кровати, и ложится ванильным
 * {@code sleep(pos)} в обычной позе. Утром пара говорит одну реплику через очередь инициативы. Никаких сцен и анимаций сверх
 * ванильного сна. Только серверный поток. */
final class CoSleepController {
    private static final long PERIOD = 5;
    /** Пара, не дошедшая до кровати за столько тиков, бросает затею (ночь к тому времени обычно уже пропущена). */
    private static final long GIVE_UP_TICKS = 20 * 30;
    private final MobGameBridge bridge;
    /** Житель → его ночь рядом с игроком (идёт к кровати или уже лёг). */
    private final Map<UUID, Night> nights = new HashMap<>();
    /** Житель → приглашение «пойдём спать»: за кем идти и до какого времени (реальные мс). */
    private final Map<UUID, Invite> invites = new HashMap<>();
    /** Кто из игроков спал на прошлой проверке: чтобы заметить, как он лёг и как встал. */
    private final Set<UUID> sleeping = new HashSet<>();
    private static final class Night {
        final UUID player; final BlockPos bed; final long startedTick; boolean asleep;
        Night(UUID player, BlockPos bed, long startedTick) { this.player = player; this.bed = bed; this.startedTick = startedTick; }
    }
    private record Invite(UUID player, long until) { }

    CoSleepController(MobGameBridge bridge) { this.bridge = bridge; }
    private boolean enabled() { return bridge.config.enabled && bridge.config.socialLayer && bridge.config.coSleep; }

    /** Игрок позвал свою пару спать: она идёт за ним, пока он не ляжет (или пока не выйдет срок). Не пару не зовут. */
    void invite(UUID villager, UUID player) {
        if (!enabled() || !bridge.memory.jealousy().partner(villager, player)) return;
        invites.put(villager, new Invite(player, System.currentTimeMillis() + CoSleep.INVITE_MILLIS));
    }

    void tick(ServerWorld world) {
        if (!enabled()) { if (!nights.isEmpty() || !invites.isEmpty()) clearRuntime(); return; }
        if (world.getTime() % PERIOD != 2) return;
        long now = System.currentTimeMillis();
        invites.values().removeIf(invite -> now > invite.until());
        for (ServerPlayerEntity player : world.getPlayers()) {
            boolean asleep = player.isSleeping() && player.getSleepingPosition().isPresent();
            if (asleep && sleeping.add(player.getUuid())) lieDown(world, player, now);
            else if (!asleep && sleeping.remove(player.getUuid())) woke(world, player, now);
        }
        for (Iterator<Map.Entry<UUID, Night>> it = nights.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Night> entry = it.next();
            Night night = entry.getValue();
            Entity entity = world.getEntity(entry.getKey());
            if (entity == null) { if (world.getServer().getPlayerManager().getPlayer(night.player) == null) it.remove(); continue; } // В другом мире.
            if (!(entity instanceof VillagerEntity villager) || !villager.isAlive()) { it.remove(); continue; }
            if (night.asleep) { if (!villager.isSleeping()) it.remove(); continue; }
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(night.player);
            if (player == null || !player.isSleeping() || world.getTime() - night.startedTick > GIVE_UP_TICKS) { it.remove(); continue; }
            if (villager.squaredDistanceTo(Vec3d.ofBottomCenter(night.bed)) > 4.0) continue;
            BlockState state = world.getBlockState(night.bed);
            boolean shared = player.getSleepingPosition().map(night.bed::equals).orElse(false);
            if (!(state.getBlock() instanceof BedBlock) || !shared && state.get(BedBlock.OCCUPIED)) { it.remove(); continue; }
            villager.getNavigation().stop();
            villager.sleep(night.bed); // Ванильный сон: та же поза, что у жителя в своей кровати.
            night.asleep = true;
            invites.remove(villager.getUuid());
            bridge.memory.jealousy().sleptTogether(villager.getUuid(), player.getUuid(), MobGameBridge.playerName(player), now);
        }
    }

    /** Игрок лёг: каждая пара рядом идёт к своей кровати возле него; уже спящая в 1–2 блоках — сразу засчитывается. */
    private void lieDown(ServerWorld world, ServerPlayerEntity player, long now) {
        BlockPos bed = player.getSleepingPosition().orElse(null);
        if (bed == null) return;
        boolean sharedTaken = false;
        for (JealousyLedger.Partner partner : bridge.memory.jealousy().partners(player.getUuid())) {
            if (nights.containsKey(partner.id()) || !(world.getEntity(partner.id()) instanceof VillagerEntity villager) || !villager.isAlive()) continue;
            if (villager.isSleeping() && villager.getSleepingPosition().map(pos -> Math.abs(pos.getX() - bed.getX()) <= CoSleep.BED_RADIUS
                    && Math.abs(pos.getZ() - bed.getZ()) <= CoSleep.BED_RADIUS && Math.abs(pos.getY() - bed.getY()) <= 1).orElse(false)) {
                Night night = new Night(player.getUuid(), villager.getSleepingPosition().get(), world.getTime());
                night.asleep = true; nights.put(partner.id(), night);
                bridge.memory.jealousy().sleptTogether(partner.id(), player.getUuid(), MobGameBridge.playerName(player), now);
                continue;
            }
            Invite invite = invites.get(partner.id());
            boolean invited = invite != null && invite.player().equals(player.getUuid());
            boolean busy = bridge.actions.busy(villager) || villager.getTarget() != null || villager.isPanicking() || villager.hasVehicle() || villager.isLeashed();
            double distance = Math.sqrt(villager.squaredDistanceTo(Vec3d.ofBottomCenter(bed)));
            if (!CoSleep.comes(true, !villager.isBaby(), busy, distance, invited, bridge.config.coSleepRange)) continue;
            BlockPos target = freeBed(world, bed);
            if (target == null) {
                if (sharedTaken) continue; // В одну кровать с игроком — только одна пара.
                target = bed; sharedTaken = true;
            }
            if (villager.isSleeping()) villager.wakeUp(); // Спала в своей кровати поодаль: встаёт и идёт к игроку.
            nights.put(partner.id(), new Night(player.getUuid(), target, world.getTime()));
        }
    }
    /** Ближайшая свободная кровать (изголовье) в 1–2 блоках от кровати игрока, не занятая и не обещанная другой паре; null — нет. */
    private BlockPos freeBed(ServerWorld world, BlockPos bed) {
        List<int[]> free = new ArrayList<>();
        int r = CoSleep.BED_RADIUS;
        for (int dx = -r; dx <= r; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -r; dz <= r; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            BlockPos pos = bed.add(dx, dy, dz);
            BlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof BedBlock) || state.get(BedBlock.PART) != BedPart.HEAD || state.get(BedBlock.OCCUPIED)) continue;
            if (nights.values().stream().anyMatch(night -> night.bed.equals(pos))) continue;
            free.add(new int[]{dx, dy, dz});
        }
        int[] picked = CoSleep.bed(free);
        return picked == null ? null : bed.add(picked[0], picked[1], picked[2]);
    }
    /** Игрок встал: утром — утренняя реплика той, что спала рядом; среди ночи — пара тоже встаёт. */
    private void woke(ServerWorld world, ServerPlayerEntity player, long now) {
        boolean morning = world.getTimeOfDay() % 24_000L < 12_000L;
        for (Iterator<Map.Entry<UUID, Night>> it = nights.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Night> entry = it.next();
            Night night = entry.getValue();
            if (!night.player.equals(player.getUuid())) continue;
            it.remove();
            if (!night.asleep || !(world.getEntity(entry.getKey()) instanceof VillagerEntity villager) || !villager.isAlive()) continue;
            if (!morning) { if (villager.isSleeping()) villager.wakeUp(); continue; }
            // Днём ванильный распорядок будит жителя сам; повод для реплики — один раз за ночь.
            if (!bridge.memory.jealousy().morning(villager.getUuid(), player.getUuid())) continue;
            String name = bridge.memory.displayName(villager.getUuid());
            bridge.initiative.raise(new Initiative.Cue(villager.getUuid(), name.isBlank() ? villager.getName().getString() : name, Initiative.Trigger.GIFT,
                    CoSleep.morningCue(MobGameBridge.playerName(player)), List.of(), now));
        }
    }

    /** После тиков мозга: идущая к кровати пара получает цель — кровать; спящая лежит смирно; позванная идёт за игроком.
     * true — движением жителя сейчас распоряжается сон вместе. */
    boolean afterBrain(MobEntity mob) {
        if (!(mob instanceof VillagerEntity villager)) return false;
        Night night = nights.get(mob.getUuid());
        if (night != null) {
            if (night.asleep) {
                if (MobActionController.registered(mob, MemoryModuleType.WALK_TARGET)) mob.getBrain().forget(MemoryModuleType.WALK_TARGET);
                if (MobActionController.registered(mob, MemoryModuleType.PATH)) mob.getBrain().forget(MemoryModuleType.PATH);
                mob.getNavigation().stop();
                return true;
            }
            if (MobActionController.registered(mob, MemoryModuleType.WALK_TARGET))
                mob.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(night.bed, 0.8F, 1), 20);
            else mob.getNavigation().startMovingTo(night.bed.getX() + 0.5, night.bed.getY(), night.bed.getZ() + 0.5, 0.8);
            return true;
        }
        Invite invite = invites.get(mob.getUuid());
        if (invite == null || villager.isSleeping() || !(mob.getWorld() instanceof ServerWorld world)) return false;
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(invite.player());
        if (player == null || player.getWorld() != world || player.squaredDistanceTo(mob) > Math.pow(2.0 * bridge.config.coSleepRange, 2)) return false;
        if (player.squaredDistanceTo(mob) <= 9) return false; // Рядом: стоит и ждёт, пока игрок ляжет.
        if (MobActionController.registered(mob, MemoryModuleType.WALK_TARGET))
            mob.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(new EntityLookTarget(player, false), 0.7F, 2), 20);
        return true;
    }

    void unload(MobEntity mob) { nights.remove(mob.getUuid()); invites.remove(mob.getUuid()); }
    void clearRuntime() { nights.clear(); invites.clear(); sleeping.clear(); }
}
