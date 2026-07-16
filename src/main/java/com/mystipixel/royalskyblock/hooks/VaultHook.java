package com.mystipixel.royalskyblock.hooks;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Thin wrapper over the Vault economy. Only instantiated when Vault is present (the plugin guards
 * construction with a {@code Class.forName} check), so {@code net.milkbowl.vault.*} is never linked on
 * a server without Vault.
 */
public final class VaultHook {

    private final Economy economy;

    public VaultHook() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        this.economy = rsp != null ? rsp.getProvider() : null;
    }

    public boolean isReady() {
        return economy != null;
    }

    public double balance(OfflinePlayer player) {
        return economy == null ? 0 : economy.getBalance(player);
    }

    public boolean has(OfflinePlayer player, double amount) {
        return economy != null && economy.has(player, amount);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        return economy != null && economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    public void deposit(OfflinePlayer player, double amount) {
        if (economy != null) {
            economy.depositPlayer(player, amount);
        }
    }
}
