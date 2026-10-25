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
import java.util.function.Predicate;
import org.j7m.middleware.consensus.NetworkConsensusNode;

/**
 * Quorum-authoritative resource operations. Every call rechecks authorization; mutations execute
 * exclusively through committed consensus callbacks. IOException may indicate UNKNOWN: deadlines
 * never roll back a committed operation. Retry the identical request and input to recover its result.
 * This facade is thread-safe when its clock-health provider and authorization predicate are thread-safe.
 */
public final class ReplicatedResourceClient implements ResourceEndpoint {
    private final NetworkConsensusNode node;
    private final ReplicatedResources resources;
    private final String scope;
    private final Predicate<String> authorization;
    private final ClockHealth clock;
    /**
     * Supplies measured, authenticated clock health. Implementations must compare monotonic elapsed
     * time against wall-clock changes and authenticated peer/reference offsets, not trust client time.
     */
    public interface ClockHealth {
        /**
         * Samples clock health for this operation.
         * @return measured clock sample; never null
         */
        ClockMeasurement sample();
    }
    /**
     * Measured clock evidence; healthy profile permits at most 500 ms uncertainty and offset.
     * @param time wall-clock milliseconds
     * @param reference authenticated reference milliseconds
     * @param uncertainty nonnegative worst-case uncertainty milliseconds
     * @param monotonicStable whether wall-clock evolution agrees with monotonic elapsed time
     */
    public record ClockMeasurement(long time, long reference, long uncertainty, boolean monotonicStable) { }
    /**
     * Builds a facade bound to the exact state machine hosted by the supplied node.
     * @param node consensus node
     * @param resources its registered resource machine
     * @param scope tenant/principal authorization scope, immutable for this facade
     * @param authorization permission predicate, called on each operation
     * @param clock trusted measured clock evidence provider
     * @throws IllegalArgumentException for invalid scope
     */
    public ReplicatedResourceClient(NetworkConsensusNode node, ReplicatedResources resources, String scope,
            Predicate<String> authorization, ClockHealth clock) {
        this.node = Objects.requireNonNull(node); this.resources = Objects.requireNonNull(resources);
        if (!node.hosts(resources)) throw new IllegalArgumentException("State machine is not hosted by supplied node");
        if (scope == null || scope.isBlank() || scope.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid scope");
        this.scope = scope; this.authorization = Objects.requireNonNull(authorization); this.clock = Objects.requireNonNull(clock);
    }
    /**
     * Atomically applies CAS triples (key, expectedVersion, newValue). Missing values have version zero.
     * @param request idempotency identity
     * @param triples nonempty triples
     * @param deadline absolute deadline
     * @return persisted result; CONFLICT changes nothing
     * @throws IOException on unavailable quorum, ambiguous reply, cancellation or deadline
     * @throws SecurityException if operation is not authorized
     */
    public ReplicatedResources.Result batch(String request, List<String> triples, Instant deadline) throws IOException {
        return execute("BATCH", request, triples, deadline);
    }
    /**
     * Reads after a fresh quorum barrier, with no local-cache authorization.
     * @param key resource key
     * @param deadline absolute deadline
     * @return versioned value or null
     * @throws IOException on unavailable quorum or deadline
     * @throws SecurityException if read is not authorized
     */
    public ReplicatedResources.Value read(String key, Instant deadline) throws IOException {
        authorize("READ"); return resources.queryAtReadIndex(scope, key, node.readIndex(deadline));
    }
    /**
     * Commits measured logical time; an unsafe or uncertain clock never advances it.
     * @param request request identity
     * @param deadline absolute deadline
     * @return persisted time result
     * @throws IOException on quorum failure or unhealthy clock
     * @throws SecurityException if unauthorized
     */
    public ReplicatedResources.Result advanceTime(String request, Instant deadline) throws IOException {
        ClockMeasurement sample = healthy();
        return execute("TIME", request, List.of(Long.toString(sample.time), Long.toString(sample.reference),
                Long.toString(sample.uncertainty)), deadline);
    }
    /**
     * Acquires a coordination/member/leader lease through quorum; keys distinguish these uses.
     * @param request request identity
     * @param key lease key
     * @param owner owner identity
     * @param durationMillis duration in (0, 86400000]
     * @param deadline absolute deadline
     * @return result with globally monotonic fencing token
     * @throws IOException on quorum failure or unhealthy clock
     * @throws SecurityException if unauthorized
     */
    public ReplicatedResources.Result acquire(String request, String key, String owner, long durationMillis,
            Instant deadline) throws IOException {
        healthy(); return execute("ACQUIRE", request, List.of(key, owner, Long.toString(durationMillis),
                "0"), deadline);
    }
    /**
     * Renews only the expected live version and owner, issuing a fresh epoch-aware fence.
     * @param request request identity
     * @param key lease key
     * @param expectedVersion expected version
     * @param owner owner identity
     * @param durationMillis requested duration
     * @param deadline absolute deadline
     * @return persisted result
     * @throws IOException on quorum failure or unhealthy clock
     * @throws SecurityException if unauthorized
     */
    public ReplicatedResources.Result renew(String request, String key, long expectedVersion, String owner,
            long durationMillis, Instant deadline) throws IOException {
        healthy(); return execute("RENEW", request, List.of(key, Long.toString(expectedVersion), owner,
                Long.toString(durationMillis), "0"), deadline);
    }
    /**
     * Cancels the exact lease version.
     * @param request request identity
     * @param key lease key
     * @param expectedVersion expected version
     * @param deadline absolute deadline
     * @return persisted result
     * @throws IOException on quorum failure
     * @throws SecurityException if unauthorized
     */
    public ReplicatedResources.Result cancel(String request, String key, long expectedVersion, Instant deadline) throws IOException {
        return execute("CANCEL", request, List.of(key, Long.toString(expectedVersion)), deadline);
    }
    /**
     * Expires a version only when committed time already reached its expiration.
     * @param request request identity
     * @param key lease key
     * @param expectedVersion expected version
     * @param deadline absolute deadline
     * @return persisted result
     * @throws IOException on quorum failure or unhealthy clock
     * @throws SecurityException if unauthorized
     */
    public ReplicatedResources.Result expire(String request, String key, long expectedVersion, Instant deadline) throws IOException {
        healthy(); return execute("EXPIRE", request, List.of(key, Long.toString(expectedVersion)), deadline);
    }
    /**
     * Mutates a protected key only with the exact active lease fence and leadership epoch.
     * @param request request identity
     * @param lease lease key
     * @param fence fencing token
     * @param epoch leadership epoch
     * @param key protected resource key
     * @param value new value
     * @param deadline absolute deadline
     * @return persisted result
     * @throws IOException on quorum failure
     * @throws SecurityException if unauthorized
     */
    public ReplicatedResources.Result fenced(String request, String lease, long fence, long epoch,
            String key, String value, Instant deadline) throws IOException {
        return execute("FENCED", request, List.of(lease, Long.toString(fence), Long.toString(epoch), key, value), deadline);
    }
    /**
     * Returns an active authoritative lease.
     * @param key lease key
     * @param deadline absolute deadline
     * @return lease or null
     * @throws IOException on unavailable quorum
     * @throws SecurityException if unauthorized
     */
    public ReplicatedResources.Lease lease(String key, Instant deadline) throws IOException {
        authorize("READ");
        if (key == null || key.isBlank() || key.length() > 1024 || key.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid lease key");
        commitMeasuredTime(deadline);
        return resources.lease(scope, key, node.readIndex(deadline));
    }
    /**
     * Returns a recoverable retained request result.
     * @param request request identity
     * @param deadline absolute deadline
     * @return persisted result, EXPIRED, or null
     * @throws IOException on unavailable quorum
     * @throws SecurityException if unauthorized
     */
    public ReplicatedResources.Result result(String request, Instant deadline) throws IOException {
        authorize("READ"); return resources.result(scope, request, node.readIndex(deadline));
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public ReplicatedResources.Result execute(String operation, String request, List<String> args, Instant deadline) throws IOException {
        authorize(operation);
        if (Thread.currentThread().isInterrupted()) throw new IOException("CANCELLED before submit");
        if (!Instant.now().isBefore(deadline)) throw new IOException("Deadline before submit");
        ResourceCommand command = new ResourceCommand(operation, scope, request, args);
        ReplicatedResources.validate(command);
        if (operation.equals("ACQUIRE") && !args.get(3).equals("0")
                || operation.equals("RENEW") && !args.get(4).equals("0"))
            throw new IllegalArgumentException("Lease epoch must be zero; committed log term is authoritative");
        byte[] bytes = command.encode();
        String identity = Base64.getEncoder().encodeToString(scope.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + ":" + Base64.getEncoder().encodeToString(request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (identity.length() > 1024) throw new IllegalArgumentException("Encoded request identity exceeds consensus bound");
        if (operation.equals("TIME")) {
            ClockMeasurement evidence = healthy();
            long proposed = Long.parseLong(args.get(0)), reference = Long.parseLong(args.get(1));
            long uncertainty = Long.parseLong(args.get(2));
            if (proposed < 0 || reference < 0 || Math.abs(proposed - evidence.time) > 500
                    || Math.abs(reference - evidence.reference) > 500 || uncertainty < evidence.uncertainty)
                throw new IOException("CLOCK_UNSAFE: proposal lacks measured clock evidence");
            long index = node.appendControl(bytes, deadline);
            return new ReplicatedResources.Result("OK", Long.toString(resources.logicalTime()), index);
        }
        if (Set.of("ACQUIRE", "RENEW", "EXPIRE", "FENCED", "SUBSCRIBE", "ACK", "RESUME").contains(operation)) commitMeasuredTime(deadline);
        if (Set.of("SUBSCRIBE", "ACK", "RESUME").contains(operation)) {
            try { resources.validateAdmission(command, node.readIndex(deadline)); }
            catch (IllegalStateException e) { throw new IOException(e.getMessage(), e); }
        }
        if (resources.atCapacity() && resources.result(scope, request, node.readIndex(deadline)) == null)
            throw new IOException("OVERLOADED: foreground request fingerprint capacity");
        long index = node.append(identity, bytes, deadline);
        ReplicatedResources.Result result = resources.result(scope, request, index);
        return result == null ? new ReplicatedResources.Result("OVERLOADED", "", index) : result;
    }
    private void commitMeasuredTime(Instant deadline) throws IOException {
        ClockMeasurement sample = healthy();
        ResourceCommand time = new ResourceCommand("TIME", scope, "clock-control",
                List.of(Long.toString(sample.time), Long.toString(sample.reference), Long.toString(sample.uncertainty)));
        node.appendControl(time.encode(), deadline);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ReplicatedResources.Result transaction(String tx, Instant deadline) throws IOException {
        authorize("TRANSACTION"); return resources.transaction(scope, tx, node.readIndex(deadline));
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> prepared(Instant deadline) throws IOException {
        authorize("TRANSACTION"); return resources.prepared(scope, node.readIndex(deadline));
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> participants(String tx, Instant deadline) throws IOException {
        authorize("TRANSACTION"); return resources.participants(scope, tx, node.readIndex(deadline));
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public EventJournal.Page poll(String subscription, int maximum, Instant deadline) throws IOException {
        authorize("EVENTS"); commitMeasuredTime(deadline);
        try { return resources.poll(scope, subscription, maximum, node.readIndex(deadline)); }
        catch (IllegalStateException e) { throw new IOException(e.getMessage(), e); }
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public ReplicatedResources.SnapshotTail snapshotAndTail(Instant deadline) throws IOException {
        authorize("EVENTS"); return resources.snapshotAndTail(scope, node.readIndex(deadline));
    }
    private void authorize(String operation) {
        if (!authorization.test(operation)) throw new SecurityException("Operation denied");
    }
    private ClockMeasurement healthy() throws IOException {
        ClockMeasurement sample = clock.sample();
        if (!sample.monotonicStable || sample.uncertainty < 0 || sample.uncertainty > 500
                || sample.time < 0 || sample.reference < 0 || Math.abs(sample.time - sample.reference) > 500) {
            throw new IOException("CLOCK_UNSAFE: ownership and expiration suspended");
        }
        return sample;
    }
}
