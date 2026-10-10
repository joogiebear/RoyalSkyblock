package com.mystipixel.royalskyblock.world;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The island trash: every deleted island's world bytes are archived here before the store forgets
 * them. Archives are plain {@code .slime} files whatever the live backend, pruned after a retention
 * window. {@code /is admin trash restore <archive> <player>} writes the blocks back under a fresh
 * island id; upgrades and level history are not restored.
 */
public final class IslandTrash {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    /** One archived world. */
    public record Entry(String fileName, long size, long modified) {
    }

    private final RoyalSkyblockPlugin plugin;
    private final File dir;

    public IslandTrash(RoyalSkyblockPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "trash");
    }

    public boolean enabled() {
        return plugin.conf().getBoolean("trash.enabled", true);
    }

    public int retentionDays() {
        return Math.max(0, plugin.conf().getInt("trash.retention-days", 30));
    }

    /**
     * Archive a world's stored bytes. Respects {@code trash.enabled} unless {@code force} (the orphan
     * purge always archives). Throws on failure so a delete that couldn't be archived is aborted.
     */
    public void archive(String worldName, boolean force) throws Exception {
        if (!force && !enabled()) {
            return;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("could not create " + dir);
        }
        byte[] data = plugin.worlds().exportWorld(worldName);
        File out = new File(dir, worldName + "-" + STAMP.format(Instant.now()) + ".slime");
        Files.write(out.toPath(), data);
        plugin.getLogger().info("Archived island world '" + worldName + "' to trash/" + out.getName()
                + " (" + data.length / 1024 + " KB, kept " + retentionDays() + " day(s)).");
    }

    /** The archives on disk, newest first. */
    public List<Entry> list() {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".slime"));
        List<Entry> out = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                out.add(new Entry(file.getName(), file.length(), file.lastModified()));
            }
        }
        out.sort(Comparator.comparingLong(Entry::modified).reversed());
        return out;
    }

    /** Read one archive's bytes. The name is confined to the trash directory. */
    public byte[] read(String fileName) throws IOException {
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IOException("not an archive name: " + fileName);
        }
        File file = new File(dir, fileName);
        if (!file.isFile()) {
            throw new IOException("no such archive: " + fileName);
        }
        return Files.readAllBytes(file.toPath());
    }

    /** Delete archives older than the retention window. Returns how many went. 0 days keeps forever. */
    public int pruneOld() {
        int days = retentionDays();
        if (days <= 0) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
        int removed = 0;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".slime"));
        if (files != null) {
            for (File file : files) {
                if (file.lastModified() < cutoff && file.delete()) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Pruned " + removed + " island archive(s) older than " + days
                    + " day(s) from the trash.");
        }
        return removed;
    }
}
