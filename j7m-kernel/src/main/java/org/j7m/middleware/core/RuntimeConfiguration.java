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

package org.j7m.middleware.core;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.codec.api.ValueCodec;
import org.j7m.middleware.registry.api.RegistryClient;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.transport.api.TransportProvider;

/**
 * Immutable construction configuration for {@link DefaultMiddlewareRuntime}.
 *
 * @param identity runtime identity
 * @param registry registry client owned by the runtime
 * @param codec value codec
 * @param transports transport providers owned by the runtime
 * @param clock runtime clock
 * @param security security configuration
 */
public record RuntimeConfiguration(
        RuntimeIdentity identity,
        RegistryClient registry,
        ValueCodec codec,
        List<TransportProvider> transports,
        Clock clock,
        SecurityConfiguration security) {

    /**
     * Creates immutable configuration fields.
     */
    public RuntimeConfiguration {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (codec == null) {
            throw new IllegalArgumentException("codec must not be null");
        }
        transports = transports == null ? List.of() : List.copyOf(transports);
        clock = clock == null ? Clock.systemUTC() : clock;
        if (security == null) {
            throw new IllegalArgumentException("security must not be null");
        }
    }

    /**
     * Creates an identity with generated metadata.
     *
     * @param runtimeId runtime identifier
     * @return runtime identity
     */
    public static RuntimeIdentity identity(final String runtimeId) {
        return new RuntimeIdentity(runtimeId, "default", Map.of());
    }
}
