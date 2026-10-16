package io.github.mobtalk.core;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

/** Immutable game-thread capture; background conversations never touch Minecraft objects. */
public record MobSnapshot(UUID id, String type, String name, float health, float maxHealth,
        String dimension, String biome, String timeOfDay, String weather, double distance,
        String heldItem, List<String> nearby, List<String> recentEvents,
        UUID playerId, Map<String,Object> playerDetails, Map<String,Object> mobDetails,
        Map<String,Object> environmentDetails, List<ObservedEntity> observedEntities,
        List<ActionType> availableActions, long interactionRevision) {
    public MobSnapshot {
        Objects.requireNonNull(id, "id");
        nearby = nearby == null ? List.of() : List.copyOf(nearby);
        recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
        playerDetails = immutableDetails(playerDetails);
        mobDetails = immutableDetails(mobDetails);
        environmentDetails = immutableDetails(environmentDetails);
        observedEntities = observedEntities == null ? List.of() : List.copyOf(observedEntities);
        availableActions = availableActions == null ? List.of(ActionType.NONE) : List.copyOf(availableActions);
    }

    public MobSnapshot(UUID id, String type, String name, float health, float maxHealth, String dimension,
                       String biome, String timeOfDay, String weather, double distance, String heldItem,
                       List<String> nearby, List<String> recentEvents) {
        this(id, type, name, health, maxHealth, dimension, biome, timeOfDay, weather, distance,
                heldItem, nearby, recentEvents, null, Map.of(), Map.of(), Map.of(), List.of(), List.of(ActionType.NONE), 0);
    }

    /** Recursively detach JSON-compatible structures so a worker cannot observe later game-thread mutations. */
    static Map<String,Object> immutableDetails(Map<String,Object> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String,Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> { if (key != null && value != null) copy.put(key, immutableValue(value)); });
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?,?> map) {
            Map<String,Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> { if (key != null && item != null) copy.put(key.toString(), immutableValue(item)); });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return list.stream().filter(Objects::nonNull).map(MobSnapshot::immutableValue).toList();
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof UUID id) return id.toString();
        throw new IllegalArgumentException("Snapshot details must be JSON values");
    }
}
