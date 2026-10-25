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

package org.j7m.middleware.examples.annotation;

import org.j7m.middleware.annotations.CurrentInvocation;
import org.j7m.middleware.annotations.CurrentPrincipal;
import org.j7m.middleware.annotations.Deadline;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mService;
import org.j7m.middleware.annotations.RemoteMethod;
import org.j7m.middleware.annotations.RequiresPermission;
import org.j7m.middleware.annotations.RequiresRole;
import org.j7m.middleware.annotations.Sensitive;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.security.api.Principal;

/**
 * Secure annotation-first service used by examples.
 *
 * <p>The public security annotations are descriptor metadata reflected by the
 * application platform. Runtime authorization remains a policy decision; this
 * example evaluates the role requirement locally so the demo can show an
 * authorized and denied path without depending on an external identity
 * provider. Sensitive values are redacted before audit output.</p>
 */
@J7mService(name = "//apps/example/annotation/secure/ApprovalService", exported = true, transport = "default")
public final class AnnotationSecureService {
        // J7M teaching: this block keeps authorization metadata and redaction visible without treating annotations as a standalone security engine.
    private final Principal principal;
    private final InvocationContext invocationContext;
    private final AnnotationExampleAudit audit;

    /**
     * Creates a secure service scoped to the current invocation.
     *
     * @param principal current caller principal
     * @param invocationContext current invocation context
     * @param audit safe audit collector
     */
    @Inject
    public AnnotationSecureService(
            @CurrentPrincipal final Principal principal,
            @CurrentInvocation final InvocationContext invocationContext,
            final AnnotationExampleAudit audit) {
        this.principal = principal;
        this.invocationContext = invocationContext;
        this.audit = audit;
    }

    /**
     * Approves an invoice when the caller has the required role.
     *
     * @param invoiceId public invoice id
     * @param amount sensitive amount
     * @return safe result
     */
    @RemoteMethod
    @Idempotent
    @Deadline(millis = 500)
    @RequiresRole("billing-approver")
    @RequiresPermission(value = "approve", resource = "//apps/example/annotation/secure")
    public AnnotationApprovalResult approve(final String invoiceId, @Sensitive final String amount) {
        // J7M teaching: the compiled support path exercises a local denied
        // branch for demos, while production authorization remains policy
        // integration driven by the role and permission descriptor metadata.
        if (!this.principal.roles().contains("billing-approver")) {
            this.audit.record("secure.denied:principal=" + this.principal.id() + ":invoice=" + invoiceId);
            throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED, "annotation secure approval denied");
        }
        // J7M teaching: the audit sink redacts sensitive values, so examples
        // can show evidence flow without printing raw business secrets.
        this.audit.record("secure.approved:principal=" + this.principal.id()
                + ":correlation=" + this.invocationContext.correlationId()
                + ":invoice=" + invoiceId
                + ":amount=" + amount);
        return new AnnotationApprovalResult(invoiceId, "APPROVED");
    }
}
