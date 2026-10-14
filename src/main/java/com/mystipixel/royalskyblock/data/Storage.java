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

import java.util.Collection;
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

    /**
     * Clears the island's unload stamp and returns what it was (0 when it was already clear), so exactly one
     * load pays the offline catch-up for that downtime.
     */
    long claimUnloadStamp(UUID id);

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
    /** @return the row's new version, or -1 when the profile is gone or the write failed */
    long updateProfileRewardLevel(UUID id, int rewardLevel);

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

    enum ReserveResult { RESERVED, TIER_CHANGED, IN_PROGRESS, FAILED }

    /**
     * Reserves the next tier with the island row locked: refused when the island is no longer at
     * {@code expectedTier} or another tier of that upgrade is already cooking, so two servers never sell
     * the same tier.
     */
    ReserveResult reservePending(PendingUpgrade p, int expectedTier);

    /** The upgrades cooking on one island. */
    List<PendingUpgrade> getPending(UUID islandId);

    /** The upgrades on these islands whose timer ran out by {@code now}. */
    List<PendingUpgrade> duePending(long now, Collection<UUID> islandIds);

    /** @return the rows removed: 0 when there was nothing left to remove */
    int deletePending(UUID islandId, String upgradeKey);

    /**
     * Finishes a pending upgrade in one step: removes its row and raises the tier (and radius, when given).
     * Never lowers a tier set higher meanwhile.
     *
     * @return false when the row was already gone, so another server or a skip finished it
     */
    boolean completePending(PendingUpgrade p, @Nullable Integer radius);

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
