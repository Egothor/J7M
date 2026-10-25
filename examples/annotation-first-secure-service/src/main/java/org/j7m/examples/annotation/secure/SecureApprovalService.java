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

package org.j7m.examples.annotation.secure;

import org.j7m.middleware.annotations.CurrentInvocation;
import org.j7m.middleware.annotations.CurrentPrincipal;
import org.j7m.middleware.annotations.Deadline;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mService;
import org.j7m.middleware.annotations.NonIdempotent;
import org.j7m.middleware.annotations.RemoteMethod;
import org.j7m.middleware.annotations.RequiresPermission;
import org.j7m.middleware.annotations.RequiresRole;
import org.j7m.middleware.annotations.Sensitive;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.security.api.Principal;

/**
 * Secure service example that documents role, permission, and redaction
 * metadata without overstating runtime enforcement.
 *
 * <p>{@link J7mService} identifies the service for descriptor validation.
 * {@link RequiresRole} and {@link RequiresPermission} are descriptor metadata
 * in this example path; the example tests use that metadata to demonstrate an
 * allowed and denied call. A production deployment must connect the descriptor
 * to the normal J7M authorization policy before exported invocations are
 * accepted.</p>
 *
 * <p>{@link CurrentPrincipal} and {@link CurrentInvocation} inject safe
 * invocation-scoped context. {@link Sensitive} marks the amount parameter so
 * status, audit, and demo output redact it. {@link Idempotent} contributes
 * retry/dedupe metadata only and does not claim generic exactly-once
 * execution.</p>
 */
@J7mService(name = "//apps/example/annotation/secure/ApprovalService", exported = true)
public final class SecureApprovalService {
        // J7M teaching: this block keeps authorization metadata and redaction visible without treating annotations as a standalone security engine.
    private final Principal principal;
    private final InvocationContext invocationContext;

    /**
     * Creates a secure service scoped to one invocation context.
     *
     * <p>The injected principal and invocation context are public security
     * views. Application code does not receive authorization internals, raw
     * transport sessions, or mutable runtime state.</p>
     *
     * @param principal current caller principal
     * @param invocationContext current invocation context
     */
    @Inject
    public SecureApprovalService(
            @CurrentPrincipal final Principal principal,
            @CurrentInvocation final InvocationContext invocationContext) {
        this.principal = principal;
        this.invocationContext = invocationContext;
    }

    /**
     * Demonstrates an approval method with security metadata and redaction.
     *
     * <p>{@link RemoteMethod} marks the public service contract.
     * {@link RequiresRole} and {@link RequiresPermission} are validated as
     * descriptor metadata by the example; they are not a standalone
     * authorization engine. The returned value includes only public principal,
     * correlation, and invoice identifiers, while the {@link Sensitive} amount
     * is intentionally omitted.</p>
     *
     * @param invoiceId public invoice identifier
     * @param amount sensitive amount that must not appear in output
     * @return safe approval summary
     */
    @RemoteMethod
    @Idempotent
    @Deadline(millis = 500)
    @RequiresRole("billing-approver")
    @RequiresPermission(value = "approve", resource = "//apps/example/annotation/secure")
    public String approve(final String invoiceId, @Sensitive final String amount) {
        // J7M teaching: the returned status proves the allowed path without
        // echoing the sensitive amount; authorization metadata is visible to
        // the descriptor, while production enforcement remains policy-driven.
        return this.principal.id() + ":" + this.invocationContext.correlationId() + ":approved:" + invoiceId;
    }

    /**
     * Demonstrates a non-idempotent method with sensitive input.
     *
     * <p>{@link NonIdempotent} marks that callers must not retry the operation
     * automatically. The example returns a redacted preview to show what safe
     * audit/status output should look like.</p>
     *
     * @param amount sensitive amount
     * @return redacted preview text
     */
    @RemoteMethod
    @NonIdempotent
    public String previewAudit(@Sensitive final String amount) {
        // J7M teaching: secure examples should show redaction at the point
        // where a developer might otherwise be tempted to print raw input.
        return "amount=<redacted>";
    }
}
