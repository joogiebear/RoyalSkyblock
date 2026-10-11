package com.mystipixel.royalskyblock.gui;

import com.willfp.eco.core.gui.menu.Menu;
import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.bank.BankAccount;
import com.mystipixel.royalskyblock.bank.BankLevel;
import com.mystipixel.royalskyblock.bank.BankService;
import com.mystipixel.royalskyblock.bank.BankTxn;
import com.mystipixel.royalskyblock.gui.menu.MenuEffect;
import com.mystipixel.royalskyblock.gui.menu.ItemSpec;
import com.mystipixel.royalskyblock.gui.menu.MenuSlot;
import com.mystipixel.royalskyblock.gui.menu.MenuTemplate;
import com.mystipixel.royalskyblock.gui.menu.MenuText;
import com.mystipixel.royalskyblock.hooks.EcoHook;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.island.IslandRole;
import com.mystipixel.royalskyblock.listener.CommandGateListener;
import com.mystipixel.royalskyblock.message.Reason;
import com.mystipixel.royalskyblock.island.IslandSetting;
import com.mystipixel.royalskyblock.level.LevelConfig;
import com.mystipixel.royalskyblock.perk.Perk;
import com.mystipixel.royalskyblock.profile.Profile;
import com.mystipixel.royalskyblock.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Loads, renders and drives the {@code gui/*.yml} menus in the shared EcoMenus dialect. Buttons run
 * effect lists; the standard effects delegate to commands ({@code player_command}) so the GUI stays a
 * thin launcher over existing logic. A new menu is a new {@code gui/<id>.yml} registered in
 * {@link #MENUS}.
 */
public final class GuiManager implements Listener {

    /** Menu ids: the gui/<id>.yml file name and the {@code open_menu} effect's {@code menu} arg. */
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
    public static final String BANK_PERSONAL = "bank-personal";
    public static final String COOP_BANK = "coop-bank";
    public static final String COOP_BANK_TXNS = "coop-bank-txns";
    public static final String LEVEL = "level";
    public static final String TOP = "top";
    public static final String PERKS = "perks";

    // The menu id is the file's basename, so the gui/<category>/ folders don't affect the constants
    // above or any open_menu reference.
    private static final String[] MENU_PATHS = {
            "core/main", "core/confirm-delete",
            "profile/profiles", "profile/create-profile",
            "island/manage", "island/settings", "island/upgrades", "island/visit",
            "coop/coop", "coop/coop-invite", "coop/coop-member",
            "bank/bank-hub", "bank/bank-personal", "bank/coop-bank", "bank/coop-bank-txns",
            "level/level", "level/top", "level/perks",
    };

    // fully config-driven menus: eco renders each slot straight from its template
    private static final Set<String> ECO_RENDERED = Set.of(
            MAIN, CONFIRM_DELETE, CREATE_PROFILE, MANAGE, BANK_HUB);

    // data-driven menus: each render, their fillX builder paints a fresh MenuCanvas (see renderDynamic)
    private static final Set<String> ECO_DYNAMIC = Set.of(
            PROFILES, SETTINGS, UPGRADES, VISIT, COOP, COOP_INVITE, COOP_MEMBER,
            BANK_PERSONAL, COOP_BANK, COOP_BANK_TXNS, LEVEL, TOP, PERKS);

    // eco menus currently open, so tickOpenMenus can refresh one in place
    private final Map<UUID, OpenEcoMenu> openEcoMenus = new ConcurrentHashMap<>();


    // Rows the visit browser and leaderboard fetched off-thread, per viewer and menu, waiting for the
    // refresh that paints them. Cleared on close, so every opening fetches fresh rows.
    private final Map<String, List<BrowseRow>> browseRows = new ConcurrentHashMap<>();

    private record OpenEcoMenu(String menuId, Menu menu) {
    }

    private final RoyalSkyblockPlugin plugin;
    private final EcoHook ecoHook;
    private final EcoMenuFactory ecoMenus;
    private final Map<String, MenuTemplate> byId = new LinkedHashMap<>();

    public GuiManager(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.ecoHook = new EcoHook();
        this.ecoMenus = new EcoMenuFactory(ecoHook);
        reload();
    }

    public void reload() {
        byId.clear();
        for (String path : MENU_PATHS) {
            File file = new File(plugin.getDataFolder(), "gui/" + path + ".yml");
            if (!file.exists()) {
                plugin.saveResource("gui/" + path + ".yml", false); // saveResource creates parent folders
            }
            String id = path.substring(path.lastIndexOf('/') + 1);
            byId.put(id, MenuTemplate.load(file, bundled("gui/" + path + ".yml")));
        }
        // compile every menu's effect chains now, so a broken one is reported with its file and slot
        com.mystipixel.royalskyblock.libreforge.MenuChains.invalidate();
        for (Map.Entry<String, MenuTemplate> entry : byId.entrySet()) {
            com.mystipixel.royalskyblock.libreforge.MenuChains.precompile(entry.getKey(), entry.getValue());
        }
        // catch a menu added to MENU_PATHS but never classified
        for (String id : byId.keySet()) {
            if (!ECO_RENDERED.contains(id) && !ECO_DYNAMIC.contains(id)) {
                plugin.getLogger().severe("Menu '" + id + "' is loaded but not registered as static or "
                        + "dynamic: it will not open. Add it to ECO_RENDERED or ECO_DYNAMIC.");
            }
        }
    }

    private @Nullable YamlConfiguration bundled(String resource) {
        InputStream in = plugin.getResource(resource);
        if (in == null) {
            return null;
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read the bundled " + resource + ": " + e.getMessage());
            return null;
        }
    }

    /** Open a menu for a player, rendering items with their placeholders. */
    public void open(Player player, String menuId) {
        open(player, menuId, null);
    }

    /**
     * Open a menu, optionally carrying a {@code context} (e.g. the coop member a per-member menu acts
     * on) that the dynamic fill can read back off the canvas.
     */
    public void open(Player player, String menuId, String context) {
        MenuTemplate template = byId.get(menuId);
        if (template == null) {
            plugin.getLogger().warning("Tried to open unknown menu '" + menuId + "'.");
            return;
        }
        Map<String, String> placeholders = placeholders(player);

        String title = apply(template.title(), placeholders);
        if (ECO_RENDERED.contains(menuId)) {
            openEcoMenu(player, menuId, template, title);
            return;
        }
        if (ECO_DYNAMIC.contains(menuId)) {
            openEcoDynamicMenu(player, menuId, template, title, context);
            return;
        }
        // a menu added to MENU_PATHS without being classified; reload() should have reported it
        plugin.getLogger().severe("Menu '" + menuId + "' is not registered as static or dynamic: "
                + "add it to ECO_RENDERED or ECO_DYNAMIC in GuiManager.");
    }

    // Rebuilt per open rather than cached: the title is fixed at build time and may carry
    // placeholders, and /is reload takes effect immediately.
    private void openEcoMenu(Player player, String menuId, MenuTemplate template, String title) {
        Menu menu = ecoMenus.build(template, title, this::placeholders, (viewer, slot, rightClick) -> {
            List<MenuEffect> effects = rightClick && !slot.rightClick().isEmpty()
                    ? slot.rightClick() : slot.leftClick();
            playSlotSound(viewer, slot);
            for (MenuEffect effect : effects) {
                execute(viewer, effect);
            }
            com.mystipixel.royalskyblock.libreforge.MenuChains.run(menuId, slot, rightClick, viewer);
        });
        menu.open(player);
        play(player, template, "open", null);
    }

    private void openEcoDynamicMenu(Player player, String menuId, MenuTemplate template,
                                    String title, String context) {
        Menu menu = ecoMenus.buildDynamic(template, title,
                viewer -> renderDynamic(menuId, viewer, template, context),
                (viewer, slot, rightClick) -> {
                    List<MenuEffect> effects = rightClick && !slot.rightClick().isEmpty()
                            ? slot.rightClick() : slot.leftClick();
                    playSlotSound(viewer, slot);
                    for (MenuEffect effect : effects) {
                        execute(viewer, effect);
                    }
                    com.mystipixel.royalskyblock.libreforge.MenuChains.run(menuId, slot, rightClick, viewer);
                },
                // Always sound, and defer the action off the click event: opening the next menu inside
                // InventoryClickEvent isn't supported cleanly and doubles the click sound.
                (viewer, action, rightClick, configured) -> {
                    playDynamicSound(viewer, template, configured);
                    runNextTick(() -> action.accept(viewer, rightClick));
                });
        // Track after opening: openInventory fires InventoryCloseEvent for the outgoing menu synchronously,
        // which would wipe an entry registered first.
        menu.open(player);
        openEcoMenus.put(player.getUniqueId(), new OpenEcoMenu(menuId, menu));
        play(player, template, "open", null);
    }

    // Filler, configured buttons, then generated content on top: several builders deliberately overwrite
    // configured slots (pinning an upgrade, greying out a button).
    private MenuCanvas renderDynamic(String menuId, Player player, MenuTemplate template, String context) {
        MenuCanvas canvas = new MenuCanvas(menuId, context, template.size());

        ItemStack filler = template.maskFiller();
        if (filler != null) {
            List<Integer> contentSlots = template.contentSlots();
            for (int index = 0; index < canvas.size(); index++) {
                if (!contentSlots.contains(index)) {
                    canvas.setItem(index, filler.clone());
                }
            }
        }
        Map<String, String> placeholders = placeholders(player);
        for (MenuSlot slot : template.slots()) {
            canvas.setItem(slot.index(), slot.item().build(ecoHook, placeholders, slot.lore()));
        }
        fillDynamic(menuId, player, template, canvas);
        return canvas;
    }

    private void fillDynamic(String menuId, Player player, MenuTemplate template, MenuCanvas canvas) {
        if (menuId.equals(SETTINGS)) {
            fillSettings(player, template, canvas);
            return;
        }
        if (menuId.equals(UPGRADES)) {
            fillUpgrades(player, template, canvas);
            return;
        }
        if (menuId.equals(VISIT)) {
            fillVisit(player, template, canvas);
            return;
        }
        if (menuId.equals(COOP)) {
            fillCoop(player, template, canvas);
            return;
        }
        if (menuId.equals(COOP_INVITE)) {
            fillCoopInvite(player, template, canvas);
            return;
        }
        if (menuId.equals(COOP_MEMBER)) {
            fillCoopMember(player, template, canvas);
            return;
        }
        if (menuId.equals(COOP_BANK)) {
            fillBank(player, template, canvas, true);
            return;
        }
        if (menuId.equals(BANK_PERSONAL)) {
            fillBank(player, template, canvas, false);
            return;
        }
        if (menuId.equals(COOP_BANK_TXNS)) {
            fillBankTxns(player, template, canvas);
            return;
        }
        if (menuId.equals(LEVEL)) {
            fillLevel(player, template, canvas);
            return;
        }
        if (menuId.equals(TOP)) {
            fillTop(player, template, canvas);
            return;
        }
        if (menuId.equals(PERKS)) {
            fillPerks(player, template, canvas);
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
            canvas.setItem(slot, profileIcon(profile, active, template.text()));
            canvas.putAction(slot, (viewer, right) -> {
                viewer.closeInventory();
                plugin.profiles().switchProfile(viewer, profile.id());
            });
        }
    }

    private ItemStack profileIcon(Profile profile, UUID active, MenuText text) {
        var ruleset = plugin.gamemodes().ruleset(profile.gamemode());
        Material material = Material.matchMaterial(ruleset.icon().toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem()) {
            material = Material.GRASS_BLOCK;
        }
        boolean isActive = profile.id().equals(active);
        boolean hasIsland = plugin.islands().getIslandByProfile(profile.id()) != null;

        Map<String, String> ph = ph(
                "name", profile.name(),
                "gamemode_name", ruleset.displayName(),
                "gamemode_description", ruleset.description(),
                "active", isActive ? text.text("entry.active-tag") : "",
                "island", text.text(hasIsland ? "entry.island-yes" : "entry.island-no"),
                "action", text.text(isActive ? "entry.action-active" : "entry.action-switch"));
        return dress(new ItemStack(material), text, "entry.name", "entry.lore", ph);
    }

    private void fillSettings(Player player, MenuTemplate template, MenuCanvas canvas) {
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
            canvas.setItem(slot, settingIcon(setting, island.isEnabled(setting), canEdit, template.text()));
            if (canEdit) {
                canvas.putAction(slot, (viewer, right) -> {
                    Island current = managedIsland(viewer, island);
                    if (current == null) {
                        return;
                    }
                    current.setSetting(setting, !current.isEnabled(setting));
                    plugin.writeAsync(() -> plugin.storage().saveIsland(current));
                    open(viewer, SETTINGS);
                });
            }
        }
    }

    // The island viewer manages right now, re-resolved at click time, or null (menu closed, viewer told)
    // if they no longer do. A menu can stay open across a delete, kick, demotion or profile switch, and a
    // stale save would upsert a deleted island back. drawn, when given, must still be the menu's island.
    private @Nullable Island managedIsland(Player viewer, @Nullable Island drawn) {
        UUID activeId = plugin.profiles().getActiveProfileId(viewer.getUniqueId());
        Island island = activeId == null ? null : plugin.islands().getIslandByProfile(activeId);
        Profile profile = island == null ? null : plugin.profiles().getProfile(island.profileId());
        IslandRole role = profile == null ? IslandRole.VISITOR : profile.roleOf(viewer.getUniqueId());
        boolean allowed = island != null
                && (drawn == null || drawn.id().equals(island.id()))
                && (role == IslandRole.OWNER || role == IslandRole.CO_OWNER);
        if (!allowed) {
            viewer.closeInventory();
            plugin.messages().send(viewer, "island.menu-stale");
            return null;
        }
        return island;
    }

    /** Tick live upgrade countdowns: does nothing unless something is cooking, then only refreshes viewers. */
    public void tickOpenMenus() {
        if (!plugin.upgrades().hasAnyPending()) {
            return; // nothing cooking anywhere
        }
        MenuTemplate template = byId.get(UPGRADES);
        if (template == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            // refresh() re-runs the render snapshot, re-deriving the countdown without reopening the menu
            OpenEcoMenu open = openEcoMenus.get(player.getUniqueId());
            if (open != null && UPGRADES.equals(open.menuId())) {
                open.menu().refresh(player);
            }
        }
    }

    @EventHandler
    public void onEcoMenuClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            openEcoMenus.remove(player.getUniqueId());
            forgetBrowseRows(player.getUniqueId());
        }
    }

    // a player who logs out never fires a close for the open inventory
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        openEcoMenus.remove(event.getPlayer().getUniqueId());
        forgetBrowseRows(event.getPlayer().getUniqueId());
    }

    /** A loaded menu template by id, or null if that file isn't registered. */
    public MenuTemplate template(String id) {
        return byId.get(id);
    }

    private void fillUpgrades(Player player, MenuTemplate template, MenuCanvas canvas) {
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
            canvas.setItem(slot, upgradeIcon(def, island, template.text()));
            if (canEdit) {
                canvas.putAction(slot, (viewer, right) -> {
                    handleUpgradeClick(viewer, def, right);
                    open(viewer, UPGRADES);
                });
            }
        }
    }

    private void handleUpgradeClick(Player viewer, com.mystipixel.royalskyblock.upgrade.UpgradeDef def, boolean skip) {
        Island island = managedIsland(viewer, null);
        if (island == null) {
            return;
        }
        var result = skip ? plugin.upgrades().skip(viewer, island, def)
                : plugin.upgrades().start(viewer, island, def);
        switch (result) {
            case STARTED -> {
                var next = def.tier(island.upgradeTier(def.key()) + 1);
                plugin.messages().send(viewer, "upgrade.started", "upgrade", def.displayName(),
                        "time", next != null ? formatDuration(next.timeSeconds())
                                : plugin.messages().raw("format.duration.unknown"));
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
        return pending == null ? plugin.messages().raw("format.duration.seconds", "value", "0")
                : formatDuration(pending.secondsLeft(System.currentTimeMillis()));
    }

    private ItemStack upgradeIcon(com.mystipixel.royalskyblock.upgrade.UpgradeDef def, Island island, MenuText text) {
        Material material = Material.matchMaterial(def.icon().toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem()) {
            material = Material.ANVIL;
        }
        int current = island.upgradeTier(def.key());
        var pending = plugin.upgrades().pendingFor(island, def);

        Map<String, String> ph = ph(
                "name", def.displayName(),
                "description", def.description(),
                "tier", String.valueOf(current),
                "max_tier", String.valueOf(def.maxTier()));
        String lore;
        if (pending != null) {
            ph.put("target_tier", String.valueOf(pending.targetTier()));
            ph.put("time_left", formatDuration(pending.secondsLeft(System.currentTimeMillis())));
            var t = def.tier(pending.targetTier());
            lore = "entry.lore-upgrading";
            if (t != null && !t.skipCost().isFree()) {
                ph.put("skip_cost", plugin.currency().format(t.skipCost()));
                lore = "entry.lore-upgrading-skippable";
            }
        } else {
            var next = def.nextTier(current);
            if (next == null) {
                lore = "entry.lore-maxed";
            } else {
                String label = effectLabel(text, def.effect(), next.value());
                ph.put("next_tier", String.valueOf(next.tier()));
                ph.put("effect", label.isEmpty() ? "" : MenuText.fill(text.text("entry.effect"), ph("label", label)));
                ph.put("cost", plugin.currency().format(next.cost()));
                ph.put("wait", next.isInstant() ? ""
                        : MenuText.fill(text.text("entry.wait"), ph("time", formatDuration(next.timeSeconds()))));
                lore = "entry.lore";
                if (!next.skipCost().isFree()) {
                    ph.put("skip_cost", plugin.currency().format(next.skipCost()));
                    lore = "entry.lore-skippable";
                }
            }
        }
        return dress(new ItemStack(material), text, "entry.name", lore, ph);
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // a radius shows as its NxN island size
    private static String effectLabel(MenuText text, com.mystipixel.royalskyblock.upgrade.UpgradeEffect effect,
                                      double value) {
        int v = (int) value;
        String key = switch (effect) {
            case RADIUS -> "radius";
            case GUEST_SLOTS -> "guest-slots";
            case COOP_SLOTS -> "coop-slots";
            case GENERATOR -> "generator";
            case MINIONS -> "minions";
            // a chain-only track has no value to advertise; the caller drops the label
            case NONE -> null;
        };
        if (key == null) {
            return "";
        }
        boolean plural = v != 1 && effect != com.mystipixel.royalskyblock.upgrade.UpgradeEffect.RADIUS
                && effect != com.mystipixel.royalskyblock.upgrade.UpgradeEffect.GENERATOR;
        // border half-size to full NxN (incl. centre)
        return MenuText.fill(text.text("effect-labels." + key + (plural ? "-plural" : "")),
                ph("value", String.valueOf(v), "size", String.valueOf(v * 2 + 1)));
    }

    private String formatDuration(long seconds) {
        return formatDuration(seconds, (unit, value) ->
                plugin.messages().raw("format.duration." + unit, "value", String.valueOf(value)));
    }

    // unit(name, value) renders one part: instant, days, hours, minutes or seconds
    static String formatDuration(long seconds, BiFunction<String, Long, String> unit) {
        if (seconds <= 0) {
            return unit.apply("instant", 0L);
        }
        long d = seconds / 86_400, h = (seconds % 86_400) / 3_600, m = (seconds % 3_600) / 60, s = seconds % 60;
        List<String> parts = new ArrayList<>(4);
        if (d > 0) parts.add(unit.apply("days", d));
        if (h > 0) parts.add(unit.apply("hours", h));
        if (m > 0) parts.add(unit.apply("minutes", m));
        if (s > 0 && d == 0) parts.add(unit.apply("seconds", s));
        return String.join(" ", parts);
    }

    private ItemStack settingIcon(IslandSetting setting, boolean on, boolean canEdit, MenuText text) {
        String path = "settings." + setting.key();
        Material material = Material.matchMaterial(text.text(path + ".icon").toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem()) {
            material = Material.PAPER;
        }
        String name = text.has(path + ".name") ? text.text(path + ".name") : setting.niceName();
        String action = !canEdit ? "entry.action-locked" : on ? "entry.action-disable" : "entry.action-enable";
        Map<String, String> ph = ph(
                "name", name,
                "description", text.text(path + ".description"),
                "state", text.text(on ? "entry.state-on" : "entry.state-off"),
                "action", text.text(action));
        return dress(new ItemStack(material), text, "entry.name", "entry.lore", ph);
    }

    private record BrowseRow(Island island, Profile profile, String ownerName) {
    }

    private void fillVisit(Player player, MenuTemplate template, MenuCanvas canvas) {
        Profile viewer = plugin.profiles().getProfile(plugin.profiles().getActiveProfileId(player.getUniqueId()));
        if (viewer == null) {
            return;
        }
        com.mystipixel.royalskyblock.profile.Gamemode mode = viewer.gamemode();
        List<Integer> slots = template.contentSlots();
        java.util.UUID viewerId = player.getUniqueId();

        // Gathered off-thread: getAllIslands() is a full table read, and profiles and owner names can miss
        // their caches.
        List<BrowseRow> rows = browseRowsOrFetch(player, VISIT, () -> {
            List<BrowseRow> found = new ArrayList<>();
            for (Island island : plugin.storage().getAllIslands()) {
                if (found.size() >= slots.size()) {
                    break;
                }
                if (!island.isEnabled(IslandSetting.VISITORS_ALLOWED) || !island.isEnabled(IslandSetting.LISTED)) {
                    continue;
                }
                Profile prof = plugin.profiles().getProfile(island.profileId());
                if (prof == null || prof.gamemode() != mode || prof.isMember(viewerId)) {
                    continue; // gamemode must match; don't list your own
                }
                found.add(new BrowseRow(island, prof, ownerName(prof)));
            }
            return found;
        });
        if (rows == null) {
            return; // still fetching; the refresh when it lands comes back through here
        }
        int i = 0;
        for (BrowseRow row : rows) {
            int slot = slots.get(i++);
            canvas.setItem(slot, islandBrowserIcon(row.island(), row.profile(), row.ownerName(), template.text()));
            canvas.putAction(slot, (v, right) -> {
                v.closeInventory();
                visitFromBrowser(v, row.island(), row.profile(), row.ownerName());
            });
        }
    }

    // The rows for an async-filled menu, or null while still being fetched. The fill runs inside eco's
    // render and must return synchronously, so late rows are parked here and the menu refreshed.
    private @Nullable List<BrowseRow> browseRowsOrFetch(Player player, String menuId,
                                                        java.util.function.Supplier<List<BrowseRow>> gather) {
        UUID viewer = player.getUniqueId();
        String key = viewer + ":" + menuId;
        List<BrowseRow> ready = browseRows.get(key);
        if (ready != null) {
            return ready;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<BrowseRow> rows = gather.get();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                OpenEcoMenu open = openEcoMenus.get(viewer);
                if (!player.isOnline() || open == null || !menuId.equals(open.menuId())) {
                    return; // they moved on; the next opening fetches again
                }
                browseRows.put(key, rows);
                open.menu().refresh(player);
            });
        });
        return null;
    }

    private void forgetBrowseRows(UUID viewer) {
        browseRows.remove(viewer + ":" + VISIT);
        browseRows.remove(viewer + ":" + TOP);
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

    private ItemStack islandBrowserIcon(Island island, Profile prof, String ownerName, MenuText text) {
        Map<String, String> ph = ph(
                "owner", ownerName,
                "gamemode", prof.gamemode().name().toLowerCase(Locale.ROOT),
                "level", String.valueOf((int) island.level()),
                "members", String.valueOf(prof.memberCount()));
        return dress(head(Bukkit.getOfflinePlayer(prof.owner())), text, "entry.name", "entry.lore", ph);
    }

    // a player head, or a plain one if the owner lookup fails
    private static ItemStack head(org.bukkit.OfflinePlayer owner) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.SkullMeta skull) {
            try {
                skull.setOwningPlayer(owner);
            } catch (Throwable ignored) {
                // head lookup failed, leave default
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private String ownerName(Profile prof) {
        String name = Bukkit.getOfflinePlayer(prof.owner()).getName();
        return name != null ? name : prof.name();
    }

    // owner first, then co-owners/members; owner/co-owner click a member to manage them
    private void fillCoop(Player player, MenuTemplate template, MenuCanvas canvas) {
        Profile profile = plugin.profiles().getActiveProfile(player);
        if (profile == null) {
            return;
        }
        IslandRole myRole = profile.roleOf(player.getUniqueId());
        boolean canManage = myRole == IslandRole.OWNER || myRole == IslandRole.CO_OWNER;

        MenuText text = template.text();
        place(canvas, text.slot("header"), button(text, "header", "lore", ph(
                "profile", profile.name(),
                "owner", ownerName(profile),
                "members", String.valueOf(profile.memberCount()),
                "max", String.valueOf(coopMax(profile)))));

        List<com.mystipixel.royalskyblock.profile.ProfileMember> members = new ArrayList<>(profile.members());
        members.sort((a, b) -> Integer.compare(roleRank(a.role()), roleRank(b.role())));

        List<Integer> slots = template.contentSlots();
        for (int i = 0; i < members.size() && i < slots.size(); i++) {
            com.mystipixel.royalskyblock.profile.ProfileMember member = members.get(i);
            int slot = slots.get(i);
            boolean manageable = canManage
                    && member.role() != IslandRole.OWNER
                    && !member.uuid().equals(player.getUniqueId());
            canvas.setItem(slot, memberIcon(member, text, "entry.name", manageable ? "entry.lore-manageable" : "entry.lore"));
            if (manageable) {
                canvas.putAction(slot, (viewer, right) -> open(viewer, COOP_MEMBER, member.name()));
            }
        }
    }

    // per-member management: promote/demote, transfer ownership, kick; target name is the canvas context
    private void fillCoopMember(Player player, MenuTemplate template, MenuCanvas canvas) {
        String targetName = canvas.context();
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
        MenuText text = template.text();
        Map<String, String> ph = ph("member", target.name());

        int header = text.slot("header");
        if (header >= 0) {
            canvas.setItem(header, memberIcon(target, text, "header.name", "header.lore"));
        }

        // promote / demote (owner only)
        if (isOwner && target.role() == IslandRole.MEMBER) {
            int promote = place(canvas, text.slot("buttons.promote"), button(text, "buttons.promote", "lore", ph));
            onClick(canvas, promote, (viewer, right) -> runRoleAction(viewer,
                    plugin.profiles().promote(viewer, target.name()), "coop.promoted", target.name(), "coop.you-promoted"));
        } else if (isOwner && target.role() == IslandRole.CO_OWNER) {
            int demote = place(canvas, text.slot("buttons.demote"), button(text, "buttons.demote", "lore", ph));
            onClick(canvas, demote, (viewer, right) -> runRoleAction(viewer,
                    plugin.profiles().demote(viewer, target.name()), "coop.demoted", target.name(), "coop.you-demoted"));
        }

        // transfer ownership (owner only), right-click to confirm
        if (isOwner) {
            int transfer = place(canvas, text.slot("buttons.transfer"), button(text, "buttons.transfer", "lore", ph));
            onClick(canvas, transfer, (viewer, right) -> {
                if (!right) {
                    plugin.messages().send(viewer, "coop.transfer-hint", "player", target.name());
                    return;
                }
                runRoleAction(viewer, plugin.profiles().transferOwnership(viewer, target.name()),
                        "coop.transferred", target.name(), "coop.you-owner");
            });
        }

        int kick = place(canvas, text.slot("buttons.kick"), button(text, "buttons.kick", "lore", ph));
        onClick(canvas, kick, (viewer, right) -> {
            Player online = Bukkit.getPlayerExact(target.name());
            Reason error = plugin.profiles().kick(viewer, target.name());
            if (error != null) {
                plugin.messages().send(viewer, "coop.kick-error", "error", plugin.messages().raw(error));
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

    private void fillBank(Player player, MenuTemplate template, MenuCanvas canvas, boolean coop) {
        Profile profile = plugin.profiles().getActiveProfile(player);
        if (profile == null) {
            return;
        }
        BankService bank = plugin.bank();
        String accountId = coop ? BankService.coopId(profile.id())
                : BankService.personalId(profile.id(), player.getUniqueId());
        String menu = coop ? COOP_BANK : BANK_PERSONAL;
        BankAccount acct = bank.account(accountId);
        BankLevel level = bank.levels().effectiveLevel(acct.level());

        MenuText text = template.text();
        place(canvas, text.slot("header"), button(text, "header", bank.available() ? "lore" : "lore-unavailable", ph(
                "profile", profile.name(),
                "balance", bank.money(acct.balance()),
                "level", String.valueOf(level.level()),
                "level_name", level.name(),
                "max_balance", bank.money(level.maxBalance()),
                "next_interest", bank.money(bank.calculateInterest(acct.interestBase(), level)))));
        if (!bank.available()) {
            return; // no economy or no levels: the header explains, no buttons
        }

        boolean canWithdraw = mayWithdraw(profile, player, coop);

        List<Integer> amounts = plugin.conf().getIntegerList("coop.bank.amounts");
        if (amounts.isEmpty()) {
            amounts = List.of(100, 1000, 10000);
        }
        List<Integer> depositSlots = text.slots("buttons.deposit.positions");
        List<Integer> withdrawSlots = text.slots("buttons.withdraw.positions");
        for (int i = 0; i < depositSlots.size() && i < amounts.size(); i++) {
            long amount = amounts.get(i);
            Map<String, String> ph = ph("amount", fmtCoins(amount));
            int deposit = place(canvas, depositSlots.get(i), button(text, "buttons.deposit", "lore", ph));
            onClick(canvas, deposit, (viewer, right) -> runBank(viewer, accountId, menu, amount, true));
            if (canWithdraw && i < withdrawSlots.size()) {
                int withdraw = place(canvas, withdrawSlots.get(i), button(text, "buttons.withdraw", "lore", ph));
                onClick(canvas, withdraw, (viewer, right) -> runBank(viewer, accountId, menu, amount, false));
            }
        }
        int depositAll = place(canvas, text.slot("buttons.deposit-all"), button(text, "buttons.deposit-all", "lore", ph()));
        onClick(canvas, depositAll, (viewer, right) -> {
            long amount = (long) Math.floor(plugin.purseBalance(viewer));
            if (amount < 1) {
                plugin.messages().send(viewer, "bank.empty-purse");
                open(viewer, menu);
                return;
            }
            runBank(viewer, accountId, menu, amount, true);
        });
        if (canWithdraw) {
            int withdrawAll = place(canvas, text.slot("buttons.withdraw-all"),
                    button(text, "buttons.withdraw-all", "lore", ph()));
            onClick(canvas, withdrawAll, (viewer, right) -> {
                long amount = (long) Math.floor(bank.account(accountId).balance());
                if (amount < 1) {
                    plugin.messages().send(viewer, "bank.nothing-to-withdraw");
                    open(viewer, menu);
                    return;
                }
                runBank(viewer, accountId, menu, amount, false);
            });
        }

        long remaining = bank.interestSecondsRemaining(accountId);
        if (remaining <= 0) {
            int claim = place(canvas, text.slot("buttons.claim-interest"),
                    button(text, "buttons.claim-interest", "lore", ph()));
            onClick(canvas, claim, (viewer, right) -> {
                if (!bankAccess(viewer, accountId, false)) {
                    return;
                }
                Reason error = bank.claimInterest(viewer, accountId);
                if (error != null) {
                    plugin.messages().send(viewer, error);
                } else {
                    plugin.messages().send(viewer, "bank.interest");
                }
                open(viewer, menu);
            });
        } else {
            place(canvas, text.slot("buttons.interest-waiting"), button(text, "buttons.interest-waiting", "lore",
                    ph("time", formatDuration(remaining))));
        }

        BankLevel next = bank.levels().getNextLevel(acct.level()).orElse(null);
        if (next == null) {
            place(canvas, text.slot("buttons.maxed"), button(text, "buttons.maxed", "lore", ph()));
        } else {
            String items = next.itemRequirements().stream()
                    .map(com.mystipixel.royalskyblock.bank.ItemRequirement::displayName)
                    .collect(java.util.stream.Collectors.joining(", "));
            int upgrade = place(canvas, text.slot("buttons.upgrade"), button(text, "buttons.upgrade",
                    items.isEmpty() ? "lore" : "lore-items", ph(
                            "next_level", next.name(),
                            "cost", bank.money(next.upgradeMoneyCost()),
                            "items", items,
                            "max_balance", bank.money(next.maxBalance()))));
            onClick(canvas, upgrade, (viewer, right) -> {
                if (!bankAccess(viewer, accountId, false)) {
                    return;
                }
                Reason error = bank.upgrade(viewer, accountId);
                if (error != null) {
                    plugin.messages().send(viewer, error);
                } else {
                    plugin.messages().send(viewer, "bank.upgraded");
                }
                open(viewer, menu);
            });
        }

        int transactions = place(canvas, text.slot("buttons.transactions"),
                button(text, "buttons.transactions", "lore", ph()));
        onClick(canvas, transactions, (viewer, right) -> open(viewer, COOP_BANK_TXNS, accountId));
    }

    private void fillBankTxns(Player player, MenuTemplate template, MenuCanvas canvas) {
        Profile profile = plugin.profiles().getActiveProfile(player);
        if (profile == null) {
            return;
        }
        String accountId = canvas.context() != null ? canvas.context()
                : BankService.coopId(profile.id());
        List<Integer> slots = template.contentSlots();
        MenuText text = template.text();
        // the Back button returns to the right bank menu for this account
        String menu = accountId.startsWith("p:") ? BANK_PERSONAL : COOP_BANK;
        int back = place(canvas, text.slot("back"), button(text, "back", "lore", ph()));
        onClick(canvas, back, (viewer, right) -> open(viewer, menu));
        if (slots.isEmpty()) {
            return;
        }
        List<BankTxn> txns = plugin.bank().transactions(accountId, slots.size());
        if (txns.isEmpty()) {
            canvas.setItem(slots.get(0), button(text, "empty", "lore", ph()));
            return;
        }
        for (int i = 0; i < txns.size() && i < slots.size(); i++) {
            canvas.setItem(slots.get(i), txnIcon(txns.get(i), text));
        }
    }

    private ItemStack txnIcon(BankTxn t, MenuText text) {
        String type = switch (t.type()) {
            case "DEPOSIT", "WITHDRAW", "UPGRADE", "INTEREST" -> t.type().toLowerCase(Locale.ROOT);
            default -> "other";
        };
        boolean hasNote = t.note() != null && !t.note().isEmpty();
        Map<String, String> ph = ph(
                "type", t.type(),
                "amount", plugin.bank().money(t.amount()),
                "balance", plugin.bank().money(t.balanceAfter()),
                "note", hasNote ? t.note() : "");
        return item(MenuText.fill(text.text("types." + type), ph),
                MenuText.fill(text.lines(hasNote ? "entry.lore-note" : "entry.lore"), ph, Map.of()));
    }

    // coop banks can require owner/co-owner to withdraw; personal banks always allow it
    private boolean mayWithdraw(Profile profile, Player player, boolean coop) {
        if (!coop || !plugin.conf().getBoolean("coop.bank.withdraw-requires-manager", false)) {
            return true;
        }
        IslandRole role = profile.roleOf(player.getUniqueId());
        return role == IslandRole.OWNER || role == IslandRole.CO_OWNER;
    }

    // Re-checked at click time: a menu can stay open across a kick, leave, demotion or profile switch,
    // and BankService does no membership check. On refusal the menu is closed.
    private boolean bankAccess(Player viewer, String accountId, boolean withdraw) {
        Profile profile = plugin.profiles().getActiveProfile(viewer);
        boolean coop = accountId.startsWith("c:");
        boolean allowed = profile != null
                && profile.isMember(viewer.getUniqueId())
                && accountId.equals(coop ? BankService.coopId(profile.id())
                        : BankService.personalId(profile.id(), viewer.getUniqueId()))
                && (!withdraw || mayWithdraw(profile, viewer, coop));
        if (!allowed) {
            viewer.closeInventory();
            plugin.messages().send(viewer, "bank.no-access");
        }
        return allowed;
    }

    private void runBank(Player viewer, String accountId, String menu, long amount, boolean deposit) {
        if (!bankAccess(viewer, accountId, !deposit)) {
            return;
        }
        BankService bank = plugin.bank();
        Reason error = deposit ? bank.deposit(viewer, accountId, amount) : bank.withdraw(viewer, accountId, amount);
        if (error != null) {
            plugin.messages().send(viewer, error);
        } else {
            plugin.messages().send(viewer, deposit ? "bank.deposited" : "bank.withdrew", "amount", fmtCoins(amount));
        }
        open(viewer, menu);
    }

    private static String fmtCoins(double value) {
        return String.format(Locale.US, "%,.0f", value);
    }

    private void runRoleAction(Player viewer, @Nullable Reason error, String successKey, String targetName,
                               String targetKey) {
        if (error != null) {
            plugin.messages().send(viewer, "coop.role-error", "error", plugin.messages().raw(error));
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

    // every online player not already on the roster; click to invite
    private void fillCoopInvite(Player player, MenuTemplate template, MenuCanvas canvas) {
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
            canvas.setItem(slot, inviteCandidateIcon(online, template.text()));
            canvas.putAction(slot, (viewer, right) -> {
                Player target = Bukkit.getPlayerExact(online.getName());
                if (target == null) {
                    plugin.messages().send(viewer, "coop.invite-error", "error",
                            plugin.messages().raw("coop.errors.not-online"));
                    open(viewer, COOP_INVITE);
                    return;
                }
                Reason error = plugin.profiles().invite(viewer, target);
                if (error != null) {
                    plugin.messages().send(viewer, "coop.invite-error", "error", plugin.messages().raw(error));
                } else {
                    plugin.messages().send(viewer, "coop.invite-sent", "player", target.getName());
                    plugin.messages().sendInvite(target, viewer.getName());
                }
                open(viewer, COOP);
            });
        }
    }

    // OWNER, CO_OWNER, MEMBER, then anything else
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
                : plugin.conf().getInt("coop.max-members", 4);
    }

    // a coop member's head, named and described from the menu file's name/lore paths
    private ItemStack memberIcon(com.mystipixel.royalskyblock.profile.ProfileMember member, MenuText text,
                                 String namePath, String lorePath) {
        boolean online = Bukkit.getPlayerExact(member.name()) != null;
        Map<String, String> ph = ph(
                "member", member.name(),
                "role", roleName(member.role()),
                "status", plugin.messages().raw(online ? "format.online" : "format.offline"));
        return dress(head(Bukkit.getOfflinePlayer(member.uuid())), text, namePath, lorePath, ph);
    }

    private String roleName(IslandRole role) {
        return plugin.messages().raw("format.role." + role.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    private ItemStack inviteCandidateIcon(Player target, MenuText text) {
        return dress(head(target), text, "entry.name", "entry.lore", ph("name", target.getName()));
    }

    private void fillLevel(Player player, MenuTemplate template, MenuCanvas canvas) {
        // perks entry point, only when the optional perks system is enabled
        MenuText text = template.text();
        if (plugin.perks().enabled()) {
            int perks = place(canvas, text.slot("perks-button"), button(text, "perks-button", "lore", ph()));
            onClick(canvas, perks, (viewer, right) -> open(viewer, PERKS));
        }
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
            canvas.setItem(slots.get(0), button(text, "empty", "lore", ph()));
            return;
        }
        List<Map.Entry<Material, Long>> entries = new ArrayList<>(breakdown.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue() * cfg.value(b.getKey()),
                a.getValue() * cfg.value(a.getKey())));
        for (int i = 0; i < entries.size() && i < slots.size(); i++) {
            Map.Entry<Material, Long> e = entries.get(i);
            canvas.setItem(slots.get(i), levelBlockIcon(e.getKey(), e.getValue(), cfg.value(e.getKey()), text));
        }
    }

    private ItemStack levelBlockIcon(Material material, long count, long unit, MenuText text) {
        Map<String, String> ph = ph(
                "block", prettyName(material),
                "count", String.valueOf(count),
                "each", String.valueOf(unit),
                "total", String.valueOf(count * unit));
        return dress(new ItemStack(material.isItem() ? material : Material.PAPER), text, "entry.name", "entry.lore", ph);
    }

    // islands ranked by stored level (never scans live)
    private void fillTop(Player player, MenuTemplate template, MenuCanvas canvas) {
        List<Integer> slots = template.contentSlots();

        // same as fillVisit: read and rank off-thread, paint on the refresh
        List<BrowseRow> rows = browseRowsOrFetch(player, TOP, () -> {
            List<Island> all = new ArrayList<>(plugin.storage().getAllIslands());
            all.sort((a, b) -> Double.compare(b.level(), a.level()));
            List<BrowseRow> found = new ArrayList<>();
            for (Island island : all) {
                if (found.size() >= slots.size()) {
                    break;
                }
                Profile prof = plugin.profiles().getProfile(island.profileId());
                if (prof == null) {
                    continue;
                }
                found.add(new BrowseRow(island, prof, ownerName(prof)));
            }
            return found;
        });
        if (rows == null) {
            return;
        }
        int rank = 0;
        for (BrowseRow row : rows) {
            int slot = slots.get(rank++);
            canvas.setItem(slot, leaderboardIcon(rank, row.island(), row.profile(), row.ownerName(), template.text()));
        }
    }

    private ItemStack leaderboardIcon(int rank, Island island, Profile prof, String ownerName, MenuText text) {
        Map<String, String> ph = ph(
                "rank", String.valueOf(rank),
                "owner", ownerName,
                "level", String.valueOf((int) island.level()),
                "gamemode", prof.gamemode().name().toLowerCase(Locale.ROOT),
                "members", String.valueOf(prof.memberCount()));
        return dress(head(Bukkit.getOfflinePlayer(prof.owner())), text, "entry.name", "entry.lore", ph);
    }

    // a code-placed item from a menu file: its item: line (material and name) and the lore at loreKey
    private ItemStack button(MenuText text, String path, String loreKey, Map<String, String> ph) {
        return item(MenuText.fill(text.text(path + ".item"), ph),
                MenuText.fill(text.lines(path + "." + loreKey), ph, Map.of()));
    }

    private ItemStack item(String spec, List<String> lore) {
        return ItemSpec.parse(spec).build(ecoHook, Map.of(), lore);
    }

    // name and lore from the menu file on an item whose material the code picks
    private static ItemStack dress(ItemStack item, MenuText text, String namePath, String lorePath,
                                   Map<String, String> ph) {
        return dress(item, MenuText.fill(text.text(namePath), ph),
                MenuText.fill(text.lines(lorePath), ph, Map.of()));
    }

    private static ItemStack dress(ItemStack item, String name, List<String> loreLines) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(name));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>(loreLines.size());
            for (String line : loreLines) {
                lore.add(noItalic(line));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    // puts the item at slot when the menu file gave it one inside the menu; returns the slot, or -1
    private static int place(MenuCanvas canvas, int slot, ItemStack item) {
        if (slot < 0 || slot >= canvas.size()) {
            return -1;
        }
        canvas.setItem(slot, item);
        return slot;
    }

    private static void onClick(MenuCanvas canvas, int slot, BiConsumer<Player, Boolean> action) {
        if (slot >= 0) {
            canvas.putAction(slot, action);
        }
    }

    // placeholder pairs: "name", value, ... (a mutable map, so a builder can add to it)
    private static Map<String, String> ph(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], String.valueOf(pairs[i + 1]));
        }
        return map;
    }

    private void fillPerks(Player player, MenuTemplate template, MenuCanvas canvas) {
        List<Integer> slots = template.contentSlots();
        if (slots.isEmpty()) {
            return;
        }
        MenuText text = template.text();
        if (!plugin.perks().enabled()) {
            canvas.setItem(slots.get(0), button(text, "disabled", "lore", ph()));
            return;
        }
        UUID activeId = plugin.profiles().getActiveProfileId(player.getUniqueId());
        Island island = activeId == null ? null : plugin.islands().getIslandByProfile(activeId);
        int level = island == null ? 0 : (int) island.level();
        List<Perk> perks = plugin.perks().perks();
        if (perks.isEmpty()) {
            canvas.setItem(slots.get(0), button(text, "empty", "lore", ph()));
            return;
        }
        for (int i = 0; i < perks.size() && i < slots.size(); i++) {
            canvas.setItem(slots.get(i), perkIcon(perks.get(i), level, text));
        }
    }

    private ItemStack perkIcon(Perk perk, int islandLevel, MenuText text) {
        Map<String, String> ph = ph("name", perk.name(), "required_level", String.valueOf(perk.requiredLevel()));
        Map<String, List<String>> multi = Map.of("description", perk.description());
        if (islandLevel >= perk.requiredLevel()) {
            return dress(new ItemStack(perk.icon()), MenuText.fill(text.text("entry-unlocked.name"), ph),
                    MenuText.fill(text.lines("entry-unlocked.lore"), ph, multi));
        }
        return item(MenuText.fill(text.text("entry-locked.item"), ph),
                MenuText.fill(text.lines("entry-locked.lore"), ph, multi));
    }

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

    private Map<String, String> placeholders(Player player) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("player", player.getName());
        map.put("has_island", String.valueOf(plugin.profiles().activeHasIsland(player)));
        UUID activeId = plugin.profiles().getActiveProfileId(player.getUniqueId());
        Island island = activeId == null ? null : plugin.islands().getIslandByProfile(activeId);
        map.put("island_level", island == null ? "0" : String.valueOf((int) island.level()));
        return map;
    }

    // Every menu is an eco Menu, so eco owns clicks and dispatches them to the slot handlers built in EcoMenuFactory.

    private void execute(Player player, MenuEffect effect) {
        switch (effect.id().toLowerCase(Locale.ROOT)) {
            case "open_menu" -> runNextTick(() -> open(player, effect.argString("menu", MAIN)));
            case "close" -> runNextTick(player::closeInventory);
            case "player_command" -> runNextTick(() -> {
                player.closeInventory();
                String command = apply(effect.argString("command", ""), placeholders(player));
                // performCommand skips PlayerCommandPreprocessEvent, so the gamemode gate must be asked here.
                if (!CommandGateListener.refuse(plugin, player, command)) {
                    player.performCommand(command);
                }
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
                    // bad sound key, ignore
                }
            }
            // Anything else is a libreforge effect run by MenuChains; libreforge reports unknown ids at
            // compile time.
            default -> { }
        }
    }

    private void runNextTick(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    // Generated content has no config entry for a sound:, so it uses the menu's sounds.content; a slot
    // pinned in config still wins. Most menus leave content unset because the menu they reopen plays its
    // own open sound.
    private void playDynamicSound(Player player, MenuTemplate template, MenuSlot configured) {
        if (configured != null && configured.sound() != null) {
            play(player, null, null, configured.sound());
            return;
        }
        play(player, template, "content", null);
    }

    // The only thing that makes a click audible: no menu-wide default, so a navigation button doesn't
    // double with the sound of the menu it opens.
    private void playSlotSound(Player player, MenuSlot slot) {
        if (slot == null || slot.sound() == null) {
            return;
        }
        play(player, null, null, slot.sound());
    }

    private void play(Player player, MenuTemplate template, String key, MenuTemplate.SoundSpec fallback) {
        MenuTemplate.SoundSpec spec = template == null ? null : template.sound(key);
        if (spec == null) {
            spec = fallback;
        }
        if (spec == null) {
            return;
        }
        try {
            player.playSound(player.getLocation(), spec.name(), spec.volume(), spec.pitch());
        } catch (Throwable ignored) {
            // bad sound key or unavailable: never let it break a click
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
