package com.mystipixel.royalskyblock.bank;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.data.BankMutation;
import com.mystipixel.royalskyblock.data.BankOutcome;
import com.mystipixel.royalskyblock.hooks.VaultHook;
import com.mystipixel.royalskyblock.message.Reason;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RoyalSkyblock's native bank. Accounts are keyed by an opaque id (personal per-profile or shared
 * coop), with a {@link Player} as the Vault/inventory counterparty. Every mutation is decided on the
 * locked account in one database transaction that also writes its ledger row, and refunds or claws back
 * if a paired step fails. Main thread only.
 *
 * <p>{@code deposit}/{@code withdraw}/{@code upgrade}/{@code claimInterest} return {@code null} on
 * success or the {@link Reason} it failed ({@code bank.result.*} in lang.yml). Completed movements are
 * reported to the {@link BankAuditSink} (EconGuard when installed).
 */
public final class BankService {

    // short enough that an outside edit shows up quickly, long enough to absorb per-tick reads
    private static final long CACHE_TTL_MILLIS = 5_000L;
    // only sweep expired entries once the map is big enough to be worth walking
    private static final int CACHE_SWEEP_AT = 256;

    private record Cached(BankAccount account, long expiresAt) {
    }

    private final RoyalSkyblockPlugin plugin;
    private final BankLevelManager levels;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final BankAuditReporter audit;
    private VaultHook vault;

    public BankService(RoyalSkyblockPlugin plugin, BankLevelManager levels, VaultHook vault) {
        this.plugin = plugin;
        this.levels = levels;
        this.vault = vault;
        this.audit = new BankAuditReporter(plugin.getLogger());
    }

    public void setAuditSink(BankAuditSink sink) {
        audit.setSink(sink);
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

    /**
     * The account, from cache when still warm. The balance placeholder is read on every scoreboard
     * refresh, so reads must not hit the database each time. Every write here refreshes the cache, so a
     * hit is current; the short expiry only catches edits made outside this class.
     */
    public BankAccount account(String id) {
        Cached hit = cache.get(id);
        long now = System.currentTimeMillis();
        if (hit != null && now < hit.expiresAt()) {
            return hit.account();
        }
        BankAccount stored = plugin.storage().getBankAccount(id);
        BankAccount result = stored != null ? stored : new BankAccount(id, 0.0, levels.getStartingLevel(), 0L);
        cache.put(id, new Cached(result, now + CACHE_TTL_MILLIS));
        if (cache.size() > CACHE_SWEEP_AT) {
            cache.values().removeIf(entry -> now >= entry.expiresAt());
        }
        return result;
    }

    // One locked movement in the store; the cache is refreshed from what it found or wrote. Vault money moves
    // only after this returns, so a movement is never decided on a stale balance.
    private BankOutcome transact(String id, BankMutation work) {
        BankOutcome outcome = plugin.storage().bankTransaction(id, levels.getStartingLevel(), work);
        if (outcome.failed()) {
            cache.remove(id);   // unknown DB state, force the next read to go to source
        } else {
            cache.put(id, new Cached(outcome.account(), System.currentTimeMillis() + CACHE_TTL_MILLIS));
        }
        return outcome;
    }

    /** Drop a cached account, so an external change (an admin edit, a reload) is picked up at once. */
    public void invalidate(String id) {
        if (id == null) {
            cache.clear();
        } else {
            cache.remove(id);
        }
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

    public @Nullable Reason deposit(Player purse, String id, double amount) {
        if (!available()) {
            return result("unavailable");
        }
        amount = round(amount);
        double min = levels.config().getDouble("settings.min-transaction", 0.01);
        if (amount < min) {
            return result("amount.too-small", "amount", money(min));
        }
        // a first look from the cache, so a full bank never touches the purse; the transaction decides
        BankAccount seen = account(id);
        BankLevel seenLevel = levels.effectiveLevel(seen.level());
        double charge = round(Math.min(amount, seenLevel.maxBalance() - seen.balance()));
        if (charge <= 0.0) {
            return result("account.deposit.bank-full");
        }
        if (!vault.has(purse, charge)) {
            return result("account.deposit.not-enough-money");
        }
        if (!vault.withdraw(purse, charge)) {
            return result("deposit-failed");
        }
        String note = note("account-deposit", "player", purse.getName());
        long now = Instant.now().getEpochSecond();
        BankOutcome outcome = transact(id, locked -> {
            BankLevel level = levels.effectiveLevel(locked.level());
            double credit = round(Math.min(charge, level.maxBalance() - locked.balance()));
            if (credit <= 0.0) {
                return BankOutcome.refuse(locked, result("account.deposit.bank-full"));
            }
            double principal = round(locked.balance() + credit);
            BankAccount updated = locked.withBalance(principal);
            if (locked.lastInterest() <= 0) {
                // First money in starts the interest clock, so a new account (or new profile) can't deposit and
                // claim at once. The floor starts at this deposit so a normal first period still earns.
                updated = updated.withLastInterest(now).withInterestFloor(principal);
            }
            return BankOutcome.write(updated, "DEPOSIT", credit, note);
        });
        if (outcome.failed()) {
            vault.deposit(purse, charge);
            return result("account.deposit.refunded");
        }
        if (!outcome.written()) {
            vault.deposit(purse, charge);
            return outcome.refusal();
        }
        double unused = round(charge - outcome.amount());
        if (unused > 0) {
            vault.deposit(purse, unused);   // the bank filled up elsewhere meanwhile
        }
        audit.deposited(id, purse.getUniqueId(), purse.getName(), outcome.amount(), outcome.account().balance());
        return null;
    }

    /**
     * Empty an account into {@code purse}: the payout a member gets from their personal account on a
     * coop they left. Returns the amount paid (0 if empty), or -1 if the bank can't pay right now (no
     * economy, or the ledger write failed), in which case nothing moved.
     */
    public double payOutAll(Player purse, String id, String note) {
        if (account(id).balance() <= 0) {
            return 0;
        }
        if (!available()) {
            return -1;
        }
        // Ledger first: if the deposit then fails the money is lost to the economy, but it can never be
        // paid twice, which is the failure that would matter on a retry.
        BankOutcome outcome = transact(id, locked -> {
            double amount = round(locked.balance());
            return amount <= 0 ? BankOutcome.refuse(locked, null)
                    : BankOutcome.write(locked.withBalance(0.0), "PAYOUT", amount, note);
        });
        if (outcome.failed()) {
            return -1;
        }
        if (!outcome.written()) {
            return 0;
        }
        double amount = outcome.amount();
        if (!vault.deposit(purse, amount)) {
            // The purse refused it: put it back so the payout stays owed rather than vanishing.
            String reverted = note("purse-refused", "note", note);
            transact(id, locked -> BankOutcome.write(locked.withBalance(round(locked.balance() + amount)),
                    "PAYOUT_REVERTED", amount, reverted));
            return -1;
        }
        audit.paidOut(purse.getUniqueId(), purse.getName(), amount);
        return amount;
    }

    public @Nullable Reason withdraw(Player purse, String id, double amount) {
        if (!available()) {
            return result("unavailable");
        }
        double wanted = round(amount);
        double min = levels.config().getDouble("settings.min-transaction", 0.01);
        if (wanted < min) {
            return result("amount.too-small", "amount", money(min));
        }
        if (account(id).balance() < wanted) {
            return result("account.withdraw.not-enough-money");
        }
        // Debit first, against the locked balance, then pay the purse: two servers withdrawing at once can't
        // both be paid from the same money. A purse that refuses gets the debit put back.
        String note = note("account-withdraw", "player", purse.getName());
        BankOutcome outcome = transact(id, locked -> locked.balance() < wanted
                ? BankOutcome.refuse(locked, result("account.withdraw.not-enough-money"))
                : BankOutcome.write(locked.withBalance(round(locked.balance() - wanted)), "WITHDRAW", wanted, note));
        if (outcome.failed()) {
            return result("withdraw.reverted");
        }
        if (!outcome.written()) {
            return outcome.refusal();
        }
        if (!vault.deposit(purse, wanted)) {
            String reverted = note("withdraw-reverted", "player", purse.getName());
            transact(id, locked -> BankOutcome.write(locked.withBalance(round(locked.balance() + wanted)),
                    "WITHDRAW_REVERTED", wanted, reverted));
            return result("purse-full");
        }
        audit.withdrew(id, purse.getUniqueId(), purse.getName(), wanted, round(outcome.account().balance() + wanted));
        return null;
    }

    public @Nullable Reason upgrade(Player purse, String id) {
        if (!available()) {
            return result("unavailable");
        }
        BankAccount account = account(id);
        BankLevel next = levels.getNextLevel(account.level()).orElse(null);
        if (next == null) {
            return result("account.upgrade.max-level");
        }
        if (!vault.has(purse, next.upgradeMoneyCost())) {
            return result("account.upgrade.not-enough-money", "cost", money(next.upgradeMoneyCost()));
        }
        List<String> missing = missingItems(purse, next.itemRequirements());
        if (!missing.isEmpty()) {
            return result("upgrade.missing-items", "items",
                    String.join(plugin.messages().raw("bank.result.format.item-separator"), missing));
        }
        if (!vault.withdraw(purse, next.upgradeMoneyCost())) {
            return result("upgrade-payment-failed");
        }
        String note = note("upgrade", "level", next.name());
        BankOutcome outcome = transact(id, locked -> {
            BankLevel now = levels.getNextLevel(locked.level()).orElse(null);
            // upgraded elsewhere meanwhile: this purchase was for a level it no longer leads to
            if (now == null || now.level() != next.level()) {
                return BankOutcome.refuse(locked, result("upgrade.refunded"));
            }
            return BankOutcome.write(locked.withLevel(next.level()), "UPGRADE", next.upgradeMoneyCost(), note);
        });
        if (!outcome.written()) {
            vault.deposit(purse, next.upgradeMoneyCost()); // refund; items not yet removed
            return outcome.failed() ? result("upgrade.refunded") : outcome.refusal();
        }
        removeItems(purse, next.itemRequirements());
        return null;
    }

    public long interestSecondsRemaining(String id) {
        long cooldown = levels.config().getLong("settings.interest-cooldown-hours", 24) * 3600L;
        long lastInterest = account(id).lastInterest();
        if (lastInterest <= 0) {
            // Clock not started: an account funded before first deposits started it. Let it claim once,
            // which then starts its clock.
            return 0;
        }
        long next = lastInterest + cooldown;
        long now = Instant.now().getEpochSecond();
        return now >= next ? 0 : next - now;
    }

    /** Pay accrued interest into the account (capped at the level max). Returns null on success/no-op. */
    public @Nullable Reason claimInterest(Player claimer, String id) {
        if (!available()) {
            return result("interest-unavailable");
        }
        if (interestSecondsRemaining(id) > 0) {
            return result("interest-not-ready");
        }
        long cooldown = levels.config().getLong("settings.interest-cooldown-hours", 24) * 3600L;
        long now = Instant.now().getEpochSecond();
        String note = note("interest");
        // the cooldown is checked again on the locked row, so two servers can't both pay one period
        BankOutcome outcome = transact(id, locked -> {
            if (locked.lastInterest() > 0 && now < locked.lastInterest() + cooldown) {
                return BankOutcome.refuse(locked, result("interest-not-ready"));
            }
            BankLevel level = levels.effectiveLevel(locked.level());
            // On the lowest balance held since the last claim, not the balance now (see BankAccount).
            double interest = round(calculateInterest(locked.interestBase(), level));
            if (interest <= 0) {
                return BankOutcome.refuse(locked, result("no-interest"));
            }
            double newBalance = round(Math.min(locked.balance() + interest, level.maxBalance()));
            double paid = round(newBalance - locked.balance());
            if (paid <= 0) {
                return BankOutcome.refuse(locked, result("interest-full"));
            }
            BankAccount updated = locked.withBalance(newBalance).withLastInterest(now).withInterestFloor(newBalance);
            return BankOutcome.write(updated, "INTEREST", paid, note);
        });
        if (outcome.failed()) {
            return result("interest.not-saved");
        }
        if (!outcome.written()) {
            return outcome.refusal();
        }
        audit.interest(id, claimer.getUniqueId(), claimer.getName(), outcome.amount(), outcome.account().balance());
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

    private List<String> missingItems(Player player, List<ItemRequirement> requirements) {
        List<String> missing = new ArrayList<>();
        for (ItemRequirement req : requirements) {
            int have = countItems(player, req);
            if (have < req.amount()) {
                missing.add(plugin.messages().raw("bank.result.format.item-have",
                        "have", String.valueOf(have), "item", req.displayName()));
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

    private static Reason result(String key, String... placeholders) {
        return new Reason("bank.result." + key, placeholders);
    }

    // the ledger note saved with an entry, from bank.notes.<key>
    private String note(String key, String... placeholders) {
        return plugin.messages().raw("bank.notes." + key, placeholders);
    }

    private double round(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        int decimals = Math.max(0, Math.min(6, levels.config().getInt("settings.decimal-places", 2)));
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }
}
