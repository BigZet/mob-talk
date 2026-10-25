package io.github.mobtalk.game;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** A cart of the village roads (docs/roads.md): the two-wheeled cart or the covered wagon a horse draws along the road with
 * villagers sitting in it. It has no physics of its own: {@link RoadTraffic} sets where it is every tick, and the client glides
 * between the positions it hears and turns the wheels by the distance rolled. It cannot be hurt, pushed or saved with the chunk
 * (the traffic puts it back where it belongs). Seats after NiftyCarts' {@code AnimalCartEntity} and {@code WagonEntity} (MIT,
 * third_party/nifty-carts/NOTICE.md). */
public final class RoadCartEntity extends Entity {
    public static EntityType<RoadCartEntity> CART, WAGON;
    /** The woods a cart is made of (vanilla planks and logs), and the wool colours of a wagon's roof. */
    public static final String[] WOODS = { "oak", "spruce", "birch", "dark_oak", "acacia", "jungle" };
    public static final String[] ROOFS = { "white", "light_gray", "brown", "green", "red", "blue", "yellow" };
    private static final TrackedData<Byte> WOOD = DataTracker.registerData(RoadCartEntity.class, TrackedDataHandlerRegistry.BYTE);
    private static final TrackedData<Byte> ROOF = DataTracker.registerData(RoadCartEntity.class, TrackedDataHandlerRegistry.BYTE);

    /** On the client: the gliding toward the last position heard, and the wheels' turn (radians) with its value a tick ago. */
    private int lerpTicks;
    private double lerpX, lerpY, lerpZ; private float lerpYaw, lerpPitch;
    public float wheel, prevWheel;

    public RoadCartEntity(EntityType<? extends RoadCartEntity> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
    }

    public static void register() {
        CART = Registry.register(Registries.ENTITY_TYPE, Identifier.of("mobtalk", "road_cart"),
                EntityType.Builder.<RoadCartEntity>create(RoadCartEntity::new, SpawnGroup.MISC).dimensions(1.3f, 1.4f)
                        .maxTrackingRange(10).trackingTickInterval(1).disableSummon().makeFireImmune().build("road_cart"));
        WAGON = Registry.register(Registries.ENTITY_TYPE, Identifier.of("mobtalk", "road_wagon"),
                EntityType.Builder.<RoadCartEntity>create(RoadCartEntity::new, SpawnGroup.MISC).dimensions(2.5f, 3.0f)
                        .maxTrackingRange(10).trackingTickInterval(1).disableSummon().makeFireImmune().build("road_wagon"));
    }

    public boolean wagon() { return getType() == WAGON; }
    public String wood() { return WOODS[Math.floorMod(dataTracker.get(WOOD), WOODS.length)]; }
    public String roof() { return ROOFS[Math.floorMod(dataTracker.get(ROOF), ROOFS.length)]; }
    void look(int wood, int roof) { dataTracker.set(WOOD, (byte) wood); dataTracker.set(ROOF, (byte) roof); }
    /** How far the shafts reach: the horse's centre stands this far ahead of the cart's (NiftyCarts' spacing plus half a horse). */
    public double reach() { return wagon() ? 2.5 + 0.7 : 1.7 + 0.7; }
    public int seats() { return wagon() ? 4 : 2; }

    @Override protected void initDataTracker(DataTracker.Builder builder) { builder.add(WOOD, (byte) 0); builder.add(ROOF, (byte) 0); }
    @Override protected void readCustomDataFromNbt(NbtCompound nbt) { }
    @Override protected void writeCustomDataToNbt(NbtCompound nbt) { }
    @Override public boolean shouldSave() { return false; }
    @Override public boolean damage(DamageSource source, float amount) { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean isCollidable() { return false; }
    @Override public boolean canHit() { return true; } // a player's click finds the cart (and talks to whoever sits in it)
    @Override protected boolean canAddPassenger(Entity passenger) { return getPassengerList().size() < seats(); }

    @Override public void tick() {
        super.tick();
        if (getWorld().isClient) {
            if (lerpTicks > 0) { lerpPosAndRotation(lerpTicks, lerpX, lerpY, lerpZ, lerpYaw, lerpPitch); lerpTicks--; }
            prevWheel = wheel;
            double rolled = Math.hypot(getX() - prevX, getZ() - prevZ);
            if (rolled < 2) wheel += (float) (rolled / (wagon() ? 0.756 : 0.68)); // the model's wheel radius in blocks
        }
    }

    @Override public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps) {
        lerpX = x; lerpY = y; lerpZ = z; lerpYaw = yaw; lerpPitch = pitch; lerpTicks = Math.max(steps, 3);
    }
    @Override public double getLerpTargetX() { return lerpTicks > 0 ? lerpX : getX(); }
    @Override public double getLerpTargetY() { return lerpTicks > 0 ? lerpY : getY(); }
    @Override public double getLerpTargetZ() { return lerpTicks > 0 ? lerpZ : getZ(); }
    @Override public float getLerpTargetPitch() { return lerpTicks > 0 ? lerpPitch : getPitch(); }
    @Override public float getLerpTargetYaw() { return lerpTicks > 0 ? lerpYaw : getYaw(); }

    /** The seats: in the cart, the driver in front and one behind; in the wagon, two benches of two along the sides. */
    @Override protected Vec3d getPassengerAttachmentPos(Entity passenger, EntityDimensions dimensions, float scale) {
        int index = Math.max(0, getPassengerList().indexOf(passenger));
        Vec3d look = Vec3d.fromPolar(getPitch(), getYaw());
        if (!wagon()) {
            double ahead = getPassengerList().size() > 1 ? (index == 0 ? 0.2 : -0.6) : -0.1;
            Vec3d forward = look.multiply(ahead + MathHelper.sin((float) Math.toRadians(getPitch())) * 0.7);
            return new Vec3d(forward.x, (dimensions.height() - 0.5) * scale + forward.y, forward.z);
        }
        double ahead = index == 0 || index == 2 ? 0.1 : -1.2, side = index == 0 || index == 1 ? 0.7 : -0.7;
        Vec3d forward = look.multiply(ahead), sideways = new Vec3d(forward.z, 0, -forward.x).normalize().multiply(side);
        // NiftyCarts' bench height suits a seated player; a villager sits taller and would put its head through the roof.
        double sink = passenger instanceof net.minecraft.entity.player.PlayerEntity ? 0 : 0.8;
        return new Vec3d(forward.x + sideways.x, (dimensions.height() - 2 - 1 / 16.0 - sink) * scale + forward.y, forward.z + sideways.z);
    }

    /** Sitting faces the way the cart goes, or across it on the wagon's benches. */
    @Override protected void updatePassengerPosition(Entity passenger, PositionUpdater updater) {
        super.updatePassengerPosition(passenger, updater);
        int index = getPassengerList().indexOf(passenger);
        float facing = getYaw() + (wagon() ? (index == 0 || index == 3 ? 90 : -90) : 0);
        passenger.setBodyYaw(facing);
        float turn = MathHelper.wrapDegrees(passenger.getYaw() - facing), kept = MathHelper.clamp(turn, -105.0F, 105.0F);
        passenger.prevYaw += kept - turn;
        passenger.setYaw(passenger.getYaw() + kept - turn);
        passenger.setHeadYaw(passenger.getYaw());
    }
}
