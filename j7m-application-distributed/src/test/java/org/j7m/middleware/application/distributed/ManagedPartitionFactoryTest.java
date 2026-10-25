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

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.application.components.ManagedHostedResources;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.consensus.NetworkConsensusConfig;
import org.j7m.middleware.distributed.PartitionClient;
import org.j7m.middleware.distributed.PartitionServer;
import org.j7m.middleware.distributed.ReplicatedResourceClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedPartitionFactoryTest {
    @TempDir Path directory;
    private static final byte[] KEY = new byte[32];
    private ManagedPartitionProfile profile() throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        var nativeConfig = new NetworkConsensusConfig("a", Map.of("a", new InetSocketAddress("127.0.0.1", port)), Set.of("a"), directory,
                KEY, Duration.ofMillis(500), Duration.ofMillis(20), 512 * 1024, 128 * 1024 * 1024, null, "group");
        return new ManagedPartitionProfile(nativeConfig, "partition", 1, "tenant", 10,
                new InetSocketAddress("127.0.0.1", 0), Map.of("client", new PartitionServer.Binding("tenant", KEY, false, false)),
                KEY, () -> { long now = System.currentTimeMillis(); return new ReplicatedResourceClient.ClockMeasurement(now, now, 0, true); }, null);
    }
    @org.j7m.middleware.annotations.PartitionHandler(resource = "counter")
    public static class CountingCounter extends ManagedPartitionResourceTest.Counter {
        static final java.util.concurrent.atomic.AtomicInteger constructors = new java.util.concurrent.atomic.AtomicInteger();
        public CountingCounter() { constructors.incrementAndGet(); }
    }
    @org.j7m.middleware.annotations.PartitionHandler(resource = "counter")
    public static class CapturingCollaborator extends ManagedPartitionResourceTest.Counter { public final Object collaborator = new Object(); }
    public static class LifecycleParent extends ManagedPartitionResourceTest.Counter {
        @org.j7m.middleware.annotations.Initialize public void initialize() { throw new AssertionError("Pure reducer cannot start external lifecycle"); }
    }
    @org.j7m.middleware.annotations.PartitionHandler(resource = "counter")
    public static class InheritedLifecycle extends LifecycleParent { }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ManagedPartitionResourceTest.Definition definition(Class<?> type) {
        return new ManagedPartitionResourceTest.Definition() {
            @Override public Class<ManagedPartitionResourceTest.Counter> type() { return (Class) type; }
        };
    }
    @Test void malformedManualPlansAndCapturedCollaboratorsRejectBeforeComponentConstruction() throws Exception {
        CountingCounter.constructors.set(0);
        var bundle = new ManagedHostedResources.Bundle("a".repeat(64), Map.of()); var profile = profile();
        try (var factory = new ManagedPartitionFactory(Map.of("default", profile))) {
            var valid = definition(CountingCounter.class); factory.validate(valid.partition(), valid, bundle);
            var incomplete = new ManagedPartitionResourceTest.Definition() {
                @Override public org.j7m.middleware.application.api.ManagedHostedFeature partition() {
                    var original = super.partition();
                    return new org.j7m.middleware.application.api.ManagedHostedFeature(original.kind(), original.id(), original.version(), original.attributes(), List.of());
                }
            };
            assertThrows(IllegalArgumentException.class, () -> factory.validate(incomplete.partition(), incomplete, bundle));
            var mismatch = new ManagedPartitionResourceTest.Definition() { @Override public Class<?> commandType() { return String.class; } };
            assertThrows(IllegalArgumentException.class, () -> factory.validate(mismatch.partition(), mismatch, bundle));
            var captured = definition(CapturingCollaborator.class);
            assertThrows(IllegalArgumentException.class, () -> factory.validate(captured.partition(), captured, bundle));
            var lifecycle = definition(InheritedLifecycle.class);
            assertThrows(IllegalArgumentException.class, () -> factory.validate(lifecycle.partition(), lifecycle, bundle));
            var unboundedDependency = new ManagedPartitionResourceTest.Definition() {
                @Override public List<org.j7m.middleware.application.api.ManagedDependency> dependencies() {
                    return List.of(new org.j7m.middleware.application.api.ManagedDependency(org.j7m.middleware.distributed.ResourceEndpoint.class, "external", List.of(), false));
                }
            };
            assertThrows(IllegalArgumentException.class, () -> factory.validate(unboundedDependency.partition(), unboundedDependency, bundle));
            assertEquals(0, CountingCounter.constructors.get());
        }
        assertThrows(IllegalArgumentException.class, () -> new ManagedPartitionFactory(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ManagedPartitionFactory(Map.of("first", profile, "second", profile)));
    }

    @org.j7m.middleware.annotations.PartitionHandler(resource = "counter")
    public static class HeldRecoveryCounter extends ManagedPartitionResourceTest.Counter {
        static volatile boolean hold;
        static java.util.concurrent.CountDownLatch entered;
        static java.util.concurrent.CountDownLatch release;
        @Override public byte[] apply(byte[] state, byte[] command) {
            if (hold) {
                entered.countDown();
                try { assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Unexpected recovery interruption"); }
            }
            return super.apply(state, command);
        }
    }
    @Test void admissionAndBudgetedDrainDoNotWaitOnActualRecoveryCallbackOrDiscardItsProgram() throws Exception {
        var definition = definition(HeldRecoveryCounter.class); var profile = profile(); var bundle = new ManagedHostedResources.Bundle("a".repeat(64), Map.of());
        var schemas = new SchemaRegistry(List.of()); var codec = new PayloadFrameEngine(new DefaultValueCodec(schemas), schemas, ManagedPartitionResource.MAX_COMMAND_BYTES);
        String fp = ManagedPartitionFingerprint.compute(bundle, definition, schemas, "tenant", 10);
        HeldRecoveryCounter.hold = false;
        try (var factory = new ManagedPartitionFactory(Map.of("default", profile))) {
            var binding = (ManagedPartitionFactory.HostedPartition) factory.bind(definition.partition(), definition, new HeldRecoveryCounter(), bundle,
                    (method, decoder, options) -> { throw new AssertionError("Pure recovery cannot invoke effects"); });
            binding.prepare(); binding.start(); binding.commitAdmission();
            var client = new PartitionClient(List.of(binding.address()), "client", KEY);
            byte[] command = ManagedPartitionResource.command(fp, binding.groupId(), "tenant", "recovery", "key", codec.encode(new byte[]{1}, byte[].class));
            byte[] query = ManagedPartitionResource.query(fp, binding.groupId(), "tenant", "recovery", new byte[0]);
            assertEquals("OK", client.execute(1, ManagedPartitionResource.requestId("tenant", "recovery"), command, query, Instant.now().plusSeconds(5)).status());
            assertTrue(binding.drain(Duration.ofSeconds(3))); binding.close();
        }
        HeldRecoveryCounter.entered = new java.util.concurrent.CountDownLatch(1); HeldRecoveryCounter.release = new java.util.concurrent.CountDownLatch(1); HeldRecoveryCounter.hold = true;
        try (var factory = new ManagedPartitionFactory(Map.of("default", profile)); var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var binding = (ManagedPartitionFactory.HostedPartition) factory.bind(definition.partition(), definition, new HeldRecoveryCounter(), bundle,
                    (method, decoder, options) -> { throw new AssertionError("Pure recovery cannot invoke effects"); });
            var preparation = workers.submit(() -> { binding.prepare(); return null; });
            try {
                assertTrue(HeldRecoveryCounter.entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                var control = workers.submit(() -> { assertTrue(factory.addresses().isEmpty()); binding.stopAdmission(); return binding.drain(Duration.ofMillis(10)); });
                assertFalse(control.get(250, java.util.concurrent.TimeUnit.MILLISECONDS), "Actual recovery keeps the program, but must not hold host control admission");
            } finally { HeldRecoveryCounter.release.countDown(); HeldRecoveryCounter.hold = false; }
            preparation.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, binding::start);
            assertTrue(binding.drain(Duration.ofSeconds(3))); binding.close();
        } finally { HeldRecoveryCounter.hold = false; }
    }

    @org.j7m.middleware.annotations.PartitionHandler(resource = "counter")
    public static class HeldCounter extends ManagedPartitionResourceTest.Counter {
        static java.util.concurrent.CountDownLatch entered;
        static java.util.concurrent.CountDownLatch release;
        static java.util.concurrent.CountDownLatch exited;
        @Override public byte[] query(byte[] state, boolean mutate) {
            entered.countDown();
            try {
                assertFalse(Thread.currentThread().isInterrupted());
                assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(Thread.currentThread().isInterrupted(), "Native shutdown cannot interrupt committed application work");
                return super.query(state, mutate);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Unexpected worker interruption"); }
            finally { exited.countDown(); }
        }
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void actualQueryWorkerRetainsProgramUntilItsFinallyDespiteNodeAndAdmissionShutdown() throws Exception {
        HeldCounter.entered = new java.util.concurrent.CountDownLatch(1); HeldCounter.release = new java.util.concurrent.CountDownLatch(1); HeldCounter.exited = new java.util.concurrent.CountDownLatch(1);
        var definition = new ManagedPartitionResourceTest.Definition() {
            @Override public Class<ManagedPartitionResourceTest.Counter> type() { return (Class) HeldCounter.class; }
        };
        var bundle = new ManagedHostedResources.Bundle("a".repeat(64), Map.of()); var schemas = new SchemaRegistry(List.of());
        var profile = profile(); String fp = ManagedPartitionFingerprint.compute(bundle, definition, schemas, "tenant", 10);
        try (var factory = new ManagedPartitionFactory(Map.of("default", profile))) {
            var binding = (ManagedPartitionFactory.HostedPartition) factory.bind(definition.partition(), definition, new HeldCounter(), bundle,
                    (method, decoder, options) -> { throw new AssertionError("Pure query is not a hosted effect"); });
            binding.prepare(); binding.start(); binding.commitAdmission();
            var client = new PartitionClient(List.of(binding.address()), "client", KEY); client.status(Instant.now().plusSeconds(5));
            var codec = new PayloadFrameEngine(new DefaultValueCodec(schemas), schemas, ManagedPartitionResource.MAX_COMMAND_BYTES);
            byte[] query = ManagedPartitionResource.query(fp, binding.groupId(), "tenant", "", codec.encode(false, boolean.class));
            try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                var pending = executor.submit(() -> client.query(1, query, Instant.now().plusSeconds(4)));
                try {
                    assertTrue(HeldCounter.entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                    binding.stopAdmission(); assertFalse(binding.drain(Duration.ofMillis(10)));
                    assertEquals(1, HeldCounter.exited.getCount()); assertThrows(IllegalStateException.class, binding::close);
                    assertThrows(IllegalStateException.class, factory::close);
                } finally { HeldCounter.release.countDown(); }
                assertTrue(binding.drain(Duration.ofSeconds(3))); assertEquals(0, HeldCounter.exited.getCount());
                binding.close(); pending.cancel(true);
            }
        }
    }

    @Test void actualNativeEndpointHonorsBothStartupGuardsAndDurableRestartBeforeDisposal() throws Exception {
        var definition = ManagedPartitionResourceTest.definition(); var bundle = new ManagedHostedResources.Bundle("a".repeat(64), Map.of());
        var profile = profile(); var shared = new java.util.concurrent.atomic.AtomicBoolean();
        var invoker = new ManagedHostedResources.Invoker() {
            @Override public boolean admissionOpen() { return shared.get(); }
            @Override public java.util.concurrent.CompletionStage<Object> invokeDecoded(org.j7m.middleware.application.api.ManagedMethod method,
                    java.util.concurrent.Callable<Object[]> decoder, org.j7m.middleware.api.InvocationOptions options) { throw new AssertionError("Pure apply must not enter hosted effect invoker"); }
        };
        var schemas = new SchemaRegistry(List.of()); var codec = new PayloadFrameEngine(new DefaultValueCodec(schemas), schemas, ManagedPartitionResource.MAX_COMMAND_BYTES);
        String fp = ManagedPartitionFingerprint.compute(bundle, definition, schemas, "tenant", 10);
        String group;
        try (var factory = new ManagedPartitionFactory(Map.of("default", profile))) {
            assertTrue(factory.annotationTypes().contains(org.j7m.middleware.annotations.PartitionHandler.class));
            factory.validate(definition.partition(), definition, bundle);
            var binding = (ManagedPartitionFactory.HostedPartition) factory.bind(definition.partition(), definition,
                    new ManagedPartitionResourceTest.Counter(), bundle, invoker);
            assertThrows(IllegalStateException.class, factory::close);
            assertThrows(IllegalStateException.class, binding::start);
            assertTrue(factory.addresses().isEmpty());
            binding.prepare(); binding.start(); binding.commitAdmission();
            assertEquals(Map.of("counter", binding.address()), factory.addresses());
            group = binding.groupId(); assertEquals("a", binding.node().status().nodeId());
            var client = new PartitionClient(List.of(binding.address()), "client", KEY);
            var deadline = Instant.now().plusSeconds(5);
            var command = ManagedPartitionResource.command(fp, group, "tenant", "first", "key", codec.encode(new byte[]{7}, byte[].class));
            var result = ManagedPartitionResource.query(fp, group, "tenant", "first", new byte[0]);
            assertThrows(IllegalArgumentException.class, () -> client.execute(1, ManagedPartitionResource.requestId("tenant", "first"), command, result, deadline));
            shared.set(true);
            assertEquals("OK", client.execute(1, ManagedPartitionResource.requestId("tenant", "first"), command, result, deadline).status());
            assertEquals(new ManagedPartitionResource.Receipt("OK", 1, 1), ManagedPartitionResource.receipt(client.query(1, result, deadline).payload()));
            binding.stopAdmission();
            assertThrows(IllegalArgumentException.class, () -> client.query(1, result, Instant.now().plusSeconds(2)));
            assertTrue(binding.drain(Duration.ofSeconds(3))); binding.close(); binding.close(); assertTrue(factory.addresses().isEmpty());
        }
        shared.set(false);
        try (var factory = new ManagedPartitionFactory(Map.of("default", profile))) {
            var binding = (ManagedPartitionFactory.HostedPartition) factory.bind(definition.partition(), definition,
                    new ManagedPartitionResourceTest.Counter(), bundle, invoker);
            binding.prepare(); binding.start(); binding.commitAdmission(); shared.set(true);
            assertEquals(group, binding.groupId());
            var client = new PartitionClient(List.of(binding.address()), "client", KEY);
            var result = ManagedPartitionResource.query(fp, group, "tenant", "first", new byte[0]);
            assertEquals(new ManagedPartitionResource.Receipt("OK", 1, 1), ManagedPartitionResource.receipt(client.query(1, result, Instant.now().plusSeconds(5)).payload()));
            assertTrue(binding.drain(Duration.ofSeconds(3))); binding.close();
        }
    }
}
