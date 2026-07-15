package com.mystipixel.royalskyblock.island;

/**
 * A member's standing on an island. Higher {@link #weight()} = more authority; compare weights to
 * decide whether one member may act on another (kick, promote, change settings, ...).
 */
public enum IslandRole {

    /** The island founder. Exactly one per island. Cannot be kicked or demoted. */
    OWNER(100),

    /** Trusted co-manager. May invite/kick members and edit most settings. */
    CO_OWNER(75),

    /** A regular member. May build on the island. */
    MEMBER(50),

    /** Explicitly trusted visitor — can build but is not a roster member (coop). */
    COOP(25),

    /** Anyone else currently on the island. Cannot build by default. */
    VISITOR(0);

    private final int weight;

    IslandRole(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    /** True if this role outranks {@code other} (strictly greater authority). */
    public boolean outranks(IslandRole other) {
        return this.weight > other.weight;
    }

    /** True if this role can build on the island by default. */
    public boolean canBuild() {
        return weight >= COOP.weight;
    }
}
