package com.mystipixel.royalskyblock.command;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.gui.GuiManager;
import com.mystipixel.royalskyblock.profile.Gamemode;
import com.mystipixel.royalskyblock.profile.Profile;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /bank} — RoyalSkyblock owns the single bank command. On a Coop profile it opens the bank hub
 * (personal + coop); otherwise it opens the personal bank directly.
 */
public final class BankCommand implements CommandExecutor {

    private final RoyalSkyblockPlugin plugin;

    public BankCommand(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return true;
        }
        Profile active = plugin.profiles().getActiveProfile(player);
        if (active != null && active.gamemode() == Gamemode.COOP) {
            plugin.gui().open(player, GuiManager.BANK_HUB);
        } else {
            plugin.gui().open(player, GuiManager.BANK_PERSONAL);
        }
        return true;
    }
}
