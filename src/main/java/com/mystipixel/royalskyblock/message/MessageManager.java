package com.mystipixel.royalskyblock.message;

import com.mystipixel.royalskyblock.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Loads every player-facing string from {@code messages.yml} so text lives in one place instead of
 * being scattered through the code — matching the rest of the Royal suite. Placeholders use the
 * eco-style {@code %token%} form.
 *
 * <p>{@link #send} prepends the configured prefix (chat feedback); {@link #sendPlain} does not
 * (headers, multi-line help, warnings).
 */
public final class MessageManager {

    private final JavaPlugin plugin;
    private FileConfiguration messages;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        saveDefault();
        reload();
    }

    public void reload() {
        this.messages = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
    }

    private void saveDefault() {
        if (!new File(plugin.getDataFolder(), "messages.yml").exists()) {
            plugin.saveResource("messages.yml", false);
        }
    }

    public String prefix() {
        return messages.getString("prefix", "&6&lRoyalSkyblock &8» &r");
    }

    /** The raw string for a key (with {@code %token%} substitution), or a visible missing marker. */
    public String raw(String key, String... placeholders) {
        return apply(messages.getString(key, "&c<missing:" + key + ">"), placeholders);
    }

    /** Send with the prefix. {@code placeholders} are token/value pairs: {@code "player", name}. */
    public void send(CommandSender sender, String key, String... placeholders) {
        sender.sendMessage(Text.color(prefix() + raw(key, placeholders)));
    }

    /** Send without the prefix — for headers, help lines, and multi-line blocks. */
    public void sendPlain(CommandSender sender, String key, String... placeholders) {
        sender.sendMessage(Text.color(raw(key, placeholders)));
    }

    private String apply(String value, String... placeholders) {
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            value = value.replace("%" + placeholders[i] + "%", placeholders[i + 1]);
        }
        return value;
    }
}
