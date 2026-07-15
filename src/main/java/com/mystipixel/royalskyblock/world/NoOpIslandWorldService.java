package com.mystipixel.royalskyblock.world;

import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * Fallback world service used when the server is not running Advanced Slime Paper. Every world
 * operation fails fast with a clear message, so the plugin still enables (metadata, commands, config
 * all work) and island world actions explain exactly what's missing instead of throwing on class-load.
 */
public final class NoOpIslandWorldService implements IslandWorldService {

    private static final String MESSAGE =
            "The server is not running Advanced Slime Paper — island worlds are unavailable.";

    private static <T> CompletableFuture<T> unavailable() {
        return CompletableFuture.failedFuture(new IllegalStateException(MESSAGE));
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return unavailable();
    }

    @Override
    public CompletableFuture<World> createIsland(String worldName) {
        return unavailable();
    }

    @Override
    public CompletableFuture<World> loadIsland(String worldName) {
        return unavailable();
    }

    @Override
    public CompletableFuture<Void> saveIsland(String worldName) {
        return unavailable();
    }

    @Override
    public CompletableFuture<Void> unloadIsland(String worldName, boolean save) {
        return unavailable();
    }

    @Override
    public CompletableFuture<Void> deleteIsland(String worldName) {
        return unavailable();
    }

    @Override
    public boolean isLoaded(String worldName) {
        return false;
    }

    @Override
    public void shutdown() {
        // nothing to release
    }
}
