package com.mystipixel.royalskyblock.bank;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.hooks.VaultHook;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Fallback coop bank used when RoyalBank isn't installed: the balance lives in RoyalSkyblock's own
 * {@code coop_banks} table and moves to/from player purses through Vault. Each op persists first and
 * compensates (refund / claw-back) if the paired Vault or DB step fails, so money is never lost or
 * duplicated. Needs only Vault.
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
    public double balance(UUID coopId) {
        return round(plugin.storage().getCoopBankBalance(coopId));
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
            vault.deposit(from, charge); // refund — the DB write failed
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
            vault.withdraw(to, amount); // claw back — the DB write failed
            return "&cWithdrawal could not be saved and was reverted; please try again.";
        }
        return null;
    }

    private static double round(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }
}
