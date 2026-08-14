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
 * {@code /bank} — RoyalSkyblock owns the single bank command. On a Coop profile it opens the bank hub
 * (personal + coop); otherwise it opens the personal bank directly.
 *
 * <p>An eco {@link PluginCommand} like the rest of the suite. Being declared players-only means eco
 * turns away the console with {@code messages.not-player} from this plugin's own lang.yml, which is
 * why the handler no longer checks for a {@link Player} itself.
 */
public final class BankCommand extends PluginCommand {

    public BankCommand(RoyalSkyblockPlugin plugin) {
        super(plugin, "bank", "", true);
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
