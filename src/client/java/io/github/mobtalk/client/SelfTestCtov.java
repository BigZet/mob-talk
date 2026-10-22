package io.github.mobtalk.client;

import io.github.mobtalk.game.StructureCapture;
import io.github.mobtalk.game.VillageSites;
import io.github.mobtalk.game.WeddingController;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.Gender;
import io.github.mobtalk.core.MobCard;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.PlacePrompt;
import io.github.mobtalk.core.SocialProfile;
import io.github.mobtalk.core.Wedding;
import io.github.mobtalk.core.WeddingFaults;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;

/** Self-test stages for a village of CTOV (ChoiceTheorem's Overhauled Village), skipped when that mod is not installed: the nearest
 * CTOV village located through the registry, the cow taken into one of its houses (the structure facts name it in Russian, no raw
 * ids), its villagers read on the server thread once they settle (bell or home remembered, the village structure found around
 * them), two real conversations (the first villager founds a Mob Talk village keyed by the CTOV structure, the second joins the
 * same one) and a villagers' wedding at a CTOV bell on fallback texts: the bell of the same village, the ceremony on the ground at
 * its foot, the guests on the square and not on roofs, a marriage at the end. Screenshots go to screenshots/mobtalk-ctov-*.png. */
final class SelfTestCtov {
    private final SelfTest test;
    private final UUID cow;
    private final int homeX, homeY, homeZ;
    private Runnable next = () -> { };
    private BlockPos spot;
    private UUID first, second;
    private String siteKey = "";
    private int before;
    private int savedWeddingChance = -1;
    private int settleTicks;

    SelfTestCtov(SelfTest test, UUID cow, int stageX, int stageY, int stageZ) {
        this.test = test; this.cow = cow;
        this.homeX = stageX + 3; this.homeY = stageY; this.homeZ = stageZ;
    }

    /** What the server thread read about the village's residents. */
    private record Residents(int all, int settled, int belled, UUID first, UUID second, String site, BlockPos bell, String bellSite, UUID groom, UUID bride) { }
    /** Where the groom's wedding would stand, read on the server thread, with what was around it. */
    private record Place(WeddingController.Venue venue, String venueSite, int bellsAround) { }

    void run(Runnable then) {
        next = then;
        ServerWorld world = world();
        boolean installed = world.getRegistryManager().get(RegistryKeys.STRUCTURE).getKeys().stream().anyMatch(key -> key.getValue().getNamespace().equals("ctov"));
        if (!installed) { test.log("CTOV is not installed: CTOV village stages skipped"); then.run(); return; }
        test.log("CTOV stages: a CTOV village from the registry, its villagers, two conversations, a wedding at its bell");
        CompletableFuture<BlockPos> located = test.bridge().onServer(() -> StructureCapture.villageHouseSpot(world, test.serverPlayer().getBlockPos(), 100, "ctov"));
        test.await("a CTOV village located", 120, located::isDone, () -> {
            spot = located.isCompletedExceptionally() ? null : located.join();
            if (spot == null) { test.fail("no CTOV village house within 1600 blocks"); back(); return; }
            test.log("CTOV village house spot " + spot.toShortString());
            test.command("time set 1000"); test.command("weather clear");
            test.command("effect give " + player() + " minecraft:slow_falling 900 1 true");
            // Player and cow move together, as in the place stage: the stage up in the sky unloads once the player is gone, and an
            // unloaded cow can no longer be teleported.
            test.command("tp " + player() + " " + (spot.getX() + 2) + " " + spot.getY() + " " + spot.getZ());
            test.command("tp " + cow + " " + spot.getX() + " " + spot.getY() + " " + spot.getZ());
            test.await("pause: the CTOV village loads around the player", 12, () -> false, () -> {
                test.command("tp " + cow + " " + spot.getX() + " " + spot.getY() + " " + spot.getZ());
                test.command("tp " + player() + " " + (spot.getX() + 2) + " " + spot.getY() + " " + spot.getZ() + " facing entity " + cow + " eyes");
                test.await("pause: the cow in a CTOV house", 3, () -> false, () -> shot("mobtalk-ctov-house.png", this::house));
            });
        });
    }

    private String player() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }

    private void shot(String file, Runnable then) {
        ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
        test.await("pause: after the screenshot", 1, () -> false, then);
    }

    private void house() {
        ServerWorld world = world();
        CompletableFuture<MobSnapshot> captured = test.bridge().capture(cow, test.owner.client.player.getUuid(), world.getRegistryKey().getValue().toString());
        test.await("snapshot in a CTOV house", 20, captured::isDone, () -> {
            if (captured.isCompletedExceptionally()) test.fail("capture failed in the CTOV house: " + captured.exceptionNow().getMessage());
            else {
                MobSnapshot snapshot = captured.join();
                test.log("CTOV place block: " + PlacePrompt.block(snapshot).replace('\n', ' '));
                Map<?, ?> place = snapshot.environmentDetails().get("place") instanceof Map<?, ?> map ? map : Map.of();
                String structures = place.get("structures") instanceof List<?> list ? String.join(" | ", list.stream().map(String::valueOf).toList()) : "";
                test.check(structures.contains("деревня"), "the registry names the CTOV village in Russian: " + structures);
                test.check(!structures.contains("ctov") && !structures.contains("village_") && !structures.contains("/"), "no raw CTOV ids in the facts");
            }
            overview(() -> {
                // Back down among the houses: the villagers of a freshly generated village take a while to find their beds and bells.
                test.command("tp " + player() + " " + (spot.getX() + 2) + " " + spot.getY() + " " + spot.getZ());
                settleTicks = 0;
                test.await("the CTOV villagers settle (bells, or homes after a minute)", 150, this::residentsReady, this::residents);
            });
        });
    }

    /** A look over the village from above its centre, for the eyes of whoever reads the run. */
    private void overview(Runnable then) {
        CompletableFuture<ConversationStore.Site> site = test.bridge().onServer(() -> VillageSites.at(world(), spot));
        test.await("the CTOV village centre", 10, site::isDone, () -> {
            ConversationStore.Site s = site.isCompletedExceptionally() ? null : site.join();
            int cx = s == null ? spot.getX() : s.x(), cz = s == null ? spot.getZ() : s.z();
            test.command("tp " + player() + " " + cx + " " + (spot.getY() + 45) + " " + (cz - 45) + " facing " + cx + " " + spot.getY() + " " + cz);
            test.await("pause: above the CTOV village", 8, () -> false, () -> shot("mobtalk-ctov-village.png", then));
        });
    }

    /** Adult villagers around the house, those remembering a bell first, then the nearest. Server thread only. */
    private List<VillagerEntity> villagers(ServerWorld world) {
        List<VillagerEntity> found = new ArrayList<>(world.getEntitiesByClass(VillagerEntity.class, Box.of(spot.toCenterPos(), 192, 96, 192),
                v -> v.isAlive() && !v.isBaby()));
        found.sort(Comparator.comparing((VillagerEntity v) -> !belled(v)).thenComparingDouble(v -> v.getBlockPos().getSquaredDistance(spot)));
        return found;
    }
    private static boolean belled(VillagerEntity v) { return v.getBrain().hasMemoryModule(MemoryModuleType.MEETING_POINT); }
    private static boolean settled(VillagerEntity v) { return belled(v) || v.getBrain().hasMemoryModule(MemoryModuleType.HOME); }
    private CompletableFuture<long[]> counting;
    /** Two villagers remember a bell, or a minute went by and two remember at least a home (a CTOV village may have few bells). */
    private boolean residentsReady() {
        settleTicks++;
        if (counting == null) counting = test.bridge().onServer(() -> {
            List<VillagerEntity> all = villagers(world());
            return new long[]{all.stream().filter(SelfTestCtov::belled).count(), all.stream().filter(SelfTestCtov::settled).count()};
        });
        if (!counting.isDone()) return false;
        long[] counts = counting.isCompletedExceptionally() ? new long[]{0, 0} : counting.join();
        counting = null;
        return counts[0] >= 2 || settleTicks > 60 * 20 && counts[1] >= 2;
    }

    private Residents read(ServerWorld world) {
        List<VillagerEntity> all = villagers(world);
        List<VillagerEntity> settled = all.stream().filter(SelfTestCtov::settled).toList();
        int belled = (int) all.stream().filter(SelfTestCtov::belled).count();
        if (settled.isEmpty()) return new Residents(all.size(), 0, belled, null, null, "", null, "", null, null);
        VillagerEntity a = settled.getFirst();
        ConversationStore.Site site = VillageSites.of(world, a);
        String key = site == null ? "" : site.key();
        List<VillagerEntity> neighbours = settled.stream().filter(v -> { ConversationStore.Site s = VillageSites.of(world, v); return s != null && s.key().equals(key); }).toList();
        VillagerEntity b = neighbours.stream().filter(v -> v != a).findFirst().orElse(null);
        BlockPos bell = WeddingController.rememberedBell(world, a);
        ConversationStore.Site bellSite = bell == null ? null : VillageSites.at(world, bell);
        VillagerEntity groom = neighbours.stream().filter(v -> SocialProfile.genderOf(v.getUuid()) == Gender.MALE
                && test.bridge().memory.social().household(v.getUuid()).spouse() == null).findFirst().orElse(null);
        VillagerEntity bride = neighbours.stream().filter(v -> SocialProfile.genderOf(v.getUuid()) == Gender.FEMALE
                && test.bridge().memory.social().household(v.getUuid()).spouse() == null).findFirst().orElse(null);
        return new Residents(all.size(), settled.size(), belled, a.getUuid(), b == null ? null : b.getUuid(), key, bell, bellSite == null ? "" : bellSite.key(),
                groom == null ? null : groom.getUuid(), bride == null ? null : bride.getUuid());
    }

    private void residents() {
        CompletableFuture<Residents> read = test.bridge().onServer(() -> read(world()));
        test.await("CTOV residents read on the server", 10, read::isDone, () -> {
            if (read.isCompletedExceptionally()) { test.fail("reading the CTOV residents failed: " + read.exceptionNow().getMessage()); back(); return; }
            Residents r = read.join();
            test.log("CTOV villagers near the house: " + r.all() + ", with a bell or home " + r.settled() + ", with a bell " + r.belled());
            test.check(r.all() > 0, "the CTOV village has villagers");
            if (r.first() == null) { test.fail("no CTOV villager remembers a bell or home"); back(); return; }
            test.check(r.site().startsWith("ctov:"), "the village structure is found around the villager: " + (r.site().isEmpty() ? "none" : r.site()));
            siteKey = r.site();
            test.check(r.second() != null, "a second villager of the same CTOV village");
            if (r.bell() != null) test.check(r.bellSite().equals(siteKey), "the bell the villager remembers stands in the same CTOV village (" + r.bellSite() + ")");
            else test.log("the villager remembers no bell (CTOV villages may have few): the village is found by its home");
            if (r.second() == null) { back(); return; }
            first = r.first(); second = r.second();
            talk(first, "Здравствуй! Как называется ваша деревня и давно ты тут живёшь?", "mobtalk-ctov-talk-1.png", () -> {
                String village = test.bridge().memory.villageOf(first);
                ConversationStore.Village known = test.bridge().memory.village(village);
                test.check(known != null, "the first CTOV villager founded or joined a Mob Talk village: " + (known == null ? "none" : known.name()));
                test.check(known != null && known.site().equals(siteKey), "the Mob Talk village is keyed by the CTOV structure (" + (known == null ? "" : known.site()) + ")");
                talk(second, "Привет! Ты тоже из этой деревни?", "mobtalk-ctov-talk-2.png", () -> {
                    String other = test.bridge().memory.villageOf(second);
                    test.check(!village.isBlank() && village.equals(other), "the second villager of the same CTOV village joins the same Mob Talk village (" + village + " / " + other + ")");
                    wedding(r, known);
                });
            });
        });
    }

    /** One real conversation turn with a villager, the player standing beside it and facing it. */
    private void talk(UUID mob, String line, String screenshot, Runnable after) {
        CompletableFuture<Vec3d> at = test.bridge().onServer(() -> world().getEntity(mob) instanceof VillagerEntity v && v.isAlive() ? v.getPos() : null);
        test.await("the CTOV villager found", 10, at::isDone, () -> {
            Vec3d pos = at.isCompletedExceptionally() ? null : at.join();
            if (pos == null) { test.fail("the CTOV villager is gone"); back(); return; }
            test.command("tp " + player() + " " + (pos.x + 2) + " " + pos.y + " " + pos.z + " facing entity " + mob + " eyes");
            test.bridge().attend(mob, test.owner.client.player.getUuid(), 1200);
            test.await("pause: beside the CTOV villager", 3, () -> false, () -> {
                MobEntity target = MobSpeechSound.find(test.owner.world, mob);
                if (target == null) { test.fail("the CTOV villager is not on the client"); back(); return; }
                test.owner.select(target);
                before = turns(mob);
                test.check(test.owner.say(line), "say() accepted for the CTOV villager");
                test.await("CTOV villager replied", 120, () -> turns(mob) > before && !test.owner.busyTalking(), () -> {
                    MobCard card = test.bridge().memory.card(mob, test.owner.client.player.getUuid());
                    test.check(turns(mob) > before, "CTOV villager reply committed: " + (card == null || card.turns().isEmpty() ? "(none)" : card.turns().getLast().assistant()));
                    shot(screenshot, after);
                });
            });
        });
    }
    private int turns(UUID mob) { MobCard card = test.bridge().memory.card(mob, test.owner.client.player.getUuid()); return card == null ? 0 : card.turns().size(); }

    /** Two villagers of the CTOV village marry as a scene at its bell, on fallback texts (fast, no network). */
    private void wedding(Residents r, ConversationStore.Village village) {
        if (r.groom() == null || r.bride() == null) { test.log("no unmarried man and woman among the settled CTOV villagers: its wedding is skipped"); back(); return; }
        CompletableFuture<Vec3d> groomAt = test.bridge().onServer(() -> world().getEntity(r.groom()) instanceof VillagerEntity v && v.isAlive() ? v.getPos() : null);
        test.await("the CTOV groom found", 10, groomAt::isDone, () -> {
            Vec3d at = groomAt.isCompletedExceptionally() ? null : groomAt.join();
            if (at == null) { test.fail("the CTOV groom is gone"); back(); return; }
            // Stand by the groom so the chunks around his bells are loaded: the venue is never read from a chunk still loading.
            test.command(String.format(Locale.ROOT, "tp %s %.1f %.1f %.1f", player(), at.x + 2, at.y, at.z));
            test.await("pause: the area around the CTOV groom loads", 6, () -> false, () -> venue(r, village));
        });
    }

    private void venue(Residents r, ConversationStore.Village village) {
        CompletableFuture<Place> read = test.bridge().onServer(() -> {
            ServerWorld world = world();
            if (!(world.getEntity(r.groom()) instanceof VillagerEntity groom)) return new Place(null, "", 0);
            int bells = (int) world.getPointOfInterestStorage().getInCircle(type -> type.matchesKey(PointOfInterestTypes.MEETING), groom.getBlockPos(), 96,
                    PointOfInterestStorage.OccupationStatus.ANY).count();
            WeddingController.Venue venue = test.bridge().wedding.venue(world, groom);
            ConversationStore.Site site = venue == null ? null : VillageSites.at(world, venue.bell());
            return new Place(venue, site == null ? "" : site.key(), bells);
        });
        test.await("the CTOV wedding venue read on the server", 10, read::isDone, () -> {
            Place place = read.isCompletedExceptionally() ? new Place(null, "", 0) : read.join();
            WeddingController.Venue venue = place.venue();
            test.check(venue != null, "the wedding finds a bell for the CTOV groom (bells within 96 blocks: " + place.bellsAround() + ")");
            if (venue == null) { back(); return; }
            test.log("CTOV wedding venue: bell " + venue.bell().toShortString() + ", ground at its foot " + BlockPos.ofFloored(venue.plaza()).toShortString()
                    + ", open cells under the sky " + venue.openness() + ", walkable " + venue.walkable().size() + ", bells within 96 blocks " + place.bellsAround());
            test.check(place.venueSite().equals(siteKey), "the wedding bell belongs to the same CTOV village (" + place.venueSite() + ")");
            test.check(venue.openness() >= 20, "the wedding square is open enough for guests (" + venue.openness() + " cells)");
            savedWeddingChance = test.bridge().config.weddingChance;
            test.bridge().config.weddingChance = 0;
            test.command("time set 1000");
            Vec3d plaza = venue.plaza();
            test.command(String.format(Locale.ROOT, "tp %s %.1f %.1f %.1f facing %.1f %.1f %.1f", player(), plaza.x + 6, plaza.y, plaza.z + 6, plaza.x, plaza.y + 1, plaza.z));
            WeddingFaults.llm = true; WeddingFaults.tts = true;
            test.await("pause: the player at the CTOV bell", 3, () -> false, () -> marry(r, village, venue));
        });
    }

    private void marry(Residents r, ConversationStore.Village village, WeddingController.Venue venue) {
        CompletableFuture<Boolean> began = test.bridge().onServer(() -> test.bridge().wedding.startVillageForTest(world(), r.groom(), r.bride(), test.serverPlayer()));
        test.await("the CTOV wedding starts", 10, began::isDone, () -> {
            test.check(!began.isCompletedExceptionally() && began.join(), "two CTOV villagers marry as a scene at their bell");
            WeddingController.Scene scene = test.bridge().wedding.scene();
            if (scene == null) { back(); return; }
            test.check(scene.bell() != null, "the CTOV wedding has a bell: " + (scene.bell() == null ? "none" : scene.bell().toShortString()));
            double fromBell = scene.bell() == null ? 99 : scene.anchor().subtract(Vec3d.ofBottomCenter(scene.bell())).horizontalLength();
            test.check(fromBell <= 10, "the square is by the bell (" + String.format(Locale.ROOT, "%.1f", fromBell) + " blocks), at " + BlockPos.ofFloored(scene.anchor()).toShortString());
            test.check(scene.seatSpots().size() >= 4, "places were found for the couple, the officiant and the guests (" + scene.seatSpots().size() + ")");
            long level = scene.seatSpots().stream().filter(seat -> Math.abs(seat.y - scene.anchor().y) <= 2).count();
            test.check(level * 10 >= scene.seatSpots().size() * 8L, "the guests stand on the square's level: " + level + " of " + scene.seatSpots().size() + " places within 2 blocks of it");
            String named = scene.input.names().village();
            test.check(village == null || village.name().equals(named), "the wedding is in the Mob Talk village of this CTOV village: " + named);
            CompletableFuture<long[]> ground = test.bridge().onServer(() -> {
                ServerWorld world = world();
                ConversationStore.Site bellSite = scene.bell() == null ? null : VillageSites.at(world, scene.bell());
                long flowers = 0, buried = 0;
                for (UUID id : scene.archFlowers()) {
                    if (!(world.getEntity(id) instanceof net.minecraft.entity.Entity flower)) continue;
                    flowers++;
                    BlockPos cell = flower.getBlockPos();
                    if (!world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()) buried++;
                }
                return new long[]{scene.seatSpots().stream().filter(seat -> WeddingController.solidUnder(world, BlockPos.ofFloored(seat))).count(),
                        scene.seatSpots().size(), bellSite != null && bellSite.key().equals(siteKey) ? 1 : 0, flowers, buried};
            });
            test.await("the CTOV wedding places read on the server", 10, ground::isDone, () -> {
                long[] counts = ground.isCompletedExceptionally() ? new long[]{0, 1, 0, 0, 1} : ground.join();
                test.check(counts[0] * 10 >= counts[1] * 9, "no one stands on a roof: " + counts[0] + " of " + counts[1] + " places are on the ground");
                test.check(counts[2] == 1, "the wedding bell belongs to this CTOV village");
                test.check(counts[3] >= 9 && counts[4] == 0, "the flower arch stands free: " + counts[3] + " of 13 flowers, none inside a block (" + counts[4] + ")");
                test.await("the CTOV wedding service", 150, () -> test.bridge().wedding.scene() == null
                        || test.bridge().wedding.scene().ceremony.phase() == Wedding.Ceremony.Phase.SERVICE, () -> {
                    WeddingController.Scene running = test.bridge().wedding.scene();
                    test.check(running != null, "the guests gathered at the CTOV bell and the service began");
                    if (running != null) {
                        // From the guests' side, a little above them: the couple under the arch and the officiant facing them.
                        // A guest's place far from the couple, raised a little: on the square, never inside a house.
                        Vec3d couple = running.couple(), eye = running.seatSpots().stream().filter(p -> p.distanceTo(couple) <= 9)
                                .max(java.util.Comparator.comparingDouble(p -> p.distanceTo(couple))).orElse(couple.add(running.forward().multiply(5))).add(0, 2.2, 0);
                        test.command(String.format(Locale.ROOT, "tp %s %.1f %.1f %.1f facing %.1f %.1f %.1f", player(), eye.x, eye.y, eye.z, couple.x, couple.y + 1, couple.z));
                    }
                    test.await("pause: facing the CTOV wedding", 3, () -> false, () -> shot("mobtalk-ctov-wedding.png", () ->
                            test.await("the CTOV villagers are married", 240, () -> test.bridge().wedding.scene() == null, () -> {
                                test.check(r.bride().equals(test.bridge().memory.social().household(r.groom()).spouse()), "the CTOV wedding ended in a marriage");
                                back();
                            })));
                });
            });
        });
    }

    private void back() {
        if (savedWeddingChance >= 0) test.bridge().config.weddingChance = savedWeddingChance;
        WeddingFaults.clear();
        test.command("tp " + cow + " " + homeX + " " + homeY + " " + homeZ);
        test.command("tp " + player() + " " + (homeX - 3) + " " + homeY + " " + homeZ);
        test.await("pause: back on the stage", 3, () -> false, next);
    }
}
