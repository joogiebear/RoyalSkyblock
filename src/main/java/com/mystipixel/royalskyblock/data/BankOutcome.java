package com.mystipixel.royalskyblock.data;

import com.mystipixel.royalskyblock.bank.BankAccount;
import com.mystipixel.royalskyblock.message.Reason;
import org.jetbrains.annotations.Nullable;

/**
 * What a {@link Storage#bankTransaction} decided. A written outcome carries the account after the change and
 * the ledger entry stored with it; a refused one the account as it was and why (null when there was simply
 * nothing to do); a failed one nothing, because the store could not answer and nothing was written.
 */
public record BankOutcome(@Nullable BankAccount account, @Nullable String type, double amount, @Nullable String note,
                          @Nullable Reason refusal, boolean failed) {

    /** Store updated and add a ledger row; the row's balance is updated's. */
    public static BankOutcome write(BankAccount updated, String type, double amount, String note) {
        return new BankOutcome(updated, type, amount, note, null, false);
    }

    public static BankOutcome refuse(BankAccount current, @Nullable Reason why) {
        return new BankOutcome(current, null, 0, null, why, false);
    }

    public static BankOutcome failure() {
        return new BankOutcome(null, null, 0, null, null, true);
    }

    public boolean written() {
        return type != null;
    }
}
