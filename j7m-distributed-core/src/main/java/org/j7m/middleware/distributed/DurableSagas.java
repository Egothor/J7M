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
import java.time.Instant;
import java.util.*;

/**
 * Durable saga protocol with explicit external-step intents, owner fences and bounded compensation
 * retries. Call step before an effect and completeStep only after its outcome is established. A lost
 * non-idempotent reply is UNKNOWN; this API never invokes or automatically repeats external effects.
 */
public final class DurableSagas {
    private final ExecutionClient client;
    /**
     * Established no-effect failure versus an uncertain external outcome.
     */
    public enum StepResolution {
        /**
         * Independently established failure with no external effect; not an arbitrary exception.
         */ FAILED_NO_EFFECT,
        /**
         * External effect may have happened; progression requires privileged reconciliation.
         */ UNKNOWN
    }
    /**
     * Binds a scope-authorized execution transport.
     * @param client execution transport
     */
    public DurableSagas(ExecutionClient client) { this.client = Objects.requireNonNull(client); }
    /**
     * Starts durable saga state and captures trace context.
     * @param request stable operation identity
     * @param saga saga identity
     * @param input bounded input
     * @param maxCompensationAttempts attempt limit in [1,1000]
     * @param backoffMillis positive exponential retry base at most one hour
     * @param deadline operation deadline
     * @return READY or CONFLICT
     * @throws IOException on unavailable quorum or unknown completion
     */
    public ExecutionResult start(String request, String saga, String input, int maxCompensationAttempts, long backoffMillis, Instant deadline) throws IOException { return client.execute("S_START", request, List.of(saga, input, Integer.toString(maxCompensationAttempts), Long.toString(backoffMillis), client.context()), deadline); }
    /**
     * Claims a saga after prior ownership expires; unresolved non-idempotent intent yields UNKNOWN.
     * @param request stable operation identity
     * @param saga saga identity
     * @param owner worker identity
     * @param leaseMillis bounded ownership duration
     * @param deadline operation deadline
     * @return ownership fence, persisted progress and context
     * @throws IOException on unhealthy clock, unavailable quorum or unknown completion
     */
    public ExecutionResult claim(String request, String saga, String owner, long leaseMillis, Instant deadline) throws IOException { return client.execute("S_CLAIM", request, List.of(saga, owner, Long.toString(leaseMillis)), deadline); }
    /**
     * Checks the exact current live owner after host-generated measured time advancement.
     * This does not renew the lease or change run state/version. A fresh physical request identity
     * is mandatory for a new admission; replayed receipts describe history and grant no new authority.
     * @param request fresh host-generated physical verification identity
     * @param saga durable saga identity
     * @param owner exact claimed owner
     * @param fence exact claimed fence
     * @param deadline original operation deadline
     * @return compact current active state or FENCED/UNKNOWN/terminal rejection
     * @throws IOException on unavailable quorum, unhealthy clock or ambiguous verification
     */
    public ExecutionResult verify(String request,String saga,String owner,long fence,Instant deadline) throws IOException {
        return client.execute("S_VERIFY",request,List.of(saga,owner,Long.toString(fence)),deadline);
    }
    /**
     * Records a step intent before invoking an external handler; its stable key belongs in that
     * handler's idempotency protocol. Claim takeover must inspect UNKNOWN before running a handler.
     * @param request stable operation identity
     * @param saga saga identity
     * @param owner current owner
     * @param fence current owner token
     * @param step unique step name
     * @param idempotencyKey stable external protocol key
     * @param compensation bounded compensation input
     * @param idempotent whether the handler honors its key on replay
     * @param terminal whether successful completion ends the saga
     * @param deadline operation deadline
     * @return persisted intent or IN_PROGRESS/FENCED
     * @throws IOException on unavailable quorum or unknown completion
     */
    public ExecutionResult step(String request, String saga, String owner, long fence, String step, String idempotencyKey, String compensation, boolean idempotent, boolean terminal, Instant deadline) throws IOException { return client.execute("S_STEP", request, List.of(saga, owner, Long.toString(fence), step, idempotencyKey, compensation, Boolean.toString(idempotent), Boolean.toString(terminal)), deadline); }
    /**
     * Completes a step and optional protected-map effects atomically under the live owner fence.
     * @param request stable operation identity
     * @param saga saga identity
     * @param owner current owner
     * @param fence owner token
     * @param step step name
     * @param result established step result
     * @param effects protected map CAS triples
     * @param deadline operation deadline
     * @return persisted state or FENCED/CONFLICT
     * @throws IOException on unavailable quorum or unknown completion
     */
    public ExecutionResult completeStep(String request, String saga, String owner, long fence, String step, String result, List<String> effects, Instant deadline) throws IOException {
        List<String> args = new ArrayList<>(List.of(saga, owner, Long.toString(fence), step, result)); args.addAll(effects); return client.execute("S_STEP_COMPLETE", request, args, deadline); }
    /**
     * Records a compensation attempt in reverse completed-step order before the external handler.
     * @param request stable operation identity
     * @param saga saga identity
     * @param owner current owner
     * @param fence owner token
     * @param step completed step name
     * @param idempotent whether compensation honors its stable original key
     * @param deadline operation deadline
     * @return COMPENSATING, BACKOFF or RETRY_EXHAUSTED
     * @throws IOException on unavailable quorum or unknown completion
     */
    public ExecutionResult compensate(String request, String saga, String owner, long fence, String step, boolean idempotent, Instant deadline) throws IOException { return client.execute("S_COMPENSATE", request, List.of(saga, owner, Long.toString(fence), step, Boolean.toString(idempotent)), deadline); }
    /**
     * Records compensation success or schedules bounded exponential retry.
     * @param request stable operation identity
     * @param saga saga identity
     * @param owner current owner
     * @param fence owner token
     * @param step compensating step
     * @param success whether its effect was established
     * @param result bounded outcome
     * @param deadline operation deadline
     * @return persisted progress, COMPENSATED or exhausted state
     * @throws IOException on unavailable quorum or unknown completion
     */
    public ExecutionResult compensationResult(String request, String saga, String owner, long fence, String step, boolean success, String result, Instant deadline) throws IOException { return client.execute("S_COMPENSATION_RESULT", request, List.of(saga, owner, Long.toString(fence), step, Boolean.toString(success), result), deadline); }
    /**
     * Records an independently established UNKNOWN outcome. Server-bound administrative capability
     * is mandatory; a caller-supplied boolean cannot grant it.
     * @param request stable operation identity
     * @param saga saga identity
     * @param step uncertain step
     * @param expectedVersion exact run version captured with the evidence before reconciliation
     * @param succeeded whether its external effect was established
     * @param result established outcome
     * @param deadline operation deadline
     * @return reconciled state
     * @throws IOException on unavailable quorum
     * @throws SecurityException for a principal without reconciliation permission
     */
    public ExecutionResult reconcile(String request, String saga, String step, long expectedVersion, boolean succeeded, String result, Instant deadline) throws IOException { return client.execute("S_RECONCILE", request, List.of(saga, step, Long.toString(expectedVersion), Boolean.toString(succeeded), result), deadline); }
    /**
     * Resolves an active forward/compensation intent under its current owner fence.
     * FAILED_NO_EFFECT requires an established no-effect outcome; a timeout or arbitrary exception
     * must use UNKNOWN. Forward failure atomically blocks further steps and requests compensation.
     * UNKNOWN preserves whether the uncertain effect was forward or compensation, across takeover.
     * @param request stable operation identity
     * @param saga saga identity
     * @param owner current owner
     * @param fence current owner token
     * @param step active step
     * @param resolution established failure or explicit uncertainty
     * @param result bounded outcome evidence
     * @param deadline original operation deadline
     * @return durable status or FENCED/CONFLICT
     * @throws IOException on unavailable quorum or unknown completion
     */
    public ExecutionResult resolveStep(String request, String saga, String owner, long fence, String step,
            StepResolution resolution, String result, Instant deadline) throws IOException {
        return client.execute("S_STEP_RESOLVE", request, List.of(saga, owner, Long.toString(fence), step,
                Objects.requireNonNull(resolution).name(), result), deadline);
    }
    /**
     * Atomically requests compensation and revokes the previous owner before any next forward step.
     * This includes an explicitly selected completed saga. Unresolved intents remain UNKNOWN with
     * the decision retained; only actual privileged reconciliation can establish their outcome.
     * @param request stable administrative operation identity
     * @param saga saga identity
     * @param expectedVersion exact observed run version
     * @param deadline operation deadline
     * @return persisted compensation decision or CONFLICT
     * @throws IOException on unavailable quorum or unknown completion
     * @throws SecurityException without the server-bound administrative capability
     */
    public ExecutionResult requestCompensation(String request, String saga, long expectedVersion, Instant deadline) throws IOException {
        return client.execute("S_REQUEST_COMPENSATION", request, List.of(saga, Long.toString(expectedVersion)), deadline);
    }
    /**
     * Reads durable saga state and current step metadata.
     * @param saga saga identity
     * @param deadline operation deadline
     * @return persisted state or NOT_FOUND
     * @throws IOException on unavailable quorum
     */
    public ExecutionResult status(String saga, Instant deadline) throws IOException { return client.query("E_RUN", saga, deadline); }
    /**
     * Reads one persisted step's exact protocol phase, stable external key and compensation input.
     * @param saga saga identity
     * @param step persisted step identity
     * @param deadline absolute deadline
     * @return persisted step metadata or NOT_FOUND
     * @throws IOException on unavailable quorum or deadline
     */
    public ExecutionResult stepStatus(String saga, String step, Instant deadline) throws IOException {
        return ExecutionResult.decode(client.queryBytes("E_STEP", List.of(saga, step), deadline));
    }
    /**
     * Reads all persisted steps in original order using bounded eight-step pages from one stable
     * saga version. If ownership or progress changes while paging, capture restarts until deadline.
     * No application-side cache is required to recover reverse compensation order after a restart.
     * @param saga saga identity
     * @param deadline absolute deadline
     * @return immutable ordered step metadata, empty for a missing or empty saga
     * @throws IOException on unavailable quorum, corrupt response or continuously changing version
     */
    public List<ExecutionResult> steps(String saga, Instant deadline) throws IOException {
        List<ExecutionResult> steps = new ArrayList<>(); long version = 0; int offset = 0;
        while (Instant.now().isBefore(deadline)) {
            byte[] page = client.queryBytes("E_STEPS", List.of(saga, Integer.toString(offset), "8", Long.toString(version)), deadline);
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(page))) {
                int status = in.readInt(); long observedVersion = in.readLong(); int total = in.readInt(), count = in.readInt();
                if (status == 2) return List.of();
                if (status == 1) { steps.clear(); offset = 0; version = 0; continue; }
                if (status != 0 || total < 0 || total > 256 || count < 0 || count > 8 || offset + count > total || offset < total && count == 0) throw new IOException("Saga step page bounds");
                version = observedVersion;
                for (int i = 0; i < count; i++) { int size = in.readInt(); if (size < 0 || size > in.available()) throw new IOException("Saga step item bounds"); steps.add(ExecutionResult.decode(in.readNBytes(size))); }
                if (in.available() != 0) throw new IOException("Saga step trailing bytes");
                offset += count; if (offset == total) return List.copyOf(steps);
            }
        }
        throw new IOException("Saga steps changed until deadline");
    }
}
