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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;

class ManagedQueueConsumerFactoryTest {
    public static class Handler { @DurableQueueConsumer(value="orders-handler", queue="orders") public void on(String value) { } }
    public static class Invalid { @DurableQueueConsumer(value="bad", queue="orders") public String on(String value) { return value; } }
    static final ManagedMethod METHOD = new ManagedMethod(Handler.class.getName(), "on", List.of(String.class.getName()), "void");
    static final ManagedHostedFeature FEATURE = new ManagedHostedFeature("queue-consumer", "orders-handler", 1, Map.of("queue", "orders", "policy", "default"), List.of(METHOD));
    static final ManagedHostedAdapter<Handler> DEFINITION = new ManagedHostedAdapter<>() {
        public Class<Handler> type() { return Handler.class; }
        public String name() { return ""; }
        public List<ManagedQualifier> qualifiers() { return List.of(); }
        public Scope.Kind scope() { return Scope.Kind.ACTIVATION; }
        public List<ManagedDependency> dependencies() { return List.of(); }
        public Handler create(ManagedResolver resolver) { return new Handler(); }
        public List<ManagedHostedFeature> hostedFeatures() { return List.of(FEATURE); }
        public Object invokeHosted(Handler instance, ManagedMethod method, Object[] values) { instance.on((String) values[0]); return null; }
    };
    static QueueConsumerProfile profile() {
        var schema = new SchemaRegistry(List.of()); var execution = new ExecutionTransactionFixture();
        var queue = new ReplicatedQueue("queue", "tenant", (p, d) -> { throw new java.io.IOException("No backend before committed admission"); });
        return new QueueConsumerProfile(queue, new DeduplicatedRpc(execution.client), new ManagedPayloadCodec(new DefaultValueCodec(schema), schema),
                "tenant", "orders", "workers", "worker", Set.of("orders"), Duration.ofSeconds(5), Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofHours(1), QueueConsumerProfile.Strategy.EXTERNAL_GUARD);
    }
    @Test void preparedLifecycleRequiresExactGuardAndReleasesWithoutOpeningNetworkBeforeAdmission() throws Exception {
        try (var factory = new ManagedQueueConsumerFactory(Map.of("default", profile()), 1)) {
            assertEquals(Set.of(factory), factory.requiredPolicies()); assertEquals(ManagedInvocationPolicy.Phase.REPLAY, factory.phase());
            assertEquals(Set.of(DurableQueueConsumer.class, DeadLetterHandler.class), factory.annotationTypes());
            var bundle = new ManagedHostedResources.Bundle("a".repeat(64), Map.of());
            factory.validate(FEATURE, DEFINITION, bundle);
            var binding = factory.bind(FEATURE, DEFINITION, new Handler(), bundle, (m, decode, options) -> { throw new AssertionError("No application before admission"); });
            assertEquals(ManagedQueueConsumerFactory.Phase.NEW, factory.status("orders", "orders-handler").phase());
            assertThrows(IllegalStateException.class, factory::close);
            binding.prepare(); binding.start(); assertEquals(ManagedQueueConsumerFactory.Phase.ARMED, factory.status("orders", "orders-handler").phase());
            assertFalse(factory.status("orders", "orders-handler").working()); assertEquals(0, factory.status("orders", "orders-handler").received());
            assertThrows(IllegalArgumentException.class, () -> factory.status("wrong", "orders-handler"));
            assertTrue(binding.drain(Duration.ZERO)); binding.close(); binding.close();
            assertThrows(IllegalArgumentException.class, () -> factory.status("orders", "orders-handler"));
        }
    }
    @Test void metadataShapeAndHostedBoundaryRejectSpoofedOrUnboundedDefinition() throws Exception {
        try (var factory = new ManagedQueueConsumerFactory(Map.of("default", profile()), 1)) {
            var bundle = new ManagedHostedResources.Bundle("a".repeat(64), Map.of());
            var changed = new ManagedHostedFeature(FEATURE.kind(), FEATURE.id(), 1, Map.of("queue", "other", "policy", "default"), List.of(METHOD));
            assertThrows(IllegalArgumentException.class, () -> factory.validate(changed, DEFINITION, bundle));
            var contract = new ManagedServiceContract("hosted.queue-consumer.orders-handler", 1, METHOD);
            factory.validateHosted(contract, Handler.class, Set.of(METHOD));
            assertThrows(IllegalArgumentException.class, () -> factory.validateHosted(new ManagedServiceContract("ordinary-rpc", 1, METHOD), Handler.class, Set.of(METHOD)));
            try (var policy = factory.bindHosted(contract, Handler.class, Set.of(METHOD))) {
                assertThrows(java.util.concurrent.ExecutionException.class, () -> TransactionInvocation.invokeAs(TransactionInvocation.caller(), policy, frame -> fail("No privately registered delivery")));
            }
            assertThrows(IllegalArgumentException.class, () -> new ManagedQueueConsumerFactory(Map.of("default", profile()), 17));
        }
    }
}
