package io.github.mobtalk.game;

import io.github.mobtalk.core.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import java.util.*;

/** Игровая сторона соперников за одно сердце (П3, docs/rivals.md) на сервере: строки игрокам над хотбаром, полоса испытания с
 * таймером у участников, подсказка «⚔ соперник впереди/наравне/позади», когда игрок смотрит на жителя, сдача предметов для
 * испытания (из рук, брошенных и в разговоре) и выбор у колокола: житель идёт к колоколу, ухажёры собираются, звон, сердечки,
 * титры и торжественная реплика. Решения — в {@link RivalryLedger}. Только серверный поток. */
public final class RivalryController {
    /** Раз в секунду, в мире верхнего мира: общее для всех миров делается один раз. */
    private static final long PERIOD = 20, PHASE = 11;
    /** Подсказка о сопернике — если житель не дальше и почти в центре взгляда. */
    private static final double LOOK_RANGE = 6, LOOK_DOT = 0.97;
    /** Ухажёры «собрались» у колокола в стольких блоках от площади; житель — в стольких. */
    private static final double GATHER_RANGE = 12, ARRIVED = 4;
    /** Строка-уведомление держит хотбар столько тиков, подсказка о сопернике её не перебивает. */
    private static final long NOTICE_HOLD = 60;
    private final MobGameBridge bridge;
    /** Полосы испытаний и сборов к колоколу по жителю. */
    private final Map<UUID, ServerBossBar> trialBars = new HashMap<>(), sceneBars = new HashMap<>();
    /** Куда идёт житель на выбор: площадь у колокола и сам колокол (null — колокола нет, выбор на месте). */
    private final Map<UUID, Venue> venues = new HashMap<>();
    private record Venue(ServerWorld world, Vec3d plaza, BlockPos bell) { }
    /** Отложенные удары колокола: где и на каком тике. */
    private final List<Ring> rings = new ArrayList<>();
    private record Ring(ServerWorld world, BlockPos at, long tick) { }
    /** До какого тика хотбар игрока занят уведомлением. */
    private final Map<UUID, Long> held = new HashMap<>();
    RivalryController(MobGameBridge bridge) { this.bridge = bridge; }

    private boolean enabled() { return bridge.config.enabled && bridge.config.socialLayer && bridge.config.rivals; }

    void tick(ServerWorld world) {
        ringBells(world);
        if (world.getTime() % PERIOD != PHASE) return;
        if (!enabled()) { if (world.getRegistryKey() == World.OVERWORLD) clearRuntime(); return; }
        long now = MobClock.now();
        if (world.getRegistryKey() == World.OVERWORLD) {
            RivalryLedger ledger = bridge.memory.rivalry();
            ledger.expire(now);
            notices(world, ledger.drain());
            trialBars(ledger.trials(now), now);
            scenes(ledger.scenes(now), now);
        }
        if (bridge.config.rivalHud) for (ServerPlayerEntity player : world.getPlayers()) hint(world, player, now);
    }

    // ---- Уведомления и подсказка ----

    private void notices(ServerWorld world, List<RivalryLedger.Notice> notices) {
        for (RivalryLedger.Notice notice : notices) {
            ServerPlayerEntity player = bridge.server.getPlayerManager().getPlayer(notice.player());
            if (player == null) continue;
            player.sendMessage(withItem(notice.text(), notice.item()).formatted(Formatting.LIGHT_PURPLE), true);
            player.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.5f, 1.3f);
            held.put(player.getUuid(), world.getTime() + NOTICE_HOLD);
        }
    }
    /** Строка с именем предмета на языке игрока вместо {@link RivalryLedger#ITEM}. */
    private static MutableText withItem(String text, String item) {
        int at = text.indexOf(RivalryLedger.ITEM);
        if (at < 0 || item == null || item.isBlank()) return Text.literal(text.replace(RivalryLedger.ITEM, item == null ? "" : item));
        return Text.literal(text.substring(0, at)).append(itemName(item)).append(text.substring(at + RivalryLedger.ITEM.length()));
    }
    private static Text itemName(String item) {
        Item found = MobTrading.item(item);
        return found == null ? Text.literal(item) : Text.translatable(found.getTranslationKey());
    }
    /** Игрок смотрит на жителя, за которым ухаживает не один: «⚔ Петя впереди» над хотбаром. */
    private void hint(ServerWorld world, ServerPlayerEntity player, long now) {
        if (!player.isAlive() || player.isSpectator() || held.getOrDefault(player.getUuid(), 0L) > world.getTime()) return;
        Vec3d eye = player.getEyePos(), look = player.getRotationVec(1f);
        VillagerEntity seen = null; double best = LOOK_DOT;
        for (VillagerEntity v : world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(LOOK_RANGE), v -> v.isAlive() && !v.isBaby())) {
            Vec3d to = v.getEyePos().subtract(eye);
            if (to.lengthSquared() > LOOK_RANGE * LOOK_RANGE || to.lengthSquared() < 1e-4) continue;
            double dot = to.normalize().dotProduct(look);
            if (dot > best && player.canSee(v)) { best = dot; seen = v; }
        }
        if (seen == null) return;
        RivalryLedger.Rival rival = bridge.memory.rivalry().rival(seen.getUuid(), player.getUuid(), now);
        if (rival == null) return;
        Formatting color = switch (rival.standing()) { case AHEAD -> Formatting.RED; case EVEN -> Formatting.YELLOW; case BEHIND -> Formatting.GREEN; };
        player.sendMessage(Text.literal("⚔ " + rival.name() + " " + rival.standing().word).formatted(color), true);
    }

    // ---- Испытания ----

    /** Полоса «Аксинья: принести пшеница ×16 — Дима 3, Петя 5» у участников онлайн, тает со временем. */
    private void trialBars(List<RivalryLedger.TrialView> trials, long now) {
        Set<UUID> live = new HashSet<>();
        for (RivalryLedger.TrialView t : trials) {
            live.add(t.villager());
            ServerBossBar bar = trialBars.computeIfAbsent(t.villager(), id -> new ServerBossBar(Text.empty(), BossBar.Color.PINK, BossBar.Style.PROGRESS));
            MutableText name = Text.literal(t.villagerName() + ("FAVORITE".equals(t.kind()) ? ": первым подарить " : ": первым принести "))
                    .append(itemName(t.item())).append(t.count() > 1 ? " ×" + t.count() : "");
            List<String> done = t.entries().stream().filter(e -> e.progress() > 0).map(e -> e.name() + " " + e.progress()).toList();
            if (!done.isEmpty()) name.append(" — " + String.join(", ", done));
            long left = Math.max(0, t.endsAt() - now) / 1000;
            name.append(String.format(" · %d:%02d", left / 60, left % 60));
            bar.setName(name);
            bar.setPercent(Math.max(0f, Math.min(1f, (t.endsAt() - now) / (float) Math.max(1, t.endsAt() - t.startedAt()))));
            Set<UUID> who = new HashSet<>();
            for (RivalryLedger.Entry e : t.entries()) who.add(e.player());
            players(bar, who);
        }
        drop(trialBars, live);
    }
    /** Полоса видна ровно этим игрокам онлайн. */
    private void players(ServerBossBar bar, Set<UUID> who) {
        for (ServerPlayerEntity p : List.copyOf(bar.getPlayers())) if (!who.contains(p.getUuid()) || p.isDisconnected()) bar.removePlayer(p);
        for (UUID id : who) {
            ServerPlayerEntity p = bridge.server.getPlayerManager().getPlayer(id);
            if (p != null && !bar.getPlayers().contains(p)) bar.addPlayer(p);
        }
    }
    private static void drop(Map<UUID, ServerBossBar> bars, Set<UUID> live) {
        for (Iterator<Map.Entry<UUID, ServerBossBar>> it = bars.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, ServerBossBar> e = it.next();
            if (live.contains(e.getKey())) continue;
            e.getValue().clearPlayers(); it.remove();
        }
    }

    /** Игрок отдаёт жителю стопку: если это предмет его испытания, житель берёт сколько не хватает (стопка уменьшается). Сколько
     * взято; 0 — не испытание, обычный подарок. {@code speak} — житель скажет об этом сам (в разговоре он и так отвечает). */
    public int deliver(MobEntity mob, ServerPlayerEntity player, ItemStack stack, boolean speak) {
        if (!enabled() || !bridge.config.rivalTrials || !(mob instanceof VillagerEntity villager) || stack == null || stack.isEmpty()) return 0;
        long now = MobClock.now();
        String item = Registries.ITEM.getId(stack.getItem()).toString();
        RivalryLedger.Delivery delivery = bridge.memory.rivalry().deliver(villager.getUuid(), player.getUuid(), MobGameBridge.playerName(player), item, stack.getCount(), now);
        if (delivery == null || delivery.taken() <= 0) return 0;
        stack.decrement(delivery.taken());
        bridge.keep(villager, true);
        villager.getLookControl().lookAt(player, 30f, 30f);
        if (villager.getWorld() instanceof ServerWorld world) {
            world.playSound(null, villager.getBlockPos(), SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 1f, 1f);
            if (delivery.won()) {
                world.spawnParticles(ParticleTypes.HEART, villager.getX(), villager.getY() + 2.0, villager.getZ(), 10, 0.5, 0.3, 0.5, 0.05);
                player.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.7f, 1.2f);
            }
        }
        if (speak) {
            String name = bridge.memory.displayName(villager.getUuid());
            bridge.initiative.raise(new Initiative.Cue(villager.getUuid(), name.isBlank() ? villager.getName().getString() : name, Initiative.Trigger.GIFT,
                    delivery.cue(), List.of(), now));
        }
        return delivery.taken();
    }
    /** Брошенная под ноги стопка для испытания: true — взята (вся или часть; пустая стопка убрана). */
    boolean deliverThrown(VillagerEntity villager, ServerPlayerEntity player, ItemEntity drop) {
        ItemStack stack = drop.getStack();
        if (deliver(villager, player, stack, true) <= 0) return false;
        if (stack.isEmpty()) drop.discard(); else drop.setStack(stack);
        return true;
    }

    // ---- Выбор у колокола ----

    private void scenes(List<RivalryLedger.Scene> scenes, long now) {
        Set<UUID> live = new HashSet<>();
        for (RivalryLedger.Scene scene : scenes) {
            live.add(scene.villager());
            VillagerEntity villager = find(scene.villager());
            Venue venue = venues.get(scene.villager());
            if (venue == null && villager != null && villager.getWorld() instanceof ServerWorld world) {
                WeddingController.Venue found = bridge.wedding.venue(world, villager);
                venue = new Venue(world, found == null ? villager.getPos() : found.plaza(), found == null ? null : found.bell());
                venues.put(scene.villager(), venue);
            }
            if (villager != null && venue != null && villager.getWorld() == venue.world && !bridge.wedding.involved(villager.getUuid())
                    && villager.getPos().squaredDistanceTo(venue.plaza) > ARRIVED * ARRIVED && MobActionController.registered(villager, MemoryModuleType.WALK_TARGET))
                villager.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(venue.plaza, 0.6F, 1), 40);
            ServerBossBar bar = sceneBars.computeIfAbsent(scene.villager(), id -> new ServerBossBar(Text.empty(), BossBar.Color.RED, BossBar.Style.NOTCHED_10));
            long left = Math.max(0, scene.at() - now) / 1000;
            BlockPos at = venue == null ? null : venue.bell != null ? venue.bell : BlockPos.ofFloored(venue.plaza);
            bar.setName(Text.literal(scene.villagerName() + " выберет у колокола" + (at == null ? "" : " (" + at.getX() + ", " + at.getZ() + ")")
                    + String.format(" · %d:%02d", left / 60, left % 60)));
            long total = Math.max(1, bridge.config.rivalGatherMinutes) * 60_000L;
            bar.setPercent(Math.max(0f, Math.min(1f, (scene.at() - now) / (float) total)));
            players(bar, new HashSet<>(scene.suitors()));
            if (now >= scene.at() || gathered(scene, villager, venue)) resolve(scene, villager, venue, now);
        }
        drop(sceneBars, live);
        venues.keySet().retainAll(live);
    }
    /** Все ухажёры онлайн стоят у колокола, и житель дошёл: можно не ждать таймера. */
    private boolean gathered(RivalryLedger.Scene scene, VillagerEntity villager, Venue venue) {
        if (villager == null || venue == null || villager.getPos().squaredDistanceTo(venue.plaza) > ARRIVED * ARRIVED) return false;
        int here = 0;
        for (UUID id : scene.suitors()) {
            ServerPlayerEntity p = bridge.server.getPlayerManager().getPlayer(id);
            if (p == null) continue;
            if (p.getWorld() != venue.world || p.getPos().squaredDistanceTo(venue.plaza) > GATHER_RANGE * GATHER_RANGE) return false;
            here++;
        }
        return here >= 2;
    }
    private void resolve(RivalryLedger.Scene scene, VillagerEntity villager, Venue venue, long now) {
        RivalryLedger.SceneResult result = bridge.memory.rivalry().resolveScene(scene.villager(), now);
        ServerBossBar bar = sceneBars.remove(scene.villager());
        if (bar != null) bar.clearPlayers();
        venues.remove(scene.villager());
        if (result == null || result.cue().isBlank() && result.winner() == null) return;
        ServerWorld world = villager != null && villager.getWorld() instanceof ServerWorld w ? w : null;
        if (world != null) {
            BlockPos bell = venue != null && venue.bell != null ? venue.bell : villager.getBlockPos();
            long t = world.getTime();
            rings.add(new Ring(world, bell, t + 1)); rings.add(new Ring(world, bell, t + 13)); rings.add(new Ring(world, bell, t + 25));
            world.spawnParticles(result.tie() ? ParticleTypes.NOTE : ParticleTypes.HEART, villager.getX(), villager.getY() + 2.1, villager.getZ(), 12, 0.6, 0.4, 0.6, 0.05);
        }
        Text title = Text.literal(result.tie() ? result.villagerName() + " не может выбрать" : result.villagerName() + " выбирает").formatted(Formatting.GOLD);
        Set<UUID> told = new HashSet<>(scene.suitors());
        if (result.winner() != null) told.add(result.winner());
        for (UUID id : told) {
            ServerPlayerEntity p = bridge.server.getPlayerManager().getPlayer(id);
            if (p == null) continue;
            boolean won = id.equals(result.winner());
            Text subtitle = result.tie() ? Text.literal("Будет испытание").formatted(Formatting.YELLOW)
                    : won ? Text.literal("тебя! ♥").formatted(Formatting.LIGHT_PURPLE) : Text.literal(result.winnerName()).formatted(Formatting.GRAY);
            p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 60, 20));
            p.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
            p.networkHandler.sendPacket(new TitleS2CPacket(title));
            if (p.getWorld() instanceof ServerWorld pw && !result.tie())
                pw.spawnParticles(won ? ParticleTypes.HEART : ParticleTypes.ANGRY_VILLAGER, p.getX(), p.getY() + 2.1, p.getZ(), won ? 8 : 4, 0.4, 0.3, 0.4, 0.02);
        }
        // Выбор слышат все рядом, даже если реплика модели не прозвучит.
        if (world != null && !result.tie()) {
            Text line = Text.literal(result.villagerName() + " при всех выбирает: " + result.winnerName()).formatted(Formatting.LIGHT_PURPLE);
            for (ServerPlayerEntity p : world.getPlayers()) if (p.squaredDistanceTo(villager) < 64 * 64) p.sendMessage(line, false);
        }
        if (villager != null && !result.cue().isBlank())
            bridge.initiative.raise(new Initiative.Cue(villager.getUuid(), result.villagerName(), Initiative.Trigger.GIFT, result.cue(), List.of(), now));
    }
    private void ringBells(ServerWorld world) {
        if (rings.isEmpty()) return;
        long t = world.getTime();
        for (Iterator<Ring> it = rings.iterator(); it.hasNext(); ) {
            Ring ring = it.next();
            if (ring.world != world || ring.tick > t) continue;
            world.playSound(null, ring.at, SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, 2f, 1f);
            it.remove();
        }
    }
    private VillagerEntity find(UUID id) {
        for (ServerWorld world : bridge.server.getWorlds()) {
            Entity entity = world.getEntity(id);
            if (entity instanceof VillagerEntity villager && villager.isAlive()) return villager;
        }
        return null;
    }

    void clearRuntime() {
        trialBars.values().forEach(ServerBossBar::clearPlayers); trialBars.clear();
        sceneBars.values().forEach(ServerBossBar::clearPlayers); sceneBars.clear();
        venues.clear(); rings.clear(); held.clear();
    }
}
