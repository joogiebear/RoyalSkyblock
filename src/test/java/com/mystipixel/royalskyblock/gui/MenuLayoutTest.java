package com.mystipixel.royalskyblock.gui;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// The bundled menu files parse, have no duplicate keys, and every item the code places from them
// (header:, buttons:, back: ...) lands inside the menu on a slot nothing else uses.
class MenuLayoutTest {

    static final List<String> MENUS = List.of(
            "core/main", "core/confirm-delete",
            "profile/profiles", "profile/create-profile",
            "island/manage", "island/settings", "island/upgrades", "island/visit",
            "coop/coop", "coop/coop-invite", "coop/coop-member",
            "bank/bank-hub", "bank/bank-personal", "bank/coop-bank", "bank/coop-bank-txns",
            "level/level", "level/top", "level/perks");

    // code-placed items that are never shown together, so they may share a slot
    private static final List<Set<String>> ALTERNATES = List.of(
            Set.of("buttons.promote", "buttons.demote"),
            Set.of("buttons.claim-interest", "buttons.interest-waiting"),
            Set.of("buttons.upgrade", "buttons.maxed"));

    static String read(String resource) throws IOException {
        try (InputStream in = MenuLayoutTest.class.getResourceAsStream("/" + resource)) {
            assertNotNull(in, resource + " is not bundled");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static YamlConfiguration menu(String path) throws IOException, InvalidConfigurationException {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(read("gui/" + path + ".yml"));
        return cfg;
    }

    @Test
    void everyMenuParsesWithoutDuplicateKeys() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        for (String path : MENUS) {
            assertDoesNotThrow(() -> new Yaml(options).load(read("gui/" + path + ".yml")),
                    "gui/" + path + ".yml has a duplicate key or doesn't parse");
            assertDoesNotThrow(() -> menu(path), "gui/" + path + ".yml doesn't parse");
        }
    }

    // a server file without title:/rows: falls back to these
    @Test
    void everyMenuShipsATitleAndRows() throws Exception {
        for (String path : MENUS) {
            YamlConfiguration cfg = menu(path);
            assertTrue(cfg.isString("title") && !cfg.getString("title").isBlank(), path + " has no title");
            assertTrue(cfg.isInt("rows"), path + " has no rows");
        }
    }

    @Test
    void codePlacedItemsDoNotShareSlots() throws Exception {
        for (String path : MENUS) {
            YamlConfiguration cfg = menu(path);
            int size = cfg.getInt("rows", 5) * 9;
            Map<Integer, String> fixed = new HashMap<>();
            for (Map<?, ?> slot : cfg.getMapList("slots")) {
                fixed.put(index(slot.get("row"), slot.get("column")), "slots: entry");
            }

            Map<String, List<Integer>> placed = new HashMap<>();
            for (String key : List.of("header", "back", "perks-button")) {
                if (cfg.isSet(key + ".row")) {
                    placed.put(key, List.of(index(cfg.get(key + ".row"), cfg.get(key + ".column"))));
                }
            }
            ConfigurationSection buttons = cfg.getConfigurationSection("buttons");
            if (buttons != null) {
                for (String id : buttons.getKeys(false)) {
                    String key = "buttons." + id;
                    List<Integer> slots = new ArrayList<>();
                    if (cfg.isList(key + ".positions")) {
                        for (Map<?, ?> pos : cfg.getMapList(key + ".positions")) {
                            slots.add(index(pos.get("row"), pos.get("column")));
                        }
                    } else {
                        slots.add(index(cfg.get(key + ".row"), cfg.get(key + ".column")));
                    }
                    placed.put(key, slots);
                }
            }

            Map<Integer, String> taken = new HashMap<>(fixed);
            for (Map.Entry<String, List<Integer>> entry : placed.entrySet()) {
                for (int slot : entry.getValue()) {
                    assertTrue(slot >= 0 && slot < size, path + ": " + entry.getKey() + " is outside the menu");
                    String other = taken.get(slot);
                    if (other != null && !alternates(other, entry.getKey())) {
                        fail(path + ": " + entry.getKey() + " and " + other + " both use slot " + slot);
                    }
                    taken.put(slot, entry.getKey());
                }
            }
        }
    }

    private static boolean alternates(String a, String b) {
        return ALTERNATES.stream().anyMatch(set -> set.contains(a) && set.contains(b));
    }

    private static int index(Object row, Object column) {
        if (row == null || column == null) {
            return -1;
        }
        int r = Integer.parseInt(String.valueOf(row));
        int c = Integer.parseInt(String.valueOf(column));
        return c < 1 || c > 9 ? -1 : (r - 1) * 9 + (c - 1);
    }
}
