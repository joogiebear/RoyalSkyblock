package com.mystipixel.royalskyblock.command;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
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

/**
 * The {@code /island} command tree. Phase 1 wires create / home / delete / reload; the remaining
 * subcommands (visit, invite, upgrade, settings, ...) land with their systems in later phases.
 */
public final class IslandCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT_SUBS = List.of(
            "create", "home", "go", "visit", "invite", "kick", "leave",
            "sethome", "setwarp", "level", "top", "upgrade", "settings", "delete", "reload", "admin");

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

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> handleReload(sender);
            case "create" -> handleCreate(sender);
            case "home", "go" -> handleHome(sender);
            case "visit" -> handleVisit(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "admin" -> handleAdmin(sender, args);
            default -> sender.sendMessage(Text.color("&e/is " + sub + " &7isn't wired up yet — coming soon."));
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("royalskyblock.admin")) {
            sender.sendMessage(Text.color("&cYou don't have permission to do that."));
            return;
        }
        plugin.reload();
        sender.sendMessage(Text.color("&aRoyalSkyblock configuration reloaded."));
    }

    private void handleCreate(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.color("&cOnly players can create an island."));
            return;
        }
        if (!player.hasPermission("royalskyblock.create")) {
            player.sendMessage(Text.color("&cYou don't have permission to create an island."));
            return;
        }
        if (!plugin.isWorldBackendReady()) {
            player.sendMessage(Text.color("&cIsland worlds are unavailable — the server isn't running Advanced Slime Paper yet."));
            return;
        }
        if (plugin.islands().hasIsland(player.getUniqueId())) {
            player.sendMessage(Text.color("&cYou already have an island. Use &e/is home&c."));
            return;
        }

        player.sendMessage(Text.color("&7Creating your island..."));
        plugin.islands().createIsland(player).whenComplete((island, error) ->
                onMain(() -> {
                    if (error != null) {
                        player.sendMessage(Text.color("&cCouldn't create your island: " + rootMessage(error)));
                    } else {
                        player.sendMessage(Text.color("&aYour island is ready! Welcome home."));
                    }
                }));
    }

    private void handleHome(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.color("&cOnly players have an island home."));
            return;
        }
        plugin.islands().goHome(player).whenComplete((ok, error) ->
                onMain(() -> {
                    if (error != null) {
                        player.sendMessage(Text.color("&cCouldn't teleport you home: " + rootMessage(error)));
                    } else if (!Boolean.TRUE.equals(ok)) {
                        player.sendMessage(Text.color("&cYou don't have an island yet. Use &e/is create&c."));
                    }
                }));
    }

    private void handleVisit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.color("&cOnly players can visit an island."));
            return;
        }
        if (!player.hasPermission("royalskyblock.visit")) {
            player.sendMessage(Text.color("&cYou don't have permission to visit islands."));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Text.color("&cUsage: &e/is visit <player>"));
            return;
        }
        String targetName = args[1];
        java.util.UUID targetId = plugin.getServer().getOfflinePlayer(targetName).getUniqueId();
        if (targetId.equals(player.getUniqueId())) {
            handleHome(player);
            return;
        }
        plugin.islands().visit(player, targetId).whenComplete((ok, error) ->
                onMain(() -> {
                    if (error != null) {
                        player.sendMessage(Text.color("&cCouldn't take you there: " + rootMessage(error)));
                    } else if (!Boolean.TRUE.equals(ok)) {
                        player.sendMessage(Text.color("&c" + targetName + " doesn't have an island."));
                    } else {
                        player.sendMessage(Text.color("&aVisiting &e" + targetName + "&a's island."));
                    }
                }));
    }

    private void handleDelete(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.color("&cOnly players can delete an island."));
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            player.sendMessage(Text.color("&c&lWarning: &7this permanently deletes your island and everything on it."));
            player.sendMessage(Text.color("&7Type &e/is delete confirm &7to proceed."));
            return;
        }
        player.sendMessage(Text.color("&7Deleting your island..."));
        plugin.islands().deleteOwnIsland(player).whenComplete((ok, error) ->
                onMain(() -> {
                    if (error != null) {
                        player.sendMessage(Text.color("&cCouldn't delete your island: " + rootMessage(error)));
                    } else if (!Boolean.TRUE.equals(ok)) {
                        player.sendMessage(Text.color("&cYou don't own an island to delete."));
                    } else {
                        player.sendMessage(Text.color("&aYour island has been deleted."));
                    }
                }));
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("royalskyblock.admin")) {
            sender.sendMessage(Text.color("&cYou don't have permission to do that."));
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (action.equals("testworld")) {
            handleTestWorld(sender);
            return;
        }
        if (action.equals("ecoslot")) {
            handleEcoSlot(sender, args);
            return;
        }
        sender.sendMessage(Text.color("&8» &e/is admin testworld &7— run an ASP world round-trip diagnostic"));
        sender.sendMessage(Text.color("&8» &e/is admin ecoslot <1|2> &7— SPIKE: swap live eco data between two profile slots"));
    }

    /**
     * Diagnostic: create, load, persist and delete a throwaway slime world to prove the ASP backend
     * works end-to-end. Console-runnable (no player needed), so it verifies island world plumbing
     * before anyone logs in.
     */
    private void handleTestWorld(CommandSender sender) {
        if (!plugin.isWorldBackendReady()) {
            sender.sendMessage(Text.color("&cWorld backend not ready — is the server running Advanced Slime Paper?"));
            return;
        }
        String name = "rsb_diagnostic";
        sender.sendMessage(Text.color("&7[diag] creating + loading throwaway slime world '" + name + "'..."));
        plugin.worlds().createIsland(name)
                .thenCompose(world -> {
                    onMain(() -> sender.sendMessage(Text.color(
                            "&a[diag] created & loaded &f" + world.getName()
                                    + " &7(env=" + world.getEnvironment() + ", spawn=" + world.getSpawnLocation().toVector() + ")")));
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

    /**
     * SPIKE: prove per-profile eco progression. Swaps the player's live eco data between two shadow
     * "profile" slots via {@link com.mystipixel.royalskyblock.hooks.EcoProfileBridge}. Gain XP on
     * slot 1, swap to 2 (fresh), swap back to 1 (restored) → per-profile progression is proven.
     */
    private void handleEcoSlot(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.color("&cPlayers only."));
            return;
        }
        if (plugin.eco() == null || !plugin.eco().isPresent()) {
            sender.sendMessage(Text.color("&ceco isn't installed — nothing to swap."));
            return;
        }
        int target;
        try {
            target = Integer.parseInt(args[2]);
        } catch (Exception e) {
            sender.sendMessage(Text.color("&cUsage: &e/is admin ecoslot <1|2>"));
            return;
        }
        int current = plugin.ecoSlot(player.getUniqueId());
        if (current == target) {
            player.sendMessage(Text.color("&7You're already on eco slot &e" + target + "&7."));
            return;
        }
        java.util.UUID fromSlot = slotProfileId(current);
        java.util.UUID toSlot = slotProfileId(target);
        plugin.eco().swap(player.getUniqueId(), fromSlot, toSlot);
        plugin.setEcoSlot(player.getUniqueId(), target);
        player.sendMessage(Text.color("&aSwapped eco data: slot &e" + current + " &a→ &e" + target
                + "&a. Check your skills/pets/collections — they should reflect slot " + target + "."));
    }

    /** Deterministic pseudo-"profile" id for a spike slot number. */
    private static java.util.UUID slotProfileId(int slot) {
        return java.util.UUID.nameUUIDFromBytes(("rsb-eco-slot:" + slot).getBytes());
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Text.color("&6&lRoyalSkyblock"));
        sender.sendMessage(Text.color("&7Scalable per-island Skyblock."));
        sender.sendMessage(Text.color("&8» &e/is create &7— start your island"));
        sender.sendMessage(Text.color("&8» &e/is home &7— go to your island"));
        sender.sendMessage(Text.color("&8» &e/is visit <player> &7— visit an island"));
        sender.sendMessage(Text.color("&8» &e/is delete confirm &7— delete your island"));
        if (sender.hasPermission("royalskyblock.admin")) {
            sender.sendMessage(Text.color("&8» &e/is reload &7— reload config"));
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
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String s : ROOT_SUBS) {
                if ((s.equals("reload") || s.equals("admin")) && !sender.hasPermission("royalskyblock.admin")) {
                    continue;
                }
                if (s.startsWith(prefix)) {
                    out.add(s);
                }
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
            return List.of("confirm");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("visit")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    names.add(online.getName());
                }
            }
            return names;
        }
        return List.of();
    }
}
