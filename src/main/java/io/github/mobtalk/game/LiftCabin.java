package io.github.mobtalk.game;

import io.github.mobtalk.server.ServerConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** The lift's cabin: a 2 x 3 x 2 box that runs up and down its shaft. It stands at a floor with its doors and the landing's doors
 * open, takes in whoever steps in, and when it leaves carries them as standing passengers (so the ride is smooth for the
 * player); at the next floor it lets them off where they stood. Calls are served one after another, as in the old Soviet lifts
 * (no collecting on the way). СТОП halts it between floors; a floor button sets it off again; ВЫЗОВ rings the dispatcher.
 * The client runs the same motion from the tracked target, so the cabin glides without waiting for position packets. */
public class LiftCabin extends Entity {
    private static final TrackedData<Integer> FACING = DataTracker.registerData(LiftCabin.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> FLOORS = DataTracker.registerData(LiftCabin.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> STYLE = DataTracker.registerData(LiftCabin.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> FLOOR = DataTracker.registerData(LiftCabin.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> CALLS = DataTracker.registerData(LiftCabin.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Boolean> DOORS = DataTracker.registerData(LiftCabin.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> MOVING = DataTracker.registerData(LiftCabin.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Float> TARGET = DataTracker.registerData(LiftCabin.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> SPEED = DataTracker.registerData(LiftCabin.class, TrackedDataHandlerRegistry.FLOAT);

    /** Acceleration, blocks a second squared. */
    static final double ACCEL = 0.9;

    enum State { IDLE, OPENING, OPEN, CLOSING, MOVING }

    private BlockPos machine = BlockPos.ORIGIN;
    private BlockPos origin = BlockPos.ORIGIN;
    private List<Integer> slabs = new ArrayList<>();   // slab row of each floor, lowest first
    private final LinkedHashSet<Integer> calls = new LinkedHashSet<>();
    private State state = State.IDLE;
    private int timer, target = -1, checkTimer;
    private double velocity;
    private final Map<UUID, Vec3d> seats = new HashMap<>();
    private final LiftRides rides = new LiftRides(this);

    // client: the doors' slide and the prediction's speed
    public float doorProgress, prevDoorProgress;
    private double clientVelocity;

    public LiftCabin(EntityType<? extends LiftCabin> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
    }

    @Override protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(FACING, Direction.NORTH.getHorizontal()).add(FLOORS, 2).add(STYLE, 0).add(FLOOR, 0).add(CALLS, 0).add(DOORS, false).add(MOVING, false)
                .add(TARGET, 0f).add(SPEED, 1.5f);
    }

    void setup(BlockPos machine, Lifts.Layout layout, List<Integer> floors, LiftBlocks.Style style) {
        this.machine = machine;
        this.origin = layout.origin().withY(0);
        this.slabs = new ArrayList<>(floors);
        dataTracker.set(FACING, layout.facing().getHorizontal());
        dataTracker.set(FLOORS, floors.size());
        dataTracker.set(STYLE, style.ordinal());
        dataTracker.set(FLOOR, 0);
        refreshPositionAndAngles(origin.getX() + 1, feet(0), origin.getZ() + 1, 0, 0);
        placeFloor(0, true);
    }

    // ---------------------------------------------------------------- what the others ask

    public Direction facing() { return Direction.fromHorizontal(dataTracker.get(FACING)); }
    public int floors() { return dataTracker.get(FLOORS); }
    /** Its look, the shaft doors' (see {@link LiftBlocks.Style}). */
    public LiftBlocks.Style style() { return LiftBlocks.Style.of(dataTracker.get(STYLE)); }
    /** The floor it stands at or last passed, 0 = the lowest. */
    public int floor() { return dataTracker.get(FLOOR); }
    /** Lit buttons on the panel: bit i = floor i is called. */
    public int calls() { return dataTracker.get(CALLS); }
    public boolean doorsOpen() { return dataTracker.get(DOORS); }
    public boolean moving() { return dataTracker.get(MOVING); }
    /** Where the feet will be when it stops (while moving). */
    public float targetY() { return dataTracker.get(TARGET); }
    public Lifts.Layout layout() { return Lifts.Layout.of(origin.withY(slabs.isEmpty() ? 0 : slabs.get(0)), facing()); }
    public List<Integer> slabs() { return slabs; }
    State state() { return state; }

    double feet(int floor) { return slabs.get(floor) + 1; }

    /** Whether an entity stands inside the cabin (its feet within the box). */
    public boolean contains(Entity e) {
        Box b = getBoundingBox();
        Vec3d p = e.getPos();
        return p.x > b.minX && p.x < b.maxX && p.z > b.minZ && p.z < b.maxZ && p.y > b.minY - 0.5 && p.y < b.maxY - 1;
    }

    /** The cabin serving a shaft, if loaded. */
    static LiftCabin find(World world, Lifts.Layout layout) {
        BlockPos o = layout.origin();
        Box column = new Box(o.getX(), world.getBottomY(), o.getZ(), o.getX() + 2, world.getTopY(), o.getZ() + 2);
        List<LiftCabin> found = world.getEntitiesByClass(LiftCabin.class, column, c -> !c.isRemoved());
        return found.isEmpty() ? null : found.get(0);
    }

    /** A call from the landing of the floor with this slab row. */
    public void call(int slabY) {
        int f = slabs.indexOf(slabY);
        if (f < 0) return;
        Lifts.Layout l = layout();
        if (f == floor() && (state == State.OPEN || state == State.OPENING || state == State.CLOSING || state == State.IDLE && atFloor())) {
            if (state != State.OPEN) open();
            else timer = Math.max(timer, doorTicks());
            return;
        }
        LiftBlocks.ButtonBlock.light(getWorld(), l, slabY, true);
        calls.add(f);
        syncCalls();
    }

    /** A panel button from inside. */
    public void press(int button, PlayerEntity player) {
        sound(Lifts.CLICK, 0.7f, 1f);
        if (button == Lifts.BELL_BUTTON) {
            sound(Lifts.BELL, 1f, 1f);
            if (player != null) player.sendMessage(Text.translatable("mobtalk.lift.dispatcher").formatted(Formatting.GRAY), false);
            return;
        }
        if (button == Lifts.STOP_BUTTON) {
            if (state == State.MOVING) halt();
            return;
        }
        if (button < 0 || button >= slabs.size()) return;
        if (button == floor() && atFloor()) {
            if (state != State.MOVING) open();
            return;
        }
        calls.add(button);
        syncCalls();
        if (state == State.OPEN || state == State.OPENING) timer = Math.min(timer, 20);
    }

    /** The cabin stands level with its floor (not stopped between floors). */
    public boolean atFloor() {
        return !slabs.isEmpty() && Math.abs(getY() - feet(floor())) < 0.01 && state != State.MOVING;
    }

    private void syncCalls() {
        int bits = 0;
        for (int c : calls) if (c < 31) bits |= 1 << c;
        dataTracker.set(CALLS, bits);
    }

    // ---------------------------------------------------------------- ticking

    @Override public void tick() {
        if (getWorld().isClient) {
            clientTick();
            return;
        }
        if (slabs.size() < 2) {
            discard();
            return;
        }
        if (++checkTimer % 100 == 0 && !getWorld().getBlockState(machine).isOf(Lifts.MACHINE) && getWorld().isChunkLoaded(machine)) {
            placeFloor(floor(), false);
            removeAllPassengers();
            discard();
            return;
        }
        switch (state) {
            case IDLE -> {
                if (!calls.isEmpty()) {
                    int next = calls.iterator().next();
                    if (next == floor() && atFloor()) {
                        calls.remove(next);
                        syncCalls();
                        open();
                    } else depart(next);
                }
            }
            case OPENING -> {
                if (--timer <= 0) {
                    state = State.OPEN;
                    timer = doorTicks();
                }
            }
            case OPEN -> {
                if (someoneInDoorway()) timer = Math.max(timer, 30);
                if (--timer <= 0) close();
            }
            case CLOSING -> {
                if (someoneInDoorway()) {
                    open();
                    break;
                }
                if (--timer <= 0) state = State.IDLE;
            }
            case MOVING -> move();
        }
        rides.tick();
    }

    private int doorTicks() { return 20 * ServerConfig.get().liftDoorSeconds; }

    private void open() {
        int f = floor();
        state = State.OPENING;
        timer = LiftBlocks.DOOR_TICKS;
        dataTracker.set(DOORS, true);
        setDoors(f, true);
        LiftBlocks.ButtonBlock.light(getWorld(), layout(), slabs.get(f), false);
        calls.remove(f);
        syncCalls();
        sound(Lifts.DOOR_OPEN, 0.9f, 1f);
    }

    private void close() {
        state = State.CLOSING;
        timer = LiftBlocks.DOOR_TICKS;
        dataTracker.set(DOORS, false);
        setDoors(floor(), false);
        sound(Lifts.DOOR_CLOSE, 1f, 1f);
    }

    private void setDoors(int f, boolean open) {
        Lifts.Layout l = layout();
        int s = slabs.get(f);
        for (BlockPos col : List.of(l.left(), l.right()))
            for (int y = s + 1; y <= s + 2; y++) {
                BlockPos p = col.withY(y);
                BlockState st = getWorld().getBlockState(p);
                if (st.isOf(Lifts.DOOR) && st.get(LiftBlocks.DoorBlock.OPEN) != open)
                    getWorld().setBlockState(p, st.with(LiftBlocks.DoorBlock.OPEN, open), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
    }

    private boolean someoneInDoorway() {
        Lifts.Layout l = layout();
        double y = feet(floor());
        Box doorway = new Box(l.left().withY((int) y)).union(new Box(l.right().withY((int) y))).stretch(0, 1, 0).expand(0.05);
        // only someone standing in the doorway itself; a passenger at the cabin's front edge does not hold the doors
        return !getWorld().getOtherEntities(this, doorway, e -> e instanceof LivingEntity && !e.isSpectator() && !contains(e)
                && doorway.contains(e.getPos().add(0, 0.1, 0))).isEmpty();
    }

    /** Leaves for a floor: boards whoever is inside, takes its floor away, sets off. */
    private void depart(int to) {
        target = to;
        if (atFloor()) {
            for (Entity e : getWorld().getOtherEntities(this, getBoundingBox().expand(-0.05, 0, -0.05),
                    e -> e instanceof LivingEntity && !e.isSpectator() && !e.hasVehicle() && contains(e))) {
                e.startRiding(this, true);
            }
            placeFloor(floor(), false);
        }
        state = State.MOVING;
        velocity = 0;
        dataTracker.set(TARGET, (float) feet(to));
        dataTracker.set(SPEED, (float) speed());
        dataTracker.set(MOVING, true);
        sound(Lifts.START, 1f, 1f);
    }

    static double speed() { return ServerConfig.get().liftSpeedTenths / 10.0; }

    private void move() {
        double before = getY();
        double next = step(before, target < 0 ? before : feet(target), speed(), velocity);
        velocity = lastVelocity;
        setPosition(getX(), next, getZ());
        int passed = nearestFloor(next);
        if (passed != floor()) {
            dataTracker.set(FLOOR, passed);
            if (Math.abs(next - feet(passed)) < 0.3) sound(Lifts.RELAY, 0.6f, 1f);
        }
        if (target >= 0 && next == feet(target)) arrive();
        else if (target < 0 && velocity == 0) {   // halted between floors
            state = State.IDLE;
            dataTracker.set(MOVING, false);
            sound(Lifts.STOP, 1f, 0.9f);
        }
    }

    private double lastVelocity;

    /** One tick of the motion toward {@code goal}: speed up, run, slow down to stand exactly level. The client runs the same. */
    double step(double y, double goal, double vmax, double v) {
        double dt = 0.05, left = Math.abs(goal - y);
        if (left < 1e-4) {
            lastVelocity = 0;
            return goal;
        }
        double brake = Math.sqrt(2 * ACCEL * left);
        v = Math.min(Math.min(vmax, v + ACCEL * dt), Math.max(brake, 0.08));
        double d = Math.min(left, v * dt);
        lastVelocity = d >= left ? 0 : v;
        return d >= left ? goal : y + Math.signum(goal - y) * d;
    }

    private int nearestFloor(double y) {
        int best = 0;
        for (int i = 1; i < slabs.size(); i++) if (Math.abs(feet(i) - y) < Math.abs(feet(best) - y)) best = i;
        return best;
    }

    private void arrive() {
        int f = target;
        target = -1;
        dataTracker.set(FLOOR, f);
        dataTracker.set(MOVING, false);
        state = State.IDLE;
        placeFloor(f, true);
        for (Entity e : new ArrayList<>(getPassengerList())) e.stopRiding();
        seats.clear();
        sound(Lifts.STOP, 1f, 1f);
        rides.arrived(f);
        open();
    }

    /** СТОП: brake hard to a halt between floors (and stay there until a floor is pressed). */
    private void halt() {
        target = -1;
        calls.clear();
        syncCalls();
        velocity = 0;
        dataTracker.set(TARGET, (float) getY());
        dataTracker.set(MOVING, false);
        state = State.IDLE;
        sound(Lifts.STOP, 1f, 0.8f);
    }

    private void placeFloor(int f, boolean put) {
        if (slabs.isEmpty()) return;
        int y = slabs.get(f);
        for (BlockPos c : layout().cells()) {
            BlockPos p = c.withY(y);
            BlockState s = getWorld().getBlockState(p);
            if (put && s.isAir()) getWorld().setBlockState(p, Lifts.FLOOR.getDefaultState(), Block.NOTIFY_ALL);
            else if (!put && s.isOf(Lifts.FLOOR)) getWorld().setBlockState(p, net.minecraft.block.Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
    }

    private void clientTick() {
        prevDoorProgress = doorProgress;
        float want = doorsOpen() ? 1f : 0f, d = 1f / LiftBlocks.DOOR_TICKS;
        doorProgress = doorProgress < want ? Math.min(want, doorProgress + d) : Math.max(want, doorProgress - d);
        if (moving()) {
            double next = step(getY(), dataTracker.get(TARGET), dataTracker.get(SPEED), clientVelocity);
            clientVelocity = lastVelocity;
            setPosition(getX(), next, getZ());
        } else clientVelocity = 0;
    }

    /** Position packets: while the client runs the motion itself it takes them only to correct a real drift. */
    @Override public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps) {
        if (!moving() || Math.abs(y - getY()) > 0.75) setPosition(x, y, z);
    }

    @Override public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (MOVING.equals(data) && !moving() && getWorld().isClient) clientVelocity = 0;
    }

    private void sound(SoundEvent sound, float volume, float pitch) {
        getWorld().playSound(null, getX(), getY() + 1.5, getZ(), sound, SoundCategory.BLOCKS, volume, pitch);
    }

    // ---------------------------------------------------------------- passengers

    @Override protected boolean canAddPassenger(Entity passenger) { return getPassengerList().size() < 8; }

    @Override protected void updatePassengerPosition(Entity passenger, PositionUpdater positionUpdater) {
        if (!hasPassenger(passenger)) return;
        Vec3d seat = seats.computeIfAbsent(passenger.getUuid(), u -> new Vec3d(
                MathHelper.clamp(passenger.getX() - getX(), -0.65, 0.65), 0, MathHelper.clamp(passenger.getZ() - getZ(), -0.65, 0.65)));
        positionUpdater.accept(passenger, getX() + seat.x, getY(), getZ() + seat.z);
        passenger.fallDistance = 0;
    }

    @Override public Vec3d updatePassengerForDismount(LivingEntity passenger) {
        Vec3d seat = seats.getOrDefault(passenger.getUuid(), Vec3d.ZERO);
        return new Vec3d(getX() + seat.x, getY() + 0.02, getZ() + seat.z);
    }

    // ---------------------------------------------------------------- the rest of Entity

    @Override public ActionResult interact(PlayerEntity player, Hand hand) {
        if (!contains(player)) return ActionResult.PASS;
        if (getWorld().isClient) Lifts.openPanel.accept(this);
        return ActionResult.SUCCESS;
    }

    @Override public boolean canHit() { return true; }
    @Override public boolean isCollidable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean damage(DamageSource source, float amount) { return false; }
    @Override public boolean isInvulnerable() { return true; }
    @Override public boolean shouldRender(double distance) { return distance < 96 * 96; }
    @Override public boolean isAttackable() { return false; }
    @Override public boolean handleAttack(Entity attacker) { return true; }

    @Override protected void readCustomDataFromNbt(NbtCompound nbt) {
        machine = BlockPos.fromLong(nbt.getLong("machine"));
        origin = BlockPos.fromLong(nbt.getLong("origin"));
        slabs = new ArrayList<>();
        for (int s : nbt.getIntArray("floors")) slabs.add(s);
        dataTracker.set(FACING, nbt.getInt("facing"));
        dataTracker.set(STYLE, nbt.getInt("style"));
        dataTracker.set(FLOORS, slabs.size());
        int f = MathHelper.clamp(nbt.getInt("floor"), 0, Math.max(0, slabs.size() - 1));
        dataTracker.set(FLOOR, f);
        calls.clear();
        for (int c : nbt.getIntArray("calls")) if (c >= 0 && c < slabs.size()) calls.add(c);
        syncCalls();
        target = nbt.contains("target", NbtElement.INT_TYPE) ? nbt.getInt("target") : -1;
        if (!slabs.isEmpty()) {
            if (target >= 0) {   // saved mid-ride: carry on
                state = State.MOVING;
                dataTracker.set(TARGET, (float) feet(target));
                dataTracker.set(MOVING, true);
            } else state = State.IDLE;
        }
    }

    @Override protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putLong("machine", machine.asLong());
        nbt.putLong("origin", origin.asLong());
        nbt.put("floors", new NbtIntArray(slabs.stream().mapToInt(Integer::intValue).toArray()));
        nbt.putInt("facing", dataTracker.get(FACING));
        nbt.putInt("style", dataTracker.get(STYLE));
        nbt.putInt("floor", floor());
        nbt.put("calls", new NbtIntArray(calls.stream().mapToInt(Integer::intValue).toArray()));
        if (state == State.MOVING && target >= 0) nbt.putInt("target", target);
    }
}
