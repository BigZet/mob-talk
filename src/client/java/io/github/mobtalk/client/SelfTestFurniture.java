package io.github.mobtalk.client;

import io.github.mobtalk.game.FurnitureBlocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** {@code -Dmobtalk.selftest.only=furniture}: a show flat of the new city's furniture (rebuild spec stage 5). Four rooms two blocks
 * high — living room, kitchen, bedroom, bathroom — furnished through {@link FurnitureBlocks#place}; checks that every piece stands with
 * all its parts, that a chair seats the player, a lamp switches, curtains draw; screenshots from above (no ceiling) and at eye level
 * (ceiling on, lamps on, evening). No LLM, no voice. */
final class SelfTestFurniture {
    private final SelfTest test;
    private final int x0, y0, z0;
    private final List<Runnable> steps = new ArrayList<>();
    private int placed, failed;

    SelfTestFurniture(SelfTest test, int stageX, int stageY, int stageZ) { this.test = test; this.x0 = stageX + 40; this.y0 = stageY; this.z0 = stageZ; }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private String at(double dx, double dy, double dz) { return (x0 + dx) + " " + (y0 + dy) + " " + (z0 + dz); }
    private String bp(int dx, int dy, int dz) { return (x0 + dx) + " " + (y0 + dy) + " " + (z0 + dz); }
    private BlockPos pos(int dx, int dy, int dz) { return new BlockPos(x0 + dx, y0 + dy, z0 + dz); }

    void run(Runnable then) {
        test.log("furniture stages: a show flat, sitting, lamps, curtains, screenshots");
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.owner.client.options.hudHidden = true;
        test.owner.client.options.getGamma().setValue(1.0);
        test.command("gamemode creative " + name());
        test.command("time set 6000"); test.command("weather clear");
        test.command("effect give " + name() + " minecraft:slow_falling 30 1 true");
        test.command("tp " + name() + " " + at(13, 12, -6) + " 0 50");
        test.await("pause: the show flat's chunks load", 4, () -> false, () -> { shell(); furnish(); look(then); });
    }

    /** Floor (parquet-coloured planks), white walls, a window on the south side of every room; ceiling later. */
    private void shell() {
        test.command("fill " + bp(-3, -2, -3) + " " + bp(31, 6, 10) + " minecraft:air");
        test.command("fill " + bp(-2, -1, -2) + " " + bp(30, -1, 9) + " minecraft:oak_planks");
        test.command("fill " + bp(-1, -1, -1) + " " + bp(29, -1, 7) + " minecraft:stripped_oak_wood");
        test.command("fill " + bp(-1, 0, -1) + " " + bp(29, 1, 7) + " minecraft:white_concrete outline");
        test.command("fill " + bp(0, 0, 0) + " " + bp(28, 1, 6) + " minecraft:air");
        for (int wall : new int[] {9, 16, 24}) {
            test.command("fill " + bp(wall, 0, -1) + " " + bp(wall, 1, 7) + " minecraft:white_concrete");
            test.command("fill " + bp(wall, 0, 4) + " " + bp(wall, 1, 4) + " minecraft:air"); // doorways
        }
        for (int[] w : new int[][] {{3, 5}, {12, 13}, {19, 21}, {26, 26}}) test.command("fill " + bp(w[0], 1, 7) + " " + bp(w[1], 1, 7) + " minecraft:glass");
        test.command("fill " + bp(-1, 2, -1) + " " + bp(29, 2, 7) + " minecraft:air");
    }

    private void put(String id, int dx, int dy, int dz, Direction facing) { put(id, dx, dy, dz, facing, false); }

    private void put(String id, int dx, int dy, int dz, Direction facing, boolean radiator) {
        BlockPos p = pos(dx, dy, dz);
        test.server().execute(() -> {
            if (FurnitureBlocks.place(world(), p, id, facing, radiator)) placed++;
            else { failed++; test.fail("no room for " + id + " at " + p.toShortString()); }
        });
    }

    private void furnish() {
        // living room x 0..8: wall unit on the north wall, sofa on the east wall, TV and armchairs, coffee table, lamps, curtains
        put("apt_wall_unit", 2, 0, 0, Direction.SOUTH);
        put("apt_sofa_red", 8, 0, 2, Direction.WEST);
        put("apt_floor_lamp", 8, 0, 4, Direction.WEST);
        put("apt_coffee_table", 6, 0, 2, Direction.WEST);
        put("apt_armchair_green", 5, 0, 4, Direction.EAST);
        put("apt_armchair_green", 6, 0, 0, Direction.SOUTH);
        put("apt_tv", 4, 0, 2, Direction.EAST);
        put("apt_chair", 0, 0, 5, Direction.EAST);
        put("apt_chandelier", 4, 1, 3, Direction.SOUTH);
        for (int x = 3; x <= 5; x++) put("apt_curtain_wine", x, 0, 6, Direction.NORTH, true);
        // kitchen x 10..15
        put("apt_fridge", 10, 0, 0, Direction.SOUTH);
        put("apt_stove_kettle", 11, 0, 0, Direction.SOUTH);
        put("apt_kitchen_base_white", 12, 0, 0, Direction.SOUTH);
        put("apt_kitchen_sink_white", 13, 0, 0, Direction.SOUTH);
        put("apt_kitchen_jars_white", 14, 0, 0, Direction.SOUTH);
        put("apt_kitchen_base_white", 15, 0, 0, Direction.SOUTH);
        put("apt_wall_cupboard_white", 12, 1, 0, Direction.SOUTH);
        put("apt_dish_rack_white", 13, 1, 0, Direction.SOUTH);
        put("apt_wall_cupboard_white", 14, 1, 0, Direction.SOUTH);
        put("apt_wall_cupboard_white", 15, 1, 0, Direction.SOUTH);
        put("apt_kitchen_table", 13, 0, 5, Direction.NORTH);
        put("apt_stool", 12, 0, 5, Direction.EAST);
        put("apt_stool", 14, 0, 5, Direction.WEST);
        put("apt_ceiling_plate", 13, 1, 3, Direction.SOUTH);
        for (int x = 12; x <= 13; x++) put("apt_curtain_gold", x, 0, 6, Direction.NORTH, true);
        // bedroom x 17..23: a double bed between bedside tables under a carpet, wardrobe, chest, dressing table, suitcases
        test.server().execute(() -> {
            if (FurnitureBlocks.placeDoubleBed(world(), pos(20, 0, 1), Direction.NORTH)) placed++;
            else { failed++; test.fail("no room for the double bed"); }
        });
        put("apt_nightstand", 18, 0, 0, Direction.SOUTH);
        put("apt_nightstand", 21, 0, 0, Direction.SOUTH);
        put("apt_wall_carpet_high", 20, 1, 0, Direction.SOUTH);
        put("apt_bedside_rug", 20, 0, 3, Direction.EAST);
        put("apt_wardrobe", 23, 0, 1, Direction.WEST);
        put("apt_chest_drawers", 23, 0, 2, Direction.WEST);
        put("apt_suitcases", 23, 0, 3, Direction.WEST);
        put("apt_dressing_table", 17, 0, 1, Direction.EAST);
        put("apt_chair_clothes", 18, 0, 1, Direction.WEST);
        put("apt_ironing_board", 22, 0, 5, Direction.NORTH);
        put("apt_ceiling_plate", 20, 1, 4, Direction.SOUTH);
        put("apt_sconce", 17, 1, 3, Direction.EAST);
        for (int x = 19; x <= 21; x++) put("apt_curtain_blue", x, 0, 6, Direction.NORTH, true);
        // bathroom x 25..28
        put("apt_bathtub", 25, 0, 0, Direction.EAST);
        put("apt_toilet", 28, 0, 3, Direction.WEST);
        put("apt_washbasin", 28, 0, 0, Direction.WEST);
        put("apt_washer", 25, 0, 3, Direction.EAST);
        put("apt_towel_rail", 28, 1, 5, Direction.WEST);
        put("apt_radiator", 26, 0, 6, Direction.NORTH);
        put("apt_ceiling_plate", 26, 1, 3, Direction.SOUTH);
        put("apt_picture_wave", 5, 1, 0, Direction.SOUTH);
    }

    private void look(Runnable then) {
        test.await("pause: the furniture is placed", 3, () -> false, () -> {
            test.check(failed == 0 && placed > 30, "every piece found room (" + placed + " placed, " + failed + " failed)");
            BlockState part = world().getBlockState(pos(1, 1, 0));
            test.check(part.getBlock() != net.minecraft.block.Blocks.AIR, "the wall unit fills its upper cells: " + part);
            // aerial shots of the four rooms with no ceiling
            String[][] aerial = {
                    {"4.5 3.5 -2.5", "0 50", "furniture-01-living-above"}, {"12.5 3.5 -2.5", "0 50", "furniture-02-kitchen-above"},
                    {"20.5 3.5 -2.5", "0 50", "furniture-03-bedroom-above"}, {"26.5 3.5 -2.5", "0 55", "furniture-04-bath-above"},
                    {"4.5 -0.4 9.5", "180 0", "furniture-05-living-window"}, {"12.5 -0.4 9.5", "180 0", "furniture-06-kitchen-window"}};
            shots(aerial, 0, () -> sitAndSwitch(then));
        });
    }

    private void shots(String[][] list, int i, Runnable then) {
        if (i >= list.length) { then.run(); return; }
        String[] xyz = list[i][0].split(" ");
        test.command("gamemode spectator " + name()); // no gravity, no collisions: the camera stays where it is put
        test.command("tp " + name() + " " + at(Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2])) + " " + list[i][1]);
        test.await("pause: the camera settles", 3, () -> false, () -> {
            var p = test.owner.client.player;
            test.log(String.format("camera at %.1f %.1f %.1f yaw %.0f pitch %.0f", p.getX() - x0, p.getY() - y0, p.getZ() - z0, p.getYaw(), p.getPitch()));
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, list[i][2] + ".png", test.owner.client.getFramebuffer(),
                    text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, () -> shots(list, i + 1, then));
        });
    }

    private void sitAndSwitch(Runnable then) {
        // the ceiling goes on; evening; the chandelier switches off and on; the player sits on the sofa; curtains drawn
        test.command("fill " + bp(-1, 2, -1) + " " + bp(29, 2, 7) + " minecraft:white_concrete");
        test.command("time set 13000");
        test.command("gamemode creative " + name());
        test.server().execute(() -> {
            BlockPos lamp = pos(4, 1, 3);
            BlockState s = world().getBlockState(lamp);
            if (s.getBlock() instanceof FurnitureBlocks.Furniture f) f.setLit(world(), lamp, s, false);
        });
        test.await("pause: the lamp switched", 1, () -> false, () -> {
            test.check(!world().getBlockState(pos(4, 1, 3)).get(FurnitureBlocks.LIT), "the chandelier switches off");
            test.check(world().getLightLevel(pos(4, 0, 3)) < 15, "the room is darker with the lamp off");
            test.server().execute(() -> {
                BlockPos lamp = pos(4, 1, 3);
                BlockState s = world().getBlockState(lamp);
                if (s.getBlock() instanceof FurnitureBlocks.Furniture f) f.setLit(world(), lamp, s, true);
                BlockState sofa = world().getBlockState(pos(8, 0, 2));
                if (sofa.getBlock() instanceof FurnitureBlocks.Furniture f)
                    FurnitureBlocks.sit(world(), pos(8, 0, 1), f.spec(), sofa.get(net.minecraft.block.HorizontalFacingBlock.FACING), test.serverPlayer());
            });
            test.await("the player sits on the sofa", 5, () -> test.owner.client.player.hasVehicle(), () -> {
                String[][] inside = {{"", "", "furniture-07-from-the-sofa"}};
                test.await("pause: the camera settles", 3, () -> false, () -> {
                    ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, inside[0][2] + ".png", test.owner.client.getFramebuffer(),
                            text -> test.log("screenshot: " + text.getString()));
                    test.command("ride " + name() + " dismount");
                    test.await("the player gets up", 5, () -> !test.owner.client.player.hasVehicle(), () -> eyeLevel(then));
                });
            });
        });
    }

    /** Every piece of the catalogue in a row against a white wall, in daylight, four to a screenshot: proves each model loads and
     * shows the whole set at the player's scale. Wall pieces hang in the upper cell, ceiling ones under a beam. */
    private void gallery(Runnable then) {
        int gz = 24;
        List<String> ids = new ArrayList<>(FurnitureBlocks.catalogue().keySet());
        int perRow = (ids.size() + 1) / 2;
        int len = perRow * 3;
        test.command("time set 6000");
        test.command("forceload add " + (x0 - 3) + " " + (z0 + gz - 2) + " " + (x0 + len) + " " + (z0 + gz + 17));
        test.await("pause: the gallery's chunks load", 3, () -> false, () -> {
            for (int row = 0; row < 2; row++) {
                int z = gz + row * 14;
                test.command("fill " + bp(-3, -2, z - 2) + " " + bp(len, 4, z + 3) + " minecraft:air");
                test.command("fill " + bp(-3, -1, z - 2) + " " + bp(len, -1, z + 3) + " minecraft:stripped_oak_wood");
                test.command("fill " + bp(-3, 0, z - 1) + " " + bp(len, 2, z - 1) + " minecraft:white_concrete");
            }
            test.await("pause: the gallery is built", 2, () -> false, () -> gallerySet(ids, gz, perRow, then));
        });
    }

    private void gallerySet(List<String> ids, int gz, int perRow, Runnable then) {
        {
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                int x = (i % perRow) * 3, z = gz + (i / perRow) * 14;
                FurnitureBlocks.Mount mount = FurnitureBlocks.catalogue().get(id);
                if (id.contains("curtain")) { for (int dx = -1; dx <= 1; dx++) put(id, x + dx, 0, z, Direction.SOUTH); continue; }
                if (id.equals("apt_bed_double_r")) continue; // stood with its left half
                if (id.equals("apt_bed_double_l")) {
                    int bx = x, bz = z;
                    test.server().execute(() -> { if (FurnitureBlocks.placeDoubleBed(world(), pos(bx, 0, bz + 1), Direction.NORTH)) placed++;
                                                  else { failed++; test.fail("gallery: no room for the double bed"); } });
                    continue;
                }
                if (id.startsWith("apt_bed")) { put(id, x, 0, z + 1, Direction.NORTH); continue; }
                int y = mount == FurnitureBlocks.Mount.GROUND || id.contains("radiator") || id.equals("apt_wall_carpet") ? 0 : 1;
                if (mount == FurnitureBlocks.Mount.CEILING) test.command("setblock " + bp(x, 2, z) + " minecraft:white_concrete");
                int zz = z, yy = y;
                test.server().execute(() -> {
                    for (int dz = 0; dz <= 2; dz++) if (FurnitureBlocks.place(world(), pos(x, yy, zz + dz), id, Direction.SOUTH)) { placed++; return; }
                    failed++; test.fail("gallery: no room for " + id);
                });
            }
            List<String[]> list = new ArrayList<>();
            for (int row = 0; row * perRow < ids.size(); row++)
                for (int col = 0; col < perRow && row * perRow + col < ids.size(); col += 3) {
                    int n = Math.min(3, Math.min(perRow - col, ids.size() - row * perRow - col));
                    double cx = col * 3 + (n - 1) * 1.5 + 0.5;
                    list.add(new String[] {cx + " 0.6 " + (gz + row * 14 + 6.5), "180 14",
                            String.format("furniture-g%02d-%s", list.size() + 1, ids.get(row * perRow + col))});
                }
            test.await("pause: the gallery is furnished", 2, () -> false, () -> {
                test.check(failed == 0, "every gallery piece found room (" + failed + " failed)");
                shots(list.toArray(new String[0][]), 0, () -> { test.owner.client.options.hudHidden = false; then.run(); });
            });
        }
    }

    private void eyeLevel(Runnable then) {
        String[][] eye = {
                {"0.5 0 5.5", "-120 15", "furniture-08-living-eye"}, {"7.5 0 5.5", "135 10", "furniture-09-living-eye-2"},
                {"12.5 0 4.5", "180 20", "furniture-10-kitchen-eye"}, {"22.5 0 5.5", "140 18", "furniture-11-bedroom-eye"}, {"17.5 0 5.5", "-150 15", "furniture-11b-bedroom-eye"},
                {"27.5 0 5.5", "150 20", "furniture-12-bath-eye"}, {"6.5 0 1.5", "20 8", "furniture-13-curtains-eye"}};
        shots(eye, 0, () -> {
            test.server().execute(() -> {
                BlockPos c = pos(4, 0, 6);
                BlockState s = world().getBlockState(c);
                s.onUse(world(), test.serverPlayer(), new net.minecraft.util.hit.BlockHitResult(c.toCenterPos(), Direction.NORTH, c, false));
            });
            test.await("pause: curtains drawn", 2, () -> false, () -> {
                test.check(!world().getBlockState(pos(3, 1, 6)).get(FurnitureBlocks.OPEN), "the whole window's curtains draw together");
                shots(new String[][] {{"6.5 0 1.5", "20 8", "furniture-14-curtains-drawn"}}, 0, () -> gallery(then));
            });
        });
    }
}
