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
 * The island trash can. Every deleted island's world bytes are archived here before the store
 * forgets them, because {@code /is delete confirm} is two words typed in anger and the island IS
 * the player's progress. Archives are plain {@code .slime} files regardless of which backend the
 * live store uses — the loader API is bytes in, bytes out — pruned after a retention window.
 *
 * <p>Restoring: {@code /is admin trash restore <archive> <player>} writes the bytes back under a
 * fresh island id for the player's active profile. Upgrades and level history lived in database
 * rows that died with the old island; a restore brings back the blocks, which is the part that
 * cannot be re-earned by clicking.
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
     * Archive a world's stored bytes. Respects the {@code trash.enabled} switch unless {@code force}
     * — the orphan purge always archives, because "purge" must never mean "hard-delete". Throws on
     * failure so a delete that could not be archived is aborted rather than made unrecoverable.
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
