package com.mystipixel.royalskyblock.upgrade;

import com.mystipixel.royalskyblock.currency.Cost;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The two parsers that turn upgrades.yml text into numbers. A wrong answer is silent: a cost that
// parses to zero makes an upgrade free, and a zero duration makes a three-day wait instant.
class UpgradeParsingTest {

    @Test
    void parsesEveryDurationUnit() {
        assertEquals(45, UpgradeManager.parseTime("45s"), "seconds");
        assertEquals(30 * 60, UpgradeManager.parseTime("30m"), "minutes");
        assertEquals(4 * 3600, UpgradeManager.parseTime("4h"), "hours");
        assertEquals(2 * 86_400, UpgradeManager.parseTime("2d"), "days");
    }

    // both spellings of "no wait" land on zero; the config documents 0
    @Test
    void treatsZeroAndInstantAsNoWait() {
        assertEquals(0, UpgradeManager.parseTime("0"));
        assertEquals(0, UpgradeManager.parseTime("instant"));
        assertEquals(0, UpgradeManager.parseTime(""));
        assertEquals(0, UpgradeManager.parseTime(null));
    }

    // unquoted YAML may hand these over as integers; a bare number must still mean seconds
    @Test
    void readsABareNumberAsSeconds() {
        assertEquals(90, UpgradeManager.parseTime("90"));
    }

    // nonsense must not throw inside config loading; a bad duration reads as instant
    @Test
    void survivesNonsense() {
        assertEquals(0, UpgradeManager.parseTime("soon"));
        assertEquals(0, UpgradeManager.parseTime("d"));
    }

    private Cost cost(String yaml) {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.loadFromString(yaml);
        } catch (Exception e) {
            throw new AssertionError("test yaml did not parse", e);
        }
        return UpgradeManager.parseCost(cfg, "cost", Logger.getAnonymousLogger());
    }

    @Test
    void readsTheCompactForm() {
        Cost c = cost("cost: 5000 coins");
        assertEquals("coins", c.currency());
        assertEquals(5000, c.amount());
    }

    // the block form must keep working: it is the documented choice when a value wants a comment
    @Test
    void readsTheBlockForm() {
        Cost c = cost("cost:\n  currency: gems\n  amount: 288");
        assertEquals("gems", c.currency());
        assertEquals(288, c.amount());
    }

    // an amount with no currency falls back to coins
    @Test
    void defaultsTheCurrencyToCoins() {
        assertEquals("coins", cost("cost: 175").currency());
        assertEquals(175, cost("cost: 175").amount());
    }

    // a missing cost is free, not an error
    @Test
    void treatsAMissingCostAsFree() {
        assertEquals(0, cost("value: 1").amount());
    }
}
