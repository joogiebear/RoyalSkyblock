package com.mystipixel.royalskyblock.profile;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveRotationTest {

    private static List<UUID> players(int n) {
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(UUID.randomUUID());
        }
        return out;
    }

    @Test
    @DisplayName("0 or less turns the autosave off; short intervals are raised to the minimum")
    void intervalFromConfig() {
        assertEquals(0, SaveRotation.intervalTicks(0));
        assertEquals(0, SaveRotation.intervalTicks(-5));
        assertEquals(SaveRotation.MIN_INTERVAL_SECONDS * 20L, SaveRotation.intervalTicks(1));
        assertEquals(300 * 20L, SaveRotation.intervalTicks(300));
        assertThrows(IllegalArgumentException.class, () -> new SaveRotation(0));
    }

    @Test
    @DisplayName("config.yml ships the autosave key with a 5 minute default")
    void configDefault() throws Exception {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertTrue(config.isInt("profiles.autosave-interval-seconds"));
        assertEquals(300, config.getInt("profiles.autosave-interval-seconds"));
    }

    @Test
    @DisplayName("every online player is saved once per interval, spread over the first minute")
    void staggersOverSpread() {
        List<UUID> online = players(100);
        long interval = 6000;
        SaveRotation rotation = new SaveRotation(interval);
        Set<UUID> saved = new HashSet<>();
        int maxPerTick = 0;
        for (long tick = 1; tick <= interval; tick++) {
            List<UUID> due = rotation.next(() -> online);
            maxPerTick = Math.max(maxPerTick, due.size());
            for (UUID uuid : due) {
                assertTrue(saved.add(uuid), "saved twice in one interval");
            }
            if (tick == SaveRotation.MAX_SPREAD_TICKS) {
                assertEquals(100, saved.size(), "everyone should be saved by the end of the spread");
            }
        }
        assertEquals(1, maxPerTick);
        assertEquals(new HashSet<>(online), saved);

        // the next interval starts over with the same players
        Set<UUID> second = new HashSet<>();
        for (long tick = 1; tick <= interval; tick++) {
            second.addAll(rotation.next(() -> online));
        }
        assertEquals(new HashSet<>(online), second);
    }

    @Test
    @DisplayName("a crowded server saves only a few players per tick")
    void bigServerStaysFlat() {
        List<UUID> online = players(2500);
        SaveRotation rotation = new SaveRotation(6000);
        int total = 0;
        for (int tick = 1; tick <= 6000; tick++) {
            int n = rotation.next(() -> online).size();
            assertTrue(n <= 3, "tick " + tick + " saved " + n);
            total += n;
        }
        assertEquals(2500, total);
    }

    @Test
    @DisplayName("an interval shorter than a minute spreads over the whole interval")
    void shortInterval() {
        List<UUID> online = players(10);
        long interval = SaveRotation.intervalTicks(SaveRotation.MIN_INTERVAL_SECONDS);
        SaveRotation rotation = new SaveRotation(interval);
        int[] perCycle = new int[3];
        for (int cycle = 0; cycle < 3; cycle++) {
            for (long tick = 1; tick <= interval; tick++) {
                perCycle[cycle] += rotation.next(() -> online).size();
            }
        }
        assertEquals(10, perCycle[0]);
        assertEquals(10, perCycle[1]);
        assertEquals(10, perCycle[2]);
    }

    @Test
    @DisplayName("the online list is read once per interval; a player who joins mid-way waits for the next one")
    void readsOnlineAtCycleStart() {
        List<UUID> online = new ArrayList<>(players(3));
        AtomicInteger reads = new AtomicInteger();
        SaveRotation rotation = new SaveRotation(600);
        Set<UUID> saved = new HashSet<>();
        UUID late = UUID.randomUUID();
        for (int tick = 1; tick <= 600; tick++) {
            if (tick == 5) {
                online.add(late);
            }
            saved.addAll(rotation.next(() -> {
                reads.incrementAndGet();
                return List.copyOf(online);
            }));
        }
        assertEquals(1, reads.get());
        assertEquals(3, saved.size());
        assertTrue(!saved.contains(late));
        for (int tick = 1; tick <= 600; tick++) {
            saved.addAll(rotation.next(() -> List.copyOf(online)));
        }
        assertTrue(saved.contains(late));
    }

    @Test
    @DisplayName("nobody online means nothing to do")
    void emptyServer() {
        SaveRotation rotation = new SaveRotation(600);
        for (int tick = 1; tick <= 1200; tick++) {
            assertTrue(rotation.next(List::of).isEmpty());
        }
    }
}
