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

package org.j7m.middleware.application.components;

import java.util.Objects;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.BindingConnector;

/**
 * Production exact-reference adapter over the public runtime; no lookup or HA proxy is used.
 * The runtime remains host-owned and is never closed by this adapter.
 */
public final class RuntimeBindingConnector implements BindingConnector {
    private final MiddlewareRuntime runtime;
    /**
     * Creates an adapter over a borrowed runtime.
     * @param runtime trusted host runtime
     */
    public RuntimeBindingConnector(MiddlewareRuntime runtime) { this.runtime = Objects.requireNonNull(runtime); }
    /**
     * {@inheritDoc}
     */
    @Override public Object connect(ObjectRef reference, Class<?> contract, ProxyOptions options) {
        return runtime.reference(reference, contract, options);
    }
    /**
     * {@inheritDoc}
     */
    @Override public boolean probe(ObjectRef reference, Class<?> contract, ProxyOptions options) {
        // The built-in gRPC OPEN validates the exact target without invoking application code.
        // Other transports have no such qualified read-only handshake contract here.
        if (!reference.transport().equals("grpc")) return false;
        try { runtime.reference(reference, contract, options); return true; }
        catch (RuntimeException unavailable) { return false; }
    }
}
