package io.github.mobtalk.core;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable game-thread capture; background conversations never touch Minecraft objects. */
public record MobSnapshot(UUID id, String type, String name, float health, float maxHealth,
        String dimension, String biome, String timeOfDay, String weather, double distance,
        String heldItem, List<String> nearby, List<String> recentEvents) {
    public MobSnapshot {
        Objects.requireNonNull(id, "id");
        nearby = nearby == null ? List.of() : List.copyOf(nearby);
        recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
    }
}
