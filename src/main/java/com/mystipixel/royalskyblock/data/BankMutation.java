package com.mystipixel.royalskyblock.data;

import com.mystipixel.royalskyblock.bank.BankAccount;

/**
 * Decides a bank movement from the locked account. Runs inside the database transaction and may run more than
 * once (a deadlock retries it), so it must not touch Bukkit, Vault or caches.
 */
@FunctionalInterface
public interface BankMutation {
    BankOutcome apply(BankAccount locked);
}
