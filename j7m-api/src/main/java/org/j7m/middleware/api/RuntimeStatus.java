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

package org.j7m.middleware.api;

import java.util.List;
import java.util.Map;

/**
 * Immutable read-only status snapshot of a middleware runtime.
 *
 * @param identity runtime identity
 * @param instances local exported instances
 * @param registryRecords visible registry record references
 * @param transports transport endpoint statuses
 * @param registry registry summary
 * @param invocationCount total invocation count
 * @param invocationFailureCount total failed invocation count
 * @param recentInvocations recent invocation log
 * @param security security and audit summary metadata
 */
public record RuntimeStatus(
        RuntimeIdentity identity,
        List<InstanceStatus> instances,
        List<ObjectRef> registryRecords,
        List<TransportStatus> transports,
        RegistryStatus registry,
        long invocationCount,
        long invocationFailureCount,
        List<InvocationMetadata> recentInvocations,
        Map<String, String> security) {

    /**
     * Creates immutable status snapshots.
     */
    public RuntimeStatus {
        instances = instances == null ? List.of() : List.copyOf(instances);
        registryRecords = registryRecords == null ? List.of() : List.copyOf(registryRecords);
        transports = transports == null ? List.of() : List.copyOf(transports);
        recentInvocations = recentInvocations == null ? List.of() : List.copyOf(recentInvocations);
        security = security == null ? Map.of() : Map.copyOf(security);
    }

    /**
     * Creates a compatibility status without security metadata.
     *
     * @param identity runtime identity
     * @param instances instances
     * @param registryRecords registry records
     * @param transports transports
     * @param registry registry status
     * @param invocationCount invocation count
     * @param invocationFailureCount failure count
     * @param recentInvocations recent invocations
     */
    public RuntimeStatus(
            final RuntimeIdentity identity,
            final List<InstanceStatus> instances,
            final List<ObjectRef> registryRecords,
            final List<TransportStatus> transports,
            final RegistryStatus registry,
            final long invocationCount,
            final long invocationFailureCount,
            final List<InvocationMetadata> recentInvocations) {
        this(identity, instances, registryRecords, transports, registry, invocationCount, invocationFailureCount, recentInvocations, Map.of());
    }
}
