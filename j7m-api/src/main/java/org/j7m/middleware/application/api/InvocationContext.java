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

/**
 * Safe current invocation context exposed to application service code.
 *
 * <p>The record is immutable and thread-safe. It is installed by managed
 * dispatch for the lifetime of one invocation and is cleared when dispatch
 * completes. Cleanup is required for platform threads and virtual thread
 * dispatch paths that use the internal scoped {@link J7mContext}. Application
 * code must not retain the context as a long-lived authority token.</p>
 *
 * <p>The context carries safe security, application, and correlation data. It
 * does not expose credentials, raw transport internals, mutable runtime maps,
 * or unredacted status data. Missing caller data is represented by an
 * anonymous principal and empty correlation id rather than a null context. The control is a local
 * host capability, not transport data: neither it nor this record is a wire DTO or a bearer token.
 * Keeping this immutable record does not extend the control's invocation lifetime.</p>
 *
 * @param security caller security context
 * @param application application context
 * @param correlationId correlation id
 * @param control host-owned invocation controls; unavailable outside managed dispatch
 */
public record InvocationContext(
        InvocationSecurityContext security,
        InvocationApplicationContext application,
        String correlationId,
        InvocationControl control) {
    /**
     * Creates immutable context data.
     */
    public InvocationContext {
        security = security == null ? new InvocationSecurityContext(null, null, null) : security;
        application = application == null ? InvocationApplicationContext.empty() : application;
        correlationId = correlationId == null ? "" : correlationId;
        control = control == null ? InvocationControl.unavailable() : control;
    }

    /**
     * Creates the compatible context without an active work-submission capability.
     * Existing callers retain the original three-argument constructor; a managed host supplies
     * the four-argument constructor when it installs its actual invocation lifetime.
     * @param security caller security context, or null for anonymous data
     * @param application application context, or null for an empty context
     * @param correlationId correlation id, or null for an empty identifier
     */
    public InvocationContext(InvocationSecurityContext security, InvocationApplicationContext application, String correlationId) {
        this(security, application, correlationId, InvocationControl.unavailable());
    }

    /**
     * Creates an empty safe context for code running outside middleware dispatch.
     *
     * @return empty safe context
     */
    public static InvocationContext empty() {
        return new InvocationContext(null, null, "");
    }
}
