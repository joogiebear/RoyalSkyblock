package com.mystipixel.royalskyblock.bank;

/**
 * One bank account, keyed by an opaque string id (personal = {@code p:<profile>:<player>}, coop =
 * {@code c:<profile>}). {@code lastInterest} is epoch seconds of the last interest claim.
 */
public record BankAccount(String id, double balance, int level, long lastInterest) {

    public BankAccount withBalance(double newBalance) {
        return new BankAccount(id, newBalance, level, lastInterest);
    }

    public BankAccount withLevel(int newLevel) {
        return new BankAccount(id, balance, newLevel, lastInterest);
    }

    public BankAccount withLastInterest(long ts) {
        return new BankAccount(id, balance, level, ts);
    }
}
