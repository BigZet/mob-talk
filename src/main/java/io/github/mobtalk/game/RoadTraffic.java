package io.github.mobtalk.game;

import io.github.mobtalk.core.RoadPlan;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.HorseColor;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;
import net.minecraft.world.PersistentState;

/** The traffic of the village roads (docs/roads.md): carts and covered wagons with villagers in them, drawn by a horse, and
 * horsemen, going back and forth between the two villages of a road at a walk, each in the right-hand lane. A trip lives as a
 * place on its road (how far along, which way), saved with the world; it takes shape as real entities only near a player, and
 * goes on without them elsewhere. The horse follows the road; the cart is pulled after it (it keeps the shafts' length from the
 * horse, as NiftyCarts pulls its carts), so it swings wide on a bend and round in the turn at each end. A trip waits for a player
 * standing in its way. The entities are the traffic's alone: never saved with the chunk, tagged, and any left over from a crash are
 * removed when they load. Server thread only. */
final class RoadTraffic {
    static final String TAG = "mobtalk_road";
    /** Walking speed (blocks a tick) of a cart and of a horseman, the lane's distance from the middle, the rest at each end. */
    static final double CART_SPEED = 0.1, RIDER_SPEED = 0.14, LANE = 1.2;
    static final int REST = 600, TURN = 60;
    /** A trip takes shape this near a player and goes back to being a place on the road farther than this. */
    static final double APPEAR = 96, VANISH = 128;
    /** The escort rides this far behind the wagon. */
    static final double ESCORT_GAP = 3.8;

    enum Kind { CART, WAGON, RIDER }

    private final MobGameBridge bridge;
    private final Map<String, World> worlds = new HashMap<>();
    /** Traffic entities now in the world, and the ones that loaded from disk (a crash left them): removed on the next tick. */
    private final Set<UUID> live = new HashSet<>();
    private final List<Entity> stale = new ArrayList<>();
    /** A spot that counts as a player for a while (the command's {@code bring} from the console, and the self-test). */
    private Vec3d watch; private long watchUntil;

    RoadTraffic(MobGameBridge bridge) { this.bridge = bridge; }

    private int perRoad() { return bridge.config.enabled && bridge.config.villageRoads ? Math.max(0, bridge.config.roadTraffic) : 0; }

    // ---- Saved state ----

    static final class Trip {
        int road, slot; Kind kind; double s; int dir = 1; int rest, turn; int wood, roof, horse; boolean escort;
        /** The entities while the trip has taken shape near a player, and where the cart stands. */
        transient AbstractHorseEntity steed; transient RoadCartEntity cart; transient final List<VillagerEntity> people = new ArrayList<>();
        transient AbstractHorseEntity escortHorse; transient VillagerEntity escortRider;
        transient Vec3d cartAt;

        NbtCompound write() {
            NbtCompound tag = new NbtCompound();
            tag.putInt("road", road); tag.putInt("slot", slot); tag.putString("kind", kind.name()); tag.putDouble("s", s); tag.putInt("dir", dir);
            tag.putInt("rest", rest); tag.putInt("wood", wood); tag.putInt("roof", roof); tag.putInt("horse", horse); tag.putBoolean("escort", escort);
            return tag;
        }
        static Trip read(NbtCompound tag) {
            Trip trip = new Trip();
            trip.road = tag.getInt("road"); trip.slot = tag.getInt("slot");
            try { trip.kind = Kind.valueOf(tag.getString("kind")); } catch (IllegalArgumentException unknown) { trip.kind = Kind.CART; }
            trip.s = tag.getDouble("s"); trip.dir = tag.getInt("dir") < 0 ? -1 : 1; trip.rest = tag.getInt("rest");
            trip.wood = tag.getInt("wood"); trip.roof = tag.getInt("roof"); trip.horse = tag.getInt("horse"); trip.escort = tag.getBoolean("escort");
            return trip;
        }
        boolean shaped() { return steed != null; }
    }

    static final class Saved extends PersistentState {
        static final PersistentState.Type<Saved> TYPE = new PersistentState.Type<>(Saved::new, Saved::read, null);
        final List<Trip> trips = new ArrayList<>();
        static Saved read(NbtCompound tag, RegistryWrapper.WrapperLookup lookup) {
            Saved saved = new Saved();
            NbtList list = tag.getList("trips", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) saved.trips.add(Trip.read(list.getCompound(i)));
            return saved;
        }
        @Override public NbtCompound writeNbt(NbtCompound tag, RegistryWrapper.WrapperLookup lookup) {
            NbtList list = new NbtList();
            for (Trip trip : trips) list.add(trip.write());
            tag.put("trips", list);
            return tag;
        }
    }

    private static final class World { Saved saved; }

    private World world(ServerWorld world) {
        World state = worlds.computeIfAbsent(world.getRegistryKey().getValue().toString(), k -> new World());
        if (state.saved == null) state.saved = world.getPersistentStateManager().getOrCreate(Saved.TYPE, "mobtalk_road_traffic");
        return state;
    }

    /** The world is going away (or the bridge): every trip that has taken shape goes back to being a place on its road. */
    void clearRuntime() {
        for (World state : worlds.values()) if (state.saved != null) for (Trip trip : state.saved.trips) vanish(trip);
        worlds.clear();
    }

    /** A traffic entity came in from disk: it was left by a crash (the traffic never saves them), and goes on the next tick. */
    void loaded(Entity entity) {
        if (entity.getCommandTags().contains(TAG) && !live.contains(entity.getUuid())) stale.add(entity);
    }

    // ---- The tick ----

    void tick(ServerWorld world) {
        if (!stale.isEmpty()) { for (Entity entity : stale) if (!entity.isRemoved()) entity.discard(); stale.clear(); }
        World state = world(world);
        long now = world.getTime();
        if (now % 200 == 53) plan(world, state);
        for (Iterator<Trip> it = state.saved.trips.iterator(); it.hasNext(); ) {
            Trip trip = it.next();
            RoadNetwork.Road road = bridge.roads.road(world, trip.road);
            if (road == null || trip.slot >= perRoad()) { vanish(trip); it.remove(); state.saved.markDirty(); continue; }
            if (now % 20 == trip.slot) shape(world, road, trip);
            step(world, state, road, trip);
        }
        if (now % 200 == 7) state.saved.markDirty();
    }

    /** Every road gets its trips: as many as the setting asks, spread along it, each its own kind. */
    private void plan(ServerWorld world, World state) {
        int wanted = perRoad();
        for (RoadNetwork.Road road : bridge.roads.roads(world)) {
            if (road.done.isEmpty()) continue; // not a stone of it laid yet
            Set<Integer> have = new HashSet<>();
            for (Trip trip : state.saved.trips) if (trip.road == road.id) have.add(trip.slot);
            for (int slot = 0; slot < wanted; slot++) {
                if (have.contains(slot)) continue;
                Random random = new Random(world.getSeed() * 31 + road.id * 977L + slot);
                Trip trip = new Trip();
                trip.road = road.id; trip.slot = slot;
                trip.kind = slot % 3 == 0 ? Kind.CART : slot % 3 == 1 ? Kind.WAGON : Kind.RIDER;
                int[] range = range(road);
                trip.s = range[0] + (range[1] - range[0]) * (slot + 0.5) / wanted;
                trip.dir = random.nextBoolean() ? 1 : -1;
                trip.wood = random.nextInt(RoadCartEntity.WOODS.length); trip.roof = random.nextInt(RoadCartEntity.ROOFS.length);
                trip.horse = random.nextInt(HorseColor.values().length); trip.escort = trip.kind == Kind.WAGON && random.nextInt(3) > 0;
                state.saved.trips.add(trip);
                state.saved.markDirty();
            }
        }
    }

    /** The part of the road a trip runs: from where it leaves the one village to where it meets the other. */
    private static int[] range(RoadNetwork.Road road) {
        int n = road.points.size();
        int from = road.from == Integer.MIN_VALUE ? 0 : road.from, to = road.to == Integer.MIN_VALUE ? n - 1 : road.to;
        if (to - from < 8) { from = 0; to = n - 1; }
        return new int[] { from, to };
    }

    private double speed(Trip trip) { return trip.kind == Kind.RIDER ? RIDER_SPEED : CART_SPEED; }

    /** How far the horse's centre is ahead of the end of the trip (the cart, or the escort, behind it). */
    private static double length(Trip trip) {
        double cart = trip.kind == Kind.WAGON ? 3.2 : trip.kind == Kind.CART ? 2.4 : 0;
        return cart + (trip.escort ? ESCORT_GAP + 1 : 0);
    }

    private void step(ServerWorld world, World state, RoadNetwork.Road road, Trip trip) {
        int[] range = range(road);
        if (trip.turn > 0) { // turning round at the end; a player in the way is waited for
            if (!(trip.shaped() && blocked(world, state, road, trip)) && --trip.turn == 0) turnAround(road, trip, range);
            if (trip.shaped()) place(world, road, trip);
            return;
        }
        if (trip.rest > 0) { // resting at the village; then the turn (at once where nobody sees it)
            if (--trip.rest == 0) { if (trip.shaped()) trip.turn = TURN; else turnAround(road, trip, range); }
            if (trip.shaped()) place(world, road, trip);
            return;
        }
        boolean held = trip.shaped() && (blocked(world, state, road, trip) || !laidAhead(world, road, trip));
        if (!held) {
            trip.s += trip.dir * speed(trip);
            double end = trip.dir > 0 ? range[1] : range[0];
            if ((trip.s - end) * trip.dir >= 0) { trip.s = end; trip.rest = REST; }
        }
        if (trip.shaped()) place(world, road, trip);
    }

    /** After the turn the horse stands in the other lane, facing back the way it came (the cart's swung round behind it). */
    private static void turnAround(RoadNetwork.Road road, Trip trip, int[] range) {
        trip.dir = -trip.dir;
        trip.s = MathHelper.clamp(trip.s, range[0], range[1]);
    }

    /** A player in the way just ahead of the horse, or another trip going the same way close ahead. */
    private boolean blocked(ServerWorld world, World state, RoadNetwork.Road road, Trip trip) {
        Vec3d head = trip.steed.getPos();
        Frame frame = frame(road, trip.s, trip.dir);
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.isSpectator() || player.hasVehicle()) continue;
            Vec3d to = player.getPos().subtract(head);
            double ahead = to.x * frame.fx + to.z * frame.fz, across = to.x * -frame.fz + to.z * frame.fx;
            if (ahead > 0.3 && ahead < 4.5 && Math.abs(across) < 1.6 && Math.abs(to.y) < 2.5) return true;
        }
        if (trip.turn > 0) return false;
        for (Trip other : state.saved.trips) {
            if (other == trip || other.road != trip.road || other.dir != trip.dir) continue;
            double gap = (other.s - trip.s) * trip.dir;
            if (gap > 0 && gap < length(other) + 4.5) return true;
        }
        return false;
    }

    /** The road a few blocks ahead is laid (a chunk not laid yet is near a player, so it soon will be). */
    private boolean laidAhead(ServerWorld world, RoadNetwork.Road road, Trip trip) {
        Frame frame = frame(road, MathHelper.clamp(trip.s + trip.dir * 6, 0, road.points.size() - 1), trip.dir);
        return road.done.contains(ChunkPos.toLong(MathHelper.floor(frame.x) >> 4, MathHelper.floor(frame.z) >> 4));
    }

    // ---- Where things stand ----

    /** A place on the road: the middle of the road at arc length {@code s}, its height, and the way forward for {@code dir}. */
    record Frame(double x, double y, double z, double fx, double fz) {
        Vec3d lane(double offset) { return new Vec3d(x - fz * offset, y, z + fx * offset); }
        float yaw() { return (float) Math.toDegrees(Math.atan2(-fx, fz)); }
    }

    static Frame frame(RoadNetwork.Road road, double s, int dir) {
        List<RoadPlan.Point> points = road.points;
        int n = points.size();
        double t = MathHelper.clamp(s, 0, n - 1);
        int i = Math.min(n - 2, (int) Math.floor(t));
        double f = t - i;
        RoadPlan.Point a = points.get(i), b = points.get(i + 1);
        double x = a.x() + (b.x() - a.x()) * f, z = a.z() + (b.z() - a.z()) * f, y = (a.h2() + (b.h2() - a.h2()) * f) / 2.0;
        // The heading, smoothed over a few blocks so that the horse does not twitch at every point.
        RoadPlan.Point ahead = points.get(Math.min(n - 1, i + 3)), behind = points.get(Math.max(0, i - 2));
        double fx = ahead.x() - behind.x(), fz = ahead.z() - behind.z(), d = Math.hypot(fx, fz);
        if (d < 1e-6) { fx = a.dx(); fz = a.dz(); d = Math.hypot(fx, fz); }
        return new Frame(x, y, z, fx / d * dir, fz / d * dir);
    }

    /** Puts the trip's entities where they belong this tick: the horse in its lane (or on its turning circle), the cart pulled after it. */
    private void place(ServerWorld world, RoadNetwork.Road road, Trip trip) {
        if (!alive(trip)) { vanish(trip); return; }
        Frame frame = frame(road, trip.s, trip.dir);
        Vec3d head; float yaw;
        if (trip.turn > 0) {
            // The turn: a half circle to the left about the middle of the road, from this lane to the other.
            double phi = Math.PI * (TURN - trip.turn) / (double) TURN;
            double rx = -frame.fz, rz = frame.fx, ox = rx * Math.cos(phi) + frame.fx * Math.sin(phi), oz = rz * Math.cos(phi) + frame.fz * Math.sin(phi);
            head = new Vec3d(frame.x + ox * LANE, frame.y, frame.z + oz * LANE);
            double hx = -rx * Math.sin(phi) + frame.fx * Math.cos(phi), hz = -rz * Math.sin(phi) + frame.fz * Math.cos(phi);
            yaw = (float) Math.toDegrees(Math.atan2(-hx, hz));
        } else {
            head = frame.lane(LANE);
            yaw = frame.yaw();
        }
        move(trip.steed, head, yaw, 0);
        Vec3d back = head; float backYaw = yaw;
        if (trip.cart != null) {
            double reach = trip.cart.reach();
            Vec3d cart = trip.cartAt == null ? frame(road, trip.s - trip.dir * reach, trip.dir).lane(LANE) : trip.cartAt;
            Vec3d pull = new Vec3d(head.x - cart.x, 0, head.z - cart.z);
            double length = pull.length();
            if (length > 1e-4 && length != reach) cart = new Vec3d(head.x - pull.x / length * reach, cart.y, head.z - pull.z / length * reach);
            // The height where the cart's wheels are, read along the road: smooth over the half-block steps.
            double cartY = frame(road, trip.turn > 0 ? trip.s : trip.s - trip.dir * reach, trip.dir).y;
            cart = new Vec3d(cart.x, cartY, cart.z);
            trip.cartAt = cart;
            float cartYaw = length > 1e-4 ? (float) Math.toDegrees(Math.atan2(-(head.x - cart.x), head.z - cart.z)) : yaw;
            float pitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(head.y - cart.y, reach)), -15f, 15f);
            move(trip.cart, cart, cartYaw, pitch);
            back = cart; backYaw = cartYaw;
        }
        if (trip.escortHorse != null) {
            double rad = Math.toRadians(backYaw), bx = Math.sin(rad), bz = -Math.cos(rad); // behind: against the heading
            Vec3d at = new Vec3d(back.x + bx * ESCORT_GAP, back.y, back.z + bz * ESCORT_GAP);
            at = new Vec3d(at.x, frame(road, nearest(road, at, trip.s), trip.dir).y, at.z);
            move(trip.escortHorse, at, backYaw, 0);
        }
    }

    /** The arc length of the road point nearest to {@code at}, looked for close to {@code s}. */
    private static double nearest(RoadNetwork.Road road, Vec3d at, double s) {
        int n = road.points.size(), best = (int) MathHelper.clamp(Math.round(s), 0, n - 1);
        double bestD = Double.MAX_VALUE;
        for (int i = Math.max(0, best - 12); i <= Math.min(n - 1, best + 12); i++) {
            RoadPlan.Point p = road.points.get(i);
            double d = (p.x() - at.x) * (p.x() - at.x) + (p.z() - at.z) * (p.z() - at.z);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private static void move(Entity entity, Vec3d at, float yaw, float pitch) {
        entity.setVelocity(Vec3d.ZERO);
        entity.refreshPositionAndAngles(at.x, at.y, at.z, yaw, pitch);
        if (entity instanceof net.minecraft.entity.LivingEntity living) { living.setBodyYaw(yaw); living.setHeadYaw(yaw); }
    }

    // ---- Taking shape and going back ----

    /** Near a player, in a chunk that ticks and where the road is laid: the trip's entities come into being; far away they go. */
    private void shape(ServerWorld world, RoadNetwork.Road road, Trip trip) {
        Frame frame = frame(road, trip.s, trip.dir);
        BlockPos at = BlockPos.ofFloored(frame.x, frame.y, frame.z);
        double nearest = Double.MAX_VALUE;
        for (ServerPlayerEntity player : world.getPlayers()) nearest = Math.min(nearest, Math.hypot(player.getX() - frame.x, player.getZ() - frame.z));
        if (watch != null && world.getTime() < watchUntil) nearest = Math.min(nearest, Math.hypot(watch.x - frame.x, watch.z - frame.z));
        boolean ticking = world.shouldTickEntity(at) && road.done.contains(ChunkPos.toLong(at.getX() >> 4, at.getZ() >> 4));
        if (trip.shaped() && (nearest > VANISH || !ticking || !alive(trip))) vanish(trip);
        else if (!trip.shaped() && nearest < APPEAR && ticking) appear(world, road, trip, frame);
    }

    private static boolean alive(Trip trip) {
        if (trip.steed == null || trip.steed.isRemoved()) return false;
        if (trip.kind != Kind.RIDER && (trip.cart == null || trip.cart.isRemoved())) return false;
        return trip.escortHorse == null || !trip.escortHorse.isRemoved();
    }

    private void appear(ServerWorld world, RoadNetwork.Road road, Trip trip, Frame frame) {
        Random random = new Random(world.getTime() * 13 + trip.road * 31L + trip.slot);
        Vec3d head = frame.lane(LANE);
        trip.steed = horse(world, head, frame.yaw(), trip.horse, trip.kind == Kind.RIDER);
        if (trip.steed == null) return;
        VillagerType type = VillagerType.forBiome(world.getBiome(BlockPos.ofFloored(head)));
        if (trip.kind == Kind.RIDER) {
            VillagerEntity rider = horseman(world, head, type, random);
            if (rider != null) { rider.startRiding(trip.steed, true); trip.people.add(rider); }
        } else {
            RoadCartEntity cart = (trip.kind == Kind.WAGON ? RoadCartEntity.WAGON : RoadCartEntity.CART).create(world);
            if (cart == null) { vanish(trip); return; }
            Frame back = frame(road, trip.s - trip.dir * cart.reach(), trip.dir);
            Vec3d at = back.lane(LANE);
            cart.refreshPositionAndAngles(at.x, at.y, at.z, back.yaw(), 0);
            cart.look(trip.wood, trip.roof);
            cart.addCommandTag(TAG);
            live.add(cart.getUuid()); // before it is spawned: its load is heard at once
            world.spawnEntity(cart);
            trip.cart = cart; trip.cartAt = at;
            int people = trip.kind == Kind.WAGON ? 2 + random.nextInt(3) : 1 + random.nextInt(2);
            for (int k = 0; k < people; k++) {
                VillagerEntity traveller = villager(world, at, type, random);
                if (traveller == null) continue;
                if (!traveller.startRiding(cart, true)) { traveller.discard(); continue; }
                trip.people.add(traveller);
            }
            if (trip.escort) {
                Frame behind = frame(road, trip.s - trip.dir * (cart.reach() + ESCORT_GAP), trip.dir);
                Vec3d spot = behind.lane(LANE);
                trip.escortHorse = horse(world, spot, behind.yaw(), (trip.horse + 3) % HorseColor.values().length, true);
                VillagerEntity rider = trip.escortHorse == null ? null : horseman(world, spot, type, random);
                if (rider != null) { rider.startRiding(trip.escortHorse, true); trip.escortRider = rider; }
            }
        }
        place(world, road, trip);
    }

    /** Takes the trip's entities out of the world; the trip goes on as a place on its road. */
    private void vanish(Trip trip) {
        List<Entity> all = new ArrayList<>(trip.people);
        if (trip.escortRider != null) all.add(trip.escortRider);
        if (trip.cart != null) all.add(trip.cart);
        if (trip.steed != null) all.add(trip.steed);
        if (trip.escortHorse != null) all.add(trip.escortHorse);
        for (Entity entity : all) { live.remove(entity.getUuid()); if (!entity.isRemoved()) { entity.stopRiding(); entity.discard(); } }
        trip.people.clear(); trip.cart = null; trip.steed = null; trip.escortHorse = null; trip.escortRider = null; trip.cartAt = null;
        if (trip.turn > 0) { trip.turn = 0; trip.dir = -trip.dir; } // a turn begun is finished at once
    }

    private AbstractHorseEntity horse(ServerWorld world, Vec3d at, float yaw, int color, boolean saddled) {
        HorseEntity horse = EntityType.HORSE.create(world);
        if (horse == null) return null;
        horse.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0);
        horse.initialize(world, world.getLocalDifficulty(BlockPos.ofFloored(at)), SpawnReason.MOB_SUMMONED, null);
        horse.setVariant(HorseColor.values()[Math.floorMod(color, HorseColor.values().length)]);
        horse.setTame(true);
        horse.setAiDisabled(true);
        horse.setNoGravity(true);
        horse.setInvulnerable(true);
        horse.setSilent(false);
        if (saddled) horse.saddle(new ItemStack(Items.SADDLE), null);
        horse.addCommandTag(TAG);
        live.add(horse.getUuid());
        world.spawnEntity(horse);
        return horse;
    }

    /** A traveller: a grown villager of the biome's kind, of one of the trades that go to market. */
    private VillagerEntity villager(ServerWorld world, Vec3d at, VillagerType type, Random random) {
        VillagerProfession[] trades = { VillagerProfession.FARMER, VillagerProfession.FISHERMAN, VillagerProfession.SHEPHERD, VillagerProfession.FLETCHER,
                VillagerProfession.LIBRARIAN, VillagerProfession.CLERIC, VillagerProfession.BUTCHER, VillagerProfession.NONE };
        return spawnVillager(world, at, type, trades[random.nextInt(trades.length)]);
    }

    /** A horseman of the road: the village defence's cavalryman when that trade exists in this game, otherwise a rider in leather
     * with a sword. */
    private VillagerEntity horseman(ServerWorld world, Vec3d at, VillagerType type, Random random) {
        Identifier cavalry = Identifier.of("mobtalk", "cavalry");
        VillagerProfession trade = Registries.VILLAGER_PROFESSION.containsId(cavalry) ? Registries.VILLAGER_PROFESSION.get(cavalry) : VillagerProfession.NONE;
        VillagerEntity rider = spawnVillager(world, at, type, trade);
        if (rider == null) return null;
        rider.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        rider.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        rider.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
        rider.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
        rider.equipStack(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
        for (EquipmentSlot slot : EquipmentSlot.values()) rider.setEquipmentDropChance(slot, 0f);
        return rider;
    }

    private VillagerEntity spawnVillager(ServerWorld world, Vec3d at, VillagerType type, VillagerProfession trade) {
        VillagerEntity villager = EntityType.VILLAGER.create(world);
        if (villager == null) return null;
        villager.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
        villager.initialize(world, world.getLocalDifficulty(BlockPos.ofFloored(at)), SpawnReason.MOB_SUMMONED, null);
        villager.setVillagerData(new VillagerData(type, trade, 1));
        villager.setExperience(1); // keeps the trade without a work place
        villager.setBreedingAge(0);
        villager.addCommandTag(TAG);
        live.add(villager.getUuid());
        world.spawnEntity(villager);
        return villager;
    }

    // ---- For the command ----

    List<String> status(ServerWorld world) {
        List<String> out = new ArrayList<>();
        World state = world(world);
        for (Trip trip : state.saved.trips) {
            RoadNetwork.Road road = bridge.roads.road(world, trip.road);
            Frame frame = road == null ? null : frame(road, trip.s, trip.dir);
            out.add("#" + trip.road + "/" + trip.slot + " " + trip.kind + (trip.escort ? "+конвой" : "") + " в " + (frame == null ? "?" : (int) frame.x + " " + (int) frame.z)
                    + (trip.dir > 0 ? " →" : " ←") + (trip.rest > 0 ? " стоит " + trip.rest / 20 + " с" : "") + (trip.shaped() ? " (в мире)" : ""));
        }
        return out;
    }

    /** Puts a trip of the road right here (for the self-test and the command): it takes shape at once if a player is near. */
    String bring(ServerWorld world, int roadId, double x, double z, String kind) {
        RoadNetwork.Road road = bridge.roads.road(world, roadId);
        if (road == null) return "нет дороги " + roadId;
        World state = world(world);
        if (state.saved.trips.stream().noneMatch(trip -> trip.road == roadId)) plan(world, state);
        Kind wanted = Kind.WAGON;
        for (Kind k : Kind.values()) if (k.name().equalsIgnoreCase(kind)) wanted = k;
        Trip best = null;
        for (Trip trip : state.saved.trips) if (trip.road == roadId && (best == null || trip.kind == wanted)) best = trip;
        if (best == null) return "у дороги " + roadId + " нет поездок (движение выключено?)";
        vanish(best);
        watch = new Vec3d(x, 0, z); watchUntil = world.getTime() + 20 * 300;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < road.points.size(); i++) {
            RoadPlan.Point p = road.points.get(i);
            double d = (p.x() - x) * (p.x() - x) + (p.z() - z) * (p.z() - z);
            if (d < bestD) { bestD = d; best.s = i; }
        }
        int[] range = range(road);
        best.s = MathHelper.clamp(best.s, range[0] + 6, range[1] - 6);
        best.rest = 0; best.turn = 0;
        shape(world, road, best);
        return "поездка #" + roadId + "/" + best.slot + " " + best.kind + " у " + (int) frame(road, best.s, best.dir).x + " " + (int) frame(road, best.s, best.dir).z;
    }
}
