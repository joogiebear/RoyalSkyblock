package com.mystipixel.royalskyblock.upgrade;

import com.mystipixel.royalskyblock.currency.Cost;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The two parsers that turn {@code upgrades.yml} text into numbers.
 *
 * <p>Both were reshaped when the file's formatting was unified — costs gained a compact
 * {@code 5000 coins} form beside the block one, and every {@code time:} lost its quotes — and neither
 * had any coverage. A wrong answer here is quiet: a cost that parses to zero makes an upgrade free,
 * and a duration that parses to zero makes a three-day wait instant. Nothing throws either way.
 */
class UpgradeParsingTest {

    // ── durations ────────────────────────────────────────────────────────────────

    @Test
    void parsesEveryDurationUnit() {
        assertEquals(45, UpgradeManager.parseTime("45s"), "seconds");
        assertEquals(30 * 60, UpgradeManager.parseTime("30m"), "minutes");
        assertEquals(4 * 3600, UpgradeManager.parseTime("4h"), "hours");
        assertEquals(2 * 86_400, UpgradeManager.parseTime("2d"), "days");
    }

    /** Both spellings of "no wait" have to land on zero — the config documents {@code 0}. */
    @Test
    void treatsZeroAndInstantAsNoWait() {
        assertEquals(0, UpgradeManager.parseTime("0"));
        assertEquals(0, UpgradeManager.parseTime("instant"));
        assertEquals(0, UpgradeManager.parseTime(""));
        assertEquals(0, UpgradeManager.parseTime(null));
    }

    /**
     * Unquoting the file left YAML free to hand these over as integers rather than strings. Bukkit
     * coerces via getString, so a bare number must still mean seconds rather than falling through.
     */
    @Test
    void readsABareNumberAsSeconds() {
        assertEquals(90, UpgradeManager.parseTime("90"));
    }

    /** Nonsense must not throw inside config loading — a bad duration should read as instant. */
    @Test
    void survivesNonsense() {
        assertEquals(0, UpgradeManager.parseTime("soon"));
        assertEquals(0, UpgradeManager.parseTime("d"));
    }

    // ── costs ────────────────────────────────────────────────────────────────────

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

    /** The block form has to keep working — it is the documented choice when a value wants a comment. */
    @Test
    void readsTheBlockForm() {
        Cost c = cost("cost:\n  currency: gems\n  amount: 288");
        assertEquals("gems", c.currency());
        assertEquals(288, c.amount());
    }

    /** An amount with no currency named falls back to coins rather than to nothing. */
    @Test
    void defaultsTheCurrencyToCoins() {
        assertEquals("coins", cost("cost: 175").currency());
        assertEquals(175, cost("cost: 175").amount());
    }

    /** A missing cost is free, not an error — plenty of tier-one upgrades have none. */
    @Test
    void treatsAMissingCostAsFree() {
        assertEquals(0, cost("value: 1").amount());
    }
}
