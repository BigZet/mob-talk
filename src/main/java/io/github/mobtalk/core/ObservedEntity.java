package io.github.mobtalk.core;

import java.util.Map;
import java.util.UUID;

public record ObservedEntity(String key, UUID id, String type, String name, double distance,
                             String direction, boolean visible, Map<String,Object> details) {
    public ObservedEntity { details = MobSnapshot.immutableDetails(details); }
}
