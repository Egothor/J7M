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

package org.j7m.middleware.operations.api;

import java.time.Instant;
import java.util.Map;
import org.j7m.middleware.api.RuntimeIdentity;

/**
 * Immutable process-level identity of one J7M nugget.
 *
 * <p>A nugget id is distinct from {@link RuntimeIdentity#runtimeId()}. The
 * nugget id is stable for one nugget process lifetime and must not be treated
 * as proof of continuity across restart unless a future explicit persistence
 * policy says so. The human-readable name is not globally unique.</p>
 *
 * @param nuggetId immutable process-lifetime nugget id
 * @param nuggetName human-readable non-unique nugget name
 * @param runtimeIdentity hosted runtime identity
 * @param startedAt nugget process start timestamp
 * @param processId safe process id when available, otherwise empty string
 * @param hostSummary safe host summary when available, otherwise empty string
 * @param j7mVersion J7M implementation version when available
 * @param jvmVersion JVM version summary
 * @param state nugget lifecycle state
 * @param capabilities safe capability versions keyed by capability name
 * @param metadata redacted safe metadata
 */
public record NuggetIdentity(
        String nuggetId,
        String nuggetName,
        RuntimeIdentity runtimeIdentity,
        Instant startedAt,
        String processId,
        String hostSummary,
        String j7mVersion,
        String jvmVersion,
        NuggetState state,
        Map<String, String> capabilities,
        Map<String, String> metadata) {
    /**
     * Creates a defensive immutable identity.
     */
    public NuggetIdentity {
        if (nuggetId == null || nuggetId.isBlank()) {
            throw new IllegalArgumentException("nuggetId must not be blank");
        }
        nuggetName = nuggetName == null || nuggetName.isBlank() ? nuggetId : nuggetName;
        if (runtimeIdentity == null) {
            throw new IllegalArgumentException("runtimeIdentity must not be null");
        }
        startedAt = startedAt == null ? Instant.EPOCH : startedAt;
        processId = processId == null ? "" : processId;
        hostSummary = hostSummary == null ? "" : hostSummary;
        j7mVersion = j7mVersion == null ? "" : j7mVersion;
        jvmVersion = jvmVersion == null ? "" : jvmVersion;
        state = state == null ? NuggetState.UNKNOWN : state;
        capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
