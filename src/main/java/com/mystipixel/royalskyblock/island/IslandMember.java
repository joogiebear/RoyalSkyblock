package com.mystipixel.royalskyblock.island;

import java.util.UUID;

/**
 * A single membership row on an island: who they are, their {@link IslandRole}, and when they joined.
 * The name is a cached last-known value for display (rosters must render offline members).
 */
public final class IslandMember {

    private final UUID uuid;
    private String name;
    private IslandRole role;
    private final long joinedAt;

    public IslandMember(UUID uuid, String name, IslandRole role, long joinedAt) {
        this.uuid = uuid;
        this.name = name;
        this.role = role;
        this.joinedAt = joinedAt;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public IslandRole role() {
        return role;
    }

    public void setRole(IslandRole role) {
        this.role = role;
    }

    /** Epoch millis when this member joined the island. */
    public long joinedAt() {
        return joinedAt;
    }
}
