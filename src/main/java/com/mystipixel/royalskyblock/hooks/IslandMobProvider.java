package com.mystipixel.royalskyblock.hooks;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * A pluggable backend that spawns a configured mob by id at a location. The island spawn service speaks
 * only to this interface, so a new mob plugin (LevelledMobs, MythicMobs) is added by writing one
 * implementation — no island-spawn logic changes. Each implementation references its own plugin's types
 * and is only instantiated when that plugin is present, so RoyalSkyblock loads fine without any of them.
 */
public interface IslandMobProvider {

    /** Stable id matching the {@code island-mobs.provider} config value (e.g. {@code "ecomobs"}). */
    String id();

    /** Whether this provider's plugin is installed and usable right now. */
    boolean available();

    /**
     * Spawn the provider mob with the given id at the location. Returns the spawned entity (so the
     * caller can tag it), or {@code null} if the id is unknown or the spawn failed. Must be called on
     * the main thread.
     */
    Entity spawn(String mobId, Location location);
}
