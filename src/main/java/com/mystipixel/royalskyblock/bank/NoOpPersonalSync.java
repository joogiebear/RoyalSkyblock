package com.mystipixel.royalskyblock.bank;

import java.util.UUID;

/** Personal-bank sync when RoyalBank isn't installed: inactive, so profile switches don't touch a bank. */
public final class NoOpPersonalSync implements PersonalBankSync {

    @Override
    public boolean active() {
        return false;
    }

    @Override
    public BankSnapshotState export(UUID playerId) {
        return new BankSnapshotState(0.0, 1, 0L, false);
    }

    @Override
    public void restore(UUID playerId, BankSnapshotState state) {
        // no personal bank to swap
    }

    @Override
    public void reset(UUID playerId) {
        // no personal bank to swap
    }
}
