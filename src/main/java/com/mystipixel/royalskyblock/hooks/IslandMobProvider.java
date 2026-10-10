package com.mystipixel.royalskyblock.hooks;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * A backend that spawns a configured mob by id at a location; a new mob plugin is supported by one
 * implementation. Implementations reference their own plugin's types and are only instantiated when
 * that plugin is present.
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
