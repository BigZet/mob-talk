package io.github.divineblock.boss;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** A boss's attacks in flight, ticked on the server with the boss: slow orbs, expanding rings on the ground, marked zones that
 * strike after a warning, and beams that show their line before they burn. Everything is drawn with particles and every
 * harm goes through {@link SacredBoss#hurt}, so it is capped and scaled. Cleared when the boss dies or unloads. */
public final class Hazards {
    private interface Hazard { boolean tick(ServerWorld world); }

    private final SacredBoss boss;
    private final List<Hazard> live = new ArrayList<>();

    Hazards(SacredBoss boss) { this.boss = boss; }

    void tick(ServerWorld world) {
        if (live.isEmpty()) return;
        List<Hazard> now = new ArrayList<>(live);
        live.clear();
        for (Hazard hazard : now) {
            boolean keep;
            try { keep = hazard.tick(world); } catch (RuntimeException failure) { keep = false; }
            if (keep) live.add(hazard);
        }
    }
    void clear() { live.clear(); }
    public int count() { return live.size(); }

    /** A slow orb flying from {@code from} with {@code velocity} (blocks per tick) for up to {@code life} ticks; it bursts on
     * the first foe it touches or on a solid block. */
    public void orb(Vec3d from, Vec3d velocity, int life, double radius, ParticleEffect trail, Consumer<LivingEntity> hit) {
        live.add(new Hazard() {
            Vec3d pos = from; int age;
            @Override public boolean tick(ServerWorld world) {
                pos = pos.add(velocity);
                boss.particles(trail, pos.x, pos.y, pos.z, 3, radius * 0.3, radius * 0.3, radius * 0.3, 0.01);
                for (LivingEntity victim : boss.victims(pos, radius + 1.5))
                    if (victim.getBoundingBox().expand(radius).contains(pos)) { hit.accept(victim); return false; }
                if (!world.getBlockState(BlockPos.ofFloored(pos)).getCollisionShape(world, BlockPos.ofFloored(pos)).isEmpty()) return false;
                return ++age < life;
            }
        });
    }

    /** A ring on the ground spreading from {@code center} at {@code speed} blocks per tick up to {@code maxRadius}. It is only
     * {@code height} blocks tall: a jump at the right moment clears it. Each foe is struck once per ring. */
    public void ring(Vec3d center, double speed, double maxRadius, double height, ParticleEffect edge, Consumer<LivingEntity> hit) {
        live.add(new Hazard() {
            double radius = 0.5; final Set<LivingEntity> struck = new HashSet<>();
            @Override public boolean tick(ServerWorld world) {
                radius += speed;
                int points = (int) Math.min(160, 6 * radius + 8);
                for (int i = 0; i < points; i++) {
                    double angle = Math.PI * 2 * i / points;
                    boss.particles(edge, center.x + Math.cos(angle) * radius, center.y + 0.2, center.z + Math.sin(angle) * radius, 1, 0, 0.05, 0, 0);
                }
                for (LivingEntity victim : boss.victims(center, radius + 1)) {
                    double flat = Math.hypot(victim.getX() - center.x, victim.getZ() - center.z);
                    double above = victim.getY() - center.y;
                    if (Math.abs(flat - radius) < 0.9 && above < height && above > -2 && struck.add(victim)) hit.accept(victim);
                }
                return radius < maxRadius;
            }
        });
    }

    /** A circle marked on the ground for {@code warn} ticks, then {@code strike} runs with the circle's centre; every foe inside
     * the circle at that moment gets {@code hit}. */
    public void zone(Vec3d center, double radius, int warn, ParticleEffect mark, Consumer<Vec3d> strike, Consumer<LivingEntity> hit) {
        live.add(new Hazard() {
            int left = warn;
            @Override public boolean tick(ServerWorld world) {
                if (left % 4 == 0) {
                    int points = (int) Math.max(8, radius * 8);
                    for (int i = 0; i < points; i++) {
                        double angle = Math.PI * 2 * i / points;
                        boss.particles(mark, center.x + Math.cos(angle) * radius, center.y + 0.15, center.z + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
                    }
                    if (left < 20) boss.particles(mark, center.x, center.y + 0.2, center.z, 4, radius * 0.4, 0.05, radius * 0.4, 0);
                }
                if (--left > 0) return true;
                strike.accept(center);
                Box box = new Box(center.x - radius, center.y - 1, center.z - radius, center.x + radius, center.y + 4, center.z + radius);
                for (LivingEntity victim : boss.victims(center, radius + 2))
                    if (victim.getBoundingBox().intersects(box) && Math.hypot(victim.getX() - center.x, victim.getZ() - center.z) <= radius + 0.4) hit.accept(victim);
                return false;
            }
        });
    }

    /** A beam from {@code from} to {@code to}: a thin dotted line for {@code warn} ticks, then a dense burning line for
     * {@code burn} ticks that hurts every foe within {@code width} of it once every ten ticks. */
    public void beam(Vec3d from, Vec3d to, int warn, int burn, double width, ParticleEffect sight, ParticleEffect fire, Consumer<LivingEntity> hit) {
        live.add(new Hazard() {
            int age;
            @Override public boolean tick(ServerWorld world) {
                Vec3d line = to.subtract(from);
                double length = line.length();
                boolean burning = age >= warn;
                int steps = (int) (length * (burning ? 3 : 1));
                if (burning || age % 3 == 0)
                    for (int i = 0; i <= steps; i++) {
                        Vec3d at = from.add(line.multiply(i / (double) Math.max(1, steps)));
                        boss.particles(burning ? fire : sight, at.x, at.y, at.z, 1, burning ? width * 0.2 : 0, burning ? width * 0.2 : 0, burning ? width * 0.2 : 0, 0);
                    }
                if (burning && (age - warn) % 10 == 0)
                    for (LivingEntity victim : boss.victims(from.add(line.multiply(0.5)), length / 2 + 2))
                        if (distanceToSegment(victim.getBoundingBox().getCenter(), from, to) <= width + victim.getWidth() / 2) hit.accept(victim);
                return ++age < warn + burn;
            }
        });
    }

    /** Runs {@code action} once after {@code ticks}, as part of the boss's attacks (cleared with them). */
    public void later(int ticks, Runnable action) {
        live.add(new Hazard() {
            int left = ticks;
            @Override public boolean tick(ServerWorld world) { if (--left > 0) return true; action.run(); return false; }
        });
    }

    /** Runs {@code step} {@code times} times, every {@code every} ticks (the first at once), with the step's index. */
    public void repeat(int every, int times, java.util.function.IntConsumer step) {
        live.add(new Hazard() {
            int age, done;
            @Override public boolean tick(ServerWorld world) {
                if (age++ % Math.max(1, every) == 0) step.accept(done++);
                return done < times;
            }
        });
    }

    static double distanceToSegment(Vec3d point, Vec3d a, Vec3d b) {
        Vec3d ab = b.subtract(a);
        double t = ab.lengthSquared() < 1e-6 ? 0 : Math.max(0, Math.min(1, point.subtract(a).dotProduct(ab) / ab.lengthSquared()));
        return point.distanceTo(a.add(ab.multiply(t)));
    }
}
