package com.mystipixel.royalskyblock.bank;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Where bank movements are reported (EconGuard when installed). {@code incoming} is true when the player
 * gains money they did not already own (a coop withdrawal funded by another member, interest), not when
 * money only moves between their own purse and bank. {@code counterparty} names the other player, if any.
 */
public interface BankAuditSink {

    BankAuditSink NONE = (uuid, username, action, amount, balanceAfter, incoming, counterparty, counterpartyName) -> {
    };

    void record(UUID uuid, String username, String action, double amount, double balanceAfter,
                boolean incoming, @Nullable UUID counterparty, @Nullable String counterpartyName);
}
