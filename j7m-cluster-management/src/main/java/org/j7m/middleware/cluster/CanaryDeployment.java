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

package org.j7m.middleware.cluster;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.distributed.ResourceEndpoint;

/**
 * Quorum-backed canary traffic controller invoking real managed application endpoints.
 * Each policy is CAS-versioned in the authenticated resource scope. Readiness is obtained through
 * the lifecycle's live inspection. Missing/stale observations cause HOLD and cannot raise exposure.
 * Bad candidate observations atomically disable candidate admission before lifecycle rollback/drain.
 * Completion timeout is UNKNOWN: this class never retries an application invocation automatically.
 * Metrics count calls made through this instance; restarting requires fresh minimum samples.
 * Instances are thread-safe; close stops evaluation, not application effects or durable policies.
 */
public final class CanaryDeployment implements AutoCloseable {
    /**
     * Candidate observations without request identities or application payloads.
     * @param observedAt observation wall time
     * @param successes successful candidate responses
     * @param errors failed candidate responses
     * @param totalLatencyNanos sum of candidate elapsed monotonic durations
     */
    public record Probe(Instant observedAt, long successes, long errors, long totalLatencyNanos) {
        /**
         * Validates immutable bounded observations.
         * @param observedAt observation time
         * @param successes nonnegative successes
         * @param errors nonnegative errors
         * @param totalLatencyNanos nonnegative accumulated latency
         * @throws IllegalArgumentException for invalid counts
         */
        public Probe {
            Objects.requireNonNull(observedAt);
            if (successes < 0 || errors < 0 || successes > Long.MAX_VALUE - errors || totalLatencyNanos < 0)
                throw new IllegalArgumentException("Invalid candidate observations");
        }
    }
    /**
     * Optional concrete observation adapter; returning null or throwing means missing probes.
     */
    @FunctionalInterface public interface ProbeReader {
        /**
         * Retrieves candidate observations for this policy revision only.
         * @param revision active policy revision
         * @return observations, or null when unavailable
         * @throws IOException for unavailable collector
         */
        Probe read(long revision) throws IOException;
    }
    /**
     * Durable controller state.
     * @param version resource CAS version
     * @param policy current policy
     * @param state ACTIVE, HOLD, ROLLING_BACK or ROLLED_BACK
     * @param lifecycleVersion original rollback optimistic version, retained across controller restart
     * @param rollbackNodes original rollback node set, retained for idempotent operation replay
     */
    public record Status(long version, CanaryPolicy policy, String state, long lifecycleVersion, List<String> rollbackNodes) {
        /**
         * Copies the durable rollback participant list.
         * @param version policy resource version
         * @param policy current policy
         * @param state controller state
         * @param lifecycleVersion original lifecycle version
         * @param rollbackNodes original rollback participants
         */
        public Status { rollbackNodes = List.copyOf(rollbackNodes); }
    }
    private final String scope;
    private final String key;
    private final ResourceEndpoint resources;
    private final ClusterLifecycle lifecycle;
    private final Map<String, ManagedInvocation> endpoints;
    private final ProbeReader probes;
    private final ScheduledExecutorService evaluator;
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong latency = new AtomicLong();
    private final AtomicLong holds = new AtomicLong();
    private final AtomicLong rollbacks = new AtomicLong();
    private volatile long measuredRevision;

    /**
     * Creates an automatically evaluated controller using measured local invocation outcomes.
     * @param scope authenticated policy namespace, bounded deployment identity
     * @param resources scope-bound durable CAS endpoint
     * @param lifecycle concrete remote cluster lifecycle
     * @param endpoints actual managed invocation clients by node identity, 1..64
     * @throws IllegalArgumentException for invalid scope or endpoint capacity
     */
    public CanaryDeployment(String scope, ResourceEndpoint resources, ClusterLifecycle lifecycle,
            Map<String, ManagedInvocation> endpoints) {
        this(scope, resources, lifecycle, endpoints, null);
    }
    /**
     * Creates a controller with an optional external observation adapter.
     * The adapter must authenticate its source and return measurements for the supplied revision.
     * @param scope authenticated namespace
     * @param resources durable CAS endpoint
     * @param lifecycle concrete cluster lifecycle
     * @param endpoints managed invocation clients by node
     * @param probes external probe reader, or null for local measured calls
     * @throws IllegalArgumentException for invalid scope or endpoint capacity
     */
    public CanaryDeployment(String scope, ResourceEndpoint resources, ClusterLifecycle lifecycle,
            Map<String, ManagedInvocation> endpoints, ProbeReader probes) {
        CanaryPolicy.identity(scope); this.scope = scope; key = "canary/" + scope;
        this.resources = Objects.requireNonNull(resources); this.lifecycle = Objects.requireNonNull(lifecycle);
        this.endpoints = Map.copyOf(endpoints); this.probes = probes;
        if (endpoints.isEmpty() || endpoints.size() > 64) throw new IllegalArgumentException("Invalid endpoint capacity");
        evaluator = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("j7m-canary-" + scope).factory());
        evaluator.scheduleWithFixedDelay(() -> {
            try { evaluate(Instant.now().plusSeconds(2)); } catch (IOException | RuntimeException unavailable) { holds.incrementAndGet(); }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }
    /**
     * Installs an increasing revision after fresh readiness/probe checks; retries use the same request ID.
     * @param request stable authorized operation identity
     * @param expectedVersion expected resource version, zero for initial creation
     * @param policy new explicit traffic/threshold policy
     * @param deadline absolute deadline
     * @return committed policy state
     * @throws IOException for missing probes, readiness, CAS conflict or unknown commit
     */
    public synchronized Status configure(String request, long expectedVersion, CanaryPolicy policy, Instant deadline) throws IOException {
        Status current = status(deadline);
        if (current != null && current.policy.equals(policy)) return current;
        if (current != null && policy.revision() <= current.policy.revision()) throw new IOException("STALE_POLICY_REVISION");
        ClusterView view = lifecycle.inspect(deadline);
        if (nodes(view, policy.baseline()).isEmpty() || (policy.canaryWeight() > 0 && nodes(view, policy.canary()).isEmpty()))
            throw new IOException("HOLD: release is not ready");
        Probe observation = observation(policy.revision());
        if (!fresh(policy, observation)) throw new IOException("HOLD: probes unavailable");
        Status installed = persist(request, expectedVersion, policy, "ACTIVE", deadline);
        reset(policy.revision()); return installed;
    }
    /**
     * Invokes one actual managed endpoint selected by the durable policy and request identity.
     * No fallback invocation follows an ambiguous error; applications must reconcile by request ID.
     * @param requestId stable application request identity
     * @param contract exported application contract
     * @param payload application bytes passed unchanged
     * @param deadline absolute deadline
     * @return managed application response
     * @throws IOException on unavailable policy/owner or unknown application outcome
     */
    public byte[] invoke(String requestId, String contract, byte[] payload, Instant deadline) throws IOException {
        Status current = status(deadline);
        if (current == null) throw new IOException("POLICY_NOT_FOUND");
        ClusterView view = lifecycle.inspect(deadline);
        boolean candidate = current.state.equals("ACTIVE")
                && CanaryRouter.bucket(scope, current.policy.revision(), requestId) < current.policy.canaryWeight();
        List<String> selected = nodes(view, candidate ? current.policy.canary() : current.policy.baseline());
        if (selected.isEmpty()) throw new IOException("HOLD: selected release unavailable");
        String node = CanaryRouter.node(scope, current.policy.revision(), requestId, selected);
        long started = System.nanoTime();
        try {
            byte[] result = endpoints.get(node).invokeRevision(candidate ? current.policy.canary() : current.policy.baseline(), requestId, contract, payload, deadline);
            if (candidate) record(current.policy.revision(), true, System.nanoTime() - started);
            return result;
        } catch (IOException | RuntimeException failed) {
            if (candidate) record(current.policy.revision(), false, System.nanoTime() - started);
            throw failed;
        }
    }
    /**
     * Evaluates fresh readiness and measured error/mean-latency thresholds, resuming durable rollback.
     * @param deadline absolute deadline for quorum/lifecycle operations
     * @return latest durable state, or null before configuration
     * @throws IOException for unavailable quorum or unknown lifecycle outcome
     */
    public synchronized Status evaluate(Instant deadline) throws IOException {
        Status current = status(deadline); if (current == null || current.state.equals("ROLLED_BACK")) return current;
        if (current.state.equals("ROLLING_BACK")) return finishRollback(current, deadline);
        ClusterView view;
        Probe sample;
        try { view = lifecycle.inspect(deadline); sample = observation(current.policy.revision()); }
        catch (IOException unavailable) { return hold(current, deadline); }
        if (!fresh(current.policy, sample) || nodes(view, current.policy.baseline()).isEmpty()
                || nodes(view, current.policy.canary()).isEmpty()) return hold(current, deadline);
        long count = sample.successes() + sample.errors();
        if (count >= current.policy.minimumSamples()
                && ((double) sample.errors() / count > current.policy.maximumErrorRate()
                || (double) sample.totalLatencyNanos() / count / 1000000 > current.policy.maximumMeanLatencyMillis())) {
            CanaryPolicy candidatePolicy = current.policy;
            current = persist("canary-disable-" + scope + "-" + current.policy.revision(), current.version,
                    current.policy, "ROLLING_BACK", view.version(), view.nodes().values().stream()
                            .filter(n -> n.releaseId().equals(candidatePolicy.canary())).map(NodeView::nodeId).sorted().toList(), deadline);
            return finishRollback(current, deadline);
        }
        return current;
    }
    /**
     * Reads the current quorum-authoritative policy.
     * @param deadline absolute deadline
     * @return durable policy state, or null
     * @throws IOException on unavailable quorum or unsupported encoding
     */
    public Status status(Instant deadline) throws IOException {
        var value = resources.read(key, deadline); if (value == null) return null;
        try {
            String[] f = value.value().split("\\|", -1);
            if (f.length != 12 || !f[0].equals("1")) throw new IOException("Unsupported canary policy format");
            return new Status(value.version(), new CanaryPolicy(Long.parseLong(f[1]), f[2], f[3], Integer.parseInt(f[4]),
                    Long.parseLong(f[5]), Double.parseDouble(f[6]), Long.parseLong(f[7]), Long.parseLong(f[8])), f[9], Long.parseLong(f[10]), f[11].isEmpty() ? List.of() : List.of(f[11].split(",")));
        } catch (IllegalArgumentException malformed) { throw new IOException("Malformed canary policy", malformed); }
    }
    /**
     * Returns bounded identity-free controller counters.
     * @return counts of hold observations and completed rollback evaluations
     */
    public Map<String, Long> metrics() { return Map.of("holds", holds.get(), "rollbacks", rollbacks.get()); }
    /**
     * {@inheritDoc}
     */
    @Override public void close() { evaluator.shutdownNow(); }

    private Status hold(Status current, Instant deadline) throws IOException {
        holds.incrementAndGet();
        return current.state.equals("HOLD") ? current : persist("canary-hold-" + scope + "-" + current.policy.revision(),
                current.version, current.policy, "HOLD", deadline);
    }
    private Status finishRollback(Status current, Instant deadline) throws IOException {
        if (!current.rollbackNodes.isEmpty()) {
            OperationStatus operation = lifecycle.rollback("canary-rollback-" + scope + "-" + current.policy.revision(),
                    current.lifecycleVersion, current.policy.baseline(), current.rollbackNodes, deadline);
            if (!operation.state().equals("COMPLETED")) return current;
        }
        Status done = persist("canary-done-" + scope + "-" + current.policy.revision(), current.version, current.policy, "ROLLED_BACK", current.lifecycleVersion, current.rollbackNodes, deadline);
        rollbacks.incrementAndGet(); return done;
    }
    private Status persist(String request, long version, CanaryPolicy p, String state, Instant deadline) throws IOException {
        return persist(request, version, p, state, 0, List.of(), deadline);
    }
    private Status persist(String request, long version, CanaryPolicy p, String state, long lifecycleVersion, List<String> rollbackNodes, Instant deadline) throws IOException {
        String encoded = String.join("|", "1", Long.toString(p.revision()), p.baseline(), p.canary(), Integer.toString(p.canaryWeight()),
                Long.toString(p.minimumSamples()), Double.toString(p.maximumErrorRate()), Long.toString(p.maximumMeanLatencyMillis()),
                Long.toString(p.maximumProbeAgeMillis()), state, Long.toString(lifecycleVersion), String.join(",", rollbackNodes));
        var result = resources.execute("BATCH", request, List.of(key, Long.toString(version), encoded), deadline);
        if (!result.status().equals("OK")) throw new IOException("Canary policy mutation: " + result.status());
        Status saved = status(deadline);
        if (saved == null || !saved.policy.equals(p) || !saved.state.equals(state)) throw new IOException("CONFLICT: canary policy changed");
        return saved;
    }
    private List<String> nodes(ClusterView view, String release) {
        return view.nodes().values().stream().filter(n -> n.releaseId().equals(release) && n.ready() && !n.draining()
                && endpoints.containsKey(n.nodeId())).map(NodeView::nodeId).sorted().toList();
    }
    private Probe observation(long revision) throws IOException {
        if (probes != null) return probes.read(revision);
        if (measuredRevision != revision) return new Probe(Instant.now(), 0, 0, 0);
        return new Probe(Instant.now(), successes.get(), errors.get(), latency.get());
    }
    private static boolean fresh(CanaryPolicy policy, Probe probe) {
        long now = System.currentTimeMillis();
        return probe != null && probe.observedAt().toEpochMilli() <= now + 500
                && now - probe.observedAt().toEpochMilli() <= policy.maximumProbeAgeMillis();
    }
    private synchronized void record(long revision, boolean success, long nanos) {
        // A prior revision can finish after configuration publishes a newer measurement window.
        if (revision < measuredRevision) return;
        if (measuredRevision != revision) reset(revision);
        if (success) successes.updateAndGet(v -> v == Long.MAX_VALUE ? v : v + 1);
        else errors.updateAndGet(v -> v == Long.MAX_VALUE ? v : v + 1);
        latency.updateAndGet(v -> Long.MAX_VALUE - v < nanos ? Long.MAX_VALUE : v + nanos);
    }
    private void reset(long revision) { measuredRevision = revision; successes.set(0); errors.set(0); latency.set(0); }
}
