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

package org.j7m.middleware.distributed;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * Scope-bound reusable execution transport using authenticated topology routes and stable request IDs.
 * Unknown external effects must be reconciled by the application; this client never invokes effects.
 */
public final class ExecutionClient {
    private final String partition, scope;
    private final ReplicatedQueue.RouteResolver routes;
    private final Supplier<Map<String, String>> context;
    private final Observer observer;
    /**
     * Vendor-neutral lifecycle instrumentation; implementations must not throw or block delivery.
     */
    public interface Observer {
        /**
         * Opens one operation observation on the calling thread.
         * @param operation bounded protocol operation name
         * @return observation closed on success or exception
         */
        Observation begin(String operation);
    }
    /**
     * An observation owns its span/scope; completion is delivered only for an actual result.
     */
    public interface Observation extends AutoCloseable {
        /**
         * Records the returned protocol outcome.
         * @param result actual durable outcome
         */
        void completed(ExecutionResult result);
        /**
         * Closes the observation and its calling-thread context.
         */
        @Override void close();
    }
    private static final Observer NO_OBSERVER = operation -> new Observation() {
        @Override public void completed(ExecutionResult result) { }
        @Override public void close() { }
    };
    /**
     * Creates a client without trace context.
     * @param partition stable resource partition
     * @param scope authenticated tenant scope
     * @param routes committed topology resolver
     */
    public ExecutionClient(String partition, String scope, ReplicatedQueue.RouteResolver routes) { this(partition, scope, routes, Map::of); }
    /**
     * Creates a context-propagating client without depending on an observability vendor.
     * @param partition stable resource partition
     * @param scope authenticated tenant scope
     * @param routes committed topology resolver
     * @param context supplier of bounded traceparent/tracestate fields captured on submission
     */
    public ExecutionClient(String partition, String scope, ReplicatedQueue.RouteResolver routes, Supplier<Map<String, String>> context) {
        this(partition, scope, routes, context, NO_OBSERVER);
    }
    /**
     * Creates an automatically instrumented client, persisting the first submission's trace carrier.
     * Trace context is excluded from business identity fingerprints; new spans on retries cannot turn
     * identical work into an input conflict. The first committed context remains authoritative.
     * @param partition stable resource partition
     * @param scope authenticated tenant scope
     * @param routes committed topology resolver
     * @param context current bounded W3C carrier supplier
     * @param observer lifecycle instrumentation with no-throw callbacks
     */
    public ExecutionClient(String partition, String scope, ReplicatedQueue.RouteResolver routes, Supplier<Map<String, String>> context, Observer observer) {
        new ResourceCommand("E_RESULT", scope, "query", List.of(partition)); this.partition = Objects.requireNonNull(partition); this.scope = scope; this.routes = Objects.requireNonNull(routes); this.context = Objects.requireNonNull(context); this.observer = Objects.requireNonNull(observer);
    }
    String context() { return ExecutionStateMachine.encodeContext(context.get()); }
    /**
     * Submits an execution operation through the authoritative partition.
     * @param operation execution protocol operation
     * @param request stable tenant-scoped request identity
     * @param arguments bounded operation arguments
     * @param deadline absolute operation deadline
     * @return immutable persisted result, or explicit REQUEST_CONFLICT/OVERLOADED status
     * @throws IOException on unavailable quorum, cancellation, deadline or ambiguous completion
     */
    public ExecutionResult execute(String operation, String request, List<String> arguments, Instant deadline) throws IOException {
        try (Observation observation = observer.begin(operation)) {
        List<String> captured = new ArrayList<>(arguments); if (operation.equals("R_CLAIM") && captured.size() == 5) captured.add(UUID.randomUUID().toString()); int contextPosition = ExecutionStateMachine.contextPosition(operation); if (contextPosition >= 0) captured.set(contextPosition, context());
        ResourceCommand command = new ResourceCommand(operation, scope, request, captured); ExecutionStateMachine.validate(command); byte[] encoded = command.encode();
        byte[] query = new ResourceCommand("E_RESULT", scope, "query", operation.equals("R_CLAIM") ? List.of(request, captured.get(5)) : List.of(request)).encode();
        while (Instant.now().isBefore(deadline)) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Execution cancelled; outcome UNKNOWN");
            var route = routes.resolve(partition, deadline); PartitionReply reply;
            try { reply = route.endpoint().execute(route.epoch(), request, encoded, query, deadline); }
            catch (IllegalArgumentException conflict) {
                if (conflict.getMessage() == null || !conflict.getMessage().toLowerCase(Locale.ROOT).contains("identity conflict")) throw conflict;
                ExecutionResult result = new ExecutionResult("CONFLICT", Map.of()); observation.completed(result); return result;
            }
            if (reply.status().equals("OK")) { ExecutionResult result = ExecutionResult.decode(reply.payload()); observation.completed(result); return result; }
            if (!Set.of("MOVED", "FROZEN").contains(reply.status())) { ExecutionResult result = new ExecutionResult(reply.status(), Map.of()); observation.completed(result); return result; }
        }
        throw new IOException("Execution deadline; outcome UNKNOWN");
        }
    }
    /**
     * Queries durable execution state through a fresh quorum and ownership barrier.
     * @param type E_RUN, E_RPC, E_VALUE or E_RESULT
     * @param identity scope-local entity identity
     * @param deadline absolute deadline
     * @return immutable authorized state
     * @throws IOException on unavailable quorum or deadline
     */
    public ExecutionResult query(String type, String identity, Instant deadline) throws IOException {
        return ExecutionResult.decode(queryBytes(type, List.of(identity), deadline));
    }
    /**
     * Reads bounded authorized aggregate counters with no identity labels.
     * @param deadline absolute deadline
     * @return actual job/saga/UNKNOWN/RPC replay and retention counters
     * @throws IOException on unavailable quorum or unhealthy clock
     */
    public ExecutionResult statistics(Instant deadline) throws IOException { return ExecutionResult.decode(queryBytes("E_STATS", List.of(), deadline)); }
    /**
     * Queries an execution capability using its documented bounded binary format.
     * Public event subscriptions use this method for poll and atomic projection-plus-tail capture.
     * @param type authorized query operation
     * @param arguments bounded query arguments
     * @param deadline absolute operation deadline
     * @return independent encoded query result
     * @throws IOException on unavailable quorum, unhealthy clock or deadline
     */
    public byte[] queryBytes(String type, List<String> arguments, Instant deadline) throws IOException {
        byte[] query = new ResourceCommand(type, scope, "query", arguments).encode();
        byte[] refresh = new ResourceCommand("E_REFRESH", scope, "read-time", List.of()).encode();
        byte[] stats = new ResourceCommand("E_STATS", scope, "query", List.of()).encode();
        try (Observation observation = observer.begin(type)) {
            while (Instant.now().isBefore(deadline)) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Execution query cancelled");
                var route = routes.resolve(partition, deadline);
                var tick = route.endpoint().execute(route.epoch(), "read-time", refresh, stats, deadline);
                if (Set.of("MOVED", "FROZEN").contains(tick.status())) continue;
                if (!tick.status().equals("OK")) throw new IOException("Execution clock refresh: " + tick.status());
                var reply = route.endpoint().query(route.epoch(), query, deadline);
                if (reply.status().equals("OK")) {
                    if (!Set.of("E_POLL", "E_SNAPSHOT_TAIL", "E_STEPS", "E_RUNS").contains(type)) observation.completed(ExecutionResult.decode(reply.payload()));
                    return reply.payload();
                }
                if (!Set.of("MOVED", "FROZEN").contains(reply.status())) throw new IOException("Execution query: " + reply.status());
            }
        }
        throw new IOException("Execution query deadline");
    }
}
