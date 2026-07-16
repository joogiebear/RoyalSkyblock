package com.mystipixel.royalskyblock.bank;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * A shared balance for one coop, keyed by the coop profile id. Two backends implement this:
 * {@code RoyalBankCoopBank} (delegates to RoyalBank's shared-account API when RoyalBank is installed)
 * and {@code VaultCoopBank} (a RoyalSkyblock-owned balance moved via Vault when it isn't). Either way
 * money flows through Vault, so the server economy total is conserved.
 *
 * <p>{@code deposit}/{@code withdraw} return {@code null} on success or a colour-coded error string.
 */
public interface CoopBank {

    /** Whether a working economy backend is present (else deposits/withdraws are unavailable). */
    boolean available();

    /** Short backend name for diagnostics ("RoyalBank" / "Vault"). */
    String backendName();

    double balance(UUID coopId);

    String deposit(Player from, UUID coopId, String label, double amount);

    String withdraw(Player to, UUID coopId, String label, double amount);
}
