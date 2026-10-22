package io.github.mobtalk.game;

import io.github.mobtalk.core.*;
import net.minecraft.block.*;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Ребёнок в семье (трек N) на встроенном сервере: особый вечер (уют дома, затемнение ночи, утро), срок беременности (новость,
 * периоды, желания, тяжёлая походка в конце) и мягкие роды — у кровати, с затемнением у тех, кто рядом, детским плачем и малышом.
 * Сбои сети здесь ничего не ломают: реплики идут через очередь инициативы и могут промолчать, а события срока двигает только время.
 * Однополая пара и выключенная беременность идут прежним путём ({@link FamilyController}). Только серверный поток, кроме
 * {@link #fade}, который читает клиентский рендер. */
public final class ChildController {
    private static final long PERIOD = 100;
    private static final double NEAR = 24, FADE_RANGE = 16;
    private static final Identifier SLOW = Identifier.of("mobtalk", "pregnancy_slow");
    private final MobGameBridge bridge;
    private final Random random = new Random();
    /** Носящая жительница → тик начала схваток; игрок-носящий → то же по его UUID. */
    private final Map<UUID, Long> labour = new HashMap<>();
    /** Игрок → когда он последний раз говорил со своей парой в особую ночь (реальные мс). */
    private final Map<UUID, Long> spoke = new HashMap<>();

    /** Затемнение на экране игрока: ночь (пока лежит), «Наутро…», роды. Пишет сервер, читает клиентский рендер. */
    public enum FadeKind { NIGHT, MORNING, BIRTH }
    public record Fade(FadeKind kind, long startMillis) { }
    private final Map<UUID, Fade> fades = new ConcurrentHashMap<>();
    public static final long MORNING_MILLIS = 4_000, BIRTH_MILLIS = 7_000;

    ChildController(MobGameBridge bridge) { this.bridge = bridge; }
    private boolean enabled() { return bridge.config.enabled && bridge.config.socialLayer && bridge.config.familyLife; }
    private boolean pregnancy() { return enabled() && bridge.config.pregnancy; }

    /** Затемнение для игрока сейчас, null — нет. Истёкшее «Наутро…» и роды убираются сами. */
    public Fade fade(UUID player) {
        Fade fade = player == null ? null : fades.get(player);
        if (fade == null) return null;
        long age = System.currentTimeMillis() - fade.startMillis();
        if (fade.kind() == FadeKind.MORNING && age > MORNING_MILLIS || fade.kind() == FadeKind.BIRTH && age > BIRTH_MILLIS) { fades.remove(player, fade); return null; }
        return fade;
    }
    private void fadeFor(UUID player, FadeKind kind) { if (bridge.config.familyFade && player != null) fades.put(player, new Fade(kind, System.currentTimeMillis())); }

    // ---- Особый вечер. ----

    /** Игрок позвал супруга на особый вечер. Строка для чата: подсказка или почему нельзя ("" — звали не супруга). */
    String startEvening(VillagerEntity villager, ServerPlayerEntity player) {
        if (!enabled() || villager == null || player == null || villager.isBaby()) return "";
        boolean married = bridge.memory.jealousy().partners(player.getUuid()).stream().anyMatch(p -> p.id().equals(villager.getUuid()) && p.married());
        if (!married) return "";
        Pregnancy.Cozy cozy = cozy((ServerWorld) player.getWorld(), player.getBlockPos(), villager, player);
        long now = System.currentTimeMillis();
        bridge.memory.children().startEvening(villager.getUuid(), player.getUuid(), cozy, now);
        bridge.coSleep.invite(villager.getUuid(), player.getUuid()); // Идёт за игроком домой и ляжет рядом, когда он ляжет.
        ((ServerWorld) villager.getWorld()).spawnParticles(ParticleTypes.HEART, villager.getX(), villager.getY() + 1.9, villager.getZ(), 5, 0.4, 0.3, 0.4, 0.02);
        String name = name(villager);
        if (cozy.score() > 0) cue(villager, SpecialEvening.arrivalCue(cozy.words()), now); // Она замечает, что игрок приготовил.
        return "Особый вечер с " + name + ": говорите о чём угодно. Свечи, цветы, ковёр, пластинка и торт рядом — " + name + " их заметит."
                + " Ложитесь спать, когда захотите; в темноте можно шептаться.";
    }
    /** Что игрок приготовил в 6 блоках вокруг: свечи и мягкий свет, цветы, ковёр, пластинка, торт; и нет ли в доме лишних. */
    static Pregnancy.Cozy cozy(ServerWorld world, BlockPos center, VillagerEntity spouse, ServerPlayerEntity player) {
        boolean candles = false, flowers = false, carpet = false, music = false, cake = false;
        for (BlockPos pos : BlockPos.iterate(center.add(-6, -2, -6), center.add(6, 3, 6))) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            Block block = state.getBlock();
            if (block instanceof AbstractCandleBlock && state.contains(AbstractCandleBlock.LIT) && state.get(AbstractCandleBlock.LIT)
                    || block instanceof LanternBlock || block == Blocks.GLOWSTONE || block == Blocks.SEA_LANTERN || block == Blocks.SHROOMLIGHT) candles = true;
            if (block instanceof FlowerPotBlock pot && pot.getContent() != Blocks.AIR || state.isIn(BlockTags.FLOWERS)) flowers = true;
            if (state.isIn(BlockTags.WOOL_CARPETS)) carpet = true;
            if (block instanceof JukeboxBlock && state.get(JukeboxBlock.HAS_RECORD)) music = true;
            if (block instanceof CakeBlock || block instanceof CandleCakeBlock) cake = true;
        }
        boolean alone = world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(6), v -> v.isAlive() && v != spouse && !v.isBaby()).isEmpty()
                && world.getPlayers().stream().noneMatch(other -> other != player && other.squaredDistanceTo(player) < 36);
        return new Pregnancy.Cozy(candles, flowers, carpet, music, cake, alone);
    }

    /** Трек J засчитал ночь вместе: особый вечер гаснет в темноту, у старающейся пары — шанс малыша. */
    void night(VillagerEntity villager, ServerPlayerEntity player) {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        ChildLedger.Evening evening = bridge.memory.children().evening(villager.getUuid());
        if (evening != null && evening.player().equals(player.getUuid())) {
            bridge.memory.children().cozy(villager.getUuid(), cozy((ServerWorld) player.getWorld(), player.getBlockPos(), villager, player));
            bridge.memory.children().dark(villager.getUuid(), now);
            spoke.put(player.getUuid(), now);
            fadeFor(player.getUuid(), FadeKind.NIGHT);
        }
        if (!pregnancy()) return;
        Pregnancy.Carrier carrier = bridge.memory.children().night(villager.getUuid(), player.getUuid(), time(player.getServerWorld()), random.nextDouble());
        if (carrier != null) LOGGER.info("[mobtalk] conceived: {} with {}, carrier {}", villager.getUuid(), player.getUuid(), carrier);
    }
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("mobtalk-family");

    /** Утро после ночи вместе: после особой — тёплая смущённая реплика, сердечки и «Наутро…»; null — ночь была обычной. */
    String morning(VillagerEntity villager, ServerPlayerEntity player) {
        if (!enabled() || !bridge.memory.children().morning(villager.getUuid(), player.getUuid(), MobGameBridge.playerName(player), System.currentTimeMillis())) return null;
        spoke.remove(player.getUuid());
        fadeFor(player.getUuid(), FadeKind.MORNING);
        return SpecialEvening.morningCue(MobGameBridge.playerName(player));
    }
    /** Игрок встал: ночное затемнение уходит (утро заменит его на «Наутро…»). */
    void woke(ServerPlayerEntity player) {
        Fade fade = fades.get(player.getUuid());
        if (fade != null && fade.kind() == FadeKind.NIGHT) fades.remove(player.getUuid(), fade);
    }
    /** Игрок сказал что-то своей паре: в особую ночь это держит ночь, пока шепчутся. */
    void spoke(UUID player) { if (spoke.containsKey(player)) spoke.put(player, System.currentTimeMillis()); }
    /** Особая ночь не пропускается, пока игрок шепчется с парой: до {@link MobTalkConfig#specialNightQuietSeconds} тишины. */
    boolean holdsNight(ServerPlayerEntity player) {
        if (!enabled() || !player.isSleeping()) return false;
        Long last = spoke.get(player.getUuid());
        return last != null && System.currentTimeMillis() - last < 1000L * bridge.config.specialNightQuietSeconds;
    }

    // ---- Срок и роды. ----

    void tick(ServerWorld world) {
        if (world != world.getServer().getOverworld() || world.getTime() % PERIOD != 23) return;
        long now = System.currentTimeMillis();
        for (UUID expired : bridge.memory.children().expired(now)) bridge.memory.children().endEvening(expired);
        refreshEvenings(world);
        if (!pregnancy()) return;
        long tick = time(world);
        for (ChildLedger.Pregnant pregnant : bridge.memory.children().pregnancies()) {
            try { pregnancy(pregnant, tick, now); }
            catch (RuntimeException failure) { LOGGER.warn("[mobtalk] pregnancy step failed for {}", pregnant.villager(), failure); }
        }
    }
    /** Пока вечер не дошёл до ночи, уют пересчитывается: игрок мог зажечь свечи после приглашения. */
    private void refreshEvenings(ServerWorld world) {
        if (world.getTime() % (PERIOD * 2) != 23) return;
        for (Map.Entry<UUID, ChildLedger.Evening> entry : bridge.memory.children().evenings().entrySet()) {
            if (entry.getValue().dark()) continue;
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(entry.getValue().player());
            VillagerEntity villager = villager(world, entry.getKey());
            if (player == null || villager == null || !villager.isAlive()) { bridge.memory.children().endEvening(entry.getKey()); continue; }
            bridge.memory.children().cozy(entry.getKey(), cozy((ServerWorld) player.getWorld(), player.getBlockPos(), villager, player));
        }
    }

    private void pregnancy(ChildLedger.Pregnant p, long tick, long now) {
        ServerPlayerEntity player = bridge.server.getPlayerManager().getPlayer(p.player());
        VillagerEntity spouse = villager(bridge.server.getOverworld(), p.villager());
        Pregnancy.Term term = p.term(tick);
        int day = p.day(tick);
        bridge.memory.children().progress(p.villager(), term, day);
        boolean near = player != null && spouse != null && spouse.getWorld() == player.getWorld() && spouse.squaredDistanceTo(player) <= NEAR * NEAR;
        String playerName = player == null ? "" : MobGameBridge.playerName(player);
        boolean villagerCarries = p.carrier() == Pregnancy.Carrier.VILLAGER;
        if (spouse != null) slow(spouse, villagerCarries && (term == Pregnancy.Term.LATE || term == Pregnancy.Term.DUE));
        if (term == Pregnancy.Term.DUE) { labour(p, spouse, player, tick, near); return; }
        if (!p.known(tick)) return;
        if (p.news() < 1) {
            if (villagerCarries) {
                if (near && bridge.memory.children().news(p.villager(), 1, "Ты узнала, что ждёшь ребёнка от игрока " + playerName, now))
                    cue(spouse, Pregnancy.newsCue(playerName), now);
                else if (player != null && day >= 3 && bridge.memory.children().news(p.villager(), 1, "", now))
                    player.sendMessage(Text.literal("§d[Mob Talk] " + p.name() + " ждёт малыша — заходите к ней."), false);
            } else if (player != null && bridge.memory.children().news(p.villager(), 1, "Ты узнал, что " + playerName + " ждёт от тебя ребёнка", now)) {
                player.sendMessage(Text.literal("§d[Mob Talk] Похоже, вы ждёте малыша (" + day + " из " + p.days() + " дн.). Срок — в экране семьи."), false);
                if (near) cue(spouse, Pregnancy.newsCuePlayer(playerName), now);
            }
            return;
        }
        if (!near || !villagerCarries) return;
        if (term == Pregnancy.Term.MIDDLE && p.news() < 2 && bridge.memory.children().news(p.villager(), 2, "", now)) { cue(spouse, Pregnancy.termCue(term), now); return; }
        if (term == Pregnancy.Term.LATE && p.news() < 3 && bridge.memory.children().news(p.villager(), 3, "", now)) { cue(spouse, Pregnancy.termCue(term), now); return; }
        if (bridge.memory.children().craving(p.villager(), day)) cue(spouse, Pregnancy.cravingCue(ChildLedger.cravingOf(p, day)), now);
    }

    /** Роды: схватки {@link Pregnancy#LABOUR_TICKS}, жительница идёт к своей кровати; потом затемнение, плач и малыш. Носит игрок —
     * всё у него. Ждёт, пока носящая загружена (игрок — в игре). */
    private void labour(ChildLedger.Pregnant p, VillagerEntity spouse, ServerPlayerEntity player, long tick, boolean near) {
        boolean villagerCarries = p.carrier() == Pregnancy.Carrier.VILLAGER;
        if (villagerCarries ? spouse == null || !spouse.isAlive() : player == null || !player.isAlive()) return;
        UUID who = villagerCarries ? p.villager() : p.player();
        long now = System.currentTimeMillis();
        String playerName = player == null ? "" : MobGameBridge.playerName(player);
        Long started = labour.get(who);
        if (started == null) {
            labour.put(who, tick);
            bridge.memory.children().news(p.villager(), 4, villagerCarries ? "У тебя начались роды" : "У " + playerName + " начались роды", now);
            if (villagerCarries) {
                if (near) cue(spouse, Pregnancy.termCue(Pregnancy.Term.DUE), now);
                else if (player != null) player.sendMessage(Text.literal("§d[Mob Talk] " + p.name() + ": кажется, началось! Она идёт домой."), false);
            } else {
                player.sendMessage(Text.literal("§d[Mob Talk] Кажется, началось… Лягте или присядьте, всё будет хорошо."), false);
                if (spouse != null && near) cue(spouse, "у " + playerName + " начались роды — ты рядом, держишь за руку. Скажи одну тёплую ободряющую фразу", now);
            }
            return;
        }
        if (tick - started < (villagerCarries ? Pregnancy.LABOUR_TICKS : Pregnancy.LABOUR_TICKS / 2)) return;
        labour.remove(who);
        ServerWorld world = villagerCarries ? (ServerWorld) spouse.getWorld() : player.getServerWorld();
        Vec3d at = villagerCarries ? spouse.getPos() : player.getPos();
        for (ServerPlayerEntity watcher : world.getPlayers())
            if (watcher.squaredDistanceTo(at) <= FADE_RANGE * FADE_RANGE) fadeFor(watcher.getUuid(), FadeKind.BIRTH);
        boolean present = player != null && player.getWorld() == world && player.squaredDistanceTo(at) <= FADE_RANGE * FADE_RANGE;
        int count = random.nextDouble() < ChildGrowth.TWINS ? 2 : 1;
        Gender sex = null;
        for (int i = 0; i < count; i++) {
            VillagerEntity baby = spawnBaby(world, at, spouse);
            if (baby == null) continue;
            sex = bridge.memory.social().born(baby.getUuid(), p.villager(), p.player(), playerName, world.getServer().getOverworld().getTime(), now); // Рост — по игровому времени.
        }
        if (sex == null) { LOGGER.warn("[mobtalk] no baby spawned for {}", p.villager()); return; }
        world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_VILLAGER_AMBIENT, SoundCategory.NEUTRAL, 1.0f, 1.9f);
        world.spawnParticles(ParticleTypes.HEART, at.x, at.y + 1.6, at.z, 12, 0.8, 0.5, 0.8, 0.02);
        String baby = (count == 2 ? "двойня, " : "") + (sex == Gender.FEMALE ? "дочь" : "сын");
        if (player != null) player.sendMessage(Text.literal("§d[Mob Talk] У вас родил" + (sex == Gender.FEMALE ? "ась дочь" : "ся сын") + (count == 2 ? " — и ещё один малыш, двойня!" : "!")
                + (present ? "" : " Вас не было рядом — " + p.name() + " вас ждала.")), false);
        if (spouse != null) cue(spouse, villagerCarries ? Pregnancy.birthCue(baby, playerName, present)
                : "только что у вас с " + playerName + " родился малыш: " + baby + ". Скажи одну-две счастливые фразы", now);
    }
    private VillagerEntity spawnBaby(ServerWorld world, Vec3d at, VillagerEntity parent) {
        VillagerEntity baby = EntityType.VILLAGER.create(world);
        if (baby == null) return null;
        baby.refreshPositionAndAngles(at.x + random.nextDouble() - 0.5, at.y, at.z + random.nextDouble() - 0.5, random.nextFloat() * 360, 0);
        if (parent != null) baby.setVillagerData(baby.getVillagerData().withType(parent.getVillagerData().getType()));
        baby.setBreedingAge(-24_000);
        baby.setPersistent();
        world.spawnEntityAndPassengers(baby);
        return baby;
    }

    /** Поздний срок: жительница ходит медленнее (временный модификатор — в мир не пишется). */
    private static void slow(VillagerEntity villager, boolean heavy) {
        EntityAttributeInstance speed = villager.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (speed == null) return;
        if (heavy && !speed.hasModifier(SLOW)) speed.addTemporaryModifier(new EntityAttributeModifier(SLOW, -0.25, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        else if (!heavy && speed.hasModifier(SLOW)) speed.removeModifier(SLOW);
    }

    /** В схватках жительница идёт к своей кровати (дом из памяти мозга); true — её движением распоряжаются роды. */
    boolean afterBrain(MobEntity mob) {
        if (!(mob instanceof VillagerEntity villager) || !labour.containsKey(mob.getUuid()) || villager.isSleeping()) return false;
        Optional<GlobalPos> home = MobActionController.registered(mob, MemoryModuleType.HOME) ? mob.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HOME) : Optional.empty();
        if (home.isEmpty() || home.get().dimension() != mob.getWorld().getRegistryKey()) return false;
        BlockPos bed = home.get().pos();
        if (mob.squaredDistanceTo(Vec3d.ofBottomCenter(bed)) <= 4) { mob.getNavigation().stop(); return true; }
        if (MobActionController.registered(mob, MemoryModuleType.WALK_TARGET)) mob.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(bed, 0.6F, 1), 40);
        return true;
    }

    private void cue(VillagerEntity villager, String reason, long now) {
        if (villager == null || reason == null || reason.isBlank()) return;
        bridge.initiative.raise(new Initiative.Cue(villager.getUuid(), name(villager), Initiative.Trigger.GIFT, reason, List.of(), now));
    }
    private VillagerEntity villager(ServerWorld any, UUID id) {
        for (ServerWorld world : any.getServer().getWorlds()) if (world.getEntity(id) instanceof VillagerEntity villager) return villager;
        return null;
    }
    private String name(MobEntity mob) { String given = bridge.memory.displayName(mob.getUuid()); return given.isBlank() ? mob.getName().getString() : given; }
    /** Время срока — время суток верхнего мира (общее для измерений): сон проматывает и срок, дни считаются, как их видит игрок. */
    private static long time(ServerWorld world) { return world.getServer().getOverworld().getTimeOfDay(); }

    void died(MobEntity mob) { bridge.memory.children().endEvening(mob.getUuid()); labour.remove(mob.getUuid()); }
    void clearRuntime() { labour.clear(); spoke.clear(); fades.clear(); }
}
