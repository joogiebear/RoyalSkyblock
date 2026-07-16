package com.mystipixel.royalskyblock.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Marks an inventory as a RoyalSkyblock menu (so the click listener owns it) and carries the menu id
 * plus any per-slot dynamic click actions (used by data-driven menus like the profile list, where the
 * clickable items aren't fixed in the yml).
 */
public final class MenuHolder implements InventoryHolder {

    private final String menuId;
    private Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    public MenuHolder(String menuId) {
        this.menuId = menuId;
    }

    public String menuId() {
        return menuId;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    void putAction(int slot, Consumer<Player> action) {
        actions.put(slot, action);
    }

    @Nullable Consumer<Player> action(int slot) {
        return actions.get(slot);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
