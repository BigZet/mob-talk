package io.github.mobtalk.game.city;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** A car of the city (spec §7, docs/cars.md): one of {@link CarTypes} in one of its paint schemes, standing where it was parked.
 * It is solid (people bump into it and stand on its roof), has its seats (the driver's on the left), doors that open for whoever
 * gets in or out, lamps (head and tail lights, the brake, a beacon on a police car) and the parts a moving car needs: the client
 * steers the front wheels by {@link #steer()} and turns all four by the distance rolled.
 *
 * <p>Driving is ready to be taught: {@link #drive(float, float)} sets the pedal and the wheel, and the car moves as a bicycle model
 * (the rear axle follows the front) at the speed the pedal gives; {@link #setTarget(List, double)} follows a path of points (a
 * pure-pursuit steer toward a point ahead) and stops at its end. Nothing in the city drives yet: parked cars only stand.
 */
public final class CarEntity extends Entity {
    public static EntityType<CarEntity> TYPE;
    private static final TrackedData<String> CAR = DataTracker.registerData(CarEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<String> SCHEME = DataTracker.registerData(CarEntity.class, TrackedDataHandlerRegistry.STRING);
    /** Lamps: {@link #HEAD}, {@link #BEACON}, {@link #BRAKE}, {@link #HAZARD}. */
    private static final TrackedData<Byte> LIGHTS = DataTracker.registerData(CarEntity.class, TrackedDataHandlerRegistry.BYTE);
    /** One bit a door, open while set. */
    private static final TrackedData<Integer> DOORS = DataTracker.registerData(CarEntity.class, TrackedDataHandlerRegistry.INTEGER);
    /** The front wheels' angle, degrees, positive to the left. */
    private static final TrackedData<Float> STEER = DataTracker.registerData(CarEntity.class, TrackedDataHandlerRegistry.FLOAT);
    /** Who sits where: the network ids of the sitters, one a seat ("-1" for an empty seat), comma-separated. */
    private static final TrackedData<String> SEATING = DataTracker.registerData(CarEntity.class, TrackedDataHandlerRegistry.STRING);
    public static final int HEAD = 1, BEACON = 2, BRAKE = 4, HAZARD = 8;

    /** Server: ticks a door stays open; the seat the next passenger takes. */
    private int[] doorTicks = new int[0];
    private int pendingSeat = -1;
    /** Driving (server): pedal -1..1, speed in blocks a tick, the path being followed. */
    private float throttle;
    private double speed, cruise;
    private List<Vec3d> path = new ArrayList<>();
    private int pathIndex;

    /** Client: gliding to the last position heard; the wheels' turn (radians) and the doors' opening (0..1), now and a tick ago. */
    private int lerpTicks;
    private double lerpX, lerpY, lerpZ; private float lerpYaw, lerpPitch;
    public float wheel, prevWheel;
    public float[] door = new float[0], prevDoor = new float[0];

    public CarEntity(EntityType<? extends CarEntity> type, World world) {
        super(type, world);
    }

    // ------------------------------------------------------------ what car it is

    public CarTypes.Type type() {
        CarTypes.Type t = CarTypes.get(dataTracker.get(CAR));
        return t != null ? t : CarTypes.ALL.get(0);
    }
    public String scheme() {
        String s = dataTracker.get(SCHEME);
        return type().schemes().contains(s) ? s : type().schemes().get(0);
    }
    /** Makes this the given car in the given scheme (an unknown scheme falls back to the type's first). */
    public CarEntity setCar(String id, String scheme) {
        CarTypes.Type t = CarTypes.get(id);
        if (t == null) t = CarTypes.ALL.get(0);
        dataTracker.set(CAR, t.id());
        dataTracker.set(SCHEME, scheme != null && t.schemes().contains(scheme) ? scheme : t.schemes().get(0));
        calculateDimensions();
        refreshPosition();
        return this;
    }
    public boolean hasBeacon() { return type().beaconSchemes().contains(scheme()); }

    @Override protected Text getDefaultName() { return Text.literal(type().name()); }

    @Override protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(CAR, CarTypes.ALL.get(0).id());
        builder.add(SCHEME, "white");
        builder.add(LIGHTS, (byte) 0);
        builder.add(DOORS, 0);
        builder.add(STEER, 0f);
        builder.add(SEATING, "");
    }

    @Override public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (CAR.equals(data)) { calculateDimensions(); refreshPosition(); }
    }

    @Override protected void readCustomDataFromNbt(NbtCompound nbt) {
        setCar(nbt.getString("Car"), nbt.getString("Scheme"));
        dataTracker.set(LIGHTS, nbt.getByte("Lights"));
    }

    @Override protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putString("Car", type().id());
        nbt.putString("Scheme", scheme());
        nbt.putByte("Lights", dataTracker.get(LIGHTS));
    }

    // ------------------------------------------------------------ size and collision

    /** The footprint turned by the car's yaw: exact at the four right angles (how cars are parked), the box around the turned
     * rectangle in between, a little shrunk so that a car at 45° does not block a whole lane. */
    @Override protected Box calculateBoundingBox() {
        CarTypes.Type t = type();
        double yaw = Math.toRadians(getYaw());
        double c = Math.abs(Math.cos(yaw)), s = Math.abs(Math.sin(yaw));
        double hw = t.width() / 2, hl = t.length() / 2;
        double ex = hw * c + hl * s, ez = hw * s + hl * c;
        double shrink = 1 - 0.18 * Math.sin(2 * Math.atan2(s, c));
        ex *= shrink; ez *= shrink;
        return new Box(getX() - ex, getY(), getZ() - ez, getX() + ex, getY() + t.height(), getZ() + ez);
    }

    @Override public EntityDimensions getDimensions(EntityPose pose) {
        CarTypes.Type t = type();
        return EntityDimensions.fixed(t.width(), t.height());
    }

    @Override public void setYaw(float yaw) {
        super.setYaw(yaw);
        setBoundingBox(calculateBoundingBox());
    }

    @Override public boolean isCollidable() { return true; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean canHit() { return !isRemoved(); }
    @Override public float getStepHeight() { return 0.6f; }
    @Override public boolean shouldRender(double distance) { double d = 96 * getRenderDistanceMultiplier(); return distance < d * d; }

    /** A car cannot be broken by blows; a player in creative removes it (as a boat is broken). */
    @Override public boolean damage(DamageSource source, float amount) {
        if (getWorld().isClient || isRemoved()) return false;
        if (source.getAttacker() instanceof PlayerEntity player && player.getAbilities().creativeMode) { discard(); return true; }
        return false;
    }

    // ------------------------------------------------------------ lamps, doors, steering

    public boolean light(int bit) { return (dataTracker.get(LIGHTS) & bit) != 0; }
    public void setLight(int bit, boolean on) {
        int v = dataTracker.get(LIGHTS);
        dataTracker.set(LIGHTS, (byte) (on ? v | bit : v & ~bit));
    }
    public void setHeadlights(boolean on) { setLight(HEAD, on); }
    /** The beacon's blue (and red) lamps flash; only a car whose scheme has a beacon shows them. */
    public void setBeacon(boolean on) { setLight(BEACON, on); }

    public boolean doorOpen(int index) { return (dataTracker.get(DOORS) & (1 << index)) != 0; }
    /** Opens a door for {@code ticks} (0 closes it now). */
    public void openDoor(int index, int ticks) {
        if (index < 0 || index >= type().doors()) return;
        if (doorTicks.length != type().doors()) doorTicks = new int[type().doors()];
        doorTicks[index] = ticks;
        int v = dataTracker.get(DOORS);
        dataTracker.set(DOORS, ticks > 0 ? v | (1 << index) : v & ~(1 << index));
    }

    public float steer() { return dataTracker.get(STEER); }

    // ------------------------------------------------------------ driving (for the thread that teaches cars to drive)

    /** Pedal (-1 back .. 1 forward) and wheel (-1 right .. 1 left, a share of the type's lock). Clears a path being followed. */
    public void drive(float pedal, float wheel) {
        path.clear();
        throttle = MathHelper.clamp(pedal, -1, 1);
        dataTracker.set(STEER, MathHelper.clamp(wheel, -1, 1) * type().maxSteer());
    }

    /** Follows the points (world positions on the road, in order) at up to {@code blocksPerSecond}, then stops. */
    public void setTarget(List<Vec3d> points, double blocksPerSecond) {
        path = new ArrayList<>(points);
        pathIndex = 0;
        cruise = blocksPerSecond / 20.0;
    }

    public void stop() { path.clear(); throttle = 0; }
    public boolean moving() { return Math.abs(speed) > 1e-3; }
    public double speed() { return speed * 20; }

    private void steerAlongPath() {
        while (pathIndex < path.size() && path.get(pathIndex).subtract(getPos()).horizontalLength() < Math.max(2.5, type().length() * 0.6)) pathIndex++;
        if (pathIndex >= path.size()) { path.clear(); throttle = 0; dataTracker.set(STEER, 0f); return; }
        Vec3d to = path.get(pathIndex).subtract(getPos());
        // angle from our heading to the point, positive to the left
        Vec3d forward = Vec3d.fromPolar(0, getYaw());
        double cross = forward.z * to.x - forward.x * to.z, dot = forward.x * to.x + forward.z * to.z;
        double angle = Math.toDegrees(Math.atan2(cross, dot));
        dataTracker.set(STEER, (float) MathHelper.clamp(angle * 1.2, -type().maxSteer(), type().maxSteer()));
        double remaining = to.horizontalLength() + (path.size() - pathIndex - 1) * 4.0;
        double want = Math.min(cruise, Math.max(0.05, remaining * 0.02)) * (Math.abs(angle) > 60 ? 0.4 : 1);
        throttle = (float) MathHelper.clamp((want - speed) * 8, -1, 1);
    }

    // ------------------------------------------------------------ seats

    private int[] seating() {
        int n = type().seats().size();
        int[] out = new int[n];
        java.util.Arrays.fill(out, -1);
        String s = dataTracker.get(SEATING);
        if (!s.isEmpty()) {
            String[] parts = s.split(",");
            for (int i = 0; i < Math.min(n, parts.length); i++) {
                try { out[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException ignored) { }
            }
        }
        return out;
    }

    private void setSeating(int[] seats) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < seats.length; i++) { if (i > 0) b.append(','); b.append(seats[i]); }
        dataTracker.set(SEATING, b.toString());
    }

    /** The seat this entity sits on, or -1. */
    public int seatOf(Entity passenger) {
        int[] seats = seating();
        for (int i = 0; i < seats.length; i++) if (seats[i] == passenger.getId()) return i;
        return -1;
    }

    public Entity driver() {
        int id = seating().length > 0 ? seating()[0] : -1;
        return id < 0 ? null : getWorld().getEntityById(id);
    }

    /** Seats {@code who} on the given seat (or the first free one when -1: the driver's for a player, a passenger's for anyone
     * else); opens its door for a moment. Returns false when there is no free seat. */
    public boolean seat(Entity who, int seat) {
        int[] seats = seating();
        if (seat < 0) {
            int first = who instanceof PlayerEntity ? 0 : Math.min(1, seats.length - 1);
            for (int i = 0; i < seats.length && seat < 0; i++) { int j = (first + i) % seats.length; if (seats[j] < 0) seat = j; }
        }
        if (seat < 0 || seat >= seats.length || seats[seat] >= 0) return false;
        pendingSeat = seat;
        boolean ok = who.startRiding(this, true);
        pendingSeat = -1;
        return ok;
    }

    @Override protected boolean canAddPassenger(Entity passenger) {
        for (int id : seating()) if (id < 0) return true;
        return false;
    }

    @Override protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (getWorld().isClient) return;
        int[] seats = seating();
        int seat = pendingSeat;
        if (seat < 0 || seat >= seats.length || seats[seat] >= 0) {
            seat = -1;
            for (int i = 0; i < seats.length && seat < 0; i++) if (seats[i] < 0) seat = i;
        }
        if (seat >= 0) {
            seats[seat] = passenger.getId();
            setSeating(seats);
            openDoor(type().seatDoor().get(seat), 24);
        }
    }

    @Override protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (getWorld().isClient) return;
        int[] seats = seating();
        for (int i = 0; i < seats.length; i++) {
            if (seats[i] == passenger.getId()) { seats[i] = -1; openDoor(type().seatDoor().get(i), 24); }
        }
        setSeating(seats);
    }

    /** A click: sit on the free seat nearest to where the player stands (and its door opens). */
    @Override public ActionResult interact(PlayerEntity player, Hand hand) {
        if (player.shouldCancelInteraction()) return ActionResult.PASS;
        if (getWorld().isClient) return ActionResult.SUCCESS;
        Vec3d local = toLocal(player.getPos());
        int[] seats = seating();
        int best = -1; double bestD = Double.MAX_VALUE;
        for (int i = 0; i < seats.length; i++) {
            if (seats[i] >= 0) continue;
            CarTypes.Seat s = type().seats().get(i);
            double d = (s.x() - local.x) * (s.x() - local.x) + (s.z() - local.z) * (s.z() - local.z);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best >= 0 && seat(player, best) ? ActionResult.CONSUME : ActionResult.PASS;
    }

    /** A world point in the car's space (x left, z forward). */
    public Vec3d toLocal(Vec3d world) {
        double yaw = Math.toRadians(getYaw()), c = Math.cos(yaw), s = Math.sin(yaw);
        double dx = world.x - getX(), dz = world.z - getZ();
        return new Vec3d(dx * c + dz * s, world.y - getY(), -dx * s + dz * c);
    }

    /** A point of the car's space in the world. */
    public Vec3d toWorld(double x, double y, double z) {
        double yaw = Math.toRadians(getYaw()), c = Math.cos(yaw), s = Math.sin(yaw);
        return new Vec3d(getX() + x * c - z * s, getY() + y, getZ() + x * s + z * c);
    }

    @Override protected Vec3d getPassengerAttachmentPos(Entity passenger, EntityDimensions dimensions, float scale) {
        int seat = seatOf(passenger);
        CarTypes.Type t = type();
        CarTypes.Seat s = t.seats().get(Math.max(0, seat));
        // Minecraft's people are tall for a real car: a sitting player's eyes are 1.02 over its seat, which would put a head
        // through the roof of a Zhiguli. The sitter goes down until its eyes are a fifth of a block under the roof, its legs
        // hidden in the floor; a villager (no sitting pose of its own) is set down by its eyes the same way.
        double eye = passenger.getStandingEyeHeight();
        double attach = passenger.getVehicleAttachmentPos(this).y;
        double eyes = Math.min(s.y() + 1.02, t.height() - 0.2);
        double y = eyes - eye + attach;
        return toWorld(s.x(), y, s.z()).subtract(getPos());
    }

    @Override protected void updatePassengerPosition(Entity passenger, PositionUpdater updater) {
        super.updatePassengerPosition(passenger, updater);
        float facing = getYaw();
        passenger.setBodyYaw(facing);
        float turn = MathHelper.wrapDegrees(passenger.getYaw() - facing), kept = MathHelper.clamp(turn, -110.0F, 110.0F);
        passenger.prevYaw += kept - turn;
        passenger.setYaw(passenger.getYaw() + kept - turn);
        passenger.setHeadYaw(passenger.getYaw());
    }

    /** Getting out: beside the seat's own door, on the ground. */
    @Override public Vec3d updatePassengerForDismount(LivingEntity passenger) {
        int seat = seatOf(passenger);
        CarTypes.Type t = type();
        CarTypes.Seat s = t.seats().get(Math.max(0, seat));
        double side = s.x() >= 0 ? 1 : -1;
        if (t.kind().equals("bus") || t.kind().equals("van")) side = -1; // the doors of a bus and a van's saloon are on the right
        Vec3d out = toWorld(side * (t.width() / 2 + 0.55), 0, seat < 0 ? 0 : s.z());
        return new Vec3d(out.x, getY(), out.z);
    }

    // ------------------------------------------------------------ ticking

    @Override public void tick() {
        super.tick();
        World world = getWorld();
        if (world.isClient) {
            if (lerpTicks > 0) { lerpPosAndRotation(lerpTicks, lerpX, lerpY, lerpZ, lerpYaw, lerpPitch); lerpTicks--; setBoundingBox(calculateBoundingBox()); }
            prevWheel = wheel;
            double dx = getX() - prevX, dz = getZ() - prevZ;
            Vec3d forward = Vec3d.fromPolar(0, getYaw());
            double rolled = dx * forward.x + dz * forward.z;
            if (Math.abs(rolled) < 3) wheel += (float) (rolled / type().wheelRadius());
            int n = type().doors();
            if (door.length != n) { door = new float[n]; prevDoor = new float[n]; }
            for (int i = 0; i < n; i++) {
                prevDoor[i] = door[i];
                door[i] = MathHelper.clamp(door[i] + (doorOpen(i) ? 0.12f : -0.12f), 0, 1);
            }
            return;
        }
        for (int i = 0; i < doorTicks.length; i++) if (doorTicks[i] > 0 && --doorTicks[i] == 0) openDoor(i, 0);
        if (!path.isEmpty()) steerAlongPath();
        Entity driver = driver();
        boolean driven = !path.isEmpty() || throttle != 0 || Math.abs(speed) > 1e-4;
        if (!driven && isOnGround() && driver == null) { setVelocity(Vec3d.ZERO); return; }
        // the pedal: up to 0.6 blocks a tick forward (43 km/h), a third of that back; rolling slows by itself
        double top = throttle >= 0 ? 0.6 : 0.2;
        speed += throttle * 0.02;
        speed *= throttle == 0 ? 0.94 : 0.995;
        speed = MathHelper.clamp(speed, -top, top);
        if (Math.abs(speed) < 1e-4 && throttle == 0) speed = 0;
        setLight(BRAKE, throttle < 0 && speed > 0 || throttle == 0 && Math.abs(speed) > 1e-3);
        CarTypes.Type t = type();
        if (Math.abs(speed) > 1e-4) {
            double yawRate = Math.toDegrees(speed / t.wheelBase() * Math.tan(Math.toRadians(steer())));
            setYaw(getYaw() - (float) yawRate);
        }
        Vec3d forward = Vec3d.fromPolar(0, getYaw()).multiply(speed);
        double vy = isOnGround() ? 0 : getVelocity().y - 0.08;
        setVelocity(forward.x, vy, forward.z);
        move(MovementType.SELF, getVelocity());
        if (horizontalCollision) speed *= 0.2;
    }

    @Override public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps) {
        lerpX = x; lerpY = y; lerpZ = z; lerpYaw = yaw; lerpPitch = pitch; lerpTicks = Math.max(steps, 3);
    }
    @Override public double getLerpTargetX() { return lerpTicks > 0 ? lerpX : getX(); }
    @Override public double getLerpTargetY() { return lerpTicks > 0 ? lerpY : getY(); }
    @Override public double getLerpTargetZ() { return lerpTicks > 0 ? lerpZ : getZ(); }
    @Override public float getLerpTargetPitch() { return lerpTicks > 0 ? lerpPitch : getPitch(); }
    @Override public float getLerpTargetYaw() { return lerpTicks > 0 ? lerpYaw : getYaw(); }

    /** For the server's own code: a car of the given type and scheme at a point, facing yaw (0 = south, as entities face). */
    public static CarEntity create(ServerWorld world, String id, String scheme, double x, double y, double z, float yaw) {
        CarEntity car = new CarEntity(TYPE, world);
        car.setCar(id, scheme);
        car.refreshPositionAndAngles(x, y, z, yaw, 0);
        car.setYaw(yaw);
        return car;
    }
}
