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

package org.j7m.middleware.isolation;

import java.nio.file.Path;
import java.time.Clock;
import org.j7m.middleware.api.isolation.ResourceBudget;

/**
 * Configuration for first-party isolation providers.
 *
 * <p>The configuration is immutable and thread-safe. It owns only local
 * provider directories and default admission-control limits; it does not
 * configure Docker, Kubernetes, OCI, or any external sandbox product.</p>
 *
 * @param providerDirectory provider-owned temporary and recovery directory
 * @param clock clock used for audit, timeout, and metrics timestamps
 * @param defaultBudget default budget for requests that do not provide one
 * @param maxAuditEvents maximum audit events retained in memory
 */
public record IsolationConfig(
        Path providerDirectory,
        Clock clock,
        ResourceBudget defaultBudget,
        int maxAuditEvents) {
    /**
     * Creates a validated isolation configuration.
     */
    public IsolationConfig {
        if (providerDirectory == null) {
            throw new IllegalArgumentException("provider directory is required");
        }
        providerDirectory = providerDirectory.toAbsolutePath().normalize();
        clock = clock == null ? Clock.systemUTC() : clock;
        defaultBudget = defaultBudget == null ? ResourceBudget.defaults() : defaultBudget;
        if (maxAuditEvents <= 0) {
            throw new IllegalArgumentException("max audit events must be positive");
        }
    }

    /**
     * Creates a default local configuration.
     *
     * @param directory provider root directory
     * @return validated default configuration
     */
    public static IsolationConfig defaults(final Path directory) {
        return new IsolationConfig(directory, Clock.systemUTC(), ResourceBudget.defaults(), 512);
    }
}
