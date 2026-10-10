package com.mystipixel.royalskyblock.data;

import com.mystipixel.royalskyblock.bank.BankAccount;
import com.mystipixel.royalskyblock.bank.BankTxn;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.profile.Profile;
import com.mystipixel.royalskyblock.profile.ProfileData;
import com.mystipixel.royalskyblock.upgrade.PendingUpgrade;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Everything RoyalSkyblock persists. Implemented by {@link SqlStorage} (SQLite/MySQL) and
 * {@link EcoStorage} (eco's data layer). {@link #getAllIslands()} is the one full scan, which a
 * key-value store has to answer with its own index.
 */
public interface Storage {

    /** Open the store. Returns false if it could not be initialised, which disables the plugin. */
    boolean connect();

    /** Release resources. Called on disable. */
    void close();

    /**
     * The island, or {@code null} if there is no such island.
     *
     * @throws StorageException if the store could not answer. Never {@code null} for that: callers
     *         treat {@code null} as "gone" and delete things accordingly.
     */
    @Nullable Island getIsland(UUID id);

    /** As {@link #getIsland}: {@code null} only when the profile has no island. */
    @Nullable Island getIslandByProfile(UUID profileId);

    /** Every island. A full scan: used by the level leaderboard and to warm the island cache at boot. */
    List<Island> getAllIslands();

    boolean saveIsland(Island island);

    boolean deleteIsland(UUID id);

    @Nullable Profile getProfile(UUID id);

    List<Profile> getProfilesByOwner(UUID owner);

    /** Profiles this player belongs to but does not own, i.e. coop membership. */
    List<UUID> getProfileIdsByMember(UUID uuid);

    boolean saveProfile(Profile profile);

    boolean deleteProfile(UUID id);

    /** Which profile the player is currently on, or null if they have never picked one. */
    @Nullable UUID getActiveProfile(UUID player);

    void setActiveProfile(UUID player, UUID profileId);

    @Nullable ProfileData getProfileData(UUID profileId, UUID playerUuid);

    boolean saveProfileData(UUID profileId, UUID playerUuid, ProfileData data);

    void deleteProfileData(UUID profileId, UUID playerUuid);

    /**
     * Record that {@code player} is owed what they had on {@code fromProfile} (their personal bank
     * savings and the items they carried) after leaving or being kicked from that coop. Their
     * {@code profile_data} row and bank account there are kept until the payout is delivered.
     */
    void addCoopPayout(UUID player, UUID fromProfile);

    /** Coop profiles {@code player} is still owed a payout from. */
    List<UUID> getCoopPayouts(UUID player);

    void removeCoopPayout(UUID player, UUID fromProfile);

    List<PendingUpgrade> getAllPending();

    boolean savePending(PendingUpgrade p);

    void deletePending(UUID islandId, String upgradeKey);

    @Nullable BankAccount getBankAccount(String accountId);

    /** Save a balance change and its ledger entry together, so the two cannot disagree. */
    boolean saveBankAccountWithTxn(BankAccount account, String type, double amount,
                                   double balanceAfter, String note);

    /**
     * The most recent {@code limit} transactions for an account, newest first. Never the whole history,
     * so a non-relational store can keep a capped list per account.
     */
    List<BankTxn> getBankTransactions(String accountId, int limit);
}
