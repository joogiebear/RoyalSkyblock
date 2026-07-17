package com.mystipixel.royalskyblock.api;

import com.mystipixel.royalskyblock.island.Island;
import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the main thread just after an island world is loaded, when it spent time unloaded.
 * An unloaded world does not tick — no crop growth, no minions, no furnaces — so this event is
 * how that missed time gets paid back.
 *
 * <p>This is RoyalSkyblock's public extension point for offline progression. Anything that would
 * have ticked while the island slept should listen here and fast-forward itself; RoyalSkyblock
 * only simulates crops natively (see {@code simulation.crops} in config.yml). A minion plugin,
 * for instance, would listen and award the output its minions would have produced:
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
 * <p>{@link #getOfflineSeconds()} is already clamped to {@code simulation.max-offline-hours}, so a
 * listener can use it directly without worrying that an island left alone for a year mints a year
 * of output. It is never negative and never zero — the event does not fire for a trivial gap.
 *
 * <p>The event is not cancellable: the time has already passed. A listener that wants to opt out
 * simply does nothing.
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

    /** The freshly-loaded island world. Safe to read and modify — this fires on the main thread. */
    public World getWorld() {
        return world;
    }

    /**
     * Seconds to simulate: real offline time, clamped to {@code simulation.max-offline-hours}.
     * Use this one.
     */
    public long getOfflineSeconds() {
        return offlineSeconds;
    }

    /**
     * The unclamped time the island actually spent unloaded. Only differs from
     * {@link #getOfflineSeconds()} when the cap kicked in — useful for telling a player
     * "your island was away 9 days, but only 24h were simulated".
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
