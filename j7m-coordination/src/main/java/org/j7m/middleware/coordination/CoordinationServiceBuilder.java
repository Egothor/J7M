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

package org.j7m.middleware.coordination;

import java.nio.file.Path;
import java.time.Duration;
import org.j7m.middleware.api.coordination.HeartbeatOptions;
import org.j7m.middleware.durablelog.DurableLogConfig;
import org.j7m.middleware.durablelog.FileDurableLog;

/**
 * Builder for local log-backed coordination services.
 */
public final class CoordinationServiceBuilder {
    private Path directory;
    private CoordinationClock clock;
    private HeartbeatOptions heartbeatOptions;
    private int maxWatches = 1024;
    private int maxMembers = CoordinationConfig.DEFAULT_MAX_MEMBERS;
    private int maxLeases = CoordinationConfig.DEFAULT_MAX_LEASES;
    private int maxCoordinates = CoordinationConfig.DEFAULT_MAX_COORDINATES;
    private int maxCoordinateValueBytes = CoordinationConfig.DEFAULT_MAX_COORDINATE_VALUE_BYTES;
    private int maxCheckpointBytes = CoordinationConfig.DEFAULT_MAX_CHECKPOINT_BYTES;

    /**
     * Creates a builder with conservative defaults.
     */
    public CoordinationServiceBuilder() {
    }

    /**
     * Sets the durable log directory.
     *
     * @param directory directory
     * @return this builder
     */
    public CoordinationServiceBuilder directory(final Path directory) {
        this.directory = directory;
        return this;
    }

    /**
     * Sets the coordination clock.
     *
     * @param clock clock
     * @return this builder
     */
    public CoordinationServiceBuilder clock(final CoordinationClock clock) {
        this.clock = clock;
        return this;
    }

    /**
     * Sets heartbeat options.
     *
     * @param heartbeatOptions heartbeat options
     * @return this builder
     */
    public CoordinationServiceBuilder heartbeatOptions(final HeartbeatOptions heartbeatOptions) {
        this.heartbeatOptions = heartbeatOptions;
        return this;
    }

    /**
     * Sets maximum runtime watches.
     *
     * @param maxWatches maximum watches
     * @return this builder
     */
    public CoordinationServiceBuilder maxWatches(final int maxWatches) {
        this.maxWatches = maxWatches;
        return this;
    }

    /**
     * Sets maximum retained member records.
     *
     * @param maxMembers maximum members
     * @return this builder
     */
    public CoordinationServiceBuilder maxMembers(final int maxMembers) {
        this.maxMembers = maxMembers;
        return this;
    }

    /**
     * Sets maximum retained lease records.
     *
     * @param maxLeases maximum leases
     * @return this builder
     */
    public CoordinationServiceBuilder maxLeases(final int maxLeases) {
        this.maxLeases = maxLeases;
        return this;
    }

    /**
     * Sets maximum retained coordinate records.
     *
     * @param maxCoordinates maximum coordinates
     * @return this builder
     */
    public CoordinationServiceBuilder maxCoordinates(final int maxCoordinates) {
        this.maxCoordinates = maxCoordinates;
        return this;
    }

    /**
     * Sets maximum coordinate value size in bytes.
     *
     * @param maxCoordinateValueBytes maximum coordinate value size in bytes
     * @return this builder
     */
    public CoordinationServiceBuilder maxCoordinateValueBytes(final int maxCoordinateValueBytes) {
        this.maxCoordinateValueBytes = maxCoordinateValueBytes;
        return this;
    }

    /**
     * Sets maximum serialized checkpoint size in bytes.
     *
     * @param maxCheckpointBytes maximum serialized checkpoint size in bytes
     * @return this builder
     */
    public CoordinationServiceBuilder maxCheckpointBytes(final int maxCheckpointBytes) {
        this.maxCheckpointBytes = maxCheckpointBytes;
        return this;
    }

    /**
     * Builds a coordination service.
     *
     * @return coordination service
     */
    public LogBackedCoordinationService build() {
        Path safeDirectory = this.directory;
        if (safeDirectory == null) {
            throw new IllegalArgumentException("directory is required");
        }
        FileDurableLog durableLog = new FileDurableLog(DurableLogConfig.defaults(safeDirectory));
        return new LogBackedCoordinationService(new CoordinationConfig(
                durableLog,
                this.clock,
                this.heartbeatOptions == null ? new HeartbeatOptions(Duration.ofSeconds(10L), Duration.ofSeconds(30L)) : this.heartbeatOptions,
                this.maxWatches,
                this.maxMembers,
                this.maxLeases,
                this.maxCoordinates,
                this.maxCoordinateValueBytes,
                this.maxCheckpointBytes));
    }
}
