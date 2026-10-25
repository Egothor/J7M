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

package org.j7m.middleware.security.api;

import java.time.Duration;

/**
 * Immutable runtime security configuration.
 *
 * @param mode security mode
 * @param policy authorization policy
 * @param auditSink audit sink
 * @param redactor redactor
 * @param requireAuthentication whether requests require authentication
 * @param allowInsecureProductionTransport explicit production insecure override
 * @param maxInboundMessageBytes max inbound message bytes
 * @param maxOutboundMessageBytes max outbound message bytes
 * @param maxConcurrentInvocations max concurrent invocations
 * @param adminDeadline admin deadline
 * @param credentialProvider credential provider
 * @param replayProtectionEnabled whether invocation replay protection is enabled
 * @param allowedClockSkew accepted request timestamp skew
 * @param maxReplayEntries maximum retained replay cache entries
 * @param strictRegistryTrust whether invalid or unsigned registry records are rejected
 * @param resourcePolicy resource policy
 */
public record SecurityConfiguration(
        SecurityMode mode,
        AuthorizationPolicy policy,
        SecurityAuditSink auditSink,
        Redactor redactor,
        boolean requireAuthentication,
        boolean allowInsecureProductionTransport,
        int maxInboundMessageBytes,
        int maxOutboundMessageBytes,
        int maxConcurrentInvocations,
        Duration adminDeadline,
        CredentialProvider credentialProvider,
        boolean replayProtectionEnabled,
        Duration allowedClockSkew,
        int maxReplayEntries,
        boolean strictRegistryTrust,
        ResourcePolicy resourcePolicy) {
    /**
     * Creates security configuration.
     */
    public SecurityConfiguration {
        mode = mode == null ? SecurityMode.DEV : mode;
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (auditSink == null) {
            throw new IllegalArgumentException("auditSink must not be null");
        }
        if (redactor == null) {
            throw new IllegalArgumentException("redactor must not be null");
        }
        maxInboundMessageBytes = maxInboundMessageBytes <= 0 ? 1024 * 1024 : maxInboundMessageBytes;
        maxOutboundMessageBytes = maxOutboundMessageBytes <= 0 ? 1024 * 1024 : maxOutboundMessageBytes;
        maxConcurrentInvocations = maxConcurrentInvocations <= 0 ? 1024 : maxConcurrentInvocations;
        adminDeadline = adminDeadline == null ? Duration.ofSeconds(2L) : adminDeadline;
        allowedClockSkew = allowedClockSkew == null ? Duration.ofSeconds(30L) : allowedClockSkew;
        maxReplayEntries = maxReplayEntries <= 0 ? 4096 : maxReplayEntries;
        resourcePolicy = resourcePolicy == null ? new DefaultAllowResourcePolicy() : resourcePolicy;
    }

    /**
     * Creates configuration with Phase 3.1 fields but no resource policy.
     *
     * @param mode security mode
     * @param policy authorization policy
     * @param auditSink audit sink
     * @param redactor redactor
     * @param requireAuthentication whether authentication is required
     * @param allowInsecureProductionTransport insecure production override
     * @param maxInboundMessageBytes inbound message limit
     * @param maxConcurrentInvocations concurrent invocation limit
     * @param adminDeadline admin deadline
     * @param credentialProvider credential provider
     * @param replayProtectionEnabled whether replay protection is enabled
     * @param allowedClockSkew allowed clock skew
     * @param maxReplayEntries max replay entries
     * @param strictRegistryTrust strict registry trust flag
     */
    public SecurityConfiguration(
            final SecurityMode mode,
            final AuthorizationPolicy policy,
            final SecurityAuditSink auditSink,
            final Redactor redactor,
            final boolean requireAuthentication,
            final boolean allowInsecureProductionTransport,
            final int maxInboundMessageBytes,
            final int maxConcurrentInvocations,
            final Duration adminDeadline,
            final CredentialProvider credentialProvider,
            final boolean replayProtectionEnabled,
            final Duration allowedClockSkew,
            final int maxReplayEntries,
            final boolean strictRegistryTrust) {
        this(mode, policy, auditSink, redactor, requireAuthentication, allowInsecureProductionTransport, maxInboundMessageBytes,
                maxInboundMessageBytes, maxConcurrentInvocations, adminDeadline, credentialProvider, replayProtectionEnabled, allowedClockSkew,
                maxReplayEntries, strictRegistryTrust, null);
    }

    /**
     * Creates a Phase 3.0-compatible configuration.
     *
     * @param mode security mode
     * @param policy authorization policy
     * @param auditSink audit sink
     * @param redactor redactor
     * @param requireAuthentication whether authentication is required
     * @param allowInsecureProductionTransport insecure production override
     * @param maxInboundMessageBytes inbound message limit
     * @param maxConcurrentInvocations concurrent invocation limit
     * @param adminDeadline admin deadline
     * @param credentialProvider credential provider
     */
    public SecurityConfiguration(
            final SecurityMode mode,
            final AuthorizationPolicy policy,
            final SecurityAuditSink auditSink,
            final Redactor redactor,
            final boolean requireAuthentication,
            final boolean allowInsecureProductionTransport,
            final int maxInboundMessageBytes,
            final int maxConcurrentInvocations,
            final Duration adminDeadline,
            final CredentialProvider credentialProvider) {
        this(mode, policy, auditSink, redactor, requireAuthentication, allowInsecureProductionTransport, maxInboundMessageBytes,
                maxInboundMessageBytes, maxConcurrentInvocations, adminDeadline, credentialProvider, false, Duration.ofSeconds(30L), 4096, false, null);
    }

    private static final class DefaultAllowResourcePolicy implements ResourcePolicy {
        @Override
        public String id() {
            return "allow-all-resource";
        }

        @Override
        public ResourcePolicyDecision evaluate(final SecurityContext context, final ResourcePolicyRequest request) {
            return ResourcePolicyDecision.allow(this.id());
        }
    }
}
