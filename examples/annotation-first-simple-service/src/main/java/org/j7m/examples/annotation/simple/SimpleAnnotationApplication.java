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

package org.j7m.examples.annotation.simple;

import org.j7m.middleware.annotations.CurrentApplication;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mApplication;
import org.j7m.middleware.annotations.Startup;
import org.j7m.middleware.application.api.ApplicationContext;

/**
 * Minimal annotation-first application shell for the simple-service example.
 *
 * <p>{@link J7mApplication} supplies stable application identity metadata that
 * the annotation processor can turn into a descriptor. The constructor shows
 * the preferred J7M programming model: application code asks for the public
 * {@link ApplicationContext} with {@link CurrentApplication} and for its
 * business service directly, instead of manually reading runtime maps,
 * transport registries, or module-layer internals.</p>
 *
 * <p>The {@link Startup} method is executed by the J7M injector in the
 * lifecycle order demonstrated by the tests. Service export and ObjectRef
 * publication remain runtime responsibilities; this example validates the
 * descriptor metadata and keeps the low-level export API as an advanced path.
 * No authorization or retry behavior is implied by this application class.</p>
 */
@J7mApplication(group = "org.j7m.examples", name = "annotation-simple", version = "1.0.0")
public final class SimpleAnnotationApplication {
    // J7M teaching: the application asks for public context and a service, so
    // copy/paste code avoids runtime maps, transport handles, and globals.
    private final ApplicationContext context;
    private final SimpleGreetingService service;
    private String startupSummary;

    /**
     * Creates the application from public, injection-safe capabilities.
     *
     * <p>This constructor is safe to copy into a real application: the
     * annotation container supplies the current application context and the
     * service dependency. A missing service binding fails during injection with
     * a stable middleware error instead of exposing internal registries.</p>
     *
     * @param context safe application context for this activation
     * @param service greeting service constructed by the J7M injector
     */
    @Inject
    public SimpleAnnotationApplication(
            @CurrentApplication final ApplicationContext context,
            final SimpleGreetingService service) {
        this.context = context;
        this.service = service;
        this.startupSummary = "";
    }

    /**
     * Starts the simple application and invokes the annotated service locally.
     *
     * <p>The method demonstrates lifecycle wiring and low-boilerplate business
     * invocation. It is intentionally local: the example does not create a live
     * transport export, and therefore does not weaken ObjectRef authorization
     * or reference semantics.</p>
     */
    @Startup
    public void start() {
        this.startupSummary = this.context.applicationIdentity().coordinate() + ":" + this.service.greet("developer");
    }

    /**
     * Returns the safe summary produced by the startup lifecycle hook.
     *
     * @return public startup summary, or an empty string before startup
     */
    public String startupSummary() {
        return this.startupSummary;
    }
}
