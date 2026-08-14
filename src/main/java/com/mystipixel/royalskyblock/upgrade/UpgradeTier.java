package com.mystipixel.royalskyblock.upgrade;

import com.mystipixel.royalskyblock.currency.Cost;

import java.util.List;

/**
 * One tier of an upgrade: its effect {@code value}, the base {@code cost} (paid then you wait
 * {@code timeSeconds}), and the {@code skipCost} (paid to finish instantly). A {@code timeSeconds} of
 * 0 means instant.
 *
 * @param unlockCommands console commands run when the island reaches this tier — the hook for an
 *                       upgrade whose effect lives in another plugin, which is how the minion-slot
 *                       track raises an EcoMinions permission limit. Placeholders: %owner%, %tier%,
 *                       %value%, %world% and %island%. Scope anything per-player to %world%, or it
 *                       leaks across the owner's other profiles. Run on every path that applies
 *                       the tier, including an admin setting it, so the outside world cannot end up
 *                       disagreeing with the tier the island actually has. Write them idempotent:
 *                       reaching a tier twice runs them twice.
 */
public record UpgradeTier(int tier, double value, Cost cost, Cost skipCost, long timeSeconds,
                          List<String> unlockCommands) {

    public boolean isInstant() {
        return timeSeconds <= 0;
    }
}
