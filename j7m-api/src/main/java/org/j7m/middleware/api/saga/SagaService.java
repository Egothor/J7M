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

package org.j7m.middleware.api.saga;

/**
 * J7M-native durable saga orchestration service.
 *
 * <p>Implementations own durable saga definition metadata, execution state,
 * retry state, timeout state, cancellation state, evidence, dead-letter
 * summaries, and durable outbox command intent. Methods may block on local
 * durable-log append/replay and outbox operations. The service is not a global
 * transaction manager and does not promise universal single-effect execution.
 *
 * <p>Implementations must be thread-safe or document narrower ownership. All
 * public status and evidence returned by this interface must be redacted. After
 * recovery, executable action callbacks must be re-registered before
 * non-terminal executions can continue; recovered in-flight work must either
 * resume through a documented idempotent retry/compensation path or report a
 * safe waiting state. Invalid definitions, unknown executions, overload,
 * recovery failures, durable-log failures, outbox failures, and closed-service
 * access must map to stable middleware error codes.
 */
public interface SagaService extends AutoCloseable {
    /**
     * Registers or validates a saga definition.
     *
     * <p>Registration links durable definition metadata to executable Java
     * actions. After recovery, callers must register the same definition and
     * step identifiers to resume non-terminal executions.</p>
     *
     * @param definition executable definition to register
     * @throws SagaException if durable registration fails or the definition is invalid
     */
    void register(SagaDefinition definition);

    /**
     * Starts a saga execution.
     *
     * @param request start request including definition id, options, context, and payload
     * @return durable start result, including duplicate status when idempotency matched
     * @throws SagaException if the execution cannot be started
     */
    SagaStartResult start(SagaStartRequest request);

    /**
     * Runs eligible saga work deterministically until blocked or terminal.
     *
     * <p>The method records outbox command intent before dispatching a step
     * action. It may stop when executable actions are missing, retry delay has
     * not elapsed, cancellation is pending, or a terminal state is reached.
     * Implementations must preserve idempotency metadata across recovery so
     * retrying recovered work does not become a generic exactly-once claim.</p>
     *
     * @param executionId execution to advance
     * @return redacted execution status after work is drained or blocked
     * @throws SagaException if durable state cannot be advanced
     */
    SagaExecution runUntilIdle(SagaExecutionId executionId);

    /**
     * Requests saga cancellation.
     *
     * @param request cancellation request
     * @return cancellation result after the durable request is recorded
     * @throws SagaException if the cancellation cannot be recorded
     */
    SagaCancelResult cancel(SagaCancelRequest request);

    /**
     * Evaluates deterministic timeout transitions.
     *
     * <p>Both saga-level and step-level timeouts are evaluated using the
     * implementation clock; callers should use a mutable/test clock for
     * deterministic validation rather than sleeps.</p>
     *
     * @throws SagaException if timeout evaluation cannot persist required events
     */
    void evaluateTimeouts();

    /**
     * Returns safe service status.
     *
     * <p>The status is a redacted summary containing metrics, per-execution
     * state, retained evidence, dead-letter summaries, and recovery diagnostics.
     * It must not expose command payload bytes, credentials, or full internal
     * transaction contexts.</p>
     *
     * @return immutable redacted status snapshot
     */
    SagaStatus status();

    /**
     * Returns a safe execution summary.
     *
     * @param executionId execution identifier
     * @return immutable redacted execution summary
     * @throws SagaException if the execution is unknown
     */
    SagaExecution execution(SagaExecutionId executionId);

    /**
     * Closes owned resources.
     *
     * <p>Implementations should make the operation idempotent. After close,
     * mutating operations may reject with a stable saga error.
     */
    @Override
    void close();
}
