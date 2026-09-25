package com.mystipixel.royalskyblock.bank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Interest is paid on the lowest balance held since the last claim. */
class BankAccountTest {

    @Test
    @DisplayName("depositing just before a claim earns nothing extra")
    void lateDepositDoesNotRaiseTheBase() {
        BankAccount account = new BankAccount("p:x", 1000, 1, 1L).withInterestFloor(1000);
        account = account.withBalance(101_000);        // deposit 100k right before claiming
        assertEquals(1000, account.interestBase());
    }

    @Test
    @DisplayName("a withdrawal lowers the base, and a later deposit does not restore it")
    void withdrawalLowersTheBase() {
        BankAccount account = new BankAccount("p:x", 5000, 1, 1L).withInterestFloor(5000);
        account = account.withBalance(200).withBalance(5000);
        assertEquals(200, account.interestBase());
    }

    @Test
    @DisplayName("an account saved before floors existed reads its balance as the base")
    void legacyAccountUsesBalance() {
        BankAccount legacy = new BankAccount("c:x", 7500, 1, 1L);
        assertEquals(7500, legacy.interestBase());
        assertEquals(7500, legacy.withBalance(9000).interestBase(), "the first deposit after upgrade");
    }

    @Test
    @DisplayName("the base never exceeds the balance")
    void baseCappedByBalance() {
        BankAccount account = new BankAccount("p:x", 100, 1, 1L).withInterestFloor(5000);
        assertEquals(100, account.interestBase());
    }
}
