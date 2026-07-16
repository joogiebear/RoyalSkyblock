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

    // Guest spawn (where visitors land), in island-world coordinates. Unset -> visitors use home.
    private boolean hasGuestHome;
    private double guestX, guestY, guestZ;
    private float guestYaw, guestPitch;

    /** Upgrade tier per upgrade key (e.g. "size" -> 3). Populated in the upgrades phase. */
    private final Map<String, Integer> upgrades = new ConcurrentHashMap<>();

    /** Explicitly-set setting overrides (key -> enabled). Unset settings use their default. */
    private final Map<String, Boolean> settings = new ConcurrentHashMap<>();

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

    // ── settings ──────────────────────────────────────────────────────────────────

    public boolean isEnabled(IslandSetting setting) {
        return settings.getOrDefault(setting.key(), setting.defaultEnabled());
    }

    public void setSetting(IslandSetting setting, boolean enabled) {
        settings.put(setting.key(), enabled);
    }

    /** Serialize overrides as {@code key=1,key=0} for the {@code settings} column. */
    public String serializeSettings() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Boolean> e : settings.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue() ? '1' : '0');
        }
        return sb.toString();
    }

    /** Load overrides from the serialized {@code settings} column value. */
    public void loadSettings(String serialized) {
        settings.clear();
        if (serialized == null || serialized.isBlank()) {
            return;
        }
        for (String pair : serialized.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                settings.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim().equals("1"));
            }
        }
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

    // ── guest spawn ────────────────────────────────────────────────────────────────

    public boolean hasGuestHome() {
        return hasGuestHome;
    }

    public void setGuestHome(double x, double y, double z, float yaw, float pitch) {
        this.hasGuestHome = true;
        this.guestX = x;
        this.guestY = y;
        this.guestZ = z;
        this.guestYaw = yaw;
        this.guestPitch = pitch;
    }

    /** Where a visitor should land: the guest spawn if set, otherwise the island home. */
    public @Nullable Location guestOrHomeLocation() {
        if (!hasGuestHome) {
            return homeLocation();
        }
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, guestX, guestY, guestZ, guestYaw, guestPitch);
    }

    public String serializeGuestHome() {
        return hasGuestHome ? guestX + "," + guestY + "," + guestZ + "," + guestYaw + "," + guestPitch : "";
    }

    public void loadGuestHome(String serialized) {
        hasGuestHome = false;
        if (serialized == null || serialized.isBlank()) {
            return;
        }
        String[] p = serialized.split(",");
        if (p.length == 5) {
            try {
                setGuestHome(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                        Float.parseFloat(p[3]), Float.parseFloat(p[4]));
            } catch (NumberFormatException ignored) {
                hasGuestHome = false;
            }
        }
    }
}
