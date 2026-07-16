package com.mystipixel.royalskyblock.bank;

import com.mystipixel.royalbank.api.RoyalBankAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;

/**
 * Coop bank backed by RoyalBank's shared-account API (fetched from Bukkit's ServicesManager). Because
 * this class references {@code com.mystipixel.royalbank.api.*}, it is only ever constructed once the
 * plugin has confirmed RoyalBank is present (guarded by a {@code Class.forName} check) — otherwise
 * loading it would fail verification, exactly like the Vault/ASP/WorldEdit hooks.
 */
public final class RoyalBankCoopBank implements CoopBank {

    private final RoyalBankAPI api;

    public RoyalBankCoopBank() {
        RegisteredServiceProvider<RoyalBankAPI> rsp =
                Bukkit.getServicesManager().getRegistration(RoyalBankAPI.class);
        this.api = rsp != null ? rsp.getProvider() : null;
    }

    @Override
    public boolean available() {
        return api != null;
    }

    @Override
    public String backendName() {
        return "RoyalBank";
    }

    @Override
    public double balance(UUID coopId) {
        return api == null ? 0.0 : api.getSharedBalance(coopId);
    }

    @Override
    public String deposit(Player from, UUID coopId, String label, double amount) {
        return api == null ? "&cCoop bank is unavailable." : api.sharedDeposit(from, coopId, label, amount);
    }

    @Override
    public String withdraw(Player to, UUID coopId, String label, double amount) {
        return api == null ? "&cCoop bank is unavailable." : api.sharedWithdraw(to, coopId, label, amount);
    }
}
