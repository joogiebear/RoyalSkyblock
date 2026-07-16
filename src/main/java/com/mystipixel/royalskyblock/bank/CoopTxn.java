package com.mystipixel.royalskyblock.bank;

/** One coop bank ledger entry. {@code timestamp} is epoch seconds. */
public record CoopTxn(String type, double amount, double balanceAfter, long timestamp, String note) {
}
