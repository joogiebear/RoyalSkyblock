package com.mystipixel.royalskyblock.bank;

/**
 * One bank account, keyed by an opaque string id (personal = {@code p:<profile>:<player>}, coop =
 * {@code c:<profile>}). {@code lastInterest} is epoch seconds of the last interest claim.
 *
 * <p>{@code interestFloor} is the lowest balance held since the last claim, and interest is paid on
 * it rather than on the balance at the moment of claiming — otherwise depositing just before a claim
 * and withdrawing just after earned a full period's interest on money that was never there. A
 * negative floor means "not recorded yet" (an account saved before this existed) and reads as the
 * balance.
 */
public record BankAccount(String id, double balance, int level, long lastInterest, double interestFloor) {

    public BankAccount(String id, double balance, int level, long lastInterest) {
        this(id, balance, level, lastInterest, -1.0);
    }

    /** What interest is paid on: the floor, never more than the current balance. */
    public double interestBase() {
        return interestFloor < 0 ? balance : Math.min(interestFloor, balance);
    }

    /** A new balance. The floor only ever goes down between claims: deposits do not raise it. */
    public BankAccount withBalance(double newBalance) {
        return new BankAccount(id, newBalance, level, lastInterest, Math.min(interestBase(), newBalance));
    }

    public BankAccount withLevel(int newLevel) {
        return new BankAccount(id, balance, newLevel, lastInterest, interestFloor);
    }

    public BankAccount withLastInterest(long ts) {
        return new BankAccount(id, balance, level, ts, interestFloor);
    }

    /** Reset the floor: after a claim, or when the interest clock starts. */
    public BankAccount withInterestFloor(double floor) {
        return new BankAccount(id, balance, level, lastInterest, floor);
    }
}
