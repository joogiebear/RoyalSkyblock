package com.mystipixel.royalskyblock.profile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Which online players the periodic profile save handles on each tick. Every interval it takes the
 * online players and spreads them evenly over the first {@link #MAX_SPREAD_TICKS} ticks (or the whole
 * interval, if shorter), so a full server never serializes everyone in one tick.
 */
public final class SaveRotation {

    public static final int MIN_INTERVAL_SECONDS = 30;
    // capped so a player who joins just after a cycle starts waits at most one interval plus a minute
    static final long MAX_SPREAD_TICKS = 60L * 20L;

    private final long intervalTicks;
    private final long spreadTicks;
    private final ArrayDeque<UUID> queue = new ArrayDeque<>();
    private long tickInCycle;
    private int cycleSize;
    private int taken;

    public SaveRotation(long intervalTicks) {
        if (intervalTicks <= 0) {
            throw new IllegalArgumentException("interval must be positive, got " + intervalTicks);
        }
        this.intervalTicks = intervalTicks;
        this.spreadTicks = Math.min(intervalTicks, MAX_SPREAD_TICKS);
    }

    /** The configured interval in ticks: 0 (off) for 0 or less, otherwise at least {@link #MIN_INTERVAL_SECONDS}. */
    public static long intervalTicks(int seconds) {
        if (seconds <= 0) {
            return 0;
        }
        return Math.max(seconds, MIN_INTERVAL_SECONDS) * 20L;
    }

    /** Call once per tick. {@code online} is only read at the start of each interval. */
    public List<UUID> next(Supplier<? extends Collection<UUID>> online) {
        if (tickInCycle == 0) {
            queue.clear();
            queue.addAll(online.get());
            cycleSize = queue.size();
            taken = 0;
        }
        tickInCycle++;
        List<UUID> due = new ArrayList<>();
        if (tickInCycle <= spreadTicks) {
            long target = Math.ceilDiv(cycleSize * tickInCycle, spreadTicks);
            while (taken < target && !queue.isEmpty()) {
                due.add(queue.poll());
                taken++;
            }
        }
        if (tickInCycle >= intervalTicks) {
            tickInCycle = 0;
        }
        return due;
    }
}
