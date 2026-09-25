package com.mystipixel.royalskyblock.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * One render of a data-driven menu: what each slot shows and what the code-registered slots do when
 * clicked.
 *
 * <p>The {@code fillX} builders in {@link GuiManager} paint into one of these, and eco's render reads it
 * back slot by slot (see {@link EcoMenuFactory#buildDynamic}). It replaces the throwaway Bukkit
 * inventory and fake {@code InventoryHolder} the builders used to fill, which only existed so the old
 * Bukkit-inventory engine's builders could run unchanged under eco. A fresh canvas is made per render,
 * so a refresh redraws from current state and nothing outlives the render that produced it.
 */
public final class MenuCanvas {

    private final String menuId;
    private final String context;   // optional target for per-subject menus (e.g. a coop member name)
    private final ItemStack[] items;
    // slot -> action(player, rightClick)
    private final Map<Integer, BiConsumer<Player, Boolean>> actions = new HashMap<>();

    public MenuCanvas(String menuId, @Nullable String context, int size) {
        this.menuId = menuId;
        this.context = context;
        this.items = new ItemStack[size];
    }

    public String menuId() {
        return menuId;
    }

    public @Nullable String context() {
        return context;
    }

    public int size() {
        return items.length;
    }

    void setItem(int slot, @Nullable ItemStack item) {
        items[slot] = item;
    }

    @Nullable ItemStack item(int slot) {
        return items[slot];
    }

    void putAction(int slot, BiConsumer<Player, Boolean> action) {
        actions.put(slot, action);
    }

    @Nullable BiConsumer<Player, Boolean> action(int slot) {
        return actions.get(slot);
    }
}
