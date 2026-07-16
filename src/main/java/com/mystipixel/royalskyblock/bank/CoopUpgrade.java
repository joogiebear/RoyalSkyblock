package com.mystipixel.royalskyblock.bank;

/** The coop bank's next-level offer ({@code maxed} = no further levels or backend has no levels). */
public record CoopUpgrade(boolean maxed, int nextLevel, String nextName,
                          double moneyCost, String itemsText, double nextMaxBalance) {
}
