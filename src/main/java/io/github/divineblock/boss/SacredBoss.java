package io.github.divineblock.boss;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.StopSoundS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;

/** What every sacred boss shares: a boss bar, power 1..5 and the difficulty from the settings, three phases by health, a
 * timer for ordinary attacks and a longer one for the ultimate, and casting with a telegraph: every blow is announced by
 * particles and sound before it lands, the ultimate by a title three seconds ahead. Damage to a player is capped per hit, so
 * no boss one-shots a healthy player. Bosses carry their own {@link Hazards} (orbs, rings, marked zones, beams). */
public abstract class SacredBoss extends HostileEntity {
    /** One hit never takes more than this share of a player's maximum health. */
    public static final float MAX_HIT_SHARE = 0.45f;
    /** The ultimate is announced this many ticks before it strikes. */
    public static final int ULT_WARNING = 60;

    /** What the client animates: 0 nothing, 1 an ordinary cast, 2 the ultimate; and the boss's phase. */
    private static final net.minecraft.entity.data.TrackedData<Byte> CAST_STATE =
            net.minecraft.entity.data.DataTracker.registerData(SacredBoss.class, net.minecraft.entity.data.TrackedDataHandlerRegistry.BYTE);
    private static final net.minecraft.entity.data.TrackedData<Byte> PHASE =
            net.minecraft.entity.data.DataTracker.registerData(SacredBoss.class, net.minecraft.entity.data.TrackedDataHandlerRegistry.BYTE);

    protected final ServerBossBar bar;
    protected final Hazards hazards = new Hazards(this);
    protected int power = 3, phase = 1;
    protected BlockPos home;
    private int attackTimer = 40, ultTimer = 20 * 15, meleeTimer, idleTicks, musicTimer, configAge;
    private Cast casting;
    private DivineConfig config;

    private record Cast(String name, int total, boolean holdStill, IntConsumer warning, Runnable strike) { }
    private int castLeft;
    private boolean ultimateNext;

    protected SacredBoss(EntityType<? extends HostileEntity> type, World world, BossBar.Color color) {
        super(type, world);
        this.bar = new ServerBossBar(Text.translatable(type.getTranslationKey()).formatted(Formatting.GOLD), color, BossBar.Style.NOTCHED_10);
        this.experiencePoints = 150;
        setPersistent();
    }

    @Override protected void initDataTracker(net.minecraft.entity.data.DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(CAST_STATE, (byte) 0);
        builder.add(PHASE, (byte) 1);
    }
    /** On the client: 0 idle, 1 casting an attack, 2 casting the ultimate. */
    public int castState() { return dataTracker.get(CAST_STATE); }
    /** On the client too: the phase 1..3. */
    public int syncedPhase() { return dataTracker.get(PHASE); }

    public static DefaultAttributeContainer.Builder attributes(double health, double speed, double armor) {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, health)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, speed)
                .add(EntityAttributes.GENERIC_ARMOR, armor)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 6)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.9)
                .add(EntityAttributes.GENERIC_STEP_HEIGHT, 1.0);
    }

    @Override protected void initGoals() {
        goalSelector.add(0, new SwimGoal(this));
        goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 24f));
        goalSelector.add(9, new LookAroundGoal(this));
        targetSelector.add(1, new RevengeGoal(this));
        targetSelector.add(2, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
    }

    // ---- What each boss defines. ----

    public abstract BossKind kind();
    /** One ordinary attack (usually through {@link #cast}); returns the ticks until the next one. */
    protected abstract int attack(LivingEntity target);
    /** The ultimate: must go through {@link #castUltimate}, so it is announced. */
    protected abstract void ultimate(LivingEntity target);
    /** The boss's chant (a piece from the mod's sounds), looped while it fights. */
    protected abstract String theme();
    /** How many phases the fight has, split evenly by health (the Four Horsemen have four). */
    protected int phaseCount() { return 3; }
    /** A new phase began (2, 3, ...): announce it, toughen up, call help. */
    protected void onPhase(int phase) { }
    /** Melee reach in blocks; 0 for bosses that never strike in melee. */
    protected double meleeReach() { return 3.2; }
    protected float meleeDamage() { return 7f; }
    /** Moves toward or around the target; the default walks up to melee reach. */
    protected void pursue(LivingEntity target) {
        if (age % 10 == 0) {
            if (squaredDistanceTo(target) > meleeReach() * meleeReach() * 0.6) getNavigation().startMovingTo(target, 1.0);
            else getNavigation().stop();
        }
    }
    /** The boss was defeated: lift its affliction, say its last words. */
    protected void onDefeated() { }

    // ---- Power and settings. ----

    /** Sets the power 1..5 and scales health with it and with the difficulty setting. */
    public void setPower(int power) {
        this.power = Math.max(1, Math.min(5, power));
        var health = getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (health != null) {
            double base = health.getAttribute().value().getDefaultValue();
            double wanted = DivineBosses.defaultHealth(getType()) * (0.6 + 0.2 * this.power) * config().bossDifficulty / 100.0;
            health.setBaseValue(Math.max(base, wanted));
        }
        setHealth(getMaxHealth());
    }
    public int power() { return power; }
    public int phase() { return phase; }
    public void setHome(BlockPos home) { this.home = home == null ? null : home.toImmutable(); }

    protected DivineConfig config() {
        if (config == null || configAge-- <= 0) { config = DivineBlockMod.config(); configAge = 200; }
        return config;
    }
    /** How hard the boss hits: power and the difficulty setting. */
    protected float scale() { return (0.7f + 0.15f * power) * config().bossDifficulty / 100f; }
    /** Whether ultimates may break glass, put out lights and light fires here. */
    protected boolean mayBreakBlocks() {
        return config().bossBlockDamage && getWorld() instanceof ServerWorld world && world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)
                && !io.github.divineblock.game.ChurchArea.inZone(world, getBlockPos()); // the church's yard and cemetery stay whole
    }
    protected void debug(String line) { if (config().debug) DivineBlockMod.LOG.info("Божественный блок: {} — {}", kind().title, line); }

    // ---- Harm, always capped. ----

    /** Hurts a victim by {@code base} scaled by power and difficulty, never more than {@link #MAX_HIT_SHARE} of its maximum health. */
    public boolean hurt(LivingEntity victim, float base) {
        if (victim == null || !victim.isAlive() || victim == this) return false;
        float amount = Math.min(base * scale(), victim.getMaxHealth() * MAX_HIT_SHARE);
        return victim.damage(getDamageSources().mobAttack(this), amount);
    }
    /** Pushes a victim away from a point, horizontally by {@code strength} and up by {@code lift}. */
    public static void push(LivingEntity victim, Vec3d from, double strength, double lift) {
        Vec3d away = victim.getPos().subtract(from).multiply(1, 0, 1);
        away = away.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : away.normalize();
        victim.addVelocity(away.x * strength, lift, away.z * strength);
        victim.velocityModified = true;
    }

    /** Players and the current target within {@code radius}: who an area attack can reach. Creative and spectators are spared. */
    public List<LivingEntity> victims(Vec3d center, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        if (!(getWorld() instanceof ServerWorld world)) return out;
        for (ServerPlayerEntity player : world.getPlayers(p -> p.isAlive() && !p.isSpectator() && !p.isCreative()
                && p.squaredDistanceTo(center) <= radius * radius)) out.add(player);
        LivingEntity target = getTarget();
        if (target != null && !(target instanceof PlayerEntity) && target.isAlive() && target.squaredDistanceTo(center) <= radius * radius) out.add(target);
        return out;
    }
    public List<ServerPlayerEntity> playersNear(double radius) {
        if (!(getWorld() instanceof ServerWorld world)) return List.of();
        return world.getPlayers(p -> p.isAlive() && !p.isSpectator() && p.squaredDistanceTo(this) <= radius * radius);
    }

    // ---- Casting with a telegraph. ----

    /** Starts an attack that strikes after {@code warn} ticks; {@code warning} runs every tick before with the ticks left. */
    protected void cast(String name, int warn, boolean holdStill, IntConsumer warning, Runnable strike) {
        casting = new Cast(name, Math.max(10, warn), holdStill, warning, strike);
        castLeft = casting.total();
        dataTracker.set(CAST_STATE, (byte) (ultimateNext ? 2 : 1));
        ultimateNext = false;
        if (holdStill) getNavigation().stop();
        if (name != null) for (ServerPlayerEntity player : playersNear(32)) player.sendMessage(Text.literal("⚠ " + name).formatted(Formatting.RED), true);
        debug("готовит: " + name + " за " + warn + " тиков");
    }
    /** The ultimate: a title three seconds ahead, then the strike. */
    protected void castUltimate(IntConsumer warning, Runnable strike) {
        for (ServerPlayerEntity player : playersNear(48)) {
            player.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 50, 10));
            player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(kind().title + " готовит удар — уходи!").formatted(Formatting.GOLD)));
            player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("«" + kind().ultimate + "»").formatted(Formatting.DARK_RED, Formatting.BOLD)));
        }
        ultimateNext = true;
        cast(null, ULT_WARNING, true, warning, strike);
    }
    public boolean isCasting() { return casting != null; }
    /** Tests and the command: cast the ultimate on the next tick (at the nearest player when there is no target). */
    public void forceUltimate() {
        if (getTarget() == null) getWorld().getPlayers().stream().filter(p -> p.isAlive() && !p.isSpectator() && p.squaredDistanceTo(this) < 48 * 48)
                .min(java.util.Comparator.comparingDouble(p -> p.squaredDistanceTo(this))).ifPresent(this::setTarget);
        casting = null;
        dataTracker.set(CAST_STATE, (byte) 0);
        ultTimer = 1;
    }
    /** Tests: how many of its attacks are in flight. */
    public int hazardCount() { return hazards.count(); }
    /** Tests: cast an ordinary attack on the next tick. */
    public void forceAttack() { attackTimer = 1; }
    /** 0..1: how far the current cast has come. */
    protected float castProgress() { return casting == null ? 0 : 1f - castLeft / (float) casting.total(); }

    // ---- The fight loop. ----

    @Override protected void mobTick() {
        super.mobTick();
        if (!(getWorld() instanceof ServerWorld world)) return;
        hazards.tick(world);
        bar.setPercent(getHealth() / getMaxHealth());
        int now = Math.min(phaseCount(), 1 + (int) ((1f - getHealth() / getMaxHealth()) * phaseCount()));
        if (now > phase) {
            phase = now;
            dataTracker.set(PHASE, (byte) phase);
            for (ServerPlayerEntity player : playersNear(48))
                player.sendMessage(Text.literal(kind().title + ": фаза " + phase).formatted(Formatting.DARK_RED), true);
            onPhase(phase);
            ultTimer = Math.min(ultTimer, 60);
        }
        LivingEntity target = getTarget();
        if (target != null && (!target.isAlive() || target.isSpectator() || target instanceof PlayerEntity p && p.isCreative())) { setTarget(null); target = null; }
        playTheme(world, target != null);

        if (casting != null) {
            Cast cast = casting;
            if (target != null) getLookControl().lookAt(target, 30, 30);
            if (cast.holdStill()) getNavigation().stop();
            try { cast.warning().accept(castLeft); } catch (RuntimeException failure) { DivineBlockMod.LOG.warn("Божественный блок: знак удара упал", failure); }
            if (--castLeft <= 0) {
                casting = null;
                dataTracker.set(CAST_STATE, (byte) 0);
                try { cast.strike().run(); } catch (RuntimeException failure) { DivineBlockMod.LOG.warn("Божественный блок: удар босса упал", failure); }
            }
            return;
        }
        if (target == null) { idle(); return; }
        idleTicks = 0;
        pursue(target);
        getLookControl().lookAt(target, 30, 30);
        if (meleeTimer > 0) meleeTimer--;
        if (meleeReach() > 0 && meleeTimer == 0 && squaredDistanceTo(target) <= meleeReach() * meleeReach() && canSee(target)) {
            swingHand(net.minecraft.util.Hand.MAIN_HAND);
            if (hurt(target, meleeDamage())) push(target, getPos(), 0.5, 0.2);
            meleeTimer = 30;
        }
        if (--ultTimer <= 0) { ultimate(target); ultTimer = (phase == 1 ? 30 : phase == 2 ? 24 : 18) * 20; return; }
        if (--attackTimer <= 0) attackTimer = Math.max(20, attack(target));
    }

    private void idle() {
        if (++idleTicks > 100 && home != null && !home.isWithinDistance(getBlockPos(), 6) && age % 40 == 0)
            getNavigation().startMovingTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0.8);
        if (idleTicks > 200) ultTimer = Math.max(ultTimer, 20 * 12);
    }

    private void playTheme(ServerWorld world, boolean fighting) {
        if (!fighting) { if (musicTimer > 0) stopTheme(world); musicTimer = 0; return; }
        if (musicTimer-- > 0) return;
        musicTimer = 20 * 34;
        if (config().chantVolume <= 0) return;
        SoundEvent chant = SoundEvent.of(Identifier.of(DivineBlockMod.ID, "chant." + theme()));
        world.playSound(null, getX(), getY(), getZ(), RegistryEntry.of(chant), SoundCategory.HOSTILE, 1.6f * config().chantVolume / 100f, 1f, world.random.nextLong());
    }
    private void stopTheme(ServerWorld world) {
        Identifier id = Identifier.of(DivineBlockMod.ID, "chant." + theme());
        for (ServerPlayerEntity player : playersNear(64)) player.networkHandler.sendPacket(new StopSoundS2CPacket(id, SoundCategory.HOSTILE));
    }

    /** Plays a sound at the boss for everyone around. */
    public void sound(RegistryEntry<SoundEvent> sound, float volume, float pitch) {
        getWorld().playSound(null, getX(), getY(), getZ(), sound, SoundCategory.HOSTILE, volume, pitch);
    }
    public void sound(SoundEvent sound, float volume, float pitch) {
        getWorld().playSound(null, getX(), getY(), getZ(), sound, SoundCategory.HOSTILE, volume, pitch);
    }

    // ---- Boss bar, saving, death. ----

    @Override public void onStartedTrackingBy(ServerPlayerEntity player) { super.onStartedTrackingBy(player); bar.addPlayer(player); }
    @Override public void onStoppedTrackingBy(ServerPlayerEntity player) { super.onStoppedTrackingBy(player); bar.removePlayer(player); }
    @Override public void setCustomName(Text name) { super.setCustomName(name); bar.setName(getDisplayName()); }

    @Override public void onDeath(DamageSource source) {
        super.onDeath(source);
        hazards.clear();
        casting = null;
        bar.setPercent(0);
        if (getWorld() instanceof ServerWorld world) {
            stopTheme(world);
            for (ServerPlayerEntity player : playersNear(64)) {
                player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 60, 20));
                player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("повержен").formatted(Formatting.GRAY)));
                player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(kind().title).formatted(Formatting.GOLD)));
            }
        }
        try { onDefeated(); } catch (RuntimeException failure) { DivineBlockMod.LOG.warn("Божественный блок: посмертие босса упало", failure); }
    }

    @Override public void remove(RemovalReason reason) {
        super.remove(reason);
        hazards.clear();
        bar.clearPlayers();
        if (getWorld() instanceof ServerWorld world && reason.shouldDestroy()) stopTheme(world);
    }

    @Override public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("DivinePower", power);
        nbt.putInt("DivinePhase", phase);
        if (home != null) nbt.putLong("DivineHome", home.asLong());
    }
    @Override public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("DivinePower")) power = Math.max(1, Math.min(5, nbt.getInt("DivinePower")));
        if (nbt.contains("DivinePhase")) { phase = Math.max(1, Math.min(phaseCount(), nbt.getInt("DivinePhase"))); dataTracker.set(PHASE, (byte) phase); }
        if (nbt.contains("DivineHome")) home = BlockPos.fromLong(nbt.getLong("DivineHome"));
        if (hasCustomName()) bar.setName(getDisplayName());
    }

    @Override public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource source) { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean canUsePortals(boolean allowVehicles) { return false; }
    @Override protected boolean isDisallowedInPeaceful() { return true; }
    @Override public boolean cannotDespawn() { return true; }
    @Override public boolean canImmediatelyDespawn(double distanceSquared) { return false; }

    /** Spawns particles for every player around (further than the vanilla 32 blocks). */
    public void particles(net.minecraft.particle.ParticleEffect effect, double x, double y, double z, int count, double dx, double dy, double dz, double speed) {
        if (!(getWorld() instanceof ServerWorld world)) return;
        for (ServerPlayerEntity player : world.getPlayers(p -> p.squaredDistanceTo(x, y, z) < 96 * 96))
            world.spawnParticles(player, effect, true, x, y, z, count, dx, dy, dz, speed);
    }

    /** Whether {@code entity} is a living player or mob this boss may harm (not another boss, not itself). */
    public boolean isFoe(Entity entity) {
        return entity instanceof LivingEntity living && living.isAlive() && !(entity instanceof SacredBoss)
                && (entity instanceof PlayerEntity player ? !player.isCreative() && !player.isSpectator() : entity == getTarget());
    }
}
