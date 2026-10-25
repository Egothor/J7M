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

package org.j7m.middleware.api;

import java.util.Map;
import org.j7m.middleware.api.reliability.InvocationReliabilityOptions;
import org.j7m.middleware.api.reliability.ReliabilityContext;

/**
 * Immutable metadata captured for one completed invocation.
 *
 * @param invocationId invocation identifier
 * @param target target object reference
 * @param method method name
 * @param startedAtMillis start timestamp
 * @param durationNanos elapsed time in nanoseconds
 * @param success whether invocation succeeded
 * @param errorCode failure code when unsuccessful
 * @param metadata public metadata
 */
public record InvocationMetadata(
        String invocationId,
        ObjectRef target,
        String method,
        long startedAtMillis,
        long durationNanos,
        boolean success,
        MiddlewareErrorCode errorCode,
        Map<String, String> metadata) {

    /**
     * Creates immutable invocation metadata.
     */
    public InvocationMetadata {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Decodes typed reliability options from public invocation metadata.
     *
     * @return reliability options
     */
    public InvocationReliabilityOptions reliabilityOptions() {
        return InvocationReliabilityOptions.fromMetadata(this.metadata);
    }

    /**
     * Returns a reliability context view of this invocation metadata.
     *
     * @return reliability context
     */
    public ReliabilityContext reliabilityContext() {
        return new ReliabilityContext(this.invocationId, this.reliabilityOptions(), this.metadata);
    }
}
