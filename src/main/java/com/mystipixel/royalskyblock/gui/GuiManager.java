package com.mystipixel.royalskyblock.gui;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.gui.menu.MenuEffect;
import com.mystipixel.royalskyblock.gui.menu.MenuSlot;
import com.mystipixel.royalskyblock.gui.menu.MenuTemplate;
import com.mystipixel.royalskyblock.hooks.EcoHook;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.island.IslandRole;
import com.mystipixel.royalskyblock.island.IslandSetting;
import com.mystipixel.royalskyblock.profile.Profile;
import com.mystipixel.royalskyblock.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Loads, renders, and drives the {@code gui/*.yml} menus in the shared EcoMenus dialect. Buttons run
 * effect lists; the standard effects delegate to commands ({@code player_command}) so the GUI stays a
 * thin launcher over existing logic instead of duplicating it.
 *
 * <p>New menus (profile switcher, upgrades, ...) are just new {@code gui/<id>.yml} files registered in
 * {@link #MENUS} — the engine renders them with no extra code.
 */
public final class GuiManager implements Listener {

    /** Menu ids ↔ gui/<id>.yml file names ↔ the {@code open_menu} effect's {@code menu} arg. */
    public static final String MAIN = "main";
    public static final String CONFIRM_DELETE = "confirm-delete";
    public static final String PROFILES = "profiles";
    public static final String CREATE_PROFILE = "create-profile";
    public static final String SETTINGS = "settings";

    private static final String[] MENUS = {MAIN, CONFIRM_DELETE, PROFILES, CREATE_PROFILE, SETTINGS};

    private final RoyalSkyblockPlugin plugin;
    private final EcoHook ecoHook;
    private final Map<String, MenuTemplate> byId = new LinkedHashMap<>();

    public GuiManager(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.ecoHook = new EcoHook();
        reload();
    }

    public void reload() {
        byId.clear();
        for (String id : MENUS) {
            File file = new File(plugin.getDataFolder(), "gui/" + id + ".yml");
            if (!file.exists()) {
                plugin.saveResource("gui/" + id + ".yml", false);
            }
            byId.put(id, MenuTemplate.load(file, "&6&lSkyblock", 5));
        }
    }

    /** Open a menu for a player, rendering items with their placeholders. */
    public void open(Player player, String menuId) {
        MenuTemplate template = byId.get(menuId);
        if (template == null) {
            plugin.getLogger().warning("Tried to open unknown menu '" + menuId + "'.");
            return;
        }
        Map<String, String> placeholders = placeholders(player);

        MenuHolder holder = new MenuHolder(menuId);
        Inventory inv = Bukkit.createInventory(holder, template.size(),
                Text.color(apply(template.title(), placeholders)));
        holder.setInventory(inv);

        template.applyFiller(inv);
        for (MenuSlot slot : template.slots()) {
            ItemStack item = slot.item().build(ecoHook, placeholders, slot.lore());
            inv.setItem(slot.index(), item);
        }
        fillDynamic(menuId, player, template, inv, holder);
        player.openInventory(inv);
    }

    /** Fill data-driven menus (profile list, settings toggles) into their mask content slots. */
    private void fillDynamic(String menuId, Player player, MenuTemplate template, Inventory inv, MenuHolder holder) {
        if (menuId.equals(SETTINGS)) {
            fillSettings(player, template, inv, holder);
            return;
        }
        if (!menuId.equals(PROFILES)) {
            return;
        }
        List<Profile> profiles = plugin.profiles().getProfiles(player.getUniqueId());
        UUID active = plugin.profiles().getActiveProfileId(player.getUniqueId());
        List<Integer> slots = template.contentSlots();
        for (int i = 0; i < profiles.size() && i < slots.size(); i++) {
            Profile profile = profiles.get(i);
            int slot = slots.get(i);
            inv.setItem(slot, profileIcon(profile, active));
            holder.putAction(slot, viewer -> {
                viewer.closeInventory();
                plugin.profiles().switchProfile(viewer, profile.id());
            });
        }
    }

    private ItemStack profileIcon(Profile profile, UUID active) {
        var ruleset = plugin.gamemodes().ruleset(profile.gamemode());
        Material material = Material.matchMaterial(ruleset.icon().toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem()) {
            material = Material.GRASS_BLOCK;
        }
        boolean isActive = profile.id().equals(active);
        boolean hasIsland = plugin.islands().getIslandByProfile(profile.id()) != null;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String title = "&e&l" + profile.name() + " &r" + ruleset.displayName() + (isActive ? " &a(active)" : "");
            meta.displayName(noItalic(title));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(noItalic(ruleset.description()));
            lore.add(noItalic("&7Island: " + (hasIsland ? "&ayes" : "&cno")));
            lore.add(noItalic(""));
            lore.add(noItalic(isActive ? "&8You're on this profile." : "&eClick to switch!"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillSettings(Player player, MenuTemplate template, Inventory inv, MenuHolder holder) {
        UUID activeId = plugin.profiles().getActiveProfileId(player.getUniqueId());
        Island island = activeId == null ? null : plugin.islands().getIslandByProfile(activeId);
        if (island == null) {
            return;
        }
        Profile profile = plugin.profiles().getProfile(island.profileId());
        IslandRole role = profile == null ? IslandRole.VISITOR : profile.roleOf(player.getUniqueId());
        boolean canEdit = role == IslandRole.OWNER || role == IslandRole.CO_OWNER;

        List<Integer> slots = template.contentSlots();
        IslandSetting[] all = IslandSetting.values();
        for (int i = 0; i < all.length && i < slots.size(); i++) {
            IslandSetting setting = all[i];
            int slot = slots.get(i);
            inv.setItem(slot, settingIcon(setting, island.isEnabled(setting), canEdit));
            if (canEdit) {
                holder.putAction(slot, viewer -> {
                    island.setSetting(setting, !island.isEnabled(setting));
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.storage().saveIsland(island));
                    open(viewer, SETTINGS);
                });
            }
        }
    }

    private ItemStack settingIcon(IslandSetting setting, boolean on, boolean canEdit) {
        Material material = Material.matchMaterial(setting.icon().toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem()) {
            material = Material.PAPER;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(setting.displayName() + (on ? " &a&lON" : " &c&lOFF")));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(noItalic("&7" + setting.description()));
            lore.add(noItalic(""));
            lore.add(noItalic(canEdit ? (on ? "&eClick to disable" : "&eClick to enable")
                    : "&8Only the owner can change this."));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static net.kyori.adventure.text.Component noItalic(String legacy) {
        return Text.color(legacy).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    /** Placeholders available to every menu. Extended as systems come online (level, profile, ...). */
    private Map<String, String> placeholders(Player player) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("player", player.getName());
        map.put("has_island", String.valueOf(plugin.profiles().activeHasIsland(player)));
        return map;
    }

    // ── click handling ───────────────────────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true); // menus are read-only; buttons act via effects
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int raw = event.getRawSlot();
        MenuTemplate template = byId.get(holder.menuId());
        if (template == null || raw < 0 || raw >= template.size()) {
            return;
        }
        Consumer<Player> dynamic = holder.action(raw);
        if (dynamic != null) {
            runNextTick(() -> dynamic.accept(player));
            return;
        }
        MenuSlot slot = template.slotAt(raw);
        if (slot == null) {
            return;
        }
        List<MenuEffect> effects = event.isRightClick() && !slot.rightClick().isEmpty()
                ? slot.rightClick() : slot.leftClick();
        for (MenuEffect effect : effects) {
            execute(player, effect);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    private void execute(Player player, MenuEffect effect) {
        switch (effect.id().toLowerCase(Locale.ROOT)) {
            case "open_menu" -> runNextTick(() -> open(player, effect.argString("menu", MAIN)));
            case "close" -> runNextTick(player::closeInventory);
            case "player_command" -> runNextTick(() -> {
                player.closeInventory();
                player.performCommand(apply(effect.argString("command", ""), placeholders(player)));
            });
            case "console_command" -> runNextTick(() -> Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(), apply(effect.argString("command", ""), placeholders(player))));
            case "message" -> player.sendMessage(Text.color(apply(effect.argString("message", ""), placeholders(player))));
            default -> plugin.getLogger().warning("Unknown menu effect '" + effect.id() + "'.");
        }
    }

    private void runNextTick(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    private static String apply(String input, Map<String, String> placeholders) {
        if (input == null) {
            return "";
        }
        String result = input;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            result = result.replace("%" + e.getKey() + "%", e.getValue());
        }
        return result;
    }
}
