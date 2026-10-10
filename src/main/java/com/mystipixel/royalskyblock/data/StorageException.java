package com.mystipixel.royalskyblock.data;

/**
 * The store could not answer, as opposed to answering "there is no such row". Callers delete or
 * recreate things on a null, so a failed lookup must throw this instead.
 */
public final class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
