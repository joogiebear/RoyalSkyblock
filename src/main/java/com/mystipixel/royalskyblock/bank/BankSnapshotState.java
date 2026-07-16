package com.mystipixel.royalskyblock.bank;

/**
 * RoyalSkyblock-owned copy of a personal bank account's swappable state (kept independent of RoyalBank
 * types so it's safe to reference from always-loaded classes like Storage/PlayerStateService).
 */
public record BankSnapshotState(double balance, int level, long lastInterest, boolean bonus) {
}
