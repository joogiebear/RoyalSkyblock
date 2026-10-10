package com.mystipixel.royalskyblock.world;

import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * Fallback when the server isn't running Advanced Slime Paper. Every world operation fails fast with a
 * clear message, so the plugin still enables and island actions explain what's missing.
 */
public final class NoOpIslandWorldService implements IslandWorldService {

    private static final String MESSAGE =
            "The server is not running Advanced Slime Paper: island worlds are unavailable.";

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
    public void saveIslandNow(String worldName) {
        // no backend, nothing to save
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
    public byte[] exportWorld(String worldName) throws Exception {
        throw new IllegalStateException(MESSAGE);
    }

    @Override
    public void importWorld(String worldName, byte[] data) throws Exception {
        throw new IllegalStateException(MESSAGE);
    }

    @Override
    public java.util.List<String> listWorldNames() {
        return java.util.List.of();
    }

    @Override
    public void shutdown() {
        // nothing to release
    }
}
