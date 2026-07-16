package com.mystipixel.royalskyblock.bank;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * A shared balance for one coop, keyed by the coop profile id. Backed by RoyalBank's id-keyed accounts
 * (full: levels, upgrades, ledger) when RoyalBank is installed, else a Vault-backed store RoyalSkyblock
 * owns (balance only — {@link #supportsUpgrades()} is false). Money flows through Vault either way.
 *
 * <p>{@code deposit}/{@code withdraw}/{@code upgrade} return {@code null} on success or a colour-coded
 * error string. All methods are main-thread only.
 */
public interface CoopBank {

    boolean available();

    String backendName();

    /** Whether this backend supports bank levels/upgrades (RoyalBank yes, Vault fallback no). */
    boolean supportsUpgrades();

    CoopAccountView view(UUID coopId, String label);

    String deposit(Player from, UUID coopId, String label, double amount);

    String withdraw(Player to, UUID coopId, String label, double amount);

    CoopUpgrade upgradeInfo(UUID coopId, String label);

    String upgrade(Player by, UUID coopId, String label);

    List<CoopTxn> transactions(UUID coopId, int limit);
}
