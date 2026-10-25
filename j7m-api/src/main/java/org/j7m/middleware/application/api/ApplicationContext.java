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

import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.ResourceUsageSnapshot;

/**
 * Safe capabilities supplied to application lifecycle hooks.
 *
 * <p>The context is thread-safe if the supplied capability implementations are
 * thread-safe. It intentionally hides runtime maps, registry internals,
 * transport providers, and classloader management.</p>
 *
 * <p>The context is owned by one application activation lifecycle. It exposes
 * only safe capability facades for configuration, service export, audit,
 * metrics, and resource snapshots. Security decisions remain the responsibility
 * of the runtime and policy layer; the activation principal is informational
 * unless an API explicitly accepts it as input. Configuration, audit, metrics,
 * and resource status must be redacted before they are published. JPMS module
 * descriptors and declared {@code opens} clauses remain authoritative for
 * lifecycle and injection access.</p>
 */
public interface ApplicationContext {
    /**
     * Returns the immutable identity of the deployed application.
     *
     * @return application identity
     */
    ApplicationIdentity applicationIdentity();

    /**
     * Returns the identity of the currently active application instance.
     *
     * @return active instance identity
     */
    ApplicationInstanceIdentity instanceIdentity();

    /**
     * Returns the principal that requested this activation.
     *
     * @return principal that requested activation
     */
    Principal activationPrincipal();

    /**
     * Returns the runtime identity hosting this application instance.
     *
     * @return owning runtime identity
     */
    RuntimeIdentity runtimeIdentity();

    /**
     * Returns an immutable application configuration view.
     *
     * @return immutable configuration view
     */
    ConfigurationView configuration();

    /**
     * Returns the safe service export capability for this application.
     *
     * @return safe service exporter
     */
    ServiceExporter services();

    /**
     * Returns the application-scoped audit publishing capability.
     *
     * @return safe audit publisher
     */
    AuditPublisher audit();

    /**
     * Returns the application-scoped metrics publishing capability.
     *
     * @return safe metrics publisher
     */
    MetricsPublisher metrics();

    /**
     * Returns the current resource usage snapshot visible to the application.
     *
     * @return current resource snapshot
     */
    ResourceUsageSnapshot resources();
}
