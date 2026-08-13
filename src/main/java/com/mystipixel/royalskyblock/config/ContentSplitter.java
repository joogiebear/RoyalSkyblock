package com.mystipixel.royalskyblock.config;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.libreforge.RoyalHolders;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a legacy {@code upgrades.yml} / {@code perks.yml} into the one-file-per-thing layout.
 *
 * <p>Both layouts have always loaded, but the folders are only written on a fresh install — an
 * existing server keeps its monolith and never sees them appear. That is deliberate (nothing should
 * rewrite an admin's config behind their back) and it leaves upgrading servers with no way across,
 * which is what this closes. It runs from {@code /is admin split-content}, never on its own.
 *
 * <h2>Why it slices text</h2>
 *
 * <p>The obvious implementation — load with Bukkit, write each section back out — would destroy the
 * thing the folder layout exists for. Bukkit's YAML writer keeps no comments, so a config whose value
 * is half documentation comes back as bare keys. Every block is therefore cut out of the original
 * file verbatim and only shifted left, so what an admin wrote is what they get, alignment included.
 *
 * <p>The split is conservative on purpose: an id that already has a file is skipped rather than
 * overwritten, and the monolith is renamed rather than deleted.
 */
public final class ContentSplitter {

    /** One item cut out of a monolith: the comment block above it, and its body. */
    public record Block(List<String> comment, List<String> body) { }

    /** What a run did, or would do. */
    public record Result(List<String> written, List<String> skipped, List<String> notes) {

        public boolean isEmpty() {
            return written.isEmpty() && skipped.isEmpty();
        }
    }

    private final RoyalSkyblockPlugin plugin;

    public ContentSplitter(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    // ── the pure part ──────────────────────────────────────────────────────────

    /**
     * Cut every {@code <indent>key:} block out of {@code lines}.
     *
     * <p>A comment block sitting directly above a key belongs to that key. The file's own banner does
     * not — it documents all of them — so scanning starts past it, or the first item would adopt the
     * whole preamble and the rest would get nothing.
     *
     * @param container the section the items live under ({@code perks}), or null if they are at root
     */
    public static Map<String, Block> sliceBlocks(List<String> lines, int indent, @Nullable String container) {
        int start = 0;
        if (container != null) {
            start = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (stripTrailing(lines.get(i)).equals(container + ":")) {
                    start = i + 1;
                    break;
                }
            }
            if (start < 0) {
                return Map.of();                                  // no content section: nothing to split
            }
        } else {
            while (start < lines.size() && isCommentOrBlank(lines.get(start))) {
                start++;
            }
        }

        List<Integer> keyLines = new ArrayList<>();
        List<String> keyNames = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            String name = keyAt(lines.get(i), indent);
            if (name != null) {
                keyLines.add(i);
                keyNames.add(name);
            }
        }

        Map<String, Block> blocks = new LinkedHashMap<>();
        for (int n = 0; n < keyLines.size(); n++) {
            int keyLine = keyLines.get(n);

            int top = keyLine;
            while (top > start && isCommentOrBlank(lines.get(top - 1))) {
                top--;
            }
            List<String> comment = new ArrayList<>();
            for (String line : lines.subList(top, keyLine)) {
                if (!line.isBlank()) {
                    comment.add(line);
                }
            }

            int bodyEnd = n + 1 < keyLines.size() ? keyLines.get(n + 1) : lines.size();
            List<String> body = new ArrayList<>(lines.subList(keyLine + 1, bodyEnd));
            // Trailing blanks and comments introduce the NEXT item, so they are not part of this one.
            while (!body.isEmpty() && isCommentOrBlank(body.get(body.size() - 1))) {
                body.remove(body.size() - 1);
            }
            blocks.put(keyNames.get(n), new Block(comment, body));
        }
        return blocks;
    }

    /**
     * Shift lines left by {@code amount}, preserving everything else.
     *
     * <p>Returns null if any line is indented less than that, which would mean the block was not
     * shaped the way it was read. Refusing beats emitting YAML that parses differently.
     */
    public static @Nullable List<String> dedent(List<String> lines, int amount) {
        String prefix = " ".repeat(amount);
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.isBlank()) {
                out.add("");
            } else if (line.startsWith(prefix)) {
                out.add(line.substring(amount));
            } else {
                return null;
            }
        }
        return out;
    }

    /** Render one split file: the header, the item's own comment block, then its body at column 0. */
    public static @Nullable String renderFile(String header, Block block, int keyIndent, int bodyIndent) {
        List<String> comment = dedent(block.comment(), keyIndent);
        List<String> body = dedent(block.body(), bodyIndent);
        if (comment == null || body == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(header).append('\n').append('\n');
        for (String line : comment) {
            sb.append(line).append('\n');
        }
        if (!comment.isEmpty()) {
            sb.append('\n');
        }
        for (String line : body) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static @Nullable String keyAt(String line, int indent) {
        if (line.length() <= indent || !line.startsWith(" ".repeat(indent))) {
            return null;
        }
        if (indent > 0 && line.charAt(indent) == ' ') {
            return null;                                          // deeper than the item level
        }
        String rest = stripTrailing(line).substring(indent);
        if (!rest.endsWith(":")) {
            return null;
        }
        String name = rest.substring(0, rest.length() - 1);
        if (name.isEmpty()) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) && c == Character.toLowerCase(c)) && c != '_' && c != '-') {
                return null;
            }
        }
        return name;
    }

    private static boolean isCommentOrBlank(String line) {
        return line.isBlank() || line.stripLeading().startsWith("#");
    }

    private static String stripTrailing(String line) {
        return line.stripTrailing();
    }

    // ── the file-touching part ─────────────────────────────────────────────────

    /**
     * Split both monoliths.
     *
     * @param apply false to report what would happen without touching anything
     */
    public Result run(boolean apply) {
        List<String> written = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        splitOne("upgrades.yml", "upgrades", null, 0, 2, "Upgrade track", "size.yml",
                apply, written, skipped, notes);
        splitOne("perks.yml", "perks", "perks", 2, 4, "Perk", "haste.yml",
                apply, written, skipped, notes);

        if (apply && !written.isEmpty()) {
            plugin.upgrades().reload();
            plugin.perks().reload();
            // The effect chains are compiled separately from the content, and the split just moved the
            // files they are read from. Without this they would keep serving what was compiled at boot
            // from a monolith that no longer exists, until something else happened to reload them.
            RoyalHolders.INSTANCE.reload(plugin);
            notes.add("Reloaded upgrades, perks, and their effect chains.");
        }
        return new Result(written, skipped, notes);
    }

    private void splitOne(String monolithName, String folderName, @Nullable String container,
                          int keyIndent, int bodyIndent, String title, String headerSample,
                          boolean apply, List<String> written, List<String> skipped, List<String> notes) {
        File monolith = new File(plugin.getDataFolder(), monolithName);
        if (!monolith.isFile()) {
            notes.add(monolithName + ": not present, nothing to split.");
            return;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(monolith.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            notes.add(monolithName + ": could not be read — " + e.getMessage());
            return;
        }

        Map<String, Block> blocks = sliceBlocks(lines, keyIndent, container);
        if (blocks.isEmpty()) {
            notes.add(monolithName + ": no content to split"
                    + (container == null ? "." : " (no " + container + ": section)."));
            return;
        }

        File folder = new File(plugin.getDataFolder(), folderName);
        String header = headerFor(folderName, headerSample, title);

        for (Map.Entry<String, Block> entry : blocks.entrySet()) {
            String id = entry.getKey();
            File target = new File(folder, id + ".yml");
            if (target.isFile()) {
                skipped.add(folderName + "/" + id + ".yml (already exists)");
                continue;
            }
            String rendered = renderFile(header.replace("{id}", id), entry.getValue(), keyIndent, bodyIndent);
            if (rendered == null) {
                skipped.add(folderName + "/" + id + ".yml (unexpected indentation — left alone)");
                continue;
            }
            if (apply) {
                try {
                    if (!folder.isDirectory() && !folder.mkdirs()) {
                        notes.add("Could not create " + folderName + "/.");
                        return;
                    }
                    Files.writeString(target.toPath(), rendered, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    skipped.add(folderName + "/" + id + ".yml (write failed: " + e.getMessage() + ")");
                    continue;
                }
            }
            written.add(folderName + "/" + id + ".yml");
        }

        if (!apply) {
            return;
        }
        // Retire the monolith only once its content is safely in the folder. perks.yml survives with
        // its two switches — those are settings, not content — and leaving the perks: section beside
        // the folder would mean edits to it silently doing nothing, which is the trap this avoids.
        if (container == null) {
            retire(monolith, notes);
        } else {
            trimContainer(monolith, lines, container, notes);
        }
    }

    /** Keep everything above the content section; drop the section itself. */
    private void trimContainer(File file, List<String> lines, String container, List<String> notes) {
        int cut = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (stripTrailing(lines.get(i)).equals(container + ":")) {
                cut = i;
                break;
            }
        }
        if (cut < 0) {
            return;
        }
        List<String> head = new ArrayList<>(lines.subList(0, cut));
        while (!head.isEmpty() && head.get(head.size() - 1).isBlank()) {
            head.remove(head.size() - 1);
        }
        head.add("");
        head.add("# The " + container + " themselves live in the " + container + "/ folder, one file each,");
        head.add("# the way every eco plugin ships its content. The file name is the id — copy a file");
        head.add("# there to add one, there is nothing to register. This file keeps only the settings.");
        head.add("");
        try {
            Files.copy(file.toPath(), new File(file.getPath() + ".pre-split").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.write(file.toPath(), head, StandardCharsets.UTF_8);
            notes.add(file.getName() + ": content moved out, settings kept (backup: "
                    + file.getName() + ".pre-split).");
        } catch (IOException e) {
            notes.add(file.getName() + ": could not be trimmed — " + e.getMessage());
        }
    }

    private void retire(File monolith, List<String> notes) {
        File backup = new File(monolith.getPath() + ".pre-split");
        if (monolith.renameTo(backup)) {
            notes.add(monolith.getName() + ": moved to " + backup.getName() + ".");
        } else {
            notes.add(monolith.getName() + ": could NOT be renamed — it is still being read, so the "
                    + "folder files are shadowed by it. Move it aside by hand.");
        }
    }

    /**
     * The banner shipped on this kind of content file, retitled with a {@code {id}} placeholder.
     * Falls back to a one-line header if the jar resource has moved.
     */
    private String headerFor(String folderName, String sample, String title) {
        String fallback = "# ═══════════════════════════════════════════════════════════════════════════════\n"
                + "#  RoyalSkyblock  ·  " + title + ": {id}\n"
                + "# ═══════════════════════════════════════════════════════════════════════════════";
        try (InputStream in = plugin.getResource(folderName + "/" + sample)) {
            if (in == null) {
                return fallback;
            }
            List<String> out = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!isCommentOrBlank(line)) {
                        break;                                    // banner ends at the first real key
                    }
                    if (line.isBlank()) {
                        continue;
                    }
                    out.add(line.contains("RoyalSkyblock") && line.contains("·")
                            ? "#  RoyalSkyblock  ·  " + title + ": {id}"
                            : line);
                }
            }
            return out.isEmpty() ? fallback : String.join("\n", out);
        } catch (IOException e) {
            return fallback;
        }
    }
}
