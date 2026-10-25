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

import java.io.IOException;
import java.util.*;
import org.j7m.middleware.annotations.NoRemoteExport;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.components.ManagedInvocationLifetime;
import org.j7m.middleware.distributed.*;

/**
 * Same-invocation strict-2PL resource capability; commits belong exclusively to the host finalizer.
 * Reads acquire durable native locks before observing values. Writes are local intents until prepare
 * and a durable coordinator decision. At most64 distinct reads/ranges/writes and256KiB textual data
 * are admitted. Prepared locks never expire merely because the caller or coordinator disappeared.
 */
@NoRemoteExport
public final class ManagedResourceTransactionContext {
    ManagedDeduplicationContext deduplication;
    final String profile;
    final ManagedResourceTransactionPolicy.Registration registration;
    final InvocationContext invocation;
    final RecoverableTransactions transactions;
    private final String id = UUID.randomUUID().toString().replace("-", "");
    private final Map<String, Map<String, ReplicatedResources.Value>> reads = new TreeMap<>();
    private final Map<String, Map<String, String>> writes = new TreeMap<>();
    private final Map<String, Set<String>> ranges = new TreeMap<>();
    private final Set<String> enlisted = new TreeSet<>();
    private boolean sealed;
    private volatile boolean rollbackOnly;
    boolean begun;
    private int operations, bytes, request;

    ManagedResourceTransactionContext(String profile, ManagedResourceTransactionPolicy.Registration registration, InvocationContext invocation) {
        this.profile = profile; this.registration = registration; this.invocation = invocation;
        transactions = new RecoverableTransactions(registration.coordinator(), registration.participants());
    }
    /**
     * Returns the durable opaque transaction ID for authorized native recovery/status operations.
     * @return unique ID within the backend authorization scope; never a bearer permission
     */
    public String transactionId() { return id; }
    /**
     * Reads a locked value, including this transaction's staged write and the observed base version.
     * A staged value's version is its observed pre-commit version, not a future commit index.
     * @param participant explicitly host-registered participant ID
     * @param key nonblank key, at most128 characters without NUL or reserved star
     * @return locked value/version, or null for absent
     * @throws IOException for unavailable/uncertain native operation; transaction becomes rollback-only
     * @throws MiddlewareException for invalid bounds, conflicts or exhausted capacity
     * @throws IllegalStateException outside the owning open USER context
     */
    public ReplicatedResources.Value read(String participant, String key) throws IOException {
        user(); key(key); ResourceEndpoint endpoint = participant(participant);
        Map<String, ReplicatedResources.Value> observed = reads.computeIfAbsent(participant, ignored -> new TreeMap<>());
        if (!observed.containsKey(key)) {
            reserve(key); enlist(participant);
            try {
                var locked = transactions.read(id, participant, id + ":read:" + ++request, key, TransactionPolicySupport.deadline(invocation));
                if (!locked.status().equals("OK")) throw TransactionPolicySupport.failure(MiddlewareErrorCode.UNAVAILABLE);
                observed.put(key, endpoint.read(key, TransactionPolicySupport.deadline(invocation)));
            } catch (IOException | RuntimeException failure) { rollbackOnly = true; throw failure; }
        }
        var value = observed.get(key); var pending = writes.get(participant);
        return pending != null && pending.containsKey(key) ? new ReplicatedResources.Value(pending.get(key), value == null ? 0 : value.version()) : value;
    }
    /**
     * Stages a write after locking its observed base value; publication occurs only with durable COMMIT.
     * @param participant registered participant ID
     * @param key bounded resource key
     * @param value at most16384 characters without NUL
     * @throws IOException for unavailable/uncertain read or enlist
     * @throws MiddlewareException for invalid value or capacity
     * @throws IllegalStateException outside the owning USER phase
     */
    public void write(String participant, String key, String value) throws IOException {
        user();
        if (value == null || value.length() > 16384 || value.indexOf(0) >= 0) throw TransactionPolicySupport.failure(MiddlewareErrorCode.INVALID_ARGUMENT);
        read(participant, key); var pending = writes.computeIfAbsent(participant, ignored -> new TreeMap<>());
        if (!pending.containsKey(key)) reserve(value);
        else { int updated = bytes - TransactionPolicySupport.bytes(pending.get(key)) + TransactionPolicySupport.bytes(value); if (updated > 262144) throw TransactionPolicySupport.failure(MiddlewareErrorCode.OVERLOADED); bytes = updated; }
        pending.put(key, value);
    }
    /**
     * Acquires the native conservative tenant-wide predicate lock for a logical prefix.
     * The lock protects subsequent participating reads and prevents phantoms through prepare/recovery;
     * this method does not invent a separate range query or expose a raw endpoint.
     * @param participant registered participant ID
     * @param prefix at most128 characters, possibly empty, without NUL or reserved star
     * @throws IOException for unavailable/uncertain native lock
     * @throws MiddlewareException for invalid bounds, conflict or capacity
     */
    public void lockRange(String participant, String prefix) throws IOException {
        user(); participant(participant);
        if (prefix == null || prefix.length() > 128 || prefix.equals("*") || prefix.indexOf(0) >= 0) throw TransactionPolicySupport.failure(MiddlewareErrorCode.INVALID_ARGUMENT);
        var selected = ranges.computeIfAbsent(participant, ignored -> new TreeSet<>());
        if (selected.contains(prefix)) return;
        reserve(prefix); enlist(participant);
        try {
            var result = transactions.lockRange(id, participant, id + ":range:" + ++request, prefix, TransactionPolicySupport.deadline(invocation));
            if (!result.status().equals("OK")) throw TransactionPolicySupport.failure(MiddlewareErrorCode.UNAVAILABLE);
            selected.add(prefix);
        } catch (IOException | RuntimeException failure) { rollbackOnly = true; throw failure; }
    }
    /**
     * Marks the entire REQUIRED transaction rollback-only; a nested caller cannot clear this flag.
     * @throws IllegalStateException outside the owning open USER scope
     */
    public void setRollbackOnly() { user(); rollbackOnly = true; }
    /**
     * Returns the sticky rollback-only state without granting commit authority.
     * @return whether a nested failure or explicit request requires rollback
     */
    public boolean rollbackOnly() { return rollbackOnly; }
    private void user() {
        if (ManagedInvocationLifetime.currentContext(ManagedResourceTransactionContext.class) != this
                || !ManagedInvocationLifetime.currentUserWorkOpen() || sealed || !begun) throw new IllegalStateException("RESOURCE_TRANSACTION_CLOSED");
        TransactionPolicySupport.deadline(invocation);
    }
    private void reserve(String value) {
        int additional = TransactionPolicySupport.bytes(value);
        if (operations >= 64 || bytes + additional > 262144) throw TransactionPolicySupport.failure(MiddlewareErrorCode.OVERLOADED);
        operations++; bytes += additional;
    }
    private ResourceEndpoint participant(String name) {
        ResourceEndpoint endpoint = registration.participants().get(name);
        if (endpoint == null) throw TransactionPolicySupport.failure(MiddlewareErrorCode.INVALID_ARGUMENT);
        return endpoint;
    }
    private void enlist(String participant) throws IOException {
        if (enlisted.contains(participant)) return;
        try {
            var result = transactions.enlist(id, participant, TransactionPolicySupport.deadline(invocation));
            if (!result.status().equals("OK")) throw TransactionPolicySupport.failure(MiddlewareErrorCode.UNAVAILABLE);
            enlisted.add(participant);
        } catch (IOException | RuntimeException failure) { rollbackOnly = true; throw failure; }
    }
    private static void key(String key) {
        if (key == null || key.isBlank() || key.length() > 128 || key.equals("*") || key.indexOf(0) >= 0) throw TransactionPolicySupport.failure(MiddlewareErrorCode.INVALID_ARGUMENT);
    }
    Map<String, List<String>> seal() {
        sealed = true; Map<String, List<String>> result = new TreeMap<>();
        for (String participant : enlisted) {
            List<String> ops = new ArrayList<>();
            for (var entry : reads.getOrDefault(participant, Map.of()).entrySet()) ops.addAll(List.of("READ", entry.getKey(), Long.toString(entry.getValue() == null ? 0 : entry.getValue().version())));
            for (String prefix : ranges.getOrDefault(participant, Set.of())) ops.addAll(List.of("RANGE", prefix));
            for (var entry : writes.getOrDefault(participant, Map.of()).entrySet()) ops.addAll(List.of("WRITE", entry.getKey(), entry.getValue()));
            result.put(participant, List.copyOf(ops));
        }
        return Map.copyOf(result);
    }
    void failNested() { rollbackOnly = true; }
}
