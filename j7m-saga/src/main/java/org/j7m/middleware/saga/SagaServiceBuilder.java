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
 * Builder for {@link LogBackedSagaService}.
 *
 * <p>The builder is mutable and intended for single-threaded service
 * construction. The built service owns local durable-log and outbox resources
 * under the configured directory.
 */
public final class SagaServiceBuilder {
    private Path directory;
    private Clock clock;
    private int maxActiveSagas = 1024;
    private int maxStepsPerSaga = 128;
    private int maxRetryAttempts = 3;
    private int maxPendingOutboxCommands = 1024;
    private int maxCommandPayloadBytes = 1024 * 1024;
    private int maxEvidenceEntries = 1024;
    private int maxDeadLettersInStatus = 128;

    /**
     * Creates a builder with default limits.
     */
    public SagaServiceBuilder() {
    }

    /**
     * Sets the root service directory.
     *
     * @param value root directory for durable saga state
     * @return this builder
     */
    public SagaServiceBuilder directory(final Path value) {
        this.directory = value;
        return this;
    }

    /**
     * Sets the deterministic service clock.
     *
     * @param value clock used for timeout and retry decisions
     * @return this builder
     */
    public SagaServiceBuilder clock(final Clock value) {
        this.clock = value;
        return this;
    }

    /**
     * Sets max active sagas.
     *
     * @param value maximum non-terminal executions
     * @return this builder
     */
    public SagaServiceBuilder maxActiveSagas(final int value) {
        this.maxActiveSagas = value;
        return this;
    }

    /**
     * Sets max steps per definition.
     *
     * @param value maximum number of steps accepted in one definition
     * @return this builder
     */
    public SagaServiceBuilder maxStepsPerSaga(final int value) {
        this.maxStepsPerSaga = value;
        return this;
    }

    /**
     * Sets max retry attempts.
     *
     * @param value maximum retry attempts accepted by policies
     * @return this builder
     */
    public SagaServiceBuilder maxRetryAttempts(final int value) {
        this.maxRetryAttempts = value;
        return this;
    }

    /**
     * Sets max pending outbox commands.
     *
     * @param value maximum pending durable outbox commands
     * @return this builder
     */
    public SagaServiceBuilder maxPendingOutboxCommands(final int value) {
        this.maxPendingOutboxCommands = value;
        return this;
    }

    /**
     * Sets max command payload bytes.
     *
     * @param value maximum accepted command payload bytes
     * @return this builder
     */
    public SagaServiceBuilder maxCommandPayloadBytes(final int value) {
        this.maxCommandPayloadBytes = value;
        return this;
    }

    /**
     * Builds and recovers a service.
     *
     * @return opened service after durable state recovery
     */
    public LogBackedSagaService build() {
        if (this.directory == null) {
            throw new IllegalArgumentException("directory is required");
        }
        return new LogBackedSagaService(new SagaConfig(this.directory.resolve("events"), this.directory.resolve("messaging"),
                this.clock == null ? Clock.systemUTC() : this.clock, this.maxActiveSagas, this.maxStepsPerSaga,
                this.maxRetryAttempts, this.maxPendingOutboxCommands, this.maxCommandPayloadBytes,
                this.maxEvidenceEntries, this.maxDeadLettersInStatus));
    }
}
