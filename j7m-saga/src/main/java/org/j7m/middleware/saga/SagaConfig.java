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

package org.j7m.middleware.saga;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Configuration for a local durable saga service.
 *
 * <p>The configuration owns local durable-log and messaging directories and
 * enforces bounded resource use. Paths are local filesystem paths; the service
 * does not use an external workflow engine or transaction coordinator.
 *
 * @param eventLogDirectory directory for durable saga event log state
 * @param messagingDirectory directory for durable outbox and inbox state
 * @param clock service clock used for deterministic timeout and retry evaluation
 * @param maxActiveSagas maximum number of non-terminal saga executions
 * @param maxStepsPerSaga maximum steps allowed in one definition
 * @param maxRetryAttempts maximum retry attempts allowed by policies
 * @param maxPendingOutboxCommands maximum pending durable outbox commands
 * @param maxCommandPayloadBytes maximum command payload size accepted at start
 * @param maxEvidenceEntries maximum evidence records retained in public status
 * @param maxDeadLettersInStatus maximum dead-letter records exposed in public status
 * @param maxCheckpointBytes maximum canonical checkpoint body bytes accepted for write and recovery
 */
public record SagaConfig(
        Path eventLogDirectory,
        Path messagingDirectory,
        Clock clock,
        int maxActiveSagas,
        int maxStepsPerSaga,
        int maxRetryAttempts,
        int maxPendingOutboxCommands,
        int maxCommandPayloadBytes,
        int maxEvidenceEntries,
        int maxDeadLettersInStatus,
        int maxCheckpointBytes) {
    /**
     * Creates a validated configuration.
     */
    public SagaConfig {
        if (eventLogDirectory == null || messagingDirectory == null) {
            throw new IllegalArgumentException("event and messaging directories are required");
        }
        clock = clock == null ? Clock.systemUTC() : clock;
        if (maxActiveSagas <= 0 || maxStepsPerSaga <= 0 || maxRetryAttempts < 0 || maxPendingOutboxCommands <= 0
                || maxCommandPayloadBytes <= 0 || maxEvidenceEntries <= 0 || maxDeadLettersInStatus <= 0
                || maxCheckpointBytes <= 0) {
            throw new IllegalArgumentException("saga limits are invalid");
        }
    }

    /**
     * Creates a validated configuration using the default checkpoint byte limit.
     *
     * @param eventLogDirectory directory for durable saga event log state
     * @param messagingDirectory directory for durable outbox and inbox state
     * @param clock service clock used for deterministic timeout and retry evaluation
     * @param maxActiveSagas maximum number of non-terminal saga executions
     * @param maxStepsPerSaga maximum steps allowed in one definition
     * @param maxRetryAttempts maximum retry attempts allowed by policies
     * @param maxPendingOutboxCommands maximum pending durable outbox commands
     * @param maxCommandPayloadBytes maximum command payload size accepted at start
     * @param maxEvidenceEntries maximum evidence records retained in public status
     * @param maxDeadLettersInStatus maximum dead-letter records exposed in public status
     */
    public SagaConfig(
            final Path eventLogDirectory,
            final Path messagingDirectory,
            final Clock clock,
            final int maxActiveSagas,
            final int maxStepsPerSaga,
            final int maxRetryAttempts,
            final int maxPendingOutboxCommands,
            final int maxCommandPayloadBytes,
            final int maxEvidenceEntries,
            final int maxDeadLettersInStatus) {
        this(eventLogDirectory, messagingDirectory, clock, maxActiveSagas, maxStepsPerSaga, maxRetryAttempts,
                maxPendingOutboxCommands, maxCommandPayloadBytes, maxEvidenceEntries, maxDeadLettersInStatus, 1_048_576);
    }

    /**
     * Creates a local default configuration.
     *
     * @param directory root directory for service state
     * @return validated default configuration rooted below {@code directory}
     */
    public static SagaConfig defaults(final Path directory) {
        if (directory == null) {
            throw new IllegalArgumentException("directory is required");
        }
        return new SagaConfig(directory.resolve("events"), directory.resolve("messaging"), Clock.systemUTC(),
                1024, 128, 3, 1024, 1024 * 1024, 1024, 128, 1_048_576);
    }
}
