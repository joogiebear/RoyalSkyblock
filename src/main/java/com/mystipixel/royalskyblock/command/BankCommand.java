package com.mystipixel.royalskyblock.command;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.gui.GuiManager;
import com.mystipixel.royalskyblock.profile.Gamemode;
import com.mystipixel.royalskyblock.profile.Profile;
import com.willfp.eco.core.command.impl.PluginCommand;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * {@code /bank}: on a Coop profile it opens the bank hub (personal + coop); otherwise the personal
 * bank. Declared players-only, so eco turns the console away with {@code messages.not-player}.
 */
public final class BankCommand extends PluginCommand {

    public BankCommand(RoyalSkyblockPlugin plugin) {
        // Not "": eco checks the string as given, and Bukkit treats an unknown permission as op-only.
        super(plugin, "bank", "royalskyblock.bank", true);
    }

    @Override
    public void onExecute(@NotNull Player player, @NotNull List<String> args) {
        RoyalSkyblockPlugin plugin = (RoyalSkyblockPlugin) getPlugin();
        Profile active = plugin.profiles().getActiveProfile(player);
        if (active != null && active.gamemode() == Gamemode.COOP) {
            plugin.gui().open(player, GuiManager.BANK_HUB);
        } else {
            plugin.gui().open(player, GuiManager.BANK_PERSONAL);
        }
    }

    @Override
    public @NotNull String getDescription() {
        return "Open your bank (personal + coop).";
    }
}
