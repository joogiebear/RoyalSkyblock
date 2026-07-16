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
            "menu", "create", "home", "go", "visit", "profile", "sethome", "setwarp",
            "level", "top", "upgrade", "settings", "delete", "reload", "admin");

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
            plugin.messages().send(player, "visit.usage");
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
        plugin.islands().teleportToIsland(player, island).whenComplete((ok, error) -> onMain(() -> {
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
        sender.sendMessage(Text.color("&8» &e/is admin testworld &7— ASP world round-trip diagnostic"));
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
        plugin.messages().sendPlain(sender, "help.menu");
        plugin.messages().sendPlain(sender, "help.create");
        plugin.messages().sendPlain(sender, "help.home");
        plugin.messages().sendPlain(sender, "help.visit");
        plugin.messages().sendPlain(sender, "help.profile");
        plugin.messages().sendPlain(sender, "help.delete");
        if (sender.hasPermission("royalskyblock.admin")) {
            plugin.messages().sendPlain(sender, "help.reload");
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
        if (args.length == 2 && sub.equals("visit")) {
            List<String> names = new ArrayList<>();
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                names.add(online.getName());
            }
            return filter(names, args[1], sender);
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
