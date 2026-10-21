package io.github.mobtalk.client;

import io.github.mobtalk.client.game.StructureCapture;
import io.github.mobtalk.client.game.VillageSites;
import io.github.mobtalk.client.game.WeddingController;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.MobCard;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.PlacePrompt;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** Self-test stages for a village of CTOV (ChoiceTheorem's Overhauled Village), skipped when that mod is not installed: the nearest
 * CTOV village located through the registry, the cow taken into one of its houses (the structure facts name it in Russian, no raw
 * ids), its villagers read (bell and home remembered, the village structure found around them, the wedding bell found), and two
 * real conversations: the first villager founds a Mob Talk village keyed by the CTOV structure, the second joins the same one. */
final class SelfTestCtov {
    private final SelfTest test;
    private final UUID cow;
    private final int homeX, homeY, homeZ;
    private Runnable next = () -> { };
    private BlockPos spot;
    private UUID first, second;
    private String siteKey = "";
    private int before;

    SelfTestCtov(SelfTest test, UUID cow, int stageX, int stageY, int stageZ) {
        this.test = test; this.cow = cow;
        this.homeX = stageX + 3; this.homeY = stageY; this.homeZ = stageZ;
    }

    void run(Runnable then) {
        next = then;
        ServerWorld world = world();
        boolean installed = world.getRegistryManager().get(RegistryKeys.STRUCTURE).getKeys().stream().anyMatch(key -> key.getValue().getNamespace().equals("ctov"));
        if (!installed) { test.log("CTOV is not installed: CTOV village stages skipped"); then.run(); return; }
        test.log("CTOV stages: a CTOV village from the registry, its villagers, two conversations");
        spot = StructureCapture.villageHouseSpot(world, test.serverPlayer().getBlockPos(), 100, "ctov");
        if (spot == null) { test.fail("no CTOV village house within 1600 blocks"); back(); return; }
        test.log("CTOV village house spot " + spot.toShortString());
        test.command("time set day");
        test.command("tp " + player() + " " + (spot.getX() + 3) + " " + (spot.getY() + 1) + " " + (spot.getZ() + 3));
        test.command("effect give " + player() + " minecraft:slow_falling 60 1 true");
        test.command("tp " + cow + " " + spot.getX() + " " + spot.getY() + " " + spot.getZ());
        test.await("pause: the CTOV village loads around the player", 10, () -> false, this::house);
    }

    private String player() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }

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
            test.await("a CTOV villager remembers its bell or home", 90, this::residentsReady, this::residents);
        });
    }

    /** Adult villagers of this village around the house, nearest first. */
    private List<VillagerEntity> villagers() {
        List<VillagerEntity> found = new ArrayList<>(world().getEntitiesByClass(VillagerEntity.class, Box.of(spot.toCenterPos(), 192, 96, 192),
                v -> v.isAlive() && !v.isBaby()));
        found.sort(Comparator.comparingDouble(v -> v.getBlockPos().getSquaredDistance(spot)));
        return found;
    }
    private static boolean settled(VillagerEntity v) {
        return v.getBrain().hasMemoryModule(MemoryModuleType.MEETING_POINT) || v.getBrain().hasMemoryModule(MemoryModuleType.HOME);
    }
    private boolean residentsReady() { return villagers().stream().filter(SelfTestCtov::settled).count() >= 2; }

    private void residents() {
        ServerWorld world = world();
        List<VillagerEntity> all = villagers();
        test.log("CTOV villagers near the house: " + all.size() + ", settled " + all.stream().filter(SelfTestCtov::settled).count());
        test.check(!all.isEmpty(), "the CTOV village has villagers");
        List<VillagerEntity> settled = all.stream().filter(SelfTestCtov::settled).toList();
        if (settled.size() < 2) { test.fail("fewer than two CTOV villagers remember a bell or home"); back(); return; }
        VillagerEntity a = settled.getFirst();
        ConversationStore.Site site = VillageSites.of(world, a);
        test.check(site != null && site.key().startsWith("ctov:"), "the village structure is found around the villager: " + (site == null ? "none" : site.key()));
        siteKey = site == null ? "" : site.key();
        VillagerEntity b = settled.stream().skip(1).filter(v -> { ConversationStore.Site s = VillageSites.of(world, v); return s != null && s.key().equals(siteKey); })
                .findFirst().orElse(null);
        test.check(b != null, "a second villager of the same CTOV village");
        BlockPos bell = WeddingController.bell(world, a);
        test.check(bell != null, "the wedding finds the CTOV village bell: " + (bell == null ? "none" : bell.toShortString()));
        if (bell != null) {
            ConversationStore.Site bellSite = VillageSites.at(world, bell);
            test.check(bellSite != null && bellSite.key().equals(siteKey), "the bell stands in the same CTOV village (" + (bellSite == null ? "none" : bellSite.key()) + ")");
        }
        if (b == null) { back(); return; }
        first = a.getUuid(); second = b.getUuid();
        talk(first, "Здравствуй! Как называется ваша деревня и давно ты тут живёшь?", () -> {
            String village = test.bridge().memory.villageOf(first);
            ConversationStore.Village known = test.bridge().memory.village(village);
            test.check(known != null, "the first CTOV villager founded or joined a Mob Talk village: " + (known == null ? "none" : known.name()));
            test.check(known != null && known.site().equals(siteKey), "the Mob Talk village is keyed by the CTOV structure (" + (known == null ? "" : known.site()) + ")");
            talk(second, "Привет, соседка или сосед! Ты из этой же деревни?", () -> {
                String other = test.bridge().memory.villageOf(second);
                test.check(!village.isBlank() && village.equals(other), "the second villager of the same CTOV village joins the same Mob Talk village (" + village + " / " + other + ")");
                back();
            });
        });
    }

    /** One real conversation turn with a villager, the player standing beside it. */
    private void talk(UUID mob, String line, Runnable after) {
        ServerWorld world = world();
        if (!(world.getEntity(mob) instanceof VillagerEntity villager)) { test.fail("the CTOV villager is gone"); back(); return; }
        test.command("tp " + player() + " " + (villager.getX() + 2) + " " + villager.getY() + " " + villager.getZ());
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
                after.run();
            });
        });
    }
    private int turns(UUID mob) { MobCard card = test.bridge().memory.card(mob, test.owner.client.player.getUuid()); return card == null ? 0 : card.turns().size(); }

    private void back() {
        test.command("tp " + cow + " " + homeX + " " + homeY + " " + homeZ);
        test.command("tp " + player() + " " + (homeX - 3) + " " + homeY + " " + homeZ);
        test.await("pause: back on the stage", 3, () -> false, next);
    }
}
