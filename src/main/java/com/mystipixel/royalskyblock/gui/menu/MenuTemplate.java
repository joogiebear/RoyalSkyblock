package com.mystipixel.royalskyblock.gui.menu;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/**
 * A single menu loaded from a {@code gui/*.yml} file in the EcoMenus dialect shared with the rest of
 * the Royal suite: {@code title}, {@code rows}, an optional page {@code mask} (filler pattern where
 * {@code 0} marks dynamic-content slots), and fixed {@code slots} — each with a 1-based
 * {@code row}/{@code column} placed directly on the slot, an inline {@code item} spec, {@code lore},
 * and {@code left-click}/{@code right-click} effect lists.
 */
public final class MenuTemplate {

    private final String title;
    private final int rows;
    private final ItemStack maskFiller;        // null when no mask
    private final List<Integer> contentSlots;  // indices marked 0 in the mask
    private final List<MenuSlot> slots;
    private final Map<String, SoundSpec> sounds;

    private MenuTemplate(String title, int rows, ItemStack maskFiller,
                         List<Integer> contentSlots, List<MenuSlot> slots,
                         Map<String, SoundSpec> sounds) {
        this.title = title;
        this.rows = rows;
        this.maskFiller = maskFiller;
        this.contentSlots = contentSlots;
        this.slots = slots;
        this.sounds = sounds;
    }

    /** One entry from a menu's {@code sounds:} block. */
    public record SoundSpec(String name, float volume, float pitch) {
    }

    /**
     * Read the {@code sounds:} block. Keys are free-form ({@code open}, {@code click}, ...) so a menu can
     * define whatever the engine asks for without the parser needing to know the names.
     *
     * <p>Sound ids are written as the Bukkit enum ({@code UI_BUTTON_CLICK}) and converted to the
     * namespaced key Paper expects, so configs match the rest of the suite. {@code enabled: false}
     * silences one without deleting it.
     */
    private static Map<String, SoundSpec> parseSounds(FileConfiguration cfg) {
        ConfigurationSection section = cfg.getConfigurationSection("sounds");
        if (section == null) {
            return Map.of();
        }
        Map<String, SoundSpec> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            // 'name' is the suite's key (RoyalBank, RoyalWardrobe, RoyalAuctions all use it);
            // 'sound' is accepted too so a config written either way works.
            String name = entry.getString("name", entry.getString("sound", ""));
            if (name.isBlank() || !entry.getBoolean("enabled", true)) {
                continue;
            }
            out.put(key.toLowerCase(Locale.ROOT), new SoundSpec(
                    name.trim().toLowerCase(Locale.ROOT).replace('_', '.'),
                    (float) entry.getDouble("volume", 0.7),
                    (float) entry.getDouble("pitch", 1.0)));
        }
        return Map.copyOf(out);
    }

    public static MenuTemplate load(File file, String defaultTitle, int defaultRows) {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        String title = cfg.getString("title", defaultTitle);
        int rows = Math.max(1, Math.min(6, cfg.getInt("rows", defaultRows)));
        int size = rows * 9;

        ItemStack filler = null;
        List<Integer> contentSlots = new ArrayList<>();
        ConfigurationSection mask = firstPageMask(cfg);
        if (mask != null) {
            List<String> maskItems = mask.getStringList("items");
            List<String> pattern = mask.getStringList("pattern");
            if (!maskItems.isEmpty()) {
                filler = ItemSpec.parse(maskItems.get(0) + " name:\" \"").build(Map.of(), List.of());
            }
            for (int r = 0; r < pattern.size() && r < rows; r++) {
                String line = pattern.get(r);
                for (int c = 0; c < 9 && c < line.length(); c++) {
                    if (line.charAt(c) == '0') {
                        contentSlots.add(r * 9 + c);
                    }
                }
            }
        }

        List<MenuSlot> slots = new ArrayList<>();
        for (Map<?, ?> raw : cfg.getMapList("slots")) {
            MenuSlot slot = parseSlot(raw, size);
            if (slot != null) {
                slots.add(slot);
            }
        }

        return new MenuTemplate(title, rows, filler, contentSlots, slots, parseSounds(cfg));
    }

    private static ConfigurationSection firstPageMask(FileConfiguration cfg) {
        List<Map<?, ?>> pages = cfg.getMapList("pages");
        if (pages.isEmpty()) {
            // also allow a top-level `mask:` for simple menus
            return cfg.getConfigurationSection("mask");
        }
        Object mask = pages.get(0).get("mask");
        if (mask instanceof ConfigurationSection cs) {
            return cs;
        }
        if (mask instanceof Map<?, ?> m) {
            YamlConfiguration tmp = new YamlConfiguration();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                tmp.set(String.valueOf(e.getKey()), e.getValue());
            }
            return tmp;
        }
        return null;
    }

    private static MenuSlot parseSlot(Map<?, ?> raw, int size) {
        Object itemObj = raw.get("item");
        String content = raw.get("content") == null ? null : String.valueOf(raw.get("content"));
        if (itemObj == null && content == null) {
            return null;
        }
        int index = slotIndex(raw, size);
        if (index < 0) {
            return null;
        }
        String id = raw.get("id") == null ? null : String.valueOf(raw.get("id"));
        // A content slot without its own item gets a placeholder; code renders the real icon over it.
        ItemSpec item = ItemSpec.parse(itemObj == null ? "barrier" : String.valueOf(itemObj));
        return new MenuSlot(index, id, content, item, stringList(raw.get("lore")),
                MenuEffect.parseList(castMapList(raw.get("left-click"))),
                MenuEffect.parseList(castMapList(raw.get("right-click"))));
    }

    /** Slots that pin a named dynamic entry ({@code content: <key>}) → their 0-based index. */
    public Map<String, Integer> namedContentSlots() {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (MenuSlot slot : slots) {
            if (slot.content() != null) {
                map.put(slot.content().toLowerCase(java.util.Locale.ROOT), slot.index());
            }
        }
        return map;
    }

    /**
     * Resolve a slot's 1-based {@code row}/{@code column} into a 0-based inventory index. Row/column sit
     * directly on the slot (the eco-menus convention); a legacy nested {@code location: {row, column}}
     * is still accepted as a fallback.
     */
    private static int slotIndex(Map<?, ?> raw, int size) {
        Object rowObj = raw.get("row");
        Object colObj = raw.get("column");
        if (rowObj == null && colObj == null) {
            Object loc = raw.get("location");
            if (loc instanceof ConfigurationSection cs) {
                rowObj = cs.get("row");
                colObj = cs.get("column");
            } else if (loc instanceof Map<?, ?> m) {
                rowObj = m.get("row");
                colObj = m.get("column");
            }
        }
        int index = (intOf(rowObj, 1) - 1) * 9 + (intOf(colObj, 1) - 1);
        return index >= 0 && index < size ? index : -1;
    }

    // ── accessors / rendering ────────────────────────────────────────────────────

    public String title() {
        return title;
    }

    public int size() {
        return rows * 9;
    }

    public List<MenuSlot> slots() {
        return slots;
    }

    /** Inventory indices marked {@code 0} in the mask — where dynamic content (e.g. profile icons) go. */
    /** A configured sound by key, or null when the menu doesn't define one. */
    public SoundSpec sound(String key) {
        return sounds.get(key);
    }

    public List<Integer> contentSlots() {
        return contentSlots;
    }

    public MenuSlot slotAt(int index) {
        for (MenuSlot slot : slots) {
            if (slot.index() == index) {
                return slot;
            }
        }
        return null;
    }

    /** Paint the mask filler across every non-content slot. */
    public void applyFiller(Inventory inv) {
        if (maskFiller == null) {
            return;
        }
        for (int i = 0; i < inv.getSize(); i++) {
            if (!contentSlots.contains(i)) {
                inv.setItem(i, maskFiller.clone());
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private static List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object e : list) {
                out.add(String.valueOf(e));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> castMapList(Object o) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof Map<?, ?> m) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
