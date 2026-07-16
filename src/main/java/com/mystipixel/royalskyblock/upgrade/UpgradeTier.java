package com.mystipixel.royalskyblock.upgrade;

import com.mystipixel.royalskyblock.currency.Cost;

/**
 * One tier of an upgrade: its effect {@code value}, the base {@code cost} (paid then you wait
 * {@code timeSeconds}), and the {@code skipCost} (paid to finish instantly). A {@code timeSeconds} of
 * 0 means instant.
 */
public record UpgradeTier(int tier, double value, Cost cost, Cost skipCost, long timeSeconds) {

    public boolean isInstant() {
        return timeSeconds <= 0;
    }
}
