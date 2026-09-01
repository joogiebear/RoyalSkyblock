package com.mystipixel.royalskyblock.profile;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.data.Storage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

/**
 * Swaps a player's live state in and out per profile: inventory, ender chest, XP and vitals (stored in
 * {@code profile_data}), plus eco progression (via the eco bridge). Item arrays are serialized with
 * Bukkit's object stream so slot positions and empty slots round-trip exactly.
 *
 * <p>All methods touch the live player, so they must run on the server thread.
 */
public final class PlayerStateService {

    private static final double DEFAULT_MAX_HEALTH = 20.0;
    private static final int[] EMPTY_SLOTS = new int[0];

    private final RoyalSkyblockPlugin plugin;
    private final Storage storage;

    /**
     * Players whose most recent load could not deserialize its saved items. While a player is in this
     * set, {@link #save} refuses to run: the live inventory is the empty one that replaced the
     * unreadable blob, and persisting it would overwrite the stored row — turning a transient decode
     * failure (version drift, a foreign item) into permanent loss. The flag clears on the next
     * successful load.
     */
    private final java.util.Set<UUID> loadFailed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PlayerStateService(RoyalSkyblockPlugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    /** Capture the player's current state into {@code profileId}'s saved data + eco shadow. */
    public void save(Player player, UUID profileId) {
        if (profileId == null) {
            return;
        }
        if (loadFailed.contains(player.getUniqueId())) {
            plugin.getLogger().severe("Refusing to save profile " + profileId + " for " + player.getName()
                    + ": their last profile load failed to deserialize, so what they are carrying is the"
                    + " emptied fallback, not their real inventory. The stored row is being kept.");
            return;
        }
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot : externallyManagedSlots()) {
            if (slot < contents.length) {
                contents[slot] = null;
            }
        }
        byte[] inventory = serialize(contents);
        byte[] enderChest = serialize(player.getEnderChest().getContents());

        // A null blob means serialization failed (an item Bukkit can't write — a foreign plugin item,
        // version drift). Persisting it would be destructive: load() treats null as "empty profile" and
        // clears the inventory, so one bad item would silently wipe everything on the next login.
        // Keep the last good row instead — a stale save is recoverable, a wipe is not.
        if (inventory == null || enderChest == null) {
            plugin.getLogger().severe("Refusing to save profile " + profileId + " for " + player.getName()
                    + ": item serialization failed, so the previous save is being kept. "
                    + "This session's inventory changes are NOT saved — check the error above for the item.");
            plugin.eco().save(player.getUniqueId(), profileId);   // progression is unaffected, still save it
            return;
        }

        ProfileData data = new ProfileData(
                inventory,
                enderChest,
                player.getLevel(),
                player.getExp(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation());
        storage.saveProfileData(profileId, player.getUniqueId(), data);
        plugin.eco().save(player.getUniqueId(), profileId);
        // The native bank is keyed by (profile, player), so it's already per-profile — nothing to swap.
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
        ProfileData data = preloaded != null ? preloaded : storage.getProfileData(profileId, player.getUniqueId());
        if (data == null) {
            data = ProfileData.fresh();
        }

        int size = player.getInventory().getSize();
        ItemStack[] restored = data.inventory() == null ? new ItemStack[size] : deserialize(data.inventory());
        ItemStack[] chest = data.enderChest() == null ? null : deserialize(data.enderChest());
        // An unreadable blob must not end in a wipe. save() already refuses to persist when items fail
        // to WRITE; this is the read-side twin: hand the player empty containers for the session (the
        // stored row still holds their gear), flag them so save() keeps the row intact, and say so.
        if ((data.inventory() != null && restored == null) || (data.enderChest() != null && chest == null)) {
            loadFailed.add(player.getUniqueId());
            player.getInventory().clear();
            player.getEnderChest().clear();
            player.updateInventory();
            plugin.getLogger().severe("Could not deserialize saved items of profile " + profileId + " for "
                    + player.getName() + ". Their inventory is empty for this session and will NOT be saved"
                    + " over the stored one — the row is intact; check the error above for the cause.");
            return;
        }
        loadFailed.remove(player.getUniqueId());
        // Whatever another plugin put in these slots stays put: they were never saved, so restoring
        // over them would either delete the item or hand back a stale copy alongside a fresh one.
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

    /**
     * Inventory slots owned by another plugin, excluded from the profile snapshot.
     *
     * <p>A per-profile inventory and a plugin that pins an item to a slot are otherwise in direct
     * conflict: the pinned item gets captured into whichever profile was active, then handed back on a
     * different one, so it either duplicates or disappears. Naming the slot here settles the ownership
     * question — RoyalSkyblock saves everything else and leaves that square alone.
     *
     * <p>Configured as hotbar positions 1-9, matching how menus and RoyalJoin place things.
     */
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

    // ── serialization ────────────────────────────────────────────────────────────

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

    /** The stored items, or null when the blob cannot be read — callers must not treat that as empty. */
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
