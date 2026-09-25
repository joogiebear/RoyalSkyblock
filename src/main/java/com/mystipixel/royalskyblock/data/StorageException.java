package com.mystipixel.royalskyblock.data;

/**
 * The store could not answer — as opposed to answering "there is no such row".
 *
 * <p>Lookups used to return {@code null} for both, and callers act on {@code null}: the upgrade timer
 * deletes a pending upgrade whose island "no longer exists", the orphan purge archives a world whose
 * row "no longer exists", and island creation makes a second island for a profile whose first one
 * "no longer exists". During a database blip every one of those destroyed real data. Throwing this
 * instead makes a failed lookup fail loudly and leaves the data alone.
 */
public final class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
