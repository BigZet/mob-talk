package io.github.divineblock.boss;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

/** The god himself over the clouds: a puppet with no will of its own. {@link GodWrath} moves it from stage to stage and the
 * client draws the elder with the mace from what is tracked here (the stage, the strike, where it falls). It is not a boss:
 * no bar, no health, no collision, nothing can hit or target it, and it is never saved — a world loaded later has no god in
 * its sky. Its render box is huge, so the figure is never culled and shows from far away. */
public final class GodAvatarEntity extends Entity {
    /** Stages as the client animates them. */
    public static final int ARRIVE = 0, HOVER = 1, WARN = 2, STRIKE = 3, LEAVE = 4;
    /** Strikes: the mace from above, the mace swept across, lightning thrown from the hand. */
    public static final int OVERHEAD = 0, SWEEP = 1, LIGHTNING = 2;

    private static final TrackedData<Byte> STAGE = DataTracker.registerData(GodAvatarEntity.class, TrackedDataHandlerRegistry.BYTE);
    private static final TrackedData<Byte> STRIKE_KIND = DataTracker.registerData(GodAvatarEntity.class, TrackedDataHandlerRegistry.BYTE);
    private static final TrackedData<Integer> STAGE_TICKS = DataTracker.registerData(GodAvatarEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<BlockPos> TARGET = DataTracker.registerData(GodAvatarEntity.class, TrackedDataHandlerRegistry.BLOCK_POS);

    /** On the client: ticks since the stage began. */
    private int stageAge;
    /** Set by the controller; an avatar without one (left over somehow) goes away by itself. */
    GodWrath wrath;

    public GodAvatarEntity(EntityType<? extends GodAvatarEntity> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
        setInvulnerable(true);
    }

    @Override protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(STAGE, (byte) ARRIVE);
        builder.add(STRIKE_KIND, (byte) OVERHEAD);
        builder.add(STAGE_TICKS, 80);
        builder.add(TARGET, BlockPos.ORIGIN);
    }

    /** The controller moves the puppet: a new stage that lasts {@code ticks}. */
    void stage(int stage, int ticks) {
        dataTracker.set(STAGE_TICKS, Math.max(1, ticks));
        dataTracker.set(STAGE, (byte) stage);
        stageAge = 0;
    }
    void strike(int kind, BlockPos target) {
        dataTracker.set(STRIKE_KIND, (byte) kind);
        dataTracker.set(TARGET, target.toImmutable());
    }

    public int stage() { return dataTracker.get(STAGE); }
    public int strikeKind() { return dataTracker.get(STRIKE_KIND); }
    public int stageTicks() { return dataTracker.get(STAGE_TICKS); }
    public BlockPos target() { return dataTracker.get(TARGET); }
    /** Ticks since the stage began (counted on each side). */
    public int stageAge() { return stageAge; }
    /** 0..1 through the current stage. */
    public float stageProgress(float tickDelta) { return Math.min(1f, (stageAge + tickDelta) / stageTicks()); }

    @Override public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (STAGE.equals(data)) stageAge = 0;
    }

    @Override public void tick() {
        super.tick();
        stageAge++;
        if (!getWorld().isClient && (wrath == null || wrath.finished())) discard();
    }

    // ---- Untouchable, unsaved, always drawn. ----

    @Override public boolean damage(DamageSource source, float amount) { return false; }
    @Override public boolean canHit() { return false; }
    @Override public boolean isCollidable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean isAttackable() { return false; }
    @Override public boolean isImmuneToExplosion(net.minecraft.world.explosion.Explosion explosion) { return true; }
    @Override public boolean shouldSave() { return false; }
    @Override public boolean shouldRender(double distance) { return true; }
    @Override public Box getVisibilityBoundingBox() { return getBoundingBox().expand(80, 80, 80); }
    @Override protected void readCustomDataFromNbt(NbtCompound nbt) { discard(); }
    @Override protected void writeCustomDataToNbt(NbtCompound nbt) { }
}
