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

package org.j7m.middleware.examples.workload;

import java.util.Map;
import org.j7m.middleware.application.api.ApplicationContext;
import org.j7m.middleware.application.api.J7mApplication;

/**
 * Bootstrap for the workload-processing deployable example.
 */
public final class WorkloadProcessingApplication implements J7mApplication {
    /**
     * Creates the application bootstrap.
     */
    public WorkloadProcessingApplication() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(final ApplicationContext context) {
        context.audit().publish("workload-example-initialize", Map.of("application",
                context.applicationIdentity().coordinate().toString()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start(final ApplicationContext context) {
        context.metrics().record("workload.example.started", 1L, Map.of("runtime", context.runtimeIdentity().runtimeId()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void drain(final ApplicationContext context) {
        context.metrics().record("workload.example.draining", 1L, Map.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop(final ApplicationContext context) {
        context.audit().publish("workload-example-stop", Map.of("application",
                context.applicationIdentity().coordinate().toString()));
    }
}
