package com.mystipixel.royalskyblock.island;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class IslandHostedElsewhereExceptionTest {

    @Test
    void isFoundInsideTheWrappersAFutureAdds() {
        IslandHostedElsewhereException hosted = new IslandHostedElsewhereException(UUID.randomUUID(), "alpha");
        CompletableFuture<Object> failed = CompletableFuture.failedFuture(hosted).thenApply(x -> x);
        Throwable seen = failed.handle((ok, error) -> error).join();
        assertSame(hosted, IslandHostedElsewhereException.find(seen));
        assertSame(hosted, IslandHostedElsewhereException.find(new CompletionException(new RuntimeException(hosted))));
        assertEquals("alpha", IslandHostedElsewhereException.find(seen).holder());
    }

    @Test
    void anyOtherFailureIsNotMistakenForIt() {
        assertNull(IslandHostedElsewhereException.find(new IllegalStateException("world save failed")));
        assertNull(IslandHostedElsewhereException.find(null));
    }
}
