package com.mystipixel.royalskyblock.bank;

import java.util.UUID;

/**
 * Makes a player's personal RoyalBank account per-profile by swapping its state on profile switch.
 * Active only when RoyalBank is installed; otherwise a no-op (there's no personal bank to swap).
 * All methods are main-thread only.
 */
public interface PersonalBankSync {

    boolean active();

    /** The player's current personal bank state (a fresh account if they have none). */
    BankSnapshotState export(UUID playerId);

    /** Overwrite the player's personal bank with the given state. */
    void restore(UUID playerId, BankSnapshotState state);

    /** Reset the player's personal bank to a fresh starting account (new profile). */
    void reset(UUID playerId);
}
