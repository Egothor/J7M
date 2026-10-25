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

import org.j7m.middleware.annotations.ConfigValue;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mComponent;
import org.j7m.middleware.annotations.J7mResource;
import org.j7m.middleware.annotations.J7mService;
import org.j7m.middleware.annotations.Named;
import org.j7m.middleware.annotations.RemoteMethod;

/**
 * Annotation-first greeting service used as the smallest complete service
 * example.
 *
 * <p>{@link J7mService} describes the service name and export intent that the
 * application platform can place in a descriptor. The annotation removes
 * boilerplate around service identity and method metadata. It does not by
 * itself create a transport listener or bypass ObjectRef security; export
 * remains controlled by the J7M runtime.</p>
 *
 * <p>The example is intentionally narrow. It teaches constructor injection
 * with {@link Inject}, qualifier selection with {@link Named}, safe
 * configuration with {@link ConfigValue}, public capability injection with
 * {@link J7mResource}, and one {@link RemoteMethod}. Lifecycle, retry,
 * backpressure, high-availability, authorization, and observability
 * annotations are taught by later focused examples.</p>
 */
@J7mService(name = "//apps/example/annotation/simple/GreetingService", exported = true)
public final class SimpleGreetingService {
    // J7M teaching: constructor injection keeps the copyable service small and
    // makes every required public capability visible at activation time.
    private final SimpleFormatter formatter;
    private final String region;
    private final GreetingAudit audit;

    /**
     * Creates the service with a named formatter and redaction-safe
     * configuration value.
     *
     * <p>{@link Named} selects the formatter binding and
     * {@link ConfigValue} reads a public configuration key with a default.
     * Real services should copy this constructor-injection shape rather than
     * pulling dependencies from global state.</p>
     *
     * @param formatter named formatter component
     * @param region configured public region label
     * @param audit application-scoped public audit helper
     */
    @Inject
    public SimpleGreetingService(
            @Named("formal") final SimpleFormatter formatter,
            @ConfigValue(value = "annotation.region", defaultValue = "local") final String region,
            @J7mResource("greeting-audit") final GreetingAudit audit) {
        this.formatter = formatter;
        this.region = region;
        this.audit = audit;
    }

    /**
     * Produces the greeting shown by the example and demo.
     *
     * <p>{@link RemoteMethod} marks the method as part of the service contract.
     * The method itself contains the business logic a developer should copy
     * into an application service. Retry, deadline, idempotency, authorization,
     * and one-way dispatch are intentionally absent here so the first example
     * remains a focused starting point.</p>
     *
     * @param name caller-supplied display name
     * @return greeting containing only public region and caller text
     */
    @RemoteMethod
    public String greet(final String name) {
        // J7M teaching: the business method stays small because service
        // identity and dependency wiring were declared with annotations.
        this.audit.record("greet");
        return this.formatter.format(this.region, name);
    }

    /**
     * Named formatter component used to demonstrate qualified injection.
     *
     * <p>{@link J7mComponent} and {@link Named} make this helper available to
     * the injector without exposing it as a remote service. The component is
     * immutable and safe for concurrent example invocations.</p>
     */
    @J7mComponent(name = "formal")
    @Named("formal")
    public static final class SimpleFormatter {
        /**
         * Formats a public greeting value.
         *
         * @param region configured public region
         * @param name caller-supplied name
         * @return formatted greeting with no sensitive data
         */
        public String format(final String region, final String name) {
            return region + ":hello " + name;
        }
    }

    /**
     * Small public capability used to demonstrate {@link J7mResource}
     * injection in a copyable service.
     *
     * <p>The capability records only public event names. It is deliberately not
     * a runtime audit subsystem and does not receive credentials, transport
     * handles, or unredacted payloads.</p>
     */
    public interface GreetingAudit {
        /**
         * Records a public event name.
         *
         * @param eventName public event name
         */
        void record(String eventName);
    }
}
