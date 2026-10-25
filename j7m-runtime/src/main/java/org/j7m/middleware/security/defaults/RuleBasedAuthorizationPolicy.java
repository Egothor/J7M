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

import java.util.List;
import java.util.Set;
import org.j7m.middleware.security.api.AuthorizationDecision;
import org.j7m.middleware.security.api.AuthorizationPolicy;
import org.j7m.middleware.security.api.PermissionAction;
import org.j7m.middleware.security.api.PermissionRequest;
import org.j7m.middleware.security.api.SecurityContext;

/**
 * Simple role/action/resource authorization policy.
 *
 * @param rules immutable rule list
 */
public record RuleBasedAuthorizationPolicy(List<Rule> rules) implements AuthorizationPolicy {
    /**
     * Creates immutable rules.
     */
    public RuleBasedAuthorizationPolicy {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String id() {
        return "rule-based";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AuthorizationDecision evaluate(final SecurityContext context, final PermissionRequest request) {
        for (Rule rule : this.rules) {
            if (rule.matches(context, request)) {
                return AuthorizationDecision.allow(this.id());
            }
        }
        return AuthorizationDecision.deny(this.id(), "no matching allow rule");
    }

    /**
     * Immutable allow rule.
     *
     * @param roles accepted roles
     * @param actions accepted actions
     * @param resourcePrefix accepted resource prefix
     */
    public record Rule(Set<String> roles, Set<PermissionAction> actions, String resourcePrefix) {
        /**
         * Creates immutable rule sets.
         */
        public Rule {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            actions = actions == null ? Set.of() : Set.copyOf(actions);
            resourcePrefix = resourcePrefix == null ? "" : resourcePrefix;
        }

        private boolean matches(final SecurityContext context, final PermissionRequest request) {
            boolean roleMatches = this.roles.isEmpty() || context.roles().stream().anyMatch(this.roles::contains);
            boolean actionMatches = this.actions.isEmpty() || this.actions.contains(request.action());
            boolean resourceMatches = this.resourcePrefix.isBlank() || request.resource().startsWith(this.resourcePrefix);
            return roleMatches && actionMatches && resourceMatches;
        }
    }
}
