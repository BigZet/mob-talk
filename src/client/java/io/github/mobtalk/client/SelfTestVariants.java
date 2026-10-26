package io.github.mobtalk.client;

import io.github.mobtalk.game.FurnitureBlocks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** {@code -Dmobtalk.selftest.only=variants}: for every base piece that has colour variants, one row "base + all its variants" on a
 * bare floor, shot from eight angles (front, back, left, right, above, close, far, night). Frames go to {@code screenshots/var-*.png}. */
final class SelfTestVariants {
    private final SelfTest test;
    private final int x0, y0, z0;
    private int failed;

    SelfTestVariants(SelfTest test, int stageX, int stageY, int stageZ) { this.test = test; this.x0 = stageX + 40; this.y0 = stageY; this.z0 = stageZ + 60; }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private String at(double dx, double dy, double dz) { return (x0 + dx) + " " + (y0 + dy) + " " + (z0 + dz); }
    private String bp(int dx, int dy, int dz) { return (x0 + dx) + " " + (y0 + dy) + " " + (z0 + dz); }
    private BlockPos pos(int dx, int dy, int dz) { return new BlockPos(x0 + dx, y0 + dy, z0 + dz); }

    void run(Runnable then) {
        Map<String, List<String>> rows = new TreeMap<>();
        var cat = FurnitureBlocks.catalogue();
        for (String id : cat.keySet()) {
            int k = id.indexOf("__");
            if (k > 0 && !id.endsWith("_part")) rows.computeIfAbsent(id.substring(0, k), b -> new ArrayList<>()).add(id);
        }
        List<List<String>> list = new ArrayList<>();
        for (var e : rows.entrySet()) {
            List<String> row = new ArrayList<>();
            if (cat.containsKey(e.getKey())) row.add(e.getKey());
            row.addAll(e.getValue().stream().sorted().toList());
            list.add(row);
        }
        List<String> curtains = new ArrayList<>(cat.keySet().stream().filter(s -> s.startsWith("apt_curtain_")).sorted().toList());
        if (!curtains.isEmpty()) list.add(curtains);
        test.log("variants: " + list.size() + " rows");
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.owner.client.options.hudHidden = true;
        test.owner.client.options.getGamma().setValue(1.0);
        test.command("gamemode spectator " + name());
        test.command("weather clear");
        test.command("forceload add " + (x0 - 14) + " " + (z0 - 20) + " " + (x0 + 40) + " " + (z0 + 32));
        test.command("tp " + name() + " " + at(0, 3, 6) + " 180 20");
        test.await("pause: the chunks load", 6, () -> false, () -> row(list, 0, then));
    }

    private void row(List<List<String>> rows, int n, Runnable then) {
        if (n >= rows.size()) { test.check(failed == 0, "every variant found room (" + failed + " failed)"); test.owner.client.options.hudHidden = false; then.run(); return; }
        List<String> ids = rows.get(n);
        var cat = FurnitureBlocks.catalogue();
        int w = ids.size() * 3;
        boolean ground = cat.get(ids.get(0)) == FurnitureBlocks.Mount.GROUND && !ids.get(0).equals("apt_wall_carpet");
        test.command("time set 6000");
        test.command("fill " + bp(-14, 0, -18) + " " + bp(w + 14, 6, 30) + " minecraft:air");
        test.command("fill " + bp(-14, -2, -18) + " " + bp(w + 14, -2, 30) + " minecraft:stone");
        test.command("fill " + bp(-14, -1, -18) + " " + bp(w + 14, -1, 30) + " minecraft:stripped_oak_wood");
        int wallZ = ground ? -16 : -1;
        test.command("fill " + bp(-8, 0, wallZ) + " " + bp(w + 8, 5, wallZ) + " minecraft:white_concrete");
        test.await("pause: the row is built", 2, () -> false, () -> {
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                int x = i * 3 + 1;
                FurnitureBlocks.Mount mount = cat.get(id);
                if (id.contains("curtain")) { for (int dx = -1; dx <= 1; dx++) put(id, x + dx, 0, 0, Direction.SOUTH); continue; }
                if (id.startsWith("apt_bed")) { put(id, x, 0, 1, Direction.NORTH); continue; }
                int y = mount == FurnitureBlocks.Mount.GROUND || id.equals("apt_wall_carpet") ? 0 : 1;
                if (mount == FurnitureBlocks.Mount.CEILING) { test.command("setblock " + bp(x, 3, 0) + " minecraft:white_concrete"); y = 2; }
                put(id, x, y, 0, Direction.SOUTH);
            }
            String tag = String.format("var-%02d-%s", n + 1, ids.get(0).replace("apt_", "").replaceAll("__.*", ""));
            double cx = w / 2.0 + 0.5, far = Math.max(5, w / 2.2 + 1.5), lastX = (ids.size() - 1) * 3 + 1.5;
            double mx = Math.min(ids.size() - 1, 1) * 3 + 1.5;
            String pit = ground ? "10" : "2";
            List<String[]> day = new ArrayList<>();
            day.add(new String[] {cx + " 0 " + (far + 1), "180 " + pit, tag + "-1-front"});
            if (ground) day.add(new String[] {cx + " 0 " + (-far), "0 " + pit, tag + "-2-back"});
            day.add(new String[] {"-1.5 0 0.5", "-90 " + pit, tag + "-3-left"});
            day.add(new String[] {(lastX + 3) + " 0 0.5", "90 " + pit, tag + "-4-right"});
            day.add(new String[] {cx + " " + (far * 0.6 + 1) + " " + (far * 0.75), "180 38", tag + "-5-above"});
            day.add(new String[] {mx + " 0.3 3.2", "180 12", tag + "-6-close"});
            if (ids.size() > 4) day.add(new String[] {(lastX - 1.5) + " 0.3 3.2", "180 12", tag + "-6b-close"});
            day.add(new String[] {cx + " 1.5 " + (far * 1.8 + 3), "180 6", tag + "-7-far"});
            test.await("pause: the row is furnished", 3, () -> false, () -> shots(day.toArray(new String[0][]), 0, () -> night(tag, cx, far, w, n, rows, then)));
        });
    }

    private void night(String tag, double cx, double far, int w, int n, List<List<String>> rows, Runnable then) {
        test.command("time set 18000");
        put("apt_floor_lamp", -1, 0, 1, Direction.SOUTH);
        put("apt_floor_lamp", w + 1, 0, 1, Direction.SOUTH);
        test.await("pause: night falls", 4, () -> false, () ->
                shots(new String[][] {{cx + " 0 " + far, "180 6", tag + "-8-night"}}, 0, () -> row(rows, n + 1, then)));
    }

    private void put(String id, int dx, int dy, int dz, Direction facing) {
        BlockPos p = pos(dx, dy, dz);
        test.server().execute(() -> {
            for (int k = 0; k <= 2; k++) if (FurnitureBlocks.place(world(), p.south(k), id, facing, false)) return;
            failed++; test.fail("no room for " + id);
        });
    }

    private void shots(String[][] list, int i, Runnable then) {
        if (i >= list.length) { then.run(); return; }
        String[] xyz = list[i][0].split(" ");
        test.command("tp " + name() + " " + at(Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2])) + " " + list[i][1]);
        test.await("pause: the camera settles", 3, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, list[i][2] + ".png", test.owner.client.getFramebuffer(),
                    text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, () -> shots(list, i + 1, then));
        });
    }
}
