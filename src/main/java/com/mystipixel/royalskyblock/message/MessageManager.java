package com.mystipixel.royalskyblock.message;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;

/**
 * Loads every player-facing string from eco's {@code lang.yml}, with {@code %token%} placeholders.
 * Keys sit under {@code messages:}; callers pass the short key ({@code island.created}). A legacy
 * messages.yml is migrated on first start ({@link #migrateLegacyMessages()}).
 *
 * <p>{@link #send} prepends the configured prefix (chat feedback); {@link #sendPlain} does not
 * (headers, multi-line help, warnings).
 */
public final class MessageManager {

    // every RoyalSkyblock string is nested under this root of eco's lang file
    private static final String ROOT = "messages.";

    private static final String LEGACY_FILE = "messages.yml";

    private final RoyalSkyblockPlugin plugin;

    public MessageManager(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
        migrateLegacyMessages();
    }

    /** No-op: eco reloads lang.yml itself before handleReload() runs. */
    public void reload() {
    }

    // One-time migration from messages.yml: copies every value into lang.yml under messages. (keeping
    // customised text) and renames the old file to messages.yml.migrated.
    private void migrateLegacyMessages() {
        File legacy = new File(plugin.getDataFolder(), LEGACY_FILE);
        if (!legacy.isFile()) {
            return;
        }
        YamlConfiguration old = YamlConfiguration.loadConfiguration(legacy);
        int moved = 0;
        for (String key : old.getKeys(true)) {
            if (old.isConfigurationSection(key)) {
                continue;                        // only leaves carry text
            }
            Object value = old.get(key);
            if (value != null) {
                plugin.getLangYml().set(ROOT + key, value);
                moved++;
            }
        }
        try {
            plugin.getLangYml().save();
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save lang.yml while migrating messages.yml: " + e.getMessage());
            return;                              // leave messages.yml in place so the next start retries
        }
        File migrated = new File(plugin.getDataFolder(), LEGACY_FILE + ".migrated");
        if (!legacy.renameTo(migrated)) {
            plugin.getLogger().warning("Migrated messages.yml into lang.yml but could not rename the old "
                    + "file; delete " + LEGACY_FILE + " by hand or it will migrate again next start.");
            return;
        }
        plugin.getLogger().info("Migrated " + moved + " message(s) from messages.yml into lang.yml. "
                + "The old file is kept as " + LEGACY_FILE + ".migrated.");
    }

    public String prefix() {
        String prefix = plugin.getLangYml().getStringOrNull(ROOT + "prefix");
        return prefix != null ? prefix : "&6&lRoyalSkyblock &8» &r";
    }

    /** The raw string for a key (with {@code %token%} substitution), or a visible missing marker. */
    public String raw(String key, String... placeholders) {
        String value = plugin.getLangYml().getStringOrNull(ROOT + key);
        return apply(value != null ? value : "&c<missing:" + key + ">", placeholders);
    }

    /** Send with the prefix. {@code placeholders} are token/value pairs: {@code "player", name}. */
    public void send(CommandSender sender, String key, String... placeholders) {
        sender.sendMessage(Text.color(prefix() + raw(key, placeholders)));
    }

    /** Send without the prefix: for headers, help lines, and multi-line blocks. */
    public void sendPlain(CommandSender sender, String key, String... placeholders) {
        sender.sendMessage(Text.color(raw(key, placeholders)));
    }

    /**
     * Send a coop invite with clickable {@code [Accept]}/{@code [Deny]} buttons, labelled from
     * {@code coop.invite-accept-button} / {@code -deny-button}.
     */
    public void sendInvite(Player target, String inviterName) {
        Component base = Text.color(prefix() + raw("coop.invite-received", "player", inviterName));
        Component accept = Text.color(raw("coop.invite-accept-button"))
                .clickEvent(ClickEvent.runCommand("/is accept"))
                .hoverEvent(HoverEvent.showText(Text.color("&aClick to accept")));
        Component deny = Text.color(raw("coop.invite-deny-button"))
                .clickEvent(ClickEvent.runCommand("/is deny"))
                .hoverEvent(HoverEvent.showText(Text.color("&cClick to decline")));
        target.sendMessage(base.append(Component.space()).append(accept)
                .append(Component.space()).append(deny));
    }

    private String apply(String value, String... placeholders) {
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            value = value.replace("%" + placeholders[i] + "%", placeholders[i + 1]);
        }
        return value;
    }
}
