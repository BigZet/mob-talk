package io.github.mobtalk.client;

import io.github.mobtalk.game.RoadCartEntity;
import io.github.mobtalk.game.Roads;
import java.util.ArrayList;
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
 * laid end to end, nothing stands in it, no tree is left in the strip beside it, each trip takes shape with its horse, cart and
 * people, moves along at a walk in its lane and keeps the cart behind the horse. Screenshots: the road from above and along it, a
 * bridge or a tunnel if the road has one, each kind of trip from the side and close up, the lamps at night, a village's junction
 * and trail. Then {@code -Dmobtalk.selftest.roads=x1,z1,x2,z2;…} names further lines to lay and check (through mountains, a swamp,
 * the badlands), each photographed. The player watches as a spectator (never in the traffic's way). No LLM, no voice. */
final class SelfTestRoads {
    private static final Pattern BUILT = Pattern.compile("дорога (\\d+):");
    private final SelfTest test;
    private final int sx, sz;
    private Runnable next = () -> { };
    private int road = -1, attempt, probe;
    private volatile String reply;
    private final List<int[]> probes = new ArrayList<>();

    SelfTestRoads(SelfTest test, int stageX, int stageY, int stageZ) { this.test = test; this.sx = stageX; this.sz = stageZ; }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private <T> T onServer(java.util.function.Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }

    void run(Runnable then) {
        next = then;
        test.log("roads stages: a road planned and laid, its carts, wagon and horseman, the lamps at night");
        test.bridge().config.villageRoads = true; test.bridge().config.roadLamps = true; test.bridge().config.roadTraffic = 3;
        test.bridge().config.roadBypass = true; test.bridge().config.roadTreeStrip = 4;
        String lines = System.getProperty("mobtalk.selftest.roads", "");
        for (String line : lines.split(";")) {
            String[] parts = line.trim().split("[ ,]+");
            if (parts.length != 4) continue;
            try { probes.add(new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]) }); }
            catch (NumberFormatException wrong) { test.log("probe line ignored: " + line); }
        }
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

    /** The road is whole: laid end to end, lamps sound, nothing left standing in it or hanging over it, no tree in the strip. */
    private void whole(int road) {
        int[] laid = onServer(() -> Roads.laid(world(), road));
        test.check(laid[1] > 0 && laid[0] == laid[1], "road " + road + " is laid end to end (" + laid[0] + "/" + laid[1] + " chunks)");
        int[] lamps = onServer(() -> Roads.lampFaults(world(), road));
        test.check(lamps[0] > 0 && lamps[1] == 0, "road " + road + ": every lamp's arm joins its post (" + lamps[0] + " lamps, " + lamps[1] + " apart)");
        int hanging = onServer(() -> Roads.hangingLogs(world(), road));
        test.check(hanging == 0, "road " + road + ": no log hangs in the air by the road (" + hanging + ")");
        int[] obstacles = onServer(() -> Roads.obstacles(world(), road));
        test.check(obstacles[0] == 0, "road " + road + ": nothing stands in the road (" + obstacles[0] + " columns, first at " + obstacles[1] + " " + obstacles[2] + " " + obstacles[3] + ")");
        int[] trees = onServer(() -> Roads.stripTrees(world(), road, test.bridge().config.roadTreeStrip));
        test.check(trees[0] == 0, "road " + road + ": no tree is left in the strip beside the road (" + trees[0] + ", first at " + trees[1] + " " + trees[2] + " " + trees[3] + ")");
    }

    /** The road itself: from above, along it from the ground, and its first bridge or tunnel. */
    private void views() {
        whole(road);
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
                // A moment first: the cart turns to its horse on its first ticks (on a lagging server the check came before them).
                test.await("pause: the " + kind + " settles", 1, () -> false, () -> {
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
                    // The nearest one: another trip's horse may be passing by (it was taken once, and the cart was said to face away).
                    AbstractHorseEntity horse = horses.stream().min(java.util.Comparator.comparingDouble(h -> h.squaredDistanceTo(cart))).get();
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
            shot("mobtalk-roads-night-above.png", 3, () -> {
                test.command("time set 6000");
                village(2, () -> village(1, this::probes));
            });
        });
    }

    /** Where the roads come to a village of the world (one that two or more roads come to, {@code roads} 2, or only one): the
     * village's chunks are held until its junction and trail (or, for roads laid into it, its streets and station) are done, then
     * it is photographed from the road and from above. Skipped when the world has no such village. */
    private void village(int roads, Runnable then) {
        List<String> keys = onServer(() -> Roads.villagesWith(world(), roads));
        if (keys.isEmpty()) { test.log("no village with " + roads + " road(s): its views skipped"); then.run(); return; }
        String key = keys.get(0);
        onServer(() -> { Roads.holdVillage(world(), key, true); return null; });
        test.await("the village " + key + " is done", 180, () -> onServer(() -> Roads.villageReport(world(), key)) != null, () -> {
            String report = onServer(() -> Roads.villageReport(world(), key));
            String kind;
            if (report.contains("развязка")) {
                kind = roads >= 2 ? "junction" : "hub";
                Matcher m = Pattern.compile("тропа (\\d+) блоков").matcher(report);
                int trail = m.find() ? Integer.parseInt(m.group(1)) : 0;
                test.check(trail > 0 && !report.contains("("), "the trail from the junction reaches a street of " + key + ": " + report);
                test.check(report.contains("навес"), "the junction of " + key + " has its shelter");
                test.check(!report.contains("фонарей 0"), "the junction of " + key + " has lamps");
                int[] hub = onServer(() -> Roads.hub(world(), key));
                int[] box = onServer(() -> Roads.villageBox(world(), key));
                boolean outside = hub != null && box != null && (hub[0] < box[0] || hub[0] > box[3] || hub[2] < box[2] || hub[2] > box[5]);
                test.check(outside, "the junction of " + key + " stands outside the village (" + (hub == null ? "none" : hub[0] + " " + hub[2]) + ")");
                if (roads >= 2) test.check(onServer(() -> Roads.onward(world(), key)), "a trip at the junction of " + key + " has a road to drive on along");
            } else {
                kind = roads >= 2 ? "through" : "station";
                test.check(report.contains("подъездов " + (roads >= 2 ? "" : "1")), "the roads reach the streets of " + key + ": " + report);
                if (roads == 1) test.check(report.contains("станция"), "a station where the only road ends at " + key);
                else test.check(!report.contains("вымощено улиц 0"), "the streets between the entries are paved at " + key);
            }
            double[] e = onServer(() -> Roads.villageEntry(world(), key));
            Vec3d at = new Vec3d(e[0], e[1], e[2]), in = new Vec3d(e[3], 0, e[4]);
            look(at.subtract(in.multiply(16)).add(in.z * 5, 4, -in.x * 5), at.add(in.multiply(6)));
            shot("mobtalk-roads-" + kind + "-entry.png", 6, () -> {
                look(at.subtract(in.multiply(10)).add(0, 22, 0), at.add(in.multiply(6)));
                shot("mobtalk-roads-" + kind + "-above.png", 3, () -> {
                    look(at.add(in.multiply(2)).add(0, 2.2, 0), at.add(in.multiply(24)).add(0, 1, 0));
                    shot("mobtalk-roads-" + kind + "-trail.png", 3, () -> {
                        onServer(() -> { Roads.holdVillage(world(), key, false); return null; });
                        then.run();
                    });
                });
            });
        });
    }

    /** The further lines asked for: each planned, laid and checked as the first road was, and photographed from above and along. */
    private void probes() {
        if (probe >= probes.size()) { finish(); return; }
        int[] line = probes.get(probe++);
        reply = null;
        test.server().execute(() -> Roads.build(world(), line[0], line[1], line[2], line[3], text -> reply = text));
        test.await("probe " + probe + " is planned", 120, () -> reply != null, () -> {
            test.log("probe " + probe + ": " + reply);
            Matcher m = BUILT.matcher(reply == null ? "" : reply);
            // The planner may refuse a line (too much bridge, too long): the probes cross whatever land they are given, so a refusal
            // is noted, not a failure; an error in the planning is one.
            if (!m.find()) { if (reply != null && reply.contains("не будет") && !reply.contains("ошибка")) test.log("probe " + probe + " refused: " + reply); else test.fail("probe " + probe + " could not be planned: " + reply); probes(); return; }
            int id = Integer.parseInt(m.group(1));
            onServer(() -> Roads.load(world(), id));
            test.await("probe " + probe + " is laid end to end", 420, () -> {
                int[] laid = onServer(() -> Roads.laid(world(), id));
                return laid[1] > 0 && laid[0] == laid[1];
            }, () -> {
                whole(id);
                int n = onServer(() -> Roads.length(world(), id));
                int[] firsts = onServer(() -> Roads.firsts(world(), id));
                // The most telling spot: the first tunnel or bridge if there is one, else the middle.
                int spot = firsts[1] >= 0 ? firsts[1] : firsts[0] >= 0 ? firsts[0] : n / 2;
                Vec3d middle = onServer(() -> Roads.point(world(), id, spot)), ahead = onServer(() -> Roads.point(world(), id, Math.min(n - 1, spot + 30)));
                Vec3d before = onServer(() -> Roads.point(world(), id, Math.max(0, spot - 16)));
                int number = probe;
                look(middle.add(-18, 34, -18), middle.add(8, 0, 8));
                shot("mobtalk-roads-probe" + number + "-above.png", 6, () -> {
                    look(before.add(0, 2.2, 0), ahead.add(0, 1, 0));
                    shot("mobtalk-roads-probe" + number + "-along.png", 4, this::probes);
                });
            });
        });
    }

    private void finish() {
        test.command("time set 6000");
        test.command("gamemode survival " + name());
        test.command("gamerule doDaylightCycle true"); test.command("gamerule doMobSpawning true");
        test.await("pause: roads stage done", 2, () -> false, next);
    }
}
