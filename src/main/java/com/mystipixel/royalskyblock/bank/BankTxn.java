package com.mystipixel.royalskyblock.bank;

/** One bank ledger entry. {@code timestamp} is epoch seconds. */
public record BankTxn(String type, double amount, double balanceAfter, long timestamp, String note) {
}
