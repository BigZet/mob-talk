package io.github.divineblock.boss;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.mob.VexEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

/** Безликий лик: a living icon in a gold oklad whose face is a blank burning void. It floats a block above the ground, always
 * turns its face to its foe and keeps its distance. «Луч взора» burns a line through where the foe stood, «Сокрытие» moves it
 * away in a cloud of gold dust, and from the second phase it sends out small flying «лики». Ultimate «Иконостас»: a wall of
 * icon frames rises in an arc behind it and each in turn fires a gaze beam at where the player stood; everyone near takes
 * «Взор лика»: the camera is drawn to the icon (client, {@code BossClient}) and every look into its face hurts a little. */
public final class FacelessIconEntity extends SacredBoss {
    static final ParticleEffect GOLD_DUST = new DustParticleEffect(new Vector3f(0.95f, 0.76f, 0.3f), 1.6f);
    static final ParticleEffect PALE_DUST = new DustParticleEffect(new Vector3f(1f, 0.93f, 0.7f), 1.0f);
    /** At most this many «лики» at once. */
    static final int MAX_FACES = 3;
    /** How far the gaze reaches and how straight a look must be to meet it. */
    static final double GAZE_RANGE = 32, GAZE_DOT = 0.97;

    private final List<UUID> faces = new ArrayList<>();

    public FacelessIconEntity(EntityType<? extends FacelessIconEntity> type, World world) {
        super(type, world, BossBar.Color.WHITE);
        setNoGravity(true);
    }

    /** The gaze's rules run in the icon's own tick ({@link #gaze}) and on the client: nothing to hook. */
    static void registerHooks() { }

    @Override public BossKind kind() { return BossKind.FACELESS_ICON; }
    @Override protected String theme() { return "punishment_21"; }
    @Override protected double meleeReach() { return 0; }

    // ---- Floating and facing. ----

    @Override protected void mobTick() {
        super.mobTick();
        if (!(getWorld() instanceof ServerWorld world)) return;
        hover(world);
        LivingEntity target = getTarget();
        if (target != null) face(target.getPos());
        if (age % 10 == 0) gaze();
    }

    /** Hangs about a block and a half above the ground and bobs slowly. */
    private void hover(ServerWorld world) {
        double ground = getY() - 12;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dy = 0; dy <= 12; dy++) {
            pos.set(getX(), getY() - dy, getZ());
            if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) { ground = pos.getY() + 1; break; }
        }
        double wanted = ground + 1.4 + Math.sin(age * 0.05) * 0.25;
        Vec3d v = getVelocity();
        setVelocity(v.x, MathHelper.clamp((wanted - getY()) * 0.08, -0.12, 0.12), v.z);
    }

    /** Turns the painted face toward a point. */
    private void face(Vec3d point) {
        float yaw = (float) (MathHelper.atan2(point.z - getZ(), point.x - getX()) * MathHelper.DEGREES_PER_RADIAN) - 90f;
        setYaw(yaw);
        setBodyYaw(yaw);
        setHeadYaw(yaw);
    }

    /** Keeps seven to eleven blocks from the foe, drifting sideways a little. */
    @Override protected void pursue(LivingEntity target) {
        Vec3d flat = target.getPos().subtract(getPos()).multiply(1, 0, 1);
        double distance = flat.length();
        Vec3d dir = distance < 1e-3 ? Vec3d.ZERO : flat.multiply(1 / distance);
        double speed = phase >= 3 ? 0.07 : 0.05;
        Vec3d move = Vec3d.ZERO;
        if (distance > 11) move = dir.multiply(speed);
        else if (distance < 7) move = dir.multiply(-speed);
        Vec3d side = new Vec3d(-dir.z, 0, dir.x).multiply(Math.sin(age * 0.02) * speed * 0.6);
        Vec3d v = getVelocity();
        setVelocity(v.x * 0.6 + move.x + side.x, v.y, v.z * 0.6 + move.z + side.z);
    }

    /** Where the blank face is: the beams come out of it. */
    Vec3d facePos() {
        float yaw = getYaw() * MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(getX() - MathHelper.sin(yaw) * 0.3, getY() + getHeight() * 0.68, getZ() + MathHelper.cos(yaw) * 0.3);
    }

    // ---- «Взор лика». ----

    /** Players under the gaze who look the icon in the face (and can see it) are hurt a little, twice a second. */
    private void gaze() {
        Vec3d face = facePos();
        for (ServerPlayerEntity player : playersNear(GAZE_RANGE)) {
            if (!player.hasStatusEffect(BossEffects.GAZE) || player.isCreative()) continue;
            Vec3d to = face.subtract(player.getEyePos());
            if (to.lengthSquared() < 1e-4) continue;
            if (player.getRotationVec(1f).dotProduct(to.normalize()) < GAZE_DOT || !player.canSee(this)) continue;
            float amount = Math.min(1.5f * scale(), Math.min(2.5f, player.getMaxHealth() * MAX_HIT_SHARE));
            if (player.damage(getDamageSources().indirectMagic(this, this), amount))
                particles(PALE_DUST, player.getX(), player.getEyeY(), player.getZ(), 4, 0.2, 0.2, 0.2, 0);
        }
    }

    // ---- Attacks. ----

    @Override protected int attack(LivingEntity target) {
        int roll = random.nextInt(phase >= 2 ? 4 : 3);
        if (roll == 3 && liveFaces() < MAX_FACES) { summonFaces(target); return 70; }
        if (roll == 2 || distanceTo(target) < 4) { stepAway(target); return 50; }
        gazeBeam(target);
        return phase >= 3 ? 45 : phase == 2 ? 55 : 70;
    }

    /** «Луч взора»: the face brightens, then a beam (two or three in later phases, fanned) burns through where the foe stood. */
    private void gazeBeam(LivingEntity target) {
        int warn = phase >= 3 ? 16 : 22;
        Vec3d aim = target.getPos().add(0, target.getHeight() * 0.5, 0);
        cast("Луч взора", warn, true, left -> {
            Vec3d face = facePos();
            particles(PALE_DUST, face.x, face.y, face.z, 4, 0.3, 0.3, 0.3, 0);
            particles(ParticleTypes.END_ROD, face.x, face.y, face.z, 1, 0.2, 0.2, 0.2, 0.02);
            if (left == warn) sound(SoundEvents.BLOCK_BEACON_ACTIVATE, 2f, 1.6f);
        }, () -> {
            Vec3d face = facePos();
            int beams = phase;
            for (int i = 0; i < beams; i++) fireBeam(face, rotate(aim.subtract(face), (i - (beams - 1) / 2.0) * 0.22), 14);
            sound(SoundEvents.ENTITY_GUARDIAN_ATTACK, 2f, 1.4f);
        });
    }

    /** A gaze beam from {@code from} along {@code line}, lengthened past its aim so it cuts a long lane. */
    private void fireBeam(Vec3d from, Vec3d line, int warn) {
        if (line.lengthSquared() < 1e-4) return;
        Vec3d to = from.add(line.normalize().multiply(Math.max(10, line.length() + 6)));
        hazards.beam(from, to, warn, 12, 0.7, GOLD_DUST, ParticleTypes.END_ROD, victim -> {
            if (hurt(victim, 6f)) particles(ParticleTypes.FLASH, victim.getX(), victim.getEyeY(), victim.getZ(), 1, 0, 0, 0, 0);
        });
    }

    private static Vec3d rotate(Vec3d v, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return new Vec3d(v.x * c - v.z * s, v.y, v.x * s + v.z * c);
    }

    /** «Сокрытие»: gold dust gathers where it will appear, then it is there, and its old place bursts. */
    private void stepAway(LivingEntity target) {
        if (!(getWorld() instanceof ServerWorld world)) return;
        Vec3d from = target.getPos();
        double angle = random.nextDouble() * Math.PI * 2;
        Vec3d to = null;
        for (int tries = 0; tries < 6 && to == null; tries++, angle += 1.1) {
            BlockPos at = DivineBosses.room(world, BlockPos.ofFloored(from.add(Math.cos(angle) * 9, 1, Math.sin(angle) * 9)), 3);
            if (at != null) to = Vec3d.ofBottomCenter(at);
        }
        if (to == null) { gazeBeam(target); return; }
        Vec3d dest = to, old = getPos();
        cast("Сокрытие", 18, true, left -> {
            particles(GOLD_DUST, dest.x, dest.y + 1.5, dest.z, 6, 0.8, 1.4, 0.8, 0);
            particles(GOLD_DUST, getX(), getY() + 1.5, getZ(), 4, 1, 1.4, 1, 0);
            if (left == 18) sound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 2f, 0.6f);
        }, () -> {
            particles(ParticleTypes.END_ROD, getX(), getY() + 1.5, getZ(), 30, 0.8, 1.4, 0.8, 0.05);
            requestTeleport(dest.x, dest.y, dest.z);
            sound(SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, 2f, 0.7f);
            hazards.zone(old, 2.5, 12, GOLD_DUST, at -> particles(ParticleTypes.FLASH, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0),
                    victim -> hurt(victim, 5f));
        });
    }

    /** «Лики»: two or three small flying faces (vexes) leave the icon; never more than {@link #MAX_FACES}. */
    private void summonFaces(LivingEntity target) {
        cast("Лики", 24, true, left -> {
            particles(GOLD_DUST, getX(), getY() + 2, getZ(), 6, 1, 1.2, 1, 0);
            if (left == 24) sound(SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, 2f, 1.3f);
        }, () -> {
            if (!(getWorld() instanceof ServerWorld world)) return;
            int count = Math.min(MAX_FACES - liveFaces(), 2 + random.nextInt(2));
            for (int i = 0; i < count; i++) {
                VexEntity face = EntityType.VEX.create(world);
                if (face == null) continue;
                Vec3d at = facePos().add(random.nextGaussian() * 0.8, random.nextGaussian() * 0.5, random.nextGaussian() * 0.8);
                face.refreshPositionAndAngles(at.x, at.y, at.z, getYaw(), 0);
                face.setOwner(this);
                face.setLifeTicks(20 * (25 + random.nextInt(10)));
                face.setCustomName(Text.literal("Лик").formatted(Formatting.GOLD));
                face.setTarget(target);
                world.spawnEntity(face);
                faces.add(face.getUuid());
                particles(ParticleTypes.END_ROD, at.x, at.y, at.z, 10, 0.2, 0.2, 0.2, 0.05);
            }
            sound(SoundEvents.ENTITY_EVOKER_CAST_SPELL, 2f, 1.4f);
        });
    }

    private int liveFaces() {
        if (!(getWorld() instanceof ServerWorld world)) return 0;
        faces.removeIf(id -> { Entity face = world.getEntity(id); return face == null || !face.isAlive(); });
        return faces.size();
    }

    // ---- «Иконостас». ----

    @Override protected void ultimate(LivingEntity target) {
        int count = phase >= 3 ? 7 : phase == 2 ? 6 : 5;
        Vec3d center = getPos();
        castUltimate(left -> {
            // The frames gather faintly where they will stand.
            if (left % 5 == 0) for (Vec3d frame : frames(center, target.getPos(), count)) drawFrame(frame, target.getPos(), PALE_DUST, false);
            particles(ParticleTypes.END_ROD, getX(), getY() + 2, getZ(), 2, 1, 1.5, 1, 0.02);
            if (left % 20 == 0) sound(SoundEvents.BLOCK_BELL_RESONATE, 2f, 1.2f);
        }, () -> iconostasis(target, center, count));
    }

    private void iconostasis(LivingEntity target, Vec3d center, int count) {
        sound(SoundEvents.ENTITY_WITHER_SPAWN, 1.2f, 1.6f);
        for (LivingEntity victim : victims(center, 24)) {
            BossEffects.afflict(victim, BossEffects.GAZE, 15 * 20);
            victim.sendMessage(Text.literal("Взор лика: не смотри ему в лицо").formatted(Formatting.GOLD));
        }
        List<Vec3d> frames = frames(center, target.getPos(), count);
        int every = phase >= 3 ? 10 : 14;
        hazards.repeat(5, (every * count + 40) / 5, step -> { for (Vec3d frame : frames) drawFrame(frame, target.getPos(), GOLD_DUST, true); });
        // Each frame in turn: a flash, then a telegraphed beam at where the foe stands now.
        hazards.repeat(every, count, i -> {
            if (!target.isAlive()) return;
            Vec3d eye = frames.get(i).add(0, 0.4, 0);
            particles(ParticleTypes.FLASH, eye.x, eye.y, eye.z, 1, 0, 0, 0, 0);
            fireBeam(eye, target.getPos().add(0, 1, 0).subtract(eye), 16);
            sound(SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.6f, 1.5f);
        });
        debug("Иконостас: рам " + count);
    }

    /** The frames' centres: an arc behind the icon, away from the foe. */
    private static List<Vec3d> frames(Vec3d center, Vec3d foe, int count) {
        Vec3d away = center.subtract(foe).multiply(1, 0, 1);
        away = away.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : away.normalize();
        double base = Math.atan2(away.z, away.x);
        List<Vec3d> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double angle = base + (i - (count - 1) / 2.0) * 0.38;
            out.add(center.add(Math.cos(angle) * 5, 2.2 + (i % 2) * 0.5, Math.sin(angle) * 5));
        }
        return out;
    }

    /** One icon frame drawn with particles, facing the foe: a gold outline and, when lit, a burning face inside. */
    private void drawFrame(Vec3d c, Vec3d foe, ParticleEffect edge, boolean lit) {
        Vec3d to = foe.subtract(c).multiply(1, 0, 1);
        to = to.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : to.normalize();
        Vec3d right = new Vec3d(-to.z, 0, to.x);
        double w = 0.75, h = 1.15;
        for (int i = 0; i <= 6; i++) {
            double t = i / 6.0 * 2 - 1;
            point(edge, c.add(right.multiply(t * w)).add(0, h, 0));
            point(edge, c.add(right.multiply(t * w)).add(0, -h, 0));
        }
        for (int i = 0; i <= 9; i++) {
            double t = i / 9.0 * 2 - 1;
            point(edge, c.add(right.multiply(w)).add(0, t * h, 0));
            point(edge, c.add(right.multiply(-w)).add(0, t * h, 0));
        }
        if (lit) particles(ParticleTypes.END_ROD, c.x, c.y + 0.4, c.z, 1, 0.1, 0.1, 0.1, 0);
    }

    private void point(ParticleEffect effect, Vec3d at) { particles(effect, at.x, at.y, at.z, 1, 0, 0, 0, 0); }

    @Override protected void onPhase(int phase) {
        sound(SoundEvents.BLOCK_BELL_USE, 2f, 0.6f);
        particles(ParticleTypes.END_ROD, getX(), getY() + 2, getZ(), 40, 1, 1.5, 1, 0.1);
    }

    @Override protected void onDefeated() {
        for (LivingEntity victim : victims(getPos(), 48)) victim.removeStatusEffect(BossEffects.GAZE);
        if (getWorld() instanceof ServerWorld world)
            for (UUID id : faces) { Entity face = world.getEntity(id); if (face != null) face.discard(); }
        faces.clear();
    }
}
