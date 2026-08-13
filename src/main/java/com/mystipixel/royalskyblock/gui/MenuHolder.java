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
 * Collects what a data-driven menu's content builder produced: the menu id, an optional subject, and
 * the per-slot click actions for items that are not fixed in the yml (the profile list, upgrade tiers,
 * the visit browser).
 *
 * <p><b>No longer a live inventory holder.</b> It originally marked an open inventory as a
 * RoyalSkyblock menu so the click listener could claim it. Every menu is now an eco Menu and eco owns
 * clicks, so nothing a player sees is held by one of these. What remains is its use as a scratch
 * collector: {@code GuiManager.renderDynamic} hands a throwaway inventory and one of these to the
 * existing {@code fillX} builders, then reads the actions back out. That is what let thirteen content
 * builders move to eco without being rewritten, and it is why this class and its
 * {@link InventoryHolder} implementation still exist.
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
