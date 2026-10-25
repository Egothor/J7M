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

package org.j7m.middleware.isolation.internal;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.j7m.middleware.api.isolation.EnvironmentPolicy;
import org.j7m.middleware.api.isolation.EnvironmentVariableRule;
import org.j7m.middleware.api.isolation.FileSystemAccess;
import org.j7m.middleware.api.isolation.FileSystemPolicy;
import org.j7m.middleware.api.isolation.FileSystemRule;
import org.j7m.middleware.api.isolation.IsolationPolicyDecision;
import org.j7m.middleware.api.isolation.IsolationSubject;
import org.j7m.middleware.api.isolation.NetworkEgressPolicy;
import org.j7m.middleware.api.isolation.NetworkEgressRule;

/**
 * Internal deterministic policy evaluator.
 */
public final class IsolationPolicyEvaluator {
    private final IsolationRedactor redactor;

    /**
     * Creates an evaluator.
     *
     * @param redactor redactor
     */
    public IsolationPolicyEvaluator(final IsolationRedactor redactor) {
        this.redactor = redactor;
    }

    /**
     * Evaluates filesystem access.
     *
     * @param subject subject
     * @param policy policy
     * @param access access type
     * @param path path
     * @return decision
     */
    public IsolationPolicyDecision fileSystem(
            final IsolationSubject subject,
            final FileSystemPolicy policy,
            final FileSystemAccess access,
            final Path path) {
        final Path normalized = path == null ? Path.of(".").toAbsolutePath().normalize() : path.toAbsolutePath().normalize();
        boolean matched = false;
        boolean allowed = !policy.denyAllByDefault();
        String reason = allowed ? "FILESYSTEM_ALLOWED_DEFAULT" : "FILESYSTEM_DENIED_DEFAULT";
        boolean redact = false;
        for (final FileSystemRule rule : policy.rules()) {
            if (rule.access() == access && normalized.startsWith(rule.root())) {
                matched = true;
                allowed = rule.allow();
                reason = allowed ? "FILESYSTEM_ALLOWED_RULE" : "FILESYSTEM_DENIED_RULE";
                redact = rule.redactPath();
            }
        }
        final String summary = this.redactor.pathSummary(normalized, redact);
        return new IsolationPolicyDecision(allowed, reason,
                Map.of("subject", subject.id().value(), "path", summary, "matched", Boolean.toString(matched)));
    }

    /**
     * Evaluates network egress.
     *
     * @param subject subject
     * @param policy policy
     * @param destination destination URI
     * @return decision
     */
    public IsolationPolicyDecision network(
            final IsolationSubject subject,
            final NetworkEgressPolicy policy,
            final URI destination) {
        final String host = destination == null || destination.getHost() == null ? "" : destination.getHost().toLowerCase(java.util.Locale.ROOT);
        final int port = destination == null ? -1 : destination.getPort();
        final boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        if (loopback && policy.allowLoopback()) {
            return new IsolationPolicyDecision(true, "NETWORK_ALLOWED_LOOPBACK", Map.of("host", host, "port", Integer.toString(port)));
        }
        if (!loopback && policy.denyNonLoopback()) {
            return new IsolationPolicyDecision(false, "NETWORK_DENIED_NON_LOOPBACK", Map.of("host", host, "port", Integer.toString(port)));
        }
        boolean allowed = !policy.denyAllByDefault();
        String reason = allowed ? "NETWORK_ALLOWED_DEFAULT" : "NETWORK_DENIED_DEFAULT";
        for (final NetworkEgressRule rule : policy.rules()) {
            final boolean hostMatches = "*".equals(rule.host()) || rule.host().equals(host);
            final boolean portMatches = rule.port() == -1 || rule.port() == port;
            if (hostMatches && portMatches) {
                allowed = rule.allow();
                reason = allowed ? "NETWORK_ALLOWED_RULE" : "NETWORK_DENIED_RULE";
            }
        }
        return new IsolationPolicyDecision(allowed, reason, Map.of("host", host, "port", Integer.toString(port)));
    }

    /**
     * Evaluates environment access.
     *
     * @param subject subject
     * @param policy policy
     * @param name name
     * @param value value
     * @return decision
     */
    public IsolationPolicyDecision environment(
            final IsolationSubject subject,
            final EnvironmentPolicy policy,
            final String name,
            final String value) {
        final String actualName = name == null ? "" : name;
        final String actualValue = value == null ? "" : value;
        if (actualValue.length() > policy.maxVariableLength()) {
            return new IsolationPolicyDecision(false, "ENVIRONMENT_DENIED_VALUE_TOO_LARGE", Map.of("name", actualName));
        }
        boolean allowed = !policy.denyAllByDefault();
        boolean redact = this.redactor.sensitiveKey(actualName);
        String reason = allowed ? "ENVIRONMENT_ALLOWED_DEFAULT" : "ENVIRONMENT_DENIED_DEFAULT";
        for (final EnvironmentVariableRule rule : policy.rules()) {
            if (rule.name().equals(actualName)) {
                allowed = rule.allow();
                redact = redact || rule.redact();
                reason = allowed ? "ENVIRONMENT_ALLOWED_RULE" : "ENVIRONMENT_DENIED_RULE";
            }
        }
        final String safeValue = redact ? "<redacted>" : this.redactor.redactText(actualValue, null);
        return new IsolationPolicyDecision(allowed, reason, Map.of("name", actualName, "value", safeValue));
    }
}
