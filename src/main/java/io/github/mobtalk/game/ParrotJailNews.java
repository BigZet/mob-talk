package io.github.mobtalk.game;

import io.github.mobtalk.MobTalkMod;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.VillageRumors;
import io.github.mobtalk.server.AdminLink;
import io.github.mobtalk.server.web.AdminWeb;
import io.github.mobtalk.server.web.Json;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Тюрьма Parrot Cages глазами деревни и админки, без общего кода: подписка на общий список {@code parrotcages:jail_listeners}.
 * Арест и выход попугая-зрителя — новость деревни у клетки: слух на игровой день, его сразу знают {@link #WITNESSES} ближайших
 * жителя в {@link #HEARING} блоках, а дальше его разносят болтовня и встречи (выключается {@code villageParrotJokes}); и записи {@code parrot_jailed} / {@code parrot_released}
 * в истории админки. События приходят на потоке сервера. */
public final class ParrotJailNews {
    public static final String CAGED_TAG = "parrotcages.caged", JAILED_TAG = "parrotcages.jailed";
    static final String LISTENERS = "parrotcages:jail_listeners";
    /** Кто у клетки сразу слышит новость. */
    static final double HEARING = 32;
    /** Сколько жителей видели сами: остальные узнают от них, иначе болтовне нечего пересказывать. */
    static final int WITNESSES = 2;

    private ParrotJailNews() { }

    @SuppressWarnings("unchecked")
    public static void install() {
        FabricLoader.getInstance().getObjectShare().whenAvailable(LISTENERS, (key, value) -> {
            if (value instanceof List<?> list) ((List<Consumer<Map<String, Object>>>) list).add(ParrotJailNews::onEvent);
        });
    }

    static void onEvent(Map<String, Object> event) {
        if (!(event.get("world") instanceof ServerWorld world) || !(event.get("pos") instanceof BlockPos pos) || !(event.get("parrot") instanceof UUID parrot)) return;
        String kind = String.valueOf(event.get("event")), name = String.valueOf(event.getOrDefault("name", "попугай"));
        String reason = String.valueOf(event.getOrDefault("reason", "")), jailer = String.valueOf(event.getOrDefault("jailerName", ""));
        int minutes = event.get("minutes") instanceof Number n ? n.intValue() : -1;
        if ("extended".equals(kind) || "released".equals(kind) && "gone".equals(reason)) return;
        boolean jailed = "jailed".equals(kind);
        history(jailed, event.get("jailer") instanceof UUID id ? id : null, jailer, name, minutes, reason);
        MobGameBridge bridge = MobGameBridge.active(world.getServer());
        if (bridge == null || !bridge.config.villageParrotJokes) return;
        ConversationStore.Village village = bridge.villageAt(world, pos);
        if (village == null) return;
        List<UUID> hearers = world.getEntitiesByClass(VillagerEntity.class, new Box(pos).expand(HEARING), VillagerEntity::isAlive).stream()
                .sorted(java.util.Comparator.comparingDouble(v -> v.squaredDistanceTo(pos.toCenterPos()))).limit(WITNESSES).map(VillagerEntity::getUuid).toList();
        String prefix = "cage-" + parrot + "-";
        bridge.memory.villageNews(village.id(), prefix + (jailed ? "in" : "out"), prefix, jailed ? jailedText(name, minutes) : releasedText(name, reason),
                3, VillageRumors.DAY_MILLIS, hearers, System.currentTimeMillis());
    }

    static String jailedText(String name, int minutes) {
        return "стример посадил в клетку попугая зрителя " + name + (minutes > 0 ? " на " + minutes + " мин." : " без срока")
                + " — сидит за решёткой, как разбойник";
    }

    static String releasedText(String name, String reason) {
        return switch (reason) {
            case "term" -> "попугай " + name + " отсидел свой срок и вышел из клетки";
            case "amnesty" -> "зрители выпросили попугаю " + name + " амнистию, и его выпустили";
            case "escape" -> "попугай " + name + " сбежал из клетки — вот ловкач";
            case "broken" -> "клетку попугая " + name + " сломали, и он на свободе";
            default -> "попугая " + name + " выпустили из клетки";
        };
    }

    private static void history(boolean jailed, UUID jailerId, String jailer, String name, int minutes, String reason) {
        AdminWeb admin = AdminLink.web();
        if (admin == null) return;
        String text = jailed ? name + " в клетке" + (minutes > 0 ? " на " + minutes + " мин." : " без срока") + (jailer.isBlank() ? "" : " (" + jailer + ")")
                : name + " на свободе (" + reasonTitle(reason) + ")";
        try {
            admin.journal().event(jailed ? "parrot_jailed" : "parrot_released", jailerId == null && jailer.isBlank() ? null : Json.player(jailerId, jailer), null, text,
                    Json.obj("parrot", name, "minutes", minutes, "reason", jailed ? "" : reason));
        } catch (RuntimeException failure) { MobTalkMod.LOGGER.debug("Админка: событие клетки не записано ({})", failure.toString()); }
    }

    static String reasonTitle(String reason) {
        return switch (reason) {
            case "term" -> "срок вышел"; case "hands" -> "выпустили"; case "amnesty" -> "амнистия"; case "escape" -> "побег";
            case "admin" -> "администратор"; case "broken" -> "клетку сломали"; default -> reason;
        };
    }
}
