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

package org.j7m.examples.annotation.isolation;

import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.IsolationProfile;
import org.j7m.middleware.annotations.J7mResource;
import org.j7m.middleware.annotations.J7mService;
import org.j7m.middleware.annotations.RemoteMethod;
import org.j7m.middleware.annotations.Sensitive;
import org.j7m.middleware.api.isolation.IsolationProvider;

/**
 * Service example for isolation-profile descriptor metadata.
 *
 * <p>{@link IsolationProfile} selects a named profile in the descriptor. In
 * this example it is descriptor-only metadata; actual filesystem, network,
 * environment, resource-budget, and stdout/stderr controls are provided by the
 * injected {@link IsolationProvider}. The source intentionally avoids claiming
 * Docker, Kubernetes, container runtime, or OS sandbox guarantees.</p>
 *
 * <p>{@link Sensitive} marks the input so status and demo output must redact
 * it. The method returns only a redacted summary and never exposes process
 * output or policy details that could contain secrets.</p>
 */
@J7mService(name = "//apps/example/annotation/isolation/ReportService", exported = true)
@IsolationProfile("baseline-process")
public final class IsolatedReportService {
    // J7M teaching: isolation annotations select policy metadata while the
    // provider owns enforcement and advisory behavior.
    private final IsolationProvider provider;

    /**
     * Creates the service with the public isolation capability.
     *
     * @param provider public isolation provider bound to the example profile
     */
    @Inject
    public IsolatedReportService(@J7mResource("baseline-process") final IsolationProvider provider) {
        this.provider = provider;
    }

    /**
     * Renders a report through descriptor-backed isolation metadata.
     *
     * <p>The method proves that {@link RemoteMethod} and
     * {@link IsolationProfile} can be discovered together. It does not launch a
     * child process; a real application would call the injected
     * {@link IsolationProvider} with explicit filesystem, network,
     * environment, and resource policies. Enforced/advisory/policy-only
     * distinctions remain those reported by the provider.</p>
     *
     * @param input sensitive report input
     * @return redacted report summary
     */
    @RemoteMethod
    @IsolationProfile("baseline-process")
    public String render(@Sensitive final String input) {
        // J7M teaching: isolation annotations select policy metadata; this
        // example only touches the public provider facade and redacts output
        // instead of pretending to enforce a container or OS sandbox.
        this.provider.getClass();
        return "rendered=<redacted>";
    }
}
