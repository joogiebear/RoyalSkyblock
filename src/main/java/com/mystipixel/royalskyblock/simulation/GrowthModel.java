package com.mystipixel.royalskyblock.simulation;

import java.util.function.DoubleSupplier;

/**
 * How much a crop grows over a stretch of unloaded time.
 *
 * <p>Vanilla doesn't grow crops on a timer — each one waits on random ticks, so its real rate is a
 * probability. Dividing elapsed time by a fixed stage duration would advance a whole field in
 * lockstep and read as obviously fake. Instead each stage is drawn from an exponential distribution
 * with mean {@code secondsPerStage}: memoryless, like the random-tick process it stands in for, so a
 * field planted at once still ripens raggedly and a short trip away is usually worth nothing.
 *
 * <p>Pure and side-effect free, with the randomness injected, so the model can be pinned by tests
 * rather than eyeballed on a live server.
 */
final class GrowthModel {

    private GrowthModel() {
    }

    /**
     * Stages a crop advances over {@code offlineSeconds}, never more than {@code stagesRemaining}.
     *
     * @param rng supplies uniforms in [0,1) — {@code ThreadLocalRandom.current()::nextDouble} live
     */
    static int stagesGrown(long offlineSeconds, double secondsPerStage, int stagesRemaining,
                           DoubleSupplier rng) {
        if (offlineSeconds <= 0 || secondsPerStage <= 0 || stagesRemaining <= 0) {
            return 0;
        }
        double budget = offlineSeconds;
        int grown = 0;
        while (grown < stagesRemaining) {
            double u = rng.getAsDouble();
            // Guard the tail: u == 1 would make log(0) = -infinity and hang the loop on a NaN budget.
            if (u >= 1.0) {
                u = Math.nextDown(1.0);
            }
            double cost = -Math.log(1 - u) * secondsPerStage;
            if (cost > budget) {
                break;
            }
            budget -= cost;
            grown++;
        }
        return grown;
    }
}
