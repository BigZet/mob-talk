package io.github.divineblock.boss;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Нимрод, «сильный зверолов пред Господом» and the builder of Babel (Genesis 10-11). He looses volleys of arrows, hurls fired
 * clay bricks in an arc and stamps a ring through the ground. Ultimate «Башня до небес»: he raises a brick tower under his
 * feet (when block damage is allowed; otherwise he rises on a column of dust) and bombards the field from its top. The tower
 * falls when he dies or after 45 seconds. Brick hits and the ultimate bring «Смешение языков»: chat, subtitles, titles and
 * item names come scrambled on the client (the mixins and {@code NimrodConfusion}). */
public final class NimrodEntity extends SacredBoss {
    static final ParticleEffect BRICK_DUST = new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.BRICKS.getDefaultState());
    static final ParticleEffect MUD_DUST = new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.MUD_BRICKS.getDefaultState());
    static final Block[] TOWER = { Blocks.BRICKS, Blocks.MUD_BRICKS, Blocks.SANDSTONE, Blocks.BRICKS, Blocks.MUD_BRICKS };
    /** «Смешение языков» lasts this long. */
    static final int CONFUSION_TICKS = 20 * 20;
    /** The tower stands this long at most. */
    static final int TOWER_LIFE = 45 * 20;
    static final double GRAVITY = 0.04;

    private static final net.minecraft.entity.data.TrackedData<Byte> MOVE =
            net.minecraft.entity.data.DataTracker.registerData(NimrodEntity.class, net.minecraft.entity.data.TrackedDataHandlerRegistry.BYTE);
    /** Which move the model should pose: none, drawing the bow, throwing a brick, stamping. */
    public static final int MOVE_NONE = 0, MOVE_DRAW = 1, MOVE_THROW = 2, MOVE_STOMP = 3;

    private final List<BlockPos> tower = new ArrayList<>();
    private int towerAge = -1, perched, moveTicks;
    private boolean levitating;
    private double perchY;

    public NimrodEntity(EntityType<? extends NimrodEntity> type, World world) { super(type, world, BossBar.Color.YELLOW); }

    /** The confusion of tongues is shown on the client only: nothing to hook on the server. */
    static void registerHooks() { }

    @Override protected void initDataTracker(net.minecraft.entity.data.DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(MOVE, (byte) 0);
    }
    /** On the client too: the move being made, for the model's pose. */
    public int move() { return dataTracker.get(MOVE); }
    private void pose(int move, int ticks) { dataTracker.set(MOVE, (byte) move); moveTicks = ticks; }

    @Override public BossKind kind() { return BossKind.NIMROD; }
    @Override protected String theme() { return "punishment_02"; }
    @Override protected float meleeDamage() { return 7f; }
    @Override protected double meleeReach() { return perched > 0 ? 0 : getWidth() * 0.5 + 2.6; }

    // ---- The fight loop. ----

    @Override protected void mobTick() {
        if (moveTicks > 0 && --moveTicks == 0) dataTracker.set(MOVE, (byte) MOVE_NONE);
        super.mobTick();
        if (!(getWorld() instanceof ServerWorld)) return;
        if (towerAge >= 0 && ++towerAge > TOWER_LIFE) demolish();
        if (perched > 0) perch();
    }

    /** On top of the tower (or the dust column): stands still; the column of the levitation is drawn. */
    private void perch() {
        getNavigation().stop();
        if (levitating) {
            Vec3d v = getVelocity();
            setVelocity(v.x * 0.5, MathHelper.clamp((perchY - getY()) * 0.15, -0.15, 0.25), v.z * 0.5);
            if (age % 4 == 0) for (double y = perchY - 1; y > perchY - 16 && y > getWorld().getBottomY(); y -= 0.8)
                particles(y > perchY - 3 ? BRICK_DUST : MUD_DUST, getX(), y, getZ(), 1, 0.6, 0.1, 0.6, 0);
        }
        // Thrown off the top: the perch is over.
        if (!levitating && getY() < perchY - 2.5) perched = 1;
        if (--perched <= 0) land();
    }

    private void land() {
        perched = 0;
        if (levitating) {
            levitating = false;
            setNoGravity(false);
            addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 200, 0, false, false));
        }
    }

    @Override protected void pursue(LivingEntity target) {
        if (perched > 0) return;
        if (age % 10 == 0) {
            double distance = distanceTo(target);
            // A hunter keeps a bowshot away, but closes in when the prey hides.
            if (distance > 12 || !canSee(target)) getNavigation().startMovingTo(target, 1.0);
            else if (distance < 5) getNavigation().startMovingTo(target, 1.1);
            else getNavigation().stop();
        }
    }

    // ---- Attacks. ----

    @Override protected int attack(LivingEntity target) {
        if (perched > 0) { bombard(target); return phase >= 2 ? 30 : 40; }
        double distance = distanceTo(target);
        int roll = random.nextInt(3);
        if (distance < 5 && roll != 2) { stomp(); return 60; }
        if (roll == 0) { bricks(target, phase >= 2 ? 2 : 1, 18); return phase >= 2 ? 45 : 60; }
        volley(target);
        return phase >= 2 ? 40 : 60;
    }

    /** «Залп зверолова»: he draws the great bow, then looses three arrows (five later) in quick succession. */
    private void volley(LivingEntity target) {
        int warn = phase >= 2 ? 14 : 20;
        int count = phase >= 2 ? 5 : 3;
        pose(MOVE_DRAW, warn + count * (phase >= 2 ? 3 : 5) + 6);
        cast("Залп зверолова", warn, true, left -> {
            Vec3d bow = bowPos();
            particles(ParticleTypes.CRIT, bow.x, bow.y, bow.z, 2, 0.2, 0.3, 0.2, 0.01);
            if (left == warn) sound(SoundEvents.ITEM_CROSSBOW_LOADING_MIDDLE, 2f, 0.6f);
        }, () -> hazards.repeat(phase >= 2 ? 3 : 5, count, i -> {
            if (target.isAlive()) shoot(target, (i - (count - 1) / 2.0) * 0.06);
        }));
    }

    /** One arrow at the foe, a little to the side by {@code spread} radians. */
    private void shoot(LivingEntity target, double spread) {
        if (!(getWorld() instanceof ServerWorld world)) return;
        Vec3d bow = bowPos();
        ArrowEntity arrow = new ArrowEntity(world, this, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        arrow.setPosition(bow.x, bow.y, bow.z);
        double dx = target.getX() - bow.x, dz = target.getZ() - bow.z;
        double dy = target.getBodyY(0.4) - bow.y;
        double flat = Math.sqrt(dx * dx + dz * dz);
        double c = Math.cos(spread), s = Math.sin(spread);
        arrow.setVelocity(dx * c - dz * s, dy + flat * 0.18, dx * s + dz * c, 1.8f, 2f);
        arrow.setDamage(1.6 * scale());
        arrow.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
        world.spawnEntity(arrow);
        sound(SoundEvents.ENTITY_SKELETON_SHOOT, 1.6f, 0.7f);
    }

    /** «Обожжённый кирпич»: he weighs a brick in his hand, then hurls one or more in an arc; the landing spot is marked. */
    private void bricks(LivingEntity target, int count, int warn) {
        pose(MOVE_THROW, warn + 8);
        cast("Обожжённый кирпич", warn, true, left -> {
            Vec3d hand = handPos();
            particles(BRICK_DUST, hand.x, hand.y, hand.z, 3, 0.15, 0.15, 0.15, 0);
            particles(ParticleTypes.SMOKE, hand.x, hand.y, hand.z, 1, 0.1, 0.1, 0.1, 0.01);
            if (left == warn) sound(SoundEvents.BLOCK_MUD_BRICKS_PLACE, 2f, 0.6f);
        }, () -> throwBricks(target, count));
    }

    private void throwBricks(LivingEntity target, int count) {
        Vec3d hand = handPos();
        for (int i = 0; i < count; i++) {
            Vec3d spot = target.getPos().add(i == 0 ? Vec3d.ZERO : new Vec3d(random.nextGaussian() * 2, 0, random.nextGaussian() * 2));
            arc(hand, spot);
        }
        sound(SoundEvents.ENTITY_WITCH_THROW, 2f, 0.5f);
        swingHand(net.minecraft.util.Hand.MAIN_HAND);
    }

    /** A clay brick flying in an arc from {@code from} to land at {@code to}; smoke trails it and its landing spot is marked.
     * It hits the first foe it touches, or bursts where it lands and hurts whoever stands right there. */
    private void arc(Vec3d from, Vec3d to) {
        double flat = Math.hypot(to.x - from.x, to.z - from.z);
        int flight = (int) MathHelper.clamp(14 + flat * 1.3, 18, 48);
        Vec3d start = new Vec3d((to.x - from.x) / flight, (to.y - from.y) / flight + 0.5 * GRAVITY * flight, (to.z - from.z) / flight);
        hazards.repeat(1, flight + 30, new java.util.function.IntConsumer() {
            Vec3d pos = from, velocity = start;
            boolean done;
            @Override public void accept(int step) {
                if (done) return;
                if (step % 4 == 0) for (int k = 0; k < 8; k++) {
                    double a = Math.PI * 2 * k / 8;
                    particles(ParticleTypes.SMOKE, to.x + Math.cos(a) * 1.2, to.y + 0.15, to.z + Math.sin(a) * 1.2, 1, 0, 0, 0, 0);
                }
                pos = pos.add(velocity);
                velocity = velocity.add(0, -GRAVITY, 0);
                particles(BRICK_DUST, pos.x, pos.y, pos.z, 2, 0.12, 0.12, 0.12, 0);
                particles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y, pos.z, 1, 0.05, 0.05, 0.05, 0.005);
                for (LivingEntity victim : victims(pos, 2.5))
                    if (victim.getBoundingBox().expand(0.35).contains(pos)) { strike(victim); done = true; return; }
                BlockPos block = BlockPos.ofFloored(pos);
                if (!getWorld().getBlockState(block).getCollisionShape(getWorld(), block).isEmpty() || pos.y < to.y - 3) {
                    done = true;
                    particles(BRICK_DUST, pos.x, pos.y + 0.3, pos.z, 20, 0.4, 0.2, 0.4, 0);
                    particles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x, pos.y + 0.3, pos.z, 2, 0.3, 0.1, 0.3, 0.01);
                    getWorld().playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_DECORATED_POT_SHATTER, net.minecraft.sound.SoundCategory.HOSTILE, 1.5f, 0.7f);
                    for (LivingEntity victim : victims(pos, 1.6)) strike(victim);
                }
            }

            private void strike(LivingEntity victim) {
                if (hurt(victim, 6f)) confuse(victim);
                particles(BRICK_DUST, victim.getX(), victim.getBodyY(0.6), victim.getZ(), 16, 0.3, 0.3, 0.3, 0);
            }
        });
    }

    /** «Поступь царя»: he lifts his foot (the ground shakes), then stamps, and a low ring runs out through the dust; jump it. */
    private void stomp() {
        pose(MOVE_STOMP, 26);
        cast("Поступь царя", 20, true, left -> {
            particles(MUD_DUST, getX(), getY() + 0.1, getZ(), 6, 1.5, 0.05, 1.5, 0);
            if (left % 6 == 0) sound(SoundEvents.ENTITY_RAVAGER_STEP, 2f, 0.5f);
        }, () -> {
            Vec3d center = getPos();
            sound(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1f, 0.6f);
            particles(ParticleTypes.EXPLOSION, center.x, center.y + 0.3, center.z, 2, 0.5, 0.1, 0.5, 0);
            hazards.ring(center, 0.45, phase >= 3 ? 10 : 8, 1.2, MUD_DUST, victim -> { if (hurt(victim, 7f)) push(victim, center, 0.9, 0.35); });
        });
    }

    /** From the top of the tower: bricks (two to four) and a couple of arrows. */
    private void bombard(LivingEntity target) {
        pose(MOVE_THROW, (phase >= 2 ? 12 : 16) + 8);
        cast("Град с башни", phase >= 2 ? 12 : 16, true, left -> {
            Vec3d hand = handPos();
            particles(BRICK_DUST, hand.x, hand.y, hand.z, 3, 0.2, 0.2, 0.2, 0);
        }, () -> {
            throwBricks(target, 2 + Math.min(2, phase - 1));
            hazards.later(8, () -> { if (target.isAlive()) { shoot(target, -0.04); shoot(target, 0.04); } });
        });
    }

    // ---- «Башня до небес». ----

    @Override protected void ultimate(LivingEntity target) {
        Vec3d center = getPos();
        castUltimate(left -> {
            double a = left * 0.35;
            particles(BRICK_DUST, center.x + Math.cos(a) * 1.6, center.y + 0.2 + (60 - left) * 0.04, center.z + Math.sin(a) * 1.6, 3, 0.1, 0.1, 0.1, 0);
            particles(MUD_DUST, center.x, center.y + 0.1, center.z, 4, 1.4, 0.05, 1.4, 0);
            if (left % 10 == 0) sound(SoundEvents.BLOCK_MUD_BRICKS_PLACE, 2f, 0.5f + (60 - left) * 0.01f);
        }, () -> raise(target));
    }

    private void raise(LivingEntity target) {
        if (!(getWorld() instanceof ServerWorld world)) return;
        int height = phase >= 3 ? 14 : phase == 2 ? 12 : 10;
        sound(SoundEvents.ENTITY_EVOKER_PREPARE_WOLOLO, 2f, 0.5f);
        for (LivingEntity victim : victims(getPos(), 24)) {
            confuse(victim);
            victim.sendMessage(Text.literal("«Сойдём же и смешаем там язык их»").formatted(Formatting.GOLD));
        }
        perched = (phase >= 3 ? 25 : 20) * 20;
        perchY = getY();
        if (mayBreakBlocks()) {
            // Still standing on his last tower: he simply holds it again.
            if (!tower.isEmpty() && isOnGround() && getY() > tower.get(0).getY() + 2) { debug("Башня до небес: снова на своей башне"); return; }
            demolish();
            BlockPos base = getBlockPos();
            towerAge = 0;
            int every = Math.max(2, 60 / height);
            hazards.repeat(every, height, layer -> buildLayer(world, base, layer));
            debug("Башня до небес: строит " + height + " блоков");
        } else {
            levitating = true;
            setNoGravity(true);
            perchY = getY() + height;
            debug("Башня до небес: столп из пыли, высота " + height);
        }
    }

    /** Lifts him one block and lays a 3×3 course of bricks under his feet, only into air and never into anyone. */
    private void buildLayer(ServerWorld world, BlockPos base, int layer) {
        BlockPos center = base.up(layer);
        // He stands on the course just laid: the air must be free for his whole height.
        for (int up = 1; up <= MathHelper.ceil(getHeight()) + 1; up++)
            if (!world.getBlockState(center.up(up)).getCollisionShape(world, center.up(up)).isEmpty()) return;
        if (getY() < center.getY() - 1.5) return;
        requestTeleport(base.getX() + 0.5, center.getY() + 1, base.getZ() + 0.5);
        setVelocity(Vec3d.ZERO);
        perchY = center.getY() + 1;
        Block material = layer >= 0 && (layer % 4 == 3) ? Blocks.SANDSTONE : TOWER[(layer + random.nextInt(2)) % TOWER.length];
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = center.add(dx, 0, dz);
                if (!world.getBlockState(pos).isAir()) continue;
                if (!world.getOtherEntities(this, new Box(pos), entity -> entity instanceof LivingEntity).isEmpty()) continue;
                Block block = dx == 0 && dz == 0 ? Blocks.MUD_BRICKS : material;
                world.setBlockState(pos, block.getDefaultState());
                tower.add(pos.toImmutable());
            }
        particles(BRICK_DUST, center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5, 20, 1, 0.3, 1, 0);
        sound(SoundEvents.BLOCK_MUD_BRICKS_PLACE, 1.5f, 0.6f + layer * 0.03f);
    }

    /** Takes the tower down: only blocks it laid that are still its own bricks. */
    private void demolish() {
        towerAge = -1;
        if (tower.isEmpty() || !(getWorld() instanceof ServerWorld world)) { tower.clear(); return; }
        for (int i = tower.size() - 1; i >= 0; i--) {
            BlockPos pos = tower.get(i);
            BlockState state = world.getBlockState(pos);
            if (!isTowerBlock(state)) continue;
            world.setBlockState(pos, Blocks.AIR.getDefaultState());
            if (i % 3 == 0) world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0);
        }
        world.playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_MUD_BRICKS_BREAK, net.minecraft.sound.SoundCategory.HOSTILE, 2f, 0.5f);
        debug("Башня до небес: разобрано блоков " + tower.size());
        tower.clear();
    }

    private static boolean isTowerBlock(BlockState state) {
        for (Block block : TOWER) if (state.isOf(block)) return true;
        return false;
    }

    // ---- The affliction. ----

    static void confuse(LivingEntity victim) {
        boolean had = victim.hasStatusEffect(BossEffects.CONFUSION);
        BossEffects.afflict(victim, BossEffects.CONFUSION, CONFUSION_TICKS);
        if (!had && victim instanceof net.minecraft.entity.player.PlayerEntity player)
            player.sendMessage(Text.literal("Смешение языков").formatted(Formatting.GOLD), true);
    }

    @Override protected void onPhase(int phase) {
        sound(SoundEvents.ENTITY_RAVAGER_ROAR, 1.6f, 0.7f);
        if (phase >= 2) addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, StatusEffectInstance.INFINITE, 0, false, false));
    }

    @Override protected void onDefeated() {
        land();
        demolish();
        for (LivingEntity victim : victims(getPos(), 48)) victim.removeStatusEffect(BossEffects.CONFUSION);
    }

    @Override public void remove(RemovalReason reason) {
        if (reason.shouldDestroy()) demolish();
        super.remove(reason);
    }

    // ---- Saving. ----

    @Override public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (!tower.isEmpty()) {
            nbt.putLongArray("NimrodTower", tower.stream().mapToLong(BlockPos::asLong).toArray());
            nbt.putInt("NimrodTowerAge", towerAge);
        }
    }

    @Override public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        tower.clear();
        for (long packed : nbt.getLongArray("NimrodTower")) tower.add(BlockPos.fromLong(packed));
        towerAge = tower.isEmpty() ? -1 : Math.max(0, nbt.getInt("NimrodTowerAge"));
        setNoGravity(false);
    }

    private Vec3d bowPos() {
        float yaw = bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(getX() - Math.cos(yaw) * 0.3 - Math.sin(yaw) * 1.4, getY() + getHeight() * 0.7, getZ() - Math.sin(yaw) * 0.3 + Math.cos(yaw) * 1.4);
    }

    private Vec3d handPos() {
        float yaw = bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(getX() + Math.cos(yaw) * 0.8 - Math.sin(yaw) * 0.4, getY() + getHeight() * 0.85, getZ() + Math.sin(yaw) * 0.8 + Math.cos(yaw) * 0.4);
    }
}
