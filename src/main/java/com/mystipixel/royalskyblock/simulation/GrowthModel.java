package com.mystipixel.royalskyblock.simulation;

import java.util.function.DoubleSupplier;

// How much a crop grows over unloaded time. Vanilla growth is random ticks, so each stage is drawn from
// an exponential distribution with mean secondsPerStage rather than elapsed / duration, which would
// ripen a field in lockstep. Randomness is injected so tests can pin it.
final class GrowthModel {

    private GrowthModel() {
    }

    // stages a crop advances over offlineSeconds, never more than stagesRemaining;
    // rng supplies uniforms in [0,1) (ThreadLocalRandom.current()::nextDouble live)
    static int stagesGrown(long offlineSeconds, double secondsPerStage, int stagesRemaining,
                           DoubleSupplier rng) {
        if (offlineSeconds <= 0 || secondsPerStage <= 0 || stagesRemaining <= 0) {
            return 0;
        }
        double budget = offlineSeconds;
        int grown = 0;
        while (grown < stagesRemaining) {
            double u = rng.getAsDouble();
            // guard the tail: u == 1 would make log(0) = -infinity and hang the loop on a NaN budget
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
