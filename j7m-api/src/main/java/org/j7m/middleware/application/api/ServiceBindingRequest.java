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

package org.j7m.middleware.application.api;

import java.util.List;
import java.util.Objects;
import org.j7m.middleware.annotations.BindingRefresh;

/**
 * Immutable service selection contract. Candidate metadata is only a compatibility filter;
 * a mandatory host admission check establishes actual trust, authorization and readiness.
 * @param <T> ordinary business interface
 * @param type requested interface class
 * @param serviceName logical service identity
 * @param contractId explicit stable contract ID
 * @param contractVersion positive required version
 * @param qualifiers exact custom qualifiers
 * @param registryContext registry/application isolation context
 * @param refresh opt-in target refresh
 */
public record ServiceBindingRequest<T>(Class<T> type, String serviceName, String contractId,
        int contractVersion, List<ManagedQualifier> qualifiers, String registryContext, BindingRefresh refresh) {
    /**
     * Validates and defensively copies selection metadata.
     * @param type required ordinary interface
     * @param serviceName exact nonblank name, at most256 characters
     * @param contractId required nonblank identity, at most512 characters
     * @param contractVersion positive contract version
     * @param qualifiers at most16 exact qualifier identities
     * @param registryContext required context, at most256 characters
     * @param refresh explicit mode
     * @throws IllegalArgumentException for invalid contracts or bounds
     */
    public ServiceBindingRequest {
        Objects.requireNonNull(type); Objects.requireNonNull(refresh);
        if (!type.isInterface() || serviceName == null || serviceName.isBlank() || serviceName.length() > 256
                || contractId == null || contractId.isBlank() || contractId.length() > 512 || contractVersion < 1
                || registryContext == null || registryContext.isBlank() || registryContext.length() > 256) throw new IllegalArgumentException("Invalid service binding contract");
        qualifiers = new ManagedDependency(type, "", qualifiers, false).qualifiers();
    }
}
