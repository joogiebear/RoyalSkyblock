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
import java.util.function.BiConsumer;

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
    public static final String UPGRADES = "upgrades";
    public static final String VISIT = "visit";

    private static final String[] MENUS = {MAIN, CONFIRM_DELETE, PROFILES, CREATE_PROFILE, SETTINGS, UPGRADES, VISIT};

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
        if (menuId.equals(UPGRADES)) {
            fillUpgrades(player, template, inv, holder);
            return;
        }
        if (menuId.equals(VISIT)) {
            fillVisit(player, template, inv, holder);
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
            holder.putAction(slot, (viewer, right) -> {
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
                holder.putAction(slot, (viewer, right) -> {
                    island.setSetting(setting, !island.isEnabled(setting));
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.storage().saveIsland(island));
                    open(viewer, SETTINGS);
                });
            }
        }
    }

    /**
     * Tick live upgrade countdowns. Guarded so it does nothing unless something is actually cooking,
     * and even then only re-draws the specific timer icons for players currently viewing the menu.
     */
    public void tickOpenMenus() {
        if (!plugin.upgrades().hasAnyPending()) {
            return; // nothing cooking anywhere — zero work
        }
        MenuTemplate template = byId.get(UPGRADES);
        if (template == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder holder
                    && UPGRADES.equals(holder.menuId())) {
                // Re-render (cheap: a few icons) so pinned/auto slots + countdowns stay correct.
                fillUpgrades(player, template, holder.getInventory(), holder);
            }
        }
    }

    private void fillUpgrades(Player player, MenuTemplate template, Inventory inv, MenuHolder holder) {
        UUID activeId = plugin.profiles().getActiveProfileId(player.getUniqueId());
        Island island = activeId == null ? null : plugin.islands().getIslandByProfile(activeId);
        if (island == null) {
            return;
        }
        Profile profile = plugin.profiles().getProfile(island.profileId());
        IslandRole role = profile == null ? IslandRole.VISITOR : profile.roleOf(player.getUniqueId());
        boolean canEdit = role == IslandRole.OWNER || role == IslandRole.CO_OWNER;

        Map<String, Integer> named = template.namedContentSlots();
        List<Integer> auto = template.contentSlots();
        int autoIndex = 0;
        for (com.mystipixel.royalskyblock.upgrade.UpgradeDef def : plugin.upgrades().all()) {
            Integer slot = named.get(def.key().toLowerCase(Locale.ROOT));
            if (slot == null) {
                if (autoIndex >= auto.size()) {
                    continue; // no pinned slot and no auto slot left
                }
                slot = auto.get(autoIndex++);
            }
            inv.setItem(slot, upgradeIcon(def, island));
            if (canEdit) {
                holder.putAction(slot, (viewer, right) -> {
                    handleUpgradeClick(viewer, def, right);
                    open(viewer, UPGRADES);
                });
            }
        }
    }

    private void handleUpgradeClick(Player viewer, com.mystipixel.royalskyblock.upgrade.UpgradeDef def, boolean skip) {
        UUID activeId = plugin.profiles().getActiveProfileId(viewer.getUniqueId());
        Island island = activeId == null ? null : plugin.islands().getIslandByProfile(activeId);
        if (island == null) {
            return;
        }
        var result = skip ? plugin.upgrades().skip(viewer, island, def)
                : plugin.upgrades().start(viewer, island, def);
        switch (result) {
            case STARTED -> {
                var next = def.tier(island.upgradeTier(def.key()) + 1);
                plugin.messages().send(viewer, "upgrade.started", "upgrade", def.displayName(),
                        "time", next != null ? formatDuration(next.timeSeconds()) : "?");
            }
            case COMPLETED -> plugin.messages().send(viewer, skip ? "upgrade.skipped" : "upgrade.completed",
                    "upgrade", def.displayName(), "tier", String.valueOf(island.upgradeTier(def.key())));
            case MAXED -> plugin.messages().send(viewer, "upgrade.maxed", "upgrade", def.displayName());
            case IN_PROGRESS -> plugin.messages().send(viewer, "upgrade.in-progress", "upgrade", def.displayName(),
                    "time", pendingTimeLeft(island, def));
            case NOT_IN_PROGRESS -> plugin.messages().send(viewer, "upgrade.not-in-progress");
            case CANT_AFFORD -> plugin.messages().send(viewer, "upgrade.cant-afford");
        }
    }

    private String pendingTimeLeft(Island island, com.mystipixel.royalskyblock.upgrade.UpgradeDef def) {
        var pending = plugin.upgrades().pendingFor(island, def);
        return pending == null ? "0s" : formatDuration(pending.secondsLeft(System.currentTimeMillis()));
    }

    private ItemStack upgradeIcon(com.mystipixel.royalskyblock.upgrade.UpgradeDef def, Island island) {
        Material material = Material.matchMaterial(def.icon().toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem()) {
            material = Material.ANVIL;
        }
        int current = island.upgradeTier(def.key());
        var pending = plugin.upgrades().pendingFor(island, def);

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(def.displayName() + " &7(Tier " + current + "/" + def.maxTier() + ")"));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(noItalic("&7" + def.description()));
            lore.add(noItalic(""));
            if (pending != null) {
                lore.add(noItalic("&e⏳ Upgrading to tier " + pending.targetTier()
                        + " &7— " + formatDuration(pending.secondsLeft(System.currentTimeMillis())) + " left"));
                var t = def.tier(pending.targetTier());
                if (t != null && !t.skipCost().isFree()) {
                    lore.add(noItalic("&7Skip now: &e" + plugin.currency().format(t.skipCost())));
                    lore.add(noItalic(""));
                    lore.add(noItalic("&eRight-click to skip the wait."));
                }
            } else {
                var next = def.nextTier(current);
                if (next == null) {
                    lore.add(noItalic("&a✔ Maxed out."));
                } else {
                    lore.add(noItalic("&7Next: tier " + next.tier() + " &8(value " + fmt(next.value()) + ")"));
                    lore.add(noItalic("&7Cost: &e" + plugin.currency().format(next.cost())
                            + (next.isInstant() ? "" : " &7+ " + formatDuration(next.timeSeconds()) + " wait")));
                    if (!next.skipCost().isFree()) {
                        lore.add(noItalic("&7Skip cost: &e" + plugin.currency().format(next.skipCost())));
                    }
                    lore.add(noItalic(""));
                    lore.add(noItalic("&eClick to upgrade" + (next.skipCost().isFree() ? "" : " &7(right-click after to skip)")));
                }
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String formatDuration(long seconds) {
        if (seconds <= 0) {
            return "instant";
        }
        long d = seconds / 86_400, h = (seconds % 86_400) / 3_600, m = (seconds % 3_600) / 60, s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 && d == 0) sb.append(s).append("s");
        return sb.toString().trim();
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

    private void fillVisit(Player player, MenuTemplate template, Inventory inv, MenuHolder holder) {
        Profile viewer = plugin.profiles().getProfile(plugin.profiles().getActiveProfileId(player.getUniqueId()));
        if (viewer == null) {
            return;
        }
        com.mystipixel.royalskyblock.profile.Gamemode mode = viewer.gamemode();
        List<Integer> slots = template.contentSlots();
        int i = 0;
        for (Island island : plugin.storage().getAllIslands()) {
            if (!island.isEnabled(IslandSetting.VISITORS_ALLOWED) || !island.isEnabled(IslandSetting.LISTED)) {
                continue;
            }
            Profile prof = plugin.profiles().getProfile(island.profileId());
            if (prof == null || prof.gamemode() != mode || prof.isMember(player.getUniqueId())) {
                continue; // gamemode must match; don't list your own
            }
            if (i >= slots.size()) {
                break;
            }
            int slot = slots.get(i++);
            String ownerName = ownerName(prof);
            inv.setItem(slot, islandBrowserIcon(island, prof, ownerName));
            holder.putAction(slot, (v, right) -> {
                v.closeInventory();
                visitFromBrowser(v, island, prof, ownerName);
            });
        }
    }

    private void visitFromBrowser(Player viewer, Island island, Profile prof, String ownerName) {
        org.bukkit.World world = plugin.getServer().getWorld(island.worldName());
        if (world != null && !viewer.hasPermission("royalskyblock.bypass")) {
            int visitors = 0;
            for (Player p : world.getPlayers()) {
                if (!prof.isMember(p.getUniqueId())) {
                    visitors++;
                }
            }
            if (visitors >= plugin.upgrades().guestLimit(island)) {
                plugin.messages().send(viewer, "visit.full", "player", ownerName);
                return;
            }
        }
        plugin.islands().teleportVisitor(viewer, island).whenComplete((ok, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.messages().send(viewer, "visit.failed", "error", error.getMessage());
                    } else {
                        plugin.messages().send(viewer, "visit.visiting", "player", ownerName);
                    }
                }));
    }

    private ItemStack islandBrowserIcon(Island island, Profile prof, String ownerName) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.SkullMeta skull) {
            try {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(prof.owner()));
            } catch (Throwable ignored) {
                // head lookup failed — leave default
            }
        }
        if (meta != null) {
            meta.displayName(noItalic("&a" + ownerName + "&7's Island"));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(noItalic("&7Gamemode: &f" + prof.gamemode().name().toLowerCase(Locale.ROOT)));
            lore.add(noItalic("&7Island level: &f" + (int) island.level()));
            lore.add(noItalic("&7Members: &f" + prof.memberCount()));
            lore.add(noItalic(""));
            lore.add(noItalic("&eClick to visit!"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String ownerName(Profile prof) {
        String name = Bukkit.getOfflinePlayer(prof.owner()).getName();
        return name != null ? name : prof.name();
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
        BiConsumer<Player, Boolean> dynamic = holder.action(raw);
        if (dynamic != null) {
            boolean rightClick = event.isRightClick();
            playClick(player);
            runNextTick(() -> dynamic.accept(player, rightClick));
            return;
        }
        MenuSlot slot = template.slotAt(raw);
        if (slot == null) {
            return;
        }
        List<MenuEffect> effects = event.isRightClick() && !slot.rightClick().isEmpty()
                ? slot.rightClick() : slot.leftClick();
        if (!effects.isEmpty()) {
            playClick(player);
        }
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
            case "play_sound" -> {
                String sound = effect.argString("sound", "ui.button.click").replace('_', '.');
                try {
                    player.playSound(player.getLocation(), sound,
                            (float) doubleArg(effect, "volume", 0.6), (float) doubleArg(effect, "pitch", 1.2));
                } catch (Throwable ignored) {
                    // bad sound key — ignore
                }
            }
            default -> plugin.getLogger().warning("Unknown menu effect '" + effect.id() + "'.");
        }
    }

    private void runNextTick(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    private void playClick(Player player) {
        try {
            player.playSound(player.getLocation(), "ui.button.click", 0.6f, 1.2f);
        } catch (Throwable ignored) {
            // sound unavailable — never let it break a click
        }
    }

    private static double doubleArg(MenuEffect effect, String key, double def) {
        try {
            return Double.parseDouble(effect.argString(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
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
