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

import java.util.List;
import java.util.Map;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.application.api.ManagedDependency;
import org.j7m.middleware.application.api.ManagedHostedFeature;
import org.j7m.middleware.application.api.ManagedMethod;
import org.j7m.middleware.application.api.ManagedPartitionDefinition;
import org.j7m.middleware.application.api.ManagedQualifier;
import org.j7m.middleware.application.api.ManagedResolver;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.junit.jupiter.api.Test;

class ManagedPartitionResourceTest {
    static ManagedPartitionDefinition<?> definition() { return new Definition(); }
    @Test void receiptCannotTurnUncommittedOrInvalidMetadataIntoAuthority() {
        assertThrows(IllegalArgumentException.class, () -> new ManagedPartitionResource.Receipt("OK", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ManagedPartitionResource.Receipt("ERROR", 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ManagedPartitionResource.Receipt("NOT_FOUND", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ManagedPartitionResource.Receipt("OVERLOADED", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ManagedPartitionResource.Receipt(null, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ManagedPartitionResource.Receipt("UNKNOWN", 1, 1));
        assertEquals(0, new ManagedPartitionResource.Receipt("NOT_FOUND", 0, 0).epoch());
    }
    private static final String PROGRAM = "a".repeat(64);
    private static final SchemaRegistry SCHEMAS = new SchemaRegistry(List.of());
    private static final PayloadFrameEngine CODEC = new PayloadFrameEngine(new DefaultValueCodec(SCHEMAS), SCHEMAS, ManagedPartitionResource.MAX_COMMAND_BYTES);
    @org.j7m.middleware.annotations.PartitionHandler(resource = "counter")
    public static class Counter {
        public byte[] initial() { return new byte[]{0}; }
        public byte[] apply(byte[] state, byte[] command) {
            state[0] += command[0];
            if (command[0] < 0) throw new IllegalStateException("SYNTHETIC_REDUCER_DETAIL");
            return state;
        }
        public byte[] query(byte[] state, boolean mutate) { if (mutate) state[0] = 99; return state; }
    }
    private static ManagedPartitionResource resource(String fingerprint, int capacity) {
        return new ManagedPartitionResource(new Definition(), new Counter(), new DefaultValueCodec(SCHEMAS), SCHEMAS, fingerprint, "group", "tenant", capacity);
    }
    private static byte[] command(String request, int value) {
        return ManagedPartitionResource.command(PROGRAM, "group", "tenant", request, "key", CODEC.encode(new byte[]{(byte) value}, byte[].class));
    }
    private static byte[] query(boolean mutate) { return ManagedPartitionResource.query(PROGRAM, "group", "tenant", "", CODEC.encode(mutate, boolean.class)); }
    private static ManagedPartitionResource.Receipt result(ManagedPartitionResource resource, String request) {
        return ManagedPartitionResource.receipt(resource.query(ManagedPartitionResource.query(PROGRAM, "group", "tenant", request, new byte[0])));
    }
    private static int current(ManagedPartitionResource resource) { return ((byte[]) CODEC.decode(resource.query(query(false)), byte[].class))[0]; }

    @Test void sharedAdmissionAndActualQueryFinallyProtectProgramLifetimeWithoutBlockingHostStatus() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var definition = new Definition() {
            @Override public Object query(Counter instance, Object state, Object query) throws Exception {
                entered.countDown(); assertTrue(release.await(2, java.util.concurrent.TimeUnit.SECONDS));
                return super.query(instance, state, query);
            }
        };
        var resource = new ManagedPartitionResource(definition, new Counter(), new DefaultValueCodec(SCHEMAS), SCHEMAS, PROGRAM, "group", "tenant", 2);
        var shared = new java.util.concurrent.atomic.AtomicBoolean();
        resource.bindAdmission(shared::get); resource.startAdmission();
        assertThrows(IllegalStateException.class, () -> resource.validate(command("first", 1), "tenant", false));
        shared.set(true); resource.validate(command("first", 1), "tenant", false); resource.ownershipEpoch(1);
        resource.apply(1, command("first", 1));
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var query = executor.submit(() -> current(resource));
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                resource.stopAdmission();
                var drained = resource.closeAfterNodeExit();
                assertEquals(1, resource.activeWork());
                assertFalse(drained.toCompletableFuture().isDone());
            } finally { release.countDown(); }
            assertEquals(1, query.get(2, java.util.concurrent.TimeUnit.SECONDS));
            resource.closeAfterNodeExit().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(0, resource.activeWork());
        assertThrows(IllegalStateException.class, () -> resource.query(query(false)));
        assertThrows(IllegalStateException.class, resource::startAdmission);
    }

    @Test void pureCallbacksAndSchemaWorkCannotObserveCallerSecurityInvocationOrTrace() {
        var definition = new Definition() {
            @Override public Object query(Counter instance, Object state, Object query) throws Exception {
                assertNull(org.j7m.middleware.security.api.SecurityContexts.current());
                assertEquals(org.j7m.middleware.application.api.InvocationContext.empty().security(),
                        org.j7m.middleware.application.api.J7mContext.currentInvocation().security());
                assertTrue(org.j7m.middleware.application.api.InvocationTraceContext.current().isEmpty());
                assertEquals("", org.j7m.middleware.application.api.J7mContext.currentInvocation().correlationId());
                assertSame(ManagedPartitionResource.class.getClassLoader(), Thread.currentThread().getContextClassLoader());
                return super.query(instance, state, query);
            }
        };
        var resource = new ManagedPartitionResource(definition, new Counter(), new DefaultValueCodec(SCHEMAS), SCHEMAS, PROGRAM, "group", "tenant", 2);
        var caller = org.j7m.middleware.security.api.SecurityContext.system("host");
        var context = new org.j7m.middleware.application.api.InvocationContext(null, null, "caller-correlation");
        var trace = Map.of("traceparent", "00-01234567890123456789012345678901-0123456789012345-01");
        var thread = Thread.currentThread(); var previous = thread.getContextClassLoader();
        var callerLoader = new ClassLoader(previous) { }; thread.setContextClassLoader(callerLoader);
        try (var application = org.j7m.middleware.application.api.J7mContext.open(context);
                var tracing = org.j7m.middleware.application.api.InvocationTraceContext.open(trace)) {
            org.j7m.middleware.security.api.SecurityContexts.call(caller, () -> {
                assertEquals(0, current(resource));
                assertSame(caller, org.j7m.middleware.security.api.SecurityContexts.current());
                assertSame(context, org.j7m.middleware.application.api.J7mContext.currentInvocation());
                assertEquals(trace, org.j7m.middleware.application.api.InvocationTraceContext.current());
                assertSame(callerLoader, thread.getContextClassLoader());
                return null;
            });
        } finally { thread.setContextClassLoader(previous); }
    }

    @Test void applicationErrorsLeaveSafeRuntimeFailureAndActualWorkFinallyWithoutRawCauses() {
        var definition = new Definition() {
            @Override public Object query(Counter instance, Object state, Object query) { throw new AssertionError("SYNTHETIC_PRIVATE_DETAIL"); }
        };
        var resource = new ManagedPartitionResource(definition, new Counter(), new DefaultValueCodec(SCHEMAS), SCHEMAS, PROGRAM, "group", "tenant", 2);
        byte[] before = resource.snapshot();
        var failure = assertThrows(IllegalStateException.class, () -> resource.query(query(false)));
        assertEquals("Managed partition program failed", failure.getMessage()); assertNull(failure.getCause());
        assertEquals(0, resource.activeWork()); assertArrayEquals(before, resource.snapshot());
    }

    enum UnsupportedState { VALUE }
    @org.j7m.middleware.annotations.SchemaType(id = "unsupported", version = 1)
    public record UnsupportedContainer(@org.j7m.middleware.annotations.WireField(id = 1) java.util.List<String> values) { }
    @Test void replicatedDataProfileDoesNotSilentlyAdoptBroaderA08ContainersEnumsOrMalformedSchemas() {
        ManagedPartitionResource.validateData(new Definition(), SCHEMAS);
        var general = new PayloadFrameEngine(new DefaultValueCodec(SCHEMAS), SCHEMAS, 12288);
        general.validateDeclaredType(java.util.List.class); general.validateDeclaredType(UnsupportedState.class);
        for (Class<?> unsupported : List.of(java.util.List.class, java.util.Map.class, java.time.Instant.class, java.nio.file.Path.class, UnsupportedState.class)) {
            var definition = new Definition() { @Override public Class<?> commandType() { return unsupported; } };
            assertThrows(IllegalArgumentException.class, () -> ManagedPartitionResource.validateData(definition, SCHEMAS));
        }
        var factories = new java.util.concurrent.atomic.AtomicInteger();
        var schemas = new SchemaRegistry(List.of(new org.j7m.middleware.codec.api.SchemaDescriptor<>("unsupported", 1, UnsupportedContainer.class,
                List.of(new org.j7m.middleware.codec.api.SchemaField("1", java.util.List.class, true, null)), value -> Map.of("1", value.values()),
                fields -> { factories.incrementAndGet(); return new UnsupportedContainer(List.of()); })));
        var definition = new Definition() { @Override public Class<?> commandType() { return UnsupportedContainer.class; } };
        assertThrows(IllegalArgumentException.class, () -> ManagedPartitionResource.validateData(definition, schemas));
        assertEquals(0, factories.get());
    }

    @Test void fatalVmFailureIsNotRecordedAsOrdinaryBusinessErrorAndFinallyStillRuns() {
        var fatal = new OutOfMemoryError("SYNTHETIC_VM_FAILURE");
        var definition = new Definition() { @Override public Object query(Counter instance, Object state, Object query) { throw fatal; } };
        var resource = new ManagedPartitionResource(definition, new Counter(), new DefaultValueCodec(SCHEMAS), SCHEMAS, PROGRAM, "group", "tenant", 2);
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> resource.query(query(false))));
        assertEquals(0, resource.activeWork());
    }

    @Test void liveGroupGuardCannotBeBypassedByPortableReplayAndRoutingIsNotBusinessIdentity() {
        var source = resource(PROGRAM, 4); source.ownershipEpoch(1);
        byte[] first = command("first", 5); source.apply(1, first);
        var target = new ManagedPartitionResource(new Definition(), new Counter(), new DefaultValueCodec(SCHEMAS),
                SCHEMAS, PROGRAM, "target", "tenant", 4);
        target.restore(source.snapshot()); target.ownershipEpoch(2);
        assertThrows(SecurityException.class, () -> target.validate(first, "tenant", false));
        assertThrows(SecurityException.class, () -> target.apply(2, first));
        assertThrows(SecurityException.class, () -> target.validateQuery(query(false), "tenant"));
        // Native authenticated handoff validates nested source replay on the live resource,
        // but performs it only against the independently staged transfer copy.
        target.validateReplayCommand(first);
        var candidate = (ManagedPartitionResource) target.copyForTransfer();
        candidate.apply(2, first);
        target.restore(candidate.snapshot());
        byte[] rerouted = ManagedPartitionResource.command(PROGRAM, "target", "tenant", "first", "key",
                CODEC.encode(new byte[]{5}, byte[].class));
        assertFalse(target.identityConflict(rerouted)); target.validate(rerouted, "tenant", false);
        target.apply(3, rerouted);
        byte[] read = ManagedPartitionResource.query(PROGRAM, "target", "tenant", "", CODEC.encode(false, boolean.class));
        assertEquals(5, ((byte[]) CODEC.decode(target.query(read), byte[].class))[0]);
        var receipt = ManagedPartitionResource.receipt(target.query(ManagedPartitionResource.query(PROGRAM, "target", "tenant", "first", new byte[0])));
        assertEquals(new ManagedPartitionResource.Receipt("OK", 1, 1), receipt);
        assertThrows(SecurityException.class, () -> target.validate(ManagedPartitionResource.command(PROGRAM, "target", "another", "first", "key", CODEC.encode(new byte[]{5}, byte[].class)), "another", false));
        var wrongScope = new ManagedPartitionResource(new Definition(), new Counter(), new DefaultValueCodec(SCHEMAS),
                SCHEMAS, PROGRAM, "target", "another", 4);
        assertThrows(IllegalArgumentException.class, () -> wrongScope.restore(source.snapshot()));
    }

    @Test void retriesRetainFirstCommittedTicketAndHistoricalEpochAcrossHandoffAndRestart() {
        var source = resource(PROGRAM, 4); source.ownershipEpoch(1);
        byte[] first = command("first", 5);
        source.validate(first, "tenant", false);
        assertEquals(ManagedPartitionResource.requestId("tenant", "first"), source.requestIdentity(first));
        source.apply(1, first); source.apply(2, first);
        assertEquals(5, current(source));
        assertEquals(new ManagedPartitionResource.Receipt("OK", 1, 1), result(source, "first"));
        assertFalse(source.identityConflict(first)); assertTrue(source.identityConflict(command("first", 7)));
        var destination = resource(PROGRAM, 4); destination.restore(source.snapshot()); destination.ownershipEpoch(2);
        destination.apply(3, first);
        assertEquals(5, current(destination));
        assertEquals(new ManagedPartitionResource.Receipt("OK", 1, 1), result(destination, "first"));
        destination.apply(4, command("second", 3));
        assertEquals(new ManagedPartitionResource.Receipt("OK", 4, 2), result(destination, "second"));
        assertEquals(8, current(destination)); assertEquals(5, current(source));
        var restarted = resource(PROGRAM, 4); restarted.restore(destination.snapshot());
        assertEquals(result(destination, "second"), result(restarted, "second")); assertEquals(8, current(restarted));
    }

    @Test void queryMutationAndFailedReducerCannotPublishChangesAndTransferCopyIsDeepIndependent() {
        var source = resource(PROGRAM, 4); source.ownershipEpoch(1); source.apply(1, command("first", 5));
        assertEquals(99, ((byte[]) CODEC.decode(source.query(query(true)), byte[].class))[0]);
        assertEquals(5, current(source));
        source.apply(2, command("rejected", -2));
        assertEquals(5, current(source)); assertEquals("ERROR", result(source, "rejected").status());
        var candidate = (ManagedPartitionResource) source.copyForTransfer(); candidate.ownershipEpoch(2);
        candidate.apply(3, command("candidate", 2));
        assertEquals(7, current(candidate)); assertEquals(5, current(source));
        assertEquals("NOT_FOUND", result(source, "candidate").status()); assertTrue(source.transferReady());
    }

    @Test void completeJournalIsBoundedWithoutEvictionAndInvalidFramesNeverMutateLiveState() {
        var resource = resource(PROGRAM, 1); resource.ownershipEpoch(1); resource.apply(1, command("first", 5));
        resource.apply(2, command("excess", 10));
        assertEquals(5, current(resource)); assertEquals("OVERLOADED", result(resource, "excess").status());
        assertEquals(1, result(resource, "first").sequence());
        byte[] before = resource.snapshot();
        assertThrows(SecurityException.class, () -> resource.validate(command("first", 5), "another-tenant", false));
        assertThrows(SecurityException.class, () -> resource.validateQuery(query(false), "another-tenant"));
        var wrong = resource("b".repeat(64), 1); wrong.ownershipEpoch(1);
        assertThrows(IllegalArgumentException.class, () -> wrong.restore(before));
        byte[] corrupt = before.clone(); corrupt[corrupt.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> resource.validateSnapshot(corrupt));
        assertThrows(IllegalArgumentException.class, () -> resource.restore(corrupt));
        assertArrayEquals(before, resource.snapshot());
        assertThrows(IllegalArgumentException.class, () -> resource(PROGRAM, 0));
        assertThrows(IllegalArgumentException.class, () -> resource(PROGRAM, 10001));
        assertThrows(IllegalArgumentException.class, () -> resource("unverified", 1));
        assertThrows(IllegalArgumentException.class, () -> resource.ownershipEpoch(0));
        assertThrows(IllegalArgumentException.class, () -> ManagedPartitionResource.command(PROGRAM, "group", "tenant", "request", "key", new byte[ManagedPartitionResource.MAX_COMMAND_BYTES]));
        resource.validateReplayCommand(command("first", 5));
        resource.validateSnapshot(before);
    }

    static class Definition implements ManagedPartitionDefinition<Counter> {
        @Override public Class<Counter> type() { return Counter.class; }
        @Override public String name() { return ""; }
        @Override public List<ManagedQualifier> qualifiers() { return List.of(); }
        @Override public Scope.Kind scope() { return Scope.Kind.ACTIVATION; }
        @Override public List<ManagedDependency> dependencies() { return List.of(); }
        @Override public Counter create(ManagedResolver resolver) { return new Counter(); }
        @Override public ManagedHostedFeature partition() {
            var methods = java.util.Arrays.stream(type().getMethods()).filter(method -> List.of("initial", "apply", "query").contains(method.getName()))
                    .map(method -> new ManagedMethod(method.getDeclaringClass().getName(), method.getName(),
                            java.util.Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(), method.getReturnType().getName())).toList();
            return new ManagedHostedFeature("partition", "counter", 1, Map.of("policy", "default"), methods);
        }
        @Override public List<ManagedHostedFeature> hostedFeatures() { return List.of(partition()); }
        @Override public Object invokeHosted(Counter instance, ManagedMethod method, Object[] arguments) { throw new UnsupportedOperationException(); }
        @Override public Class<?> stateType() { return byte[].class; }
        @Override public Class<?> commandType() { return byte[].class; }
        @Override public Class<?> queryType() { return boolean.class; }
        @Override public Class<?> viewType() { return byte[].class; }
        @Override public Object initial(Counter instance) { return instance.initial(); }
        @Override public Object apply(Counter instance, Object state, Object command) { return instance.apply((byte[]) state, (byte[]) command); }
        @Override public Object query(Counter instance, Object state, Object query) throws Exception { return instance.query((byte[]) state, (boolean) query); }
    }
}
