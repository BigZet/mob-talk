package io.github.mobtalk.client;

import io.github.mobtalk.game.Fences;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Self-test of the Soviet yard fences ({@code -Dmobtalk.selftest.only=fences}, no LLM, no voice): {@code /city fences} lays out a
 * run with a corner of every kind (gates in the metal ones, a double gate in the welded ones) and a low row; the checks read joins,
 * stack parts, posts and the double gate opening together; screenshots go to screenshots/mobtalk-fences-*.png. */
final class SelfTestFences {
    private final SelfTest test;
    private final int px, py, pz;
    private Runnable next = () -> { };
    private final List<Runnable> shots = new ArrayList<>();

    SelfTestFences(SelfTest test, int stageX, int stageY, int stageZ) {
        this.test = test;
        this.px = stageX + 40; this.py = stageY + 20; this.pz = stageZ;
    }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private <T> T onServer(Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }
    private BlockState at(int dx, int dy, int dz) { return onServer(() -> world().getBlockState(new BlockPos(px + dx, py + dy, pz + dz))); }
    /** Row k of the showcase: its fence line runs along x at this z offset. */
    private static int row(int k) { return 2 + k * 6; }

    void run(Runnable then) {
        next = then;
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.owner.client.options.hudHidden = true;
        test.log("fence stages: the showcase, joins and posts, the double gate, screenshots");
        test.command("time set 6000"); test.command("weather clear"); test.command("gamerule doDaylightCycle false");
        test.command("gamemode spectator " + name());
        for (int y = 0; y < 12; y += 6)
            test.command("fill " + (px - 15) + " " + (py + y) + " " + (pz - 25) + " " + (px + 15) + " " + (py + y + 5) + " " + (pz + 60) + " minecraft:air");
        test.command("fill " + (px - 15) + " " + (py - 1) + " " + (pz - 25) + " " + (px + 15) + " " + (py - 1) + " " + (pz + 60) + " minecraft:grass_block");
        test.command("tp " + name() + " " + px + " " + py + " " + pz + " 0 0");
        test.await("pause: the stage chunks load", 3, () -> false, () -> {
            test.command("execute positioned " + px + " " + py + " " + pz + " run city fences");
            test.await("pause: the fences are set", 2, () -> false, this::checks);
        });
    }

    private void checks() {
        int z0 = row(0);
        BlockState end = at(0, 0, z0), corner = at(9, 0, z0), top = at(9, 1, z0);
        test.check(end.isOf(Fences.BLOCKS.get("chainlink_fence_galv")), "the chain-link run stands (" + end + ")");
        test.check(end.get(Fences.POST) && end.get(Fences.PART) == Fences.Part.BOTTOM, "its end has a post and is a stack bottom");
        test.check(corner.get(Fences.POST) && corner.get(Fences.FenceLineBlock.WEST) && corner.get(Fences.FenceLineBlock.SOUTH), "the corner joins west and south with a post");
        test.check(top.get(Fences.PART) == Fences.Part.TOP, "the upper block is a stack top");
        test.check(at(3, 0, z0).getBlock() instanceof Fences.GateBlock, "the wicket hangs at x 3");
        test.check(at(2, 0, z0).get(Fences.POST) && at(4, 0, z0).get(Fences.POST) && at(4, 0, z0).get(Fences.FenceLineBlock.WEST),
                "posts beside the wicket, the fence joins it");
        int plain = 0, wrong = 0;
        for (int x = 5; x <= 8; x++) {
            BlockState s = at(x, 0, z0);
            boolean want = Math.floorMod(px + x, 3) == 0;
            if (!want) plain++;
            if (s.get(Fences.POST) != want) wrong++;
        }
        int bare = plain;
        test.check(wrong == 0 && bare > 0, "posts every third block along the straight run (" + bare + " cells without one, " + wrong + " wrong)");
        BlockState po2 = at(5, 0, row(6));
        test.check(po2.isOf(Fences.BLOCKS.get("concrete_fence")) && po2.get(Fences.POST) == (Math.floorMod(px + 5, 4) == 0), "PO-2 posts every fourth block");
        BlockState low = at(2, 0, row(Fences.FENCES.size()));
        test.check(low.get(Fences.PART) == Fences.Part.SINGLE, "the low fence is one block high");

        int z2 = row(2);
        shot("mobtalk-fences-0-overview.png", px - 5, py + 9, pz - 5, -40, 35);
        for (int k = 0; k < Fences.FENCES.size(); k++)
            shot("mobtalk-fences-" + (k + 1) + "-" + Fences.FENCES.get(k) + ".png", px + 4.5, py, pz + row(k) - 3.6, 0, 12);
        shot("mobtalk-fences-low.png", px + 4.5, py, pz + row(Fences.FENCES.size()) - 3.6, 0, 20);
        look("mobtalk-fences-9-concrete-corner.png", px + 6.3, py + 0.4, pz + row(6) + 2.7, px + 9.5, py + 1.2, pz + row(6) + 0.5);
        look("mobtalk-fences-9b-po2-along.png", px - 2.5, py + 0.3, pz + row(6) - 2.2, px + 9, py + 1.2, pz + row(6));
        look("mobtalk-fences-10-mesh-close.png", px + 6.5, py + 0.2, pz + row(0) - 1.6, px + 6.5, py + 1.2, pz + row(0) + 0.5);
        look("mobtalk-fences-11-lattice-close.png", px + 6.5, py + 0.2, pz + row(3) - 1.6, px + 6.5, py + 1.2, pz + row(3) + 0.5);
        shots.add(() -> {
            onServer(() -> {
                BlockPos leaf = new BlockPos(px + 3, py, pz + z2);
                BlockState s = world().getBlockState(leaf);
                ((DoorBlock) s.getBlock()).setOpen(null, world(), s, leaf, true);
                return true;
            });
            test.await("pause: the gate swings", 1, () -> false, () -> {
                test.check(at(3, 0, z2).get(DoorBlock.OPEN) && at(2, 0, z2).get(DoorBlock.OPEN), "the double gate opens both leaves together");
                nextShot();
            });
        });
        look("mobtalk-fences-12-gate-open.png", px + 6.5, py + 0.3, pz + z2 + 3.5, px + 2.5, py + 1, pz + z2 - 0.5);
        look("mobtalk-fences-13-gate-open-out.png", px + 1.5, py + 0.3, pz + z2 - 3.5, px + 3, py + 1, pz + z2);
        shot("mobtalk-fences-14-far.png", px - 14, py + 3, pz + 22, -90, 8);
        shots.add(this::finish);
        nextShot();
    }

    private void nextShot() {
        if (!shots.isEmpty()) shots.remove(0).run();
    }

    private void look(String file, double x, double y, double z, double tx, double ty, double tz) {
        double dx = tx - x, dy = ty - (y + 1.62), dz = tz - z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        shot(file, x, y, z, yaw, pitch);
    }

    private void shot(String file, double x, double y, double z, float yaw, float pitch) {
        shots.add(() -> {
            test.command("tp " + name() + " " + x + " " + y + " " + z + " " + yaw + " " + pitch);
            test.await("pause: the camera settles", 2, () -> false, () -> {
                ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
                test.await("pause: after the screenshot", 1, () -> false, this::nextShot);
            });
        });
    }

    private void finish() {
        test.owner.client.options.hudHidden = false;
        test.command("gamerule doDaylightCycle true");
        test.await("pause: fence stage done", 1, () -> false, next);
    }
}
