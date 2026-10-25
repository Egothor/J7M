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

import java.time.Duration;
import org.j7m.middleware.api.coordination.HeartbeatOptions;
import org.j7m.middleware.api.durablelog.DurableLog;

/**
 * Immutable configuration for {@link LogBackedCoordinationService}.
 *
 * @param durableLog durable local event log
 * @param clock deterministic clock
 * @param heartbeatOptions liveness timing
 * @param maxWatches maximum runtime watches
 * @param maxMembers maximum retained members
 * @param maxLeases maximum retained leases
 * @param maxCoordinates maximum retained coordinates
 * @param maxCoordinateValueBytes maximum coordinate value size in bytes
 * @param maxCheckpointBytes maximum serialized checkpoint size in UTF-8 bytes
 */
public record CoordinationConfig(
        DurableLog durableLog,
        CoordinationClock clock,
        HeartbeatOptions heartbeatOptions,
        int maxWatches,
        int maxMembers,
        int maxLeases,
        int maxCoordinates,
        int maxCoordinateValueBytes,
        int maxCheckpointBytes) {
    /**
     * Default maximum retained member count.
     */
    public static final int DEFAULT_MAX_MEMBERS = 1024;
    /**
     * Default maximum retained lease count.
     */
    public static final int DEFAULT_MAX_LEASES = 1024;
    /**
     * Default maximum retained coordinate count.
     */
    public static final int DEFAULT_MAX_COORDINATES = 4096;
    /**
     * Default maximum coordinate value size in bytes.
     */
    public static final int DEFAULT_MAX_COORDINATE_VALUE_BYTES = 16 * 1024;
    /**
     * Default maximum serialized checkpoint size in bytes.
     */
    public static final int DEFAULT_MAX_CHECKPOINT_BYTES = 1024 * 1024;

    /**
     * Creates validated configuration.
     */
    public CoordinationConfig {
        if (durableLog == null) {
            throw new IllegalArgumentException("durable log is required");
        }
        clock = clock == null ? java.time.Instant::now : clock;
        heartbeatOptions = heartbeatOptions == null
                ? new HeartbeatOptions(Duration.ofSeconds(10L), Duration.ofSeconds(30L))
                : heartbeatOptions;
        if (maxWatches <= 0) {
            throw new IllegalArgumentException("max watches must be positive");
        }
        if (maxMembers <= 0 || maxLeases <= 0 || maxCoordinates <= 0 || maxCoordinateValueBytes <= 0
                || maxCheckpointBytes <= 0) {
            throw new IllegalArgumentException("coordination state limits must be positive");
        }
    }

    /**
     * Creates configuration with the historical watch-only state limit surface.
     *
     * @param durableLog durable local event log
     * @param clock deterministic clock
     * @param heartbeatOptions liveness timing
     * @param maxWatches maximum runtime watches
     */
    public CoordinationConfig(
            final DurableLog durableLog,
            final CoordinationClock clock,
            final HeartbeatOptions heartbeatOptions,
            final int maxWatches) {
        this(durableLog, clock, heartbeatOptions, maxWatches, DEFAULT_MAX_MEMBERS, DEFAULT_MAX_LEASES,
                DEFAULT_MAX_COORDINATES, DEFAULT_MAX_COORDINATE_VALUE_BYTES, DEFAULT_MAX_CHECKPOINT_BYTES);
    }
}
