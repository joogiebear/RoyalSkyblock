package com.mystipixel.royalskyblock.profile;

import com.mystipixel.royalskyblock.island.IslandRole;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player's profile, effectively a separate Skyblock save: one island, a coop roster, and its own
 * inventory, ender chest and progression. A player may have several and switch between them.
 */
public final class Profile {

    private final UUID id;
    private UUID owner;
    private String name;
    private Gamemode gamemode;
    private final long createdAt;

    // the profile's island id, or null until an island has been created for it
    private UUID islandId;

    // highest island level whose rewards this profile has been paid; survives the island being deleted
    private int rewardLevel;

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

    /** Reassign the profile owner (coop ownership transfer). Roles are updated separately by the caller. */
    public void setOwner(UUID owner) {
        this.owner = owner;
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

    public int rewardLevel() {
        return rewardLevel;
    }

    public void setRewardLevel(int rewardLevel) {
        this.rewardLevel = rewardLevel;
    }

    public @Nullable UUID islandId() {
        return islandId;
    }

    public void setIslandId(@Nullable UUID islandId) {
        this.islandId = islandId;
    }

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
