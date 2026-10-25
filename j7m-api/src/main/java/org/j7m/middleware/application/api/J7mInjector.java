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

import java.lang.annotation.Annotation;

/**
 * J7M-native application component injector.
 *
 * <p>The injector creates application-owned components from public
 * annotations and public capability bindings. It is intentionally much smaller
 * than a general-purpose dependency injection framework: it supports
 * constructor injection, controlled field injection, safe configuration
 * values, invocation-scoped context values, lifecycle hooks, and diagnostics.
 * It does not require Spring, Guice, CDI, bytecode generation, classpath
 * scanning, or vendor-specific APIs.</p>
 *
 * <p>Implementations must be thread-safe for concurrent component creation
 * once configured. Blocking behavior is limited to reflective construction and
 * lifecycle method invocation; implementations must not perform network I/O as
 * part of dependency resolution. Missing or ambiguous dependencies must fail
 * with stable middleware error codes and redacted diagnostics.</p>
 */
public interface J7mInjector {
    /**
     * Creates an application-owned component.
     *
     * @param type component class
     * @param <T> component type
     * @return constructed and field-injected component
     */
    <T> T instantiate(Class<T> type);

    /**
     * Invokes all no-argument lifecycle methods annotated with the supplied
     * annotation.
     *
     * @param component component instance
     * @param annotation lifecycle annotation type
     */
    void invokeLifecycle(Object component, Class<? extends Annotation> annotation);

    /**
     * Returns a safe immutable status snapshot.
     *
     * @return injection graph status
     */
    InjectionGraphStatus status();
}

