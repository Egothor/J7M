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

import java.util.Map;

/**
 * Stable descriptor derived from an annotated service method.
 *
 * <p>The descriptor records only safe method metadata. Security, idempotency,
 * deadline, retry, and backpressure fields are used to validate that the
 * annotation-first model maps to the same public invocation semantics as the
 * lower-level API.</p>
 *
 * @param methodName Java method name
 * @param remote whether the method is exported remotely
 * @param idempotent whether retry/dedupe metadata marks the method idempotent
 * @param requiredRole required caller role, or the empty string
 * @param requiredPermission required permission action, or the empty string
 * @param resource required permission resource, or the empty string
 * @param metadata safe additional metadata
 */
public record AnnotatedServiceMethodDescriptor(
        String methodName,
        boolean remote,
        boolean idempotent,
        String requiredRole,
        String requiredPermission,
        String resource,
        Map<String, String> metadata) {
    /**
     * Creates an immutable method descriptor.
     */
    public AnnotatedServiceMethodDescriptor {
        methodName = methodName == null ? "" : methodName;
        requiredRole = requiredRole == null ? "" : requiredRole;
        requiredPermission = requiredPermission == null ? "" : requiredPermission;
        resource = resource == null ? "" : resource;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

