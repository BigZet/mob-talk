package io.github.divineblock.boss;

import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

/** Голиаф (1 Samuel 17): a giant of Gath in a brass helmet and scale mail. He thrusts with a spear like a weaver's beam (a
 * telegraphed line; twice in a row from phase 2), bashes with his shield, hurls the brass javelin from between his shoulders at
 * those who keep away, and roars a taunt that stuns («Оглушение»: no striking, heavy feet). Ultimate «Вызов воинству»: he
 * calls for a man to fight him, throws his great spear at the challenger's place (two spears in phase 3) and slams the
 * ground, sending out a stunning ring. His armour shrugs off swords, but not his bare forehead: a projectile that hits his head
 * (a snowball or an egg too, like David's stone) deals heavy damage and brings him to his knees for two seconds. */
public final class GoliathEntity extends SacredBoss {
    private static final TrackedData<Boolean> KNEELING = DataTracker.registerData(GoliathEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Byte> MOVE = DataTracker.registerData(GoliathEntity.class, TrackedDataHandlerRegistry.BYTE);
    /** Which move the model should pose: none, the spear thrust, the shield bash, the roar, the javelin, the stamp. */
    public static final int MOVE_NONE = 0, MOVE_THRUST = 1, MOVE_BASH = 2, MOVE_ROAR = 3, MOVE_JAVELIN = 4, MOVE_SLAM = 5;
    private static final DustParticleEffect BRASS = new DustParticleEffect(new Vector3f(0.9f, 0.65f, 0.25f), 1.6f);
    private static final DustParticleEffect SPEAR_LINE = new DustParticleEffect(new Vector3f(1f, 0.3f, 0.15f), 2.2f);
    /** How long a stone in the forehead keeps him on his knees, and how soon the next one counts again. */
    static final int STAGGER = 40, HEAD_COOLDOWN = 60;
    /** How long the roar and the slam stun. */
    static final int STUN = 60;

    private int stagger, moveTicks;

    public GoliathEntity(EntityType<? extends GoliathEntity> type, World world) { super(type, world, BossBar.Color.YELLOW); }

    /** «Оглушение»: a stunned player cannot strike anything. */
    static void registerHooks() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!player.hasStatusEffect(BossEffects.STUN)) return ActionResult.PASS;
            if (!world.isClient) player.sendMessage(Text.literal("Оглушение: руки не слушаются").formatted(Formatting.GRAY), true);
            return ActionResult.FAIL;
        });
    }

    /** Stuns a victim: no striking (see {@link #registerHooks}), heavy feet and a short dizziness. */
    static void stun(LivingEntity victim, int ticks) {
        BossEffects.afflict(victim, BossEffects.STUN, ticks);
        victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, ticks, 2, false, false));
        victim.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, Math.min(ticks, 50), 0, false, false));
    }

    @Override protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(KNEELING, false);
        builder.add(MOVE, (byte) 0);
    }
    /** On the client too: he is on his knees after a stone in the forehead. */
    public boolean kneeling() { return dataTracker.get(KNEELING); }
    private boolean staggered() { return stagger > 0; }
    /** On the client too: the move being wound up or struck, for the model's pose. */
    public int move() { return dataTracker.get(MOVE); }
    private void pose(int move, int ticks) { dataTracker.set(MOVE, (byte) move); moveTicks = ticks; }

    @Override public BossKind kind() { return BossKind.GOLIATH; }
    @Override protected String theme() { return "punishment_21"; }
    @Override protected double meleeReach() { return staggered() ? 0 : getWidth() * 0.5 + 3.6; }
    @Override protected float meleeDamage() { return 9f; }

    @Override protected void pursue(LivingEntity target) {
        if (staggered()) { getNavigation().stop(); return; }
        super.pursue(target);
    }

    @Override protected void mobTick() {
        if (moveTicks > 0 && --moveTicks == 0) dataTracker.set(MOVE, (byte) MOVE_NONE);
        if (stagger > 0 && --stagger == 0) dataTracker.set(KNEELING, false);
        super.mobTick();
        if (staggered()) {
            getNavigation().stop();
            if (age % 4 == 0) particles(ParticleTypes.CRIT, getX(), getY() + getHeight() * 0.6, getZ(), 3, 0.5, 0.2, 0.5, 0.05);
        }
    }

    // ---- The weak point: the forehead. ----

    /** A snowball or an egg hurts nothing by itself: in his forehead it hits like David's stone. */
    @Override public boolean damage(DamageSource source, float amount) {
        if (!getWorld().isClient && source.getSource() instanceof ProjectileEntity projectile && amount < 2 && headHit(projectile)) amount = 6f;
        return super.damage(source, amount);
    }

    /** The weak point: a projectile in the bare forehead brings him to his knees (the window of {@link SacredBoss}). */
    @Override protected boolean weakTrigger(DamageSource source, float amount) {
        if (!(source.getSource() instanceof ProjectileEntity projectile) || !headHit(projectile)) return false;
        headshot(source.getAttacker());
        return true;
    }
    @Override protected String weakHint() { return "медный доспех держит удар — целься ему в открытый лоб (стрела, снежок, яйцо)"; }
    @Override protected String weakReason() { return "Камень в лоб: Голиаф на коленях — бей!"; }
    @Override protected String ultHint() { return "уйди с линии копья, перепрыгни кольцо удара о землю"; }
    @Override protected Vec3d weakSpot() { return getEyePos(); }
    @Override protected String phaseName(int phase) { return phase == 2 ? "Посрамлю полки Израильские" : "Отдам тело твоё птицам"; }

    @Override public void provokeWeakPoint(ServerPlayerEntity player) {
        // A snowball thrown at his forehead, as David's stone.
        var ball = new net.minecraft.entity.projectile.thrown.SnowballEntity(getWorld(), player);
        Vec3d head = getEyePos();
        ball.setPosition(head.x, head.y, head.z - 0.6);
        ball.setVelocity(0, 0, 1.2);
        damage(getDamageSources().thrown(ball, player), 0f);
        ball.discard();
    }

    /** Whether a projectile struck his head: where its flight crosses his box (or where it is) lies above his shoulders
     * (the model's chin is at 0.79 of the box's height and the helmet's brim at 0.93). */
    private boolean headHit(ProjectileEntity projectile) {
        Vec3d pos = projectile.getPos(), velocity = projectile.getVelocity();
        Vec3d at = getBoundingBox().expand(0.3).raycast(pos.subtract(velocity), pos.add(velocity.multiply(1.5))).orElse(pos);
        return at.y >= getY() + getHeight() * 0.76;
    }

    private void headshot(Entity shooter) {
        stagger = EXPOSED_TICKS;
        dataTracker.set(KNEELING, true);
        pose(MOVE_NONE, 1);
        getNavigation().stop();
        Vec3d head = getEyePos();
        particles(ParticleTypes.CRIT, head.x, head.y, head.z, 40, 0.4, 0.3, 0.4, 0.4);
        particles(ParticleTypes.ENCHANTED_HIT, head.x, head.y, head.z, 20, 0.3, 0.3, 0.3, 0.2);
        sound(SoundEvents.BLOCK_ANVIL_LAND, 2f, 1.6f);
        sound(SoundEvents.ENTITY_IRON_GOLEM_DAMAGE, 2f, 0.5f);
        for (ServerPlayerEntity player : playersNear(48))
            player.sendMessage(Text.literal(player == shooter ? "Камень вонзился ему в лоб! Голиаф пал на колени" : "Голиаф пал на колени")
                    .formatted(Formatting.YELLOW, Formatting.BOLD), true);
        debug("попадание в лоб, на коленях " + STAGGER + " тиков");
    }

    /** A blow that comes while he kneels is lost: the stone took his strength. */
    private boolean fizzled() {
        if (!staggered()) return false;
        particles(ParticleTypes.SMOKE, getX(), getY() + getHeight() * 0.45, getZ(), 12, 0.8, 0.8, 0.8, 0.02);
        return true;
    }

    // ---- Attacks. ----

    @Override protected int attack(LivingEntity target) {
        if (staggered()) return 20;
        double distance = distanceTo(target);
        int roll = random.nextInt(3);
        if (distance > 8.5) { javelin(target); return 80; }
        if (distance < 4.5 && roll == 0) { bash(target); return 60; }
        if (roll == 2) { roar(); return 80; }
        thrust(target);
        return phase >= 2 ? 65 : 75;
    }

    /** «Удар копьём»: a red line along the spear's way, then the thrust; from phase 2 a second one follows at once. */
    private void thrust(LivingEntity target) {
        int warn = phase >= 2 ? 14 : 20;
        pose(MOVE_THRUST, warn + 12);
        cast("Удар копьём", warn, true, left -> {
            if (left == warn) sound(SoundEvents.ITEM_TRIDENT_RETURN, 2f, 0.5f);
        }, () -> { if (!fizzled()) { swingHand(Hand.MAIN_HAND); sound(SoundEvents.ITEM_TRIDENT_THROW.value(), 2f, 0.5f); } });
        spearLine(target, warn);
        if (phase >= 2) hazards.later(warn + 8, () -> {
            if (!target.isAlive() || staggered() || isCasting()) return;
            pose(MOVE_THRUST, 26);
            cast("Второй удар копьём", 14, true, left -> { }, () -> { if (!fizzled()) { swingHand(Hand.MAIN_HAND); sound(SoundEvents.ITEM_TRIDENT_THROW.value(), 2f, 0.6f); } });
            spearLine(target, 14);
        });
    }

    private void spearLine(LivingEntity target, int warn) {
        Vec3d from = new Vec3d(getX(), getY() + getHeight() * 0.45, getZ());
        Vec3d to = from.add(target.getPos().add(0, 1, 0).subtract(from).normalize().multiply(10));
        hazards.beam(from, to, warn, 4, 0.7, SPEAR_LINE, ParticleTypes.CRIT, victim -> {
            if (!staggered() && hurt(victim, 10f)) push(victim, from, 0.9, 0.3);
        });
    }

    /** «Удар щитом»: the ground before him is marked, then the round shield knocks everyone there away. */
    private void bash(LivingEntity target) {
        Vec3d front = getPos().add(flat(target).multiply(getWidth() * 0.5 + 1.8));
        pose(MOVE_BASH, 30);
        cast("Удар щитом", 18, true, left -> { if (left == 18) sound(SoundEvents.ITEM_SHIELD_BLOCK, 2f, 0.5f); }, () -> { });
        hazards.zone(front, 2.8, 18, ParticleTypes.CRIT, at -> {
            if (fizzled()) return;
            swingHand(Hand.OFF_HAND);
            sound(SoundEvents.ENTITY_IRON_GOLEM_ATTACK, 2f, 0.5f);
            particles(ParticleTypes.CLOUD, at.x, at.y + 0.5, at.z, 20, 1, 0.3, 1, 0.05);
        }, victim -> {
            if (!staggered() && hurt(victim, 8f)) {
                push(victim, getPos(), 1.6, 0.45);
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1));
            }
        });
    }

    /** «Рык Голиафа»: his taunt from the valley of Elah; whoever stands within seven blocks is stunned. */
    private void roar() {
        Vec3d center = getPos();
        pose(MOVE_ROAR, 40);
        cast("Рык Голиафа", 26, true, left -> {
            if (left == 26) for (ServerPlayerEntity player : playersNear(32))
                player.sendMessage(Text.literal("Голиаф: «Что ты идёшь на меня с палкою? Разве я собака?»").formatted(Formatting.GOLD));
            if (left % 6 == 0) particles(ParticleTypes.ANGRY_VILLAGER, getX(), getY() + getHeight() * 0.9, getZ(), 2, 0.5, 0.3, 0.5, 0);
        }, () -> { });
        hazards.zone(center, 7, 26, ParticleTypes.CRIT, at -> {
            if (fizzled()) return;
            sound(SoundEvents.ENTITY_RAVAGER_ROAR, 3f, 0.6f);
            particles(ParticleTypes.SONIC_BOOM, getX(), getY() + getHeight() * 0.82, getZ(), 1, 0, 0, 0, 0);
            particles(ParticleTypes.CLOUD, at.x, at.y + 0.5, at.z, 50, 3, 0.4, 3, 0.05);
        }, victim -> {
            if (staggered()) return;
            hurt(victim, 3f);
            stun(victim, STUN);
        });
    }

    /** «Медный дротик»: the javelin from between his shoulders, at a foe who keeps away. */
    private void javelin(LivingEntity target) {
        pose(MOVE_JAVELIN, 30);
        cast("Медный дротик", 22, true, left -> {
            Vec3d hand = handPos();
            if (left % 3 == 0) particles(BRASS, hand.x, hand.y, hand.z, 2, 0.2, 0.2, 0.2, 0);
        }, () -> { });
        throwSpear(target.getPos().add(0, 0.8, 0), 22, 0.9, 7f, false);
    }

    // ---- The ultimate. ----

    @Override protected void ultimate(LivingEntity target) {
        castUltimate(left -> {
            if (left == ULT_WARNING) for (ServerPlayerEntity player : playersNear(48))
                player.sendMessage(Text.literal("Голиаф: «Дайте мне человека, и мы сразимся вдвоём!»").formatted(Formatting.GOLD, Formatting.BOLD));
            Vec3d hand = handPos();
            particles(BRASS, hand.x, hand.y, hand.z, 3, 0.3, 0.3, 0.3, 0);
            if (left % 4 == 0 && target.isAlive()) dotted(hand, target.getPos().add(0, 0.8, 0));
            if (left % 20 == 0) sound(SoundEvents.ITEM_TRIDENT_RETURN, 2.5f, 0.4f);
        }, () -> {
            if (fizzled() || !target.isAlive()) return;
            throwSpear(target.getPos().add(0, 0.8, 0), 16, 1.1, 13f, true);
            if (phase >= 3) hazards.later(14, () -> { if (target.isAlive() && !staggered()) throwSpear(target.getPos().add(0, 0.8, 0), 16, 1.1, 13f, true); });
            hazards.later(phase >= 3 ? 34 : 20, this::slam);
        });
    }

    /** A spear throw: a line to where the foe stands, then it flies along it (the great spear also bursts where it lands). */
    private void throwSpear(Vec3d aim, int warn, double width, float damage, boolean great) {
        Vec3d from = handPos();
        Vec3d line = aim.subtract(from);
        Vec3d to = from.add(line.normalize().multiply(line.length() + 4));
        sound(SoundEvents.ITEM_TRIDENT_RIPTIDE_1, 2f, 0.5f);
        hazards.beam(from, to, warn, great ? 8 : 4, width, SPEAR_LINE, ParticleTypes.CRIT, victim -> {
            if (!staggered() && hurt(victim, damage)) push(victim, from, 1.0, 0.3);
        });
        hazards.later(warn, () -> {
            if (fizzled()) return;
            swingHand(Hand.MAIN_HAND);
            sound(SoundEvents.ITEM_TRIDENT_THROW.value(), 3f, 0.4f);
        });
        if (great) hazards.zone(aim.subtract(0, 0.8, 0), 2.2, warn + 2, BRASS, at -> {
            particles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 2, 0.5, 0.3, 0.5, 0);
            getWorld().playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_TRIDENT_HIT_GROUND, SoundCategory.HOSTILE, 3f, 0.5f);
        }, victim -> { if (!staggered()) hurt(victim, 4f); });
    }

    /** The slam after the throw: he stamps, and a ring runs over the ground (jump it) that stuns whoever it catches. */
    private void slam() {
        if (staggered() || isDead() || isCasting()) return;
        pose(MOVE_SLAM, 30);
        cast("Удар о землю", 22, true, left -> {
            if (left == 22) sound(SoundEvents.ENTITY_WARDEN_ROAR, 2f, 0.6f);
            particles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, getX(), getY() + 0.2, getZ(), 2, 1, 0.1, 1, 0.01);
        }, () -> {
            if (fizzled()) return;
            Vec3d center = getPos();
            sound(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 2f, 0.5f);
            particles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y, center.z, 1, 0, 0, 0, 0);
            hazards.ring(center, 0.45, 13, 1.1, ParticleTypes.CLOUD, victim -> {
                if (hurt(victim, 7f)) { push(victim, center, 0.8, 0.4); stun(victim, STUN); }
            });
        });
    }

    @Override protected void onPhase(int phase) {
        sound(SoundEvents.ENTITY_RAVAGER_ROAR, 3f, 0.5f);
        for (ServerPlayerEntity player : playersNear(48))
            player.sendMessage(Text.literal(phase == 2 ? "Голиаф: «Сегодня я посрамлю полки Израильские!»" : "Голиаф: «Отдам тело твоё птицам небесным!»")
                    .formatted(Formatting.GOLD));
        if (phase == 3) addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, StatusEffectInstance.INFINITE, 0, false, false));
    }

    @Override protected void onDefeated() {
        for (LivingEntity victim : victims(getPos(), 32)) {
            victim.removeStatusEffect(BossEffects.STUN);
            if (victim instanceof PlayerEntity player) player.sendMessage(Text.literal("Исполин пал лицом на землю").formatted(Formatting.GOLD), true);
        }
    }

    // ---- Helpers. ----

    private Vec3d flat(LivingEntity target) {
        Vec3d to = target.getPos().subtract(getPos()).multiply(1, 0, 1);
        return to.lengthSquared() < 1e-4 ? getRotationVector().multiply(1, 0, 1).normalize() : to.normalize();
    }

    private void dotted(Vec3d from, Vec3d to) {
        Vec3d line = to.subtract(from);
        int steps = (int) line.length();
        for (int i = 0; i <= steps; i += 2) {
            Vec3d at = from.add(line.multiply(i / (double) Math.max(1, steps)));
            particles(BRASS, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        }
    }

    /** Where the throwing hand is: raised high at his right shoulder. */
    private Vec3d handPos() {
        float yaw = bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(getX() - Math.cos(yaw) * 1.3, getY() + getHeight() * 0.88, getZ() - Math.sin(yaw) * 1.3);
    }
}
