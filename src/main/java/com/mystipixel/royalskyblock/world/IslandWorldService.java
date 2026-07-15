package com.mystipixel.royalskyblock.world;

import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * The island world backend, abstracted away from any specific implementation.
 *
 * <p>The rest of the plugin speaks only in Bukkit {@link World}s and world names — it never touches
 * Advanced Slime Paper types. The one adapter that does ({@code world.asp.AspIslandWorldService})
 * is the sole place that knows ASP's API, so a change in ASP (or a future grid backend) is a
 * one-file change.
 *
 * <p>World read/write is inherently async in the slime format, so every operation that hits the
 * data source returns a {@link CompletableFuture}. Futures complete on an unspecified thread;
 * callers that then touch the Bukkit world must hop back onto the main thread themselves.
 */
public interface IslandWorldService {

    /**
     * Prepare the backend: connect the data source and register the slime loader. Called once on
     * enable, before any island load.
     */
    CompletableFuture<Void> initialize();

    /**
     * Create a brand-new, empty island world under {@code worldName} and load it into the server.
     * The caller is responsible for pasting the starter schematic afterwards.
     */
    CompletableFuture<World> createIsland(String worldName);

    /**
     * Load an already-existing island world from the data source into the server. Completes with the
     * live {@link World}. If it is already loaded, completes with the current instance.
     */
    CompletableFuture<World> loadIsland(String worldName);

    /**
     * Persist the currently-loaded island world back to the data source without unloading it. Used
     * after editing an island's blocks (e.g. pasting the starter schematic) so the change survives a
     * crash. No-op if the world is not loaded.
     */
    CompletableFuture<Void> saveIsland(String worldName);

    /**
     * Unload an island world, optionally persisting it back to the data source first. No-op if the
     * world is not currently loaded.
     */
    CompletableFuture<Void> unloadIsland(String worldName, boolean save);

    /**
     * Permanently delete an island world from the data source. Unloads it first if loaded.
     */
    CompletableFuture<Void> deleteIsland(String worldName);

    /** Whether the named island world is currently loaded on this server. */
    boolean isLoaded(String worldName);

    /** Release any resources (connection pools, registered loaders) on plugin disable. */
    void shutdown();
}
