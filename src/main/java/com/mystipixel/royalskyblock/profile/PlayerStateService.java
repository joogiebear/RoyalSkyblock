package com.mystipixel.royalskyblock.profile;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.data.Storage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

/**
 * Swaps a player's live state per profile: inventory, ender chest, XP and vitals (in
 * {@code profile_data}), plus eco progression via the eco bridge. Item arrays use Bukkit's object
 * stream so slot positions round-trip exactly. Main thread only.
 */
public final class PlayerStateService {

    private static final double DEFAULT_MAX_HEALTH = 20.0;
    private static final int[] EMPTY_SLOTS = new int[0];

    private final RoyalSkyblockPlugin plugin;
    private final Storage storage;

    // Players whose last load couldn't decode their saved items. save() refuses to run for them, so the
    // empty replacement inventory never overwrites the stored row. Cleared on the next successful load.
    private final java.util.Set<UUID> loadFailed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final ProfileRowWriter rows;

    public PlayerStateService(RoyalSkyblockPlugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.rows = new ProfileRowWriter(storage, plugin::writeAsync);
    }

    ProfileRowWriter rows() {
        return rows;
    }

    /** Capture the player's current state into {@code profileId}'s saved data + eco shadow. */
    public void save(Player player, UUID profileId) {
        ProfileData data = capture(player, profileId);
        if (data != null) {
            rows.saveNow(profileId, player.getUniqueId(), data);
            plugin.eco().save(player.getUniqueId(), profileId);
            // the native bank is keyed by (profile, player), so it is already per-profile
        }
    }

    /**
     * Like {@link #save}, but the row is written on the storage thread, after anything already queued there; until
     * it lands, {@link #savedData} returns it. For quits and profile switches. Main thread.
     */
    public void saveQueued(Player player, UUID profileId) {
        ProfileData data = capture(player, profileId);
        if (data != null) {
            rows.saveLater(profileId, player.getUniqueId(), data);
            plugin.eco().save(player.getUniqueId(), profileId);
        }
    }

    /** The player's saved row of a profile, including a queued save not yet written. Any thread. */
    public @Nullable ProfileData savedData(UUID profileId, UUID player) {
        ProfileData queued = rows.pending(profileId, player);
        return queued != null ? queued : storage.getProfileData(profileId, player);
    }

    /**
     * Like {@link #save}, but only the capture runs here; the row is written on the storage thread, and
     * skipped if a later save of the same row happened first. Main thread.
     */
    public void saveInBackground(Player player, UUID profileId) {
        if (profileId == null || loadFailed.contains(player.getUniqueId())) {
            return;   // already reported at load; save() reports it again on quit
        }
        ProfileData data = capture(player, profileId);
        if (data != null) {
            rows.saveLater(profileId, player.getUniqueId(), data);
            plugin.eco().save(player.getUniqueId(), profileId);
        }
    }

    // The row to write, or null when none may be written (nothing to save, load failed, or an item would
    // not serialize; eco progression is still saved in that last case).
    private @Nullable ProfileData capture(Player player, UUID profileId) {
        if (profileId == null) {
            return null;
        }
        if (loadFailed.contains(player.getUniqueId())) {
            plugin.getLogger().severe("Refusing to save profile " + profileId + " for " + player.getName()
                    + ": their last profile load failed to deserialize, so what they are carrying is the"
                    + " emptied fallback, not their real inventory. The stored row is being kept.");
            return null;
        }
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot : externallyManagedSlots()) {
            if (slot < contents.length) {
                contents[slot] = null;
            }
        }
        byte[] inventory = serialize(contents);
        byte[] enderChest = serialize(player.getEnderChest().getContents());

        // A null blob means serialization failed (an item Bukkit can't write). load() reads null as an empty
        // profile, so keep the last good row instead of wiping it.
        if (inventory == null || enderChest == null) {
            plugin.getLogger().severe("Refusing to save profile " + profileId + " for " + player.getName()
                    + ": item serialization failed, so the previous save is being kept. "
                    + "This session's inventory changes are NOT saved; check the error above for the item.");
            plugin.eco().save(player.getUniqueId(), profileId);   // progression is unaffected, still save it
            return null;
        }
        return new ProfileData(
                inventory,
                enderChest,
                player.getLevel(),
                player.getExp(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation());
    }

    /** Load {@code profileId}'s saved data (or a fresh slate) onto the player, plus its eco shadow. */
    public void load(Player player, UUID profileId) {
        load(player, profileId, null);
    }

    /**
     * Apply a profile's saved state. {@code preloaded} is the row already read off-thread by
     * {@code ProfileManager.preload}; pass null to read it here (the fallback path).
     */
    public void load(Player player, UUID profileId, ProfileData preloaded) {
        if (profileId == null) {
            return;
        }
        ProfileData data = preloaded != null ? preloaded : savedData(profileId, player.getUniqueId());
        if (data == null) {
            data = ProfileData.fresh();
        }

        int size = player.getInventory().getSize();
        ItemStack[] restored = data.inventory() == null ? new ItemStack[size] : deserialize(data.inventory());
        ItemStack[] chest = data.enderChest() == null ? null : deserialize(data.enderChest());
        // Read-side twin of the null-blob guard in save(): give empty containers for the session, flag the
        // player so save() keeps the stored row, and tell them.
        if ((data.inventory() != null && restored == null) || (data.enderChest() != null && chest == null)) {
            loadFailed.add(player.getUniqueId());
            player.getInventory().clear();
            player.getEnderChest().clear();
            player.updateInventory();
            plugin.getLogger().severe("Could not deserialize saved items of profile " + profileId + " for "
                    + player.getName() + ". Their inventory is empty for this session and will NOT be saved"
                    + " over the stored one: the row is intact; check the error above for the cause.");
            return;
        }
        loadFailed.remove(player.getUniqueId());
        // slots another plugin manages were never saved, so restoring over them would delete or duplicate the item
        for (int slot : externallyManagedSlots()) {
            if (slot < restored.length) {
                restored[slot] = player.getInventory().getItem(slot);
            }
        }
        player.getInventory().setContents(restored);
        if (chest == null) {
            player.getEnderChest().clear();
        } else {
            player.getEnderChest().setContents(chest);
        }

        player.setLevel(Math.max(0, data.expLevel()));
        player.setExp(clamp01(data.expProgress()));
        player.setFoodLevel(Math.max(0, Math.min(20, data.food())));
        player.setSaturation(Math.max(0f, data.saturation()));
        player.setHealth(Math.max(1.0, Math.min(data.health(), DEFAULT_MAX_HEALTH)));

        plugin.eco().load(player.getUniqueId(), profileId);
        player.updateInventory();
    }

    // Hotbar slots (1-9) owned by another plugin (e.g. RoyalJoin), left out of the profile snapshot so
    // a pinned item isn't captured into one profile and handed back on another.
    private int[] externallyManagedSlots() {
        List<Integer> configured = plugin.conf().getIntegerList("profile.externally-managed-hotbar-slots");
        if (configured.isEmpty()) {
            return EMPTY_SLOTS;
        }
        return configured.stream()
                .filter(slot -> slot >= 1 && slot <= 9)
                .mapToInt(slot -> slot - 1)
                .toArray();
    }

    /**
     * Every item in a saved row (inventory then ender chest, empty slots dropped), for a coop payout.
     * Null if the row can't be decoded, which callers must treat as "keep the row", never "nothing there".
     */
    public List<ItemStack> itemsOf(ProfileData data) {
        List<ItemStack> out = new java.util.ArrayList<>();
        for (byte[] blob : new byte[][]{data.inventory(), data.enderChest()}) {
            if (blob == null) {
                continue;
            }
            ItemStack[] items = deserialize(blob);
            if (items == null) {
                return null;
            }
            for (ItemStack item : items) {
                if (item != null && !item.getType().isAir()) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    /** A row holding only {@code items}: what is left of a payout that did not fit. Null on failure. */
    public ProfileData leftoverRow(List<ItemStack> items) {
        byte[] blob = serialize(items.toArray(new ItemStack[0]));
        return blob == null ? null : new ProfileData(blob, null, 0, 0f, DEFAULT_MAX_HEALTH, 20, 5f);
    }

    private byte[] serialize(ItemStack[] items) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeInt(items.length);
            for (ItemStack item : items) {
                out.writeObject(item);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (Exception e) {
            plugin.getLogger().severe("Could not serialize items: " + e.getMessage());
            return null;
        }
    }

    // the stored items, or null when the blob can't be read; callers must not treat that as empty
    private ItemStack[] deserialize(byte[] data) {
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(data))) {
            int length = in.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) in.readObject();
            }
            return items;
        } catch (Exception e) {
            plugin.getLogger().severe("Could not deserialize items: " + e.getMessage());
            return null;
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(0.9999f, v));
    }
}
