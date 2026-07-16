package com.mystipixel.royalskyblock.island;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-memory model of one island. An island belongs to a {@link com.mystipixel.royalskyblock.profile.Profile}
 * (not directly to a player) — the profile owns the roster, so membership/roles are resolved through
 * the profile. Persisted metadata maps to the {@code islands} table; the island's blocks live in the
 * ASP slime data-source.
 *
 * <p>Home coordinates are stored relative to the island world's own origin.
 */
public final class Island {

    private final UUID id;
    private UUID profileId;
    private final String worldName;
    private final long createdAt;

    private int radius;
    private double level;

    private double homeX, homeY, homeZ;
    private float homeYaw, homePitch;

    /** Upgrade tier per upgrade key (e.g. "size" -> 3). Populated in the upgrades phase. */
    private final Map<String, Integer> upgrades = new ConcurrentHashMap<>();

    public Island(UUID id, UUID profileId, String worldName, long createdAt) {
        this.id = id;
        this.profileId = profileId;
        this.worldName = worldName;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID profileId() {
        return profileId;
    }

    public void setProfileId(UUID profileId) {
        this.profileId = profileId;
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

    // ── upgrades ────────────────────────────────────────────────────────────────

    public int upgradeTier(String key) {
        return upgrades.getOrDefault(key, 0);
    }

    public void setUpgradeTier(String key, int tier) {
        upgrades.put(key, tier);
    }

    public Map<String, Integer> upgrades() {
        return new HashMap<>(upgrades);
    }

    // ── home ────────────────────────────────────────────────────────────────────

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

    /** Resolve the home as a live Bukkit {@link Location}, or {@code null} if the world isn't loaded. */
    public @Nullable Location homeLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, homeX, homeY, homeZ, homeYaw, homePitch);
    }
}
