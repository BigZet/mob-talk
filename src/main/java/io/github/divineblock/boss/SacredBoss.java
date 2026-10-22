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
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundEvents;
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
    /** Share of a blow the boss takes normally, and while its weak point is open (five times as much). Its armour attribute
     * is light, so the weapon's tier counts: a normal fight is long but finite, one that uses the weak point takes minutes. */
    public static final float TAKEN_NORMAL = 0.22f, TAKEN_EXPOSED = TAKEN_NORMAL * 5f;
    /** How long the weak point stays open, how soon it can open again, and how long a phase change staggers the boss. */
    public static final int EXPOSED_TICKS = 120, WEAK_COOLDOWN = 200, TRANSITION_TICKS = 40;
    /** How long the boss keeps a foe it cannot see, searching where it saw it last. */
    public static final int MEMORY_TICKS = 200;
    /** How long a chant runs (the longest piece is ~34 s): it is never started again over itself. */
    static final int CHANT_TICKS = 20 * 34;

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
    private int transition, exposedTicks, weakCooldown, hintCooldown, unseenTicks, weakOpened;
    private BossBar.Color barColor;
    private Vec3d lastSeen;

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
                .add(EntityAttributes.GENERIC_ARMOR, armor * 0.35)
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
        targetSelector.add(2, targetGoal());
    }
    /** Hunts players and keeps one out of sight for {@link #MEMORY_TICKS} (behind a tree it is not forgotten at once). */
    protected ActiveTargetGoal<PlayerEntity> targetGoal() {
        ActiveTargetGoal<PlayerEntity> goal = new ActiveTargetGoal<>(this, PlayerEntity.class, true);
        goal.setMaxTimeWithoutVisibility(MEMORY_TICKS);
        return goal;
    }

    // ---- What each boss defines. ----

    public abstract BossKind kind();
    /** One ordinary attack (usually through {@link #cast}); returns the ticks until the next one. */
    protected abstract int attack(LivingEntity target);
    /** The ultimate: must go through {@link #castUltimate}, so it is announced. */
    protected abstract void ultimate(LivingEntity target);
    /** The boss's chant (a piece from the mod's sounds), looped while it fights. */
    protected abstract String theme();
    /** How many phases the fight has (the Four Horsemen have four, split evenly; three are 100–60 %, 60–25 %, below 25 %). */
    protected int phaseCount() { return 3; }
    /** The phase for a share of health left. */
    protected int phaseFor(float share) {
        if (phaseCount() == 3) return share > 0.6f ? 1 : share > 0.25f ? 2 : 3;
        return Math.min(phaseCount(), 1 + (int) ((1f - share) * phaseCount()));
    }
    /** The name a new phase is announced by (the title over the screen). */
    protected String phaseName(int phase) { return phase >= phaseCount() ? "Ярость" : "Гнев"; }
    /** One line for the subtitle of the ultimate's title: how to live through it. */
    protected String ultHint() { return "уходи из отмеченного!"; }
    /** One line for the player who hits the boss outside its weak window: what opens it. */
    protected String weakHint() { return "ищи слабое место"; }
    /** Where the weak point glows (particles while it is open). */
    protected Vec3d weakSpot() { return new Vec3d(getX(), getY() + getHeight() * 0.6, getZ()); }
    /** Whether {@code source} (already known not to bypass invulnerability) is the boss's counter: it opens the weak point. */
    protected boolean weakTrigger(DamageSource source, float amount) { return false; }
    /** Tests and the command: does what a player would do to open the weak point (or the nearest thing to it in code). */
    public void provokeWeakPoint(ServerPlayerEntity player) { expose(EXPOSED_TICKS, weakReason()); }
    /** A new phase began (2, 3, ...): announce it, toughen up, call help. */
    protected void onPhase(int phase) { }
    /** Melee reach in blocks; 0 for bosses that never strike in melee. */
    protected double meleeReach() { return 3.2; }
    protected float meleeDamage() { return 7f; }
    /** Moves toward or around the target; the default walks up to melee reach. */
    protected void pursue(LivingEntity target) {
        if (unseenTicks > 10 && lastSeen != null) {
            // Out of sight: it goes to where it saw the foe last and searches there.
            if (age % 10 == 0) getNavigation().startMovingTo(lastSeen.x, lastSeen.y, lastSeen.z, 1.0);
            return;
        }
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
    protected float scale() { return (0.7f + 0.15f * power) * config().bossDifficulty / 100f * (1f + 0.12f * (phase - 1)); }
    /** Whether ultimates may break glass, put out lights and light fires here. */
    protected boolean mayBreakBlocks() {
        return config().bossBlockDamage && getWorld() instanceof ServerWorld world && world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING);
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
            player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(kind().title + ": " + ultHint()).formatted(Formatting.GOLD)));
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
        int now = phaseFor(getHealth() / getMaxHealth());
        if (now > phase) beginPhase(now);
        LivingEntity target = getTarget();
        if (target != null && (!target.isAlive() || target.isSpectator() || target instanceof PlayerEntity p && p.isCreative())) { setTarget(null); target = null; }
        remember(target);
        playTheme(world, target != null);
        if (exposedTicks > 0) tickExposed();
        else if (weakCooldown > 0) weakCooldown--;
        if (hintCooldown > 0) hintCooldown--;
        if (transition > 0) {
            transition--;
            getNavigation().stop();
            if (age % 2 == 0) particles(phase >= phaseCount() ? ParticleTypes.FLAME : ParticleTypes.ENCHANT, getX(), getY() + getHeight() * 0.5, getZ(), 6,
                    getWidth() * 0.6, getHeight() * 0.4, getWidth() * 0.6, 0.08);
            return;
        }
        if (phase >= phaseCount() && phaseCount() > 1 && age % 8 == 0)
            particles(ParticleTypes.ANGRY_VILLAGER, getX(), getY() + getHeight() * 1.02, getZ(), 1, getWidth() * 0.3, 0.1, getWidth() * 0.3, 0);

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
        if (exposedTicks > 0) { getNavigation().stop(); return; }
        pursue(target);
        getLookControl().lookAt(target, 30, 30);
        if (meleeTimer > 0) meleeTimer--;
        if (meleeReach() > 0 && meleeTimer == 0 && squaredDistanceTo(target) <= meleeReach() * meleeReach() && canSee(target)) {
            swingHand(net.minecraft.util.Hand.MAIN_HAND);
            if (hurt(target, meleeDamage())) push(target, getPos(), 0.5, 0.2);
            meleeTimer = 30;
        }
        if (--ultTimer <= 0) { ultimate(target); ultTimer = (phase == 1 ? 30 : phase == 2 ? 24 : 18) * 20; return; }
        if (--attackTimer <= 0) attackTimer = Math.max(20, Math.round(attack(target) * (phase <= 1 ? 1f : phase >= phaseCount() ? 0.7f : 0.85f)));
    }

    // ---- Phases, the weak point and the foe out of sight. ----

    /** A new phase: a title for everyone around, a short stagger in which it takes no harm, then its own changes. */
    private void beginPhase(int now) {
        phase = now;
        dataTracker.set(PHASE, (byte) phase);
        casting = null;
        dataTracker.set(CAST_STATE, (byte) 0);
        if (exposedTicks > 0) closeWeakPoint();
        transition = TRANSITION_TICKS;
        boolean last = phase >= phaseCount();
        for (ServerPlayerEntity player : playersNear(48)) {
            player.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 30, 10));
            player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(kind().title + " — фаза " + phase + (last ? ": сильнее и быстрее" : ": новые удары"))
                    .formatted(Formatting.GOLD)));
            player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("«" + phaseName(phase) + "»").formatted(last ? Formatting.DARK_RED : Formatting.RED, Formatting.BOLD)));
        }
        sound(SoundEvents.ENTITY_WITHER_SPAWN, 1.0f, 0.7f + 0.15f * phase);
        sound(SoundEvents.ITEM_TOTEM_USE, 1.4f, 0.6f);
        particles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY() + getHeight() * 0.5, getZ(), 1, 0, 0, 0, 0);
        particles(last ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.TOTEM_OF_UNDYING, getX(), getY() + getHeight() * 0.5, getZ(), 80,
                getWidth() * 0.5, getHeight() * 0.4, getWidth() * 0.5, 0.4);
        debug("фаза " + phase);
        try { onPhase(phase); } catch (RuntimeException failure) { DivineBlockMod.LOG.warn("Божественный блок: смена фазы упала", failure); }
        ultTimer = Math.min(ultTimer, 60 + TRANSITION_TICKS);
        attackTimer = Math.max(attackTimer, TRANSITION_TICKS + 10);
    }
    /** Tests: whether a phase change stagger is running. */
    public boolean inTransition() { return transition > 0; }

    /** Opens the weak point for {@code ticks}: it stops, glows, its bar turns white, and blows hurt it five times as much. */
    public void expose(int ticks, String why) {
        if (!(getWorld() instanceof ServerWorld) || !isAlive() || transition > 0) return;
        boolean fresh = exposedTicks == 0;
        exposedTicks = Math.max(exposedTicks, ticks);
        if (!fresh) return;
        weakOpened++;
        casting = null;
        dataTracker.set(CAST_STATE, (byte) 0);
        getNavigation().stop();
        setGlowing(true);
        barColor = bar.getColor();
        bar.setColor(BossBar.Color.WHITE);
        bar.setName(Text.literal("").append(getDisplayName()).append(Text.literal("  ✦ слабое место ✦").formatted(Formatting.WHITE, Formatting.BOLD)));
        Vec3d spot = weakSpot();
        particles(ParticleTypes.FLASH, spot.x, spot.y, spot.z, 1, 0, 0, 0, 0);
        particles(ParticleTypes.END_ROD, spot.x, spot.y, spot.z, 40, 0.4, 0.4, 0.4, 0.15);
        sound(SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 2.5f, 0.6f);
        sound(SoundEvents.BLOCK_BEACON_DEACTIVATE, 2f, 1.4f);
        for (ServerPlayerEntity player : playersNear(48)) {
            player.networkHandler.sendPacket(new TitleFadeS2CPacket(2, 30, 8));
            player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(why).formatted(Formatting.YELLOW)));
            player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("✦ Слабое место! ✦").formatted(Formatting.WHITE, Formatting.BOLD)));
        }
        debug("слабое место открыто на " + ticks + " тиков: " + why);
    }
    /** On the server: whether the weak point is open. */
    public boolean exposed() { return exposedTicks > 0; }
    /** Tests: how many times the weak point has opened. */
    public int weakOpened() { return weakOpened; }
    /** Whether the weak point may open now (not right after the last time, not in a phase change). */
    protected boolean weakReady() { return exposedTicks == 0 && weakCooldown == 0 && transition == 0; }
    /** Tests: let the weak point open again at once. */
    public void rearmWeakPoint() { weakCooldown = 0; }

    private void tickExposed() {
        getNavigation().stop();
        Vec3d spot = weakSpot();
        if (age % 2 == 0) particles(ParticleTypes.END_ROD, spot.x, spot.y, spot.z, 2, 0.35, 0.35, 0.35, 0.01);
        if (age % 5 == 0) particles(ParticleTypes.ENCHANTED_HIT, spot.x, spot.y, spot.z, 4, 0.5, 0.5, 0.5, 0.1);
        if (age % 20 == 0) {
            sound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 2f, 0.8f);
            for (ServerPlayerEntity player : playersNear(48))
                player.sendMessage(Text.literal("✦ Слабое место открыто: " + (exposedTicks / 20 + 1) + " с — бей! ✦").formatted(Formatting.WHITE, Formatting.BOLD), true);
        }
        if (--exposedTicks <= 0) closeWeakPoint();
    }
    private void closeWeakPoint() {
        exposedTicks = 0;
        weakCooldown = WEAK_COOLDOWN;
        setGlowing(false);
        if (barColor != null) bar.setColor(barColor);
        bar.setName(getDisplayName());
        sound(SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.5f, 0.6f);
        attackTimer = Math.min(attackTimer, 20);
    }

    /** Harm to the boss: none while a phase changes; the counter opens the weak point; then the share by the window. */
    @Override public boolean damage(DamageSource source, float amount) {
        if (getWorld().isClient || isInvulnerableTo(source) || source.isIn(net.minecraft.registry.tag.DamageTypeTags.BYPASSES_INVULNERABILITY))
            return super.damage(source, amount);
        if (transition > 0) {
            if (age % 4 == 0) sound(SoundEvents.ITEM_SHIELD_BLOCK, 1.2f, 0.6f);
            return false;
        }
        if (weakReady() && weakTrigger(source, amount)) expose(EXPOSED_TICKS, weakReason());
        if (exposedTicks > 0) {
            amount *= TAKEN_EXPOSED;
            Vec3d spot = weakSpot();
            particles(ParticleTypes.CRIT, spot.x, spot.y, spot.z, 14, 0.4, 0.4, 0.4, 0.4);
            if (source.getAttacker() instanceof PlayerEntity) sound(SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.5f, 0.7f);
        } else {
            amount *= TAKEN_NORMAL;
            if (hintCooldown == 0 && source.getAttacker() instanceof ServerPlayerEntity player) {
                player.sendMessage(Text.literal(kind().title + " почти неуязвим: " + weakHint()).formatted(Formatting.GOLD), true);
                hintCooldown = 20 * 25;
            }
        }
        return super.damage(source, amount);
    }
    /** The subtitle when the counter opened the weak point. */
    protected String weakReason() { return "бей сейчас — урон впятеро!"; }

    /** Keeps where the foe was seen last, and counts how long it has been hidden. */
    private void remember(LivingEntity target) {
        if (target == null) { unseenTicks = 0; return; }
        if (age % 5 != 0) return;
        if (canSee(target)) { lastSeen = target.getPos(); unseenTicks = 0; }
        else unseenTicks += 5;
        if (unseenTicks > 10 && lastSeen != null && squaredDistanceTo(lastSeen) < 4) lastSeen = target.getPos();
    }

    private void idle() {
        if (++idleTicks > 100 && home != null && !home.isWithinDistance(getBlockPos(), 6) && age % 40 == 0)
            getNavigation().startMovingTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0.8);
        if (idleTicks > 200) ultTimer = Math.max(ultTimer, 20 * 12);
    }

    /** The chant comes from the boss itself (it follows it and fades with distance) and plays to its end: losing the foe,
     * the foe's death and respawn never start it again over a piece still sounding, and never cut it off. */
    private void playTheme(ServerWorld world, boolean fighting) {
        if (musicTimer > 0) musicTimer--;
        if (!fighting || musicTimer > 0) return;
        musicTimer = CHANT_TICKS;
        if (config().chantVolume <= 0) return;
        SoundEvent chant = SoundEvent.of(Identifier.of(DivineBlockMod.ID, "chant." + theme()));
        world.playSoundFromEntity(null, this, RegistryEntry.of(chant), SoundCategory.HOSTILE, Math.max(1f, 1.6f * config().chantVolume / 100f), 1f, world.random.nextLong());
    }
    /** Tests: ticks until the chant may start again (0: silent). */
    public int chantLeft() { return musicTimer; }
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
