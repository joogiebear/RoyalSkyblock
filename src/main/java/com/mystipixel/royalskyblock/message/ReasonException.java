package com.mystipixel.royalskyblock.message;

/** Fails a future with a player-facing {@link Reason}; the message is the key, for logs. */
public final class ReasonException extends RuntimeException {

    private final Reason reason;

    public ReasonException(Reason reason) {
        super(reason.key());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
