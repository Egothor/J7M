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

/**
 * Durable RPC invocation journal. Atomic effects are limited to the same execution partition's map;
 * external effects require an idempotency protocol or explicit UNKNOWN/reconciliation. Retained IDs
 * expire publicly and never become eligible for automatic re-execution.
 */
public final class DeduplicatedRpc {
    private final ExecutionClient client;
    /**
     * Binds a scope-authorized execution transport.
     * @param client execution transport
     */
    public DeduplicatedRpc(ExecutionClient client) { this.client = Objects.requireNonNull(client); }
    /**
     * Atomically records one invocation's outcome and protected CAS effects.
     * @param operation stable operation identity for transport recovery
     * @param request invocation identity
     * @param inputHash lowercase SHA-256 input digest
     * @param retentionMillis positive duration at most one day
     * @param result persisted result
     * @param error persisted error, empty for success
     * @param effects protected map CAS triples
     * @param deadline operation deadline
     * @return identical stored outcome for duplicates, CONFLICT or EXPIRED
     * @throws IOException on unavailable quorum or unknown completion
     */
    public ExecutionResult invoke(String operation, String request, String inputHash, long retentionMillis, String result, String error, List<String> effects, Instant deadline) throws IOException {
        List<String> args = new ArrayList<>(List.of(request, inputHash, Long.toString(retentionMillis), result, error, client.context(), "false")); args.addAll(effects); return client.execute("R_INVOKE", operation, args, deadline);
    }
    /**
     * Records an in-progress invocation before any non-atomic computation or external call.
     * Each physical method call has a fresh delivery attempt; transport retries keep that attempt.
     * Only the first physical claim can return acquired=true. A new call with the same operation ID
     * returns acquired=false and UNKNOWN for unresolved external work, including after restart.
     * @param operation stable operation identity
     * @param request invocation identity
     * @param inputHash SHA-256 input digest
     * @param retentionMillis retention duration
     * @param external whether the subsequent effect lacks a transactional middleware protocol
     * @param deadline operation deadline
     * @return claim status and fence; duplicate claims return persisted in-progress/unknown state
     * @throws IOException on unavailable quorum or unknown completion
     */
    public ExecutionResult claim(String operation, String request, String inputHash, long retentionMillis, boolean external, Instant deadline) throws IOException { return client.execute("R_CLAIM", operation, List.of(request, inputHash, Long.toString(retentionMillis), Boolean.toString(external), client.context(), UUID.randomUUID().toString()), deadline); }
    /**
     * Finishes a claim atomically with its protected CAS effects.
     * @param operation stable operation identity
     * @param request invocation identity
     * @param hash original input digest
     * @param fence claim fence
     * @param result stored result
     * @param error stored error, empty for success
     * @param effects protected map CAS triples
     * @param deadline operation deadline
     * @return persisted terminal outcome or UNKNOWN/FENCED
     * @throws IOException on unavailable quorum or unknown completion
     */
    public ExecutionResult complete(String operation, String request, String hash, long fence, String result, String error, List<String> effects, Instant deadline) throws IOException { return finish("R_COMPLETE", operation, request, hash, fence, result, error, effects, deadline); }
    /**
     * Marks an ambiguous external effect without retrying it.
     * @param operation stable operation identity
     * @param request invocation identity
     * @param hash original input digest
     * @param fence claim fence
     * @param deadline operation deadline
     * @return UNKNOWN or CONFLICT
     * @throws IOException on unavailable quorum
     */
    public ExecutionResult unknown(String operation, String request, String hash, long fence, Instant deadline) throws IOException { return client.execute("R_UNKNOWN", operation, List.of(request, hash, Long.toString(fence)), deadline); }
    /**
     * Privileged reconciliation records an externally established outcome; the hosting principal
     * must have administrative capability, enforced before consensus admission.
     * @param operation stable operation identity
     * @param request invocation identity
     * @param hash original input digest
     * @param fence original claim fence
     * @param result established result
     * @param error established error
     * @param effects protected map CAS triples
     * @param deadline operation deadline
     * @return reconciled terminal outcome
     * @throws IOException on unavailable quorum
     * @throws SecurityException when the server-bound principal lacks reconciliation capability
     */
    public ExecutionResult reconcile(String operation, String request, String hash, long fence, String result, String error, List<String> effects, Instant deadline) throws IOException { return finish("R_RECONCILE", operation, request, hash, fence, result, error, effects, deadline); }
    private ExecutionResult finish(String type, String operation, String request, String hash, long fence, String result, String error, List<String> effects, Instant deadline) throws IOException { List<String> args = new ArrayList<>(List.of(request, hash, Long.toString(fence), result, error, "false")); args.addAll(effects); return client.execute(type, operation, args, deadline); }
    /**
     * Reads an invocation's persisted outcome or explicit retention expiry.
     * @param request invocation identity
     * @param deadline operation deadline
     * @return durable outcome, NOT_FOUND or EXPIRED
     * @throws IOException on unavailable quorum
     */
    public ExecutionResult status(String request, Instant deadline) throws IOException { return client.query("E_RPC", request, deadline); }
}
