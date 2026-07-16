package com.mystipixel.royalskyblock.currency;

/**
 * A price in a named currency (resolved by {@link CurrencyService}). {@code currency} is a key from
 * the config {@code currencies:} section (e.g. {@code coins}, {@code gems}).
 */
public record Cost(String currency, double amount) {

    public boolean isFree() {
        return amount <= 0;
    }
}
