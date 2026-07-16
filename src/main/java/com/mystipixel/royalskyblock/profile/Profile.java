package com.mystipixel.royalskyblock.profile;

import com.mystipixel.royalskyblock.island.IslandRole;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player's profile — effectively a separate Skyblock save. It owns one island, a coop roster, and
 * (via per-profile state) its own inventory, ender chest, and progression. A player may have several
 * (Solo, Coop, Ironman, ...) and switches between them.
 */
public final class Profile {

    private final UUID id;
    private final UUID owner;
    private String name;
    private Gamemode gamemode;
    private final long createdAt;

    /** The profile's island id, or {@code null} until an island has been created for it. */
    private UUID islandId;

    private final ConcurrentHashMap<UUID, ProfileMember> members = new ConcurrentHashMap<>();

    public Profile(UUID id, UUID owner, String name, Gamemode gamemode, long createdAt) {
        this.id = id;
        this.owner = owner;
        this.name = name;
        this.gamemode = gamemode;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Gamemode gamemode() {
        return gamemode;
    }

    public void setGamemode(Gamemode gamemode) {
        this.gamemode = gamemode;
    }

    public long createdAt() {
        return createdAt;
    }

    public @Nullable UUID islandId() {
        return islandId;
    }

    public void setIslandId(@Nullable UUID islandId) {
        this.islandId = islandId;
    }

    // ── members (coop roster) ────────────────────────────────────────────────────

    public Collection<ProfileMember> members() {
        return members.values();
    }

    public @Nullable ProfileMember member(UUID uuid) {
        return members.get(uuid);
    }

    public boolean isMember(UUID uuid) {
        return members.containsKey(uuid);
    }

    public void putMember(ProfileMember member) {
        members.put(member.uuid(), member);
    }

    public void removeMember(UUID uuid) {
        members.remove(uuid);
    }

    public int memberCount() {
        return members.size();
    }

    /** The member's role, or {@link IslandRole#VISITOR} if they are not on the roster. */
    public IslandRole roleOf(UUID uuid) {
        ProfileMember m = members.get(uuid);
        return m != null ? m.role() : IslandRole.VISITOR;
    }
}
