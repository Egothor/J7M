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

import java.io.Serializable;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.j7m.middleware.api.reliability.InvocationReliabilityOptions;

/**
 * Immutable options for one method invocation.
 *
 * @param invocationId caller-supplied or generated invocation identifier
 * @param deadline invocation deadline duration
 * @param metadata public invocation metadata
 */
public record InvocationOptions(String invocationId, Duration deadline, Map<String, String> metadata) implements Serializable {
    /**
     * Creates immutable invocation options.
     */
    public InvocationOptions {
        invocationId = invocationId == null || invocationId.isBlank() ? UUID.randomUUID().toString() : invocationId;
        deadline = deadline == null ? Duration.ofSeconds(5L) : deadline;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Returns default invocation options.
     *
     * @return default options
     */
    public static InvocationOptions defaults() {
        return new InvocationOptions(null, Duration.ofSeconds(5L), Map.of());
    }

    /**
     * Decodes typed reliability options from this invocation's metadata.
     *
     * @return reliability options
     */
    public InvocationReliabilityOptions reliabilityOptions() {
        return InvocationReliabilityOptions.fromMetadata(this.metadata);
    }

    /**
     * Returns a copy with reliability metadata merged into the public metadata map.
     *
     * @param reliabilityOptions reliability options to encode
     * @return invocation options with reliability metadata
     */
    public InvocationOptions withReliabilityOptions(final InvocationReliabilityOptions reliabilityOptions) {
        InvocationReliabilityOptions safeOptions = reliabilityOptions == null ? InvocationReliabilityOptions.defaults() : reliabilityOptions;
        Map<String, String> merged = new LinkedHashMap<>(this.metadata);
        merged.putAll(safeOptions.toMetadata());
        return new InvocationOptions(this.invocationId, this.deadline, merged);
    }
}
