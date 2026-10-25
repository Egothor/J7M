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

package org.j7m.middleware.enterprise.iam;

import org.j7m.middleware.security.api.AuditEvent;
import org.j7m.middleware.security.api.SecurityAuditSink;
import org.j7m.middleware.security.api.SecurityContext;

/**
 * Break-glass policy hook that requires mandatory audit.
 */
public final class BreakGlassPolicyHook {
    /**
     * Creates a break-glass hook.
     */
    public BreakGlassPolicyHook() {
    }

    /**
     * Records a break-glass access event.
     *
     * @param context security context
     * @param reason safe reason
     * @param sink audit sink
     */
    public void activate(final SecurityContext context, final String reason, final SecurityAuditSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("audit sink must not be null for break-glass access");
        }
        String principal = context == null ? "anonymous" : context.principal().id();
        sink.record(new AuditEvent(java.time.Instant.now(), context == null ? "" : context.callerRuntimeId(), principal,
                "break-glass", "enterprise-iam", "activated", "break-glass",
                context == null ? "" : context.correlationId(), context == null ? "" : context.invocationId(),
                context == null ? "" : context.remoteEndpoint(), reason, "", "default", java.util.Map.of("mandatory", "true")));
    }
}
