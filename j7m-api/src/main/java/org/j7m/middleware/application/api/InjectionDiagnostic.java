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

import java.time.Instant;
import java.util.Map;

/**
 * Safe diagnostic emitted by the J7M-native injector.
 *
 * <p>Diagnostics are intended for activation logs, status pages, tests, and
 * developer tooling. The metadata is copied defensively and must contain only
 * redacted values. Implementations must report missing dependencies,
 * duplicate candidates, lifecycle failures, and JPMS access failures through
 * these safe records rather than leaking constructor arguments or
 * configuration secrets.</p>
 *
 * @param timestamp event time
 * @param component component class name
 * @param action diagnostic action
 * @param result diagnostic result
 * @param reasonCode stable reason code
 * @param metadata safe redacted metadata
 */
public record InjectionDiagnostic(
        Instant timestamp,
        String component,
        String action,
        String result,
        String reasonCode,
        Map<String, String> metadata) {
    /**
     * Creates an immutable safe diagnostic.
     */
    public InjectionDiagnostic {
        timestamp = timestamp == null ? Instant.EPOCH : timestamp;
        component = component == null ? "" : component;
        action = action == null ? "" : action;
        result = result == null ? "" : result;
        reasonCode = reasonCode == null ? "" : reasonCode;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

