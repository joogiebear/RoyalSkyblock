package com.mystipixel.royalskyblock.message;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.function.Function;

// lang.yml keys that were renamed, new key to old key (both under messages.). A server's lang.yml keeps
// the old key and eco adds the new one with the bundled text, so an old key's text wins until the owner
// edits the new key.
final class RenamedKeys {

    static final Map<String, String> OLD_BY_NEW = Map.ofEntries(
            Map.entry("bank.result.amount.too-small", "bank.result.amount-too-small"),
            Map.entry("bank.result.account.deposit.bank-full", "bank.result.full"),
            Map.entry("bank.result.account.deposit.not-enough-money", "bank.result.insufficient-purse"),
            Map.entry("bank.result.account.deposit.refunded", "bank.result.deposit-not-saved"),
            Map.entry("bank.result.account.withdraw.not-enough-money", "bank.result.insufficient-bank"),
            Map.entry("bank.result.account.upgrade.max-level", "bank.result.max-level"),
            Map.entry("bank.result.account.upgrade.not-enough-money", "bank.result.upgrade-cost"),
            Map.entry("bank.result.withdraw.reverted", "bank.result.withdraw-not-saved"),
            Map.entry("bank.result.upgrade.missing-items", "bank.result.missing-items"),
            Map.entry("bank.result.upgrade.refunded", "bank.result.upgrade-not-saved"),
            Map.entry("bank.result.interest.not-saved", "bank.result.interest-not-saved"),
            Map.entry("bank.result.format.item-have", "bank.result.missing-item"),
            Map.entry("bank.result.format.item-separator", "bank.result.missing-items-separator"),
            Map.entry("bank.notes.account-deposit", "bank.note.deposit"),
            Map.entry("bank.notes.account-withdraw", "bank.note.withdraw"),
            Map.entry("bank.notes.upgrade", "bank.note.upgrade"),
            Map.entry("bank.notes.interest", "bank.note.interest"),
            Map.entry("bank.notes.coop-payout", "bank.note.coop-payout"),
            Map.entry("bank.notes.purse-refused", "bank.note.purse-refused"));

    private RenamedKeys() {
    }

    /**
     * The text for {@code key}: the server's own value if it was edited away from the bundled text, else
     * the server's old key if it still has one, else whatever the server or the bundled file has.
     * {@code server} and {@code bundled} return null for a missing key.
     */
    static @Nullable String resolve(String key, Function<String, String> server, Function<String, String> bundled) {
        String current = server.apply(key);
        String oldKey = OLD_BY_NEW.get(key);
        if (oldKey == null) {
            return current;
        }
        String shipped = bundled.apply(key);
        if (current != null && !current.equals(shipped)) {
            return current;
        }
        String old = server.apply(oldKey);
        if (old != null) {
            return old;
        }
        return current != null ? current : shipped;
    }
}
