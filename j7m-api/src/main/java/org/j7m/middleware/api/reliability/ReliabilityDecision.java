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

package org.j7m.middleware.api.reliability;

import java.io.Serializable;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;

/**
 * Result of a reliability policy decision.
 *
 * <p>The record is immutable, serializable, and thread-safe. Messages are
 * stable status text and must not include raw payloads or sensitive keys.</p>
 *
 * @param status stable reliability status
 * @param accepted whether the operation may proceed
 * @param errorCode middleware error code when rejected
 * @param retrySafe whether retry is safe
 * @param message stable safe status text
 * @param metadata safe diagnostic metadata
 */
public record ReliabilityDecision(
        ReliabilityStatus status,
        boolean accepted,
        MiddlewareErrorCode errorCode,
        boolean retrySafe,
        String message,
        Map<String, String> metadata) implements Serializable {

    /**
     * Creates a validated reliability decision.
     */
    public ReliabilityDecision {
        if (status == null) {
            throw new IllegalArgumentException("reliability status must not be null");
        }
        if (!accepted && errorCode == null) {
            throw new IllegalArgumentException("rejected reliability decisions require an error code");
        }
        message = ReliabilityValues.requireStatusText("message", message == null ? status.name() : message, 256);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Creates an accepted decision.
     *
     * @param status decision status
     * @param retrySafe retry safety
     * @return accepted decision
     */
    public static ReliabilityDecision accepted(final ReliabilityStatus status, final boolean retrySafe) {
        return new ReliabilityDecision(status, true, null, retrySafe, status.name(), Map.of());
    }

    /**
     * Creates a rejected decision.
     *
     * @param status decision status
     * @param errorCode middleware error code
     * @param message safe status text
     * @return rejected decision
     */
    public static ReliabilityDecision rejected(
            final ReliabilityStatus status,
            final MiddlewareErrorCode errorCode,
            final String message) {
        return new ReliabilityDecision(status, false, errorCode, false, message, Map.of());
    }
}
