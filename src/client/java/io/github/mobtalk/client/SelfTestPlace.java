package io.github.mobtalk.client;

import io.github.mobtalk.game.StructureCapture;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.PlacePrompt;
import io.github.mobtalk.core.place.PlaceClassifier;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Self-test stages for the place block's second vision (track H), no LLM involved: a birch grown by its feature next to the cow
 * (trees by rule), a small plank house built by commands with the cow inside (the classifier's two-level guess), and the cow
 * taken into a house of the nearest village located through the registry (structure facts). Each stage captures the cow's
 * snapshot the way a conversation would and checks the place details. */
final class SelfTestPlace {
    private final SelfTest test;
    private final UUID cow;
    /** A clean platform beside the main stage: the sky around it is empty. */
    private final int px, py, pz;
    private final int homeX, homeY, homeZ;
    private Runnable next = () -> { };

    SelfTestPlace(SelfTest test, UUID cow, int stageX, int stageY, int stageZ) {
        this.test = test; this.cow = cow;
        this.px = stageX + 40; this.py = stageY; this.pz = stageZ;
        this.homeX = stageX + 3; this.homeY = stageY; this.homeZ = stageZ;
    }

    void run(Runnable then) {
        next = then;
        test.log("place stages (track H): tree, player's house, village from the registry");
        test.command("fill " + (px - 9) + " " + (py - 1) + " " + (pz - 9) + " " + (px + 9) + " " + (py + 12) + " " + (pz + 9) + " minecraft:air");
        test.command("fill " + (px - 9) + " " + (py - 1) + " " + (pz - 9) + " " + (px + 9) + " " + (py - 1) + " " + (pz + 9) + " minecraft:grass_block");
        test.command("place feature minecraft:birch " + (px - 5) + " " + py + " " + (pz - 5));
        test.command("tp " + player() + " " + px + " " + py + " " + (pz + 6));
        test.command("tp " + cow + " " + (px - 3) + " " + py + " " + (pz - 5));
        test.await("pause: birch grown and cow beside it", 3, () -> false, this::tree);
    }

    private String player() { return test.owner.client.player.getName().getString(); }

    private void capture(String what, java.util.function.Consumer<MobSnapshot> checks, Runnable after) {
        ServerWorld world = (ServerWorld) test.serverPlayer().getWorld();
        CompletableFuture<MobSnapshot> captured = test.bridge().capture(cow, test.owner.client.player.getUuid(), world.getRegistryKey().getValue().toString());
        test.await("snapshot " + what, 20, captured::isDone, () -> {
            if (captured.isCompletedExceptionally()) test.fail("capture failed (" + what + "): " + captured.exceptionNow().getMessage());
            else {
                MobSnapshot snapshot = captured.join();
                test.log(what + " place block: " + PlacePrompt.block(snapshot).replace('\n', ' '));
                checks.accept(snapshot);
            }
            after.run();
        });
    }

    private static Map<?, ?> place(MobSnapshot snapshot) {
        return snapshot.environmentDetails().get("place") instanceof Map<?, ?> map ? map : Map.of();
    }
    private static String lines(Map<?, ?> place, String key) {
        return place.get(key) instanceof List<?> list ? String.join(" | ", list.stream().map(String::valueOf).toList()) : "";
    }

    private void tree() {
        capture("next to the birch", snapshot -> {
            String trees = lines(place(snapshot), "trees");
            test.check(trees.contains("берёз"), "trees by rule name the birch next to the cow: " + trees);
            test.check(lines(place(snapshot), "structures").isEmpty(), "no vanilla structure in the empty sky");
        }, this::house);
    }

    private void house() {
        // A 7×7 plank house, three high inside, with a door, a window, a bed and a chest; the cow in the middle.
        int x0 = px + 2, z0 = pz - 3, x1 = x0 + 6, z1 = z0 + 6;
        test.command("fill " + x0 + " " + py + " " + z0 + " " + x1 + " " + (py + 3) + " " + z1 + " minecraft:oak_planks hollow");
        test.command("fill " + x0 + " " + (py + 4) + " " + z0 + " " + x1 + " " + (py + 4) + " " + z1 + " minecraft:oak_slab");
        test.command("setblock " + (x0 + 3) + " " + py + " " + z0 + " minecraft:oak_door[facing=north,half=lower,open=true]");
        test.command("setblock " + (x0 + 3) + " " + (py + 1) + " " + z0 + " minecraft:oak_door[facing=north,half=upper,open=true]");
        test.command("setblock " + x1 + " " + (py + 1) + " " + (z0 + 3) + " minecraft:glass_pane");
        test.command("setblock " + (x0 + 1) + " " + py + " " + (z0 + 1) + " minecraft:red_bed[facing=south,part=head]");
        test.command("setblock " + (x0 + 1) + " " + py + " " + (z0 + 2) + " minecraft:red_bed[facing=south,part=foot]");
        test.command("setblock " + (x1 - 1) + " " + py + " " + (z1 - 1) + " minecraft:chest");
        test.command("setblock " + (x1 - 1) + " " + (py + 2) + " " + (z0 + 1) + " minecraft:wall_torch[facing=south]");
        test.command("tp " + cow + " " + (x0 + 3) + " " + py + " " + (z0 + 3));
        test.await("pause: cow inside the plank house", 3, () -> false, () -> capture("inside the player's house", snapshot -> {
            Map<?, ?> place = place(snapshot);
            test.check(place.get("summary") instanceof String s && (s.contains("помещени") || s.contains("камер")), "the flood sees a room: " + place.get("summary"));
            PlaceClassifier classifier = PlaceClassifier.bundled();
            boolean twoLevels = classifier != null && classifier.version() == 2;
            test.check(!twoLevels || place.get("looksLikeFunction") instanceof String, "the classifier names a function: " + place.get("looksLikeFunction")
                    + " (" + place.get("looksLikeFunctionConfidence") + "), class " + place.get("looksLike") + " (" + place.get("looksLikeConfidence") + ")");
            test.log("house guess: function " + place.get("looksLikeFunction") + ", class " + place.get("looksLike"));
        }, this::village));
    }

    private void village() {
        ServerWorld world = (ServerWorld) test.serverPlayer().getWorld();
        BlockPos spot = StructureCapture.villageHouseSpot(world, test.serverPlayer().getBlockPos(), 100);
        if (spot == null) { test.fail("no village within 1600 blocks"); back(); return; }
        test.log("village house spot " + spot.toShortString());
        test.command("tp " + player() + " " + (spot.getX() + 4) + " " + (spot.getY() + 6) + " " + (spot.getZ() + 4));
        test.command("effect give " + player() + " minecraft:slow_falling 30 1 true");
        test.command("tp " + cow + " " + spot.getX() + " " + spot.getY() + " " + spot.getZ());
        test.await("pause: cow in the village house", 5, () -> false, () -> capture("in a village house", snapshot -> {
            String structures = lines(place(snapshot), "structures");
            test.check(structures.contains("деревня"), "the registry names the village around the cow: " + structures);
            test.check(structures.contains("Ты внутри") || structures.contains("Рядом") || structures.contains("территории"), "the facts say where in the village");
        }, this::back));
    }

    private void back() {
        test.command("tp " + cow + " " + homeX + " " + homeY + " " + homeZ);
        test.command("tp " + player() + " " + (homeX - 3) + " " + homeY + " " + homeZ);
        test.await("pause: back on the stage", 3, () -> false, next);
    }
}
