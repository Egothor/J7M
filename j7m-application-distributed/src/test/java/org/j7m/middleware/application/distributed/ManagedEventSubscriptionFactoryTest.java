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
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.annotations.EventSubscription;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.ManagedHostedResources;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;

class ManagedEventSubscriptionFactoryTest {
    public static class Handler { @EventSubscription("projection") public void on(ManagedEventDelivery delivery) { } }
    public static class BatchHandler extends Handler { @Override @EventSubscription(value="projection",filter="BATCH") public void on(ManagedEventDelivery delivery) { } }
    private static final ManagedMethod METHOD = new ManagedMethod(Handler.class.getName(), "on", List.of(ManagedEventDelivery.class.getName()), "void");
    static ManagedHostedFeature feature(String filter) {
        var method = filter.equals("BATCH") ? new ManagedMethod(BatchHandler.class.getName(), "on", METHOD.parameterTypes(), METHOD.returnType()) : METHOD;
        return new ManagedHostedFeature("event-subscription", "projection", 1, Map.of("policy", "default", "filter", filter), List.of(method));
    }
    static ManagedHostedAdapter<?> definition(String filter) { return filter.equals("BATCH") ? BATCH_DEFINITION : DEFINITION; }
    static Handler handler(String filter) { return filter.equals("BATCH") ? new BatchHandler() : new Handler(); }
    static final ManagedHostedAdapter<Handler> DEFINITION = new ManagedHostedAdapter<>() {
        public Class<Handler> type() { return Handler.class; }
        public String name() { return ""; }
        public List<ManagedQualifier> qualifiers() { return List.of(); }
        public Scope.Kind scope() { return Scope.Kind.ACTIVATION; }
        public List<ManagedDependency> dependencies() { return List.of(); }
        public Handler create(ManagedResolver resolver) { return new Handler(); }
        public List<ManagedHostedFeature> hostedFeatures() { return List.of(feature("")); }
        public Object invokeHosted(Handler instance, ManagedMethod method, Object[] arguments) { instance.on((ManagedEventDelivery) arguments[0]); return null; }
    };
    static final ManagedHostedAdapter<BatchHandler> BATCH_DEFINITION = new ManagedHostedAdapter<>() {
        public Class<BatchHandler> type() { return BatchHandler.class; }
        public String name() { return ""; }
        public List<ManagedQualifier> qualifiers() { return List.of(); }
        public Scope.Kind scope() { return Scope.Kind.ACTIVATION; }
        public List<ManagedDependency> dependencies() { return List.of(); }
        public BatchHandler create(ManagedResolver resolver) { return new BatchHandler(); }
        public List<ManagedHostedFeature> hostedFeatures() { return List.of(feature("BATCH")); }
        public Object invokeHosted(BatchHandler instance, ManagedMethod method, Object[] arguments) { instance.on((ManagedEventDelivery) arguments[0]); return null; }
    };
    static final ManagedHostedResources.Bundle BUNDLE = new ManagedHostedResources.Bundle("a".repeat(64), Map.of());
    private static EventSubscriptionProfile profile(Endpoint source, Endpoint metadata, EventSubscriptionProfile.Expiry expiry) {
        return new EventSubscriptionProfile(source, metadata, "source", Duration.ofSeconds(3), Duration.ofMillis(2),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 8, 1024, EventSubscriptionProfile.Restart.RESUME, expiry);
    }
    private static ManagedHostedResources.Invoker receive(BlockingQueue<ManagedEventDelivery> deliveries) {
        return measured((method, decode, options) -> {
            try { deliveries.add((ManagedEventDelivery) decode.call()[0]); return CompletableFuture.completedFuture(null); }
            catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
        });
    }
    static ManagedHostedResources.Invoker measured(ManagedHostedResources.Invoker delegate) {
        return new ManagedHostedResources.Invoker() {
            public java.util.concurrent.CompletionStage<Object> invokeDecoded(ManagedMethod method, java.util.concurrent.Callable<Object[]> decoder, org.j7m.middleware.api.InvocationOptions options) { return delegate.invokeDecoded(method,decoder,options); }
            public java.util.concurrent.CompletionStage<Object> invokeDecoded(ManagedMethod method, java.util.concurrent.Callable<Object[]> decoder, org.j7m.middleware.api.InvocationOptions options, int bytes) { assertTrue(bytes >= 0); return delegate.invokeDecoded(method,decoder,options); }
        };
    }
    private static ManagedEventDelivery take(BlockingQueue<ManagedEventDelivery> queue) throws Exception {
        var value = queue.poll(3, TimeUnit.SECONDS); assertNotNull(value, "Bounded delivery deadline"); return value;
    }
    private static void eventually(java.util.function.BooleanSupplier condition) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) fail("Bounded state transition deadline");
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }
    @Test void lostAckRetriesSameIdentityWithoutRepeatingHandlerAndRestartResumesDurableCursor() throws Exception {
        var source = new Endpoint(); var metadata = new Endpoint(); var deliveries = new LinkedBlockingQueue<ManagedEventDelivery>();
        try (var factory = new ManagedEventSubscriptionFactory(Map.of("default", profile(source, metadata, EventSubscriptionProfile.Expiry.FAIL)), 1, 1)) {
            var binding = factory.bind(feature(""), DEFINITION, new Handler(), BUNDLE, receive(deliveries));
            binding.prepare(); assertEquals(ManagedEventSubscriptionFactory.Phase.PREPARED, factory.status("source", "projection").phase());
            binding.start();
            assertEquals(ManagedEventSubscriptionFactory.Phase.ARMED, factory.status("source", "projection").phase());
            assertTrue(deliveries.isEmpty()); assertFalse(factory.status("source", "projection").working());
            binding.commitAdmission(); assertEquals(ManagedEventDelivery.Kind.SNAPSHOT, take(deliveries).kind());
            eventually(() -> factory.status("source", "projection").cursor() != null);
            source.loseAck.set(true); source.append("BATCH");
            var event = take(deliveries); assertEquals(ManagedEventDelivery.Kind.EVENT, event.kind());
            eventually(() -> factory.status("source", "projection").cursor().sequence() == 1);
            assertEquals(1, source.ackEffects); assertTrue(source.ackAttempts >= 2);
            assertTrue(binding.drain(Duration.ofSeconds(1))); binding.close(); assertTrue(deliveries.isEmpty());
            var restarted = factory.bind(feature(""), DEFINITION, new Handler(), BUNDLE, receive(deliveries));
            restarted.prepare(); restarted.start(); restarted.commitAdmission();
            eventually(() -> factory.status("source", "projection").cursor() != null);
            source.append("BATCH"); assertEquals(2, take(deliveries).cursor().sequence());
            assertTrue(restarted.drain(Duration.ofSeconds(1))); restarted.close();
            assertThrows(IllegalArgumentException.class, () -> factory.status("source", "projection"));
        }
    }
    @Test void actualAsyncWorkPreventsAckAndDrainUntilPhysicalStageExits() throws Exception {
        var source = new Endpoint(); var metadata = new Endpoint(); var deliveries = new LinkedBlockingQueue<ManagedEventDelivery>();
        var held = new CompletableFuture<Object>();
        try (var factory = new ManagedEventSubscriptionFactory(Map.of("default", profile(source, metadata, EventSubscriptionProfile.Expiry.FAIL)), 1, 1)) {
            var binding = factory.bind(feature(""), DEFINITION, new Handler(), BUNDLE, measured((method, decode, options) -> {
                try { var delivery = (ManagedEventDelivery) decode.call()[0]; deliveries.add(delivery);
                    return delivery.kind() == ManagedEventDelivery.Kind.SNAPSHOT ? CompletableFuture.completedFuture(null) : held;
                } catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
            }));
            binding.prepare(); binding.start(); binding.commitAdmission(); take(deliveries);
            eventually(() -> factory.status("source", "projection").cursor() != null);
            source.append("BATCH"); take(deliveries);
            binding.stopAdmission();
            assertEquals(ManagedEventSubscriptionFactory.Phase.DRAINING, factory.status("source", "projection").phase());
            assertFalse(binding.drain(Duration.ofMillis(10))); assertEquals(0, source.ackEffects);
            assertThrows(IllegalStateException.class, binding::close); assertThrows(IllegalStateException.class, factory::close);
            held.complete(null); assertTrue(binding.drain(Duration.ofSeconds(1))); assertEquals(0, source.ackEffects);
            binding.close();
        }
    }
    @Test void changedFilterRejectsBeforeHandlerAndExplicitExpiryPoliciesNeverSilentlyResume() throws Exception {
        for (var expiry : EventSubscriptionProfile.Expiry.values()) {
            var source = new Endpoint(); var metadata = new Endpoint(); var deliveries = new LinkedBlockingQueue<ManagedEventDelivery>();
            try (var factory = new ManagedEventSubscriptionFactory(Map.of("default", profile(source, metadata, expiry)), 1, 1)) {
                var binding = factory.bind(feature(""), DEFINITION, new Handler(), BUNDLE, receive(deliveries));
                binding.prepare(); binding.start(); binding.commitAdmission(); take(deliveries);
                eventually(() -> factory.status("source", "projection").cursor() != null);
                assertTrue(binding.drain(Duration.ofSeconds(1))); binding.close();
                var changed = factory.bind(feature("BATCH"), definition("BATCH"), handler("BATCH"), BUNDLE, receive(deliveries));
                assertThrows(RuntimeException.class, changed::prepare); changed.close();
                source.time = 10000;
                var restarted = factory.bind(feature(""), DEFINITION, new Handler(), BUNDLE, receive(deliveries));
                restarted.prepare(); restarted.start(); restarted.commitAdmission();
                if (expiry == EventSubscriptionProfile.Expiry.FAIL) {
                    eventually(() -> factory.status("source", "projection").phase() == ManagedEventSubscriptionFactory.Phase.FAILED);
                    assertEquals(ManagedEventSubscriptionFactory.Reason.SUBSCRIPTION_EXPIRED, factory.status("source", "projection").reason());
                    assertTrue(deliveries.isEmpty());
                } else assertEquals(ManagedEventDelivery.Kind.SNAPSHOT, take(deliveries).kind());
                assertTrue(restarted.drain(Duration.ofSeconds(1))); restarted.close();
            }
        }
    }
    @Test void missingProfilesDuplicateBindingsAndHandlerFailureDoNotClaimProgress() throws Exception {
        var source = new Endpoint(); var metadata = new Endpoint();
        try (var missing = new ManagedEventSubscriptionFactory(Map.of(), 1, 1)) {
            assertThrows(IllegalArgumentException.class, () -> missing.validate(feature(""), DEFINITION, BUNDLE));
        }
        try (var factory = new ManagedEventSubscriptionFactory(Map.of("default", profile(source, metadata, EventSubscriptionProfile.Expiry.FAIL)), 1, 1)) {
            assertThrows(IllegalArgumentException.class, () -> factory.validate(new ManagedHostedFeature("event-subscription", "projection", 2,
                    feature("").attributes(), feature("").methods()), DEFINITION, BUNDLE));
            assertThrows(IllegalArgumentException.class, () -> factory.validate(new ManagedHostedFeature("event-subscription", "projection", 1,
                    Map.of("policy", "default", "filter", "BATCH"), feature("").methods()), DEFINITION, BUNDLE));
            assertThrows(IllegalArgumentException.class, () -> factory.bind(feature(""), DEFINITION, new Object(), BUNDLE, receive(new LinkedBlockingQueue<>())));
            var binding = factory.bind(feature(""), DEFINITION, new Handler(), BUNDLE,
                    measured((method, decode, options) -> CompletableFuture.failedFuture(new IllegalStateException("secret"))));
            assertThrows(IllegalArgumentException.class, () -> factory.bind(feature(""), DEFINITION, new Handler(), BUNDLE, receive(new LinkedBlockingQueue<>())));
            binding.prepare(); binding.start(); binding.commitAdmission();
            eventually(() -> factory.status("source", "projection").phase() == ManagedEventSubscriptionFactory.Phase.FAILED);
            assertEquals(ManagedEventSubscriptionFactory.Reason.HANDLER_FAILED, factory.status("source", "projection").reason());
            assertEquals(0, source.ackEffects); assertFalse(factory.status("source", "projection").toString().contains("secret"));
            assertTrue(binding.drain(Duration.ofSeconds(1))); binding.close();
        }
    }
    @Test void retainedHistoryExpiryRequiresExplicitReplacementAndFilteredPositionsAdvanceWithoutCallback() throws Exception {
        for (var expiry : EventSubscriptionProfile.Expiry.values()) {
            var source = new Endpoint(); var metadata = new Endpoint(); var deliveries = new LinkedBlockingQueue<ManagedEventDelivery>();
            try (var factory = new ManagedEventSubscriptionFactory(Map.of("default", profile(source, metadata, expiry)), 1, 1)) {
                var binding = factory.bind(feature("BATCH"), definition("BATCH"), handler("BATCH"), BUNDLE, receive(deliveries));
                binding.prepare(); binding.start(); binding.commitAdmission(); take(deliveries);
                eventually(() -> factory.status("source", "projection").cursor() != null);
                source.append("OTHER");
                eventually(() -> factory.status("source", "projection").cursor().sequence() == 1);
                assertTrue(deliveries.isEmpty(), "Filtered positions advance without application invocation");
                assertTrue(binding.drain(Duration.ofSeconds(1))); binding.close();
                source.append("BATCH"); source.append("BATCH"); source.append("BATCH");
                var restarted = factory.bind(feature("BATCH"), definition("BATCH"), handler("BATCH"), BUNDLE, receive(deliveries));
                restarted.prepare(); restarted.start(); restarted.commitAdmission();
                if (expiry == EventSubscriptionProfile.Expiry.FAIL) {
                    eventually(() -> factory.status("source", "projection").phase() == ManagedEventSubscriptionFactory.Phase.FAILED);
                    assertEquals(ManagedEventSubscriptionFactory.Reason.CURSOR_EXPIRED, factory.status("source", "projection").reason());
                    assertTrue(deliveries.isEmpty());
                } else {
                    var replacement = take(deliveries); assertEquals(ManagedEventDelivery.Kind.SNAPSHOT, replacement.kind());
                    assertEquals(4, replacement.cursor().sequence());
                }
                assertTrue(restarted.drain(Duration.ofSeconds(1))); restarted.close();
            }
        }
    }
    @Test void outOfOrderPageRejectsBeforeAnyHandlerOrAck() throws Exception {
        var source = new Endpoint(); var metadata = new Endpoint(); var deliveries = new LinkedBlockingQueue<ManagedEventDelivery>();
        try (var factory = new ManagedEventSubscriptionFactory(Map.of("default", profile(source, metadata, EventSubscriptionProfile.Expiry.FAIL)), 1, 1)) {
            var binding = factory.bind(feature(""), DEFINITION, new Handler(), BUNDLE, receive(deliveries));
            binding.prepare(); binding.start(); binding.commitAdmission(); take(deliveries);
            eventually(() -> factory.status("source", "projection").cursor() != null);
            var first = new EventJournal.Cursor("map", 0, 1); var second = new EventJournal.Cursor("map", 0, 2);
            source.faultPage = new EventJournal.Page(List.of(new EventJournal.Event(second, "tenant", "BATCH", new byte[]{1}),
                    new EventJournal.Event(first, "tenant", "BATCH", new byte[]{1})), second);
            eventually(() -> factory.status("source", "projection").phase() == ManagedEventSubscriptionFactory.Phase.FAILED);
            assertEquals(ManagedEventSubscriptionFactory.Reason.INVALID_PAGE, factory.status("source", "projection").reason());
            assertTrue(deliveries.isEmpty()); assertEquals(0, source.ackEffects);
            assertTrue(binding.drain(Duration.ofSeconds(1))); binding.close();
        }
    }
    @Test void nonVoidLogicalPayloadCannotAcknowledgeAVoidHandlerContract() throws Exception {
        var source = new Endpoint(); var metadata = new Endpoint();
        try (var factory = new ManagedEventSubscriptionFactory(Map.of("default", profile(source, metadata, EventSubscriptionProfile.Expiry.FAIL)), 1, 1)) {
            var binding = factory.bind(feature(""), DEFINITION, new Handler(), BUNDLE,
                    measured((method, decode, options) -> CompletableFuture.completedFuture("invalid logical value")));
            binding.prepare(); binding.start(); binding.commitAdmission();
            eventually(() -> factory.status("source", "projection").phase() == ManagedEventSubscriptionFactory.Phase.FAILED);
            assertEquals(ManagedEventSubscriptionFactory.Reason.HANDLER_FAILED, factory.status("source", "projection").reason());
            assertNull(factory.status("source", "projection").cursor()); assertEquals(0, source.ackEffects);
            assertTrue(binding.drain(Duration.ofSeconds(1))); binding.close();
        }
    }

    /**
     * Unit endpoint uses the actual native journal, with a deterministic logical clock and lost ACK.
     * Actual network/process and generated activation evidence is collected separately.
     */
    static final class Endpoint implements ResourceEndpoint {
        final EventJournal journal = new EventJournal(2, 262144);
        final Map<String, ReplicatedResources.Value> values = new HashMap<>();
        final Map<String, List<String>> identities = new HashMap<>();
        final Map<String, ReplicatedResources.Result> results = new HashMap<>();
        final AtomicBoolean loseAck = new AtomicBoolean();
        volatile long time = 1000;
        volatile int ackAttempts, ackEffects;
        volatile EventJournal.Page faultPage;
        long index;
        synchronized void append(String operation) { journal.append("map", 0, "tenant", operation, new byte[]{1}); }
        public synchronized ReplicatedResources.Result execute(String operation, String request, List<String> arguments, Instant deadline) throws IOException {
            if (operation.equals("ACK")) ackAttempts++;
            var identity = new ArrayList<>(arguments); identity.addFirst(operation);
            var previous = identities.putIfAbsent(request, List.copyOf(identity));
            if (previous != null) { assertEquals(previous, identity, "Unknown outcome must retain exact native request arguments"); return results.get(request); }
            String status = "OK", value = "";
            try {
                switch (operation) {
                    case "BATCH" -> {
                        var current = values.get(arguments.get(0)); long version = current == null ? 0 : current.version();
                        if (version != Long.parseLong(arguments.get(1))) status = "CONFLICT";
                        else values.put(arguments.get(0), new ReplicatedResources.Value(arguments.get(2), ++index));
                    }
                    case "SUBSCRIBE" -> journal.subscribe(arguments.get(0), "tenant", arguments.get(1), new EventJournal.Cursor(arguments.get(2), Long.parseLong(arguments.get(3)), Long.parseLong(arguments.get(4))), Long.parseLong(arguments.get(5)), time);
                    case "RESUME" -> { var cursor = journal.resume(arguments.get(0), "tenant", Long.parseLong(arguments.get(1)), time); value = cursor.partition()+":"+cursor.epoch()+":"+cursor.sequence(); }
                    case "ACK" -> { journal.ack(arguments.get(0), "tenant", new EventJournal.Cursor(arguments.get(1), Long.parseLong(arguments.get(2)), Long.parseLong(arguments.get(3))), Long.parseLong(arguments.get(4)), time); ackEffects++; }
                    default -> throw new AssertionError(operation);
                }
            } catch (IllegalStateException | IllegalArgumentException failure) { status = failure.getMessage(); }
            var result = new ReplicatedResources.Result(status, value, ++index); results.put(request, result);
            if (operation.equals("ACK") && loseAck.compareAndSet(true, false)) throw new IOException("Reply lost after durable ACK");
            return result;
        }
        public synchronized ReplicatedResources.Value read(String key, Instant deadline) { return values.get(key); }
        public ReplicatedResources.Result transaction(String tx, Instant deadline) { throw new UnsupportedOperationException(); }
        public List<String> prepared(Instant deadline) { throw new UnsupportedOperationException(); }
        public List<String> participants(String tx, Instant deadline) { throw new UnsupportedOperationException(); }
        public synchronized EventJournal.Page poll(String id, int maximum, Instant deadline) throws IOException {
            if (faultPage != null) { var page = faultPage; faultPage = null; return page; }
            try { return journal.poll(id, "tenant", maximum, time); }
            catch (IllegalStateException failure) { throw new IOException(failure.getMessage()); }
        }
        public synchronized ReplicatedResources.SnapshotTail snapshotAndTail(Instant deadline) { return new ReplicatedResources.SnapshotTail(new byte[]{1}, journal.latest("map"), index); }
    }
}
