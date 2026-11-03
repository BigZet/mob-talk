package io.github.mobtalk.game;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** The invisible seat a villager rides while it sits on a bar stool or chair ({@link BarEvenings}): it stands in the stool's block,
 * holds its rider at the seat's height facing the way the stool faces, sets it down beside the stool when it gets up and goes away
 * once empty or when the stool is broken. It is saved with the chunk, so the sitter is saved with it (a rider is saved only inside
 * its vehicle); the evening's end, or the next one after a restart, stands the sitter up. */
public final class BarSeatEntity extends Entity {
    /** How far below the seat's top the sitter's feet are: its legs bend forward, so its hips rest on the seat. */
    static final double SIT_DROP = 0.62;
    private int idle;

    public BarSeatEntity(EntityType<?> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
        setInvisible(true);
    }

    /** A seat on the furniture at {@code pos}, facing where its sitter looks. */
    static BarSeatEntity at(World world, BlockPos pos, Direction facing) {
        BarSeatEntity seat = new BarSeatEntity(BarContent.SEAT, world);
        seat.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, facing.asRotation(), 0);
        return seat;
    }

    @Override protected void initDataTracker(DataTracker.Builder builder) { }
    @Override protected void readCustomDataFromNbt(NbtCompound nbt) { }
    @Override protected void writeCustomDataToNbt(NbtCompound nbt) { }

    private BarFurnitureBlock furniture() {
        BlockState state = getWorld().getBlockState(getBlockPos());
        return state.getBlock() instanceof BarFurnitureBlock block && block.seat > 0 ? block : null;
    }

    @Override public void tick() {
        super.tick();
        if (getWorld().isClient) return;
        if (furniture() == null) { removeAllPassengers(); discard(); return; }
        idle = hasPassengers() ? 0 : idle + 1;
        if (idle > 10) discard();
    }

    @Override protected Vec3d getPassengerAttachmentPos(Entity passenger, EntityDimensions dimensions, float scaleFactor) {
        BarFurnitureBlock block = furniture();
        double seat = block == null ? 0.5 : block.seat;
        return new Vec3d(0, seat - SIT_DROP, 0);
    }

    /** The sitter's body turns with the seat; its head still looks where it likes. */
    @Override protected void updatePassengerPosition(Entity passenger, PositionUpdater updater) {
        super.updatePassengerPosition(passenger, updater);
        if (passenger instanceof LivingEntity living) {
            living.bodyYaw = getYaw();
            living.prevBodyYaw = getYaw();
        }
    }

    /** Getting up: on the floor behind the stool (the side the sitter's back is to), else on top of it as vanilla would. */
    @Override public Vec3d updatePassengerForDismount(LivingEntity passenger) {
        Direction back = Direction.fromRotation(getYaw()).getOpposite();
        BlockPos floor = getBlockPos().offset(back);
        World world = getWorld();
        if (world.getBlockState(floor).getCollisionShape(world, floor).isEmpty() && world.getBlockState(floor.up()).getCollisionShape(world, floor.up()).isEmpty())
            return Vec3d.ofBottomCenter(floor);
        return super.updatePassengerForDismount(passenger);
    }

    @Override public boolean canHit() { return false; }
    @Override public boolean isCollidable() { return false; }
    @Override protected boolean canAddPassenger(Entity passenger) { return !hasPassengers(); }
}
