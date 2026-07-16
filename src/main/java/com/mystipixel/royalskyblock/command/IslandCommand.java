package com.mystipixel.royalskyblock.command;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.gui.GuiManager;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.profile.Gamemode;
import com.mystipixel.royalskyblock.profile.Profile;
import com.mystipixel.royalskyblock.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The {@code /island} command tree. Island actions operate on the player's <em>active profile</em>;
 * {@code /is profile} manages the profiles themselves. Player-facing text comes from messages.yml.
 */
public final class IslandCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT_SUBS = List.of(
            "menu", "create", "home", "go", "visit", "profile", "invite", "accept", "deny",
            "kick", "leave", "members", "manage", "bank", "transfer", "promote", "demote", "settings",
            "setspawn", "sethome", "setguestspawn", "kickall",
            "level", "top", "perks", "upgrade", "delete", "reload", "admin");

    private final RoyalSkyblockPlugin plugin;

    public IslandCommand(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "menu" -> handleMenu(sender);
            case "reload" -> handleReload(sender);
            case "create" -> handleCreate(sender);
            case "home", "go" -> handleHome(sender);
            case "visit" -> handleVisit(sender, args);
            case "profile", "profiles" -> handleProfile(sender, args);
            case "invite" -> handleInvite(sender, args);
            case "accept" -> handleAccept(sender);
            case "deny", "decline" -> handleDeny(sender);
            case "kick" -> handleKick(sender, args);
            case "transfer" -> handleTransfer(sender, args);
            case "promote" -> handlePromote(sender, args);
            case "demote" -> handleDemote(sender, args);
            case "leave" -> handleLeave(sender);
            case "members" -> handleMembers(sender);
            case "manage" -> handleManage(sender);
            case "bank" -> handleBank(sender);
            case "level" -> handleLevel(sender, args);
            case "top" -> handleTop(sender);
            case "perks" -> handlePerks(sender);
            case "settings" -> handleSettings(sender);
            case "upgrade", "upgrades" -> handleUpgrades(sender);
            case "sethome", "setspawn" -> handleSetSpawn(sender, false);
            case "setguestspawn" -> handleSetSpawn(sender, true);
            case "kickall" -> handleKickAll(sender);
            case "delete" -> handleDelete(sender, args);
            case "admin" -> handleAdmin(sender, args);
            default -> sender.sendMessage(Text.color("&e/is " + args[0] + " &7isn't wired up yet — coming soon."));
        }
        return true;
    }

    private void handleMenu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        plugin.gui().open(player, GuiManager.MAIN);
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("royalskyblock.admin")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        plugin.reload();
        plugin.messages().send(sender, "general.reloaded");
    }

    private void handleCreate(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (!player.hasPermission("royalskyblock.create")) {
            plugin.messages().send(player, "island.no-permission-create");
            return;
        }
        if (!plugin.isWorldBackendReady()) {
            plugin.messages().send(player, "island.worlds-unavailable");
            return;
        }
        if (plugin.profiles().activeHasIsland(player)) {
            plugin.messages().send(player, "island.already-have");
            return;
        }
        plugin.messages().send(player, "island.creating");
        plugin.profiles().goToActiveIsland(player).whenComplete((ok, error) -> onMain(() -> {
            if (error != null) {
                plugin.messages().send(player, "island.create-failed", "error", rootMessage(error));
            } else {
                plugin.messages().send(player, "island.created");
            }
        }));
    }

    private void handleHome(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (!plugin.isWorldBackendReady()) {
            plugin.messages().send(player, "island.worlds-unavailable");
            return;
        }
        plugin.profiles().goToActiveIsland(player).whenComplete((ok, error) -> onMain(() -> {
            if (error != null) {
                plugin.messages().send(player, "home.failed", "error", rootMessage(error));
            } else if (!Boolean.TRUE.equals(ok)) {
                plugin.messages().send(player, "home.no-island");
            }
        }));
    }

    private void handleVisit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (!player.hasPermission("royalskyblock.visit")) {
            plugin.messages().send(player, "visit.no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.gui().open(player, GuiManager.VISIT); // no target -> open the browser
            return;
        }
        String targetName = args[1];
        UUID targetId = plugin.getServer().getOfflinePlayer(targetName).getUniqueId();
        if (targetId.equals(player.getUniqueId())) {
            handleHome(player);
            return;
        }
        UUID targetActive = plugin.profiles().getActiveProfileId(targetId);
        Island island = targetActive == null ? null : plugin.islands().getIslandByProfile(targetActive);
        if (island == null) {
            plugin.messages().send(player, "visit.no-target-island", "player", targetName);
            return;
        }
        // Privacy: a private island only admits its own profile members.
        if (!island.isEnabled(com.mystipixel.royalskyblock.island.IslandSetting.VISITORS_ALLOWED)
                && !player.hasPermission("royalskyblock.bypass")) {
            Profile prof = plugin.profiles().getProfile(island.profileId());
            if (prof == null || !prof.isMember(player.getUniqueId())) {
                plugin.messages().send(player, "visit.private", "player", targetName);
                return;
            }
        }
        // Guest limit: count non-members already on the island (if its world is loaded).
        org.bukkit.World world = plugin.getServer().getWorld(island.worldName());
        if (world != null && !player.hasPermission("royalskyblock.bypass")) {
            Profile prof = plugin.profiles().getProfile(island.profileId());
            int visitors = 0;
            for (Player p : world.getPlayers()) {
                if (prof == null || !prof.isMember(p.getUniqueId())) {
                    visitors++;
                }
            }
            if (visitors >= plugin.upgrades().guestLimit(island)) {
                plugin.messages().send(player, "visit.full", "player", targetName);
                return;
            }
        }
        plugin.islands().teleportVisitor(player, island).whenComplete((ok, error) -> onMain(() -> {
            if (error != null) {
                plugin.messages().send(player, "visit.failed", "error", rootMessage(error));
            } else {
                plugin.messages().send(player, "visit.visiting", "player", targetName);
            }
        }));
    }

    private void handleDelete(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        UUID active = plugin.profiles().getActiveProfileId(player.getUniqueId());
        Island island = active == null ? null : plugin.islands().getIslandByProfile(active);
        if (island == null) {
            plugin.messages().send(player, "home.no-island");
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            plugin.messages().sendPlain(player, "delete.warning");
            plugin.messages().sendPlain(player, "delete.confirm-hint");
            return;
        }
        plugin.messages().send(player, "delete.deleting");
        plugin.islands().deleteIsland(island.id()).whenComplete((ignored, error) -> onMain(() -> {
            if (error != null) {
                plugin.messages().send(player, "delete.failed", "error", rootMessage(error));
            } else {
                plugin.messages().send(player, "delete.deleted");
            }
        }));
    }

    // ── profiles ──────────────────────────────────────────────────────────────────

    private void handleProfile(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "create" -> profileCreate(player, args);
            case "switch", "swap" -> profileSwitch(player, args);
            case "delete" -> profileDelete(player, args);
            default -> profileList(player);
        }
    }

    private void profileList(Player player) {
        List<Profile> profiles = plugin.profiles().getProfiles(player.getUniqueId());
        UUID active = plugin.profiles().getActiveProfileId(player.getUniqueId());
        int max = plugin.getConfig().getInt("profiles.max-profiles", 3);
        plugin.messages().sendPlain(player, "profile.header", "count", String.valueOf(profiles.size()), "max", String.valueOf(max));
        for (Profile p : profiles) {
            boolean hasIsland = plugin.islands().getIslandByProfile(p.id()) != null;
            String activeTag = p.id().equals(active) ? plugin.messages().raw("profile.active-tag") : "";
            plugin.messages().sendPlain(player, "profile.line",
                    "name", p.name(),
                    "gamemode", p.gamemode().name().toLowerCase(Locale.ROOT),
                    "active", activeTag,
                    "has_island", hasIsland ? "&ayes" : "&cno");
        }
        plugin.messages().sendPlain(player, "profile.hint");
    }

    private void profileCreate(Player player, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(player, "profile.create-usage");
            return;
        }
        Gamemode gamemode = Gamemode.fromString(args[2], null);
        if (gamemode == null) {
            plugin.messages().send(player, "profile.unknown-gamemode");
            return;
        }
        String name = args.length >= 4 ? args[3] : null;
        if (!plugin.isWorldBackendReady()) {
            plugin.messages().send(player, "island.worlds-unavailable");
            return;
        }
        plugin.profiles().createProfile(player, gamemode, name).whenComplete((profile, error) -> onMain(() -> {
            if (error != null) {
                plugin.messages().send(player, "profile.create-failed", "error", rootMessage(error));
            } else {
                plugin.messages().send(player, "profile.created",
                        "name", profile.name(), "gamemode", profile.gamemode().name().toLowerCase(Locale.ROOT));
            }
        }));
    }

    private void profileSwitch(Player player, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(player, "profile.switch-usage");
            return;
        }
        Profile target = findProfile(player, args[2]);
        if (target == null) {
            plugin.messages().send(player, "profile.not-found", "name", args[2]);
            return;
        }
        plugin.messages().send(player, "profile.switching", "name", target.name());
        plugin.profiles().switchProfile(player, target.id()).whenComplete((ok, error) -> onMain(() -> {
            if (error != null) {
                plugin.messages().send(player, "profile.switch-failed", "error", rootMessage(error));
            } else if (Boolean.TRUE.equals(ok)) {
                plugin.messages().send(player, "profile.switched", "name", target.name());
            }
        }));
    }

    private void profileDelete(Player player, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(player, "profile.delete-usage");
            return;
        }
        Profile target = findProfile(player, args[2]);
        if (target == null) {
            plugin.messages().send(player, "profile.not-found", "name", args[2]);
            return;
        }
        String name = target.name();
        plugin.profiles().deleteProfile(player, target.id()).whenComplete((ok, error) -> onMain(() -> {
            if (Boolean.TRUE.equals(ok)) {
                plugin.messages().send(player, "profile.deleted", "name", name);
            }
        }));
    }

    /** Find a player's profile by name (case-insensitive) or 1-based list index. */
    private Profile findProfile(Player player, String query) {
        List<Profile> profiles = plugin.profiles().getProfiles(player.getUniqueId());
        for (Profile p : profiles) {
            if (p.name().equalsIgnoreCase(query)) {
                return p;
            }
        }
        try {
            int index = Integer.parseInt(query) - 1;
            if (index >= 0 && index < profiles.size()) {
                return profiles.get(index);
            }
        } catch (NumberFormatException ignored) {
            // not an index
        }
        return null;
    }

    /** Set the island home ({@code setspawn}/{@code sethome}) or the guest spawn ({@code setguestspawn}). */
    private void handleSetSpawn(CommandSender sender, boolean guest) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        UUID active = plugin.profiles().getActiveProfileId(player.getUniqueId());
        Island island = active == null ? null : plugin.islands().getIslandByProfile(active);
        if (island == null) {
            plugin.messages().send(player, "home.no-island");
            return;
        }
        if (!player.getWorld().getName().equals(island.worldName())) {
            plugin.messages().send(player, "island.not-on-island");
            return;
        }
        if (!isIslandManager(player, island)) {
            plugin.messages().send(player, "island.no-permission-manage");
            return;
        }
        org.bukkit.Location l = player.getLocation();
        if (guest) {
            island.setGuestHome(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        } else {
            island.setHome(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> plugin.storage().saveIsland(island));
        plugin.messages().send(player, guest ? "island.guest-spawn-set" : "island.spawn-set");
    }

    private void handleKickAll(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        UUID active = plugin.profiles().getActiveProfileId(player.getUniqueId());
        Island island = active == null ? null : plugin.islands().getIslandByProfile(active);
        if (island == null) {
            plugin.messages().send(player, "home.no-island");
            return;
        }
        if (!isIslandManager(player, island)) {
            plugin.messages().send(player, "island.no-permission-manage");
            return;
        }
        org.bukkit.World world = plugin.getServer().getWorld(island.worldName());
        Profile profile = plugin.profiles().getProfile(island.profileId());
        int kicked = 0;
        if (world != null && profile != null) {
            org.bukkit.Location spawn = plugin.islands().resolveSpawnLocation();
            for (Player online : new ArrayList<>(world.getPlayers())) {
                if (!profile.isMember(online.getUniqueId())) {
                    if (spawn != null) {
                        online.teleport(spawn);
                    }
                    kicked++;
                }
            }
        }
        plugin.messages().send(player, "island.kicked-guests", "count", String.valueOf(kicked));
    }

    /** True if the player owns or co-owns the island's profile. */
    private boolean isIslandManager(Player player, Island island) {
        Profile profile = plugin.profiles().getProfile(island.profileId());
        if (profile == null) {
            return false;
        }
        var role = profile.roleOf(player.getUniqueId());
        return role == com.mystipixel.royalskyblock.island.IslandRole.OWNER
                || role == com.mystipixel.royalskyblock.island.IslandRole.CO_OWNER;
    }

    private void handleSettings(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (!player.hasPermission("royalskyblock.settings")) {
            plugin.messages().send(player, "general.no-permission");
            return;
        }
        if (!plugin.profiles().activeHasIsland(player)) {
            plugin.messages().send(player, "home.no-island");
            return;
        }
        plugin.gui().open(player, GuiManager.SETTINGS);
    }

    private void handleUpgrades(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (!plugin.profiles().activeHasIsland(player)) {
            plugin.messages().send(player, "home.no-island");
            return;
        }
        plugin.gui().open(player, GuiManager.UPGRADES);
    }

    // ── coop invites ──────────────────────────────────────────────────────────────

    private void handleInvite(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (!player.hasPermission("royalskyblock.invite")) {
            plugin.messages().send(player, "general.no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.gui().open(player, GuiManager.COOP_INVITE); // no target -> open the picker
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) {
            plugin.messages().send(player, "coop.invite-error", "error", "That player isn't online.");
            return;
        }
        String error = plugin.profiles().invite(player, target);
        if (error != null) {
            plugin.messages().send(player, "coop.invite-error", "error", error);
            return;
        }
        plugin.messages().send(player, "coop.invite-sent", "player", target.getName());
        plugin.messages().sendInvite(target, player.getName());
    }

    private void handleAccept(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        var result = plugin.profiles().acceptInvite(player);
        if (result.profile() != null) {
            plugin.messages().send(player, "coop.accepted", "profile", result.profile().name());
            return;
        }
        switch (result.error() == null ? "none" : result.error()) {
            case "expired" -> plugin.messages().send(player, "coop.invite-expired");
            case "full" -> plugin.messages().send(player, "coop.invite-full");
            default -> plugin.messages().send(player, "coop.no-invite");
        }
    }

    private void handleDeny(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (plugin.profiles().denyInvite(player)) {
            plugin.messages().send(player, "coop.denied");
        } else {
            plugin.messages().send(player, "coop.no-invite");
        }
    }

    private void handleKick(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "coop.kick-usage");
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        String error = plugin.profiles().kick(player, args[1]);
        if (error != null) {
            plugin.messages().send(player, "coop.kick-error", "error", error);
            return;
        }
        plugin.messages().send(player, "coop.kicked", "player", args[1]);
        if (target != null) {
            plugin.messages().send(target, "coop.you-were-kicked");
        }
    }

    private void handleTransfer(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (args.length < 2) {
            plugin.gui().open(player, GuiManager.COOP); // no target -> open the roster to pick
            return;
        }
        coopRoleCommand(player, args[1], plugin.profiles().transferOwnership(player, args[1]),
                "coop.transferred", "coop.you-owner");
    }

    private void handlePromote(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (args.length < 2) {
            plugin.gui().open(player, GuiManager.COOP);
            return;
        }
        coopRoleCommand(player, args[1], plugin.profiles().promote(player, args[1]),
                "coop.promoted", "coop.you-promoted");
    }

    private void handleDemote(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (args.length < 2) {
            plugin.gui().open(player, GuiManager.COOP);
            return;
        }
        coopRoleCommand(player, args[1], plugin.profiles().demote(player, args[1]),
                "coop.demoted", "coop.you-demoted");
    }

    /** Shared: report a coop role change to the actor, and notify the online target. */
    private void coopRoleCommand(Player actor, String targetName, String error, String successKey, String targetKey) {
        if (error != null) {
            plugin.messages().send(actor, "coop.role-error", "error", error);
            return;
        }
        plugin.messages().send(actor, successKey, "player", targetName);
        Player online = plugin.getServer().getPlayerExact(targetName);
        if (online != null && targetKey != null) {
            plugin.messages().send(online, targetKey);
        }
    }

    private void handleLeave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        String error = plugin.profiles().leave(player);
        if (error != null) {
            plugin.messages().send(player, "coop.leave-error", "error", error);
        } else {
            plugin.messages().send(player, "coop.left");
        }
    }

    private void handleMembers(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (plugin.profiles().getActiveProfile(player) == null) {
            plugin.messages().send(player, "home.no-island");
            return;
        }
        plugin.gui().open(player, GuiManager.COOP);
    }

    private void handleManage(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (!plugin.profiles().activeHasIsland(player)) {
            plugin.messages().send(player, "home.no-island");
            return;
        }
        plugin.gui().open(player, GuiManager.MANAGE);
    }

    private void handleBank(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        Profile active = plugin.profiles().getActiveProfile(player);
        if (active != null && active.gamemode() == Gamemode.COOP) {
            plugin.gui().open(player, GuiManager.BANK_HUB); // coop: choose personal vs coop bank
        } else {
            plugin.gui().open(player, GuiManager.BANK_PERSONAL); // solo/ironman: personal bank
        }
    }

    /** {@code /is level} opens the level menu; {@code /is level recalc} triggers a fresh scan. */
    private void handleLevel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        Island island = islandOf(player);
        if (island == null) {
            plugin.messages().send(player, "home.no-island");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("recalc")) {
            if (plugin.getServer().getWorld(island.worldName()) == null) {
                plugin.messages().send(player, "island.world-not-loaded");
                return;
            }
            long cooldown = plugin.levels().cooldownRemaining(island);
            if (cooldown > 0) {
                plugin.messages().send(player, "level.cooldown", "seconds", String.valueOf(cooldown));
                return;
            }
            plugin.messages().send(player, "level.scanning");
            plugin.levels().recalc(island).whenComplete((level, error) -> onMain(() -> {
                plugin.messages().send(player, "level.done", "level",
                        String.valueOf(level == null ? (int) island.level() : level.intValue()));
                plugin.gui().open(player, GuiManager.LEVEL);
            }));
            return;
        }
        plugin.gui().open(player, GuiManager.LEVEL);
    }

    private void handleTop(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        plugin.gui().open(player, GuiManager.TOP);
    }

    private void handlePerks(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (!plugin.perks().enabled()) {
            plugin.messages().send(player, "perks.disabled");
            return;
        }
        plugin.gui().open(player, GuiManager.PERKS);
    }

    // ── admin / spike diagnostics ────────────────────────────────────────────────

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("royalskyblock.admin")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (action.equals("testworld")) {
            handleTestWorld(sender);
            return;
        }
        if (action.equals("schematic")) {
            handleSchematic(sender, args);
            return;
        }
        if (action.equals("chesttest")) {
            handleChestTest(sender);
            return;
        }
        if (action.equals("upgrade")) {
            handleUpgradeAdmin(sender, args);
            return;
        }
        if (action.equals("status")) {
            handleAdminStatus(sender);
            return;
        }
        if (action.equals("border")) {
            handleBorderAdmin(sender, args);
            return;
        }
        sender.sendMessage(Text.color("&8» &e/is admin status &7— dependency & config health"));
        sender.sendMessage(Text.color("&8» &e/is admin border <blue|red|green|off> &7— island border colour"));
        sender.sendMessage(Text.color("&8» &e/is admin testworld &7— ASP world round-trip diagnostic"));
        sender.sendMessage(Text.color("&8» &e/is admin schematic save <name> &7— save your WorldEdit selection"));
        sender.sendMessage(Text.color("&8» &e/is admin upgrade <key> <tier> &7— set an upgrade tier instantly"));
    }

    /** {@code /is admin border <blue|red|green|off>} — set the island border colour live. */
    private void handleBorderAdmin(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.color("&7Border colour: &f" + plugin.borders().color().name().toLowerCase(Locale.ROOT)
                    + "  &8· &e/is admin border <blue|red|green|off>"));
            return;
        }
        String choice = args[2].toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("blue", "red", "green", "off").contains(choice)) {
            sender.sendMessage(Text.color("&cUse: blue, red, green, or off."));
            return;
        }
        plugin.getConfig().set("island.border.color", choice);
        plugin.saveConfig();
        plugin.borders().reload();
        plugin.borders().refreshAll();
        sender.sendMessage(Text.color("&aIsland border set to &e" + choice + "&a (applied live)."));
    }

    /** In-game version of the boot status panel: which dependencies are active + config health. */
    private void handleAdminStatus(CommandSender sender) {
        String worldSrc = plugin.getConfig().getString("world.slime-data-source", "file");
        String storage = plugin.getConfig().getString("storage.type", "sqlite").toUpperCase(Locale.ROOT);
        sender.sendMessage(Text.color("&6&l✦ RoyalSkyblock &7— status"));
        sender.sendMessage(Text.color(dep("Islands (ASP)", plugin.isWorldBackendReady(),
                "source: " + worldSrc, "install Advanced Slime Paper")));
        sender.sendMessage(Text.color(dep("Economy (Vault)", plugin.economyReady(),
                "", "not found — bank & coin costs off")));
        sender.sendMessage(Text.color(dep("Bank", plugin.bank().available(),
                plugin.bank().levels().levelCount() + " levels", "needs Vault + bank.yml levels")));
        sender.sendMessage(Text.color(dep("Schematics", plugin.schematics().isAvailable(),
                "WorldEdit/FAWE", "built-in generator")));
        sender.sendMessage(Text.color(dep("Progression (eco)", plugin.eco().isPresent(),
                "per-profile", "not per-profile")));
        sender.sendMessage(Text.color("&7Storage: &f" + storage));
        sender.sendMessage(Text.color("&7Perks: " + (plugin.perks().enabled()
                ? "&a✔ on &7(" + plugin.perks().perkCount() + ")" : "&8off (optional)")));
        sender.sendMessage(Text.color("&7Border: &f" + plugin.borders().color().name().toLowerCase(Locale.ROOT)
                + " &8(per-player; admins bypass)"));
        sender.sendMessage(Text.color("&7Config health:"));
        String spawnWorld = plugin.getConfig().getString("spawn.world", "world");
        boolean spawnOk = plugin.getServer().getWorld(spawnWorld) != null;
        sender.sendMessage(Text.color(check(spawnOk, "spawn world '" + spawnWorld + "' loaded",
                "spawn world '" + spawnWorld + "' NOT found — set spawn.world in config.yml")));
        int upTracks = plugin.upgrades().all().size();
        sender.sendMessage(Text.color(check(upTracks > 0, upTracks + " upgrade track(s) loaded",
                "no upgrades loaded — check upgrades.yml")));
        sender.sendMessage(Text.color(check(!plugin.bank().levels().isEmpty(),
                plugin.bank().levels().levelCount() + " bank level(s) loaded", "no bank levels — check bank.yml")));
    }

    private static String dep(String name, boolean ok, String okDetail, String failDetail) {
        if (ok) {
            return "&7" + name + ": &a✔ ready" + (okDetail.isEmpty() ? "" : " &7(" + okDetail + ")");
        }
        return "&7" + name + ": &c✘ " + failDetail;
    }

    private static String check(boolean ok, String okMsg, String failMsg) {
        return ok ? "  &a✔ &7" + okMsg : "  &c✘ &f" + failMsg;
    }

    private void handleSchematic(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (args.length < 4 || !args[2].equalsIgnoreCase("save")) {
            sender.sendMessage(Text.color("&cUsage: &e/is admin schematic save <name>"));
            return;
        }
        if (!plugin.schematics().isAvailable()) {
            sender.sendMessage(Text.color("&cWorldEdit or FAWE isn't installed — schematics are unavailable."));
            return;
        }
        String name = args[3];
        String error = plugin.schematics().saveSelection(player, name);
        if (error == null) {
            sender.sendMessage(Text.color("&aSaved &eschematics/" + name + ".schem&a. "
                    + "Use it by setting &eisland.starter.schematic: " + name + " &ain config.yml."));
        } else {
            sender.sendMessage(Text.color("&c" + error));
        }
    }

    /** Diagnostic: place a chest with items, save+unload+reload the world, count items each step —
     *  tells us whether an empty starter chest is a placement bug or an ASP persistence bug. */
    private void handleChestTest(CommandSender sender) {
        if (!plugin.isWorldBackendReady()) {
            sender.sendMessage(Text.color("&cWorld backend not ready."));
            return;
        }
        String name = "rsb_chesttest";
        plugin.worlds().createIsland(name)
                .thenCompose(world -> onMainSupply(() -> {
                    org.bukkit.block.Block b = world.getBlockAt(0, 101, 0);
                    b.setType(org.bukkit.Material.CHEST, false);
                    if (b.getState(false) instanceof org.bukkit.block.Chest chest) {
                        chest.getBlockInventory().addItem(new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND, 5));
                        chest.update(true, false);
                    }
                    sender.sendMessage(Text.color("&7[chest] placed; count now = &e" + chestCount(world)));
                    return world;
                }))
                .thenCompose(w -> plugin.worlds().unloadIsland(name, true))
                .thenCompose(v -> plugin.worlds().loadIsland(name))
                .thenCompose(world -> onMainSupply(() -> {
                    sender.sendMessage(Text.color("&7[chest] after save+reload = &e" + chestCount(world)
                            + " &7(5 = persists ✔, 0 = ASP drops container contents)"));
                    return world;
                }))
                .thenCompose(w -> plugin.worlds().deleteIsland(name))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        onMain(() -> sender.sendMessage(Text.color("&c[chest] FAILED: " + rootMessage(error))));
                    }
                });
    }

    private static int chestCount(org.bukkit.World world) {
        if (world.getBlockAt(0, 101, 0).getState() instanceof org.bukkit.block.Chest chest) {
            int n = 0;
            for (org.bukkit.inventory.ItemStack it : chest.getBlockInventory().getContents()) {
                if (it != null) {
                    n += it.getAmount();
                }
            }
            return n;
        }
        return -1;
    }

    private <T> java.util.concurrent.CompletableFuture<T> onMainSupply(java.util.function.Supplier<T> supplier) {
        java.util.concurrent.CompletableFuture<T> f = new java.util.concurrent.CompletableFuture<>();
        onMain(() -> {
            try {
                f.complete(supplier.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    private void handleUpgradeAdmin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(Text.color("&cUsage: &e/is admin upgrade <key> <tier>"));
            return;
        }
        com.mystipixel.royalskyblock.upgrade.UpgradeDef def = plugin.upgrades().get(args[2]);
        if (def == null) {
            sender.sendMessage(Text.color("&cUnknown upgrade '" + args[2] + "'. Try: size, guest-limit, coop-slots."));
            return;
        }
        int tier;
        try {
            tier = Math.max(0, Math.min(def.maxTier(), Integer.parseInt(args[3])));
        } catch (NumberFormatException e) {
            sender.sendMessage(Text.color("&cTier must be a number (0-" + def.maxTier() + ")."));
            return;
        }
        com.mystipixel.royalskyblock.island.Island island = islandOf(player);
        if (island == null) {
            plugin.messages().send(player, "home.no-island");
            return;
        }
        plugin.upgrades().setTier(island, def, tier);
        sender.sendMessage(Text.color("&aSet &e" + def.key() + " &ato tier &e" + tier
                + " &7(value " + def.valueAt(tier) + ")."));
    }

    private com.mystipixel.royalskyblock.island.@org.jetbrains.annotations.Nullable Island islandOf(Player player) {
        java.util.UUID active = plugin.profiles().getActiveProfileId(player.getUniqueId());
        return active == null ? null : plugin.islands().getIslandByProfile(active);
    }

    private void handleTestWorld(CommandSender sender) {
        if (!plugin.isWorldBackendReady()) {
            sender.sendMessage(Text.color("&cWorld backend not ready — is the server running Advanced Slime Paper?"));
            return;
        }
        String name = "rsb_diagnostic";
        sender.sendMessage(Text.color("&7[diag] creating + loading throwaway slime world '" + name + "'..."));
        plugin.worlds().createIsland(name)
                .thenCompose(world -> {
                    onMain(() -> sender.sendMessage(Text.color("&a[diag] created & loaded &f" + world.getName())));
                    return plugin.worlds().deleteIsland(name);
                })
                .whenComplete((ignored, error) -> onMain(() -> {
                    if (error != null) {
                        sender.sendMessage(Text.color("&c[diag] FAILED: " + rootMessage(error)));
                    } else {
                        sender.sendMessage(Text.color("&a[diag] deleted '" + name + "' — ASP round-trip succeeded. ✔"));
                    }
                }));
    }

    // ── help / tab-complete ──────────────────────────────────────────────────────

    private void sendHelp(CommandSender sender) {
        plugin.messages().sendPlain(sender, "help.header");
        plugin.messages().sendPlain(sender, "help.subtitle");
        plugin.messages().sendPlain(sender, "help.island");
        plugin.messages().sendPlain(sender, "help.bank");
        plugin.messages().sendPlain(sender, "help.level");
        plugin.messages().sendPlain(sender, "help.upgrade");
        plugin.messages().sendPlain(sender, "help.profile");
        plugin.messages().sendPlain(sender, "help.coop");
        plugin.messages().sendPlain(sender, "help.manage");
        plugin.messages().sendPlain(sender, "help.delete");
        if (sender.hasPermission("royalskyblock.admin")) {
            plugin.messages().sendPlain(sender, "help.admin");
        }
    }

    private void onMain(Runnable runnable) {
        if (plugin.getServer().isPrimaryThread()) {
            runnable.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(ROOT_SUBS, args[0], sender);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && sub.equals("delete")) {
            return List.of("confirm");
        }
        if (args.length == 2 && sub.equals("level")) {
            return filter(List.of("recalc"), args[1], sender);
        }
        if (args.length == 2 && (sub.equals("profile") || sub.equals("profiles"))) {
            return filter(List.of("list", "create", "switch", "delete"), args[1], sender);
        }
        if (args.length == 3 && (sub.equals("profile") || sub.equals("profiles")) && args[1].equalsIgnoreCase("create")) {
            return filter(List.of("solo", "coop", "ironman"), args[2], sender);
        }
        if (args.length == 3 && (sub.equals("profile") || sub.equals("profiles"))
                && (args[1].equalsIgnoreCase("switch") || args[1].equalsIgnoreCase("delete"))
                && sender instanceof Player player) {
            List<String> names = new ArrayList<>();
            for (Profile p : plugin.profiles().getProfiles(player.getUniqueId())) {
                names.add(p.name());
            }
            return filter(names, args[2], sender);
        }
        if (args.length == 2 && (sub.equals("visit") || sub.equals("invite"))) {
            List<String> names = new ArrayList<>();
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                names.add(online.getName());
            }
            return filter(names, args[1], sender);
        }
        if (args.length == 2 && (sub.equals("kick") || sub.equals("transfer") || sub.equals("promote")
                || sub.equals("demote")) && sender instanceof Player player) {
            List<String> names = new ArrayList<>();
            Profile active = plugin.profiles().getActiveProfile(player);
            if (active != null) {
                for (var m : active.members()) {
                    if (!m.uuid().equals(player.getUniqueId())) {
                        names.add(m.name());
                    }
                }
            }
            return filter(names, args[1], sender);
        }
        if (args.length == 2 && sub.equals("admin") && sender.hasPermission("royalskyblock.admin")) {
            return filter(List.of("status", "border", "testworld", "schematic", "upgrade", "chesttest"), args[1], sender);
        }
        if (args.length == 3 && sub.equals("admin") && args[1].equalsIgnoreCase("border")) {
            return filter(List.of("blue", "red", "green", "off"), args[2], sender);
        }
        if (args.length == 3 && sub.equals("admin") && args[1].equalsIgnoreCase("schematic")) {
            return filter(List.of("save"), args[2], sender);
        }
        if (args.length == 3 && sub.equals("admin") && args[1].equalsIgnoreCase("upgrade")) {
            List<String> keys = new ArrayList<>();
            for (var def : plugin.upgrades().all()) {
                keys.add(def.key());
            }
            return filter(keys, args[2], sender);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix, CommandSender sender) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : options) {
            if ((s.equals("reload") || s.equals("admin")) && !sender.hasPermission("royalskyblock.admin")) {
                continue;
            }
            if (s.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(s);
            }
        }
        return out;
    }
}
