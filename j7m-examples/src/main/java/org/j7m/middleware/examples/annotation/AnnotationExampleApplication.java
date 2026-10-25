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

package org.j7m.middleware.examples.annotation;

import org.j7m.middleware.annotations.CurrentApplication;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mApplication;
import org.j7m.middleware.annotations.Startup;
import org.j7m.middleware.application.api.ApplicationContext;

/**
 * Annotation-first application entry point used by the examples and demo.
 *
 * <p>The class receives only the safe public {@link ApplicationContext} and a
 * business service. It does not receive registry maps, transport internals, or
 * classloader controls. Its {@link Startup} hook participates in the same
 * deterministic lifecycle order shown by the full annotation-first lifecycle
 * example.</p>
 */
@J7mApplication(group = "org.j7m.examples", name = "annotation-first", version = "1.0.0")
public final class AnnotationExampleApplication {
        // J7M teaching: this block is copyable application code that relies on descriptors and J7M-native injection instead of runtime internals.
    private final ApplicationContext applicationContext;
    private final AnnotationSimpleService simpleService;
    private boolean started;

    /**
     * Creates the application from injected public capabilities.
     *
     * @param applicationContext safe application context
     * @param simpleService simple service
     */
    @Inject
    public AnnotationExampleApplication(
            @CurrentApplication final ApplicationContext applicationContext,
            final AnnotationSimpleService simpleService) {
        this.applicationContext = applicationContext;
        this.simpleService = simpleService;
    }

    /**
     * Starts application-owned state in the startup lifecycle phase.
     *
     * <p>The hook is invoked after injection has completed. It performs only
     * local state changes so startup output cannot leak secrets or internal
     * runtime handles.</p>
     */
    @Startup
    public void start() {
        this.started = true;
    }

    /**
     * Returns the application coordinate visible through the safe context.
     *
     * @return application coordinate string
     */
    public String applicationCoordinate() {
        if (this.applicationContext == null || this.applicationContext.applicationIdentity() == null) {
            return "";
        }
        return this.applicationContext.applicationIdentity().coordinate().toString();
    }

    /**
     * Returns a greeting from the injected service.
     *
     * @param name caller name
     * @return greeting
     */
    public String greet(final String name) {
        return this.simpleService.greet(name);
    }

    /**
     * Returns whether the startup hook ran.
     *
     * @return startup state
     */
    public boolean started() {
        return this.started;
    }
}
