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

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Host-only callable for one exact activation generation; it is not a wire DTO or authority token.
 * Each actual call owns an in-flight slot until application code returns, including after a caller
 * timeout. Hosts supply bounded/coalescing execution rather than invoking probes under a monitor.
 * @param id stable opaque component/probe identity, never a caller or payload identifier
 * @param kind operational probe purpose
 * @param check actual host-admitted application callback; may throw when the activation is unavailable
 */
public record ManagedApplicationProbe(String id, ManagedProbeKind kind, Callable<Boolean> check) {
    /**
     * Validates a bounded host handle.
     * @param id nonblank safe opaque identity of at most128 characters
     * @param kind nonnull probe purpose
     * @param check callback bound to the exact owning activation
     * @throws IllegalArgumentException for invalid identity
     * @throws NullPointerException for a missing kind or callback
     */
    public ManagedApplicationProbe {
        if (id == null || id.isBlank() || id.length() > 128 || id.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid host probe identity");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(check, "check");
    }
}
