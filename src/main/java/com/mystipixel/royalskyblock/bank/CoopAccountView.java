package com.mystipixel.royalskyblock.bank;

/** Read-only view of a coop bank account for rendering. */
public record CoopAccountView(double balance, int level, String levelName, double maxBalance) {
}
