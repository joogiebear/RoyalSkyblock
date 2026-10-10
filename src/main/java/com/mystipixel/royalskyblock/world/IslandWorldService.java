package com.mystipixel.royalskyblock.world;

import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * The island world backend. The rest of the plugin only sees Bukkit {@link World}s and names; the ASP
 * adapter ({@code world.asp.AspIslandWorldService}) is the only place that knows ASP's API.
 *
 * <p>Operations that hit the data source return a {@link CompletableFuture} that completes on an
 * unspecified thread; callers touching the Bukkit world must hop back to the main thread themselves.
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
     * Persist the loaded island world without unloading it, e.g. after pasting the starter. No-op if the
     * world is not loaded.
     */
    CompletableFuture<Void> saveIsland(String worldName);

    /**
     * Save an island world on the calling thread, bypassing the scheduler. Only for {@code onDisable},
     * where Bukkit refuses to schedule tasks so {@link #saveIsland} would lose the save. No-op if the world
     * is not loaded.
     */
    void saveIslandNow(String worldName);

    /**
     * Unload an island world, optionally persisting it back to the data source first. No-op if the
     * world is not currently loaded.
     */
    CompletableFuture<Void> unloadIsland(String worldName, boolean save);

    /** Permanently delete an island world from the data source. Unloads it first if loaded. */
    CompletableFuture<Void> deleteIsland(String worldName);

    /** Whether the named island world is currently loaded on this server. */
    boolean isLoaded(String worldName);

    /** The raw serialized bytes of a stored world, for the island trash. Blocking: call off the main thread. */
    byte[] exportWorld(String worldName) throws Exception;

    /**
     * Write raw serialized world bytes into the store under {@code worldName}, for restoring an archived
     * island. Blocking: call off the main thread.
     */
    void importWorld(String worldName, byte[] data) throws Exception;

    /**
     * Every world name in the store. Other features use the store too, so filter by your own prefix.
     * Blocking: call off the main thread.
     */
    java.util.List<String> listWorldNames() throws Exception;

    /** Release any resources (connection pools, registered loaders) on plugin disable. */
    void shutdown();
}
