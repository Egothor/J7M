/*
 * BSD 4-Clause License
 *
 * Copyright (c) 2026, Leo Galambos and the Egothor team
 * Website of the Egothor team: https://www.egothor.org
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. All advertising materials mentioning features or use of this
 * software must display the following acknowledgement:
 *
 * This product includes software developed by Leo Galambos and the Egothor
 * team (https://www.egothor.org).
 *
 * 4. Neither the name of the copyright holder nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.j7m.middleware.application.distributed;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.components.*;
import org.junit.jupiter.api.Test;

class ManagedTransactionCompositionTest {
    public interface Combined { @Deduplicated @ResourceTransaction String apply(@IdempotencyKey String key); }
    public static final class Implementation implements Combined { public String apply(String key) { return key; } }
    private static ManagedResourceTransactionContext resource() { return ManagedInvocationLifetime.currentContext(ManagedResourceTransactionContext.class); }
    private static ManagedDeduplicationContext dedup() { return ManagedInvocationLifetime.currentContext(ManagedDeduplicationContext.class); }
    @Test void cacheOnlyCompositionCommitsResourcesBeforeCacheAndReplayCannotStartAnotherTransaction() throws Exception {
        var resources = new ManagedResourceTransactionTest.Fixture(); var execution = new ExecutionTransactionFixture(); var contract = TransactionInvocation.contract(Combined.class);
        try (var transaction = resources.policy.bind(contract, Implementation.class, Combined.class); var cache = execution.policy.bind(contract, Implementation.class, Combined.class)) {
            var binding = TransactionInvocation.compose(cache, transaction); var calls = new AtomicInteger();
            assertEquals("cached", TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> { calls.incrementAndGet(); resource().write("left", "balance", "once"); return "cached"; }));
            long committed = resources.coordinator.index;
            assertEquals("cached", TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> fail("cached body")));
            assertEquals(1, calls.get()); assertEquals(committed, resources.coordinator.index); assertEquals("once", resources.left.read("balance", Instant.now().plusSeconds(1)).value());
        }
    }
    @Test void caughtProtectedCasMixStillRollsBackResourceTransactionAndCannotCacheSuccess() throws Exception {
        var resources = new ManagedResourceTransactionTest.Fixture(); var execution = new ExecutionTransactionFixture(); var contract = TransactionInvocation.contract(Combined.class);
        try (var transaction = resources.policy.bind(contract, Implementation.class, Combined.class); var cache = execution.policy.bind(contract, Implementation.class, Combined.class)) {
            var binding = TransactionInvocation.compose(cache, transaction);
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> {
                resource().write("left", "balance", "must-not-commit");
                assertThrows(IllegalStateException.class, () -> dedup().compareAndSet("d12-effect", 0, "must-not-commit")); return "caught";
            }));
            assertNull(resources.left.read("balance", Instant.now().plusSeconds(1))); assertTrue(resources.left.prepared(Instant.now().plusSeconds(1)).isEmpty());
            assertEquals("NOT_FOUND", execution.client.query("E_VALUE", "d12-effect", Instant.now().plusSeconds(1)).status());
        }
    }
    @Test void resourceCommitToCacheGapRemainsUnknownAndNeverRepeatsCommittedEffects() throws Exception {
        var resources = new ManagedResourceTransactionTest.Fixture(); var execution = new ExecutionTransactionFixture(); var contract = TransactionInvocation.contract(Combined.class); var calls = new AtomicInteger();
        try (var transaction = resources.policy.bind(contract, Implementation.class, Combined.class); var cache = execution.policy.bind(contract, Implementation.class, Combined.class)) {
            var binding = TransactionInvocation.compose(cache, transaction);
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> { calls.incrementAndGet(); resource().write("left", "balance", "committed"); return "x".repeat(13000); }));
            var before = resources.left.read("balance", Instant.now().plusSeconds(1)); assertEquals("committed", before.value());
            assertThrows(ExecutionException.class, () -> TransactionInvocation.invokeAs(TransactionInvocation.caller(), binding, frame -> { calls.incrementAndGet(); return "must-not-reenter"; }));
            assertEquals(1, calls.get()); assertEquals(before, resources.left.read("balance", Instant.now().plusSeconds(1)));
        }
    }
}
