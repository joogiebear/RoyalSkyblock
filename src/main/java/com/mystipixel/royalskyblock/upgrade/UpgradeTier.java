package com.mystipixel.royalskyblock.upgrade;

import com.mystipixel.royalskyblock.currency.Cost;

import java.util.List;

/**
 * One tier of an upgrade: its effect {@code value}, the base {@code cost} (paid, then you wait
 * {@code timeSeconds}; 0 means instant), and the {@code skipCost} (paid to finish instantly).
 *
 * @param unlockCommands console commands run when the island reaches this tier, for effects that live
 *                       in another plugin. Placeholders: %owner%, %tier%, %value%, %world%, %island%.
 *                       Scope anything per-player to %world% or it leaks across the owner's other
 *                       profiles. Run on every path that applies the tier, so write them idempotent.
 */
public record UpgradeTier(int tier, double value, Cost cost, Cost skipCost, long timeSeconds,
                          List<String> unlockCommands) {

    public boolean isInstant() {
        return timeSeconds <= 0;
    }
}
