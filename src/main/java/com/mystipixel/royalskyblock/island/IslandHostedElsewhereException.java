package com.mystipixel.royalskyblock.island;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** The island is loaded on another server sharing the database, so this server may not load it. */
public final class IslandHostedElsewhereException extends RuntimeException {

    private final UUID islandId;
    private final String holder;

    public IslandHostedElsewhereException(UUID islandId, String holder) {
        super("Island " + islandId + " is hosted on server " + holder);
        this.islandId = islandId;
        this.holder = holder;
    }

    public UUID islandId() {
        return islandId;
    }

    /** The server id of the host. */
    public String holder() {
        return holder;
    }

    /** This exception anywhere in a failure's cause chain (futures wrap it), or null. */
    public static @Nullable IslandHostedElsewhereException find(@Nullable Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IslandHostedElsewhereException hosted) {
                return hosted;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }
}
