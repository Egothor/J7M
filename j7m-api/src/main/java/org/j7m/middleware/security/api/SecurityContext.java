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

import java.io.Serializable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.reliability.InvocationReliabilityOptions;

/**
 * Immutable per-operation security context.
 *
 * @param principal caller principal
 * @param callerRuntimeId caller runtime id
 * @param trustDomain caller trust domain
 * @param credential credential descriptor
 * @param correlationId correlation id
 * @param invocationId invocation id
 * @param remoteEndpoint remote endpoint when known
 * @param authenticated whether caller authentication succeeded
 * @param transportSecurityLevel transport security level
 * @param roles caller roles
 * @param requestMetadata safe request metadata
 * @param createdAt context creation time
 */
public record SecurityContext(
        Principal principal,
        String callerRuntimeId,
        TrustDomain trustDomain,
        Credential credential,
        String correlationId,
        String invocationId,
        String remoteEndpoint,
        boolean authenticated,
        TransportSecurityLevel transportSecurityLevel,
        Set<String> roles,
        Map<String, String> requestMetadata,
        Instant createdAt) implements Serializable {
    /**
     * Creates immutable context fields.
     */
    public SecurityContext {
        principal = principal == null ? Principal.anonymous() : principal;
        callerRuntimeId = callerRuntimeId == null ? "" : callerRuntimeId;
        trustDomain = trustDomain == null ? new TrustDomain("default") : trustDomain;
        credential = credential == null ? new Credential("none", "0", Map.of()) : credential;
        correlationId = correlationId == null ? "" : correlationId;
        invocationId = invocationId == null ? "" : invocationId;
        remoteEndpoint = remoteEndpoint == null ? "" : remoteEndpoint;
        transportSecurityLevel = transportSecurityLevel == null ? TransportSecurityLevel.UNKNOWN : transportSecurityLevel;
        roles = roles == null || roles.isEmpty() ? principal.roles() : Set.copyOf(roles);
        requestMetadata = requestMetadata == null ? Map.of() : Map.copyOf(requestMetadata);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /**
     * Returns a development context for a runtime.
     *
     * @param runtimeId runtime id
     * @return system context
     */
    public static SecurityContext system(final String runtimeId) {
        Principal principal = Principal.runtime(runtimeId);
        return new SecurityContext(principal, runtimeId, new TrustDomain("default"), new Credential("dev", "1", Map.of()),
                "", "", "", true, TransportSecurityLevel.TEST_AUTHENTICATED, principal.roles(), Map.of(), Instant.now());
    }

    /**
     * Encodes this context into invocation metadata.
     *
     * @return metadata map
     */
    public Map<String, String> toMetadata() {
        Map<String, String> values = new LinkedHashMap<>(this.requestMetadata);
        values.put("sec.principal", this.principal.id());
        values.put("sec.runtime", this.callerRuntimeId);
        values.put("sec.trustDomain", this.trustDomain.id());
        values.put("sec.credentialType", this.credential.type());
        values.put("sec.credentialVersion", this.credential.version());
        values.put("sec.correlationId", this.correlationId);
        values.put("sec.authenticated", Boolean.toString(this.authenticated));
        values.put("sec.level", this.transportSecurityLevel.name());
        values.put("sec.roles", String.join(",", this.roles));
        values.put("sec.createdAt", this.createdAt.toString());
        return Map.copyOf(values);
    }

    /**
     * Decodes an untrusted security context from invocation options.
     *
     * <p>Public invocation metadata is caller controlled. Keys in the
     * {@code sec.} namespace are retained only as request metadata for audit
     * redaction and are not trusted as authentication, principal, role,
     * credential, trust-domain, or transport-security evidence.</p>
     *
     * @param options invocation options
     * @param fallbackRuntimeId fallback caller runtime
     * @return decoded context
     */
    public static SecurityContext fromInvocationOptions(final InvocationOptions options, final String fallbackRuntimeId) {
        Map<String, String> metadata = options == null ? Map.of() : options.metadata();
        String runtimeId = fallbackRuntimeId == null ? "" : fallbackRuntimeId;
        String correlationId = metadata.getOrDefault(InvocationReliabilityOptions.PREFIX + "correlationId",
                metadata.getOrDefault("reliability.correlationId", ""));
        return new SecurityContext(Principal.anonymous(), runtimeId, new TrustDomain("default"),
                new Credential("none", "0", Map.of()), correlationId, options == null ? "" : options.invocationId(),
                "", false, TransportSecurityLevel.INSECURE, Set.of(), metadata, Instant.now());
    }

    /**
     * Returns a trusted transport context with invocation metadata attached.
     *
     * <p>This method is intended only for local or transport-authenticated
     * boundaries that have already validated peer identity outside public
     * invocation metadata.</p>
     *
     * @param trusted trusted context supplied by the transport or local boundary
     * @param options invocation options whose public metadata remains untrusted
     * @return trusted context for dispatch
     */
    public static SecurityContext fromTrustedBoundary(final SecurityContext trusted, final InvocationOptions options) {
        if (trusted == null) {
            return fromInvocationOptions(options, "");
        }
        Map<String, String> metadata = options == null ? Map.of() : options.metadata();
        return new SecurityContext(trusted.principal(), trusted.callerRuntimeId(), trusted.trustDomain(), trusted.credential(),
                trusted.correlationId(), options == null ? trusted.invocationId() : options.invocationId(),
                trusted.remoteEndpoint(), trusted.authenticated(), trusted.transportSecurityLevel(), trusted.roles(),
                metadata, trusted.createdAt());
    }

}
