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

package org.j7m.middleware.security.defaults;

import org.j7m.middleware.security.api.ResourcePolicy;
import org.j7m.middleware.security.api.ResourcePolicyDecision;
import org.j7m.middleware.security.api.ResourcePolicyRequest;
import org.j7m.middleware.security.api.SecurityContext;

/**
 * Fail-closed resource policy that denies every request.
 */
public final class DenyAllResourcePolicy implements ResourcePolicy {
    private final boolean overloaded;

    /**
     * Creates a permission-denying policy.
     */
    public DenyAllResourcePolicy() {
        this(false);
    }

    /**
     * Creates a deny-all resource policy.
     *
     * @param overloaded true to report denials as overload
     */
    public DenyAllResourcePolicy(final boolean overloaded) {
        this.overloaded = overloaded;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String id() {
        return this.overloaded ? "deny-all-resource-overload" : "deny-all-resource";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ResourcePolicyDecision evaluate(final SecurityContext context, final ResourcePolicyRequest request) {
        if (this.overloaded) {
            return ResourcePolicyDecision.overload(this.id(), "resource-policy-overload");
        }
        return ResourcePolicyDecision.deny(this.id(), "resource-policy-deny");
    }
}
