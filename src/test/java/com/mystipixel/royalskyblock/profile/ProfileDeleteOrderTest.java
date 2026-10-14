package com.mystipixel.royalskyblock.profile;

import com.mystipixel.royalskyblock.island.IslandHostedElsewhereException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileDeleteOrderTest {

    @Test
    void anIslandHostedElsewhereLeavesTheRosterAlone() {
        List<String> steps = new ArrayList<>();
        CompletableFuture<Void> claim = new CompletableFuture<>();
        CompletableFuture<Boolean> result = ProfileManager.afterClaim(claim, () -> steps.add("strip"), () -> {
            steps.add("delete");
            return CompletableFuture.completedFuture(true);
        });
        assertTrue(steps.isEmpty(), "nothing runs before the claim answers");
        IslandHostedElsewhereException hosted = new IslandHostedElsewhereException(UUID.randomUUID(), "beta");
        claim.completeExceptionally(hosted);
        assertTrue(result.isCompletedExceptionally());
        assertTrue(steps.isEmpty());
        assertSame(hosted, IslandHostedElsewhereException.find(result.handle((ok, error) -> error).join()));
    }

    @Test
    void membersLeaveOnlyAfterTheClaimThenTheDeleteRuns() {
        List<String> steps = new ArrayList<>();
        CompletableFuture<Void> claim = new CompletableFuture<>();
        CompletableFuture<Boolean> result = ProfileManager.afterClaim(claim, () -> steps.add("strip"), () -> {
            steps.add("delete");
            return CompletableFuture.completedFuture(true);
        });
        claim.complete(null);
        assertTrue(result.join());
        assertEquals(List.of("strip", "delete"), steps);
    }
}
