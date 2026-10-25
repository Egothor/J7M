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
import java.util.Set;

/**
 * Immutable authenticated or anonymous caller principal.
 *
 * @param id stable principal id
 * @param displayName safe display name
 * @param roles role names
 * @param tags public caller tags
 */
public record Principal(String id, String displayName, Set<String> roles, Set<String> tags) implements Serializable {
    /**
     * Creates immutable principal fields.
     */
    public Principal {
        id = id == null || id.isBlank() ? "anonymous" : id;
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    /**
     * Returns an anonymous principal.
     *
     * @return anonymous principal
     */
    public static Principal anonymous() {
        return new Principal("anonymous", "anonymous", Set.of(), Set.of());
    }

    /**
     * Returns a system principal.
     *
     * @param runtimeId runtime id
     * @return system principal
     */
    public static Principal runtime(final String runtimeId) {
        return new Principal(runtimeId == null ? "runtime" : runtimeId, runtimeId == null ? "runtime" : runtimeId, Set.of("runtime", "admin"), Set.of("system"));
    }
}
