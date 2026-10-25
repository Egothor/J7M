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

import java.util.Map;

/**
 * Immutable resource-policy evaluation request.
 *
 * @param action action being evaluated
 * @param applicationContext application context
 * @param runtimeId runtime id
 * @param serviceName service name when known
 * @param objectId object id when known
 * @param methodDescriptor method descriptor when known
 * @param quota configured quota snapshot
 * @param usage current usage snapshot
 * @param metadata safe metadata
 */
public record ResourcePolicyRequest(
        ResourcePolicyAction action,
        ApplicationContextId applicationContext,
        String runtimeId,
        String serviceName,
        String objectId,
        String methodDescriptor,
        ResourceQuota quota,
        ResourceUsageSnapshot usage,
        Map<String, String> metadata) {
    /**
     * Creates immutable request fields.
     */
    public ResourcePolicyRequest {
        action = action == null ? ResourcePolicyAction.INVOKE : action;
        applicationContext = applicationContext == null ? new ApplicationContextId("default") : applicationContext;
        runtimeId = runtimeId == null ? "" : runtimeId;
        serviceName = serviceName == null ? "" : serviceName;
        objectId = objectId == null ? "" : objectId;
        methodDescriptor = methodDescriptor == null ? "" : methodDescriptor;
        quota = quota == null ? new ResourceQuota(0, 0, 0, 0) : quota;
        usage = usage == null ? new ResourceUsageSnapshot(0, 0, 0, 0, 0) : usage;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
