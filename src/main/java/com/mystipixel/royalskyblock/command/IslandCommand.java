package com.mystipixel.royalskyblock.command;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.config.ContentSplitter;
import com.mystipixel.royalskyblock.data.StorageException;
import com.mystipixel.royalskyblock.gui.GuiManager;
import com.mystipixel.royalskyblock.island.Island;
import com.mystipixel.royalskyblock.island.IslandHostedElsewhereException;
import com.mystipixel.royalskyblock.island.IslandRole;
import com.mystipixel.royalskyblock.listener.CommandGateListener;
import com.mystipixel.royalskyblock.message.Reason;
import com.mystipixel.royalskyblock.message.ReasonException;
import com.mystipixel.royalskyblock.profile.Gamemode;
import com.mystipixel.royalskyblock.profile.Profile;
import com.mystipixel.royalskyblock.profile.ProfileMember;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The {@code /island} command tree. Island actions operate on the player's <em>active profile</em>;
 * {@code /is profile} manages the profiles themselves. Player-facing text comes from lang.yml.
 */
public final class IslandCommand {

    private final RoyalSkyblockPlugin plugin;

    public IslandCommand(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    void handleMenu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        plugin.gui().open(player, GuiManager.MAIN);
    }

    void handleReload(CommandSender sender) {
        if (!sender.hasPermission("royalskyblock.admin")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        plugin.reload();
        plugin.messages().send(sender, "general.reloaded");
    }

    void handleCreate(CommandSender sender) {
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

    void handleHome(CommandSender sender) {
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

    void handleVisit(CommandSender sender, String[] args) {
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
        org.bukkit.OfflinePlayer known = knownPlayer(targetName);
        if (known == null) {
            plugin.messages().send(player, "visit.no-target-island", "player", targetName);
            return;
        }
        UUID targetId = known.getUniqueId();
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
        // a private island only admits its own profile members
        if (!island.isEnabled(com.mystipixel.royalskyblock.island.IslandSetting.VISITORS_ALLOWED)
                && !player.hasPermission("royalskyblock.bypass")) {
            Profile prof = plugin.profiles().getProfile(island.profileId());
            if (prof == null || !prof.isMember(player.getUniqueId())) {
                plugin.messages().send(player, "visit.private", "player", targetName);
                return;
            }
        }
        // guest limit: count non-members already on the island (if its world is loaded)
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
            IslandHostedElsewhereException hosted = IslandHostedElsewhereException.find(error);
            if (hosted != null) {
                plugin.messages().send(player, "visit.hosted-elsewhere", "player", targetName,
                        "server", hosted.holder());
            } else if (error != null) {
                plugin.messages().send(player, "visit.failed", "error", rootMessage(error));
            } else {
                plugin.messages().send(player, "visit.visiting", "player", targetName);
            }
        }));
    }

    // Online or in this server's user cache only: getOfflinePlayer(String) blocks on a Mojang lookup
    // for an unknown name, and /is visit passes it whatever a player typed.
    private @org.jetbrains.annotations.Nullable org.bukkit.OfflinePlayer knownPlayer(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        return online != null ? online : plugin.getServer().getOfflinePlayerIfCached(name);
    }

    void handleDelete(CommandSender sender, String[] args) {
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
        // Owner only, like deleteProfile: the island is shared by every coop member, so a member trashing
        // it would be a grief tool.
        Profile profile = plugin.profiles().getProfile(active);
        if (profile == null || profile.roleOf(player.getUniqueId()) != IslandRole.OWNER) {
            plugin.messages().send(player, "island.no-permission-manage");
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

    void handleProfile(CommandSender sender, String[] args) {
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

    void profileList(Player player) {
        List<Profile> profiles = plugin.profiles().getProfiles(player.getUniqueId());
        UUID active = plugin.profiles().getActiveProfileId(player.getUniqueId());
        int max = plugin.conf().getInt("profiles.max-profiles", 3);
        plugin.messages().sendPlain(player, "profile.header", "count", String.valueOf(profiles.size()), "max", String.valueOf(max));
        for (Profile p : profiles) {
            boolean hasIsland = plugin.islands().getIslandByProfile(p.id()) != null;
            String activeTag = p.id().equals(active) ? plugin.messages().raw("profile.active-tag") : "";
            plugin.messages().sendPlain(player, "profile.line",
                    "name", p.name(),
                    "gamemode", p.gamemode().name().toLowerCase(Locale.ROOT),
                    "active", activeTag,
                    "has_island", plugin.messages().raw(hasIsland ? "profile.island-yes" : "profile.island-no"));
        }
        plugin.messages().sendPlain(player, "profile.hint");
    }

    void profileCreate(Player player, String[] args) {
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

    void profileSwitch(Player player, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(player, "profile.switch-usage");
            return;
        }
        Profile target = findProfile(player, args[2], "switch");
        if (target == null) {
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

    void profileDelete(Player player, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(player, "profile.delete-usage");
            return;
        }
        Profile target = findProfile(player, args[2], "delete");
        if (target == null) {
            return;
        }
        String name = target.name();
        plugin.profiles().deleteProfile(player, target.id()).whenComplete((ok, error) -> onMain(() -> {
            if (error != null) {
                plugin.messages().send(player, "profile.delete-failed", "error", rootMessage(error));
            } else if (Boolean.TRUE.equals(ok)) {
                plugin.messages().send(player, "profile.deleted", "name", name);
            }
        }));
    }

    // Finds a profile by name (case-insensitive) or 1-based index, or tells the player why not and
    // returns null. Names aren't unique (a coop keeps its owner's name), so an ambiguous name picks
    // nothing and lists the choices by number; delete must never guess.
    private Profile findProfile(Player player, String query, String action) {
        List<Profile> profiles = plugin.profiles().getProfiles(player.getUniqueId());
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).name().equalsIgnoreCase(query)) {
                matches.add(i);
            }
        }
        if (matches.size() == 1) {
            return profiles.get(matches.get(0));
        }
        if (matches.size() > 1) {
            plugin.messages().send(player, "profile.ambiguous", "name", query);
            for (int i : matches) {
                Profile p = profiles.get(i);
                ProfileMember owner = p.member(p.owner());
                String ownerName = p.owner().equals(player.getUniqueId()) ? plugin.messages().raw("profile.owner-yours")
                        : owner != null ? plugin.messages().raw("profile.owner-named", "player", owner.name())
                        : plugin.messages().raw("profile.owner-unknown");
                plugin.messages().sendPlain(player, "profile.ambiguous-line",
                        "action", action, "index", String.valueOf(i + 1), "name", p.name(),
                        "gamemode", p.gamemode().name().toLowerCase(Locale.ROOT), "owner", ownerName);
            }
            return null;
        }
        try {
            int index = Integer.parseInt(query) - 1;
            if (index >= 0 && index < profiles.size()) {
                return profiles.get(index);
            }
        } catch (NumberFormatException ignored) {
            // not an index
        }
        plugin.messages().send(player, "profile.not-found", "name", query);
        return null;
    }

    void handleSetSpawn(CommandSender sender, boolean guest) {
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
        UUID islandId = island.id();
        if (guest) {
            island.setGuestHome(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
            String serialized = island.serializeGuestHome();
            plugin.islands().writeIsland(island, () -> plugin.storage().updateIslandGuestHome(islandId, serialized));
        } else {
            island.setHome(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
            plugin.islands().writeIsland(island, () -> plugin.storage().updateIslandHome(islandId,
                    l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()));
        }
        plugin.messages().send(player, guest ? "island.guest-spawn-set" : "island.spawn-set");
    }

    void handleKickAll(CommandSender sender) {
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

    private boolean isIslandManager(Player player, Island island) {
        Profile profile = plugin.profiles().getProfile(island.profileId());
        if (profile == null) {
            return false;
        }
        var role = profile.roleOf(player.getUniqueId());
        return role == com.mystipixel.royalskyblock.island.IslandRole.OWNER
                || role == com.mystipixel.royalskyblock.island.IslandRole.CO_OWNER;
    }

    void handleSettings(CommandSender sender) {
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

    void handleUpgrades(CommandSender sender) {
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

    void handleInvite(CommandSender sender, String[] args) {
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
            plugin.messages().send(player, "coop.invite-error", "error", plugin.messages().raw("coop.errors.not-online"));
            return;
        }
        Reason error = plugin.profiles().invite(player, target);
        if (error != null) {
            plugin.messages().send(player, "coop.invite-error", "error", plugin.messages().raw(error));
            return;
        }
        plugin.messages().send(player, "coop.invite-sent", "player", target.getName());
        plugin.messages().sendInvite(target, player.getName());
    }

    void handleAccept(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        var result = plugin.profiles().acceptInvite(player);
        if (result.profile() != null) {
            // straight onto the coop
            Profile joined = result.profile();
            plugin.messages().send(player, "coop.accepted-switching", "profile", joined.name());
            plugin.profiles().switchProfile(player, joined.id()).whenComplete((ok, error) -> onMain(() -> {
                if (error != null) {
                    plugin.messages().send(player, "profile.switch-failed", "error", rootMessage(error));
                }
            }));
            return;
        }
        switch (result.error() == null ? "none" : result.error()) {
            case "expired" -> plugin.messages().send(player, "coop.invite-expired");
            case "full" -> plugin.messages().send(player, "coop.invite-full");
            default -> plugin.messages().send(player, "coop.no-invite");
        }
    }

    void handleDeny(CommandSender sender) {
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

    void handleKick(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "coop.kick-usage");
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        Reason error = plugin.profiles().kick(player, args[1]);
        if (error != null) {
            plugin.messages().send(player, "coop.kick-error", "error", plugin.messages().raw(error));
            return;
        }
        plugin.messages().send(player, "coop.kicked", "player", args[1]);
        if (target != null) {
            plugin.messages().send(target, "coop.you-were-kicked");
        }
    }

    void handleTransfer(CommandSender sender, String[] args) {
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

    void handlePromote(CommandSender sender, String[] args) {
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

    void handleDemote(CommandSender sender, String[] args) {
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

    private void coopRoleCommand(Player actor, String targetName, @Nullable Reason error, String successKey,
                                 String targetKey) {
        if (error != null) {
            plugin.messages().send(actor, "coop.role-error", "error", plugin.messages().raw(error));
            return;
        }
        plugin.messages().send(actor, successKey, "player", targetName);
        Player online = plugin.getServer().getPlayerExact(targetName);
        if (online != null && targetKey != null) {
            plugin.messages().send(online, targetKey);
        }
    }

    void handleLeave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        Reason error = plugin.profiles().leave(player);
        if (error != null) {
            plugin.messages().send(player, "coop.leave-error", "error", plugin.messages().raw(error));
        } else {
            plugin.messages().send(player, "coop.left");
        }
    }

    void handleMembers(CommandSender sender) {
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

    void handleManage(CommandSender sender) {
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

    void handleBank(CommandSender sender) {
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

    void handleLevel(CommandSender sender, String[] args) {
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

    void handleTop(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        plugin.gui().open(player, GuiManager.TOP);
    }

    void handlePerks(CommandSender sender) {
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

    void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("royalskyblock.admin")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (action.equals("testworld")) {
            handleTestWorld(sender);
            return;
        }
        if (action.equals("loadtest")) {
            handleLoadTest(sender, args);
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
        if (action.equals("mobspawn")) {
            handleMobSpawnAdmin(sender, args);
            return;
        }
        if (action.equals("split-content")) {
            handleSplitContent(sender, args);
            return;
        }
        if (action.equals("trash")) {
            handleTrashAdmin(sender, args);
            return;
        }
        if (action.equals("orphans")) {
            handleOrphansAdmin(sender, args);
            return;
        }
        if (action.equals("npc-open")) {
            handleNpcOpen(sender, args);
            return;
        }
        plugin.messages().sendLines(sender, "admin.help");
    }

    // admin output: sent as is, without the chat prefix
    private void say(CommandSender sender, String key, String... placeholders) {
        plugin.messages().sendPlain(sender, key, placeholders);
    }

    // /is admin npc-open <player> <command...>: how a hub NPC opens an npc-only-commands entry. The
    // command runs as the player, skipping the typed-command gate but still asking the gamemode gate.
    private void handleNpcOpen(CommandSender sender, String[] args) {
        if (args.length < 4) {
            say(sender, "admin.npc-open.usage");
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[2]);
        if (target == null) {
            say(sender, "admin.npc-open.not-online", "player", args[2]);
            return;
        }
        String command = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
        // next tick: an NPC click can arrive mid-interaction, and the command may open an inventory
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (target.isOnline() && !CommandGateListener.refuse(plugin, target, command)) {
                target.performCommand(command);
            }
        });
    }

    // /is admin trash list|restore. Restores into the player's active profile, which must not already
    // have an island: a profile can't hold two.
    void handleTrashAdmin(CommandSender sender, String[] args) {
        com.mystipixel.royalskyblock.world.IslandTrash trash = plugin.islands().trash();
        String sub = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "list";

        if (sub.equals("list")) {
            var entries = trash.list();
            if (entries.isEmpty()) {
                say(sender, "admin.trash.empty");
                return;
            }
            say(sender, "admin.trash.header", "count", String.valueOf(entries.size()),
                    "days", String.valueOf(trash.retentionDays()));
            var fmt = java.time.format.DateTimeFormatter.ofPattern(plugin.messages().raw("admin.trash.date-format"))
                    .withZone(java.time.ZoneId.systemDefault());
            int shown = 0;
            for (var entry : entries) {
                if (shown++ >= 15) {
                    say(sender, "admin.trash.more", "count", String.valueOf(entries.size() - 15));
                    break;
                }
                say(sender, "admin.trash.line", "size", String.valueOf(entry.size() / 1024),
                        "date", fmt.format(java.time.Instant.ofEpochMilli(entry.modified())), "file", entry.fileName());
            }
            say(sender, "admin.trash.restore-hint");
            return;
        }

        if (sub.equals("restore")) {
            if (args.length < 5) {
                say(sender, "admin.trash.restore-usage");
                return;
            }
            org.bukkit.OfflinePlayer target = knownPlayer(args[4]);
            if (target == null || !target.hasPlayedBefore() && !target.isOnline()) {
                say(sender, "admin.trash.unknown-player", "player", args[4]);
                return;
            }
            UUID profileId = plugin.profiles().getActiveProfileId(target.getUniqueId());
            if (profileId == null) {
                say(sender, "admin.trash.no-profile");
                return;
            }
            if (plugin.islands().getIslandByProfileFresh(profileId) != null) {
                say(sender, "admin.trash.has-island");
                return;
            }
            byte[] data;
            try {
                data = trash.read(args[3]);
            } catch (Exception e) {
                say(sender, "admin.trash.read-failed", "error", String.valueOf(e.getMessage()));
                return;
            }
            plugin.islands().restoreIsland(profileId, data).whenComplete((island, error) -> onMain(() -> {
                if (error != null) {
                    say(sender, "admin.trash.restore-failed", "error", String.valueOf(error.getMessage()));
                } else {
                    say(sender, "admin.trash.restored", "player", args[4], "archive", args[3]);
                }
            }));
            return;
        }
        say(sender, "admin.trash.usage");
    }

    // /is admin orphans [purge]: store worlds no island row references. Purge always archives to the
    // trash first, even with the trash off. Loaded worlds are skipped: a world mid-creation has no row yet.
    void handleOrphansAdmin(CommandSender sender, String[] args) {
        boolean purge = args.length >= 3 && args[2].equalsIgnoreCase("purge");
        String prefix = plugin.conf().getString("world.world-name-prefix", "island_");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            java.util.List<String> names;
            try {
                names = plugin.worlds().listWorldNames();
            } catch (Exception e) {
                onMain(() -> say(sender, "admin.orphans.list-failed", "error", String.valueOf(e.getMessage())));
                return;
            }
            java.util.List<String> orphans = new java.util.ArrayList<>();
            try {
                for (String name : names) {
                    if (!name.startsWith(prefix) || plugin.worlds().isLoaded(name)) {
                        continue;   // another feature's world (gardens share the store), or mid-creation
                    }
                    UUID islandId;
                    try {
                        islandId = UUID.fromString(name.substring(prefix.length()));
                    } catch (IllegalArgumentException notOurs) {
                        continue;
                    }
                    if (hostedSomewhere(islandId)) {
                        continue;   // mid-creation on another server sharing the store: its row comes next
                    }
                    if (plugin.storage().getIsland(islandId) == null) {
                        orphans.add(name);
                    }
                }
            } catch (StorageException e) {
                // One unanswered lookup makes the whole list untrustworthy, and a partial purge on it would
                // archive live islands. Stop before anything moves.
                onMain(() -> say(sender, "admin.orphans.database-failed", "error", String.valueOf(e.getMessage())));
                return;
            }
            if (orphans.isEmpty()) {
                onMain(() -> say(sender, "admin.orphans.none"));
                return;
            }
            if (!purge) {
                onMain(() -> {
                    say(sender, "admin.orphans.header", "count", String.valueOf(orphans.size()));
                    for (int i = 0; i < orphans.size() && i < 15; i++) {
                        say(sender, "admin.orphans.line", "world", orphans.get(i));
                    }
                    if (orphans.size() > 15) {
                        say(sender, "admin.orphans.more", "count", String.valueOf(orphans.size() - 15));
                    }
                    say(sender, "admin.orphans.purge-hint");
                });
                return;
            }
            int moved = 0;
            int failed = 0;
            for (String name : orphans) {
                try {
                    plugin.islands().trash().archive(name, true);
                    plugin.worlds().deleteIsland(name).join();
                    moved++;
                } catch (Exception e) {
                    failed++;
                    plugin.getLogger().warning("Could not purge orphaned world '" + name + "': " + e.getMessage());
                }
            }
            int finalMoved = moved;
            int finalFailed = failed;
            onMain(() -> say(sender, "admin.orphans.purged", "failed", finalFailed > 0
                    ? plugin.messages().raw("admin.orphans.purged-failures", "count", String.valueOf(finalFailed))
                    : "", "count", String.valueOf(finalMoved)));
        });
    }

    // whether a server sharing the database holds the island right now; off the main thread
    private boolean hostedSomewhere(UUID islandId) {
        if (!(plugin.storage() instanceof com.mystipixel.royalskyblock.data.SqlStorage sql)) {
            return false;
        }
        try {
            return sql.hosts().holderOf(islandId) != null;
        } catch (java.sql.SQLException e) {
            throw new StorageException("Could not read the island host table: " + e.getMessage(), e);
        }
    }

    // /is admin split-content [confirm]: preview unless confirm is passed
    void handleSplitContent(CommandSender sender, String[] args) {
        boolean apply = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
        ContentSplitter.Result result = new ContentSplitter(plugin).run(apply);

        say(sender, apply ? "admin.split-content.header-applied" : "admin.split-content.header-preview");
        for (String note : result.notes()) {
            say(sender, "admin.split-content.note", "note", note);
        }
        for (String file : result.written()) {
            say(sender, apply ? "admin.split-content.wrote" : "admin.split-content.would-write", "file", file);
        }
        for (String file : result.skipped()) {
            say(sender, "admin.split-content.skipped", "file", file);
        }
        if (result.isEmpty()) {
            say(sender, "admin.split-content.nothing");
            return;
        }
        say(sender, apply ? "admin.split-content.applied" : "admin.split-content.preview-hint");
    }

    void handleBorderAdmin(CommandSender sender, String[] args) {
        if (args.length < 3) {
            say(sender, "admin.border.current", "color", plugin.borders().color().name().toLowerCase(Locale.ROOT));
            return;
        }
        String choice = args[2].toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("blue", "red", "green", "off").contains(choice)) {
            say(sender, "admin.border.invalid");
            return;
        }
        // Through the plugin, not conf().set() + saveConfig(): conf() is a converted copy of eco's config,
        // so a write there is dropped on the next read.
        plugin.setConfigValue("island.border.color", choice);
        plugin.borders().reload();
        plugin.borders().refreshAll();
        say(sender, "admin.border.set", "color", choice);
    }

    void handleMobSpawnAdmin(CommandSender sender, String[] args) {
        com.mystipixel.royalskyblock.island.IslandMobSpawnService svc = plugin.mobSpawns();
        if (svc == null) {
            say(sender, "admin.mobspawn.not-running");
            return;
        }
        String sub = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "status";
        if (sub.equals("test")) {
            if (!(sender instanceof Player player)) {
                say(sender, "admin.mobspawn.players-only");
                return;
            }
            if (args.length < 4) {
                say(sender, "admin.mobspawn.test-usage");
                return;
            }
            int level = 1;
            if (args.length >= 5) {
                try {
                    level = Integer.parseInt(args[4]);
                } catch (NumberFormatException ignored) {
                    // keep default
                }
            }
            say(sender, "admin.mobspawn.test-result", "result", svc.testSpawn(player, args[3], level));
            return;
        }
        say(sender, "admin.mobspawn.status",
                "availability", plugin.messages().raw(svc.providerAvailable()
                        ? "admin.mobspawn.available" : "admin.mobspawn.unavailable"),
                "families", String.valueOf(svc.familyCount()),
                "enabled", String.valueOf(svc.enabled()),
                "provider", svc.providerId());
        if (sender instanceof Player viewer) {
            say(sender, "admin.mobspawn.intimidation", "summary", plugin.intimidationSummary(viewer));
        }
        say(sender, "admin.mobspawn.test-hint");
    }

    void handleAdminStatus(CommandSender sender) {
        String worldSrc = plugin.conf().getString("world.slime-data-source", "file");
        String storage = plugin.conf().getString("storage.type", "sqlite").toUpperCase(Locale.ROOT);
        String levelCount = String.valueOf(plugin.bank().levels().levelCount());
        say(sender, "admin.status.header");
        dep(sender, "islands", plugin.isWorldBackendReady(), "source", worldSrc);
        dep(sender, "economy", plugin.economyReady());
        dep(sender, "bank", plugin.bank().available(), "count", levelCount);
        dep(sender, "schematics", plugin.schematics().isAvailable());
        dep(sender, "progression", plugin.eco().isPresent());
        say(sender, "admin.status.storage", "storage", storage);
        if (plugin.perks().enabled()) {
            say(sender, "admin.status.perks-on", "count", String.valueOf(plugin.perks().perkCount()));
        } else {
            say(sender, "admin.status.perks-off");
        }
        say(sender, "admin.status.border", "color", plugin.borders().color().name().toLowerCase(Locale.ROOT));
        say(sender, "admin.status.config-health");
        String spawnWorld = plugin.conf().getString("spawn.world", "world");
        boolean spawnOk = plugin.getServer().getWorld(spawnWorld) != null;
        check(sender, spawnOk, "spawn-ok", "spawn-missing", "world", spawnWorld);
        int upTracks = plugin.upgrades().all().size();
        check(sender, upTracks > 0, "upgrades-ok", "upgrades-missing", "count", String.valueOf(upTracks));
        check(sender, !plugin.bank().levels().isEmpty(), "bank-levels-ok", "bank-levels-missing", "count", levelCount);
    }

    // One dependency line: admin.status.<id> is its name, <id>-ready / <id>-missing the detail (an
    // empty ready detail shows plain "ready").
    private void dep(CommandSender sender, String id, boolean ok, String... placeholders) {
        String name = plugin.messages().raw("admin.status." + id);
        if (!ok) {
            say(sender, "admin.status.missing", "detail",
                    plugin.messages().raw("admin.status." + id + "-missing", placeholders), "name", name);
            return;
        }
        String detail = plugin.messages().raw("admin.status." + id + "-ready", placeholders);
        if (detail.isEmpty()) {
            say(sender, "admin.status.ready", "name", name);
        } else {
            say(sender, "admin.status.ready-detail", "detail", detail, "name", name);
        }
    }

    private void check(CommandSender sender, boolean ok, String okKey, String failKey, String... placeholders) {
        String message = plugin.messages().raw("admin.status." + (ok ? okKey : failKey), placeholders);
        say(sender, ok ? "admin.status.check-ok" : "admin.status.check-failed", "message", message);
    }

    void handleSchematic(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (args.length < 4 || !args[2].equalsIgnoreCase("save")) {
            say(sender, "admin.schematic.usage");
            return;
        }
        if (!plugin.schematics().isAvailable()) {
            say(sender, "admin.schematic.unavailable");
            return;
        }
        String name = args[3];
        Reason error = plugin.schematics().saveSelection(player, name);
        if (error == null) {
            say(sender, "admin.schematic.saved", "name", name);
        } else {
            say(sender, "admin.schematic.failed", "error", plugin.messages().raw(error));
        }
    }

    // diagnostic: does a starter chest lose its items through save/unload/reload (placement vs ASP persistence)
    void handleChestTest(CommandSender sender) {
        if (!plugin.isWorldBackendReady()) {
            say(sender, "admin.chesttest.not-ready");
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
                    say(sender, "admin.chesttest.placed", "count", String.valueOf(chestCount(world)));
                    return world;
                }))
                .thenCompose(w -> plugin.worlds().unloadIsland(name, true))
                .thenCompose(v -> plugin.worlds().loadIsland(name))
                .thenCompose(world -> onMainSupply(() -> {
                    say(sender, "admin.chesttest.reloaded", "count", String.valueOf(chestCount(world)));
                    return world;
                }))
                .thenCompose(w -> plugin.worlds().deleteIsland(name))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        onMain(() -> say(sender, "admin.chesttest.failed", "error", rootMessage(error)));
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

    void handleUpgradeAdmin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (args.length < 4) {
            say(sender, "admin.upgrade.usage");
            return;
        }
        com.mystipixel.royalskyblock.upgrade.UpgradeDef def = plugin.upgrades().get(args[2]);
        if (def == null) {
            say(sender, "admin.upgrade.unknown", "key", args[2]);
            return;
        }
        int tier;
        try {
            tier = Math.max(0, Math.min(def.maxTier(), Integer.parseInt(args[3])));
        } catch (NumberFormatException e) {
            say(sender, "admin.upgrade.bad-tier", "max", String.valueOf(def.maxTier()));
            return;
        }
        com.mystipixel.royalskyblock.island.Island island = islandOf(player);
        if (island == null) {
            plugin.messages().send(player, "home.no-island");
            return;
        }
        plugin.upgrades().setTier(island, def, tier);
        say(sender, "admin.upgrade.set", "tier", String.valueOf(tier),
                "value", String.valueOf(def.valueAt(tier)), "key", def.key());
    }

    private com.mystipixel.royalskyblock.island.@org.jetbrains.annotations.Nullable Island islandOf(Player player) {
        java.util.UUID active = plugin.profiles().getActiveProfileId(player.getUniqueId());
        return active == null ? null : plugin.islands().getIslandByProfile(active);
    }

    // also to the console: the runner is usually a player, and their chat isn't logged
    private void loadTestReport(CommandSender sender, String key, String... placeholders) {
        String msg = plugin.messages().raw(key, placeholders);
        say(sender, "admin.loadtest.report", "message", msg);
        plugin.getLogger().info("[loadtest] " + msg);
    }

    // /is admin loadtest <count> [holdSeconds]: creates, holds and deletes N throwaway worlds
    // (rsb_loadtest_<n>) to measure island load time and heap cost. Doesn't cover the catch-up scan or
    // player presence. Every world is deleted at the end, even on partial failure.
    void handleLoadTest(CommandSender sender, String[] args) {
        if (!plugin.isWorldBackendReady()) {
            say(sender, "admin.backend-not-ready");
            return;
        }
        int count;
        try {
            count = Integer.parseInt(args.length >= 3 ? args[2] : "10");
        } catch (NumberFormatException e) {
            say(sender, "admin.loadtest.usage");
            return;
        }
        if (count < 1 || count > 64) {
            say(sender, "admin.loadtest.bad-count");
            return;
        }
        long holdSeconds = 3;
        if (args.length >= 4) {
            try {
                holdSeconds = Math.max(0, Math.min(60, Long.parseLong(args[3])));
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        runLoadTest(sender, count, holdSeconds);
    }

    private void runLoadTest(CommandSender sender, int count, long holdSeconds) {
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long heapBefore = rt.totalMemory() - rt.freeMemory();
        long batchStart = System.currentTimeMillis();

        say(sender, "admin.loadtest.starting", "count", String.valueOf(count));

        List<String> names = new ArrayList<>();
        List<java.util.concurrent.CompletableFuture<Void>> loads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = "rsb_loadtest_" + i;
            names.add(name);
            loads.add(plugin.worlds().createIsland(name).thenAccept(w -> { }));
        }

        java.util.concurrent.CompletableFuture.allOf(loads.toArray(new java.util.concurrent.CompletableFuture[0]))
                .whenComplete((ignored, error) -> onMain(() -> {
                    long loadMs = System.currentTimeMillis() - batchStart;
                    System.gc();
                    long heapAfter = rt.totalMemory() - rt.freeMemory();
                    long perIsland = (heapAfter - heapBefore) / count;

                    int loaded = 0;
                    for (String n : names) {
                        if (plugin.worlds().isLoaded(n)) {
                            loaded++;
                        }
                    }
                    loadTestReport(sender, "admin.loadtest.loaded", "loaded", String.valueOf(loaded),
                            "count", String.valueOf(count), "ms", String.valueOf(loadMs),
                            "average", String.valueOf(loadMs / Math.max(1, count)));
                    loadTestReport(sender, "admin.loadtest.heap", "total", human(heapAfter - heapBefore),
                            "per_island", human(perIsland));
                    if (error != null) {
                        say(sender, "admin.loadtest.some-failed", "error", rootMessage(error));
                    }

                    long hold = holdSeconds * 20L;
                    say(sender, "admin.loadtest.holding", "seconds", String.valueOf(holdSeconds));
                    plugin.getServer().getScheduler().runTaskLater(plugin,
                            () -> teardownLoadTest(sender, names, batchStart), hold);
                }));
    }

    private void teardownLoadTest(CommandSender sender, List<String> names, long batchStart) {
        long start = System.currentTimeMillis();
        List<java.util.concurrent.CompletableFuture<Void>> deletes = new ArrayList<>();
        for (String n : names) {
            deletes.add(plugin.worlds().deleteIsland(n));
        }
        java.util.concurrent.CompletableFuture.allOf(deletes.toArray(new java.util.concurrent.CompletableFuture[0]))
                .whenComplete((ignored, error) -> onMain(() -> {
                    long teardownMs = System.currentTimeMillis() - start;
                    long totalMs = System.currentTimeMillis() - batchStart;
                    // confirm nothing leaked: a load test that orphans worlds is worse than none
                    int leaked = 0;
                    for (String n : names) {
                        if (plugin.worlds().isLoaded(n)) {
                            leaked++;
                        }
                    }
                    loadTestReport(sender, "admin.loadtest.unloaded", "count", String.valueOf(names.size()),
                            "ms", String.valueOf(teardownMs), "leaked", String.valueOf(leaked));
                    loadTestReport(sender, "admin.loadtest.done",
                            "result", plugin.messages().raw(leaked == 0 ? "admin.loadtest.done-clean" : "admin.loadtest.done-leaked"),
                            "count", String.valueOf(names.size()), "total", String.valueOf(totalMs),
                            "ms", String.valueOf(teardownMs), "leaked", String.valueOf(leaked));
                }));
    }

    private static String human(long bytes) {
        if (Math.abs(bytes) < 1024) {
            return bytes + "B";
        }
        double kb = bytes / 1024.0;
        if (Math.abs(kb) < 1024) {
            return String.format("%.1fKB", kb);
        }
        return String.format("%.1fMB", kb / 1024.0);
    }

    void handleTestWorld(CommandSender sender) {
        if (!plugin.isWorldBackendReady()) {
            say(sender, "admin.backend-not-ready");
            return;
        }
        String name = "rsb_diagnostic";
        say(sender, "admin.testworld.creating", "world", name);
        plugin.worlds().createIsland(name)
                .thenCompose(world -> {
                    onMain(() -> say(sender, "admin.testworld.created", "world", world.getName()));
                    return plugin.worlds().deleteIsland(name);
                })
                .whenComplete((ignored, error) -> onMain(() -> {
                    if (error != null) {
                        say(sender, "admin.testworld.failed", "error", rootMessage(error));
                    } else {
                        say(sender, "admin.testworld.deleted", "world", name);
                    }
                }));
    }

    void sendHelp(CommandSender sender) {
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

    // the deepest cause's message, or the player-facing text of a ReasonException or of an island hosted on
    // another server
    private String rootMessage(Throwable t) {
        IslandHostedElsewhereException hosted = IslandHostedElsewhereException.find(t);
        if (hosted != null) {
            return plugin.messages().raw("island.hosted-elsewhere", "server", hosted.holder());
        }
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ReasonException reason) {
            return plugin.messages().raw(reason.reason());
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
