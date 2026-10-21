package io.github.mobtalk.client;

import io.github.mobtalk.client.game.StructureCapture;
import io.github.mobtalk.client.game.VillageSites;
import io.github.mobtalk.client.game.WeddingController;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.Gender;
import io.github.mobtalk.core.MobCard;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.PlacePrompt;
import io.github.mobtalk.core.SocialProfile;
import io.github.mobtalk.core.WeddingFaults;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

/** Self-test stages for a village of CTOV (ChoiceTheorem's Overhauled Village), skipped when that mod is not installed: the nearest
 * CTOV village located through the registry, the cow taken into one of its houses (the structure facts name it in Russian, no raw
 * ids), its villagers read on the server thread (bell and home remembered, the village structure found around them, the wedding
 * bell found in the same structure), two real conversations (the first villager founds a Mob Talk village keyed by the CTOV
 * structure, the second joins the same one) and a villagers' wedding at the CTOV bell on fallback texts. Screenshots go to
 * screenshots/mobtalk-ctov-*.png. */
final class SelfTestCtov {
    private final SelfTest test;
    private final UUID cow;
    private final int homeX, homeY, homeZ;
    private Runnable next = () -> { };
    private BlockPos spot, bell;
    private UUID first, second;
    private String siteKey = "";
    private int before;
    private int savedWeddingChance = -1;

    SelfTestCtov(SelfTest test, UUID cow, int stageX, int stageY, int stageZ) {
        this.test = test; this.cow = cow;
        this.homeX = stageX + 3; this.homeY = stageY; this.homeZ = stageZ;
    }

    /** What the server thread read about the village's residents. */
    private record Residents(int all, int settled, UUID first, UUID second, String site, BlockPos bell, String bellSite, UUID groom, UUID bride) { }

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
            test.command("effect give " + player() + " minecraft:slow_falling 600 1 true");
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

    /** A look over the village from above its centre, for the eyes of whoever reads the run. */
    private void overview(Runnable then) {
        CompletableFuture<ConversationStore.Site> site = test.bridge().onServer(() -> VillageSites.at(world(), spot));
        test.await("the CTOV village centre", 10, site::isDone, () -> {
            ConversationStore.Site s = site.isCompletedExceptionally() ? null : site.join();
            int cx = s == null ? spot.getX() : s.x(), cz = s == null ? spot.getZ() : s.z();
            test.command("tp " + player() + " " + cx + " " + (spot.getY() + 45) + " " + (cz - 45) + " facing " + cx + " " + spot.getY() + " " + cz);
            test.await("pause: above the CTOV village", 4, () -> false, () -> shot("mobtalk-ctov-village.png", then));
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
            overview(() -> test.await("two CTOV villagers remember their bell or home", 120, this::residentsReady, this::residents));
        });
    }

    /** Adult villagers around the house, nearest first. Server thread only. */
    private List<VillagerEntity> villagers(ServerWorld world) {
        List<VillagerEntity> found = new ArrayList<>(world.getEntitiesByClass(VillagerEntity.class, Box.of(spot.toCenterPos(), 192, 96, 192),
                v -> v.isAlive() && !v.isBaby()));
        found.sort(Comparator.comparingDouble(v -> v.getBlockPos().getSquaredDistance(spot)));
        return found;
    }
    private static boolean settled(VillagerEntity v) {
        return v.getBrain().hasMemoryModule(MemoryModuleType.MEETING_POINT) || v.getBrain().hasMemoryModule(MemoryModuleType.HOME);
    }
    private CompletableFuture<Long> settledCount;
    private boolean residentsReady() {
        if (settledCount == null) settledCount = test.bridge().onServer(() -> villagers(world()).stream().filter(SelfTestCtov::settled).count());
        if (!settledCount.isDone()) return false;
        boolean ready = !settledCount.isCompletedExceptionally() && settledCount.join() >= 2;
        settledCount = null;
        return ready;
    }

    private Residents read(ServerWorld world) {
        List<VillagerEntity> all = villagers(world);
        List<VillagerEntity> settled = all.stream().filter(SelfTestCtov::settled).toList();
        if (settled.isEmpty()) return new Residents(all.size(), 0, null, null, "", null, "", null, null);
        VillagerEntity a = settled.getFirst();
        ConversationStore.Site site = VillageSites.of(world, a);
        String key = site == null ? "" : site.key();
        List<VillagerEntity> neighbours = settled.stream().filter(v -> { ConversationStore.Site s = VillageSites.of(world, v); return s != null && s.key().equals(key); }).toList();
        VillagerEntity b = neighbours.stream().filter(v -> v != a).findFirst().orElse(null);
        BlockPos bell = WeddingController.bell(world, a);
        ConversationStore.Site bellSite = bell == null ? null : VillageSites.at(world, bell);
        VillagerEntity groom = neighbours.stream().filter(v -> SocialProfile.genderOf(v.getUuid()) == Gender.MALE
                && test.bridge().memory.social().household(v.getUuid()).spouse() == null).findFirst().orElse(null);
        VillagerEntity bride = neighbours.stream().filter(v -> SocialProfile.genderOf(v.getUuid()) == Gender.FEMALE
                && test.bridge().memory.social().household(v.getUuid()).spouse() == null).findFirst().orElse(null);
        return new Residents(all.size(), settled.size(), a.getUuid(), b == null ? null : b.getUuid(), key, bell, bellSite == null ? "" : bellSite.key(),
                groom == null ? null : groom.getUuid(), bride == null ? null : bride.getUuid());
    }

    private void residents() {
        CompletableFuture<Residents> read = test.bridge().onServer(() -> read(world()));
        test.await("CTOV residents read on the server", 10, read::isDone, () -> {
            if (read.isCompletedExceptionally()) { test.fail("reading the CTOV residents failed: " + read.exceptionNow().getMessage()); back(); return; }
            Residents r = read.join();
            test.log("CTOV villagers near the house: " + r.all() + ", settled " + r.settled());
            test.check(r.all() > 0, "the CTOV village has villagers");
            if (r.first() == null) { test.fail("no CTOV villager remembers a bell or home"); back(); return; }
            test.check(r.site().startsWith("ctov:"), "the village structure is found around the villager: " + (r.site().isEmpty() ? "none" : r.site()));
            siteKey = r.site();
            test.check(r.second() != null, "a second villager of the same CTOV village");
            test.check(r.bell() != null, "the wedding finds the CTOV village bell: " + (r.bell() == null ? "none" : r.bell().toShortString()));
            test.check(r.bell() == null || r.bellSite().equals(siteKey), "the bell stands in the same CTOV village (" + r.bellSite() + ")");
            bell = r.bell();
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
        if (r.groom() == null || r.bride() == null || bell == null) { test.log("no unmarried man and woman or no bell in the CTOV village: its wedding is skipped"); back(); return; }
        savedWeddingChance = test.bridge().config.weddingChance;
        test.bridge().config.weddingChance = 0;
        test.command("time set 1000");
        test.command("tp " + player() + " " + (bell.getX() + 6) + " " + bell.getY() + " " + (bell.getZ() + 6) + " facing " + bell.getX() + " " + bell.getY() + " " + bell.getZ());
        WeddingFaults.llm = true; WeddingFaults.tts = true;
        test.await("pause: the player at the CTOV bell", 3, () -> false, () -> {
            CompletableFuture<Boolean> began = test.bridge().onServer(() -> test.bridge().wedding.startVillageForTest(world(), r.groom(), r.bride(), test.serverPlayer()));
            test.await("the CTOV wedding starts", 10, began::isDone, () -> {
                test.check(!began.isCompletedExceptionally() && began.join(), "two CTOV villagers marry as a scene at their bell");
                WeddingController.Scene scene = test.bridge().wedding.scene();
                if (scene == null) { WeddingFaults.clear(); back(); return; }
                double off = scene.couple().subtract(Vec3d.ofBottomCenter(bell)).horizontalLength();
                test.check(off < 6, "the couple stands at the CTOV bell (" + String.format(java.util.Locale.ROOT, "%.1f", off) + " blocks)");
                String named = scene.input.names().village();
                test.check(village == null || village.name().equals(named), "the wedding is in the Mob Talk village of this CTOV village: " + named);
                test.await("the CTOV wedding service", 150, () -> test.bridge().wedding.scene() == null
                        || test.bridge().wedding.scene().ceremony.phase() == io.github.mobtalk.core.Wedding.Ceremony.Phase.SERVICE, () -> {
                    WeddingController.Scene running = test.bridge().wedding.scene();
                    test.check(running != null, "the guests gathered at the CTOV bell and the service began");
                    if (running != null) {
                        Vec3d eye = running.couple().add(running.forward().multiply(7)).add(0, 2, 0), couple = running.couple();
                        test.command(String.format(java.util.Locale.ROOT, "tp %s %.1f %.1f %.1f facing %.1f %.1f %.1f", player(), eye.x, eye.y, eye.z, couple.x, couple.y + 1, couple.z));
                    }
                    test.await("pause: facing the CTOV wedding", 3, () -> false, () -> shot("mobtalk-ctov-wedding.png", () ->
                            test.await("the CTOV villagers are married", 240, () -> test.bridge().wedding.scene() == null, () -> {
                                test.check(r.bride().equals(test.bridge().memory.social().household(r.groom()).spouse()), "the CTOV wedding ended in a marriage");
                                WeddingFaults.clear();
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
