package com.mystipixel.royalskyblock.currency;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.hooks.VaultHook;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the config-driven {@code currencies:} section and handles affordability checks + charging.
 * Two currency kinds:
 * <ul>
 *   <li><b>vault</b> — money via the Vault economy (check + charge natively).</li>
 *   <li><b>command</b> — universal: read the balance from a PlaceholderAPI placeholder for the check,
 *       and run a console command to charge. Works with EcoBits gems or any currency plugin.</li>
 * </ul>
 */
public final class CurrencyService {

    private enum Kind { VAULT, COMMAND }

    private record CurrencyDef(Kind kind, String displayName, String balancePlaceholder,
                               String takeCommand, String giveCommand) {
    }

    private final RoyalSkyblockPlugin plugin;
    private final Map<String, CurrencyDef> currencies = new HashMap<>();
    private VaultHook vault;

    public CurrencyService(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.vault = resolveVault();
        reload();
    }

    public void reload() {
        currencies.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("currencies");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection c = section.getConfigurationSection(key);
            if (c == null) {
                continue;
            }
            Kind kind = "command".equalsIgnoreCase(c.getString("type", "vault")) ? Kind.COMMAND : Kind.VAULT;
            currencies.put(key.toLowerCase(Locale.ROOT), new CurrencyDef(kind,
                    c.getString("display-name", key),
                    c.getString("balance-placeholder", ""),
                    c.getString("take-command", ""),
                    c.getString("give-command", "")));
        }
    }

    /** Whether the player can afford {@code cost}. Free costs and unknown currencies pass/deny sensibly. */
    public boolean canAfford(Player player, Cost cost) {
        if (cost.isFree()) {
            return true;
        }
        CurrencyDef def = currencies.get(cost.currency().toLowerCase(Locale.ROOT));
        if (def == null) {
            plugin.getLogger().warning("Unknown currency '" + cost.currency() + "' — treating as unaffordable.");
            return false;
        }
        if (def.kind() == Kind.VAULT) {
            return vault != null && vault.has(player, cost.amount());
        }
        // command currency: check via placeholder
        String placeholder = def.balancePlaceholder();
        if (placeholder == null || placeholder.isBlank()) {
            return true; // no check configured — trust the charge command
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            plugin.getLogger().warning("Currency '" + cost.currency() + "' needs PlaceholderAPI for its balance check.");
            return false;
        }
        return parseAmount(me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, placeholder)) >= cost.amount();
    }

    /** Charge the player. Assumes {@link #canAfford} already passed. Returns false only on a hard failure. */
    public boolean charge(Player player, Cost cost) {
        if (cost.isFree()) {
            return true;
        }
        CurrencyDef def = currencies.get(cost.currency().toLowerCase(Locale.ROOT));
        if (def == null) {
            return false;
        }
        if (def.kind() == Kind.VAULT) {
            return vault != null && vault.withdraw(player, cost.amount());
        }
        if (def.takeCommand() == null || def.takeCommand().isBlank()) {
            return false;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), fill(def.takeCommand(), player, cost.amount()));
        return true;
    }

    /** Refund the player (used if a purchase can't complete). Best-effort. */
    public void refund(Player player, Cost cost) {
        if (cost.isFree()) {
            return;
        }
        CurrencyDef def = currencies.get(cost.currency().toLowerCase(Locale.ROOT));
        if (def == null) {
            return;
        }
        if (def.kind() == Kind.VAULT) {
            if (vault != null) {
                vault.deposit(player, cost.amount());
            }
        } else if (def.giveCommand() != null && !def.giveCommand().isBlank()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), fill(def.giveCommand(), player, cost.amount()));
        }
    }

    /** Human-readable, e.g. {@code "175 coins"}. */
    public String format(Cost cost) {
        CurrencyDef def = currencies.get(cost.currency().toLowerCase(Locale.ROOT));
        String name = def != null ? def.displayName() : cost.currency();
        return trimAmount(cost.amount()) + " " + name;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private String fill(String command, Player player, double amount) {
        return command.replace("%player%", player.getName()).replace("%amount%", trimAmount(amount));
    }

    private static String trimAmount(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.valueOf(amount);
    }

    private static double parseAmount(String raw) {
        if (raw == null) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (char ch : raw.toCharArray()) {
            if (Character.isDigit(ch) || ch == '.' || ch == '-') {
                digits.append(ch);
            }
        }
        try {
            return digits.length() == 0 ? 0 : Double.parseDouble(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private VaultHook resolveVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return null;
        }
        try {
            Class.forName("net.milkbowl.vault.economy.Economy");
            VaultHook hook = new VaultHook();
            return hook.isReady() ? hook : null;
        } catch (Throwable notVault) {
            return null;
        }
    }
}
