package com.mystipixel.royalskyblock.data;

import com.mystipixel.royalskyblock.bank.BankAccount;
import com.mystipixel.royalskyblock.bank.BankTxn;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.island.IslandRole;
import com.mystipixel.royalskyblock.profile.Profile;
import com.mystipixel.royalskyblock.profile.ProfileData;
import com.mystipixel.royalskyblock.profile.ProfileMember;
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

    /** Writes a new island. Never overwrites an existing row. */
    boolean insertIsland(Island island);

    /*
     * Targeted island writes. Each changes only its own columns, so servers sharing a database don't undo
     * each other's changes, and returns the row's new version, or -1 when nothing was written.
     */

    long updateIslandHome(UUID id, double x, double y, double z, float yaw, float pitch);

    long updateIslandGuestHome(UUID id, String serializedGuestHome);

    /** The reward marker only ever goes up. */
    long updateIslandLevel(UUID id, double level, int rewardLevel);

    /** Only ever goes up. */
    long updateIslandPerkLevel(UUID id, int perkLevel);

    long updateIslandUnloadedAt(UUID id, long unloadedAt);

    /** Sets one upgrade tier, keeping the others, and the radius too when it is not null. */
    long updateIslandUpgradeTier(UUID id, String upgradeKey, int tier, @Nullable Integer radius);

    /** Sets one setting, keeping the others. */
    long updateIslandSetting(UUID id, String settingKey, boolean enabled);

    boolean deleteIsland(UUID id);

    @Nullable Profile getProfile(UUID id);

    List<Profile> getProfilesByOwner(UUID owner);

    /** Profiles this player belongs to but does not own, i.e. coop membership. */
    List<UUID> getProfileIdsByMember(UUID uuid);

    /** Writes a new profile and its roster. Never overwrites an existing row. */
    boolean insertProfile(Profile profile);

    enum AddMemberResult { ADDED, ALREADY, FULL, FAILED }

    /** Adds a member unless the roster already has maxMembers, counted in the store, not in a cached copy. */
    AddMemberResult addMember(UUID profileId, ProfileMember member, int maxMembers);

    boolean removeMember(UUID profileId, UUID member);

    boolean setMemberRole(UUID profileId, UUID member, IslandRole role);

    /** Makes newOwner the owner and steps oldOwner down to co-owner, together. */
    boolean transferOwner(UUID profileId, UUID newOwner, UUID oldOwner);

    /** Only ever goes up. */
    boolean updateProfileRewardLevel(UUID id, int rewardLevel);

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

    /**
     * One bank movement: the account is created if missing (balance 0 at startingLevel) and locked, work
     * decides on that locked copy, and a written outcome is stored with its ledger row before the lock is
     * released. Servers sharing the database therefore never act on a stale balance.
     */
    BankOutcome bankTransaction(String accountId, int startingLevel, BankMutation work);

    /**
     * The most recent {@code limit} transactions for an account, newest first. Never the whole history,
     * so a non-relational store can keep a capped list per account.
     */
    List<BankTxn> getBankTransactions(String accountId, int limit);
}
