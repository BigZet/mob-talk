package io.github.mobtalk.client;

import io.github.mobtalk.game.MfcContent;
import java.util.*;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** The МФЦ hall showcase ({@code -Only mfc}): a hall on a stage in the sky with every piece of the МФЦ furniture (tools/mfc/mfc.py)
 * where a real office has it — five reception windows under the sign and the queue board, waiting chairs, the ticket terminal, the
 * forms desk, the samples stand, the cooler, ficuses, the photo booth, queue posts, the guard post and the metal detector at the
 * door — and the staff in their clothes: five operators at the windows, the guard, the administrator, a few visitors. Screenshots
 * in both looks (people and the vanilla villager) from the door, at the windows, at the terminal and at the guard post. No LLM. */
final class SelfTestMfc {
    private static final String TAG = "mobtalk_mfc";
    private final SelfTest test;
    private final int fx, fy, fz;
    private Runnable next = () -> { };
    private boolean savedHuman;

    SelfTestMfc(SelfTest test, int stageX, int stageY, int stageZ) { this.test = test; this.fx = stageX + 600; this.fy = stageY; this.fz = stageZ; }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private String at(int dx, int dy, int dz) { return (fx + dx) + " " + (fy + dy) + " " + (fz + dz); }
    private String at(double dx, double dy, double dz) { return (fx + dx) + " " + (fy + dy) + " " + (fz + dz); }
    private BlockPos pos(int dx, int dy, int dz) { return new BlockPos(fx + dx, fy + dy, fz + dz); }
    private <T> T onServer(java.util.function.Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }
    private Box area() { return new Box(pos(8, 0, 5)).expand(16, 8, 16); }
    private void set(int x, int y, int z, String block) { test.command("setblock " + at(x, y, z) + " " + block); }

    void run(Runnable then) {
        next = then;
        savedHuman = test.owner.config().humanVillagers;
        test.owner.config().humanVillagers = true;
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.log("mfc stages: the hall's furniture, the staff in both looks");
        test.command("time set 6000"); test.command("weather clear");
        test.command("gamerule doDaylightCycle false"); test.command("gamerule doMobSpawning false");
        test.command("gamemode spectator " + name());
        test.command("tp " + name() + " " + at(8, 2, 12));
        test.await("pause: the stage chunks load", 3, () -> false, this::build);
    }

    private int tries;
    private void build() {
        test.command("fill " + at(-4, -1, -5) + " " + at(20, -1, 16) + " minecraft:smooth_quartz");
        test.command("fill " + at(-4, 0, -5) + " " + at(20, 6, 16) + " minecraft:air");
        for (int x = -3; x <= 19; x += 2) test.command("fill " + at(x, -1, -3) + " " + at(x, -1, 15) + " minecraft:light_gray_concrete"); // the floor's tile rows
        test.command("fill " + at(-3, -1, -4) + " " + at(19, -1, -2) + " minecraft:light_gray_concrete"); // the staff's side
        for (String wall : List.of(at(-4, 0, -5) + " " + at(20, 4, -5), at(-4, 0, -5) + " " + at(-4, 4, 16), at(20, 0, -5) + " " + at(20, 4, 16),
                at(-4, 0, 16) + " " + at(6, 4, 16), at(11, 0, 16) + " " + at(20, 4, 16)))
            test.command("fill " + wall + " minecraft:white_concrete");
        // Five windows in a row facing the hall (south), the operators behind them, the sign and the board on the back wall.
        for (int i = 0; i < 5; i++) set(2 + i, 0, 0, "mobtalk:mfc_window[facing=south,number=" + (i + 1) + "]");
        test.command("fill " + at(-3, 0, 0) + " " + at(1, 0, 0) + " minecraft:white_concrete");
        test.command("fill " + at(7, 0, 0) + " " + at(9, 0, 0) + " minecraft:white_concrete");
        set(4, 3, -4, "mobtalk:mfc_sign[facing=south]");
        set(12, 3, -4, "mobtalk:mfc_board[facing=south]");
        set(0, 3, -4, "mobtalk:mfc_board[facing=south]");
        set(-1, 0, -1, "mobtalk:mfc_plant[facing=south]");
        set(8, 0, -1, "mobtalk:mfc_plant[facing=south]");
        // The waiting hall: two rows of chairs facing the windows, the queue posts, the terminal by the door.
        for (int x = 1; x <= 7; x++) { set(x, 0, 5, "mobtalk:mfc_chairs[facing=north]"); set(x, 0, 7, "mobtalk:mfc_chairs[facing=north]"); }
        for (int x = 2; x <= 6; x++) set(x, 0, 2, "mobtalk:mfc_stanchion[facing=north]");
        set(9, 0, 11, "mobtalk:mfc_terminal[facing=south]");
        set(11, 0, 11, "mobtalk:mfc_terminal[facing=south]");
        // The east side: forms desk, samples stand, cooler, photo booth, a ficus.
        set(13, 0, 2, "mobtalk:mfc_form_desk[facing=west]");
        set(13, 0, 4, "mobtalk:mfc_form_desk[facing=west]");
        set(16, 0, 6, "mobtalk:mfc_info[facing=west]");
        set(17, 0, 9, "mobtalk:mfc_cooler[facing=west]");
        set(17, 0, 1, "mobtalk:mfc_photo[facing=west]");
        set(17, 0, 12, "mobtalk:mfc_plant[facing=west]");
        set(-2, 0, 12, "mobtalk:mfc_plant[facing=east]");
        // The door: the metal detector and the guard post.
        set(8, 0, 14, "mobtalk:mfc_detector[facing=south]");
        set(13, 0, 14, "mobtalk:mfc_guard_desk[facing=west]");
        test.await("the hall stands", 5, () -> onServer(() -> world().getBlockState(pos(2, 0, 0)).isOf(MfcContent.WINDOW) && world().getBlockState(pos(13, 0, 14)).isOf(MfcContent.GUARD_DESK)), () -> {
            if (!onServer(() -> world().getBlockState(pos(13, 0, 14)).isOf(MfcContent.GUARD_DESK)) && ++tries < 5) { build(); return; }
            test.check(onServer(() -> world().getBlockState(pos(4, 0, 0)).get(MfcContent.Window.NUMBER)) == 3, "the third window shows «3»");
            staff();
        });
    }

    private void summon(double x, double z, String profession, float yaw, String extra) {
        test.command("summon minecraft:villager " + at(x, 0, z) + " {NoAI:1b,PersistenceRequired:1b,Rotation:[" + yaw + "f,0f],Tags:[\"" + TAG + "\"],VillagerData:{profession:\""
                + profession + "\",type:\"minecraft:plains\",level:2},Xp:20" + extra + "}");
    }

    private void staff() {
        for (int i = 0; i < 5; i++) summon(2.5 + i, -1.2, "mobtalk:mfc_operator", 0f, "");
        summon(14.3, 14.5, "mobtalk:mfc_guard", 90f, "");
        summon(15.0, 6.5, "mobtalk:mfc_admin", 90f, "");
        String[] jobs = { "librarian", "farmer", "none", "mason", "cleric", "none" };
        double[][] visitors = { { 3.5, 1.2 }, { 5.5, 1.3 }, { 9.5, 10.0 }, { 2.5, 4.3 }, { 6.5, 4.3 }, { 13.2, 3.0 } };
        float[] yaws = { 180f, 180f, 180f, 180f, 180f, 90f };
        for (int i = 0; i < visitors.length; i++) summon(visitors[i][0], visitors[i][1], "minecraft:" + jobs[i], yaws[i], "");
        test.await("the staff and visitors stand", 15, () -> onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains(TAG)).size()) >= 13, () -> {
            long staff = onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getVillagerData().getProfession() == MfcContent.OPERATOR
                    || v.getVillagerData().getProfession() == MfcContent.GUARD || v.getVillagerData().getProfession() == MfcContent.ADMIN).size());
            test.check(staff == 7, "five operators, the guard and the administrator keep their professions (" + staff + ")");
            onServer(() -> { test.serverPlayer().setStackInHand(Hand.MAIN_HAND, new ItemStack(MfcContent.TICKET)); return null; });
            shots(true);
        });
    }

    /** The camera round: from the door, at the windows, the terminal, the east side, the guard; then the vanilla look. */
    private void shots(boolean human) {
        String look = human ? "people" : "vanilla";
        List<String[]> round = List.of(
                new String[] { at(16.5, 3.2, 15.3), "140 18", "hall" },
                new String[] { at(4.5, 1.0, 3.8), "180 6", "windows" },
                new String[] { at(10.0, 1.2, 8.6), "0 8", "terminal" },
                new String[] { at(10.5, 1.5, 4.5), "-90 10", "east" },
                new String[] { at(10.5, 1.2, 11.8), "130 5", "door" });
        step(round, 0, look, () -> {
            if (human) {
                test.owner.config().humanVillagers = false;
                test.await("pause: the vanilla look", 2, () -> false, () -> shots(false));
            } else gallery();
        });
    }

    private static final String[] PIECES = { "mfc_terminal", "mfc_window[number=7]", "mfc_chairs", "mfc_form_desk", "mfc_info", "mfc_cooler", "mfc_plant",
            "mfc_stanchion", "mfc_photo", "mfc_detector", "mfc_guard_desk", "mfc_board", "mfc_sign" };
    /** Every piece alone on a strip south of the hall, facing south: a front three-quarter view and a back view of each, with a
     * 2-block armour stand for scale (the model check of the self-review loop). */
    private void gallery() {
        test.command("fill " + at(-4, -1, 18) + " " + at(4 * PIECES.length, -1, 26) + " minecraft:smooth_quartz");
        test.command("fill " + at(-4, 0, 18) + " " + at(4 * PIECES.length, 5, 26) + " minecraft:air");
        for (int i = 0; i < PIECES.length; i++) {
            String piece = PIECES[i], props = piece.contains("[") ? piece.substring(piece.indexOf('[') + 1, piece.length() - 1) + "," : "";
            String id = piece.contains("[") ? piece.substring(0, piece.indexOf('[')) : piece;
            boolean wall = id.equals("mfc_board") || id.equals("mfc_sign");
            if (wall) test.command("fill " + at(4 * i - 1, 0, 21) + " " + at(4 * i + 1, 3, 21) + " minecraft:white_concrete");
            set(4 * i, wall ? 2 : 0, 22, "mobtalk:" + id + "[" + props + "facing=south]");
            test.command("summon minecraft:armor_stand " + at(4 * i + 2.0, 0, 22.5) + " {NoGravity:1b,Rotation:[0f,0f],Tags:[\"" + TAG + "\"]}");
        }
        test.await("pause: the gallery stands", 3, () -> false, () -> piece(0));
    }
    private void piece(int i) {
        if (i >= PIECES.length) { lineup(); return; }
        String id = PIECES[i].contains("[") ? PIECES[i].substring(0, PIECES[i].indexOf('[')) : PIECES[i];
        boolean wall = id.equals("mfc_board") || id.equals("mfc_sign");
        double y = wall ? 1.4 : 0.2;
        test.command("tp " + name() + " " + at(4 * i - 1.6, y, 25.2) + " -153 14");
        test.await("pause: the camera settles", 2, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, "mobtalk-mfc-piece-" + id + "-front.png", test.owner.client.getFramebuffer(), t -> { });
            if (wall) { test.await("pause: after the screenshot", 1, () -> false, () -> piece(i + 1)); return; }
            test.command("tp " + name() + " " + at(4 * i + 2.0, y + 0.4, 19.4) + " 37 18");
            test.await("pause: the camera settles", 2, () -> false, () -> {
                ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, "mobtalk-mfc-piece-" + id + "-back.png", test.owner.client.getFramebuffer(), t -> { });
                test.await("pause: after the screenshot", 1, () -> false, () -> piece(i + 1));
            });
        });
    }
    private void step(List<String[]> round, int i, String look, Runnable then) {
        if (i >= round.size()) { then.run(); return; }
        String[] s = round.get(i);
        if (!look.equals("people") && !s[2].equals("hall") && !s[2].equals("windows")) { step(round, i + 1, look, then); return; }
        test.command("tp " + name() + " " + s[0] + " " + s[1]);
        test.await("pause: the camera settles", 3, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, "mobtalk-mfc-" + s[2] + "-" + look + ".png", test.owner.client.getFramebuffer(), t -> test.log("screenshot: " + t.getString()));
            test.await("pause: after the screenshot", 1, () -> false, () -> step(round, i + 1, look, then));
        });
    }

    /** The staff side by side, close: four operators, two guards, two administrators, in front and from behind, in both looks. */
    private void lineup() {
        test.command("fill " + at(-4, -1, 28) + " " + at(20, -1, 36) + " minecraft:smooth_quartz");
        test.command("fill " + at(-4, 0, 28) + " " + at(20, 5, 36) + " minecraft:air");
        String[] jobs = { "mfc_operator", "mfc_operator", "mfc_operator", "mfc_operator", "mfc_guard", "mfc_guard", "mfc_admin", "mfc_admin" };
        for (int i = 0; i < jobs.length; i++) summon(2.5 + i * 1.2, 31.5, "mobtalk:" + jobs[i], 0f, "");
        test.owner.config().humanVillagers = true;
        test.await("pause: the staff line up", 3, () -> false, () -> lineupShot(true, true));
    }
    private void lineupShot(boolean human, boolean front) {
        String look = human ? "people" : "vanilla";
        test.command("tp " + name() + " " + (front ? at(6.7, 0.0, 35.0) + " 180 8" : at(6.7, 0.0, 28.2) + " 0 8"));
        test.await("pause: the camera settles", 3, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, "mobtalk-mfc-staff-" + (front ? "front" : "back") + "-" + look + ".png", test.owner.client.getFramebuffer(), t -> { });
            test.await("pause: after the screenshot", 1, () -> false, () -> {
                if (front) lineupShot(human, false);
                else if (human) { test.owner.config().humanVillagers = false; test.await("pause: the vanilla look", 2, () -> false, () -> lineupShot(false, true)); }
                else finish();
            });
        });
    }

    private void finish() {
        test.owner.config().humanVillagers = savedHuman;
        test.command("gamerule doDaylightCycle true"); test.command("gamerule doMobSpawning true");
        test.await("pause: mfc stage done", 2, () -> false, next);
    }
}
