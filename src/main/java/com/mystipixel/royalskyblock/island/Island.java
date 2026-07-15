package com.mystipixel.royalskyblock.island;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-memory model of one island. Persisted metadata (owner, members, level, upgrades, home)
 * maps to the {@code islands} / {@code island_members} tables; the island's blocks live in the ASP
 * slime data-source, not here.
 *
 * <p>Home coordinates are stored relative to the island world's own origin, so an island can be
 * migrated or re-pasted without rewriting its home.
 */
public final class Island {

    private final UUID id;
    private UUID owner;
    private final String worldName;
    private final long createdAt;

    private int radius;
    private double level;

    // Home, in island-world coordinates.
    private double homeX, homeY, homeZ;
    private float homeYaw, homePitch;

    private final Map<UUID, IslandMember> members = new ConcurrentHashMap<>();

    /** Upgrade tier per upgrade key (e.g. "size" -> 3). Populated in Phase 3. */
    private final Map<String, Integer> upgrades = new ConcurrentHashMap<>();

    public Island(UUID id, UUID owner, String worldName, long createdAt) {
        this.id = id;
        this.owner = owner;
        this.worldName = worldName;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
    }

    public String worldName() {
        return worldName;
    }

    public long createdAt() {
        return createdAt;
    }

    public int radius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = radius;
    }

    public double level() {
        return level;
    }

    public void setLevel(double level) {
        this.level = level;
    }

    // ── Members ───────────────────────────────────────────────────────────────

    public Collection<IslandMember> members() {
        return members.values();
    }

    public @Nullable IslandMember member(UUID uuid) {
        return members.get(uuid);
    }

    public boolean isMember(UUID uuid) {
        return members.containsKey(uuid);
    }

    public void putMember(IslandMember member) {
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
        IslandMember m = members.get(uuid);
        return m != null ? m.role() : IslandRole.VISITOR;
    }

    // ── Upgrades (Phase 3) ──────────────────────────────────────────────────────

    public int upgradeTier(String key) {
        return upgrades.getOrDefault(key, 0);
    }

    public void setUpgradeTier(String key, int tier) {
        upgrades.put(key, tier);
    }

    public Map<String, Integer> upgrades() {
        return new HashMap<>(upgrades);
    }

    // ── Home ────────────────────────────────────────────────────────────────────

    public void setHome(double x, double y, double z, float yaw, float pitch) {
        this.homeX = x;
        this.homeY = y;
        this.homeZ = z;
        this.homeYaw = yaw;
        this.homePitch = pitch;
    }

    public double homeX() { return homeX; }
    public double homeY() { return homeY; }
    public double homeZ() { return homeZ; }
    public float homeYaw() { return homeYaw; }
    public float homePitch() { return homePitch; }

    /**
     * Resolve the home as a live Bukkit {@link Location}, or {@code null} if the island world is not
     * currently loaded. Callers should load the island world first (see IslandWorldService).
     */
    public @Nullable Location homeLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, homeX, homeY, homeZ, homeYaw, homePitch);
    }
}
