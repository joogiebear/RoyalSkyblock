package com.mystipixel.royalskyblock.api;

import com.mystipixel.royalskyblock.island.Island;
import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the main thread just after an island world loads, when it spent time unloaded. An
 * unloaded world doesn't tick, so anything that would have ticked (minions, furnaces) should listen
 * here and fast-forward itself. RoyalSkyblock only simulates crops itself.
 *
 * <pre>{@code
 * @EventHandler
 * public void onCatchup(IslandCatchupEvent event) {
 *     for (Minion minion : minionsIn(event.getWorld())) {
 *         minion.produceFor(event.getOfflineSeconds());
 *     }
 * }
 * }</pre>
 *
 * <p>{@link #getOfflineSeconds()} is clamped to {@code simulation.max-offline-hours}. An admin can set
 * that to {@code 0} for no cap, so a listener paying out per second should apply its own ceiling if
 * unbounded payouts would hurt. It is never zero or negative. Not cancellable: the time has passed.
 */
public final class IslandCatchupEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Island island;
    private final World world;
    private final long offlineSeconds;
    private final long rawOfflineSeconds;

    public IslandCatchupEvent(Island island, World world, long offlineSeconds, long rawOfflineSeconds) {
        this.island = island;
        this.world = world;
        this.offlineSeconds = offlineSeconds;
        this.rawOfflineSeconds = rawOfflineSeconds;
    }

    public Island getIsland() {
        return island;
    }

    /** The freshly loaded island world. Safe to read and modify: this fires on the main thread. */
    public World getWorld() {
        return world;
    }

    /**
     * Seconds to simulate: real offline time, clamped to {@code simulation.max-offline-hours} unless
     * that is {@code 0} (no cap). Use this one.
     */
    public long getOfflineSeconds() {
        return offlineSeconds;
    }

    /**
     * The unclamped time the island spent unloaded. Only differs from {@link #getOfflineSeconds()} when
     * the cap applied, e.g. for "your island was away 9 days, but only 24h were simulated".
     */
    public long getRawOfflineSeconds() {
        return rawOfflineSeconds;
    }

    /** True when the cap trimmed the simulated window. */
    public boolean wasClamped() {
        return rawOfflineSeconds > offlineSeconds;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
