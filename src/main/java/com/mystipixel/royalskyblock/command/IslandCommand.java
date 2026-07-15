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
import java.util.UUID;

/**
 * The {@code /island} command tree. Player-facing text comes from {@code messages.yml} via
 * {@link com.mystipixel.royalskyblock.message.MessageManager}; only temporary spike/diagnostic
 * strings ({@code admin testworld/ecoslot}) are inline.
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
        if (plugin.islands().hasIsland(player.getUniqueId())) {
            plugin.messages().send(player, "island.already-have");
            return;
        }

        plugin.messages().send(player, "island.creating");
        plugin.islands().createIsland(player).whenComplete((island, error) ->
                onMain(() -> {
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
        plugin.islands().goHome(player).whenComplete((ok, error) ->
                onMain(() -> {
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
        plugin.islands().visit(player, targetId).whenComplete((ok, error) ->
                onMain(() -> {
                    if (error != null) {
                        plugin.messages().send(player, "visit.failed", "error", rootMessage(error));
                    } else if (!Boolean.TRUE.equals(ok)) {
                        plugin.messages().send(player, "visit.no-target-island", "player", targetName);
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
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            plugin.messages().sendPlain(player, "delete.warning");
            plugin.messages().sendPlain(player, "delete.confirm-hint");
            return;
        }
        plugin.messages().send(player, "delete.deleting");
        plugin.islands().deleteOwnIsland(player).whenComplete((ok, error) ->
                onMain(() -> {
                    if (error != null) {
                        plugin.messages().send(player, "delete.failed", "error", rootMessage(error));
                    } else if (!Boolean.TRUE.equals(ok)) {
                        plugin.messages().send(player, "delete.not-owner");
                    } else {
                        plugin.messages().send(player, "delete.deleted");
                    }
                }));
    }

    // ── admin / spike diagnostics (temporary, inline strings) ────────────────────

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
        if (action.equals("ecoslot")) {
            handleEcoSlot(sender, args);
            return;
        }
        sender.sendMessage(Text.color("&8» &e/is admin testworld &7— run an ASP world round-trip diagnostic"));
        sender.sendMessage(Text.color("&8» &e/is admin ecoslot <1|2> &7— SPIKE: swap live eco data between two profile slots"));
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
        plugin.eco().swap(player.getUniqueId(), slotProfileId(current), slotProfileId(target));
        plugin.setEcoSlot(player.getUniqueId(), target);
        player.sendMessage(Text.color("&aSwapped eco data: slot &e" + current + " &a→ &e" + target
                + "&a. Check your skills/pets/collections — they should reflect slot " + target + "."));
    }

    private static UUID slotProfileId(int slot) {
        return UUID.nameUUIDFromBytes(("rsb-eco-slot:" + slot).getBytes());
    }

    // ── help / tab-complete ──────────────────────────────────────────────────────

    private void sendHelp(CommandSender sender) {
        plugin.messages().sendPlain(sender, "help.header");
        plugin.messages().sendPlain(sender, "help.subtitle");
        plugin.messages().sendPlain(sender, "help.create");
        plugin.messages().sendPlain(sender, "help.home");
        plugin.messages().sendPlain(sender, "help.visit");
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
