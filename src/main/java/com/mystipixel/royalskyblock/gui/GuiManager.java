package com.mystipixel.royalskyblock.gui;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.bank.CoopAccountView;
import com.mystipixel.royalskyblock.bank.CoopBank;
import com.mystipixel.royalskyblock.bank.CoopTxn;
import com.mystipixel.royalskyblock.bank.CoopUpgrade;
import com.mystipixel.royalskyblock.gui.menu.MenuEffect;
import com.mystipixel.royalskyblock.gui.menu.MenuSlot;
import com.mystipixel.royalskyblock.gui.menu.MenuTemplate;
import com.mystipixel.royalskyblock.hooks.EcoHook;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.island.IslandRole;
import com.mystipixel.royalskyblock.island.IslandSetting;
import com.mystipixel.royalskyblock.level.LevelConfig;
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
    public static final String MANAGE = "manage";
    public static final String COOP = "coop";
    public static final String COOP_INVITE = "coop-invite";
    public static final String COOP_MEMBER = "coop-member";
    public static final String BANK_HUB = "bank-hub";
    public static final String COOP_BANK = "coop-bank";
    public static final String COOP_BANK_TXNS = "coop-bank-txns";
    public static final String LEVEL = "level";
    public static final String TOP = "top";

    private static final String[] MENUS = {MAIN, CONFIRM_DELETE, PROFILES, CREATE_PROFILE, SETTINGS, UPGRADES,
            VISIT, MANAGE, COOP, COOP_INVITE, COOP_MEMBER, BANK_HUB, COOP_BANK, COOP_BANK_TXNS, LEVEL, TOP};

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
        open(player, menuId, null);
    }

    /**
     * Open a menu, optionally carrying a {@code context} (e.g. the coop member a per-member menu acts
     * on) that the dynamic fill can read back off the holder.
     */
    public void open(Player player, String menuId, String context) {
        MenuTemplate template = byId.get(menuId);
        if (template == null) {
            plugin.getLogger().warning("Tried to open unknown menu '" + menuId + "'.");
            return;
        }
        Map<String, String> placeholders = placeholders(player);

        MenuHolder holder = new MenuHolder(menuId, context);
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
        if (menuId.equals(COOP)) {
            fillCoop(player, template, inv, holder);
            return;
        }
        if (menuId.equals(COOP_INVITE)) {
            fillCoopInvite(player, template, inv, holder);
            return;
        }
        if (menuId.equals(COOP_MEMBER)) {
            fillCoopMember(player, template, inv, holder);
            return;
        }
        if (menuId.equals(COOP_BANK)) {
            fillCoopBank(player, template, inv, holder);
            return;
        }
        if (menuId.equals(COOP_BANK_TXNS)) {
            fillCoopBankTxns(player, template, inv, holder);
            return;
        }
        if (menuId.equals(LEVEL)) {
            fillLevel(player, template, inv, holder);
            return;
        }
        if (menuId.equals(TOP)) {
            fillTop(player, template, inv, holder);
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

    // ── coop management ──────────────────────────────────────────────────────────

    /** Fill the coop roster: owner first, then co-owners/members. Owner/co-owner click a member to kick. */
    private void fillCoop(Player player, MenuTemplate template, Inventory inv, MenuHolder holder) {
        Profile profile = plugin.profiles().getActiveProfile(player);
        if (profile == null) {
            return;
        }
        IslandRole myRole = profile.roleOf(player.getUniqueId());
        boolean canManage = myRole == IslandRole.OWNER || myRole == IslandRole.CO_OWNER;

        int max = coopMax(profile);
        inv.setItem(4, coopInfoIcon(profile, max)); // header (row 1, col 5)

        List<com.mystipixel.royalskyblock.profile.ProfileMember> members = new ArrayList<>(profile.members());
        members.sort((a, b) -> Integer.compare(roleRank(a.role()), roleRank(b.role())));

        List<Integer> slots = template.contentSlots();
        for (int i = 0; i < members.size() && i < slots.size(); i++) {
            com.mystipixel.royalskyblock.profile.ProfileMember member = members.get(i);
            int slot = slots.get(i);
            // Owner/co-owner can manage anyone but themselves and the owner; clicking opens the member menu.
            boolean manageable = canManage
                    && member.role() != IslandRole.OWNER
                    && !member.uuid().equals(player.getUniqueId());
            inv.setItem(slot, memberIcon(member, manageable));
            if (manageable) {
                holder.putAction(slot, (viewer, right) -> open(viewer, COOP_MEMBER, member.name()));
            }
        }
    }

    /** Per-member management: promote/demote, transfer ownership, kick. Target name is the holder context. */
    private void fillCoopMember(Player player, MenuTemplate template, Inventory inv, MenuHolder holder) {
        String targetName = holder.context();
        Profile active = plugin.profiles().getActiveProfile(player);
        if (targetName == null || active == null) {
            return;
        }
        com.mystipixel.royalskyblock.profile.ProfileMember target = active.members().stream()
                .filter(m -> m.name().equalsIgnoreCase(targetName)).findFirst().orElse(null);
        if (target == null) {
            return; // member left/kicked since the menu was opened
        }
        boolean isOwner = active.roleOf(player.getUniqueId()) == IslandRole.OWNER;
        int rows = template.size() / 9;

        // header: the member's head (row 1, col 5)
        inv.setItem(4, memberIcon(target, false));

        // action row (row 2 on a 3-row menu)
        int actionRow = rows >= 3 ? 9 : 0;

        // promote / demote (owner only)
        if (isOwner && target.role() == IslandRole.MEMBER) {
            inv.setItem(actionRow + 2, infoIcon(Material.LIME_DYE, "&a&lPromote to Co-Owner",
                    List.of("&7Let " + target.name() + " invite, kick,", "&7and manage the island.",
                            "", "&eClick to promote!")));
            holder.putAction(actionRow + 2, (viewer, right) -> runRoleAction(viewer,
                    plugin.profiles().promote(viewer, target.name()), "coop.promoted", target.name(), "coop.you-promoted"));
        } else if (isOwner && target.role() == IslandRole.CO_OWNER) {
            inv.setItem(actionRow + 2, infoIcon(Material.GRAY_DYE, "&e&lDemote to Member",
                    List.of("&7Return " + target.name() + " to a", "&7regular member.",
                            "", "&eClick to demote!")));
            holder.putAction(actionRow + 2, (viewer, right) -> runRoleAction(viewer,
                    plugin.profiles().demote(viewer, target.name()), "coop.demoted", target.name(), "coop.you-demoted"));
        }

        // transfer ownership (owner only) — right-click to confirm
        if (isOwner) {
            inv.setItem(actionRow + 4, infoIcon(Material.GOLDEN_HELMET, "&6&lTransfer Ownership",
                    List.of("&7Make " + target.name() + " the owner.",
                            "&cYou'll become a co-owner.", "",
                            "&7Left-click: details", "&eRight-click: confirm transfer")));
            holder.putAction(actionRow + 4, (viewer, right) -> {
                if (!right) {
                    plugin.messages().send(viewer, "coop.transfer-hint", "player", target.name());
                    return;
                }
                runRoleAction(viewer, plugin.profiles().transferOwnership(viewer, target.name()),
                        "coop.transferred", target.name(), "coop.you-owner");
            });
        }

        // kick
        inv.setItem(actionRow + 6, infoIcon(Material.BARRIER, "&c&lRemove from Island",
                List.of("&7Kick " + target.name() + " from", "&7the coop.", "", "&eClick to remove!")));
        holder.putAction(actionRow + 6, (viewer, right) -> {
            Player online = Bukkit.getPlayerExact(target.name());
            String error = plugin.profiles().kick(viewer, target.name());
            if (error != null) {
                plugin.messages().send(viewer, "coop.kick-error", "error", error);
                open(viewer, COOP_MEMBER, target.name());
            } else {
                plugin.messages().send(viewer, "coop.kicked", "player", target.name());
                if (online != null) {
                    plugin.messages().send(online, "coop.you-were-kicked");
                }
                open(viewer, COOP);
            }
        });
    }

    // ── coop bank ────────────────────────────────────────────────────────────────

    private void fillCoopBank(Player player, MenuTemplate template, Inventory inv, MenuHolder holder) {
        Profile profile = plugin.profiles().getActiveProfile(player);
        if (profile == null) {
            return;
        }
        UUID coopId = profile.id();
        String label = profile.name();
        CoopBank bank = plugin.coopBank();
        CoopAccountView acct = bank.view(coopId, label);

        inv.setItem(4, coopBankHeaderIcon(profile, acct, bank)); // header (row 1, col 5)
        if (!bank.available()) {
            return; // no economy — header explains, no buttons
        }

        boolean canWithdraw = true;
        if (plugin.getConfig().getBoolean("coop.bank.withdraw-requires-manager", false)) {
            IslandRole role = profile.roleOf(player.getUniqueId());
            canWithdraw = role == IslandRole.OWNER || role == IslandRole.CO_OWNER;
        }

        List<Integer> amounts = plugin.getConfig().getIntegerList("coop.bank.amounts");
        if (amounts.isEmpty()) {
            amounts = List.of(100, 1000, 10000);
        }
        int[] depositCols = {19, 21, 23}; // row 3, cols 2/4/6
        int[] withdrawCols = {28, 30, 32}; // row 4, cols 2/4/6
        for (int i = 0; i < depositCols.length && i < amounts.size(); i++) {
            long amount = amounts.get(i);
            inv.setItem(depositCols[i], infoIcon(Material.LIME_DYE, "&a&lDeposit " + fmtCoins(amount),
                    List.of("&7From your purse → coop bank.", "", "&eClick to deposit!")));
            holder.putAction(depositCols[i], (viewer, right) -> runBank(viewer, coopId, label, amount, true));
            if (canWithdraw) {
                inv.setItem(withdrawCols[i], infoIcon(Material.GOLD_NUGGET, "&6&lWithdraw " + fmtCoins(amount),
                        List.of("&7From coop bank → your purse.", "", "&eClick to withdraw!")));
                holder.putAction(withdrawCols[i], (viewer, right) -> runBank(viewer, coopId, label, amount, false));
            }
        }
        // deposit all (row 3, col 8)
        inv.setItem(25, infoIcon(Material.LIME_WOOL, "&a&lDeposit All",
                List.of("&7Deposit your whole purse", "&7(up to any bank cap).", "", "&eClick to deposit!")));
        holder.putAction(25, (viewer, right) -> {
            long amount = (long) Math.floor(plugin.purseBalance(viewer));
            if (amount < 1) {
                plugin.messages().send(viewer, "bank.empty-purse");
                open(viewer, COOP_BANK);
                return;
            }
            runBank(viewer, coopId, label, amount, true);
        });
        // withdraw all (row 4, col 8)
        if (canWithdraw) {
            inv.setItem(34, infoIcon(Material.GOLD_BLOCK, "&6&lWithdraw All",
                    List.of("&7Withdraw the whole coop", "&7balance to your purse.", "", "&eClick to withdraw!")));
            holder.putAction(34, (viewer, right) -> {
                long amount = (long) Math.floor(bank.view(coopId, label).balance());
                if (amount < 1) {
                    plugin.messages().send(viewer, "bank.empty");
                    open(viewer, COOP_BANK);
                    return;
                }
                runBank(viewer, coopId, label, amount, false);
            });
        }

        // upgrade (row 5, col 4) — only when the backend has levels
        if (bank.supportsUpgrades()) {
            CoopUpgrade up = bank.upgradeInfo(coopId, label);
            inv.setItem(39, coopUpgradeIcon(up));
            if (!up.maxed()) {
                holder.putAction(39, (viewer, right) -> {
                    String error = bank.upgrade(viewer, coopId, label);
                    if (error != null) {
                        viewer.sendMessage(Text.color(plugin.messages().prefix() + error));
                    } else {
                        plugin.messages().send(viewer, "bank.upgraded");
                    }
                    open(viewer, COOP_BANK);
                });
            }
        }

        // transactions (row 5, col 6)
        inv.setItem(41, infoIcon(Material.BOOK, "&e&lTransactions",
                List.of("&7Recent coop bank activity.", "", "&eClick to view!")));
        holder.putAction(41, (viewer, right) -> open(viewer, COOP_BANK_TXNS));
    }

    private ItemStack coopUpgradeIcon(CoopUpgrade up) {
        if (up.maxed()) {
            return infoIcon(Material.NETHER_STAR, "&a&lBank Maxed",
                    List.of("&7This coop bank is at the", "&7highest level."));
        }
        List<String> lore = new ArrayList<>();
        lore.add("&7Next: &f" + up.nextName());
        lore.add("&7Cost: &e" + fmtCoins(up.moneyCost()));
        if (up.itemsText() != null && !up.itemsText().isEmpty() && !up.itemsText().equalsIgnoreCase("None")) {
            lore.add("&7Items: &f" + up.itemsText());
        }
        lore.add("&7New max balance: &f" + fmtCoins(up.nextMaxBalance()));
        lore.add("");
        lore.add("&eClick to upgrade!");
        return infoIcon(Material.ANVIL, "&6&lUpgrade Coop Bank", lore);
    }

    private void fillCoopBankTxns(Player player, MenuTemplate template, Inventory inv, MenuHolder holder) {
        Profile profile = plugin.profiles().getActiveProfile(player);
        if (profile == null) {
            return;
        }
        List<Integer> slots = template.contentSlots();
        if (slots.isEmpty()) {
            return;
        }
        List<CoopTxn> txns = plugin.coopBank().transactions(profile.id(), slots.size());
        if (txns.isEmpty()) {
            inv.setItem(slots.get(0), infoIcon(Material.PAPER, "&7No transactions yet",
                    List.of("&7Coop bank deposits, withdrawals,", "&7and upgrades will show here.")));
            return;
        }
        for (int i = 0; i < txns.size() && i < slots.size(); i++) {
            inv.setItem(slots.get(i), txnIcon(txns.get(i)));
        }
    }

    private ItemStack txnIcon(CoopTxn t) {
        Material material = switch (t.type()) {
            case "COOP_DEPOSIT" -> Material.LIME_DYE;
            case "COOP_WITHDRAW" -> Material.GOLD_NUGGET;
            case "COOP_UPGRADE" -> Material.ANVIL;
            default -> Material.PAPER;
        };
        String verb = switch (t.type()) {
            case "COOP_DEPOSIT" -> "&aDeposit";
            case "COOP_WITHDRAW" -> "&6Withdraw";
            case "COOP_UPGRADE" -> "&bUpgrade";
            default -> "&7" + t.type();
        };
        List<String> lore = new ArrayList<>();
        lore.add("&7Amount: &e" + fmtCoins(t.amount()));
        lore.add("&7Balance after: &f" + fmtCoins(t.balanceAfter()));
        if (t.note() != null && !t.note().isEmpty()) {
            lore.add("&8" + t.note());
        }
        return infoIcon(material, verb + " &7(" + fmtCoins(t.amount()) + ")", lore);
    }

    private void runBank(Player viewer, UUID coopId, String label, long amount, boolean deposit) {
        CoopBank bank = plugin.coopBank();
        String error = deposit ? bank.deposit(viewer, coopId, label, amount)
                : bank.withdraw(viewer, coopId, label, amount);
        if (error != null) {
            viewer.sendMessage(Text.color(plugin.messages().prefix() + error));
        } else {
            plugin.messages().send(viewer, deposit ? "bank.deposited" : "bank.withdrew", "amount", fmtCoins(amount));
        }
        open(viewer, COOP_BANK);
    }

    private ItemStack coopBankHeaderIcon(Profile profile, CoopAccountView acct, CoopBank bank) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic("&6&l" + profile.name() + " &7Bank"));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(noItalic("&7Balance: &e" + fmtCoins(acct.balance())));
            if (bank.supportsUpgrades()) {
                lore.add(noItalic("&7Level: &f" + acct.level() + " &8- " + acct.levelName()));
                lore.add(noItalic("&7Max balance: &f" + fmtCoins(acct.maxBalance())));
            }
            if (!bank.available()) {
                lore.add(noItalic(""));
                lore.add(noItalic("&cNo economy plugin — banking disabled."));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String fmtCoins(double value) {
        return String.format(Locale.US, "%,.0f", value);
    }

    /** Run a role change, message actor + (online) target, and re-open the right menu. */
    private void runRoleAction(Player viewer, String error, String successKey, String targetName, String targetKey) {
        if (error != null) {
            plugin.messages().send(viewer, "coop.role-error", "error", error);
            open(viewer, COOP_MEMBER, targetName);
        } else {
            plugin.messages().send(viewer, successKey, "player", targetName);
            Player online = Bukkit.getPlayerExact(targetName);
            if (online != null && targetKey != null) {
                plugin.messages().send(online, targetKey);
            }
            open(viewer, COOP);
        }
    }

    /** Fill the invite picker: every online player not already on the roster; click to invite. */
    private void fillCoopInvite(Player player, MenuTemplate template, Inventory inv, MenuHolder holder) {
        Profile profile = plugin.profiles().getActiveProfile(player);
        List<Integer> slots = template.contentSlots();
        int i = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(player) || (profile != null && profile.isMember(online.getUniqueId()))) {
                continue;
            }
            if (i >= slots.size()) {
                break;
            }
            int slot = slots.get(i++);
            inv.setItem(slot, inviteCandidateIcon(online));
            holder.putAction(slot, (viewer, right) -> {
                Player target = Bukkit.getPlayerExact(online.getName());
                if (target == null) {
                    plugin.messages().send(viewer, "coop.invite-error", "error", "That player isn't online.");
                    open(viewer, COOP_INVITE);
                    return;
                }
                String error = plugin.profiles().invite(viewer, target);
                if (error != null) {
                    plugin.messages().send(viewer, "coop.invite-error", "error", error);
                } else {
                    plugin.messages().send(viewer, "coop.invite-sent", "player", target.getName());
                    plugin.messages().sendInvite(target, viewer.getName());
                }
                open(viewer, COOP);
            });
        }
    }

    /** OWNER first, then CO_OWNER, then MEMBER, then anything else — for a tidy roster order. */
    private static int roleRank(IslandRole role) {
        return switch (role) {
            case OWNER -> 0;
            case CO_OWNER -> 1;
            case MEMBER -> 2;
            default -> 3;
        };
    }

    private int coopMax(Profile profile) {
        Island island = plugin.islands().getIslandByProfile(profile.id());
        return island != null ? plugin.upgrades().coopMemberCap(island)
                : plugin.getConfig().getInt("coop.max-members", 4);
    }

    private ItemStack coopInfoIcon(Profile profile, int max) {
        ItemStack item = new ItemStack(Material.CYAN_BED);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic("&6&l" + profile.name() + " &7Coop"));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(noItalic("&7Owner: &f" + ownerName(profile)));
            lore.add(noItalic("&7Members: &f" + profile.memberCount() + "&7/&f" + max));
            lore.add(noItalic(""));
            lore.add(noItalic("&7Click a member to manage them."));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack memberIcon(com.mystipixel.royalskyblock.profile.ProfileMember member, boolean kickable) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.SkullMeta skull) {
            try {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(member.uuid()));
            } catch (Throwable ignored) {
                // head lookup failed — leave default
            }
        }
        if (meta != null) {
            meta.displayName(noItalic("&a" + member.name()));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(noItalic("&7Role: &f" + member.role().name().toLowerCase(Locale.ROOT).replace('_', ' ')));
            boolean online = Bukkit.getPlayerExact(member.name()) != null;
            lore.add(noItalic("&7Status: " + (online ? "&aonline" : "&8offline")));
            if (kickable) {
                lore.add(noItalic(""));
                lore.add(noItalic("&cClick to remove from the island."));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack inviteCandidateIcon(Player target) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.SkullMeta skull) {
            try {
                skull.setOwningPlayer(target);
            } catch (Throwable ignored) {
                // head lookup failed — leave default
            }
        }
        if (meta != null) {
            meta.displayName(noItalic("&a" + target.getName()));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(noItalic("&7Invite to your coop island."));
            lore.add(noItalic(""));
            lore.add(noItalic("&eClick to invite!"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ── island levels ────────────────────────────────────────────────────────────

    /** Fill the level menu with the biggest point contributors from the island's last scan. */
    private void fillLevel(Player player, MenuTemplate template, Inventory inv, MenuHolder holder) {
        UUID activeId = plugin.profiles().getActiveProfileId(player.getUniqueId());
        Island island = activeId == null ? null : plugin.islands().getIslandByProfile(activeId);
        if (island == null) {
            return;
        }
        LevelConfig cfg = plugin.levels().config();
        Map<Material, Long> breakdown = plugin.levels().breakdown(island);
        List<Integer> slots = template.contentSlots();
        if (slots.isEmpty()) {
            return;
        }
        if (breakdown.isEmpty()) {
            inv.setItem(slots.get(0), infoIcon(Material.PAPER, "&7No scan yet",
                    List.of("&7Hit Recalculate to tally", "&7your island's blocks.")));
            return;
        }
        List<Map.Entry<Material, Long>> entries = new ArrayList<>(breakdown.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue() * cfg.value(b.getKey()),
                a.getValue() * cfg.value(a.getKey())));
        for (int i = 0; i < entries.size() && i < slots.size(); i++) {
            Map.Entry<Material, Long> e = entries.get(i);
            inv.setItem(slots.get(i), levelBlockIcon(e.getKey(), e.getValue(), cfg.value(e.getKey())));
        }
    }

    private ItemStack levelBlockIcon(Material material, long count, long unit) {
        ItemStack item = new ItemStack(material.isItem() ? material : Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic("&a" + prettyName(material)));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(noItalic("&7Count: &f" + count));
            lore.add(noItalic("&7Each: &e" + unit + " &7pts"));
            lore.add(noItalic("&7Total: &e" + (count * unit) + " &7pts"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Fill the leaderboard with islands ranked by stored level (never scans live). */
    private void fillTop(Player player, MenuTemplate template, Inventory inv, MenuHolder holder) {
        List<Island> all = new ArrayList<>(plugin.storage().getAllIslands());
        all.sort((a, b) -> Double.compare(b.level(), a.level()));
        List<Integer> slots = template.contentSlots();
        int rank = 0;
        for (Island island : all) {
            if (rank >= slots.size()) {
                break;
            }
            Profile prof = plugin.profiles().getProfile(island.profileId());
            if (prof == null) {
                continue;
            }
            int slot = slots.get(rank++);
            inv.setItem(slot, leaderboardIcon(rank, island, prof, ownerName(prof)));
        }
    }

    private ItemStack leaderboardIcon(int rank, Island island, Profile prof, String ownerName) {
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
            meta.displayName(noItalic("&e&l#" + rank + " &a" + ownerName));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(noItalic("&7Level: &e" + (int) island.level()));
            lore.add(noItalic("&7Gamemode: &f" + prof.gamemode().name().toLowerCase(Locale.ROOT)));
            lore.add(noItalic("&7Members: &f" + prof.memberCount()));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack infoIcon(Material material, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material.isItem() ? material : Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(name));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(noItalic(line));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** "DIAMOND_BLOCK" -> "Diamond Block". */
    private static String prettyName(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    private static net.kyori.adventure.text.Component noItalic(String legacy) {
        return Text.color(legacy).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    /** Placeholders available to every menu. Extended as systems come online (level, profile, ...). */
    private Map<String, String> placeholders(Player player) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("player", player.getName());
        map.put("has_island", String.valueOf(plugin.profiles().activeHasIsland(player)));
        UUID activeId = plugin.profiles().getActiveProfileId(player.getUniqueId());
        Island island = activeId == null ? null : plugin.islands().getIslandByProfile(activeId);
        map.put("island_level", island == null ? "0" : String.valueOf((int) island.level()));
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
