package com.mystipixel.royalskyblock.gui.menu;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The text and positions a menu file gives its code-placed items ({@code entry:}, {@code header:},
 * {@code buttons:} ...). Each key is read from the server's copy of the file and falls back to the copy
 * bundled in the jar, so a file saved by an older version still shows the shipped wording.
 */
public final class MenuText {

    private final ConfigurationSection file;
    private final ConfigurationSection bundled;
    private final int size;

    public MenuText(ConfigurationSection file, ConfigurationSection bundled, int size) {
        this.file = file;
        this.bundled = bundled == null ? new YamlConfiguration() : bundled;
        this.size = size;
    }

    private Object value(String path) {
        return file.contains(path) ? file.get(path) : bundled.get(path);
    }

    /** The string at {@code path}, or "" when neither copy has it. */
    public String text(String path) {
        Object value = value(path);
        return value == null || value instanceof ConfigurationSection ? "" : String.valueOf(value);
    }

    public boolean has(String path) {
        return file.contains(path) || bundled.contains(path);
    }

    /** The string list at {@code path} (lore), or an empty list. */
    public List<String> lines(String path) {
        Object value = value(path);
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object line : list) {
                out.add(String.valueOf(line));
            }
        }
        return out;
    }

    /** The 0-based slot from {@code path.row} / {@code path.column}, or -1 when unset or outside the menu. */
    public int slot(String path) {
        return index(value(path + ".row"), value(path + ".column"));
    }

    /** The slots of a {@code path:} list of {@code {row, column}} entries, skipping any outside the menu. */
    public List<Integer> slots(String path) {
        List<Integer> out = new ArrayList<>();
        if (value(path) instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    int index = index(map.get("row"), map.get("column"));
                    if (index >= 0) {
                        out.add(index);
                    }
                }
            }
        }
        return out;
    }

    private int index(Object row, Object column) {
        if (row == null || column == null) {
            return -1;
        }
        try {
            int r = Integer.parseInt(String.valueOf(row).trim());
            int c = Integer.parseInt(String.valueOf(column).trim());
            int index = (r - 1) * 9 + (c - 1);
            return c >= 1 && c <= 9 && index >= 0 && index < size ? index : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Replaces each {@code %key%} in one pass, so a value that itself contains {@code %...%} (a player-chosen
     * name) is never expanded again. Unknown tokens are left as they are.
     */
    public static String fill(String template, Map<String, String> placeholders) {
        if (template == null || template.indexOf('%') < 0 || placeholders.isEmpty()) {
            return template == null ? "" : template;
        }
        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf('%', i);
            if (start < 0) {
                out.append(template, i, template.length());
                break;
            }
            int end = template.indexOf('%', start + 1);
            if (end < 0) {
                out.append(template, i, template.length());
                break;
            }
            String value = placeholders.get(template.substring(start + 1, end));
            if (value == null) {
                out.append(template, i, end);    // not a token: keep the first % and look again from the second
                i = end;
                continue;
            }
            out.append(template, i, start).append(value);
            i = end + 1;
        }
        return out.toString();
    }

    /**
     * Fills each lore line; a line that is exactly {@code %key%} for a key in {@code multi} becomes that
     * list's lines (none, if it is empty).
     */
    public static List<String> fill(List<String> lines, Map<String, String> placeholders,
                                    Map<String, List<String>> multi) {
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() > 2 && trimmed.startsWith("%") && trimmed.endsWith("%")) {
                List<String> expanded = multi.get(trimmed.substring(1, trimmed.length() - 1));
                if (expanded != null) {
                    out.addAll(expanded);
                    continue;
                }
            }
            out.add(fill(line, placeholders));
        }
        return out;
    }
}
