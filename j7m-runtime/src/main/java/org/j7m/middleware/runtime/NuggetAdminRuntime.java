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

package org.j7m.middleware.runtime;

import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.operations.api.NuggetAdminService;
import org.j7m.middleware.operations.runtime.NuggetAdminController;

/**
 * Runtime assembly facade for the headless nugget administration service.
 *
 * <p>This facade is the supported runtime entry point for Phase 7.1 nugget PIN
 * admission and Phase 7.3 local/configured discovery. It starts only an
 * in-process service and does not bind a network endpoint, parse CLI commands,
 * perform remote authentication, or grant authorization.</p>
 */
public final class NuggetAdminRuntime {
    private NuggetAdminRuntime() {
    }

    /**
     * Starts a headless nugget administration service for one runtime identity.
     *
     * @param runtimeIdentity hosted runtime identity
     * @param configuration runtime nugget admin configuration
     * @return started administration service
     */
    public static NuggetAdminService start(
            final RuntimeIdentity runtimeIdentity,
            final NuggetAdminRuntimeConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        return NuggetAdminController.start(runtimeIdentity, configuration.toInternalConfiguration());
    }

    static NuggetAdminService start(
            final RuntimeIdentity runtimeIdentity,
            final NuggetAdminRuntimeConfiguration configuration,
            final Runnable runtimeCloseAction) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        return NuggetAdminController.start(runtimeIdentity, configuration.toInternalConfiguration(), runtimeCloseAction);
    }
}
