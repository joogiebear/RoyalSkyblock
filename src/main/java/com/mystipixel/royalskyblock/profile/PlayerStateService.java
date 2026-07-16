package com.mystipixel.royalskyblock.profile;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.data.Storage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

    private final RoyalSkyblockPlugin plugin;
    private final Storage storage;

    public PlayerStateService(RoyalSkyblockPlugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    /** Capture the player's current state into {@code profileId}'s saved data + eco shadow. */
    public void save(Player player, UUID profileId) {
        if (profileId == null) {
            return;
        }
        ProfileData data = new ProfileData(
                serialize(player.getInventory().getContents()),
                serialize(player.getEnderChest().getContents()),
                player.getLevel(),
                player.getExp(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation());
        storage.saveProfileData(profileId, player.getUniqueId(), data);
        plugin.eco().save(player.getUniqueId(), profileId);
        // Per-profile personal bank: stash this profile's bank state (no-op without RoyalBank).
        var bank = plugin.personalBank();
        if (bank.active()) {
            storage.saveBankSnapshot(profileId, player.getUniqueId(), bank.export(player.getUniqueId()));
        }
    }

    /** Load {@code profileId}'s saved data (or a fresh slate) onto the player, plus its eco shadow. */
    public void load(Player player, UUID profileId) {
        if (profileId == null) {
            return;
        }
        ProfileData data = storage.getProfileData(profileId, player.getUniqueId());
        if (data == null) {
            data = ProfileData.fresh();
        }

        if (data.inventory() == null) {
            player.getInventory().clear();
        } else {
            player.getInventory().setContents(deserialize(data.inventory(), player.getInventory().getSize()));
        }
        if (data.enderChest() == null) {
            player.getEnderChest().clear();
        } else {
            player.getEnderChest().setContents(deserialize(data.enderChest(), player.getEnderChest().getSize()));
        }

        player.setLevel(Math.max(0, data.expLevel()));
        player.setExp(clamp01(data.expProgress()));
        player.setFoodLevel(Math.max(0, Math.min(20, data.food())));
        player.setSaturation(Math.max(0f, data.saturation()));
        player.setHealth(Math.max(1.0, Math.min(data.health(), DEFAULT_MAX_HEALTH)));

        plugin.eco().load(player.getUniqueId(), profileId);

        // Per-profile personal bank: restore this profile's saved bank, or reset to a fresh account if
        // it has none (a new profile). handleJoin seeds the active profile first so migration doesn't wipe.
        var bank = plugin.personalBank();
        if (bank.active()) {
            com.mystipixel.royalskyblock.bank.BankSnapshotState saved =
                    storage.getBankSnapshot(profileId, player.getUniqueId());
            if (saved != null) {
                bank.restore(player.getUniqueId(), saved);
            } else {
                bank.reset(player.getUniqueId());
            }
        }
        player.updateInventory();
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

    private ItemStack[] deserialize(byte[] data, int fallbackSize) {
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(data))) {
            int length = in.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) in.readObject();
            }
            return items;
        } catch (Exception e) {
            plugin.getLogger().severe("Could not deserialize items: " + e.getMessage());
            return new ItemStack[fallbackSize];
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(0.9999f, v));
    }
}
