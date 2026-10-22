package io.github.mobtalk.client.human;

import io.github.mobtalk.core.ChildGrowth;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.Gender;
import io.github.mobtalk.core.SocialProfile;
import io.github.mobtalk.human.HumanGenes;
import io.github.mobtalk.game.WeddingItems;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Трек M: облик человека-жителя. Гены из UUID (у ребёнка пары — от родителей из дерева семьи), пол из социального профиля
 * (без профиля — тот же, что профиль дал бы), одежда по ванильной профессии, у малышей — детская. Ничего не пишется в мир:
 * старые миры и ванильные жители получают устойчивый облик сами. Данные мира приходят копией раз в полсекунды
 * ({@link #update}); рендер только читает. */
public final class HumanLooks {
    /** Люди вместо ванильных жителей (F7 → «Общее»); иначе ванильная модель. */
    public static volatile boolean enabled = true;
    private static volatile Map<UUID, ConversationStore.Lineage> lineages = Map.of();
    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();
    private static final int DEPTH = 4;

    private record Key(String type, String profession, boolean baby, ConversationStore.Lineage lineage, String outfit) { }
    private record Cached(Key key, HumanLook look) { }

    private HumanLooks() { }

    public static void update(Map<UUID, ConversationStore.Lineage> next) {
        if (!next.equals(lineages)) { lineages = Map.copyOf(next); CACHE.clear(); }
    }
    public static void clear() { lineages = Map.of(); CACHE.clear(); }

    public static HumanLook of(VillagerEntity villager) {
        UUID id = villager.getUuid();
        String type = Registries.VILLAGER_TYPE.getId(villager.getVillagerData().getType()).getPath();
        String profession = Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession()).getPath();
        // Свадебный убор на голове (трек L) — праздничный наряд: невеста, жених, подружка, дружка.
        String outfit = WeddingItems.outfit(villager.getEquippedStack(EquipmentSlot.HEAD));
        Key key = new Key(type, profession, villager.isBaby(), lineages.get(id), outfit);
        Cached cached = CACHE.get(id);
        if (cached != null && cached.key.equals(key)) return cached.look;
        HumanLook look = new HumanLook(genes(id, HumanGenes.temperature(type), DEPTH), gender(id), profession, age(villager.isBaby(), key.lineage), outfit);
        CACHE.put(id, new Cached(key, look));
        return look;
    }

    private static HumanGenes genes(UUID id, float temperature, int depth) {
        ConversationStore.Lineage lineage = lineages.get(id);
        List<UUID> parents = lineage == null ? List.of() : lineage.parents();
        if (depth <= 0 || parents.isEmpty()) return HumanGenes.of(id, temperature);
        HumanGenes mother = genes(parents.get(0), temperature, depth - 1);
        HumanGenes father = parents.size() > 1 ? genes(parents.get(1), temperature, depth - 1) : HumanGenes.of(new UUID(~id.getMostSignificantBits(), id.getLeastSignificantBits()), temperature);
        return HumanGenes.child(id, mother, father);
    }

    private static String gender(UUID id) {
        ConversationStore.Lineage lineage = lineages.get(id);
        Gender g = lineage == null ? SocialProfile.genderOf(id) : lineage.gender();
        return g == Gender.FEMALE ? "female" : "male";
    }

    private static HumanLook.Age age(boolean baby, ConversationStore.Lineage lineage) {
        if (!baby) return HumanLook.Age.ADULT;
        String stage = lineage == null ? "" : lineage.stage();
        return Objects.equals(stage, ChildGrowth.Stage.INFANT.name()) || Objects.equals(stage, ChildGrowth.Stage.TODDLER.name()) ? HumanLook.Age.TODDLER : HumanLook.Age.CHILD;
    }
}
