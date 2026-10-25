package io.github.mobtalk.game;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.JukeboxBlockEntity;
import net.minecraft.entity.ai.brain.BlockPosLookTarget;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.poi.PointOfInterest;
import net.minecraft.world.poi.PointOfInterestStorage;
import java.util.*;

/** The village bar's evening (Dmitriy 2026-10-25: villagers gather in the bar and talk over the rumours). From {@code barOpenHour}
 * to {@code barCloseHour} some free grown villagers of the village walk to the bar's stools and chairs ({@link BarContent#SEAT_POI})
 * and sit down ({@link BarSeatEntity}); one stands behind the counter. While the player is near, two of the sitters talk over the
 * village's rumours now and then ({@link VillageTalkController#barTalk}: chat text, voiced only with {@code barVoiced}). The jukebox
 * plays discs while the bar is open, unless the player put one in himself. At closing time, an alarm or a blow everyone gets up and
 * the brain takes them home to bed. Bars are found around players only; the evening is runtime state. Server thread only. */
final class BarEvenings {
    static final long PERIOD = 20, SCAN_PERIOD = 200, SCAN_PHASE = 113;
    /** How far from a player bars are looked for, how far seats of one bar lie apart at most, and from how far its guests come. */
    static final int SCAN_RADIUS = 80, BAR_SPAN = 12, GUESTS_RADIUS = 56;
    /** How close a guest must come to its seat to sit down, and how long it may walk before it is put there. */
    static final double SIT_REACH = 2.2;
    static final long WALK_TICKS = 1200;
    /** Two sitters this close may talk to each other. */
    static final double TALK_REACH = 4.5;
    private static final List<Item> DISCS = List.of(Items.MUSIC_DISC_STAL, Items.MUSIC_DISC_CAT, Items.MUSIC_DISC_MELLOHI, Items.MUSIC_DISC_CHIRP,
            Items.MUSIC_DISC_FAR, Items.MUSIC_DISC_BLOCKS, Items.MUSIC_DISC_STAL, Items.MUSIC_DISC_CREATOR_MUSIC_BOX);

    /** A bar as found: its key (dimension and lowest seat), seats, counters (facing the guests) and jukebox (null without one). */
    record Bar(String key, BlockPos center, List<BlockPos> seats, List<BlockPos> counters, BlockPos jukebox) { }
    /** An open bar's evening: who sits where, since when each walks, the bartender and where he stands, the music. */
    static final class Evening {
        final Map<UUID, BlockPos> guests = new LinkedHashMap<>();
        final Map<UUID, Long> walking = new HashMap<>();
        UUID bartender; BlockPos stand; long bartenderSince; boolean music; long lastTalk; int talks;
    }

    private final MobGameBridge bridge;
    private final Random random = new Random();
    private final Map<String, Bar> bars = new HashMap<>();
    private final Map<String, Evening> evenings = new HashMap<>();

    BarEvenings(MobGameBridge bridge) { this.bridge = bridge; }

    private boolean enabled() { return bridge.config.enabled && bridge.config.barEvenings; }

    /** Whether the bar is open at this time of day: the hours run 6 (sunrise) to 29 (5 am the next day). */
    static boolean open(long timeOfDay, int openHour, int closeHour) {
        int hour = (int) ((Math.floorMod(timeOfDay, 24000L) / 1000 + 6) % 24);
        if (hour < 6) hour += 24;
        return hour >= openHour && hour < closeHour;
    }

    void tick(ServerWorld world) {
        long time = world.getTime();
        if (!enabled()) { if (!evenings.isEmpty()) for (String key : List.copyOf(evenings.keySet())) close(world, key); return; }
        if (time % SCAN_PERIOD == SCAN_PHASE) scan(world);
        if (time % PERIOD != 0) return;
        boolean hours = open(world.getTimeOfDay(), bridge.config.barOpenHour, bridge.config.barCloseHour);
        for (Bar bar : List.copyOf(bars.values())) {
            if (!world.isChunkLoaded(bar.center())) continue;
            boolean alarm = bridge.defense.alarmAt(world, bar.center()) != null;
            Evening evening = evenings.get(bar.key());
            if (hours && !alarm && evening == null) start(world, bar);
            else if ((!hours || alarm) && evening != null) close(world, bar.key());
            else if (evening != null) attend(world, bar, evening, time);
        }
    }

    // ---- Finding bars ----

    private void scan(ServerWorld world) {
        String dimension = world.getRegistryKey().getValue().toString();
        Map<String, Bar> found = new HashMap<>();
        PointOfInterestStorage pois = world.getPointOfInterestStorage();
        for (ServerPlayerEntity player : world.getPlayers()) {
            List<BlockPos> seats = pois.getInSquare(e -> e.matchesKey(BarContent.SEAT_POI), player.getBlockPos(), SCAN_RADIUS, PointOfInterestStorage.OccupationStatus.ANY)
                    .map(PointOfInterest::getPos).sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
            List<BlockPos> left = new ArrayList<>(seats);
            while (!left.isEmpty()) {
                BlockPos first = left.removeFirst();
                List<BlockPos> group = new ArrayList<>(List.of(first));
                for (Iterator<BlockPos> it = left.iterator(); it.hasNext(); ) {
                    BlockPos other = it.next();
                    if (group.stream().anyMatch(p -> p.getManhattanDistance(other) <= BAR_SPAN)) { group.add(other); it.remove(); }
                }
                if (group.size() < 2) continue;
                BlockPos low = group.stream().min(Comparator.comparingLong(BlockPos::asLong)).orElseThrow();
                String key = dimension + "|" + low.toShortString();
                if (found.containsKey(key)) continue;
                found.put(key, describe(world, key, group));
            }
        }
        for (String key : List.copyOf(bars.keySet()))
            if (key.startsWith(dimension + "|") && !found.containsKey(key)) { if (evenings.containsKey(key)) close(world, key); bars.remove(key); }
        bars.putAll(found);
        for (Bar bar : found.values()) standUpStrangers(world, bar);
    }

    private static Bar describe(ServerWorld world, String key, List<BlockPos> seats) {
        Box box = Box.enclosing(seats.getFirst(), seats.getFirst());
        for (BlockPos seat : seats) box = box.union(new Box(seat));
        box = box.expand(4, 2, 4);
        BlockPos center = BlockPos.ofFloored(box.getCenter());
        List<BlockPos> counters = world.getPointOfInterestStorage().getInSquare(e -> e.matchesKey(BarContent.COUNTER_POI), center, 10, PointOfInterestStorage.OccupationStatus.ANY)
                .map(PointOfInterest::getPos).sorted(Comparator.comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).toList();
        BlockPos jukebox = null;
        for (BlockPos pos : BlockPos.iterate(BlockPos.ofFloored(box.minX, box.minY, box.minZ), BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ)))
            if (world.getBlockState(pos).isOf(Blocks.JUKEBOX)) { jukebox = pos.toImmutable(); break; }
        return new Bar(key, center, List.copyOf(seats), counters, jukebox);
    }

    /** Sitters left on a bar's seats with no evening holding them (a restart, a closed evening): they get up. */
    private void standUpStrangers(ServerWorld world, Bar bar) {
        Evening evening = evenings.get(bar.key());
        for (BarSeatEntity seat : world.getEntitiesByClass(BarSeatEntity.class, new Box(bar.center()).expand(BAR_SPAN), s -> true)) {
            boolean ours = evening != null && seat.getPassengerList().stream().anyMatch(p -> evening.guests.containsKey(p.getUuid()));
            if (!ours) seat.removeAllPassengers();
        }
    }

    // ---- The evening ----

    private void start(ServerWorld world, Bar bar) {
        Evening evening = new Evening();
        evenings.put(bar.key(), evening);
        List<VillagerEntity> free = new ArrayList<>(world.getEntitiesByClass(VillagerEntity.class, new Box(bar.center()).expand(GUESTS_RADIUS, 16, GUESTS_RADIUS), this::free));
        Collections.shuffle(free, random);
        List<BlockPos> seats = new ArrayList<>(bar.seats());
        Collections.shuffle(seats, random);
        int wanted = Math.min(bridge.config.barVisitors, seats.size());
        if (!bar.counters().isEmpty() && !free.isEmpty()) {
            BlockPos counter = bar.counters().get(bar.counters().size() / 2);
            BlockState state = world.getBlockState(counter);
            Direction guests = state.contains(BarFurnitureBlock.FACING) ? state.get(BarFurnitureBlock.FACING) : Direction.SOUTH;
            BlockPos stand = counter.offset(guests.getOpposite());
            if (world.getBlockState(stand).getCollisionShape(world, stand).isEmpty()) {
                VillagerEntity bartender = free.removeFirst();
                evening.bartender = bartender.getUuid(); evening.stand = stand; evening.bartenderSince = world.getTime();
            }
        }
        for (VillagerEntity villager : free) {
            if (evening.guests.size() >= wanted) break;
            if (random.nextInt(100) >= bridge.config.barVisitPercent) continue;
            evening.guests.put(villager.getUuid(), seats.get(evening.guests.size()));
            evening.walking.put(villager.getUuid(), world.getTime());
        }
        evening.lastTalk = world.getTime();
        music(world, bar, evening, true);
    }

    private void close(ServerWorld world, String key) {
        Evening evening = evenings.remove(key);
        if (evening == null) return;
        for (UUID id : evening.guests.keySet()) if (world.getEntity(id) instanceof VillagerEntity villager) release(villager);
        if (evening.bartender != null && world.getEntity(evening.bartender) instanceof VillagerEntity bartender) release(bartender);
        Bar bar = bars.get(key);
        if (bar != null && evening.music) music(world, bar, evening, false);
    }

    /** A villager who may go to the bar: grown, awake, calm, not working for the player, not on guard, not a traveller. */
    private boolean free(VillagerEntity villager) {
        if (!villager.isAlive() || villager.isRemoved() || villager.isAiDisabled() || villager.isBaby() || villager.isSleeping() || villager.isPanicking()) return false;
        if (villager.getTarget() != null || villager.hasVehicle() || villager.hasCustomer() || bridge.actions.busy(villager) || bridge.wedding.involved(villager.getUuid())) return false;
        if (DefenseContent.traveller(villager)) return false;
        VillagerProfession job = villager.getVillagerData().getProfession();
        return job != DefenseContent.ARCHER && job != DefenseContent.GUARD && job != DefenseContent.HEAVY && job != DefenseContent.CAVALRY && job != DefenseContent.CROSSBOWMAN;
    }

    /** Whether a guest has to leave: hurt, frightened, fighting, asleep, taken by the player or the wedding. */
    private boolean leaves(VillagerEntity villager) {
        return !villager.isAlive() || villager.isRemoved() || villager.hurtTime > 0 || villager.isPanicking() || villager.getTarget() != null || villager.isSleeping()
                || bridge.actions.busy(villager) && !villager.hasVehicle() || bridge.wedding.involved(villager.getUuid());
    }

    private void attend(ServerWorld world, Bar bar, Evening evening, long now) {
        List<VillagerEntity> seated = new ArrayList<>();
        for (Iterator<Map.Entry<UUID, BlockPos>> it = evening.guests.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, BlockPos> entry = it.next();
            if (!(world.getEntity(entry.getKey()) instanceof VillagerEntity villager) || leaves(villager)) {
                if (world.getEntity(entry.getKey()) instanceof VillagerEntity gone) release(gone);
                it.remove();
                continue;
            }
            BlockPos seat = entry.getValue();
            if (villager.getVehicle() instanceof BarSeatEntity) { seated.add(villager); continue; }
            Vec3d at = Vec3d.ofBottomCenter(seat);
            boolean near = villager.getPos().squaredDistanceTo(at) <= SIT_REACH * SIT_REACH && Math.abs(villager.getY() - seat.getY()) < 1.5;
            Long since = evening.walking.get(villager.getUuid());
            if (near || since != null && now - since > WALK_TICKS) { if (sit(world, villager, seat)) { evening.walking.remove(villager.getUuid()); seated.add(villager); } }
            else if (MobActionController.registered(villager, MemoryModuleType.WALK_TARGET))
                villager.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(seat, 0.55F, 1), 40);
        }
        if (evening.bartender != null) bartender(world, evening, seated, now);
        // Sitters look at each other now and then, as people at one table do.
        for (VillagerEntity villager : seated) {
            if (random.nextInt(4) != 0) continue;
            VillagerEntity other = nearest(villager, seated);
            if (other != null && MobActionController.registered(villager, MemoryModuleType.LOOK_TARGET))
                villager.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(other, true), 60);
        }
        if (evening.music) music(world, bar, evening, true);
        talk(world, evening, seated, now);
    }

    private boolean sit(ServerWorld world, VillagerEntity villager, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BarFurnitureBlock block) || block.seat <= 0) return false;
        if (!world.getEntitiesByClass(BarSeatEntity.class, new Box(pos), BarSeatEntity::hasPassengers).isEmpty()) return false;
        BarSeatEntity seat = BarSeatEntity.at(world, pos, state.get(BarFurnitureBlock.FACING));
        if (!world.spawnEntity(seat)) return false;
        villager.getNavigation().stop();
        villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        if (!villager.startRiding(seat, true)) { seat.discard(); return false; }
        return true;
    }

    private void release(VillagerEntity villager) {
        if (villager.getVehicle() instanceof BarSeatEntity) villager.stopRiding();
        if (MobActionController.registered(villager, MemoryModuleType.WALK_TARGET)) villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
    }

    /** The bartender stands behind the counter facing his guests. */
    private void bartender(ServerWorld world, Evening evening, List<VillagerEntity> seated, long now) {
        if (!(world.getEntity(evening.bartender) instanceof VillagerEntity bartender) || leaves(bartender)) { evening.bartender = null; return; }
        if (bartender.getBlockPos().getSquaredDistance(evening.stand) > 1.5 && now - evening.bartenderSince > WALK_TICKS
                && bartender.getBlockPos().getSquaredDistance(evening.stand) < GUESTS_RADIUS * GUESTS_RADIUS) {
            // Stuck on the way, as a guest who cannot reach his seat sits down anyway: he steps behind the counter.
            bartender.getNavigation().stop();
            Vec3d at = Vec3d.ofBottomCenter(evening.stand);
            bartender.refreshPositionAndAngles(at.x, at.y, at.z, bartender.getYaw(), 0);
        }
        if (bartender.getBlockPos().getSquaredDistance(evening.stand) > 1.5) {
            if (MobActionController.registered(bartender, MemoryModuleType.WALK_TARGET))
                bartender.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(evening.stand, 0.5F, 0), 40);
            return;
        }
        bartender.getNavigation().stop();
        if (!MobActionController.registered(bartender, MemoryModuleType.LOOK_TARGET)) return;
        VillagerEntity guest = seated.isEmpty() ? null : seated.get(random.nextInt(seated.size()));
        if (guest != null) bartender.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(guest, true), 60);
        else bartender.getBrain().remember(MemoryModuleType.LOOK_TARGET, new BlockPosLookTarget(evening.stand.offset(Direction.SOUTH, 2)), 60);
    }

    private static VillagerEntity nearest(VillagerEntity villager, List<VillagerEntity> others) {
        VillagerEntity best = null; double bestDistance = TALK_REACH * TALK_REACH;
        for (VillagerEntity other : others) {
            if (other == villager) continue;
            double d = other.squaredDistanceTo(villager);
            if (d <= bestDistance) { best = other; bestDistance = d; }
        }
        return best;
    }

    /** Two neighbours at the bar talk while a player is in earshot, one talk per {@code barTalkSeconds}. */
    private void talk(ServerWorld world, Evening evening, List<VillagerEntity> seated, long now) {
        if (seated.size() < 2 || now - evening.lastTalk < bridge.config.barTalkSeconds * 20L) return;
        double range = bridge.config.villageChatterRange;
        ServerPlayerEntity listener = null;
        for (ServerPlayerEntity player : world.getPlayers())
            if (player.isAlive() && !player.isSpectator() && seated.stream().anyMatch(v -> v.squaredDistanceTo(player) <= range * range)) { listener = player; break; }
        if (listener == null) return;
        List<VillagerEntity> order = new ArrayList<>(seated);
        Collections.shuffle(order, random);
        for (VillagerEntity a : order) {
            VillagerEntity b = nearest(a, seated);
            if (b == null) continue;
            if (bridge.talk.barTalk(world, a, b, listener)) { evening.lastTalk = now; evening.talks++; }
            return;
        }
    }

    /** The jukebox plays a disc after a disc while the bar is open ({@code on}) and falls silent at closing. A disc the player put
     * in is left alone. */
    private void music(ServerWorld world, Bar bar, Evening evening, boolean on) {
        if (bar.jukebox() == null || !world.isChunkLoaded(bar.jukebox()) || !(world.getBlockEntity(bar.jukebox()) instanceof JukeboxBlockEntity jukebox)) return;
        if (!on) { if (evening.music) jukebox.setStack(ItemStack.EMPTY); evening.music = false; return; }
        if (!evening.music) {
            if (!jukebox.getStack().isEmpty()) return;
            evening.music = true;
        }
        if (jukebox.getStack().isEmpty() || !jukebox.getManager().isPlaying()) {
            jukebox.setStack(ItemStack.EMPTY);
            jukebox.setDisc(new ItemStack(DISCS.get(random.nextInt(DISCS.size()))));
        }
    }

    void clearRuntime() { bars.clear(); evenings.clear(); }

    /** This villager stands behind the counter of an open bar tonight. */
    boolean isBartender(VillagerEntity villager) {
        for (Evening evening : evenings.values()) if (villager.getUuid().equals(evening.bartender)) return true;
        return false;
    }

    /** The bartender of the bar holding this spot and where he stands, for the self-test; null when it is closed or has none. */
    BlockPos bartenderStand(BlockPos near) {
        for (Bar bar : bars.values()) {
            Evening evening = evenings.get(bar.key());
            if (evening != null && evening.bartender != null && bar.center().getSquaredDistance(near) < 24 * 24) return evening.stand;
        }
        return null;
    }

    /** For /mobtalk bar: the bars found around players and how their evenings go. */
    List<String> describe(ServerWorld world) {
        List<String> out = new ArrayList<>();
        for (Bar bar : bars.values()) {
            Evening evening = evenings.get(bar.key());
            long seated = evening == null ? 0 : evening.guests.keySet().stream().filter(id -> world.getEntity(id) instanceof VillagerEntity v && v.getVehicle() instanceof BarSeatEntity).count();
            out.add(bar.center().toShortString() + ": мест " + bar.seats().size() + ", стойка " + bar.counters().size()
                    + (bar.jukebox() == null ? "" : ", проигрыватель") + (evening == null ? ", закрыт" : ", гостей " + evening.guests.size() + " (сидят " + seated + ")"
                    + (evening.bartender == null ? "" : ", бармен" + (world.getEntity(evening.bartender) instanceof VillagerEntity v
                    ? " в " + String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(v.getBlockPos().getSquaredDistance(evening.stand))) + " м от стойки" : " не загружен")) + (evening.music ? ", музыка" : "") + ", разговоров " + evening.talks));
        }
        return out;
    }
}
