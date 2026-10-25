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

package org.j7m.middleware.admin;

import org.j7m.middleware.api.MiddlewareRuntime;
import org.j7m.middleware.api.RuntimeStatus;

/**
 * Minimal read-only admin facade for Phase 1 runtimes.
 *
 * <p>The facade is immutable and thread-safe when the supplied runtime is
 * thread-safe. It intentionally exposes only status snapshots.</p>
 */
public final class RuntimeAdmin {
    private final MiddlewareRuntime runtime;

    /**
     * Creates an admin facade.
     *
     * @param runtime runtime to inspect
     */
    public RuntimeAdmin(final MiddlewareRuntime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime must not be null");
        }
        this.runtime = runtime;
    }

    /**
     * Returns a runtime status snapshot.
     *
     * @return runtime status
     */
    public RuntimeStatus status() {
        return this.runtime.status();
    }

    /**
     * Formats a compact text status for demos and diagnostics.
     *
     * @return status text
     */
    public String formatStatus() {
        RuntimeStatus status = this.runtime.status();
        return "runtime=" + status.identity().runtimeId()
                + ", instances=" + status.instances().size()
                + ", registryRecords=" + status.registryRecords().size()
                + ", peers=" + status.registry().peerCount()
                + ", peerGraph=" + status.registry().peerGraphSize()
                + ", mcastAdapter=" + status.registry().multicastAdapterType()
                + ", mcastSent=" + status.registry().multicastMessagesSent()
                + ", mcastReceived=" + status.registry().multicastMessagesReceived()
                + ", bootstrapQueries=" + status.registry().metrics().getOrDefault("bootstrapQueries", 0L)
                + ", hpDecisions=" + status.registry().recentHpDecisions().size()
                + ", invocations=" + status.invocationCount()
                + ", failures=" + status.invocationFailureCount()
                + ", securityMode=" + status.security().getOrDefault("mode", "unknown")
                + ", authPolicy=" + status.security().getOrDefault("authorizationPolicy", "unknown")
                + ", auditEvents=" + status.security().getOrDefault("auditEvents", "0")
                + ", denies=" + status.security().getOrDefault("denyCount", "0")
                + ", unauthenticated=" + status.security().getOrDefault("unauthenticatedCount", "0")
                + ", replayRejected=" + status.security().getOrDefault("replayRejectedCount", "0")
                + ", timestampRejected=" + status.security().getOrDefault("timestampRejectedCount", "0")
                + ", resourcePolicy=" + status.security().getOrDefault("resourcePolicy", "unknown")
                + ", resourceDenied=" + status.security().getOrDefault("resourcePolicyDeniedCount", "0")
                + ", overloadRejected=" + status.security().getOrDefault("overloadRejectedCount", "0")
                + ", activeInvocations=" + status.security().getOrDefault("activeInvocations", "0")
                + ", maxInboundBytes=" + status.security().getOrDefault("maxInboundMessageBytes", "0")
                + ", maxOutboundBytes=" + status.security().getOrDefault("maxOutboundMessageBytes", "0");
    }
}
