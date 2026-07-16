package com.mystipixel.royalskyblock.bank;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.hooks.VaultHook;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Fallback coop bank when RoyalBank isn't installed: a balance-only vault in RoyalSkyblock's
 * {@code coop_banks} table, moved to/from purses through Vault with refund/claw-back on failure. No
 * levels or upgrades ({@link #supportsUpgrades()} is false) and no ledger.
 */
public final class VaultCoopBank implements CoopBank {

    private final RoyalSkyblockPlugin plugin;
    private final VaultHook vault;

    public VaultCoopBank(RoyalSkyblockPlugin plugin, VaultHook vault) {
        this.plugin = plugin;
        this.vault = vault;
    }

    @Override
    public boolean available() {
        return vault != null && vault.isReady();
    }

    @Override
    public String backendName() {
        return "Vault";
    }

    @Override
    public boolean supportsUpgrades() {
        return false;
    }

    @Override
    public CoopAccountView view(UUID coopId, String label) {
        double cap = plugin.getConfig().getDouble("coop.bank.max-balance", -1.0);
        return new CoopAccountView(round(plugin.storage().getCoopBankBalance(coopId)), 0, "Basic",
                cap < 0 ? Double.MAX_VALUE : cap);
    }

    @Override
    public String deposit(Player from, UUID coopId, String label, double amount) {
        if (!available()) {
            return "&cCoop bank is unavailable (no economy plugin).";
        }
        amount = round(amount);
        if (amount < 0.01) {
            return "&cEnter a valid amount.";
        }
        double balance = plugin.storage().getCoopBankBalance(coopId);
        double cap = plugin.getConfig().getDouble("coop.bank.max-balance", -1.0);
        double charge = amount;
        if (cap >= 0.0) {
            double space = round(cap - balance);
            if (space <= 0.0) {
                return "&cThe coop bank is full.";
            }
            charge = round(Math.min(amount, space));
        }
        if (!vault.has(from, charge)) {
            return "&cYou don't have enough money to deposit that.";
        }
        if (!vault.withdraw(from, charge)) {
            return "&cDeposit failed.";
        }
        if (!plugin.storage().saveCoopBankBalance(coopId, round(balance + charge))) {
            vault.deposit(from, charge);
            return "&cDeposit could not be saved; your money was refunded.";
        }
        return null;
    }

    @Override
    public String withdraw(Player to, UUID coopId, String label, double amount) {
        if (!available()) {
            return "&cCoop bank is unavailable (no economy plugin).";
        }
        amount = round(amount);
        if (amount < 0.01) {
            return "&cEnter a valid amount.";
        }
        double balance = plugin.storage().getCoopBankBalance(coopId);
        if (balance < amount) {
            return "&cThe coop bank doesn't have that much money.";
        }
        vault.deposit(to, amount);
        if (!plugin.storage().saveCoopBankBalance(coopId, round(balance - amount))) {
            vault.withdraw(to, amount);
            return "&cWithdrawal could not be saved and was reverted; please try again.";
        }
        return null;
    }

    @Override
    public CoopUpgrade upgradeInfo(UUID coopId, String label) {
        return new CoopUpgrade(true, 0, "", 0.0, "None", 0.0); // no levels on the Vault fallback
    }

    @Override
    public String upgrade(Player by, UUID coopId, String label) {
        return "&cCoop bank upgrades need RoyalBank installed.";
    }

    @Override
    public List<CoopTxn> transactions(UUID coopId, int limit) {
        return List.of();
    }

    private static double round(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }
}
