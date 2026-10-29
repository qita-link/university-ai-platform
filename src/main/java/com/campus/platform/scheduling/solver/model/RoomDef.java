package com.campus.platform.scheduling.solver.model;

import java.util.Set;

public record RoomDef(long id, String code, long campusId, int capacity, String roomType, Set<String> features) {
    public RoomDef {
        features = features == null ? Set.of() : Set.copyOf(features);
    }
}
