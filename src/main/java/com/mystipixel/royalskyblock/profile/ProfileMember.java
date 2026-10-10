package com.mystipixel.royalskyblock.profile;

import com.mystipixel.royalskyblock.island.IslandRole;

import java.util.UUID;

/**
 * A coop roster row: who, their {@link IslandRole}, and when they joined. The name is a cached
 * last-known value for display. A Solo/Ironman profile has just the owner.
 */
public final class ProfileMember {

    private final UUID uuid;
    private String name;
    private IslandRole role;
    private final long joinedAt;

    public ProfileMember(UUID uuid, String name, IslandRole role, long joinedAt) {
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

    public long joinedAt() {
        return joinedAt;
    }
}
