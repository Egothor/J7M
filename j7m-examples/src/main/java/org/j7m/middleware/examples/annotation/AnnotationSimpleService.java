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

import java.util.ArrayList;
import java.util.List;
import org.j7m.middleware.annotations.Backpressure;
import org.j7m.middleware.annotations.ConfigValue;
import org.j7m.middleware.annotations.Deadline;
import org.j7m.middleware.annotations.Drain;
import org.j7m.middleware.annotations.HealthCheck;
import org.j7m.middleware.annotations.HighAvailability;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.Initialize;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mService;
import org.j7m.middleware.annotations.Named;
import org.j7m.middleware.annotations.PreferLocal;
import org.j7m.middleware.annotations.ReadinessCheck;
import org.j7m.middleware.annotations.RemoteMethod;
import org.j7m.middleware.annotations.RemoteService;
import org.j7m.middleware.annotations.Retry;
import org.j7m.middleware.annotations.Shutdown;
import org.j7m.middleware.annotations.Startup;

/**
 * Minimal annotation-first service with constructor injection and lifecycle hooks.
 *
 * <p>The service demonstrates descriptor-backed export metadata with
 * {@link J7mService}, descriptor metadata with {@link RemoteMethod},
 * retry-safe descriptor metadata with {@link Idempotent}, and deterministic
 * lifecycle callbacks. The annotations are public J7M metadata; low-level
 * export APIs remain available for advanced callers.</p>
 */
@RemoteService
@J7mService(name = "//apps/example/annotation/simple/GreetingService", exported = true, transport = "default")
@HighAvailability
@PreferLocal
public final class AnnotationSimpleService {
        // J7M teaching: this block is copyable application code that relies on descriptors and J7M-native injection instead of runtime internals.
    private final AnnotationGreetingFormatter formatter;
    private final AnnotationExampleAudit audit;
    private final String region;
    private final List<String> lifecycleEvents;

    /**
     * Creates the service from public application capabilities.
     *
     * @param formatter named formatter capability
     * @param audit safe audit collector
     * @param region configured region
     */
    @Inject
    public AnnotationSimpleService(
            @Named("formal") final AnnotationGreetingFormatter formatter,
            final AnnotationExampleAudit audit,
            @ConfigValue(value = "annotation.region", defaultValue = "local") final String region) {
        this.formatter = formatter;
        this.audit = audit;
        this.region = region;
        this.lifecycleEvents = new ArrayList<>();
    }

    /**
     * Initializes local state before startup.
     */
    @Initialize
    public void initialize() {
        this.lifecycleEvents.add("initialize");
        this.audit.record("simple.initialize");
    }

    /**
     * Starts the service after injection completes.
     */
    @Startup
    public void start() {
        this.lifecycleEvents.add("startup");
        this.audit.record("simple.startup");
    }

    /**
     * Greets a caller using injected configuration.
     *
     * <p>The remote, idempotent, deadline, retry, and backpressure annotations
     * are descriptor metadata in this example. Runtime dispatch policy remains
     * responsible for enforcing retries, deadlines, and admission control.</p>
     *
     * @param name caller name
     * @return greeting
     */
    @RemoteMethod
    @Idempotent
    @Deadline(millis = 250)
    @Retry(maxAttempts = 1)
    @Backpressure(maxConcurrent = 8)
    public String greet(final String name) {
        // J7M teaching: compiled example support mirrors the standalone
        // annotation example, keeping business code separate from descriptor
        // metadata and injection boilerplate.
        this.audit.record("simple.greet:idempotent=true");
        return this.formatter.format(this.region, name);
    }

    /**
     * Reports component health.
     *
     * @return safe health text
     */
    @HealthCheck
    public String health() {
        return "healthy";
    }

    /**
     * Reports readiness.
     *
     * @return {@code true} when startup ran
     */
    @ReadinessCheck
    public boolean ready() {
        return this.lifecycleEvents.contains("startup");
    }

    /**
     * Drains the service before shutdown.
     */
    @Drain
    public void drain() {
        this.lifecycleEvents.add("drain");
        this.audit.record("simple.drain");
    }

    /**
     * Shuts the service down.
     */
    @Shutdown
    public void shutdown() {
        this.lifecycleEvents.add("shutdown");
        this.audit.record("simple.shutdown");
    }

    /**
     * Returns the deterministic lifecycle event order.
     *
     * @return lifecycle events
     */
    public List<String> lifecycleEvents() {
        return List.copyOf(this.lifecycleEvents);
    }
}
