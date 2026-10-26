package io.github.mobtalk.game;

import io.github.mobtalk.server.ServerConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

/** Villagers riding a lift: now and then one standing on a landing calls the cabin, walks in, presses its floor (the one with its
 * bed when it lives in this house, any other floor otherwise), rides and steps out. One rider at a time per lift; the chance a
 * minute is {@code liftVillagerChance}. Villagers still use the stairs as before: this only adds the lift. */
final class LiftRides {
    private static final int CHECK = 20, GIVE_UP = 20 * 45;

    private final LiftCabin cabin;
    private UUID rider;
    private int from = -1, to = -1, age;
    private Stage stage = Stage.NONE;

    private enum Stage { NONE, WAITING, RIDING, LEAVING }

    LiftRides(LiftCabin cabin) { this.cabin = cabin; }

    void tick() {
        if (!(cabin.getWorld() instanceof ServerWorld world)) return;
        if (stage == Stage.WAITING || stage == Stage.LEAVING) steer(world);
        if (cabin.age % CHECK != 0) return;
        if (stage == Stage.NONE) {
            maybeStart(world);
            return;
        }
        age += CHECK;
        VillagerEntity v = rider == null ? null : world.getEntity(rider) instanceof VillagerEntity e && e.isAlive() ? e : null;
        if (v == null || age > GIVE_UP || v.isSleeping()) {
            stop(v);
            return;
        }
        switch (stage) {
            case WAITING -> {
                if (cabin.contains(v) && cabin.floor() == from) {
                    cabin.press(to, null);
                    v.getBrain().forget(MemoryModuleType.WALK_TARGET);
                    stage = Stage.RIDING;
                } else if (cabin.floor() == from && cabin.atFloor()) {
                    if (!cabin.doorsOpen()) cabin.call(cabin.slabs().get(from));   // the doors shut before it got in: press again
                } else if (!cabin.moving() && cabin.calls() == 0) {
                    cabin.call(cabin.slabs().get(from));   // someone sent it away: call again
                }
            }
            case RIDING -> {
                if (!v.hasVehicle() && !cabin.contains(v)) stop(v);   // missed the ride, or got off
            }
            case LEAVING -> {
                if (!cabin.contains(v) && v.getPos().distanceTo(doorway()) > 1.6 || age > 20 * 12) stop(v);
                else if (cabin.contains(v) && !cabin.doorsOpen() && cabin.atFloor()) cabin.call(cabin.slabs().get(to));
            }
            default -> { }
        }
    }

    /** The cabin reached a floor: our rider gets off there and walks out onto the landing. */
    void arrived(int floor) {
        if (stage == Stage.RIDING && floor == to) {
            stage = Stage.LEAVING;
            age = 0;
        }
    }

    private void maybeStart(ServerWorld world) {
        int chance = ServerConfig.get().liftVillagerChance;
        if (chance <= 0 || cabin.moving() || cabin.calls() != 0) return;
        double perCheck = 1 - Math.pow(1 - Math.min(0.99, chance / 100.0), CHECK / 1200.0);
        if (world.random.nextDouble() >= perCheck) return;
        List<Integer> floors = new ArrayList<>();
        for (int i = 0; i < cabin.floors(); i++) floors.add(i);
        Collections.shuffle(floors, new java.util.Random(world.random.nextLong()));
        for (int f : floors) {
            List<VillagerEntity> near = world.getEntitiesByClass(VillagerEntity.class, landing(f),
                    v -> v.isAlive() && !v.isSleeping() && !v.hasVehicle() && !v.isBaby() && !cabin.contains(v));
            if (near.isEmpty()) continue;
            VillagerEntity v = near.get(world.random.nextInt(near.size()));
            int dest = destination(world, v, f);
            if (dest < 0) continue;
            rider = v.getUuid();
            from = f;
            to = dest;
            age = 0;
            stage = Stage.WAITING;
            world.playSound(null, cabin.layout().button().withY(cabin.slabs().get(f) + 2), Lifts.CLICK, net.minecraft.sound.SoundCategory.BLOCKS, 0.8f, 1f);
            cabin.call(cabin.slabs().get(f));
            v.getLookControl().lookAt(cabin.getX(), cabin.getY() + 1, cabin.getZ());
            return;
        }
    }

    /** The floor with the villager's bed if it is in this house, else a random other floor; -1 to stay. */
    private int destination(ServerWorld world, VillagerEntity v, int here) {
        Optional<GlobalPos> home = v.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HOME);
        if (home.isPresent() && home.get().dimension() == world.getRegistryKey()) {
            BlockPos bed = home.get().pos();
            BlockPos o = cabin.layout().origin();
            if (Math.abs(bed.getX() - o.getX()) < 24 && Math.abs(bed.getZ() - o.getZ()) < 24) {
                int best = 0;
                for (int i = 0; i < cabin.floors(); i++) if (cabin.slabs().get(i) <= bed.getY()) best = i;
                if (best != here) return best;
                if (world.random.nextInt(3) != 0) return -1;   // already home
            }
        }
        if (cabin.floors() < 2) return -1;
        int r = world.random.nextInt(cabin.floors() - 1);
        return r >= here ? r + 1 : r;
    }

    /** The landing in front of the doors of floor f, where waiting villagers stand. */
    private Box landing(int f) {
        Lifts.Layout l = cabin.layout();
        Direction out = l.facing();
        double y = cabin.slabs().get(f) + 1;
        BlockPos a = l.left().offset(out).withY((int) y), b = l.right().offset(out, 4).withY((int) y);
        return new Box(a).union(new Box(b)).expand(1.5, 0, 1.5).stretch(0, 1.5, 0);
    }

    /** The middle of the doorway on the floor the cabin stands at: the doorway parts between the two door columns, so a mob
     * that walks from block centre to block centre would rub along a jamb; riders are steered along this line instead. */
    private Vec3d doorway() {
        Lifts.Layout l = cabin.layout();
        return new Vec3d((l.left().getX() + l.right().getX()) / 2.0 + 0.5, cabin.getY(), (l.left().getZ() + l.right().getZ()) / 2.0 + 0.5);
    }

    /** Every tick while getting in or out: walk straight along the doorway's middle line (the brain's own walking paused). */
    private void steer(ServerWorld world) {
        VillagerEntity v = rider == null ? null : world.getEntity(rider) instanceof VillagerEntity e && e.isAlive() ? e : null;
        if (v == null || !cabin.atFloor() || !cabin.doorsOpen()) return;
        Direction out = cabin.layout().facing();
        Vec3d door = doorway(), outward = Vec3d.of(out.getVector());
        Vec3d rel = v.getPos().subtract(door);
        double along = rel.dotProduct(outward);                       // > 0: on the landing side of the doorway
        double side = rel.subtract(outward.multiply(along)).horizontalLength();
        Vec3d goal;
        if (stage == Stage.WAITING) {
            if (cabin.floor() != from) return;
            goal = along > -0.2 && side > 0.2 ? door.add(outward.multiply(1.2)) : new Vec3d(cabin.getX(), cabin.getY(), cabin.getZ());
        } else {
            if (cabin.floor() != to) return;
            goal = along < 0.2 && side > 0.2 ? door.add(outward.multiply(-0.9)) : door.add(outward.multiply(2.2));
        }
        v.getBrain().forget(MemoryModuleType.WALK_TARGET);
        v.getNavigation().stop();
        v.getMoveControl().moveTo(goal.x, goal.y, goal.z, 0.55);
        v.getLookControl().lookAt(goal.x, v.getEyeY(), goal.z);
    }

    private void stop(Entity v) {
        if (v instanceof VillagerEntity villager && stage == Stage.WAITING) villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        rider = null;
        stage = Stage.NONE;
        from = to = -1;
    }
}
