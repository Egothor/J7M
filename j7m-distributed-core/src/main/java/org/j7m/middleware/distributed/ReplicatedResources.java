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

import java.io.*;
import java.security.*;
import java.util.*;

/**
 * Synchronized deterministic map reference resource; not a tuple space. Mutation batches commit
 * state/result/event atomically by publishing a fully validated copy. Version-one snapshots include
 * logical time, fences, request fingerprints, prepared read/write/range locks and decision journal.
 * Retained results expire by committed logical time; fingerprints remain to prevent re-execution.
 * Capacity exhaustion fails closed; operators must provision/migrate before limits are reached.
 */
public final class ReplicatedResources implements ResourceStateMachine, TransferableResource {
    private static final int MAX_ENTRIES = 100_000;
    private static final Set<String> OPERATIONS = Set.of("BATCH", "TIME", "ACQUIRE", "RENEW", "CANCEL",
            "EXPIRE", "FENCED", "LOCK_READ", "LOCK_RANGE", "BEGIN", "ENLIST", "SEAL", "DECIDE", "PREPARE", "COMMIT", "ROLLBACK", "SUBSCRIBE", "ACK", "RESUME");
    private State state = new State();
    private final int requestCapacity;
    private final java.util.function.Consumer<String> publicationProbe;
    /**
     * Creates an empty resource. Restore must complete before the hosting node serves requests.
     */
    public ReplicatedResources() { this(MAX_ENTRIES); }
    /**
     * Creates an empty resource with a bounded foreground fingerprint budget. All replicas must use
     * the same budget; control time updates do not consume it. Existing IDs remain replayable at capacity.
     * @param requestCapacity number of retained foreground identities in [1,100000]
     * @throws IllegalArgumentException if the budget is outside the supported bounds
     */
    public ReplicatedResources(int requestCapacity) { this(requestCapacity, ignored -> { }); }
    ReplicatedResources(int requestCapacity, java.util.function.Consumer<String> publicationProbe) {
        if (requestCapacity < 1 || requestCapacity > MAX_ENTRIES) throw new IllegalArgumentException("Request capacity");
        this.requestCapacity = requestCapacity; this.publicationProbe = Objects.requireNonNull(publicationProbe);
    }
    /**
     * Versioned map value.
     * @param value nonnull payload
     * @param version positive mutation log index
     */
    public record Value(String value, long version) { }
    /**
     * Immutable operation result.
     * @param status OK, CONFLICT, EXPIRED, CLOCK_UNSAFE, PREPARED, COMMITTED or ABORTED
     * @param value operation payload
     * @param index committed result index
     */
    public record Result(String status, String value, long index) { }
    /**
     * Immutable event emitted in the same batch as state and result.
     * @param index committed position
     * @param scope authorization scope
     * @param operation operation name
     */
    public record Event(long index, String scope, String operation) { }
    /**
     * Persisted lease including leadership epoch and globally monotonic fence.
     * @param owner owner identity
     * @param version positive lease version
     * @param expiresAt expiration in committed logical milliseconds
     * @param fence globally increasing token
     * @param leadershipEpoch term at acquisition or renewal
     */
    public record Lease(String owner, long version, long expiresAt, long fence, long leadershipEpoch) { }
    private record Saved(String fingerprint, Result result, long expiresAt) { }
    private record Tx(String status, long decisionEpoch, List<String> participants, List<String> operations) { }
    private static final class State {
        long index, time, fence, ownershipEpoch;
        String partition = "map";
        boolean timeInitialized;
        TreeMap<String, Value> values = new TreeMap<>();
        TreeMap<String, Lease> leases = new TreeMap<>();
        TreeMap<String, Saved> results = new TreeMap<>();
        TreeMap<String, Tx> transactions = new TreeMap<>();
        TreeMap<String, String> locks = new TreeMap<>();
        ArrayList<Event> events = new ArrayList<>();
        EventJournal journal = new EventJournal(10000, 16777216);
        State copy() {
            State next = new State(); next.index = index; next.time = time; next.fence = fence; next.timeInitialized = timeInitialized;
            next.partition = partition; next.ownershipEpoch = ownershipEpoch;
            next.values.putAll(values); next.leases.putAll(leases); next.results.putAll(results);
            next.transactions.putAll(transactions); next.locks.putAll(locks); next.events.addAll(events); next.journal = journal.copy();
            return next;
        }
    }
    /**
     * Applies a committed resource command at a strictly advancing log index without term metadata.
     *
     * <p>Commands are decoded and validated before application. State updates are published from
     * an isolated copy under this resource's monitor, so readers cannot observe a partial command.
     * Use the term-aware overload when committed-term metadata is available.</p>
     *
     * @param index committed log index, strictly greater than the current applied watermark
     * @param bytes encoded resource command; must satisfy the resource command format and bounds
     * @throws IllegalArgumentException if the command encoding or semantic arguments are invalid
     * @throws IllegalStateException if the applied log index does not advance
     */
    @Override
    public synchronized void apply(long index, byte[] bytes) { applyCommand(index, 0, bytes); }
    /**
     * Applies a validated committed command with the consensus term needed by resource fencing.
     *
     * <p>Application is synchronized and publishes the resulting state atomically. A committed
     * business conflict is represented by the resource result protocol; it does not authorize
     * replaying the command with an unrelated identity.</p>
     *
     * @param index committed log index, strictly greater than the current applied watermark
     * @param term positive consensus term of the committed entry
     * @param bytes bounded encoded resource command
     * @throws IllegalArgumentException if term is nonpositive or command encoding/semantics are invalid
     * @throws IllegalStateException if the applied log index does not advance
     */
    @Override
    public synchronized void apply(long index, long term, byte[] bytes) {
        if (term <= 0) throw new IllegalArgumentException("Committed term must be positive");
        applyCommand(index, term, bytes);
    }
    private void applyCommand(long index, long term, byte[] bytes) {
        ResourceCommand command = ResourceCommand.decode(bytes);
        validate(command);
        if (index <= state.index) throw new IllegalStateException("Nonmonotonic apply");
        if (command.operation().equals("TIME")) {
            State next = state.copy();
            try { execute(next, command, index, term); }
            catch (Conflict rejected) { next = state.copy(); }
            next.index = index;
            long retainedTime = next.time;
            next.results.replaceAll((id, saved) -> saved.expiresAt <= retainedTime
                    ? new Saved(saved.fingerprint, new Result("EXPIRED", "", saved.result.index), saved.expiresAt) : saved);
            state = next;
            return;
        }
        String request = scoped(command.scope(), command.requestId());
        String fingerprint = hash(bytes);
        Saved prior = state.results.get(request);
        if (prior != null) {
            if (!prior.fingerprint.equals(fingerprint)) throw new IllegalArgumentException("Request identity conflict");
            state.index = index;
            return;
        }
        if (state.results.size() >= requestCapacity) {
            state.index = index; return;
        }
        State next = state.copy();
        Result result;
        try { result = execute(next, command, index, term); }
        catch (Conflict e) { next = state.copy(); result = new Result(e.getMessage(), "", index); }
        if (next.values.size() > MAX_ENTRIES || next.leases.size() > MAX_ENTRIES
                || next.transactions.size() > MAX_ENTRIES || next.locks.size() > MAX_ENTRIES) {
            next = state.copy(); result = new Result("CAPACITY", "", index);
        }
        publicationProbe.accept("COPY");
        next.index = index;
        next.results.put(request, new Saved(fingerprint, result, Math.addExact(next.time, 86_400_000L)));
        publicationProbe.accept("RESULT");
        next.events.add(new Event(index, command.scope(), command.operation()));
        if (next.events.size() > 10_000) next.events.removeFirst();
        if (!Set.of("SUBSCRIBE", "ACK", "RESUME").contains(command.operation()))
            next.journal.append(next.partition, next.ownershipEpoch, command.scope(), command.operation(), new ResourceEvent(index, result.status(), eventCommand(next, command, result, index)).encode());
        publicationProbe.accept("EVENT");
        state = next;
        publicationProbe.accept("PUBLISH");
    }
    /**
     * Advances the applied watermark across log entries that carry no resource mutation.
     *
     * <p>The watermark may stay unchanged or advance, but must never regress. Read barriers use
     * this position to decide whether the required committed prefix has been applied.</p>
     *
     * @param index applied log position, at least the current watermark
     * @throws IllegalStateException if index is lower than the current applied watermark
     */
    @Override
    public synchronized void appliedThrough(long index) {
        if (index < state.index) throw new IllegalStateException("Applied watermark cannot regress");
        state.index = index;
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized Value queryAtReadIndex(String scope, String key, long readIndex) {
        requireBarrier(readIndex); return state.values.get(scoped(scope, key));
    }
    /**
     * Returns a retained request result, including explicit expiry without re-execution.
     * @param scope authenticated scope
     * @param requestId original id
     * @param readIndex applied quorum barrier
     * @return result or null if no request committed
     * @throws IllegalStateException if barrier is not yet applied
     */
    public synchronized Result result(String scope, String requestId, long readIndex) {
        requireBarrier(readIndex); Saved saved = state.results.get(scoped(scope, requestId));
        return saved == null ? null : saved.expiresAt <= state.time
                ? new Result("EXPIRED", "", saved.result.index) : saved.result;
    }
    /**
     * Reads an authoritative lease; committed expiration makes it absent.
     * @param scope authenticated scope
     * @param key lease key
     * @param readIndex applied quorum barrier
     * @return active lease or null
     * @throws IllegalStateException if barrier is not yet applied
     */
    public synchronized Lease lease(String scope, String key, long readIndex) {
        requireBarrier(readIndex); Lease lease = state.leases.get(scoped(scope, key));
        return lease != null && lease.expiresAt > state.time ? lease : null;
    }
    /**
     * Reads transaction status and durable decision epoch.
     * @param scope authenticated scope
     * @param id transaction identity
     * @param readIndex applied quorum barrier
     * @return status with epoch, or null
     * @throws IllegalStateException if barrier is not yet applied
     */
    public synchronized Result transaction(String scope, String id, long readIndex) {
        requireBarrier(readIndex); Tx tx = state.transactions.get(scoped(scope, id));
        return tx == null ? null : new Result(tx.status, Long.toString(tx.decisionEpoch), readIndex);
    }
    /**
     * Returns an immutable event history for validation and future journal integration.
     * @param scope authenticated scope
     * @param readIndex applied quorum barrier
     * @return only this scope's events
     * @throws IllegalStateException if barrier is not yet applied
     */
    public synchronized List<Event> events(String scope, long readIndex) {
        requireBarrier(readIndex); return state.events.stream().filter(e -> e.scope.equals(scope)).toList();
    }
    /**
     * Returns prepared transaction identities for recovery scanning.
     * @param scope authenticated scope
     * @param readIndex applied quorum barrier
     * @return immutable transaction ids
     * @throws IllegalStateException if barrier is not yet applied
     */
    public synchronized List<String> prepared(String scope, long readIndex) {
        requireBarrier(readIndex); String prefix = scoped(scope, "");
        return state.transactions.entrySet().stream().filter(e -> e.getKey().startsWith(prefix)
                && (e.getValue().status.equals("PREPARED") || e.getValue().status.equals("PREPARING"))).map(e -> e.getKey().substring(prefix.length())).toList();
    }
    /**
     * Reads the durable enlisted participant identifiers used by recovery.
     * @param scope authenticated scope
     * @param id transaction identity
     * @param readIndex applied quorum barrier
     * @return immutable identifiers, empty if absent
     * @throws IllegalStateException if barrier has not been applied
     */
    public synchronized List<String> participants(String scope, String id, long readIndex) {
        requireBarrier(readIndex); Tx tx = state.transactions.get(scoped(scope, id));
        return tx == null ? List.of() : tx.participants;
    }
    /**
     * Reads a durable subscription after an applied quorum barrier; delivery does not acknowledge it.
     * @param scope authenticated scope
     * @param subscription subscription identity
     * @param maximum maximum scanned positions in [1,256]
     * @param readIndex fresh quorum barrier
     * @return bounded event page
     * @throws IllegalStateException if barrier, subscription or retained cursor is unavailable
     */
    public synchronized EventJournal.Page poll(String scope, String subscription, int maximum, long readIndex) {
        requireBarrier(readIndex); return state.journal.poll(subscription, scope, maximum, state.time);
    }
    /**
     * Atomic source image and tail starting position, captured under the same resource lock.
     * @param snapshot full versioned resource snapshot
     * @param cursor exact event barrier at snapshot capture
     * @param index applied log position
     */
    public record SnapshotTail(byte[] snapshot, EventJournal.Cursor cursor, long index) {
        /**
         * Copies the snapshot.
         */
        public SnapshotTail { snapshot = snapshot.clone(); Objects.requireNonNull(cursor); }
        /**
         * Returns an independent snapshot copy.
         * @return full source bytes
         */
        @Override public byte[] snapshot() { return snapshot.clone(); }
    }
    /**
     * Captures source state and the exact starting cursor for subsequent subscriptions without a gap.
     * @param readIndex applied quorum barrier
     * @return atomic source image and cursor
     * @throws IllegalStateException if the barrier is not applied
     */
    public synchronized SnapshotTail snapshotAndTail(long readIndex) {
        requireBarrier(readIndex); return new SnapshotTail(snapshot(), state.journal.latest(state.partition), state.index);
    }
    /**
     * Captures an authenticated scope's map projection with the exact journal barrier. Encoded bytes
     * contain version 1, entry count, then UTF key/value and long version; other tenants are excluded.
     * @param scope authenticated scope
     * @param readIndex fresh quorum barrier
     * @return bounded immutable source projection and tail cursor
     * @throws IllegalStateException for an unapplied barrier
     */
    public synchronized SnapshotTail snapshotAndTail(String scope, long readIndex) {
        requireBarrier(readIndex); String prefix = scoped(scope, "");
        publicationProbe.accept("SNAPSHOT_CAPTURE");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            var entries = state.values.entrySet().stream().filter(e -> e.getKey().startsWith(prefix)).toList();
            out.writeInt(1); out.writeInt(entries.size());
            for (var entry : entries) { out.writeUTF(entry.getKey().substring(prefix.length())); out.writeUTF(entry.getValue().value); out.writeLong(entry.getValue().version); }
            out.flush();
            if (bytes.size() > 67108864) throw new IllegalStateException("Snapshot projection limit");
            return new SnapshotTail(bytes.toByteArray(), state.journal.latest(state.partition), state.index);
        } catch (IOException e) { throw new IllegalStateException("Projection encoding", e); }
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void ownershipChanged(String partition, long epoch) {
        new EventJournal.Cursor(partition, epoch, 0);
        if (epoch < state.ownershipEpoch || !state.partition.equals("map") && !state.partition.equals(partition)) throw new IllegalArgumentException("Ownership regression/partition");
        state.partition = partition; state.ownershipEpoch = epoch;
    }
    /**
     * {@inheritDoc}
     */
    @Override public String requestIdentity(byte[] command) { return ResourceCommand.decode(command).requestId(); }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized TransferableResource copyForTransfer() {
        ReplicatedResources candidate = new ReplicatedResources(requestCapacity); candidate.restore(snapshot()); return candidate;
    }
    /**
     * {@inheritDoc}
     */
    @Override public void validateReplayCommand(byte[] bytes) { validate(ResourceCommand.decode(bytes)); }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized boolean identityConflict(byte[] bytes) {
        ResourceCommand command = ResourceCommand.decode(bytes);
        if (command.operation().equals("TIME")) return false;
        Saved saved = state.results.get(scoped(command.scope(), command.requestId()));
        return saved != null && !saved.fingerprint.equals(hash(command.encode()));
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void validateSnapshot(byte[] snapshot) {
        ReplicatedResources candidate = new ReplicatedResources(requestCapacity); candidate.restore(snapshot);
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void validate(byte[] bytes, String authenticatedScope, boolean administrative) {
        ResourceCommand command = ResourceCommand.decode(bytes); validateAdmission(command, state.index);
        if (!command.scope().equals(authenticatedScope)) throw new SecurityException("Resource scope mismatch");
        if (!administrative && Set.of("TIME", "COMMIT", "ROLLBACK", "DECIDE", "SEAL").contains(command.operation())) throw new SecurityException("Resource maintenance permission required");
    }
    /**
     * Rejects malformed and currently impossible subscription cursors before consensus admission.
     * The committed apply rechecks against its own serialized state to handle concurrent retention.
     * @param command syntactically validated command
     * @param readIndex applied authoritative barrier
     * @throws IllegalArgumentException for invalid cursor, partition or lease
     * @throws IllegalStateException for an unavailable retained cursor or subscription
     */
    public synchronized void validateAdmission(ResourceCommand command, long readIndex) {
        validate(command); requireBarrier(readIndex);
        Saved prior = state.results.get(scoped(command.scope(), command.requestId()));
        if (prior != null) {
            if (!prior.fingerprint.equals(hash(command.encode()))) throw new IllegalArgumentException("Request identity conflict");
            return;
        }
        if (!Set.of("SUBSCRIBE", "ACK", "RESUME").contains(command.operation())) return;
        try { execute(state.copy(), command, state.index + 1, 0); }
        catch (Conflict conflict) {
            if (conflict.getMessage().equals("CURSOR_EXPIRED") || conflict.getMessage().equals("SUBSCRIPTION_EXPIRED")) throw new IllegalStateException(conflict.getMessage(), conflict);
            throw new IllegalArgumentException(conflict.getMessage(), conflict);
        }
    }
    /**
     * {@inheritDoc}
     */
    @Override public void validateQuery(byte[] bytes, String authenticatedScope) {
        ResourceCommand query = ResourceCommand.decode(bytes);
        if (!query.scope().equals(authenticatedScope)) throw new SecurityException("Resource query scope mismatch");
        if (!Set.of("QUERY_READ", "QUERY_RESULT").contains(query.operation()) || query.arguments().size() != 1) throw new IllegalArgumentException("Map query kind/arity");
        resourceKey(query.arguments().getFirst());
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized byte[] query(byte[] bytes) {
        ResourceCommand query = ResourceCommand.decode(bytes); validateQuery(bytes, query.scope());
        try {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(encoded);
            if (query.operation().equals("QUERY_READ")) {
                Value value = queryAtReadIndex(query.scope(), query.arguments().getFirst(), state.index);
                out.writeBoolean(value != null); if (value != null) { out.writeUTF(value.value); out.writeLong(value.version); }
            } else {
                Result result = result(query.scope(), query.arguments().getFirst(), state.index);
                out.writeBoolean(result != null); if (result != null) { out.writeUTF(result.status); out.writeUTF(result.value); out.writeLong(result.index); }
            }
            out.flush(); return encoded.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("Map query encoding", e); }
    }
    /**
     * {@inheritDoc}
     */
    @Override public boolean timeControl(byte[] bytes) { return ResourceCommand.decode(bytes).operation().equals("TIME"); }
    /**
     * {@inheritDoc}
     */
    @Override public boolean timeSensitive(byte[] bytes) {
        return Set.of("ACQUIRE", "RENEW", "EXPIRE", "FENCED", "SUBSCRIBE", "ACK", "RESUME").contains(ResourceCommand.decode(bytes).operation());
    }
    /**
     * {@inheritDoc}
     */
    @Override public byte[] timeCommand(long milliseconds) {
        return new ResourceCommand("TIME", "maintenance", "clock-control", List.of(Long.toString(milliseconds), Long.toString(milliseconds), "0")).encode();
    }
    /**
     * Returns whether no prepared transaction or durable lock blocks migration.
     * @return true only when moving the full source state is safe
     */
    @Override public synchronized boolean transferReady() {
        return state.locks.isEmpty() && state.transactions.values().stream().noneMatch(tx -> tx.status.equals("PREPARING") || tx.status.equals("PREPARED"));
    }
    synchronized long logicalTime() { return state.time; }
    synchronized boolean atCapacity() { return state.results.size() >= requestCapacity; }
    private void requireBarrier(long index) {
        if (index < 0 || state.index < index) throw new IllegalStateException("Read index not applied");
    }
    private static String scoped(String scope, String key) {
        if (scope == null || scope.isBlank() || scope.indexOf('\0') >= 0 || key == null || key.indexOf('\0') >= 0
                || key.length() > 1024) throw new IllegalArgumentException("Invalid scope/key");
        return scope + '\0' + key;
    }
    static void validate(ResourceCommand c) {
        if (!OPERATIONS.contains(c.operation())) throw new IllegalArgumentException("Unknown operation/version");
        var a = c.arguments();
        if (!c.operation().equals("TIME")) {
            if (a.isEmpty()) throw new IllegalArgumentException("Missing identifier");
            resourceKey(a.getFirst());
        }

        int minimum = switch (c.operation()) {
            case "SUBSCRIBE" -> 6; case "ACK" -> 5; case "RESUME" -> 2;
            case "BATCH" -> 3; case "TIME" -> 3; case "ACQUIRE" -> 4; case "RENEW" -> 5;
            case "CANCEL", "EXPIRE" -> 2; case "FENCED" -> 5; case "BEGIN", "SEAL" -> 1;
            case "ENLIST", "DECIDE", "COMMIT", "ROLLBACK", "LOCK_READ", "LOCK_RANGE" -> 2; case "PREPARE" -> 2;
            default -> throw new IllegalArgumentException("Unknown operation");
        };
        if (a.size() < minimum) throw new IllegalArgumentException("Missing command arguments");
        if (c.operation().equals("BATCH") && a.size() % 3 != 0) throw new IllegalArgumentException("Invalid batch");
        if (!c.operation().equals("BATCH") && !c.operation().equals("PREPARE") && a.size() != minimum)
            throw new IllegalArgumentException("Unexpected arguments");
        switch (c.operation()) {
            case "SUBSCRIBE" -> { if (!a.get(1).isEmpty()) resourceKey(a.get(1)); new EventJournal.Cursor(a.get(2), number(a.get(3)), number(a.get(4))); duration(a.get(5)); }
            case "ACK" -> { new EventJournal.Cursor(a.get(1), number(a.get(2)), number(a.get(3))); duration(a.get(4)); }
            case "RESUME" -> duration(a.get(1));
            case "BATCH" -> { for (int i = 0; i < a.size(); i += 3) { resourceKey(a.get(i)); number(a.get(i + 1)); } }
            case "TIME" -> { for (String value : a) number(value); }
            case "ACQUIRE" -> { resourceKey(a.get(0)); number(a.get(2)); number(a.get(3)); }
            case "RENEW" -> { resourceKey(a.get(0)); number(a.get(1)); number(a.get(3)); number(a.get(4)); }
            case "CANCEL", "EXPIRE", "COMMIT", "ROLLBACK" -> number(a.get(1));
            case "FENCED" -> { resourceKey(a.get(0)); number(a.get(1)); number(a.get(2)); resourceKey(a.get(3)); }
            case "PREPARE" -> validateOperations(a.subList(1, a.size()));
            case "LOCK_READ", "ENLIST" -> resourceKey(a.get(1));
            case "LOCK_RANGE" -> rangeKey(a.get(1));
            default -> { }
        }
        for (String value : a) if (value.indexOf('\0') >= 0 || value.length() > 32768)
            throw new IllegalArgumentException("Argument bounds");
    }
    private static Result execute(State s, ResourceCommand c, long index, long term) {
        List<String> a = c.arguments(); String key = scoped(c.scope(), a.get(0)); String value = "";
        switch (c.operation()) {
            case "SUBSCRIBE", "ACK", "RESUME" -> {
                try {
                    if (c.operation().equals("SUBSCRIBE") && !a.get(2).equals(s.partition) || c.operation().equals("ACK") && !a.get(1).equals(s.partition)) throw new IllegalArgumentException("Foreign partition");
                    if (c.operation().equals("SUBSCRIBE")) s.journal.subscribe(a.get(0), c.scope(), a.get(1), new EventJournal.Cursor(a.get(2), number(a.get(3)), number(a.get(4))), number(a.get(5)), s.time);
                    else if (c.operation().equals("ACK")) s.journal.ack(a.get(0), c.scope(), new EventJournal.Cursor(a.get(1), number(a.get(2)), number(a.get(3))), number(a.get(4)), s.time);
                    else { EventJournal.Cursor cursor = s.journal.resume(a.get(0), c.scope(), number(a.get(1)), s.time); value = cursor.partition() + ":" + cursor.epoch() + ":" + cursor.sequence(); }
                } catch (IllegalStateException | IllegalArgumentException e) { throw new Conflict(e.getMessage()); }
            }
            case "BATCH" -> {
                for (int i = 0; i < a.size(); i += 3) {
                    String k = scoped(c.scope(), a.get(i)); unlocked(s, k, null);
                    Value old = s.values.get(k); long expected = number(a.get(i + 1));
                    check((old == null ? 0 : old.version) == expected, "CONFLICT");
                    s.values.put(k, new Value(a.get(i + 2), index));
                }
            }
            case "TIME" -> {
                long proposed = number(a.get(0)), reference = number(a.get(1)), uncertainty = number(a.get(2));
                check(proposed >= 0 && reference >= 0 && uncertainty >= 0 && uncertainty <= 500 && Math.abs(proposed - reference) <= 500,
                        "CLOCK_UNSAFE");
                check(proposed <= Long.MAX_VALUE - 86_400_000L, "CLOCK_UNSAFE");
                if (s.timeInitialized && proposed <= s.time) return new Result("OK", "", index);
                if (!s.timeInitialized) {
                    // Bootstrap establishes the epoch; it must not instantly expire pre-clock results or leases.
                    s.results.replaceAll((id, saved) -> new Saved(saved.fingerprint, saved.result,
                            Math.addExact(proposed, saved.expiresAt)));
                    s.leases.replaceAll((id, lease) -> new Lease(lease.owner, lease.version,
                            Math.addExact(proposed, lease.expiresAt), lease.fence, lease.leadershipEpoch));
                    s.journal.bootstrapTime(proposed);
                    s.timeInitialized = true;
                }
                s.time = proposed;
            }
            case "ACQUIRE" -> {
                Lease old = s.leases.get(key); check(old == null || old.expiresAt <= s.time, "CONFLICT");
                long duration = duration(a.get(2)), epoch = term > 0 ? term : number(a.get(3));
                check(epoch > 0, "CONFLICT");
                Lease lease = new Lease(a.get(1), index, Math.addExact(s.time, duration), ++s.fence, epoch);
                s.leases.put(key, lease); value = Long.toString(lease.fence);
            }
            case "RENEW" -> {
                Lease old = s.leases.get(key); check(old != null && old.expiresAt > s.time
                        && old.version == number(a.get(1)) && old.owner.equals(a.get(2)), "CONFLICT");
                long epoch = term > 0 ? term : number(a.get(4)); check(epoch >= old.leadershipEpoch, "CONFLICT");
                s.leases.put(key, new Lease(old.owner, index, Math.addExact(s.time, duration(a.get(3))),
                        ++s.fence, epoch)); value = Long.toString(s.fence);
            }
            case "CANCEL", "EXPIRE" -> {
                Lease old = s.leases.get(key); check(old != null && old.version == number(a.get(1)), "CONFLICT");
                if (c.operation().equals("EXPIRE")) check(old.expiresAt <= s.time, "CONFLICT");
                s.leases.remove(key);
            }
            case "FENCED" -> {
                Lease old = s.leases.get(key); check(old != null && old.expiresAt > s.time
                        && old.fence == number(a.get(1)) && old.leadershipEpoch == number(a.get(2)), "CONFLICT");
                String target = scoped(c.scope(), a.get(3)); unlocked(s, target, null);
                s.values.put(target, new Value(a.get(4), index));
            }
            case "BEGIN" -> {
                check(!s.transactions.containsKey(key), "CONFLICT");
                s.transactions.put(key, new Tx("PREPARING", 0, List.of(), List.of()));
            }
            case "ENLIST" -> {
                Tx tx = required(s, key); check(tx.status.equals("PREPARING"), "CONFLICT");
                var participants = new TreeSet<>(tx.participants); participants.add(a.get(1));
                check(participants.size() <= 4096, "CAPACITY");
                s.transactions.put(key, new Tx(tx.status, 0, List.copyOf(participants), List.of()));
            }
            case "SEAL" -> {
                Tx tx = required(s, key); check(tx.status.equals("PREPARING") || tx.status.equals("SEALED"), "CONFLICT");
                s.transactions.put(key, new Tx("SEALED", 0, tx.participants, List.of()));
            }
            case "DECIDE" -> {
                Tx tx = required(s, key); String decision = a.get(1);
                check(Set.of("COMMITTED", "ABORTED").contains(decision), "CONFLICT");
                if (!(tx.status.equals("PREPARING") || tx.status.equals("SEALED"))) {
                    check(tx.status.equals(decision), "CONFLICT"); value = Long.toString(tx.decisionEpoch); break;
                }
                s.transactions.put(key, new Tx(decision, index, tx.participants, List.of())); value = Long.toString(index);
            }
            case "LOCK_READ", "LOCK_RANGE" -> {
                Tx tx = s.transactions.get(key);
                check(tx == null || tx.status.equals("PREPARING"), "CONFLICT");
                String resource = scoped(c.scope(), a.get(1));
                var ops = new ArrayList<String>(tx == null ? List.of() : tx.operations);
                if (c.operation().equals("LOCK_RANGE")) {
                    for (var lock : s.locks.entrySet()) if (lock.getKey().startsWith(scoped(c.scope(), "")))
                        check(lock.getValue().equals(key), "CONFLICT");
                    s.locks.put(scoped(c.scope(), "*"), key);
                    ops.addAll(List.of("RANGE", a.get(1)));
                } else {
                    unlocked(s, resource, key); s.locks.put(resource, key);
                    Value read = s.values.get(resource);
                    ops.addAll(List.of("READ", a.get(1), Long.toString(read == null ? 0 : read.version)));
                    value = read == null ? "" : read.value;
                }
                check(ops.size() <= 4096, "CAPACITY");
                s.transactions.put(key, new Tx("PREPARING", 0, List.of(), List.copyOf(ops)));
            }
            case "PREPARE" -> {
                Tx old = s.transactions.get(key);
                List<String> ops = List.copyOf(a.subList(1, a.size()));
                if (old != null && !old.status.equals("PREPARING")) {
                    check(old.status.equals("PREPARED") && old.operations.equals(ops), "CONFLICT"); break;
                }
                if (old != null) {
                    var combined = new ArrayList<String>(old.operations); combined.addAll(ops); ops = List.copyOf(combined);
                }
                check(ops.size() <= 4096, "CAPACITY");
                validateOperations(ops);
                for (int i = 0; i < ops.size();) {
                    String op = ops.get(i++), k = scoped(c.scope(), ops.get(i++));
                    if (op.equals("RANGE")) {
                        for (var lock : s.locks.entrySet()) if (lock.getKey().startsWith(scoped(c.scope(), "")))
                            check(lock.getValue().equals(key), "CONFLICT");
                        s.locks.put(scoped(c.scope(), "*"), key);
                    } else {
                        unlocked(s, k, key); s.locks.put(k, key);
                        if (op.equals("READ")) {
                            Value oldValue = s.values.get(k);
                            check((oldValue == null ? 0 : oldValue.version) == number(ops.get(i++)), "CONFLICT");
                        } else i++;
                    }
                }
                s.transactions.put(key, new Tx("PREPARED", 0, List.of(), ops));
                return new Result("PREPARED", "", index);
            }
            case "COMMIT", "ROLLBACK" -> {
                Tx tx = required(s, key); long epoch = number(a.get(1)); check(epoch > 0, "CONFLICT");
                String decision = c.operation().equals("COMMIT") ? "COMMITTED" : "ABORTED";
                if (!(tx.status.equals("PREPARED") || tx.status.equals("PREPARING") && decision.equals("ABORTED"))) {
                    check(tx.status.equals(decision) && tx.decisionEpoch == epoch, "CONFLICT");
                    return new Result(decision, Long.toString(epoch), index);
                }
                if (decision.equals("COMMITTED")) {
                    for (int i = 0; i < tx.operations.size();) {
                        String op = tx.operations.get(i++), k = scoped(c.scope(), tx.operations.get(i++));
                        if (op.equals("WRITE")) s.values.put(k, new Value(tx.operations.get(i++), index));
                        else if (op.equals("READ")) i++;
                    }
                }
                s.locks.entrySet().removeIf(e -> e.getValue().equals(key));
                s.transactions.put(key, new Tx(decision, epoch, List.of(), tx.operations));
                return new Result(decision, Long.toString(epoch), index);
            }
            default -> throw new IllegalArgumentException("Unknown operation");
        }
        check(s.values.size() <= MAX_ENTRIES && s.leases.size() <= MAX_ENTRIES
                && s.transactions.size() <= MAX_ENTRIES, "CAPACITY");
        return new Result("OK", value, index);
    }
    private static void validateOperations(List<String> ops) {
        try {
            for (int i = 0; i < ops.size();) {
                String op = ops.get(i++); String key = ops.get(i++);
                if (op.equals("RANGE")) rangeKey(key); else resourceKey(key);
                if (op.equals("READ")) number(ops.get(i++));
                else if (op.equals("WRITE")) ops.get(i++);
                else if (!op.equals("RANGE")) throw new IllegalArgumentException("Unknown transaction operation");
            }
        } catch (IndexOutOfBoundsException e) { throw new IllegalArgumentException("Truncated operations", e); }
    }
    private static void unlocked(State s, String key, String owner) {
        String lock = s.locks.get(key), range = s.locks.get(key.substring(0, key.indexOf('\0') + 1) + '*');
        check((lock == null || lock.equals(owner)) && (range == null || range.equals(owner)), "CONFLICT");
    }
    private static Tx required(State s, String key) { Tx tx = s.transactions.get(key); check(tx != null, "CONFLICT"); return tx; }
    private static void rangeKey(String key) {
        if (key.equals("*") || key.length() > 1024 || key.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Reserved/invalid range prefix");
    }
    private static void resourceKey(String key) {
        if (key.isBlank() || key.equals("*") || key.length() > 1024 || key.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Reserved/invalid resource key");
    }
    private static long number(String value) { return Long.parseLong(value); }
    private static long duration(String value) { long n = number(value); check(n > 0 && n <= 86_400_000, "CONFLICT"); return n; }
    private static void check(boolean ok, String status) { if (!ok) throw new Conflict(status); }
    private static final class Conflict extends RuntimeException { Conflict(String message) { super(message); } }
    private static ResourceCommand eventCommand(State next, ResourceCommand command, Result result, long index) {
        if (!command.operation().equals("COMMIT") || !result.status.equals("COMMITTED")) return command;
        Tx transaction = next.transactions.get(scoped(command.scope(), command.arguments().getFirst()));
        TreeMap<String, String> writes = new TreeMap<>();
        if (transaction != null) {
            var operations = transaction.operations;
            for (int i = 0; i < operations.size();) {
                String operation = operations.get(i++), key = operations.get(i++);
                if (operation.equals("RANGE")) continue;
                String value = operations.get(i++);
                Value committed = next.values.get(scoped(command.scope(), key));
                if (operation.equals("WRITE") && committed != null && committed.version == index) writes.put(key, value);
            }
        }
        if (writes.isEmpty()) return command;
        List<String> triples = new ArrayList<>();
        writes.forEach((key, value) -> { triples.add(key); triples.add("0"); triples.add(value); });
        return new ResourceCommand("BATCH", command.scope(), command.requestId(), triples);
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized byte[] snapshot() {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(2); out.writeInt(requestCapacity); out.writeLong(state.index); out.writeLong(state.time); out.writeLong(state.fence); out.writeBoolean(state.timeInitialized);
            out.writeInt(state.values.size());
            for (var e : state.values.entrySet()) { out.writeUTF(e.getKey()); out.writeUTF(e.getValue().value); out.writeLong(e.getValue().version); }
            out.writeInt(state.leases.size());
            for (var e : state.leases.entrySet()) {
                out.writeUTF(e.getKey()); Lease l = e.getValue(); out.writeUTF(l.owner); out.writeLong(l.version);
                out.writeLong(l.expiresAt); out.writeLong(l.fence); out.writeLong(l.leadershipEpoch);
            }
            out.writeInt(state.results.size());
            for (var e : state.results.entrySet()) {
                out.writeUTF(e.getKey()); Saved r = e.getValue(); out.writeUTF(r.fingerprint); out.writeUTF(r.result.status);
                out.writeUTF(r.result.value); out.writeLong(r.result.index); out.writeLong(r.expiresAt);
            }
            out.writeInt(state.transactions.size());
            for (var e : state.transactions.entrySet()) {
                out.writeUTF(e.getKey()); Tx t = e.getValue(); out.writeUTF(t.status); out.writeLong(t.decisionEpoch);
                strings(out, t.participants); strings(out, t.operations);
            }
            out.writeInt(state.locks.size());
            for (var e : state.locks.entrySet()) { out.writeUTF(e.getKey()); out.writeUTF(e.getValue()); }
            out.writeInt(state.events.size());
            for (Event e : state.events) { out.writeLong(e.index); out.writeUTF(e.scope); out.writeUTF(e.operation); }
            out.writeUTF(state.partition); out.writeLong(state.ownershipEpoch);
            byte[] journal = state.journal.snapshot(); out.writeInt(journal.length); out.write(journal);
            out.flush(); return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("Snapshot encoding", e); }
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void restore(byte[] bytes) {
        if (bytes.length > 268_435_456) throw new IllegalArgumentException("Snapshot too large");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            int version = in.readInt();
            if (version != 1 && version != 2) throw new IllegalArgumentException("Unsupported snapshot version");
            if (in.readInt() != requestCapacity) throw new IllegalArgumentException("Snapshot request capacity mismatch");
            State next = new State(); next.index = in.readLong(); next.time = in.readLong(); next.fence = in.readLong(); next.timeInitialized = in.readBoolean();
            for (int n = count(in); n > 0; n--) next.values.put(in.readUTF(), new Value(in.readUTF(), in.readLong()));
            for (int n = count(in); n > 0; n--) next.leases.put(in.readUTF(), new Lease(in.readUTF(), in.readLong(), in.readLong(), in.readLong(), in.readLong()));
            for (int n = count(in); n > 0; n--) next.results.put(in.readUTF(), new Saved(in.readUTF(), new Result(in.readUTF(), in.readUTF(), in.readLong()), in.readLong()));
            for (int n = count(in); n > 0; n--) next.transactions.put(in.readUTF(), new Tx(in.readUTF(), in.readLong(), strings(in), strings(in)));
            for (int n = count(in); n > 0; n--) next.locks.put(in.readUTF(), in.readUTF());
            for (int n = count(in); n > 0; n--) next.events.add(new Event(in.readLong(), in.readUTF(), in.readUTF()));
            if (version == 2) {
                next.partition = in.readUTF(); next.ownershipEpoch = in.readLong();
                if (next.partition.isBlank() || next.partition.length() > 1024 || next.partition.indexOf('\0') >= 0 || next.ownershipEpoch < 0) throw new IllegalArgumentException("Partition identity");
                int size = in.readInt(); if (size < 0 || size > in.available()) throw new IllegalArgumentException("Journal snapshot size");
                next.journal = EventJournal.restore(in.readNBytes(size));
            }
            if (next.results.size() > requestCapacity) throw new IllegalArgumentException("Snapshot exceeds configured request capacity");
            if (in.available() != 0 || next.index < 0 || next.time < 0 || next.fence < 0) throw new IllegalArgumentException("Corrupt snapshot");
            state = next;
        } catch (IOException e) { throw new IllegalArgumentException("Corrupt snapshot", e); }
    }
    private static int count(DataInputStream in) throws IOException {
        int count = in.readInt(); if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Snapshot count"); return count;
    }
    private static void strings(DataOutputStream out, List<String> strings) throws IOException {
        out.writeInt(strings.size()); for (String value : strings) out.writeUTF(value);
    }
    private static List<String> strings(DataInputStream in) throws IOException {
        var result = new ArrayList<String>(); for (int n = count(in); n > 0; n--) result.add(in.readUTF()); return List.copyOf(result);
    }
}
