package com.mystipixel.royalskyblock.bank;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns completed bank movements into audit events, with the same semantics as RoyalBank: moving your
 * own money is not incoming; a coop withdrawal of money another member deposited is incoming with that
 * member as counterparty; interest is incoming with no counterparty. Called only after the money has
 * moved, and a failing sink never reaches the bank action. Main thread only.
 */
final class BankAuditReporter {

    private final Logger logger;
    private final CoopContributions coopContributions = new CoopContributions();
    private BankAuditSink sink = BankAuditSink.NONE;

    BankAuditReporter(Logger logger) {
        this.logger = logger;
    }

    void setSink(@Nullable BankAuditSink sink) {
        this.sink = sink != null ? sink : BankAuditSink.NONE;
    }

    void deposited(String accountId, UUID player, String name, double amount, double balanceAfter) {
        if (isCoop(accountId)) {
            coopContributions.deposited(accountId, player, name, amount);
            report(player, name, "coop-deposited", amount, balanceAfter, false, null, null);
        } else {
            report(player, name, "deposited", amount, balanceAfter, false, null, null);
        }
    }

    void withdrew(String accountId, UUID player, String name, double amount, double balanceBefore) {
        if (!isCoop(accountId)) {
            report(player, name, "withdrew", amount, balanceBefore - amount, false, null, null);
            return;
        }
        double balance = balanceBefore;
        for (CoopContributions.Share share : coopContributions.withdrawn(accountId, player, amount)) {
            double after = balance - share.amount();
            if (player.equals(share.depositor())) {
                report(player, name, "coop-withdrew", share.amount(), after, false, null, null);
            } else if (share.depositor() != null) {
                report(player, name, "coop-received", share.amount(), after, true, share.depositor(), share.depositorName());
            } else {
                report(player, name, "coop-withdrew-untracked", share.amount(), after, true, null, null);
            }
            balance = after;
        }
    }

    // a member's own personal balance paid back to them after leaving a coop
    void paidOut(UUID player, String name, double amount) {
        report(player, name, "payout", amount, 0.0, false, null, null);
    }

    // Coop interest belongs to no member yet; it is reported (incoming, no counterparty) when withdrawn.
    void interest(String accountId, UUID player, String name, double amount, double balanceAfter) {
        if (!isCoop(accountId)) {
            report(player, name, "interest", amount, balanceAfter, true, null, null);
        }
    }

    private void report(UUID player, String name, String action, double amount, double balanceAfter, boolean incoming,
                        @Nullable UUID counterparty, @Nullable String counterpartyName) {
        try {
            sink.record(player, name, action, amount, balanceAfter, incoming, counterparty, counterpartyName);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not report bank action '" + action + "' for " + name, e);
        }
    }

    private static boolean isCoop(String accountId) {
        return accountId.startsWith("c:");
    }
}
