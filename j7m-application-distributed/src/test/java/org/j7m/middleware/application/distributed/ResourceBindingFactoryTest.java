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
import java.time.Instant;
import java.util.*;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.application.api.ManagedDependency;
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;

class ResourceBindingFactoryTest {
    @Test void jobContextRejectsCaptureRefreshAndLookupOutsideOwnedCallback() throws Exception {
        var factory = new ResourceBindingFactory(Map.of("typed", ResourceBindingFactory.jobContext()), Set.of("default"));
        var direct = edge(ManagedJobContext.class, BindingRefresh.NEVER);
        assertThrows(IllegalArgumentException.class, () -> factory.validate(direct, Scope.Kind.ACTIVATION));
        var provider = new ManagedDependency(direct.type(), direct.name(), direct.qualifiers(), true, direct.attributes());
        factory.validate(provider, Scope.Kind.ACTIVATION);
        try (var value = factory.bind(provider)) {
            assertThrows(IllegalStateException.class, value::get);
            value.close();
            assertThrows(IllegalStateException.class, value::get);
        }
        var refreshed = new ManagedDependency(direct.type(), direct.name(), direct.qualifiers(), true,
                Map.of("resource.name", "typed", "binding.refresh", "ON_FAILURE", "binding.policy", "default"));
        assertThrows(IllegalArgumentException.class, () -> factory.validate(refreshed, Scope.Kind.ACTIVATION));
    }
    static class UncertainEndpoint implements PartitionEndpoint, AutoCloseable {
        int calls; boolean closed; ResourceCommand last;
        @Override public PartitionReply execute(long epoch, String request, byte[] command, byte[] query, Instant deadline) throws IOException { calls++; last = ResourceCommand.decode(command); throw new IOException("UNKNOWN"); }
        @Override public PartitionReply query(long epoch, byte[] query, Instant deadline) throws IOException { throw new IOException("UNKNOWN"); }
        @Override public PartitionStateMachine.Status status(Instant deadline) throws IOException { throw new IOException("UNKNOWN"); }
        @Override public void close() { closed = true; }
    }
    private static ManagedDependency edge(Class<?> type, BindingRefresh mode) { return new ManagedDependency(type, "typed", List.of(), false,
            Map.of("resource.name", "typed", "binding.refresh", mode.name(), "binding.policy", "default")); }
    @Test void fixedQueueClientKeepsExactRequestAndDoesNotReplayUnknownOrCloseBorrowedEndpoint() throws Exception {
        var endpoint = new UncertainEndpoint(); var factory = new ResourceBindingFactory(Map.of("typed", ResourceBindingFactory.queueGroup("p", "tenant", endpoint, 7)), Set.of("default"));
        assertThrows(IllegalArgumentException.class, () -> factory.validate(edge(ReplicatedQueue.class, BindingRefresh.ON_FAILURE), Scope.Kind.ACTIVATION));
        try (var bound = factory.bind(edge(ReplicatedQueue.class, BindingRefresh.NEVER))) {
            var queue = (ReplicatedQueue) bound.get(); assertEquals(0, endpoint.calls);
            assertThrows(IOException.class, () -> queue.send("stable-request", "q", "message", new byte[]{3}, 3, Instant.now().plusSeconds(1)));
            assertEquals(1, endpoint.calls); assertEquals("stable-request", endpoint.last.requestId()); assertEquals("tenant", endpoint.last.scope()); assertEquals("Q_SEND", endpoint.last.operation());
            bound.close(); assertThrows(IOException.class, () -> queue.send("after-close", "q", "another", new byte[]{4}, 3, Instant.now().plusSeconds(1))); assertEquals(1, endpoint.calls);
        }
        assertFalse(endpoint.closed);
    }
    @Test void fixedExecutionClientKeepsScopeAndRequestWhileUnknownStopsWithoutReclaim() throws Exception {
        var endpoint = new UncertainEndpoint(); var factory = new ResourceBindingFactory(Map.of("typed", ResourceBindingFactory.executionGroup("p", "tenant", endpoint, 3)), Set.of("default"));
        try (var bound = factory.bind(edge(ExecutionClient.class, BindingRefresh.NEVER))) {
            var jobs = new DurableJobs((ExecutionClient) bound.get());
            assertThrows(IOException.class, () -> jobs.submit("submit", "job", "input", Instant.now().plusSeconds(1)));
            assertEquals(1, endpoint.calls); assertEquals("submit", endpoint.last.requestId()); assertEquals("J_SUBMIT", endpoint.last.operation());
        }
        assertFalse(endpoint.closed);
    }
    @Test void rawPartitionGroupSupportsOnlyPinnedIdentityAndDoesNotCloseHostClient() throws Exception {
        var endpoint = new UncertainEndpoint();
        var factory = new ResourceBindingFactory(Map.of("typed", ResourceBindingFactory.partitionGroup(endpoint)), Set.of("default"));
        assertThrows(IllegalArgumentException.class, () -> factory.validate(edge(PartitionEndpoint.class, BindingRefresh.ON_TOPOLOGY_CHANGE), Scope.Kind.CALL));
        try (var bound = factory.bind(edge(PartitionEndpoint.class, BindingRefresh.NEVER))) {
            var value = (PartitionEndpoint) bound.get();
            assertThrows(IOException.class, () -> value.query(7, new byte[0], Instant.now().plusSeconds(1)));
            bound.close(); assertThrows(IllegalStateException.class, () -> value.status(Instant.now().plusSeconds(1)));
        }
        assertFalse(endpoint.closed);
    }
    @Test void typedPartitionProgramRevokesScopedCodecAndRpcEntryWithoutClosingBorrowedOwner() throws Exception {
        var endpoint = new UncertainEndpoint(); var schemas = new org.j7m.middleware.codec.api.SchemaRegistry(List.of());
        var client = new ManagedPartitionClient((key, deadline) -> new PartitionTopology.Route("p", "group", 7),
                Map.of("group", new ManagedPartitionClient.Group(endpoint, Map.of())), ManagedPartitionResourceTest.definition(), "a".repeat(64),
                "tenant", new org.j7m.middleware.codec.defaultcodec.DefaultValueCodec(schemas), schemas);
        var factory = new ResourceBindingFactory(Map.of("typed", ResourceBindingFactory.partitionProgram(client)), Set.of("default"));
        assertThrows(IllegalArgumentException.class, () -> factory.validate(edge(ManagedPartitionClient.class, BindingRefresh.ON_FAILURE), Scope.Kind.ACTIVATION));
        try (var bound = factory.bind(edge(ManagedPartitionClient.class, BindingRefresh.NEVER))) {
            var value = (ManagedPartitionClient) bound.get();
            assertThrows(IOException.class, () -> value.query("key", false, Instant.now().plusSeconds(1)));
            bound.close(); assertThrows(IllegalStateException.class, () -> value.query("key", false, Instant.now().plusSeconds(1)));
        }
        assertThrows(IOException.class, () -> client.query("key", false, Instant.now().plusSeconds(1)));
        assertFalse(endpoint.closed);
    }
    @Test void invalidCapabilityScopePolicyAndCapacityRejectBeforeConnecting() {
        var endpoint = new UncertainEndpoint();
        assertThrows(IllegalArgumentException.class, () -> ResourceBindingFactory.queueGroup("", "tenant", endpoint, 1));
        assertThrows(IllegalArgumentException.class, () -> ResourceBindingFactory.executionGroup("p", "tenant", endpoint, 0));
        var registration = ResourceBindingFactory.queueGroup("p", "tenant", endpoint, 1);
        var factory = new ResourceBindingFactory(Map.of("typed", registration), Set.of("default"));
        assertThrows(IllegalArgumentException.class, () -> factory.validate(edge(ExecutionClient.class, BindingRefresh.NEVER), Scope.Kind.CALL));
        var tooMany = new HashMap<String, ResourceBindingFactory.Registration>(); for (int n = 0; n < 257; n++) tooMany.put("resource" + n, registration);
        assertThrows(IllegalArgumentException.class, () -> new ResourceBindingFactory(tooMany, Set.of("default"))); assertEquals(0, endpoint.calls);
        tooMany.remove("resource256"); assertDoesNotThrow(() -> new ResourceBindingFactory(tooMany, Set.of("default")));
    }    @Test void deduplicationContextRequiresProviderAndActualInvocationAndCloseRevokesLookup() throws Exception {
        var factory = new ResourceBindingFactory(Map.of("typed", ResourceBindingFactory.deduplicationContext()), Set.of("default"));
        var direct = edge(ManagedDeduplicationContext.class, BindingRefresh.NEVER);
        assertThrows(IllegalArgumentException.class, () -> factory.validate(direct, Scope.Kind.CALL));
        var provider = new ManagedDependency(direct.type(), direct.name(), direct.qualifiers(), true, direct.attributes());
        factory.validate(provider, Scope.Kind.ACTIVATION);
        try (var bound = factory.bind(provider)) {
            assertThrows(IllegalStateException.class, bound::get);
            bound.close(); assertThrows(IllegalStateException.class, bound::get);
        }
        var refresh = new ManagedDependency(direct.type(), direct.name(), direct.qualifiers(), true,
                Map.of("resource.name", "typed", "binding.refresh", "ON_FAILURE", "binding.policy", "default"));
        assertThrows(IllegalArgumentException.class, () -> factory.validate(refresh, Scope.Kind.ACTIVATION));
    }

}
