package com.mystipixel.royalskyblock.bank;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.hooks.VaultHook;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * RoyalSkyblock's native bank. Accounts are keyed by an opaque id (personal per-profile or shared coop),
 * with a {@link Player} as the Vault/inventory counterparty — so the same engine serves both. Money
 * moves through Vault; every mutation persists atomically (balance + ledger row) and compensates
 * (refund / claw-back) if a paired step fails. Ported from the RoyalBank engine. Main-thread only.
 *
 * <p>{@code deposit}/{@code withdraw}/{@code upgrade}/{@code claimInterest} return {@code null} on
 * success or a colour-coded, player-facing error string.
 */
public final class BankService {

    private final RoyalSkyblockPlugin plugin;
    private final BankLevelManager levels;
    private VaultHook vault;

    public BankService(RoyalSkyblockPlugin plugin, BankLevelManager levels, VaultHook vault) {
        this.plugin = plugin;
        this.levels = levels;
        this.vault = vault;
    }

    public boolean available() {
        return vault != null && vault.isReady() && !levels.isEmpty();
    }

    public BankLevelManager levels() {
        return levels;
    }

    public static String personalId(UUID profile, UUID player) {
        return "p:" + profile + ":" + player;
    }

    public static String coopId(UUID coopProfile) {
        return "c:" + coopProfile;
    }

    // ── reads ─────────────────────────────────────────────────────────────────────

    public BankAccount account(String id) {
        BankAccount stored = plugin.storage().getBankAccount(id);
        return stored != null ? stored : new BankAccount(id, 0.0, levels.getStartingLevel(), 0L);
    }

    public double balance(String id) {
        return round(account(id).balance());
    }

    public List<BankTxn> transactions(String id, int limit) {
        return plugin.storage().getBankTransactions(id, limit);
    }

    public String currencySymbol() {
        return levels.config().getString("settings.currency-symbol", "$");
    }

    public String money(double amount) {
        return currencySymbol() + String.format(Locale.US, "%,.2f", amount);
    }

    // ── deposit / withdraw ─────────────────────────────────────────────────────────

    public String deposit(Player purse, String id, double amount) {
        if (!available()) {
            return "&cThe bank is unavailable (no economy plugin).";
        }
        amount = round(amount);
        double min = levels.config().getDouble("settings.min-transaction", 0.01);
        if (amount < min) {
            return "&cAmount must be at least " + money(min) + ".";
        }
        BankAccount account = account(id);
        BankLevel level = levels.effectiveLevel(account.level());
        double space = level.maxBalance() - account.balance();
        if (space <= 0) {
            return "&cThe bank is full. Upgrade it to store more.";
        }
        double principal = round(Math.min(account.balance() + Math.min(amount, space), level.maxBalance()));
        double charge = round(principal - account.balance());
        if (charge <= 0.0) {
            return "&cThe bank is full. Upgrade it to store more.";
        }
        if (!vault.has(purse, charge)) {
            return "&cYou don't have enough money to deposit that.";
        }
        if (!vault.withdraw(purse, charge)) {
            return "&cDeposit failed.";
        }
        if (!plugin.storage().saveBankAccountWithTxn(account.withBalance(principal), "DEPOSIT", charge,
                principal, purse.getName() + " deposited")) {
            vault.deposit(purse, charge); // refund
            return "&cDeposit could not be saved; your money was refunded.";
        }
        return null;
    }

    public String withdraw(Player purse, String id, double amount) {
        if (!available()) {
            return "&cThe bank is unavailable (no economy plugin).";
        }
        amount = round(amount);
        double min = levels.config().getDouble("settings.min-transaction", 0.01);
        if (amount < min) {
            return "&cAmount must be at least " + money(min) + ".";
        }
        BankAccount account = account(id);
        if (account.balance() < amount) {
            return "&cThe bank doesn't have that much money.";
        }
        vault.deposit(purse, amount);
        double newBalance = round(account.balance() - amount);
        if (!plugin.storage().saveBankAccountWithTxn(account.withBalance(newBalance), "WITHDRAW", amount,
                newBalance, purse.getName() + " withdrew")) {
            vault.withdraw(purse, amount); // claw back
            return "&cWithdrawal could not be saved and was reverted; please try again.";
        }
        return null;
    }

    // ── upgrade ────────────────────────────────────────────────────────────────────

    public String upgrade(Player purse, String id) {
        if (!available()) {
            return "&cThe bank is unavailable (no economy plugin).";
        }
        BankAccount account = account(id);
        BankLevel next = levels.getNextLevel(account.level()).orElse(null);
        if (next == null) {
            return "&eThe bank is already at the maximum level.";
        }
        if (!vault.has(purse, next.upgradeMoneyCost())) {
            return "&cYou need " + money(next.upgradeMoneyCost()) + " to upgrade.";
        }
        List<String> missing = missingItems(purse, next.itemRequirements());
        if (!missing.isEmpty()) {
            return "&cMissing upgrade items: &f" + String.join(", ", missing);
        }
        if (!vault.withdraw(purse, next.upgradeMoneyCost())) {
            return "&cUpgrade payment failed.";
        }
        if (!plugin.storage().saveBankAccountWithTxn(account.withLevel(next.level()), "UPGRADE",
                next.upgradeMoneyCost(), account.balance(), "Upgraded to " + next.name())) {
            vault.deposit(purse, next.upgradeMoneyCost()); // refund; items not yet removed
            return "&cUpgrade could not be saved. Your money was refunded; please try again.";
        }
        removeItems(purse, next.itemRequirements());
        return null;
    }

    // ── interest ───────────────────────────────────────────────────────────────────

    public long interestSecondsRemaining(String id) {
        long cooldown = levels.config().getLong("settings.interest-cooldown-hours", 24) * 3600L;
        long next = account(id).lastInterest() + cooldown;
        long now = Instant.now().getEpochSecond();
        return account(id).lastInterest() <= 0 || now >= next ? 0 : next - now;
    }

    /** Pay accrued interest into the account (capped at the level max). Returns null on success/no-op. */
    public String claimInterest(String id) {
        if (!available()) {
            return "&cThe bank is unavailable.";
        }
        if (interestSecondsRemaining(id) > 0) {
            return "&eInterest isn't ready yet.";
        }
        BankAccount account = account(id);
        BankLevel level = levels.effectiveLevel(account.level());
        double interest = round(calculateInterest(account.balance(), level));
        if (interest <= 0) {
            return "&eNo interest was earned (empty bank).";
        }
        long now = Instant.now().getEpochSecond();
        double newBalance = round(Math.min(account.balance() + interest, level.maxBalance()));
        double paid = round(newBalance - account.balance());
        if (paid <= 0) {
            return "&eYour bank is full, so no interest could be paid.";
        }
        BankAccount updated = account.withBalance(newBalance).withLastInterest(now);
        if (!plugin.storage().saveBankAccountWithTxn(updated, "INTEREST", paid, newBalance, "Interest")) {
            return "&cInterest could not be saved; please try again.";
        }
        return null;
    }

    public double calculateInterest(double balance, BankLevel level) {
        double interest = 0.0;
        for (InterestTranche tranche : level.interestTranches()) {
            interest += tranche.calculate(balance);
        }
        double cap = level.maxInterest() < 0.0 ? Double.MAX_VALUE : level.maxInterest();
        return Math.min(interest, cap);
    }

    // ── item requirements (ported from RoyalBank) ──────────────────────────────────

    private List<String> missingItems(Player player, List<ItemRequirement> requirements) {
        List<String> missing = new ArrayList<>();
        for (ItemRequirement req : requirements) {
            int have = countItems(player, req);
            if (have < req.amount()) {
                missing.add(req.displayName() + " (have " + have + ")");
            }
        }
        return missing;
    }

    private int countItems(Player player, ItemRequirement req) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (matches(item, req)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private void removeItems(Player player, List<ItemRequirement> requirements) {
        for (ItemRequirement req : requirements) {
            int remaining = req.amount();
            ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < contents.length && remaining > 0; i++) {
                if (!matches(contents[i], req)) {
                    continue;
                }
                int take = Math.min(contents[i].getAmount(), remaining);
                contents[i].setAmount(contents[i].getAmount() - take);
                remaining -= take;
                if (contents[i].getAmount() <= 0) {
                    contents[i] = null;
                }
            }
            player.getInventory().setContents(contents);
        }
    }

    private boolean matches(ItemStack item, ItemRequirement req) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (req.type() == RequirementType.VANILLA) {
            return item.getType() == req.material() && !hasEcoTag(item);
        }
        return matchesEcoItem(item, req.customItemId());
    }

    private boolean hasEcoTag(ItemStack item) {
        if (!item.hasItemMeta()) {
            return false;
        }
        PersistentDataContainer c = item.getItemMeta().getPersistentDataContainer();
        return c.has(new NamespacedKey("ecoitems", "item"), PersistentDataType.STRING)
                || c.has(new NamespacedKey("ecoweapons", "weapon"), PersistentDataType.STRING);
    }

    private boolean matchesEcoItem(ItemStack item, String customItemId) {
        if (!item.hasItemMeta() || customItemId == null) {
            return false;
        }
        String normalized = customItemId.toLowerCase(Locale.ROOT);
        String[] parts = normalized.split(":", 2);
        String namespace = parts.length == 2 ? parts[0] : "ecoitems";
        String wanted = parts.length == 2 ? parts[1] : normalized;
        PersistentDataContainer c = item.getItemMeta().getPersistentDataContainer();
        String value = c.get(new NamespacedKey(namespace, "item"), PersistentDataType.STRING);
        if (value == null && namespace.equals("ecoitems")) {
            value = c.get(new NamespacedKey("ecoweapons", "weapon"), PersistentDataType.STRING);
        }
        if (value == null) {
            return false;
        }
        String v = value.toLowerCase(Locale.ROOT);
        return v.equals(wanted) || v.equals(namespace + ":" + wanted);
    }

    private double round(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        int decimals = Math.max(0, Math.min(6, levels.config().getInt("settings.decimal-places", 2)));
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }
}
