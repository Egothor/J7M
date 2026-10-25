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

package org.j7m.middleware.application.api;

import java.util.List;
import java.util.Optional;
import org.j7m.middleware.api.ObjectRef;

/**
 * Immutable typed refresh observation without callback payloads or caller credentials.
 * References are routing evidence and do not grant authority.
 * @param generation monotonic binding publication generation
 * @param sourceRevision last accepted source revision
 * @param candidates currently eligible bounded candidates
 * @param selected selected concrete reference, empty when unavailable
 * @param status safe availability/lifecycle category
 */
public record BindingSnapshot(long generation, long sourceRevision, List<ObjectRef> candidates,
        Optional<ObjectRef> selected, String status) {
    /**
     * Defensively copies bounded state.
     * @param generation nonnegative publication generation
     * @param sourceRevision nonnegative accepted revision
     * @param candidates at most256 references
     * @param selected nonnull optional selected reference
     * @param status safe bounded status name
     * @throws IllegalArgumentException for malformed state
     */
    public BindingSnapshot {
        if (generation < 0 || sourceRevision < 0 || candidates == null || candidates.size() > 256
                || selected == null || status == null || !status.matches("[A-Z_]{1,32}")) throw new IllegalArgumentException("Invalid binding snapshot");
        candidates = List.copyOf(candidates);
    }
}
