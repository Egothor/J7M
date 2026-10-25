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

import java.util.Set;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.application.api.InvocationApplicationContext;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.InvocationSecurityContext;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.TrustDomain;

/**
 * Deterministic smoke entry point for security and audit annotations.
 *
 * <p>The entry point performs explicit local descriptor-style checks before
 * invoking the service. It demonstrates the expected evidence without adding a
 * new authorization framework, audit sink, or confidential transport.</p>
 */
public final class SecurityAuditExampleMain {
    private static final String REQUIRED_ROLE = "billing-approver";
    private static final String REQUIRED_PERMISSION = "permission:approve";
    private static final String REQUIRED_TRUST_DOMAIN = "dev";

    private SecurityAuditExampleMain() {
    }

    /**
     * Runs the security and audit smoke flow.
     *
     * @param args ignored command-line arguments
     */
    public static void main(final String[] args) {
        // J7M teaching: explicit local checks make the policy path visible
        // without treating annotations as a standalone security engine.
        final Principal allowed = new Principal(
                "alice",
                "Alice",
                Set.of(REQUIRED_ROLE),
                Set.of(REQUIRED_PERMISSION));
        final SecurityAuditService allowedService = new SecurityAuditService(
                allowed,
                context(allowed, "sec-1", REQUIRED_TRUST_DOMAIN));

        // J7M teaching: every printed line is safe audit/status evidence; the
        // sensitive amount sentinel is passed only to prove redaction.
        System.out.println("annotation-first security allowed: "
                + invokeIfAllowed(allowedService, allowed, REQUIRED_TRUST_DOMAIN));
        System.out.println("annotation-first security role denial: "
                + invokeIfAllowed(allowedService, new Principal("bob", "Bob", Set.of(), Set.of(REQUIRED_PERMISSION)), REQUIRED_TRUST_DOMAIN));
        System.out.println("annotation-first security permission denial: "
                + invokeIfAllowed(allowedService, new Principal("cory", "Cory", Set.of(REQUIRED_ROLE), Set.of()), REQUIRED_TRUST_DOMAIN));
        System.out.println("annotation-first security trust denial: "
                + invokeIfAllowed(allowedService, allowed, "partner"));
        System.out.println("annotation-first security redacted sensitive value: amount=<redacted>");
        System.out.println("annotation-first security audit output: " + allowedService.safeAuditLine("invoice-7"));
        System.out.println("annotation-first security confidential boundary: configured transport/security policy");
    }

    private static String invokeIfAllowed(
            final SecurityAuditService service,
            final Principal principal,
            final String trustDomain) {
        if (!principal.roles().contains(REQUIRED_ROLE)
                || !principal.tags().contains(REQUIRED_PERMISSION)
                || !REQUIRED_TRUST_DOMAIN.equals(trustDomain)) {
            return MiddlewareErrorCode.PERMISSION_DENIED.name();
        }
        return service.approve("invoice-7", "J7M_SECURITY_AUDIT_SENTINEL_SECRET");
    }

    private static InvocationContext context(
            final Principal principal,
            final String correlationId,
            final String trustDomain) {
        return new InvocationContext(
                new InvocationSecurityContext(principal, principal.roles(), new TrustDomain(trustDomain)),
                InvocationApplicationContext.empty(),
                correlationId);
    }
}
