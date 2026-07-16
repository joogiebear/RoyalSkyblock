package com.mystipixel.royalskyblock.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Marks an inventory as a RoyalSkyblock menu (so the click listener owns it) and carries the menu id
 * plus any per-slot dynamic click actions (used by data-driven menus like the profile list, where the
 * clickable items aren't fixed in the yml).
 */
public final class MenuHolder implements InventoryHolder {

    private final String menuId;
    private final String context;   // optional target for per-subject menus (e.g. a coop member name)
    private Inventory inventory;
    // slot -> action(player, rightClick)
    private final Map<Integer, BiConsumer<Player, Boolean>> actions = new HashMap<>();

    public MenuHolder(String menuId) {
        this(menuId, null);
    }

    public MenuHolder(String menuId, @Nullable String context) {
        this.menuId = menuId;
        this.context = context;
    }

    public String menuId() {
        return menuId;
    }

    public @Nullable String context() {
        return context;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    void putAction(int slot, BiConsumer<Player, Boolean> action) {
        actions.put(slot, action);
    }

    @Nullable BiConsumer<Player, Boolean> action(int slot) {
        return actions.get(slot);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
