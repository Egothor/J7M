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

package org.j7m.middleware.api.consensus;

/**
 * Local Raft-like replicated log service for J7M platform metadata.
 *
 * <p>This API is a stable public service contract. Implementations own local
 * durable term, vote, log, commit, and recovery state, and may block while
 * appending to or replaying the local durable log. Implementations are expected
 * to be thread-safe for concurrent callers unless they document a stricter
 * ownership model. Returned status and safety reports are redacted summaries:
 * they must not expose raw command payload bytes, credentials, private endpoint
 * material, or implementation-only transport state.</p>
 *
 * <p>The Phase 6.5 contract is intentionally scoped: it is not a general
 * database, not a durable queue, not repository replication, not a transaction
 * engine, and not generic exactly-once execution. Implementations map invalid
 * input, unavailable leadership, stale terms, recovery failures, and durable
 * storage failures to stable middleware error codes through the configured
 * exception model.</p>
 *
 * <p><strong>API note:</strong> The baseline J7M implementation is in-process
 * and deterministic for validation. Production deployments may wrap this
 * service with transport or ownership layers, but the public API does not
 * define a vendor protocol.</p>
 */
public interface ConsensusService extends AutoCloseable {
    /**
     * Starts an election from the supplied node in deterministic local simulation.
     *
     * <p>The call may synchronously persist a new term and vote before returning.
     * It must reject unknown nodes and stale terms consistently with the
     * implementation's membership model. The returned state is a redacted
     * snapshot and not a mutable node handle.</p>
     *
     * @param nodeId candidate node; must identify a configured member
     * @return elected leader state or current node state after the election attempt
     */
    ConsensusState startElection(ConsensusNodeId nodeId);

    /**
     * Submits a metadata command to the current leader.
     *
     * <p>The command is replicated according to the majority commit rule before
     * a committed result is reported. The method may block on local durable-log
     * appends and deterministic message processing. Implementations must reject
     * stale leaders and must redact command payloads from status, metrics,
     * safety reports, and exception details.</p>
     *
     * @param command metadata command to append; payload bytes remain internal
     * @return command result with committed/rejected status and safe reason code
     */
    ConsensusCommandResult submit(ConsensusCommand command);

    /**
     * Returns current safe status.
     *
     * <p>The snapshot is intended for operators and tests. It exposes role, term,
     * commit index, applied index, metrics, and redacted log-entry metadata; it
     * does not expose raw {@link ConsensusCommand} payloads.</p>
     *
     * @return immutable redacted status snapshot
     */
    ConsensusStatus status();

    /**
     * Runs safety invariant checks.
     *
     * <p>Implementations inspect actual recovered node state, logs, commit
     * indexes, and recorded simulation evidence. Required invariants must fail
     * or report insufficient evidence rather than silently passing when a
     * scenario was not exercised.</p>
     *
     * @return redacted safety report containing safe evidence and violations
     */
    ConsensusSafetyReport safetyReport();

    /**
     * Closes local resources.
     *
     * <p>Implementations should make close idempotent. After close, mutating
     * operations should fail with a stable unavailable/closed error rather than
     * exposing implementation resource details.</p>
     */
    @Override
    void close();
}
