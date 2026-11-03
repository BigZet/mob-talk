package io.github.mobtalk.game;

import io.github.mobtalk.core.MobTalkConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;

/** Villagers go shopping in the city supermarket by day: one walks in, stops at two or three displays and takes a lot from
 * each (the shelf empties as it would for a player), then queues at a staffed till; the cashier serves the queue in turn, a
 * beep a lot, and now and then calls «Галя, отмена!» and keeps everyone waiting. A player paying past the queue hears about
 * it. A trip is dropped after a few minutes, at night, or when the villager is gone. Off in the settings. */
public final class ShopShoppers {
    private ShopShoppers() { }

    static final int MAX_TRIPS = 3, MAX_QUEUE = 3, PICK_RADIUS = 48;
    static final long TRIP_TICKS = 3 * 1200;
    private static final Random RANDOM = new Random();

    enum Stage { BROWSE, QUEUE, PAY }

    static final class Trip {
        final UUID villager; final BlockPos desk; final List<BlockPos> stops; final long started;
        net.minecraft.registry.RegistryKey<net.minecraft.world.World> world;
        Stage stage = Stage.BROWSE;
        int stop, lots, spent, place = -1;
        long waitUntil;
        BlockPos till;
        /** Walking somewhere: the best distance so far and when it last got better (a shopper wedged by a gondola gives up on it). */
        double best = Double.MAX_VALUE; long better; boolean gaveUp, here;
        Trip(UUID villager, BlockPos desk, List<BlockPos> stops, long started) { this.villager = villager; this.desk = desk; this.stops = stops; this.started = started; }
    }

    private static final Map<UUID, Trip> TRIPS = new HashMap<>();
    /** Each till's queue, the one being served first. */
    private static final Map<BlockPos, List<UUID>> QUEUES = new HashMap<>();
    private static final Map<BlockPos, Long> NEXT_START = new HashMap<>();

    static boolean on(ServerWorld world) { MobTalkConfig c = ShopController.config(world); return c.cityShops && c.shopShoppers; }

    static boolean day(ServerWorld world) { long t = world.getTimeOfDay() % 24000; return t < 11500 || t > 23500; }

    /** From the guard's desk now and then (ShopController's staff round): a villager from the streets around sets off shopping. */
    static void maybeStart(ServerWorld world, BlockPos desk, long now) {
        if (!on(world) || !day(world)) return;
        long count = TRIPS.values().stream().filter(t -> t.desk.equals(desk)).count();
        if (count >= MAX_TRIPS) return;
        Long next = NEXT_START.get(desk);
        if (next != null && now < next) return;
        NEXT_START.put(desk.toImmutable(), now + 400 + RANDOM.nextInt(800));
        List<VillagerEntity> idle = world.getEntitiesByClass(VillagerEntity.class, new Box(desk).expand(PICK_RADIUS, 8, PICK_RADIUS), v -> shopper(v));
        if (!idle.isEmpty()) start(world, desk, idle.get(RANDOM.nextInt(idle.size())), now);
    }

    /** Who may go shopping: a grown villager, awake, not at work in the shop or the market, not already shopping. */
    static boolean shopper(VillagerEntity v) {
        if (!v.isAlive() || v.isBaby() || v.isSleeping() || TRIPS.containsKey(v.getUuid())) return false;
        VillagerProfession p = v.getVillagerData().getProfession();
        return p != ShopContent.CASHIER && p != ShopContent.SHOP_GUARD && p != ShopContent.VENDOR
                && !v.getCommandTags().contains("mobtalk_wanted");
    }

    /** Sends this villager shopping in the shop of this guard's desk (also for the self-test); false when the shop has no displays yet. */
    public static boolean start(ServerWorld world, BlockPos desk, VillagerEntity villager, long now) {
        List<BlockPos> displays = new ArrayList<>(ShopController.displaysOf(desk));
        if (displays.isEmpty()) return false;
        java.util.Collections.shuffle(displays, RANDOM);
        List<BlockPos> stops = new ArrayList<>(displays.subList(0, Math.min(displays.size(), 2 + RANDOM.nextInt(2))));
        Trip trip = new Trip(villager.getUuid(), desk.toImmutable(), stops, now);
        trip.world = world.getRegistryKey();
        TRIPS.put(villager.getUuid(), trip);
        return true;
    }

    /** For the self-test: how many are shopping, how many stand in this till's queue. */
    public static int trips() { return TRIPS.size(); }
    public static int queued(BlockPos till) { List<UUID> q = QUEUES.get(till); return q == null ? 0 : q.size(); }
    public static int served;

    /** Once a second: each shopper takes the next step of the trip. */
    static void tick(ServerWorld world, long now) {
        if (TRIPS.isEmpty()) return;
        boolean on = on(world) && day(world);
        for (Iterator<Trip> it = TRIPS.values().iterator(); it.hasNext(); ) {
            Trip trip = it.next();
            VillagerEntity v = world.getEntity(trip.villager) instanceof VillagerEntity e && e.isAlive() ? e : null;
            if (trip.world != world.getRegistryKey()) continue; // the trip's own world ticks it
            if (!on || v == null || v.isSleeping() || now - trip.started > TRIP_TICKS) {
                    leave(trip); it.remove(); continue; }
            if (step(world, v, trip, now)) { leave(trip); it.remove(); }
        }
    }

    private static void leave(Trip trip) {
        if (trip.till != null) {
            List<UUID> q = QUEUES.get(trip.till);
            if (q != null) { q.remove(trip.villager); if (q.isEmpty()) QUEUES.remove(trip.till); }
        }
    }

    /** One step; true when the trip is over. */
    private static boolean step(ServerWorld world, VillagerEntity v, Trip trip, long now) {
        switch (trip.stage) {
            case BROWSE -> {
                if (trip.stop >= trip.stops.size()) { trip.stage = Stage.QUEUE; trip.here = false; return trip.lots == 0; } // nothing taken: just goes
                BlockPos at = trip.stops.get(trip.stop);
                if (!(world.getBlockEntity(at) instanceof ShopContent.DisplayBlockEntity display)) { trip.stop++; return false; }
                BlockPos front = front(world, at);
                if (v.squaredDistanceTo(Vec3d.ofBottomCenter(front)) > 2.2 * 2.2 && !stuck(trip, v, front, now, 6) && !trip.gaveUp) {
                    ShopController.walk(v, front, 0.55f); trip.waitUntil = 0; return false;
                }
                trip.gaveUp = false; trip.here = true; // there, or as near as it gets (reaches over)
                v.getLookControl().lookAt(Vec3d.ofCenter(at));
                if (trip.waitUntil == 0) { trip.waitUntil = now + 40 + RANDOM.nextInt(80); return false; } // looks the shelf over
                if (now < trip.waitUntil) return false;
                ShopContent.DisplayBlockEntity.Lane lane = display.lanes[RANDOM.nextInt(display.lanes.length)];
                if (!lane.goods.isEmpty() && lane.stock > 0) {
                    lane.stock--; trip.lots++; trip.spent += lane.price;
                    display.changed();
                    world.playSound(null, at, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.NEUTRAL, 0.4f, 1.1f);
                }
                trip.stop++; trip.waitUntil = 0; trip.best = Double.MAX_VALUE; trip.here = false;
                return false;
            }
            case QUEUE -> {
                if (trip.till == null) {
                    trip.till = till(world, trip.desk);
                    if (trip.till == null) return now - trip.started > TRIP_TICKS / 2; // every till closed or full: browses a while longer
                    QUEUES.computeIfAbsent(trip.till, k -> new ArrayList<>()).add(trip.villager);
                }
                List<UUID> q = QUEUES.get(trip.till);
                int place = q == null ? -1 : q.indexOf(trip.villager);
                if (place < 0) return true;
                if (place != trip.place) { trip.place = place; trip.here = false; trip.best = Double.MAX_VALUE; }
                BlockPos spot = queueSpot(world, trip.till, place);
                if (v.squaredDistanceTo(Vec3d.ofBottomCenter(spot)) > 1.2 * 1.2 && !stuck(trip, v, spot, now, 3) && !trip.gaveUp) { ShopController.walk(v, spot, 0.5f); return false; }
                trip.gaveUp = false; trip.here = true; // in the queue, near enough
                v.getLookControl().lookAt(Vec3d.ofCenter(trip.till));
                if (place == 0 && ShopController.cashierAt(world, trip.till) != null) {
                    trip.stage = Stage.PAY;
                    trip.waitUntil = now + 20 + trip.lots * 10L;
                    VillagerEntity cashier = ShopController.cashierAt(world, trip.till);
                    if (cashier != null) cashier.getLookControl().lookAt(v);
                    if (RANDOM.nextInt(6) == 0) { // the classic hold-up at the till
                        trip.waitUntil += 160;
                        shout(world, cashier, trip.till, "mobtalk.shop.queue.otmena." + RANDOM.nextInt(3));
                    }
                    if (ShopController.config(world).shopSounds)
                        for (int i = 0; i < Math.min(8, trip.lots); i++) ShopController.later(world, trip.till, ShopContent.SOUND_BEEP, 0.3f, 1f, now + 10 + i * 6L);
                }
                return false;
            }
            case PAY -> {
                if (now < trip.waitUntil) return false;
                if (ShopController.config(world).shopSounds) world.playSound(null, trip.till, ShopContent.SOUND_DRAWER, SoundCategory.BLOCKS, 0.5f, 1f);
                served++;
                MobGameBridge bridge = MobGameBridge.active(world.getServer());
                if (bridge != null && bridge.memory.known(v.getUuid()))
                    bridge.event(v, "Сходил(а) в универсам: " + trip.lots + " покупок на " + trip.spent + " изумр.");
                ShopController.walk(v, trip.desk, 0.55f); // out past the guard
                return true;
            }
        }
        return true;
    }

    /** Whether the shopper has got no nearer for five seconds while within {@code near} blocks: then where it is will do. */
    private static boolean stuck(Trip trip, VillagerEntity v, BlockPos to, long now, double near) {
        if (trip.here) return true;
        double d = Math.sqrt(v.squaredDistanceTo(Vec3d.ofBottomCenter(to)));
        if (d < trip.best - 0.4) { trip.best = d; trip.better = now; return false; }
        if (now - trip.better < 100) return false;
        trip.best = Double.MAX_VALUE;
        trip.gaveUp = d > near;
        trip.here = d <= near; // close enough: stays here until the next goal
        return trip.here;
    }

    /** The cell in front of a display, where a shopper stands. */
    private static BlockPos front(ServerWorld world, BlockPos display) {
        BlockState state = world.getBlockState(display);
        Direction facing = state.contains(HorizontalFacingBlock.FACING) ? state.get(HorizontalFacingBlock.FACING) : Direction.NORTH;
        return display.offset(facing);
    }

    /** The queue runs straight out from the till's customer side: the first at the till, the rest behind. */
    static BlockPos queueSpot(ServerWorld world, BlockPos till, int place) {
        BlockState state = world.getBlockState(till);
        Direction facing = state.contains(HorizontalFacingBlock.FACING) ? state.get(HorizontalFacingBlock.FACING) : Direction.NORTH;
        return till.offset(facing, 1 + place);
    }

    /** A staffed till of this shop with room in its queue, the shortest queue first. */
    private static BlockPos till(ServerWorld world, BlockPos desk) {
        BlockPos best = null; int shortest = Integer.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(desk.add(-ShopController.DESK_REACH, -3, -ShopController.DESK_REACH), desk.add(ShopController.DESK_REACH, 3, ShopController.DESK_REACH))) {
            if (!world.getBlockState(p).isOf(ShopContent.CASH_REGISTER) || ShopController.cashierAt(world, p) == null) continue;
            int q = queued(p);
            if (q < MAX_QUEUE && q < shortest) { shortest = q; best = p.toImmutable(); }
        }
        return best;
    }

    /** A player paying at a till where villagers wait: the one at the front of the queue has a word to say. */
    static void cutIn(ServerWorld world, BlockPos till, ServerPlayerEntity player) {
        List<UUID> q = QUEUES.get(till);
        if (q == null || q.isEmpty() || RANDOM.nextInt(2) != 0) return;
        if (world.getEntity(q.get(q.size() - 1)) instanceof VillagerEntity last && last.squaredDistanceTo(player) < 6 * 6) {
            last.getLookControl().lookAt(player);
            player.sendMessage(ShopController.name(world, last).append(Text.translatable("mobtalk.shop.queue.cut." + RANDOM.nextInt(3))), false);
        }
    }

    private static void shout(ServerWorld world, VillagerEntity cashier, BlockPos till, String key) {
        if (cashier == null) return;
        Text line = ShopController.name(world, cashier).append(Text.translatable(key));
        for (ServerPlayerEntity p : world.getPlayers()) if (p.squaredDistanceTo(Vec3d.ofCenter(till)) <= 16 * 16) p.sendMessage(line, false);
    }
}
