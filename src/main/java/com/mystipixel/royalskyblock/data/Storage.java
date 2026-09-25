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
 * Everything RoyalSkyblock persists, behind one interface.
 *
 * <p>Extracted so there can be more than one backing store. Today's implementation, {@link SqlStorage},
 * owns its own SQLite/MySQL connection pool — which makes RoyalSkyblock the odd one out in the eco
 * suite, where <em>no</em> plugin configures a database: eco holds a single {@code data-handler} and
 * every plugin's data goes through it. A second implementation backed by eco's data layer is the point
 * of this interface, and would bring MySQL/MongoDB/YAML for free, make islands network-shared on a
 * multi-node setup, and let profiles keep island data and eco data in one store instead of two.
 *
 * <p>The shape of this interface is deliberately unchanged from the SQL implementation's public API,
 * so extracting it moved no behaviour and no caller. Note what that shape assumes, because it is what
 * a key-value backing store has to work around: {@link #getAllIslands()} is a full scan used by the
 * level leaderboard and boot-time load, and eco's profile API is keyed lookup with no query layer. An
 * eco-backed implementation has to maintain its own index to answer it.
 */
public interface Storage {

    /** Open the store. Returns false if it could not be initialised, which disables the plugin. */
    boolean connect();

    /** Release resources. Called on disable. */
    void close();

    // ── islands ────────────────────────────────────────────────────────────────

    /**
     * The island, or {@code null} if there is no such island.
     *
     * @throws StorageException if the store could not answer. Never {@code null} for that: callers
     *         treat {@code null} as "gone" and delete things accordingly.
     */
    @Nullable Island getIsland(UUID id);

    /** As {@link #getIsland}: {@code null} only when the profile has no island. */
    @Nullable Island getIslandByProfile(UUID profileId);

    /**
     * Every island. A full scan: used by the level leaderboard and to warm the island cache at boot.
     * The one method with no keyed equivalent, and so the one that shapes any non-relational backing.
     */
    List<Island> getAllIslands();

    boolean saveIsland(Island island);

    boolean deleteIsland(UUID id);

    // ── profiles ───────────────────────────────────────────────────────────────

    @Nullable Profile getProfile(UUID id);

    List<Profile> getProfilesByOwner(UUID owner);

    /** Profiles this player belongs to but does not own — i.e. coop membership. */
    List<UUID> getProfileIdsByMember(UUID uuid);

    boolean saveProfile(Profile profile);

    boolean deleteProfile(UUID id);

    /** Which profile the player is currently on, or null if they have never picked one. */
    @Nullable UUID getActiveProfile(UUID player);

    void setActiveProfile(UUID player, UUID profileId);

    // ── per-profile player state (inventory, xp, health) ───────────────────────

    @Nullable ProfileData getProfileData(UUID profileId, UUID playerUuid);

    boolean saveProfileData(UUID profileId, UUID playerUuid, ProfileData data);

    void deleteProfileData(UUID profileId, UUID playerUuid);

    // ── coop payouts ───────────────────────────────────────────────────────────

    /**
     * Record that {@code player} is owed what they had on {@code fromProfile} — their personal bank
     * savings there and the items they carried — after leaving or being kicked from that coop. Their
     * {@code profile_data} row and bank account on it are kept until the payout is delivered.
     */
    void addCoopPayout(UUID player, UUID fromProfile);

    /** Coop profiles {@code player} is still owed a payout from. */
    List<UUID> getCoopPayouts(UUID player);

    void removeCoopPayout(UUID player, UUID fromProfile);

    // ── in-progress upgrades ───────────────────────────────────────────────────

    List<PendingUpgrade> getAllPending();

    boolean savePending(PendingUpgrade p);

    void deletePending(UUID islandId, String upgradeKey);

    // ── bank ───────────────────────────────────────────────────────────────────

    @Nullable BankAccount getBankAccount(String accountId);

    /** Save a balance change and its ledger entry together, so the two cannot disagree. */
    boolean saveBankAccountWithTxn(BankAccount account, String type, double amount,
                                   double balanceAfter, String note);

    /**
     * The most recent {@code limit} transactions for an account, newest first. Never asks for the
     * whole history, which is what lets a non-relational store keep a capped list per account rather
     * than an append-only table.
     */
    List<BankTxn> getBankTransactions(String accountId, int limit);
}
