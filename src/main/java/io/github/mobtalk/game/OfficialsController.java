package io.github.mobtalk.game;

import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.Initiative;
import io.github.mobtalk.core.MobState;
import io.github.mobtalk.core.Officialdom;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerType;
import net.minecraft.world.Heightmap;
import net.minecraft.world.poi.PointOfInterest;
import net.minecraft.world.poi.PointOfInterestStorage;
import java.util.*;

/** The officials in the world (track «чиновники», docs/officials.md). A village of {@code officialResidents} grown residents puts an
 * official's desk up by its bell and a newcomer takes it: first the head of the administration, then (twice as many residents) the
 * inspector, then the clerk; a city's desks in the administration are taken by its own villagers, who become clerks, and the village
 * makes the first of them its head and the next its inspector. An envelope handed to an official is judged by {@link Officialdom}; the
 * inspector writes orders to players who build or dig near him and the village turns on one who leaves an order unpaid twice. Server
 * thread only. */
final class OfficialsController {
    static final long PLAN_PERIOD = 600, PLAN_PHASE = 211, PATROL_PERIOD = 40, PATROL_PHASE = 13, OVERDUE_PERIOD = 1200, OVERDUE_PHASE = 97;
    /** How far the inspector notices a player at work and how close he comes to write the order. */
    static final double NOTICE = 24, WRITE = 4.5;
    /** How far around the official its residents see the envelope change hands. */
    static final double WITNESS = 10;
    /** The window of block changes that counts for an order, and how long a desk stays empty before a newcomer. */
    static final long CHANGES_MILLIS = 10 * 60_000L, EMPTY_TICKS = 20 * 1200L;
    /** Game days an inspector leaves a player alone after an envelope with no order. */
    static final int FAVOUR_DAYS = 3;

    private final MobGameBridge bridge;
    private final Map<BlockPos, Long> emptySince = new HashMap<>();
    /** Players the village turns on when next seen in it (an order left unpaid twice), by zone key. */
    private final Map<String, Set<UUID>> wanted = new HashMap<>();
    private final Random random = new Random();

    OfficialsController(MobGameBridge bridge) { this.bridge = bridge; }

    boolean enabled() { return bridge.config.enabled && bridge.config.officials && OfficialContent.HEAD != null; }

    void tick(ServerWorld world, List<VillageDefenseController.Zone> zones) {
        if (!enabled()) return;
        long now = world.getTime();
        if (now % PLAN_PERIOD == PLAN_PHASE) for (VillageDefenseController.Zone zone : zones) { desks(world, zone); ranks(world, zone); posts(world, zone, now); }
        if (now % PATROL_PERIOD == PATROL_PHASE) for (VillageDefenseController.Zone zone : zones) patrol(world, zone);
        if (now % OVERDUE_PERIOD == OVERDUE_PHASE) overdue(world, zones);
    }

    // ---- Desks and ranks ----

    private List<BlockPos> desks(ServerWorld world, VillageDefenseController.Zone zone, PointOfInterestStorage.OccupationStatus status) {
        int radius = (int) Math.ceil(Math.max(zone.box().getLengthX(), zone.box().getLengthZ()) / 2) + 1;
        List<BlockPos> out = new ArrayList<>();
        world.getPointOfInterestStorage().getInSquare(entry -> entry.matchesKey(OfficialContent.DESK_POI), zone.center(), radius, status)
                .map(PointOfInterest::getPos).filter(zone::holds).forEach(out::add);
        return out;
    }

    /** How many desks a village of this many grown residents keeps: one per {@code per}, at most one of each rank. */
    static int desksFor(int residents, int per) { return per <= 0 || residents <= 0 ? 0 : Math.min(3, residents / per); }

    /** A village short of desks puts one up by its bell, on the diagonals (the guards' stands take the sides). */
    private void desks(ServerWorld world, VillageDefenseController.Zone zone) {
        int wanted = desksFor(zone.residents(), bridge.config.officialResidents);
        List<BlockPos> existing = desks(world, zone, PointOfInterestStorage.OccupationStatus.ANY);
        if (existing.size() >= wanted || zone.bell() == null) return;
        List<BlockPos> spots = new ArrayList<>();
        for (int r = 4; r <= 7; r++) for (int[] d : new int[][] {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}}) spots.add(zone.bell().add(d[0] * r, 0, d[1] * r));
        for (BlockPos spot : spots) {
            if (!world.isChunkLoaded(spot)) continue;
            BlockPos at = new BlockPos(spot.getX(), world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spot.getX(), spot.getZ()), spot.getZ());
            boolean taken = false;
            for (BlockPos desk : existing) if (desk.getSquaredDistance(at) < 4 * 4) { taken = true; break; }
            if (taken || !VillageGuards.free(world, at)) continue;
            Direction facing = Direction.fromRotation(Math.toDegrees(Math.atan2(-(zone.bell().getX() - at.getX()), zone.bell().getZ() - at.getZ())));
            world.setBlockState(at, OfficialContent.DESK.getDefaultState().with(OfficialContent.Desk.FACING, facing), Block.NOTIFY_ALL);
            world.playSound(null, at, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1f, 1f);
            arrived(world, zone, newcomer(world, zone, at, rankFor(world, zone)));
            return; // one desk per round
        }
    }

    /** The officials living in the zone, by rank. */
    private Map<Officialdom.Rank, List<VillagerEntity>> officials(ServerWorld world, VillageDefenseController.Zone zone) {
        Map<Officialdom.Rank, List<VillagerEntity>> out = new EnumMap<>(Officialdom.Rank.class);
        for (VillagerEntity v : world.getEntitiesByClass(VillagerEntity.class, zone.box(), v -> v.isAlive() && !v.isBaby() && !DefenseContent.traveller(v))) {
            Officialdom.Rank rank = OfficialContent.rank(v);
            if (rank != null) out.computeIfAbsent(rank, r -> new ArrayList<>()).add(v);
        }
        return out;
    }
    /** The rank a new official gets: the head first, then the inspector, then clerks. */
    private Officialdom.Rank rankFor(ServerWorld world, VillageDefenseController.Zone zone) {
        Map<Officialdom.Rank, List<VillagerEntity>> now = officials(world, zone);
        if (!now.containsKey(Officialdom.Rank.HEAD)) return Officialdom.Rank.HEAD;
        if (!now.containsKey(Officialdom.Rank.INSPECTOR)) return Officialdom.Rank.INSPECTOR;
        return Officialdom.Rank.CLERK;
    }
    /** Clerks who took a desk on their own (a city's administration): a village without a head makes the first its head, one with a
     * head and two clerks makes one its inspector. */
    private void ranks(ServerWorld world, VillageDefenseController.Zone zone) {
        Map<Officialdom.Rank, List<VillagerEntity>> now = officials(world, zone);
        List<VillagerEntity> clerks = now.getOrDefault(Officialdom.Rank.CLERK, List.of());
        if (clerks.isEmpty()) return;
        if (!now.containsKey(Officialdom.Rank.HEAD)) promote(world, zone, clerks.getFirst(), Officialdom.Rank.HEAD);
        else if (!now.containsKey(Officialdom.Rank.INSPECTOR) && clerks.size() >= 2) promote(world, zone, clerks.getFirst(), Officialdom.Rank.INSPECTOR);
    }
    private void promote(ServerWorld world, VillageDefenseController.Zone zone, VillagerEntity villager, Officialdom.Rank rank) {
        villager.setVillagerData(villager.getVillagerData().withProfession(OfficialContent.profession(rank)));
        villager.reinitializeBrain(world);
        announce(world, zone, Text.translatable("mobtalk.official.promoted", zone.title(), name(villager), Text.translatable("mobtalk.official.rank." + rank.path)));
    }

    /** Desks left empty long enough (the official died or left) get a newcomer. */
    private void posts(ServerWorld world, VillageDefenseController.Zone zone, long now) {
        Set<BlockPos> empty = new HashSet<>(desks(world, zone, PointOfInterestStorage.OccupationStatus.HAS_SPACE));
        for (BlockPos desk : desks(world, zone, PointOfInterestStorage.OccupationStatus.ANY)) {
            if (!empty.contains(desk)) { emptySince.remove(desk); continue; }
            Long since = emptySince.putIfAbsent(desk, now);
            if (since == null || now - since < EMPTY_TICKS) continue;
            emptySince.remove(desk);
            arrived(world, zone, newcomer(world, zone, desk, rankFor(world, zone)));
        }
    }

    /** A grown villager of the local kind with the rank's profession, standing by the desk, the desk its job site. */
    VillagerEntity newcomer(ServerWorld world, VillageDefenseController.Zone zone, BlockPos desk, Officialdom.Rank rank) {
        if (!world.getBlockState(desk).isOf(OfficialContent.DESK)) return null;
        if (world.getPointOfInterestStorage().getInSquare(entry -> entry.matchesKey(OfficialContent.DESK_POI), desk, 0,
                PointOfInterestStorage.OccupationStatus.HAS_SPACE).findAny().isEmpty()) return null;
        BlockPos feet = null;
        for (Direction side : Direction.Type.HORIZONTAL) {
            BlockPos p = desk.offset(side);
            if (world.getBlockState(p).getCollisionShape(world, p).isEmpty() && world.getBlockState(p.up()).getCollisionShape(world, p.up()).isEmpty()) { feet = p; break; }
        }
        if (feet == null) return null;
        VillagerEntity official = EntityType.VILLAGER.create(world);
        if (official == null) return null;
        official.refreshPositionAndAngles(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, world.random.nextFloat() * 360f, 0f);
        official.initialize(world, world.getLocalDifficulty(feet), SpawnReason.MOB_SUMMONED, null);
        official.setVillagerData(new VillagerData(VillagerType.forBiome(world.getBiome(desk)), OfficialContent.profession(rank), 1));
        official.setBreedingAge(0);
        world.spawnEntityAndPassengers(official);
        world.getPointOfInterestStorage().getPosition(entry -> entry.matchesKey(OfficialContent.DESK_POI), (entry, pos) -> pos.equals(desk), desk, 1);
        official.reinitializeBrain(world); // a profession set by hand needs the brain vanilla builds for it
        official.getBrain().remember(MemoryModuleType.JOB_SITE, GlobalPos.create(world.getRegistryKey(), desk));
        return official;
    }
    private void arrived(ServerWorld world, VillageDefenseController.Zone zone, VillagerEntity official) {
        Officialdom.Rank rank = official == null ? null : OfficialContent.rank(official);
        if (rank != null) announce(world, zone, Text.translatable("mobtalk.official.arrived", zone.title(), titled(official, rank)));
    }

    // ---- The envelope ----

    /** From «Подарить»: the player hands {@code stack} to {@code mob}. Null when this is no envelope for an official (the ordinary
     * gift goes on); otherwise the line for the chat. */
    String envelope(ServerWorld world, ServerPlayerEntity player, MobEntity mob, ItemStack stack) {
        if (!enabled() || OfficialContent.ENVELOPE == null || !stack.isOf(OfficialContent.ENVELOPE) || !(mob instanceof VillagerEntity official)) return null;
        Officialdom.Rank rank = OfficialContent.rank(official);
        if (rank == null) return null;
        int emeralds = OfficialContent.emeralds(stack);
        VillageDefenseController.Zone zone = bridge.defense.zoneAt(world, official.getBlockPos());
        String village = zone == null ? "" : zone.key();
        long day = Math.floorDiv(world.getTimeOfDay(), 24_000L), now = System.currentTimeMillis();
        Officialdom.Fine fine = rank == Officialdom.Rank.INSPECTOR ? bridge.memory.officialFine(village, player.getUuid()) : null;
        List<VillagerEntity> witnesses = world.getEntitiesByClass(VillagerEntity.class, official.getBoundingBox().expand(WITNESS),
                v -> v != official && v.isAlive() && !v.isBaby() && OfficialContent.rank(v) == null && !DefenseContent.traveller(v) && (v.canSee(player) || v.canSee(official)));
        int relationship = bridge.memory.relationship(official.getUuid(), player.getUuid());
        Officialdom.Verdict verdict = Officialdom.judge(rank, official.getUuid(), emeralds, witnesses.size(), relationship,
                bridge.config.officialHonestPercent, bridge.config.officialPricePercent, fine == null ? 0 : fine.amount());
        String name = name(official), playerName = MobGameBridge.playerName(player);
        official.getLookControl().lookAt(player, 30f, 30f);
        if (verdict.keeps()) {
            stack.decrement(1);
            bridge.keep(official, true);
            world.playSound(null, official.getBlockPos(), SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 1f, 0.9f);
        } else {
            official.setHeadRollingTimeLeft(40);
            world.playSound(null, official.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 0.8f, 0.9f);
        }
        bridge.memory.recordBribe(official.getUuid(), MobGameBridge.type(official), official.getName().getString(), player.getUuid(), playerName,
                verdict.relationshipDelta(), memoryLine(verdict, emeralds), now);
        Text chat = switch (verdict.outcome()) {
            case TAKEN -> { bridge.memory.officialTook(official.getUuid()); yield serve(world, player, official, rank, zone, village, fine, day, name); }
            case PAID -> { bridge.memory.officialClose(village, player.getUuid()); yield Text.translatable("mobtalk.official.paid", name, fine.number()); }
            case KEPT -> { bridge.memory.officialTook(official.getUuid()); yield Text.translatable("mobtalk.official.kept", name, emeralds); }
            case TOO_LITTLE -> Text.translatable("mobtalk.official.too_little", name);
            case NOT_HERE -> Text.translatable("mobtalk.official.not_here", name);
            case EMPTY -> Text.translatable("mobtalk.official.empty", name);
            case REFUSED -> Text.translatable("mobtalk.official.refused", name);
            case REPORTED -> { bridge.defense.accuse(world, player, "дача взятки должностному лицу"); yield Text.translatable("mobtalk.official.reported", name); }
        };
        // Residents who saw an envelope go to an official talk of it (by name): not the honest payment of a fine, nor an empty envelope.
        if (verdict.outcome() != Officialdom.Outcome.PAID && verdict.outcome() != Officialdom.Outcome.EMPTY && !witnesses.isEmpty()) {
            String seen = "сунул конверт с изумрудами " + rankDative(rank) + " " + name + (verdict.keeps() ? ", и тот взял" : ", но тот не взял");
            for (VillagerEntity witness : witnesses)
                bridge.memory.recordWitnessed(witness.getUuid(), MobGameBridge.type(witness), witness.getName().getString(), player.getUuid(), playerName,
                        seen, verdict.keeps() ? -4 : -2, MobState.Event.MISCHIEF, now);
        }
        bridge.initiative.raise(new Initiative.Cue(official.getUuid(), name, Initiative.Trigger.GIFT, verdict.cue(), List.of(), now));
        return chat.getString();
    }

    /** The service a taken envelope buys. */
    private Text serve(ServerWorld world, ServerPlayerEntity player, VillagerEntity official, Officialdom.Rank rank, VillageDefenseController.Zone zone,
                       String village, Officialdom.Fine fine, long day, String name) {
        return switch (rank) {
            case HEAD -> {
                ConversationStore.Village known = zone == null || zone.villageId().isBlank() ? bridge.villageAt(world, official.getBlockPos()) : bridge.memory.village(zone.villageId());
                if (known != null) bridge.memory.forgiveVillage(known, player.getUuid());
                bridge.defense.forgive(player);
                if (zone != null) { Set<UUID> w = wanted.get(zone.key()); if (w != null) w.remove(player.getUuid()); }
                yield Text.translatable("mobtalk.official.taken.head", name);
            }
            case INSPECTOR -> {
                if (fine != null) { bridge.memory.officialClose(village, player.getUuid()); yield Text.translatable("mobtalk.official.taken.fine", name, fine.number()); }
                bridge.memory.officialFavour(village, player.getUuid(), day, FAVOUR_DAYS);
                yield Text.translatable("mobtalk.official.taken.favour", name);
            }
            case CLERK -> {
                player.getInventory().offerOrDrop(OfficialContent.certificate(MobGameBridge.playerName(player), name, day));
                yield Text.translatable("mobtalk.official.taken.clerk", name);
            }
        };
    }
    private static String memoryLine(Officialdom.Verdict verdict, int emeralds) {
        String envelope = "сунул тебе конверт с " + emeralds + " изумр.";
        return switch (verdict.outcome()) {
            case TAKEN -> envelope + "; ты взял и решил его вопрос";
            case PAID -> "заплатил штраф по твоему предписанию (" + emeralds + " изумр.)";
            case KEPT -> envelope + "; ты оставил его себе «за консультацию», вопрос не решён";
            case TOO_LITTLE -> envelope + "; мало, ты вернул";
            case NOT_HERE -> envelope + " при свидетелях; ты вернул";
            case EMPTY -> "сунул тебе пустой конверт";
            case REFUSED -> envelope + "; ты честно отказался";
            case REPORTED -> envelope + "; ты позвал стражу";
        };
    }
    private static String rankDative(Officialdom.Rank rank) {
        return switch (rank) { case HEAD -> "главе администрации"; case CLERK -> "паспортистке"; case INSPECTOR -> "инспектору"; };
    }

    // ---- The inspector's orders ----

    /** Each inspector looks for players who build or dig in his village: walks up to one and writes an order. */
    private void patrol(ServerWorld world, VillageDefenseController.Zone zone) {
        Set<UUID> sought = wanted.get(zone.key());
        if (sought != null && !sought.isEmpty())
            for (ServerPlayerEntity player : world.getPlayers()) if (zone.holds(player.getBlockPos()) && sought.remove(player.getUuid()))
                bridge.defense.accuse(world, player, "злостный неплательщик штрафов");
        List<VillagerEntity> inspectors = officials(world, zone).getOrDefault(Officialdom.Rank.INSPECTOR, List.of());
        if (inspectors.isEmpty()) return;
        long day = Math.floorDiv(world.getTimeOfDay(), 24_000L);
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.isCreative() || player.isSpectator() || !zone.holds(player.getBlockPos())) continue;
            if (!bridge.memory.officialMayFine(zone.key(), player.getUuid(), day)) continue;
            if (bridge.changes.countNear(world, player.getBlockPos(), 16, CHANGES_MILLIS) < bridge.config.inspectorBlocks) continue;
            VillagerEntity inspector = null;
            for (VillagerEntity v : inspectors) if (v.squaredDistanceTo(player) <= NOTICE * NOTICE && (inspector == null || v.squaredDistanceTo(player) < inspector.squaredDistanceTo(player))) inspector = v;
            if (inspector == null || bridge.actions.busy(inspector)) continue;
            if (inspector.squaredDistanceTo(player) > WRITE * WRITE) {
                inspector.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(player, 0.6f, 2));
                continue;
            }
            write(world, zone, inspector, player, day);
        }
    }
    private void write(ServerWorld world, VillageDefenseController.Zone zone, VillagerEntity inspector, ServerPlayerEntity player, long day) {
        String reason = Officialdom.reason(random.nextBoolean(), random.nextDouble());
        String name = name(inspector);
        Officialdom.Fine fine = bridge.memory.officialIssue(zone.key(), player.getUuid(), MobGameBridge.playerName(player), inspector.getUuid(), name,
                reason, bridge.config.inspectorFine, day, bridge.config.inspectorDays);
        inspector.getLookControl().lookAt(player, 30f, 30f);
        world.playSound(null, inspector.getBlockPos(), SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.NEUTRAL, 1f, 0.8f);
        String own = ownName(inspector);
        player.sendMessage((own == null ? Text.translatable("mobtalk.official.order.unnamed", fine.number(), reason, fine.amount(), fine.dueDay())
                : Text.translatable("mobtalk.official.order", own, fine.number(), reason, fine.amount(), fine.dueDay())).formatted(Formatting.GOLD), false);
        long now = System.currentTimeMillis();
        bridge.memory.recordEvent(inspector.getUuid(), MobGameBridge.type(inspector), inspector.getName().getString(),
                "Ты выписал игроку " + MobGameBridge.playerName(player) + " предписание №" + fine.number() + ": " + reason + ", штраф " + fine.amount() + " изумр.", now);
        bridge.initiative.raise(new Initiative.Cue(inspector.getUuid(), name, Initiative.Trigger.GIFT,
                "ты только что выписал игроку предписание №" + fine.number() + " («" + reason + "»), штраф " + fine.amount()
                        + " изумрудов до " + fine.dueDay() + "-го дня. Объяви это казённым языком; если ты не честный — намекни, что вопрос можно решить и подешевле.", List.of(), now));
    }

    /** Orders past due: doubled once (the player hears of it), then the village turns on the player when next seen in it. */
    private void overdue(ServerWorld world, List<VillageDefenseController.Zone> zones) {
        if (world != world.getServer().getOverworld()) return; // game days are the overworld's
        long day = Math.floorDiv(world.getTimeOfDay(), 24_000L);
        for (Map.Entry<Officialdom.Fine, Officialdom.Overdue> entry : bridge.memory.officialOverdue(day, bridge.config.inspectorDays).entrySet()) {
            Officialdom.Fine fine = entry.getKey();
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(fine.player());
            if (entry.getValue() == Officialdom.Overdue.DOUBLED) {
                if (player != null) player.sendMessage(Text.translatable("mobtalk.official.doubled", fine.number(), fine.amount(), fine.dueDay()).formatted(Formatting.RED), false);
            } else {
                wanted.computeIfAbsent(fine.village(), k -> new HashSet<>()).add(fine.player());
                if (player != null) player.sendMessage(Text.translatable("mobtalk.official.escalated", fine.number()).formatted(Formatting.RED), false);
            }
        }
    }

    /** The open order of this player with this inspector, for the snapshot; null for none. */
    Map<String, Object> fineFor(VillagerEntity villager, ServerPlayerEntity player) {
        if (!enabled() || OfficialContent.rank(villager) != Officialdom.Rank.INSPECTOR || !(villager.getWorld() instanceof ServerWorld world)) return null;
        VillageDefenseController.Zone zone = bridge.defense.zoneAt(world, villager.getBlockPos());
        Officialdom.Fine fine = zone == null ? null : bridge.memory.officialFine(zone.key(), player.getUuid());
        return fine == null ? null : fine.data();
    }

    private void announce(ServerWorld world, VillageDefenseController.Zone zone, Text text) {
        double range = bridge.config.alarmNoticeRange;
        for (ServerPlayerEntity player : world.getPlayers())
            if (player.getBlockPos().getSquaredDistance(zone.center()) <= range * range) player.sendMessage(text.copy().formatted(Formatting.GRAY), false);
    }
    private String name(VillagerEntity villager) {
        String name = bridge.memory.displayName(villager.getUuid());
        return name == null || name.isBlank() ? villager.getName().getString() : name;
    }

    /** «инспектор Пётр», or just «инспектор» for an unnamed one (whose name would only repeat the rank). */
    private Text titled(VillagerEntity villager, Officialdom.Rank rank) {
        String name = ownName(villager);
        Text title = Text.translatable("mobtalk.official.rank." + rank.path);
        return name == null ? title : title.copy().append(" " + name);
    }
    /** The villager's own name, or null when it has none (vanilla would show the profession instead). */
    private String ownName(VillagerEntity villager) {
        String name = bridge.memory.displayName(villager.getUuid());
        if ((name == null || name.isBlank()) && villager.hasCustomName()) name = villager.getCustomName().getString();
        return name == null || name.isBlank() ? null : name;
    }

    void clearRuntime() { emptySince.clear(); wanted.clear(); }
}
