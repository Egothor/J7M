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

package org.j7m.examples.annotation.securityaudit;

import org.j7m.middleware.annotations.Audit;
import org.j7m.middleware.annotations.Confidential;
import org.j7m.middleware.annotations.CurrentInvocation;
import org.j7m.middleware.annotations.CurrentPrincipal;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mService;
import org.j7m.middleware.annotations.RemoteMethod;
import org.j7m.middleware.annotations.RequiresPermission;
import org.j7m.middleware.annotations.RequiresRole;
import org.j7m.middleware.annotations.RequiresTrustDomain;
import org.j7m.middleware.annotations.Sensitive;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.security.api.Principal;

/**
 * Focused security and audit service for role, permission, trust-domain,
 * confidential handling, sensitive redaction, and audit metadata.
 *
 * <p>{@link RequiresRole}, {@link RequiresPermission}, and
 * {@link RequiresTrustDomain} are descriptor metadata until connected to a J7M
 * authorization policy. The smoke example evaluates the same public metadata
 * shape locally so readers see allowed, role-denied, permission-denied, and
 * trust-domain-denied outcomes without adding new runtime semantics.</p>
 *
 * <p>{@link Confidential} marks the method as requiring confidential handling
 * at configured transport, authorization, codec, status, and audit policy
 * boundaries. The annotation does not encrypt this local example output.
 * {@link Sensitive} values are redacted before any audit or status text is
 * printed.</p>
 */
@J7mService(name = "//apps/example/annotation/security-audit/ApprovalService", exported = true)
public final class SecurityAuditService {
    // J7M teaching: the service receives safe public security context and
    // keeps authorization, confidential handling, and redaction boundaries
    // visible in one focused example.
    private final Principal principal;
    private final InvocationContext invocationContext;

    /**
     * Creates the service for one current invocation context.
     *
     * @param principal current caller principal
     * @param invocationContext current invocation context
     */
    @Inject
    public SecurityAuditService(
            @CurrentPrincipal final Principal principal,
            @CurrentInvocation final InvocationContext invocationContext) {
        this.principal = principal;
        this.invocationContext = invocationContext;
    }

    /**
     * Approves an invoice when surrounding policy authorizes the caller.
     *
     * <p>The method returns only public principal, correlation, and invoice
     * identifiers. The sensitive amount is never echoed. The annotations supply
     * descriptor metadata for authorization, audit, and confidential handling;
     * the local smoke harness performs explicit checks before calling it.</p>
     *
     * @param invoiceId public invoice identifier
     * @param amount sensitive amount
     * @return safe approval result
     */
    @RemoteMethod
    @Audit
    @Confidential
    @RequiresRole("billing-approver")
    @RequiresPermission(value = "approve", resource = "//apps/example/annotation/security-audit")
    @RequiresTrustDomain("dev")
    public String approve(final String invoiceId, @Sensitive final String amount) {
        // J7M teaching: sensitive input reaches business code but is omitted
        // from public status and audit output.
        amount.length();
        return this.principal.id() + ":" + this.invocationContext.correlationId() + ":approved:" + invoiceId;
    }

    /**
     * Formats a safe audit line for the approved operation.
     *
     * @param invoiceId public invoice identifier
     * @return redacted audit summary
     */
    public String safeAuditLine(final String invoiceId) {
        return "audit principal=" + this.principal.id()
                + " action=approve resource=" + invoiceId
                + " amount=<redacted>";
    }
}
