package com.mystipixel.royalskyblock.world.asp.loaders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RsbFileLoaderTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a save replaces the world and leaves no temp file behind")
    void saveReplacesAndCleansUp() throws Exception {
        RsbFileLoader loader = new RsbFileLoader(dir.toFile());
        loader.saveWorld("island_a", new byte[]{1, 2, 3});
        loader.saveWorld("island_a", new byte[]{4, 5});

        assertArrayEquals(new byte[]{4, 5}, loader.readWorld("island_a"));
        assertEquals(List.of("island_a"), loader.listWorlds());
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "only island_a.slime should remain");
        }
    }

    @Test
    @DisplayName("overlapping saves of one world always leave one complete copy")
    void overlappingSavesNeverInterleave() throws Exception {
        RsbFileLoader loader = new RsbFileLoader(dir.toFile());
        int size = 256 * 1024;
        List<CompletableFuture<Void>> saves = IntStream.range(1, 9).mapToObj(n -> CompletableFuture.runAsync(() -> {
            byte[] data = new byte[size];
            java.util.Arrays.fill(data, (byte) n);
            try {
                loader.saveWorld("island_b", data);
            } catch (Exception e) {
                // Windows can refuse a rename while another save holds the target; that save failing
                // loudly is fine. What must never happen is a mixed or short file.
            }
        })).toList();
        CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new)).join();

        byte[] result = loader.readWorld("island_b");
        assertEquals(size, result.length);
        for (byte b : result) {
            assertEquals(result[0], b, "the saved file mixes bytes from two different saves");
        }
    }

    @Test
    @DisplayName("temp files left by a crash are cleared and never listed as worlds")
    void staleTempFilesAreCleared() throws Exception {
        Files.write(dir.resolve("island_c.slime"), new byte[]{9});
        File stale = dir.resolve("island_c.123.slime.tmp").toFile();
        Files.write(stale.toPath(), new byte[]{0});

        RsbFileLoader loader = new RsbFileLoader(dir.toFile());

        assertFalse(stale.exists());
        assertEquals(List.of("island_c"), loader.listWorlds());
        assertTrue(loader.worldExists("island_c"));
    }
}
