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
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.codec.api.*;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.security.api.*;
import org.j7m.middleware.transport.api.*;
import org.junit.jupiter.api.Test;

class ManagedDeduplicationPolicyTest {
    public interface Service { @Deduplicated String apply(@IdempotencyKey String key, String input); }
    public static final class Implementation implements Service { public String apply(String key, String input) { return input; } }
    public interface AsyncService { @Deduplicated CompletionStage<String> apply(@IdempotencyKey String key, String input); }
    public static final class AsyncImplementation implements AsyncService { public CompletionStage<String> apply(String key, String input) { return CompletableFuture.completedFuture(input); } }
    public interface Lists { @Deduplicated List<Object> apply(@IdempotencyKey String key, String input); }
    public static final class ListImplementation implements Lists { public List<Object> apply(String key, String input) { return List.of(input); } }
    public interface Missing { @Deduplicated String apply(String key); }
    public interface Wrong { @Deduplicated String apply(@IdempotencyKey int key); }
    public interface Unbound { String apply(@IdempotencyKey String key); }
    public interface Unsupported { @Deduplicated Runnable apply(@IdempotencyKey String key); }
    public interface Fire { @Deduplicated @OneWay void apply(@IdempotencyKey String key); }
    private static final class Endpoint implements PartitionEndpoint {
        final ExecutionStateMachine machine = new ExecutionStateMachine("execution"); long index;
        boolean cancelAfterClaim; ManagedInvocationLifetime active;
        Endpoint() { machine.apply(++index, machine.timeCommand(100)); }
        @Override public synchronized PartitionReply execute(long epoch, String id, byte[] command, byte[] query, Instant deadline) {
            machine.apply(++index, command);
            if (cancelAfterClaim && ResourceCommand.decode(command).operation().equals("R_CLAIM")) active.requestCancellation();
            return new PartitionReply("OK", epoch, machine.query(query));
        }
        @Override public synchronized PartitionReply query(long epoch, byte[] query, Instant deadline) { return new PartitionReply("OK", epoch, machine.query(query)); }
        @Override public PartitionStateMachine.Status status(Instant deadline) { throw new UnsupportedOperationException(); }
    }
    private static final class Fixture {
        final Endpoint endpoint = new Endpoint();
        final ExecutionClient execution = new ExecutionClient("execution", "tenant", (partition, deadline) -> new ReplicatedQueue.Route(endpoint, 1));
        final SchemaRegistry schemas = new SchemaRegistry(List.of());
        Callable<Void> afterDurable;
        final ManagedPayloadCodec codec;
        final ManagedDeduplicationPolicy policy;
        Fixture() { this(false); }
        Fixture(boolean cancelDuringResultEncoding) {
            var delegate = new DefaultValueCodec(schemas);
            codec = new ManagedPayloadCodec(new ValueCodec() {
                @Override public EncodedValue encode(Object value, Class<?> type, CodecContext context) {
                    if (cancelDuringResultEncoding && "cancel-in-codec".equals(value)) {
                        assertFalse(ManagedInvocationLifetime.currentUserWorkOpen());
                        var staged = ManagedInvocationLifetime.currentContext(ManagedDeduplicationContext.class);
                        assertThrows(IllegalStateException.class, () -> staged.compareAndSet("codec-effect", 0, "forbidden"));
                        endpoint.active.requestCancellation();
                    }
                    return delegate.encode(value, type, context);
                }
                @Override public Object decode(EncodedValue value, Class<?> type, CodecContext context) { return delegate.decode(value, type, context); }
            }, schemas);
            policy = new ManagedDeduplicationPolicy(Map.of("default", new ManagedDeduplicationPolicy.Registration(new DeduplicatedRpc(execution), codec, Duration.ofSeconds(10))));
        }
    }
    private static ManagedServiceContract contract(Class<?> type) {
        var method = type.getMethods()[0]; return new ManagedServiceContract("business", 1,
                new ManagedMethod(method.getDeclaringClass().getName(), method.getName(), Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(), method.getReturnType().getName()));
    }
    private static Object invoke(Fixture fixture, String principal, String key, String input, Callable<Object> body) throws Exception {
        return invoke(fixture, principal, key, input, body, Service.class, Implementation.class);
    }
    private static Object invoke(Fixture fixture, String principal, String key, String input, Callable<Object> body, Class<?> business, Class<?> implementation) throws Exception {
        try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor();
                var binding = fixture.policy.bind(contract(business), implementation, business)) {
            var original = new InvocationContext(new InvocationSecurityContext(new Principal(principal, principal, Set.of(), Set.of()), Set.of(), new TrustDomain("trusted")), null, "trace");
            var lifetime = new ManagedInvocationLifetime(container.openCall(), original, null, Duration.ofSeconds(5), executor, 8, 8, () -> { });
            fixture.endpoint.active = lifetime;
            if (fixture.afterDurable != null) lifetime.addResultFinalizer(outcome -> {
                fixture.afterDurable.call(); return CompletableFuture.completedFuture(outcome);
            });
            try {
                try {
                    lifetime.call(() -> binding.invoke(J7mContext.currentInvocation(), new Object[]{key, input}, new ManagedInvocationPolicy.Proceed() {
                        @Override public boolean supportsResultFinalizers() { return true; }
                        @Override public void addResultFinalizer(InvocationResultFinalizer callback) { lifetime.addResultFinalizer(callback); }
                        @Override public <T> void putContext(Class<T> type, T value) { lifetime.putContext(type, value); }
                        @Override public Object invoke() throws Exception { lifetime.markApplicationEntry(); return body.call(); }
                    }));
                } catch (Exception expectedLogicalFailure) { assertNotNull(expectedLogicalFailure); }
                return lifetime.logicalResult().toCompletableFuture().get(3, TimeUnit.SECONDS);
            } finally { lifetime.close(); lifetime.physicalCompletion().toCompletableFuture().get(3, TimeUnit.SECONDS); }
        }
    }
    @Test void completedOutcomeAndProtectedCasReplayWithoutReenteringBody() throws Exception {
        var fixture = new Fixture(); var calls = new AtomicInteger(); var retained = new ManagedDeduplicationContext[1];
        assertEquals("result", invoke(fixture, "alice", "one", "input", () -> {
            calls.incrementAndGet(); retained[0] = ManagedInvocationLifetime.currentContext(ManagedDeduplicationContext.class);
            assertTrue(retained[0].fence() > 0); assertEquals(64, retained[0].invocationId().length());
            retained[0].compareAndSet("balance", 0, "10"); return "result";
        }));
        assertEquals("result", invoke(fixture, "alice", "one", "input", () -> fail("body replayed")));
        assertEquals(1, calls.get());
        assertEquals("10", fixture.execution.query("E_VALUE", "balance", Instant.now().plusSeconds(1)).fields().get("value"));
        assertThrows(IllegalStateException.class, () -> retained[0].compareAndSet("late", 0, "bad"));
        assertThrows(ExecutionException.class, () -> invoke(fixture, "alice", "one", "different", () -> fail("conflicting body")));
        assertEquals("other", invoke(fixture, "bob", "one", "input", () -> "other"));
    }
    @Test void knownErrorsAreRedactedAndCachedWhileAmbiguousEffectsRemainUnknown() throws Exception {
        var fixture = new Fixture(); var calls = new AtomicInteger();
        Callable<Object> known = () -> { calls.incrementAndGet(); throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "secret password"); };
        for (int i = 0; i < 2; i++) {
            var failure = assertThrows(ExecutionException.class, () -> invoke(fixture, "alice", "known", "input", known));
            assertFalse(failure.toString().contains("secret password"));
        }
        assertEquals(1, calls.get());
        Callable<Object> uncertain = () -> { calls.incrementAndGet(); throw new IllegalStateException("unprotected effect may have happened"); };
        assertThrows(ExecutionException.class, () -> invoke(fixture, "alice", "unknown", "input", uncertain));
        assertThrows(ExecutionException.class, () -> invoke(fixture, "alice", "unknown", "input", uncertain));
        assertEquals(2, calls.get());
    }
    @Test void firstReplyAndReplayMaterializeSameDurableSnapshotOfMutableData() throws Exception {
        var fixture = new Fixture(); var calls = new AtomicInteger();
        var nested = new ArrayList<String>(List.of("before")); var shared = new ArrayList<Object>(); shared.add(nested);
        fixture.afterDurable = () -> {
            // Another legitimate invocation may mutate shared state after this call's durable decision.
            try (var container = ManagedContainer.activate(List.of()); var executor = Executors.newSingleThreadExecutor()) {
                var other = new ManagedInvocationLifetime(container.openCall(), InvocationContext.empty(), null,
                        Duration.ofSeconds(5), executor, 8, 8, () -> { });
                try {
                    other.call(() -> { assertTrue(ManagedInvocationLifetime.currentUserWorkOpen()); nested.add("later-nested"); shared.add("later"); return null; });
                    other.logicalResult().toCompletableFuture().get(3, TimeUnit.SECONDS);
                } finally { other.close(); other.physicalCompletion().toCompletableFuture().get(3, TimeUnit.SECONDS); }
            }
            return null;
        };
        Object first = invoke(fixture, "alice", "mutable", "input", () -> { calls.incrementAndGet(); return shared; }, Lists.class, ListImplementation.class);
        fixture.afterDurable = null;
        Object replay = invoke(fixture, "alice", "mutable", "input", () -> fail("body replayed"), Lists.class, ListImplementation.class);
        assertArrayEquals(fixture.codec.encode(replay, List.class), fixture.codec.encode(first, List.class));
        assertEquals(List.of(List.of("before")), first); assertEquals(1, calls.get());
        assertNotSame(shared, first); assertNotSame(nested, ((List<?>) first).getFirst());
        assertEquals(List.of(List.of("before", "later-nested"), "later"), shared);
    }
    @Test void cancellationAfterAcquiringClaimMustNotEnterBodyOrCommitProtectedEffects() throws Exception {
        var fixture = new Fixture(); fixture.endpoint.cancelAfterClaim = true; var effects = new AtomicInteger();
        assertThrows(ExecutionException.class, () -> invoke(fixture, "alice", "cancelled-claim", "input", () -> { effects.incrementAndGet(); return "too-late"; }));
        assertEquals(0, effects.get(), "Acquired claim is not permission to enter after cancellation");
    }
    @Test void cancellationDuringResultEncodingCannotBeginAtomicProtectedCompletion() throws Exception {
        var fixture = new Fixture(true);
        assertThrows(ExecutionException.class, () -> invoke(fixture, "alice", "codec-cancel", "input", () -> {
            ManagedInvocationLifetime.currentContext(ManagedDeduplicationContext.class).compareAndSet("cancelled-effect", 0, "forbidden");
            return "cancel-in-codec";
        }));
        assertEquals("NOT_FOUND", fixture.execution.query("E_VALUE", "cancelled-effect", Instant.now().plusSeconds(1)).status());
    }
    @Test void controlledAsyncChildStagesBeforeSettlementAndOnlyLogicalPayloadIsCached() throws Exception {
        var fixture = new Fixture(); var calls = new AtomicInteger();
        Callable<Object> work = () -> {
            calls.incrementAndGet(); var context = ManagedInvocationLifetime.currentContext(ManagedDeduplicationContext.class);
            return J7mContext.currentInvocation().control().submit(() -> { context.compareAndSet("async", 0, "settled"); return "child-value"; });
        };
        assertEquals("child-value", invoke(fixture, "alice", "async", "input", work, AsyncService.class, AsyncImplementation.class));
        assertEquals("child-value", invoke(fixture, "alice", "async", "input", work, AsyncService.class, AsyncImplementation.class));
        assertEquals(1, calls.get()); assertEquals("settled", fixture.execution.query("E_VALUE", "async", Instant.now().plusSeconds(1)).fields().get("value"));
    }
    @Test void expiredRetainedClaimNeverBecomesNewWorkAndKnownFailureDiscardsAllStagedCas() throws Exception {
        var fixture = new Fixture(); var calls = new AtomicInteger();
        assertEquals("value", invoke(fixture, "alice", "expiry", "input", () -> { calls.incrementAndGet(); return "value"; }));
        fixture.endpoint.machine.apply(++fixture.endpoint.index, fixture.endpoint.machine.timeCommand(10101));
        var expired = assertThrows(ExecutionException.class, () -> invoke(fixture, "alice", "expiry", "input", () -> { calls.incrementAndGet(); return "wrong"; }));
        assertEquals("EXPIRED", ((MiddlewareException) expired.getCause()).remoteError().details().get("status")); assertEquals(1, calls.get());
        assertThrows(ExecutionException.class, () -> invoke(fixture, "alice", "known-error", "input", () -> {
            ManagedInvocationLifetime.currentContext(ManagedDeduplicationContext.class).compareAndSet("discarded", 0, "sensitive");
            throw MiddlewareException.of(MiddlewareErrorCode.NOT_FOUND, "sensitive-error");
        }));
        assertEquals("NOT_FOUND", fixture.execution.query("E_VALUE", "discarded", Instant.now().plusSeconds(1)).status());
    }
    @Test void failedProtectedCasPublishesNeitherResultNorEarlierEffects() throws Exception {
        var fixture = new Fixture();
        assertEquals("seed", invoke(fixture, "alice", "seed", "input", () -> {
            ManagedInvocationLifetime.currentContext(ManagedDeduplicationContext.class).compareAndSet("existing", 0, "seed"); return "seed";
        }));
        assertThrows(ExecutionException.class, () -> invoke(fixture, "alice", "conflict", "input", () -> {
            var context = ManagedInvocationLifetime.currentContext(ManagedDeduplicationContext.class);
            context.compareAndSet("new-key", 0, "must-not-appear"); context.compareAndSet("existing", 0, "wrong"); return "uncommitted";
        }));
        assertEquals("NOT_FOUND", fixture.execution.query("E_VALUE", "new-key", Instant.now().plusSeconds(1)).status());
        assertEquals("seed", fixture.execution.query("E_VALUE", "existing", Instant.now().plusSeconds(1)).fields().get("value"));
        assertThrows(ExecutionException.class, () -> invoke(fixture, "alice", "conflict", "input", () -> fail("ambiguous claim replayed")));
    }
    @Test void contextCapacityAndNativeBoundsRejectWithoutPoisoningAcceptedEffects() throws Exception {
        var fixture = new Fixture();
        assertEquals("bounded", invoke(fixture, "alice", "bounded", "input", () -> {
            var context = ManagedInvocationLifetime.currentContext(ManagedDeduplicationContext.class);
            assertThrows(MiddlewareException.class, () -> context.compareAndSet("", 0, "x"));
            assertThrows(MiddlewareException.class, () -> context.compareAndSet("key", -1, "x"));
            assertThrows(MiddlewareException.class, () -> context.compareAndSet("key", 0, "x".repeat(16385)));
            for (int i = 0; i < 64; i++) context.compareAndSet("key-" + i, 0, "value");
            assertEquals(MiddlewareErrorCode.OVERLOADED, assertThrows(MiddlewareException.class, () -> context.compareAndSet("overflow", 0, "value")).code());
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class, () -> context.compareAndSet("key-0", 0, "duplicate")).code());
            return "bounded";
        }));
        assertEquals("value", fixture.execution.query("E_VALUE", "key-63", Instant.now().plusSeconds(1)).fields().get("value"));
    }
    @Test void invalidMetadataAndAnonymousCallerFailBeforeBody() throws Exception {
        var fixture = new Fixture();
        for (Class<?> type : List.of(Missing.class, Wrong.class, Unbound.class, Fire.class)) {
            assertThrows(IllegalArgumentException.class, () -> fixture.policy.validate(contract(type), type, type));
        }
        assertEquals(MiddlewareErrorCode.CODEC_ERROR, assertThrows(MiddlewareException.class, () -> fixture.policy.validate(contract(Unsupported.class), Unsupported.class, Unsupported.class)).code());
        assertEquals(ManagedInvocationPolicy.Phase.REPLAY, fixture.policy.phase());
        assertEquals(Set.of(Deduplicated.class, IdempotencyKey.class), fixture.policy.annotationTypes());
        assertThrows(ExecutionException.class, () -> invoke(fixture, "anonymous", "key", "input", () -> fail("anonymous body")));
        assertThrows(IllegalArgumentException.class, () -> new ManagedDeduplicationPolicy.Registration(new DeduplicatedRpc(fixture.execution), fixture.codec, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ManagedDeduplicationPolicy(Map.of("bad name", new ManagedDeduplicationPolicy.Registration(new DeduplicatedRpc(fixture.execution), fixture.codec, Duration.ofSeconds(1)))));
    }
}
