package io.github.mobtalk.game;

import io.github.mobtalk.core.*;
import io.github.mobtalk.game.GameBridge.ConfessionAnswer;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Property;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;
import java.util.*;

/** Исповедь у священника в мире (Б4): кто иерей (житель-клирик мужского пола; настоятель церкви Божественного блока — по его
 * тегу), где аналой (аналой церкви или кафедра в 16 блоках, иначе рабочее место, иначе где стоит), батюшка идёт к нему с книгой,
 * игрок встаёт перед аналоем, преклоняет главу (приседает), свечи, пение, луч света и колокол, регенерация после. Порядок исповеди —
 * {@link Confession.Rite}, реплики и запросы к модели и богу — серверный {@code ConfessionHost}. Здесь же епитимьи: свеча у аналоя,
 * поклоны, покаянная молитва, пост, милостыня, возмещение, труд на храм — и их срок. Прерывается без отпущения, если игрок ушёл
 * дальше 16 блоков, вышел, умер, сменил мир, напали на деревню или кто-то ударил батюшку. Только серверный поток. */
public final class ConfessionController {
    /** Тег настоятеля церкви, которого ставит Божественный блок. */
    public static final String ABBOT_TAG = "divineblock_abbot";
    static final String BOOK_MARK = "mobtalk_confession";
    private static final double LEAVE_RANGE = 16, ANALOY_RANGE = 16;
    private static final Identifier ANALOY = Identifier.of("divineblock", "analoy"), ALTAR = Identifier.of("divineblock", "altar"),
            CANDLE_STAND = Identifier.of("divineblock", "candle_stand"), CHANT = Identifier.of("divineblock", "chant.altar_01");
    private static final List<Item> MEAT = List.of(Items.BEEF, Items.COOKED_BEEF, Items.PORKCHOP, Items.COOKED_PORKCHOP, Items.MUTTON, Items.COOKED_MUTTON,
            Items.CHICKEN, Items.COOKED_CHICKEN, Items.RABBIT, Items.COOKED_RABBIT, Items.RABBIT_STEW, Items.COD, Items.COOKED_COD, Items.SALMON,
            Items.COOKED_SALMON, Items.TROPICAL_FISH, Items.PUFFERFISH, Items.ROTTEN_FLESH);
    private static final Set<Item> WHITE_STONE = Set.of(Items.CALCITE, Items.DIORITE, Items.POLISHED_DIORITE, Items.QUARTZ_BLOCK, Items.SMOOTH_QUARTZ,
            Items.QUARTZ_BRICKS, Items.QUARTZ_PILLAR, Items.CHISELED_QUARTZ_BLOCK, Items.WHITE_CONCRETE, Items.WHITE_TERRACOTTA, Items.WHITE_GLAZED_TERRACOTTA);

    private final MobGameBridge bridge;
    private final Map<UUID, Scene> byPriest = new LinkedHashMap<>(), byPlayer = new HashMap<>();
    /** Кто ждёт занятого батюшку. */
    private final Map<UUID, Deque<UUID>> waiting = new HashMap<>();
    /** Присел ли игрок в прошлую проверку (поклоны и «преклони главу»). */
    private final Map<UUID, Boolean> crouched = new HashMap<>();

    ConfessionController(MobGameBridge bridge) { this.bridge = bridge; }
    private boolean enabled() { return bridge.config.enabled && bridge.config.confession && DivineLink.available(); }

    /** Одна исповедь: порядок, мир, аналой, места батюшки и кающегося, храм ли, голос батюшки, эффекты. */
    final class Scene {
        final Confession.Rite rite;
        final ServerWorld world;
        final BlockPos analoy;
        final Vec3d priestSpot, penitentSpot;
        final String voiceId;
        boolean hurt, lighter, effects;
        long lightUntil = -1, startedTick;
        Scene(Confession.Rite rite, ServerWorld world, BlockPos analoy, Vec3d priestSpot, Vec3d penitentSpot, String voiceId) {
            this.rite = rite; this.world = world; this.analoy = analoy; this.priestSpot = priestSpot; this.penitentSpot = penitentSpot; this.voiceId = voiceId;
        }
        boolean temple() { return rite.place == Confession.Place.TEMPLE; }
        Vec3d center() { return analoy != null ? Vec3d.ofBottomCenter(analoy) : priestSpot; }
    }

    /** Идущие исповеди для ведущего: можно читать с потока сервера. */
    List<GameBridge.ConfessionView> views() {
        List<GameBridge.ConfessionView> out = new ArrayList<>();
        for (Scene s : byPriest.values()) {
            Entity priest = s.world.getEntity(s.rite.priest);
            boolean open = bridge.config.confessionStreamerPublic && s.rite.player.equals(streamer());
            out.add(new GameBridge.ConfessionView(s.rite, s.world.getRegistryKey().getValue().toString(), priest == null ? -1 : priest.getId(), open,
                    bridge.config.confessionSecrecyRange, s.voiceId));
        }
        return out;
    }
    private static UUID streamer() { try { return io.github.mobtalk.server.ServerConfig.streamer(); } catch (RuntimeException | LinkageError none) { return null; } }
    public boolean active() { return !byPriest.isEmpty(); }
    /** Житель рядом с идущей исповедью молчит (болтовня, инициатива). */
    boolean hushes(MobEntity mob) {
        for (Scene s : byPriest.values())
            if (s.world == mob.getWorld() && mob.squaredDistanceTo(s.center()) <= sq(bridge.config.confessionSecrecyRange)) return true;
        return false;
    }
    boolean involved(UUID mob) { return byPriest.containsKey(mob); }

    // ---- Начало. ----

    /** Священник ли это: клирик; {@code null} — не клирик. */
    static boolean cleric(VillagerEntity villager) { return villager.getVillagerData().getProfession() == VillagerProfession.CLERIC; }
    static boolean abbot(Entity entity) { return entity.getCommandTags().contains(ABBOT_TAG); }

    /** Игрок сказал жителю слова исповеди. Не клирик или не к исповеди — разговор идёт как обычно. */
    ConfessionAnswer ask(VillagerEntity villager, ServerPlayerEntity player, String said) {
        boolean asks = Confession.asks(said), returns = Confession.returns(said);
        if (!enabled() || (!asks && !returns) || !cleric(villager) || villager.isBaby() || !(villager.getWorld() instanceof ServerWorld world)) return ConfessionAnswer.NOT_MINE;
        boolean abbot = abbot(villager);
        if (abbot) bridge.memory.social().ordain(villager.getUuid(), villager.hasCustomName() ? villager.getCustomName().getString() : "");
        String priestName = name(villager);
        if (bridge.memory.social().household(villager.getUuid()).gender() != Gender.MALE)
            return new ConfessionAnswer(true, Confession.matushka(nearestPriestName(world, villager)), "");
        if (byPlayer.containsKey(player.getUuid())) return new ConfessionAnswer(true, "", "");
        Scene busy = byPriest.get(villager.getUuid());
        if (busy != null) {
            Deque<UUID> line = waiting.computeIfAbsent(villager.getUuid(), k -> new ArrayDeque<>());
            if (!line.contains(player.getUuid())) line.add(player.getUuid());
            return new ConfessionAnswer(true, Confession.BUSY, "");
        }
        long tick = world.getServer().getOverworld().getTime();
        if (world.hasRaidAt(villager.getBlockPos())) return new ConfessionAnswer(true, Confession.RAID, "");
        if (world.getTimeOfDay() % 24_000 >= 13_000) return new ConfessionAnswer(true, Confession.NIGHT, "");
        ConfessionLedger.Penance penance = bridge.memory.confession().penance(player.getUuid());
        if (penance != null) {
            if (!penance.done()) return new ConfessionAnswer(true, Confession.pending(penance.kind()), "");
            // Епитимья исполнена: разрешение от остального даёт любой иерей, без новой исповеди и без отката.
            DivineLink.Soul soul = DivineLink.soul(player.getUuid());
            List<Confession.Deed> deeds = penance.deeds().stream().map(id -> new Confession.Deed(id, "", "", 0, true, false, false)).toList();
            begin(world, villager, player, priestName, abbot, true, deeds, soul.punished());
            return new ConfessionAnswer(true, "", "");
        }
        if (returns) return new ConfessionAnswer(true, "Епитимьи на тебе нет, чадо. Хочешь — исповедуйся.", "");
        if (bridge.config.confessionAbbotsOnly && !abbot) return new ConfessionAnswer(true, Confession.ABBOTS_ONLY, "");
        long last = bridge.memory.confession().lastFull(player.getUuid());
        if (last >= 0 && tick - last < bridge.config.confessionCooldownMinutes * 1200L && tick >= last) return new ConfessionAnswer(true, Confession.TODAY, "");
        DivineLink.Soul soul = DivineLink.soul(player.getUuid());
        if (!soul.reachable()) return ConfessionAnswer.NOT_MINE; // Бог не ответил: без него исповеди нет — обычный разговор.
        begin(world, villager, player, priestName, abbot, false, soul.deeds(), soul.punished());
        return new ConfessionAnswer(true, "", "");
    }

    private void begin(ServerWorld world, VillagerEntity priest, ServerPlayerEntity player, String priestName, boolean abbot, boolean returning,
                       List<Confession.Deed> deeds, boolean punished) {
        BlockPos analoy = analoy(world, priest);
        Vec3d priestSpot, penitentSpot;
        if (analoy != null) {
            Direction facing = facing(world.getBlockState(analoy));
            BlockPos front = analoy.offset(facing);
            penitentSpot = standing(world, front);
            priestSpot = standing(world, front.offset(facing.rotateYClockwise()));
            if (penitentSpot == null) penitentSpot = Vec3d.ofBottomCenter(front);
            if (priestSpot == null) priestSpot = priest.getPos();
        } else {
            priestSpot = priest.getPos();
            Vec3d toward = player.getPos().subtract(priest.getPos()).multiply(1, 0, 1);
            penitentSpot = priestSpot.add(toward.lengthSquared() < 0.01 ? new Vec3d(1.5, 0, 0) : toward.normalize().multiply(1.5));
        }
        boolean slavonic = PlayerIdentity.configOf(player.getUuid(), bridge.config).confessionSlavonic;
        Confession.Rite rite = new Confession.Rite(priest.getUuid(), priestName, player.getUuid(), MobGameBridge.playerName(player),
                abbot ? Confession.Place.TEMPLE : Confession.Place.VILLAGE, returning, deeds, punished, bridge.config.confessionRounds, slavonic,
                Confession.off(bridge.config.confessionPenancesOff), System.currentTimeMillis());
        String voice = bridge.memory.voiceOf(priest.getUuid());
        if (voice == null || voice.isBlank()) voice = SpeciesCatalog.voiceFor("minecraft:villager", Gender.MALE, priest.getUuid()).id(); // Житель без профиля: мужской голос.
        Scene scene = new Scene(rite, world, analoy, priestSpot, penitentSpot, voice);
        scene.startedTick = world.getTime();
        byPriest.put(priest.getUuid(), scene);
        byPlayer.put(player.getUuid(), scene);
        book(priest);
        if (scene.temple() && !returning) {
            SoundEvent chant = SoundEvent.of(CHANT);
            world.playSound(null, BlockPos.ofFloored(scene.center()), chant, SoundCategory.RECORDS, 0.15f, 1f);
        }
        player.sendMessage(Text.literal("§7[Mob Talk] " + (analoy != null ? "Встаньте перед аналоем (искры)." : "Встаньте перед батюшкой.")
                + (returning ? "" : " Когда он умолкнет, говорите грехи: удерживайте клавишу речи (до " + bridge.config.confessionSpeakSeconds
                + " с) или пишите /mobtalk say …; «всё» — конец.")), false);
        MobTalkMod_log("Mob Talk: исповедь {} у {} ({}{})", rite.playerName, priestName, abbot ? "храм" : "село", returning ? ", возвращение после епитимьи" : "");
    }
    private static void MobTalkMod_log(String format, Object... args) { org.slf4j.LoggerFactory.getLogger("mobtalk").info(format, args); }

    /** Аналой церкви или кафедра в 16 блоках (ближайшая), иначе рабочее место священника; null — исповедь там, где он стоит. */
    static BlockPos analoy(ServerWorld world, VillagerEntity priest) {
        BlockPos best = null; double bestDistance = Double.MAX_VALUE; boolean bestIsAnaloy = false;
        BlockPos center = priest.getBlockPos();
        for (BlockPos pos : BlockPos.iterateOutwards(center, (int) ANALOY_RANGE, 4, (int) ANALOY_RANGE)) {
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            BlockState state = world.getBlockState(pos);
            boolean isAnaloy = Registries.BLOCK.getId(state.getBlock()).equals(ANALOY);
            if (!isAnaloy && !state.isOf(Blocks.LECTERN)) continue;
            double distance = pos.getSquaredDistance(center);
            if (best == null || isAnaloy && !bestIsAnaloy || isAnaloy == bestIsAnaloy && distance < bestDistance) { best = pos.toImmutable(); bestDistance = distance; bestIsAnaloy = isAnaloy; }
        }
        if (best != null) return best;
        if (MobActionController.registered(priest, MemoryModuleType.JOB_SITE)) {
            Optional<GlobalPos> job = priest.getBrain().getOptionalRegisteredMemory(MemoryModuleType.JOB_SITE);
            if (job.isPresent() && job.get().dimension() == world.getRegistryKey() && job.get().pos().isWithinDistance(center, ANALOY_RANGE)) return job.get().pos();
        }
        return null;
    }
    private static Direction facing(BlockState state) {
        for (Property<?> property : state.getProperties())
            if (property.getName().equals("facing") && state.get(property) instanceof Direction direction && direction.getAxis().isHorizontal()) return direction;
        return Direction.NORTH;
    }
    private static Vec3d standing(ServerWorld world, BlockPos near) {
        for (int dy : new int[]{0, 1, -1})
            for (BlockPos pos : new BlockPos[]{near.up(dy), near.up(dy).north(), near.up(dy).south(), near.up(dy).east(), near.up(dy).west()})
                if (standable(world, pos)) return Vec3d.ofBottomCenter(pos);
        return null;
    }
    private static boolean standable(ServerWorld world, BlockPos feet) {
        return !world.getBlockState(feet.down()).getCollisionShape(world, feet.down()).isEmpty()
                && world.getBlockState(feet).getCollisionShape(world, feet).isEmpty() && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty();
    }
    private String nearestPriestName(ServerWorld world, VillagerEntity from) {
        VillagerEntity best = null;
        for (VillagerEntity v : world.getEntitiesByClass(VillagerEntity.class, from.getBoundingBox().expand(64), v -> v != from && v.isAlive() && cleric(v) && !v.isBaby()))
            if (bridge.memory.social().household(v.getUuid()).gender() == Gender.MALE && (best == null || v.squaredDistanceTo(from) < best.squaredDistanceTo(from))) best = v;
        return best == null ? "" : name(best);
    }

    // ---- Каждый тик. ----

    void tick(ServerWorld world) {
        if (!byPriest.isEmpty()) for (Scene s : new ArrayList<>(byPriest.values())) if (s.world == world) tickScene(s);
        if (world == world.getServer().getOverworld()) penances(world);
        if (world.getTime() % 100 == 57) ordainNear(world);
    }

    /** Настоятель храма зовётся одним именем везде: «отец Амвросий» из Божественного блока становится его именем в Mob Talk (подпись,
     * HUD, речь), как только он рядом с игроком, а не только с первой исповедью. Иначе над ним висело бы имя мира («Виктор»). */
    private void ordainNear(ServerWorld world) {
        if (!bridge.config.enabled) return;
        for (ServerPlayerEntity player : world.getPlayers())
            for (VillagerEntity v : world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(48), v -> v.isAlive() && abbot(v) && v.hasCustomName())) {
                String title = v.getCustomName().getString();
                if (!title.equals(bridge.memory.displayName(v.getUuid()))) bridge.memory.social().ordain(v.getUuid(), title);
            }
    }

    private void tickScene(Scene s) {
        long now = System.currentTimeMillis(), t = s.world.getTime();
        Confession.Rite rite = s.rite;
        String trouble = trouble(s);
        if (trouble != null) rite.cancel(trouble, now);
        if (rite.over()) { finish(s); return; }
        rite.tick(now);
        ServerPlayerEntity player = s.world.getServer().getPlayerManager().getPlayer(rite.player);
        Entity priestEntity = s.world.getEntity(rite.priest);
        if (t % 40 == 3 && priestEntity instanceof VillagerEntity priest) book(priest);
        // Свечи у аналоя теплятся (в храме), место кающегося искрится, пока он идёт.
        if (s.temple() && s.analoy != null && t % 20 == 0) {
            s.world.spawnParticles(ParticleTypes.SMALL_FLAME, s.analoy.getX() + 0.5, s.analoy.getY() + 1.15, s.analoy.getZ() + 0.5, 3, 0.2, 0.05, 0.2, 0.002);
            BlockPos stand = s.analoy.offset(facing(s.world.getBlockState(s.analoy)).getOpposite());
            if (Registries.BLOCK.getId(s.world.getBlockState(stand).getBlock()).equals(CANDLE_STAND))
                s.world.spawnParticles(ParticleTypes.FLAME, stand.getX() + 0.5, stand.getY() + 1.3, stand.getZ() + 0.5, 4, 0.25, 0.1, 0.25, 0.002);
        }
        if (rite.phase() == Confession.Rite.Phase.GATHER) {
            if (t % 10 == 0) s.world.spawnParticles(ParticleTypes.END_ROD, s.penitentSpot.x, s.penitentSpot.y + 0.2, s.penitentSpot.z, 3, 0.2, 0.05, 0.2, 0.01);
            boolean priestThere = priestEntity == null || priestEntity.squaredDistanceTo(s.priestSpot) <= 1.5 * 1.5;
            if (!priestThere && t - s.startedTick > 400 && priestEntity instanceof VillagerEntity v) { // Не дошёл за 20 с: ставится на место.
                v.getNavigation().stop();
                v.refreshPositionAndAngles(s.priestSpot.x, s.priestSpot.y, s.priestSpot.z, v.getYaw(), 0);
                priestThere = true;
            }
            boolean playerThere = player != null && (player.squaredDistanceTo(s.penitentSpot) <= 2.5 * 2.5 || priestEntity != null && player.squaredDistanceTo(priestEntity) <= 3.5 * 3.5);
            if (priestThere && playerThere) rite.gathered(now);
        }
        boolean sneaking = player != null && player.isSneaking();
        if (rite.wantsBow() && sneaking) rite.bowed(now);
        if (s.lightUntil > t && t % 3 == 0 && player != null)
            for (int i = 0; i < 12; i++) s.world.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 0.3 + i * 0.6, player.getZ(), 1, 0.05, 0.05, 0.05, 0.0);
    }

    /** Почему исповедь пора прервать, или null. */
    private String trouble(Scene s) {
        Confession.Rite rite = s.rite;
        if (!(s.world.getEntity(rite.priest) instanceof VillagerEntity priest) || !priest.isAlive()) return "батюшки больше нет рядом";
        ServerPlayerEntity player = s.world.getServer().getPlayerManager().getPlayer(rite.player);
        if (player == null || player.getWorld() != s.world || !player.isAlive()) return "кающийся ушёл";
        if (player.squaredDistanceTo(priest) > sq(LEAVE_RANGE)) return "кающийся отошёл дальше 16 блоков";
        if (s.hurt) return "батюшку ударили";
        if (s.world.hasRaidAt(priest.getBlockPos())) return "на деревню напали";
        return null;
    }

    private void finish(Scene s) {
        byPriest.remove(s.rite.priest);
        byPlayer.remove(s.rite.player);
        if (s.world.getEntity(s.rite.priest) instanceof VillagerEntity priest) {
            if (bookIn(priest.getMainHandStack())) priest.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            if (MobActionController.registered(priest, MemoryModuleType.LOOK_TARGET)) priest.getBrain().forget(MemoryModuleType.LOOK_TARGET);
            if (MobActionController.registered(priest, MemoryModuleType.WALK_TARGET)) priest.getBrain().forget(MemoryModuleType.WALK_TARGET);
        }
        ServerPlayerEntity player = s.world.getServer().getPlayerManager().getPlayer(s.rite.player);
        Confession.Rite.Phase phase = s.rite.phase();
        if (player != null) {
            if (phase == Confession.Rite.Phase.CANCELLED && !s.rite.silent())
                player.sendMessage(Text.literal("§e[Mob Talk] Исповедь прервана: " + s.rite.cancelReason() + ". Ничего не отпущено; приходите снова."), false);
            if (phase == Confession.Rite.Phase.DONE) {
                player.sendMessage(Text.literal("§7" + Confession.afterwards(s.lighter && !s.rite.refused())), false);
                if (!s.rite.refused()) player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, s.temple() ? 6000 : 2400, 0));
            }
        }
        Deque<UUID> line = waiting.remove(s.rite.priest);
        if (line != null) for (UUID next : line) {
            ServerPlayerEntity waiter = s.world.getServer().getPlayerManager().getPlayer(next);
            if (waiter != null && waiter.getWorld() == s.world && waiter.squaredDistanceTo(s.center()) <= 48 * 48) {
                waiter.sendMessage(Text.literal("§7[Mob Talk] " + s.rite.priestName + " освободился — подойдите и скажите «хочу исповедаться»."), false);
                break;
            }
        }
    }

    /** Итог от бога (ведущий, поток сервера): откат, епитимья, память батюшки, луч и колокол. */
    void result(Confession.Rite rite, DivineLink.Outcome outcome) {
        Scene s = byPriest.get(rite.priest);
        ServerWorld world = s != null ? s.world : bridge.server.getOverworld();
        long tick = bridge.server.getOverworld().getTime(), now = System.currentTimeMillis();
        if (s != null) s.lighter = outcome.lighter() || rite.returning && outcome.reachable() && !outcome.absolved().isEmpty();
        if (rite.returning) bridge.memory.confession().clear(rite.player);
        else {
            Confession.Penance penance = rite.penance();
            bridge.memory.confession().confessed(rite.player, rite.playerName, rite.priest, rite.priestName, "minecraft:villager", tick, penance, now);
            List<Long> deferred = outcome.reachable() ? outcome.deferred().stream().map(DivineLink.Item::id).toList()
                    : rite.namedDeeds().stream().filter(d -> d.heavy() || rite.punished).map(Confession.Deed::id).toList();
            if (penance != null && !deferred.isEmpty()) {
                Vec3d at = s == null ? Vec3d.ZERO : s.center();
                bridge.memory.confession().assign(rite.player, penance, deferred, rite.priest, rite.priestName, rite.place,
                        world.getRegistryKey().getValue().toString(), (int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z), s != null && s.analoy != null,
                        tick, tick + bridge.config.confessionPenanceDays * 24_000L, now);
                if (penance == Confession.Penance.FAST) {
                    ServerPlayerEntity player = bridge.server.getPlayerManager().getPlayer(rite.player);
                    bridge.memory.confession().fast(rite.player, tick, player == null ? 0 : meatEaten(player));
                }
            }
        }
        if (s == null || rite.refused()) return;
        // Разрешение: узкий луч света над кающимся и удар колокола — храма у аналоя, иначе колокола деревни, если он рядом.
        s.lightUntil = s.world.getTime() + 60;
        BlockPos bell = s.temple() ? BlockPos.ofFloored(s.center()) : villageBell(s.world, BlockPos.ofFloored(s.center()));
        s.world.playSound(null, bell == null ? BlockPos.ofFloored(s.center()) : bell, SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, s.temple() ? 1.6f : 1.2f, 0.9f);
        s.world.playSound(null, BlockPos.ofFloored(s.penitentSpot), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.NEUTRAL, 0.8f, 1.2f);
    }
    private static BlockPos villageBell(ServerWorld world, BlockPos near) {
        return world.getPointOfInterestStorage().getInCircle(type -> type.matchesKey(PointOfInterestTypes.MEETING), near, 48, PointOfInterestStorage.OccupationStatus.ANY)
                .map(poi -> poi.getPos()).min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(near))).orElse(null);
    }

    void clearRuntime() {
        for (Scene s : new ArrayList<>(byPriest.values())) { s.rite.cancel("мир закрывается", System.currentTimeMillis()); if (s.world.getServer().isOnThread()) finish(s); }
        byPriest.clear(); byPlayer.clear(); waiting.clear(); crouched.clear();
    }
    /** Кто-то ударил батюшку идущей исповеди. */
    void hurt(MobEntity mob) { Scene s = byPriest.get(mob.getUuid()); if (s != null) s.hurt = true; }

    // ---- Движение батюшки. ----

    /** После мозга: батюшка идёт к своему месту у аналоя, там стоит и смотрит на кающегося. true — движением распоряжается исповедь. */
    boolean afterBrain(MobEntity mob) {
        Scene s = byPriest.get(mob.getUuid());
        if (s == null) return false;
        if (s.rite.phase() == Confession.Rite.Phase.GATHER && mob.squaredDistanceTo(s.priestSpot) > 0.7 * 0.7) {
            if (MobActionController.registered(mob, MemoryModuleType.WALK_TARGET)) mob.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(s.priestSpot, 0.55F, 0), 20);
            else mob.getNavigation().startMovingTo(s.priestSpot.x, s.priestSpot.y, s.priestSpot.z, 0.55);
            return true;
        }
        if (MobActionController.registered(mob, MemoryModuleType.WALK_TARGET)) mob.getBrain().forget(MemoryModuleType.WALK_TARGET);
        if (MobActionController.registered(mob, MemoryModuleType.PATH)) mob.getBrain().forget(MemoryModuleType.PATH);
        mob.getNavigation().stop();
        ServerPlayerEntity player = s.world.getServer().getPlayerManager().getPlayer(s.rite.player);
        if (player != null && MobActionController.registered(mob, MemoryModuleType.LOOK_TARGET)) mob.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(player, true), 10);
        return true;
    }

    // ---- Книга в руке. ----

    private static void book(VillagerEntity priest) {
        if (bookIn(priest.getMainHandStack())) return;
        ItemStack book = new ItemStack(Items.BOOK);
        NbtCompound tag = new NbtCompound(); tag.putBoolean(BOOK_MARK, true);
        book.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        priest.equipStack(EquipmentSlot.MAINHAND, book);
    }
    private static boolean bookIn(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        return data != null && data.copyNbt().contains(BOOK_MARK);
    }

    // ---- Епитимьи. ----

    private void penances(ServerWorld overworld) {
        long t = overworld.getTime();
        boolean slow = t % 100 == 41;
        List<ConfessionLedger.Penance> all = bridge.memory.confession().penances();
        if (all.isEmpty()) return;
        for (ConfessionLedger.Penance p : all) {
            if (t >= p.dueTick() && !p.done()) {
                List<Long> deeds = bridge.memory.confession().clear(p.player());
                DivineLink.release(p.player(), deeds);
                ServerPlayerEntity player = overworld.getServer().getPlayerManager().getPlayer(p.player());
                if (player != null) player.sendMessage(Text.literal("§7[Mob Talk] Срок епитимьи вышел. Грех снова на душе — приходите на исповедь."), false);
                continue;
            }
            if (p.done()) continue;
            ServerPlayerEntity player = overworld.getServer().getPlayerManager().getPlayer(p.player());
            if (player == null) continue;
            switch (p.kind()) {
                case BOWS -> bows(player, p);
                case PRAYER -> { if (slow && DivineLink.prayedPenitent(p.player(), p.assignedMillis())) completed(player, p); }
                case FAST -> { if (slow) fast(player, p, t); }
                default -> { }
            }
        }
    }
    /** Поклон: игрок присел лицом к аналою (месту исповеди) не дальше 6 блоков. */
    private void bows(ServerPlayerEntity player, ConfessionLedger.Penance p) {
        boolean now = player.isSneaking();
        Boolean before = crouched.put(player.getUuid(), now);
        if (!now || Boolean.TRUE.equals(before) || !player.getWorld().getRegistryKey().getValue().toString().equals(p.dimension())) return;
        Vec3d anchor = new Vec3d(p.x() + 0.5, p.y() + 0.5, p.z() + 0.5);
        if (player.squaredDistanceTo(anchor) > 6 * 6) return;
        Vec3d look = player.getRotationVec(1f).multiply(1, 0, 1), to = anchor.subtract(player.getPos()).multiply(1, 0, 1);
        if (look.lengthSquared() < 1e-4 || to.lengthSquared() < 1e-4 || look.normalize().dotProduct(to.normalize()) < 0.5) return;
        int done = bridge.memory.confession().progress(player.getUuid(), 1);
        player.getServerWorld().spawnParticles(ParticleTypes.ENCHANT, player.getX(), player.getY() + 1.2, player.getZ(), 6, 0.3, 0.3, 0.3, 0.2);
        if (done >= Confession.Penance.BOWS.target) completedMessage(player, p);
    }
    private void fast(ServerPlayerEntity player, ConfessionLedger.Penance p, long t) {
        int eaten = meatEaten(player);
        if (p.fastSince() < 0 || eaten > p.baseline()) {
            if (p.fastSince() >= 0) player.sendMessage(Text.literal("§7[Mob Talk] Пост нарушен — начните его сызнова."), false);
            bridge.memory.confession().fast(p.player(), t, eaten);
            return;
        }
        if (t - p.fastSince() >= 24_000) completed(player, p);
    }
    private static int meatEaten(ServerPlayerEntity player) {
        int eaten = 0;
        for (Item item : MEAT) eaten += player.getStatHandler().getStat(Stats.USED.getOrCreateStat(item));
        return eaten;
    }
    /** Свеча поставлена (блок свечи или подсвечник) не дальше 8 блоков от аналоя исповеди или от алтаря. */
    void placed(ServerWorld world, BlockPos pos, ServerPlayerEntity player) {
        ConfessionLedger.Penance p = bridge.memory.confession().penance(player.getUuid());
        if (p == null || p.done() || p.kind() != Confession.Penance.CANDLE) return;
        BlockState state = world.getBlockState(pos);
        if (!state.isIn(BlockTags.CANDLES) && !state.isIn(BlockTags.CANDLE_CAKES) && !Registries.BLOCK.getId(state.getBlock()).equals(CANDLE_STAND)) return;
        boolean byAnaloy = world.getRegistryKey().getValue().toString().equals(p.dimension()) && pos.isWithinDistance(new BlockPos(p.x(), p.y(), p.z()), 8.5);
        if (!byAnaloy && !nearAltar(world, pos)) {
            player.sendMessage(Text.literal("§7[Mob Talk] Свечу ставят у аналоя или у алтаря (не дальше 8 блоков)."), false);
            return;
        }
        world.spawnParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5, 8, 0.15, 0.2, 0.15, 0.01);
        completed(player, p);
    }
    private static boolean nearAltar(ServerWorld world, BlockPos pos) {
        for (BlockPos near : BlockPos.iterateOutwards(pos, 8, 4, 8)) if (Registries.BLOCK.getId(world.getBlockState(near).getBlock()).equals(ALTAR)) return true;
        return false;
    }
    /** Клавиша подарка с епитимьей: милостыня бедному, труд на храм священнику. Строка для чата, null — это обычный подарок. */
    String handOver(ServerPlayerEntity player, MobEntity mob) {
        ConfessionLedger.Penance p = bridge.memory.confession().penance(player.getUuid());
        if (p == null || p.done() || !(mob instanceof VillagerEntity villager)) return null;
        ItemStack stack = player.getMainHandStack();
        switch (p.kind()) {
            case ALMS -> {
                String job = Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession()).getPath();
                if (villager.isBaby() || !("none".equals(job) || "nitwit".equals(job))) return null;
                int need = stack.isOf(Items.BREAD) ? 3 : stack.isOf(Items.EMERALD) ? 5 : 0;
                if (need == 0) return null;
                if (stack.getCount() < need) return "Милостыня — три хлеба или пять изумрудов разом";
                stack.decrement(need);
                player.getServerWorld().playSound(null, villager.getBlockPos(), SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 1f, 1f);
                completed(player, p);
                return name(villager) + " принимает милостыню: «Спаси Господи!»";
            }
            case LABOR -> {
                if (!villager.getUuid().equals(p.priestId()) && !(cleric(villager) && abbot(villager))) return null;
                if (!WHITE_STONE.contains(stack.getItem()) && !stack.isIn(ItemTags.PLANKS) && !stack.isIn(ItemTags.LOGS)) return null;
                int left = Confession.Penance.LABOR.target - p.progress();
                int give = Math.min(left, stack.getCount());
                stack.decrement(give);
                int done = bridge.memory.confession().progress(player.getUuid(), give);
                player.getServerWorld().playSound(null, villager.getBlockPos(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.NEUTRAL, 1f, 1f);
                if (done >= Confession.Penance.LABOR.target) { completedMessage(player, p); return name(villager) + ": «Бог в помощь, чадо. Потрудился на храм»"; }
                return name(villager) + " принимает на храм: " + done + "/" + Confession.Penance.LABOR.target;
            }
            default -> { return null; }
        }
    }
    /** Подарок жителю принят: епитимья «возмещение» исполнена (подарок не самому батюшке). */
    void gifted(ServerPlayerEntity player, MobEntity mob) {
        ConfessionLedger.Penance p = bridge.memory.confession().penance(player.getUuid());
        if (p == null || p.done() || p.kind() != Confession.Penance.RESTITUTION || !(mob instanceof VillagerEntity) || mob.getUuid().equals(p.priestId())) return;
        completed(player, p);
    }
    private void completed(ServerPlayerEntity player, ConfessionLedger.Penance p) {
        bridge.memory.confession().done(player.getUuid());
        completedMessage(player, p);
    }
    private static void completedMessage(ServerPlayerEntity player, ConfessionLedger.Penance p) {
        player.sendMessage(Text.literal("§a[Mob Talk] Епитимья исполнена — вернитесь к батюшке (" + p.priest() + " или любой иерей) и скажите «исполнил епитимью»."), false);
        player.getServerWorld().playSound(null, player.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.8f, 1.4f);
    }
    /** Строка HUD епитимьи игрока ("" — нет). */
    String hud(UUID player) {
        ConfessionLedger.Penance p = bridge.memory.confession().penance(player);
        return p == null ? "" : p.hud(bridge.server.getOverworld().getTime());
    }

    private String name(VillagerEntity v) {
        String given = bridge.memory.displayName(v.getUuid());
        if (given.isBlank() && !v.hasCustomName()) given = bridge.memory.social().named(v.getUuid(), v.getName().getString());
        return given.isBlank() ? (v.hasCustomName() ? v.getCustomName().getString() : v.getName().getString()) : given;
    }
    private static double sq(double value) { return value * value; }
}
