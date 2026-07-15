package com.mystipixel.royalskyblock.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marks an inventory as a RoyalSkyblock menu so the click listener knows it owns it, and carries which
 * menu id is open (for routing effects like {@code open_menu}).
 */
public final class MenuHolder implements InventoryHolder {

    private final String menuId;
    private Inventory inventory;

    public MenuHolder(String menuId) {
        this.menuId = menuId;
    }

    public String menuId() {
        return menuId;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
