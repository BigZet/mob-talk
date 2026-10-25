package io.github.mobtalk.client;

import io.github.mobtalk.game.RoadCartEntity;
import io.github.mobtalk.game.Roads;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Roads between villages ({@code -Only roads}): a road planned across the land beside the stage and laid, then its traffic — the
 * covered wagon with villagers and its escort, the cart, the horseman — brought into view one after another. Checks the road is
 * laid end to end, each trip takes shape with its horse, cart and people, moves along at a walk in its lane and keeps the cart
 * behind the horse. Screenshots: the road from above and along it, a bridge or a tunnel if the road has one, each kind of trip
 * from the side and close up, the lamps at night. The player watches as a spectator (never in the traffic's way). No LLM, no voice. */
final class SelfTestRoads {
    private static final Pattern BUILT = Pattern.compile("дорога (\\d+):");
    private final SelfTest test;
    private final int sx, sz;
    private Runnable next = () -> { };
    private int road = -1, attempt;
    private volatile String reply;

    SelfTestRoads(SelfTest test, int stageX, int stageY, int stageZ) { this.test = test; this.sx = stageX; this.sz = stageZ; }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private <T> T onServer(java.util.function.Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }

    void run(Runnable then) {
        next = then;
        test.log("roads stages: a road planned and laid, its carts, wagon and horseman, the lamps at night");
        test.bridge().config.villageRoads = true; test.bridge().config.roadLamps = true; test.bridge().config.roadTraffic = 3;
        test.command("time set 6000"); test.command("weather clear");
        test.command("gamerule doDaylightCycle false"); test.command("gamerule doMobSpawning false");
        test.command("gamemode spectator " + name());
        plan();
    }

    /** A road of about 260 blocks across the land: tried along x, then along z, then farther off. */
    private void plan() {
        int[][] lines = { { sx - 130, sz + 70, sx + 130, sz + 90 }, { sx + 70, sz - 130, sx + 90, sz + 130 }, { sx - 130, sz - 300, sx + 130, sz - 280 } };
        if (attempt >= lines.length) { test.fail("no road could be planned near the stage: " + reply); finish(); return; }
        int[] line = lines[attempt++];
        reply = null;
        test.server().execute(() -> Roads.build(world(), line[0], line[1], line[2], line[3], text -> reply = text));
        test.await("the road is planned", 90, () -> reply != null, () -> {
            test.log("plan: " + reply);
            Matcher m = BUILT.matcher(reply == null ? "" : reply);
            if (!m.find()) { plan(); return; }
            road = Integer.parseInt(m.group(1));
            test.check(true, "road " + road + " planned: " + reply);
            onServer(() -> Roads.load(world(), road));
            int[] tick = { 0 };
            test.await("the road is laid end to end", 240, () -> {
                int[] laid = onServer(() -> Roads.laid(world(), road));
                if (++tick[0] % 200 == 0) test.log("laid " + laid[0] + "/" + laid[1]);
                return laid[1] > 0 && laid[0] == laid[1];
            }, this::views);
        });
    }

    private Vec3d at(int index) { return onServer(() -> Roads.point(world(), road, index)); }
    private int length() { return onServer(() -> Roads.length(world(), road)); }

    /** Puts the camera at {@code from} (or above it, out of the leaves, where it sees {@code target}) looking at {@code target}. */
    private void look(Vec3d wanted, Vec3d target) {
        Vec3d from = onServer(() -> clear(wanted, target));
        double dx = target.x - from.x, dy = target.y - from.y, dz = target.z - from.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        test.command(String.format(java.util.Locale.ROOT, "tp %s %.2f %.2f %.2f %.1f %.1f", name(), from.x, from.y, from.z, yaw, pitch));
    }

    /** The first spot going up from {@code eye} (then on the other side of the target) in the open air with nothing solid or leafy
     * between it and the target. */
    private Vec3d clear(Vec3d eye, Vec3d target) {
        Vec3d mirrored = new Vec3d(2 * target.x - eye.x, eye.y, 2 * target.z - eye.z);
        for (Vec3d base : List.of(eye, mirrored))
            for (int dy = 0; dy <= 24; dy += 2) {
                Vec3d at = base.add(0, dy, 0);
                if (!open(BlockPos.ofFloored(at)) || !open(BlockPos.ofFloored(at).up())) continue;
                if (sees(at, target)) return at;
            }
        return eye.add(0, 12, 0);
    }

    private boolean open(BlockPos pos) {
        BlockState state = world().getBlockState(pos);
        return state.getCollisionShape(world(), pos).isEmpty() && !(state.getBlock() instanceof LeavesBlock);
    }

    private boolean sees(Vec3d from, Vec3d to) {
        Vec3d step = to.subtract(from);
        double length = step.length();
        for (double d = 0.5; d < length - 1.5; d += 0.4) {
            BlockPos pos = BlockPos.ofFloored(from.add(step.multiply(d / length)));
            BlockState state = world().getBlockState(pos);
            if (state.isOpaqueFullCube(world(), pos) || state.getBlock() instanceof LeavesBlock) return false;
        }
        return true;
    }

    private void shot(String file, int settle, Runnable then) {
        test.await("pause: the camera settles", settle, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, then);
        });
    }

    /** The road itself: from above, along it from the ground, and its first bridge or tunnel. */
    private void views() {
        int[] laid = onServer(() -> Roads.laid(world(), road));
        test.check(laid[1] > 0 && laid[0] == laid[1], "road " + road + " is laid end to end (" + laid[0] + "/" + laid[1] + " chunks)");
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        int n = length();
        Vec3d middle = at(n / 2), ahead = at(n / 2 + 40);
        look(middle.add(-18, 34, -18), middle.add(8, 0, 8));
        shot("mobtalk-roads-above.png", 6, () -> {
            Vec3d from = at(n / 2 - 14);
            look(from.add(0, 2.2, 0), ahead.add(0, 1, 0));
            shot("mobtalk-roads-along.png", 4, () -> {
                int[] firsts = onServer(() -> Roads.firsts(world(), road));
                int special = firsts[0] >= 0 ? firsts[0] : firsts[1];
                test.log("first bridge " + firsts[0] + ", first tunnel " + firsts[1]);
                if (special < 0) { traffic("wagon", 0); return; }
                Vec3d spot = at(special + 6), before = at(Math.max(0, special - 10));
                look(before.add(0, firsts[0] >= 0 ? 6 : 2.2, 0).add(spot.subtract(before).crossProduct(new Vec3d(0, 1, 0)).normalize().multiply(firsts[0] >= 0 ? 9 : 0)), spot.add(0, 1, 0));
                shot(firsts[0] >= 0 ? "mobtalk-roads-bridge.png" : "mobtalk-roads-tunnel.png", 4, () -> traffic("wagon", 0));
            });
        });
    }

    private static final List<String> KINDS = List.of("wagon", "cart", "rider");

    /** One kind of trip brought to the road's middle: it takes shape, moves along its lane, and is photographed from the side and close. */
    private void traffic(String kind, int order) {
        int n = length();
        Vec3d spot = at(n / 2 + order * 25);
        test.command(String.format(java.util.Locale.ROOT, "tp %s %.1f %.1f %.1f", name(), spot.x, spot.y + 12, spot.z));
        test.await("pause: the camera comes over the road", 2, () -> false, () -> {
            String said = onServer(() -> Roads.bring(world(), road, spot.x, spot.z, kind));
            test.log(kind + ": " + said);
            test.await("the " + kind + " takes shape", 15, () -> lead(kind) != null, () -> {
                Entity lead = lead(kind);
                if (lead == null) { nextKind(kind, order); return; }
                inspect(kind, lead);
                Vec3d first = onServer(lead::getPos);
                long start = onServer(() -> world().getTime());
                test.await("pause: the " + kind + " drives on", 3, () -> false, () -> {
                    Vec3d then = onServer(lead::getPos);
                    long ticks = Math.max(1, onServer(() -> world().getTime()) - start);
                    // Per server tick, so a slow test machine does not count as a fast cart: a walk is 1–4 blocks a second.
                    double moved = Math.hypot(then.x - first.x, then.z - first.z), perSecond = moved * 20 / ticks;
                    test.check(perSecond > 1 && perSecond < 4, kind + " moves along at a walk (" + String.format(java.util.Locale.ROOT, "%.1f blocks in %d ticks, %.1f a second", moved, ticks, perSecond) + ")");
                    if (kind.equals("rider")) seat(lead);
                    side(kind, lead, order);
                });
            });
        });
    }

    private void side(String kind, Entity lead, int order) {
        Vec3d pos = onServer(lead::getPos);
        float yaw = onServer(lead::getYaw);
        double rad = Math.toRadians(yaw);
        Vec3d forward = new Vec3d(-Math.sin(rad), 0, Math.cos(rad)), right = new Vec3d(-forward.z, 0, forward.x);
        // Photographed from over the road itself (the trees beside it are cleared), the oncoming lane a little ahead: the trip drives
        // into the middle of the picture while the camera settles, then a close look from beside it.
        look(pos.add(forward.multiply(9)).add(right.multiply(-3.4)).add(0, 3.2, 0), pos.add(forward.multiply(2)).add(0, 1, 0));
        shot("mobtalk-roads-" + kind + "-side.png", 2, () -> {
            Vec3d now = onServer(lead::getPos);
            boolean wagon = kind.equals("wagon");
            look(now.add(forward.multiply(1.0)).add(right.multiply(wagon ? -4.4 : -3.8)).add(0, wagon ? 1.5 : 2.0, 0), now.add(0, 1.2, 0));
            shot("mobtalk-roads-" + kind + "-close.png", 1, () -> nextKind(kind, order));
        });
    }

    private void nextKind(String kind, int order) {
        int index = KINDS.indexOf(kind);
        if (index + 1 < KINDS.size()) traffic(KINDS.get(index + 1), order + 1);
        else night();
    }

    /** The cart (or wagon) of that kind near the player, or the horseman's horse. */
    private Entity lead(String kind) {
        return onServer(() -> {
            Box box = test.serverPlayer().getBoundingBox().expand(110, 60, 110);
            if (kind.equals("rider")) {
                List<AbstractHorseEntity> horses = world().getEntitiesByClass(AbstractHorseEntity.class, box,
                        h -> h.getCommandTags().contains("mobtalk_road") && h.isSaddled() && h.getFirstPassenger() instanceof VillagerEntity);
                return horses.stream().filter(h -> world().getEntitiesByClass(RoadCartEntity.class, h.getBoundingBox().expand(8), c -> true).isEmpty()).findFirst().orElse(null);
            }
            boolean wagon = kind.equals("wagon");
            return world().getEntitiesByClass(RoadCartEntity.class, box, c -> c.wagon() == wagon).stream().findFirst().orElse(null);
        });
    }

    private void inspect(String kind, Entity lead) {
        onServer(() -> {
            if (lead instanceof RoadCartEntity cart) {
                List<Entity> people = cart.getPassengerList();
                test.check(!people.isEmpty() && people.stream().allMatch(p -> p instanceof VillagerEntity), kind + ": villagers sit in it (" + people.size() + ")");
                List<AbstractHorseEntity> horses = world().getEntitiesByClass(AbstractHorseEntity.class, cart.getBoundingBox().expand(5),
                        h -> h.getCommandTags().contains("mobtalk_road") && !h.isSaddled());
                test.check(!horses.isEmpty(), kind + ": a horse draws it");
                if (!horses.isEmpty()) {
                    AbstractHorseEntity horse = horses.getFirst();
                    double gap = Math.hypot(horse.getX() - cart.getX(), horse.getZ() - cart.getZ());
                    test.check(Math.abs(gap - cart.reach()) < 0.6, kind + ": the horse is a shaft's length ahead (" + String.format(java.util.Locale.ROOT, "%.2f", gap) + ")");
                    double rad = Math.toRadians(cart.getYaw());
                    double ahead = (horse.getX() - cart.getX()) * -Math.sin(rad) + (horse.getZ() - cart.getZ()) * Math.cos(rad);
                    test.check(ahead > 0, kind + ": the cart faces its horse");
                }
            } else test.check(lead.getFirstPassenger() instanceof VillagerEntity, "the horseman sits his horse");
            return null;
        });
    }

    /** The horseman sits in the saddle as a player would (Dmitriy 2026-10-25: he floated over it) and faces with the horse, as the
     * client shows him. The same check as village defence's cavalry (SelfTestGuards). */
    private void seat(Entity horse) {
        Entity rider = onServer(horse::getFirstPassenger);
        if (rider == null) return;
        var world = test.owner.client.world;
        if (!(world.getEntityById(rider.getId()) instanceof VillagerEntity r) || !(world.getEntityById(horse.getId()) instanceof net.minecraft.entity.LivingEntity h)) {
            test.fail("the horseman is not seen on the client");
            return;
        }
        double lift = r.getY() - h.getY(), player = h.getPassengerRidingPos(r).y - h.getY() - 0.6;
        float turn = MathHelper.wrapDegrees(r.bodyYaw - h.bodyYaw);
        test.check(Math.abs(lift - player) < 0.2, String.format(java.util.Locale.ROOT, "the horseman sits in the saddle (feet %.2f above the horse's, a player's %.2f)", lift, player));
        test.check(Math.abs(turn) < 20, String.format(java.util.Locale.ROOT, "the horseman faces with the horse (%.0f°)", turn));
    }

    /** The lamps at night: along the road from the ground, and from above. */
    private void night() {
        test.command("time set 18000");
        int n = length();
        Vec3d from = at(n / 2 - 14), ahead = at(n / 2 + 40), middle = at(n / 2);
        look(from.add(0, 2.2, 0), ahead.add(0, 1, 0));
        shot("mobtalk-roads-night.png", 4, () -> {
            look(middle.add(-14, 16, -14), middle.add(10, 0, 10));
            shot("mobtalk-roads-night-above.png", 3, this::finish);
        });
    }

    private void finish() {
        test.command("time set 6000");
        test.command("gamemode survival " + name());
        test.command("gamerule doDaylightCycle true"); test.command("gamerule doMobSpawning true");
        test.await("pause: roads stage done", 2, () -> false, next);
    }
}
