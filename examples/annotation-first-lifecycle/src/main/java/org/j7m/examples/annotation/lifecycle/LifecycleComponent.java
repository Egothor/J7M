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

package org.j7m.examples.annotation.lifecycle;

import java.util.ArrayList;
import java.util.List;
import org.j7m.middleware.annotations.Drain;
import org.j7m.middleware.annotations.HealthCheck;
import org.j7m.middleware.annotations.Initialize;
import org.j7m.middleware.annotations.Inject;
import org.j7m.middleware.annotations.J7mComponent;
import org.j7m.middleware.annotations.LocalOnly;
import org.j7m.middleware.annotations.NoRemoteExport;
import org.j7m.middleware.annotations.ReadinessCheck;
import org.j7m.middleware.annotations.Shutdown;
import org.j7m.middleware.annotations.Startup;
import org.j7m.middleware.annotations.SwitchLevel;

/**
 * Lifecycle and health component that demonstrates deterministic hook order.
 *
 * <p>{@link J7mComponent} marks the type as an injectable local component.
 * {@link LocalOnly} and {@link NoRemoteExport} document that the component is
 * not intended for remote ObjectRef export. The smoke owner invokes
 * {@link Initialize}, {@link Startup}, {@link Drain}, and {@link Shutdown} in
 * the documented lifecycle order through the J7M injector; the injector orders
 * multiple methods for the same annotation deterministically by method name.
 * {@link HealthCheck} and {@link ReadinessCheck} are safe, public status
 * methods.</p>
 *
 * <p>{@link SwitchLevel} is reserved documentation in this current example.
 * It names the intended lifecycle family for a future conceptual level-switch
 * controller, but it has no current runtime consumer. The method annotated with
 * it is not invoked by the example lifecycle flow, and no behavior should be
 * inferred from the annotation by itself.</p>
 */
@J7mComponent(name = "lifecycle")
@LocalOnly
@NoRemoteExport
public final class LifecycleComponent {
    // J7M teaching: local lifecycle state makes the hook order visible without
    // starting external work or exporting a remote ObjectRef.
    private final List<String> events;

    /**
     * Creates the component with local in-memory lifecycle state.
     *
     * <p>The constructor is annotated to prove that J7M-native constructor
     * injection works even when no external service capability is required.</p>
     */
    @Inject
    public LifecycleComponent() {
        this.events = new ArrayList<>();
    }

    /**
     * Records initialization as the first lifecycle phase.
     *
     * <p>Real services should validate local configuration here, but should not
     * start accepting requests until startup has completed.</p>
     */
    @Initialize
    public void initialize() {
        // J7M teaching: initialize is first so configuration and local state
        // can be checked before the component advertises readiness.
        this.events.add("initialize");
    }

    /**
     * Records startup after initialization.
     *
     * <p>This is the phase where a real component may start local workers or
     * become ready for invocation, subject to normal resource limits.</p>
     */
    @Startup
    public void startup() {
        // J7M teaching: startup follows initialize and is the point where real
        // components may begin accepting work.
        this.events.add("startup");
    }

    /**
     * Returns a safe health value.
     *
     * @return public health status
     */
    @HealthCheck
    public String health() {
        return "healthy";
    }

    /**
     * Reports readiness after startup has been observed.
     *
     * @return {@code true} once the startup hook ran
     */
    @ReadinessCheck
    public boolean readiness() {
        return this.events.contains("startup");
    }

    /**
     * Demonstrates reserved lifecycle-level documentation.
     *
     * <p>The annotation model can discover {@link SwitchLevel}, but this
     * example does not implement or invoke a level-switch controller. The
     * method records safe state only if called directly by a test, which keeps
     * discovery separate from runtime behavior.</p>
     */
    @SwitchLevel
    public void switchLevelMetadataOnly() {
        this.events.add("switch-level-metadata");
    }

    /**
     * Records the drain phase before shutdown.
     *
     * <p>A real component would stop accepting new work and let in-flight work
     * complete here. This example records order only.</p>
     */
    @Drain
    public void drain() {
        // J7M teaching: drain precedes shutdown so a real service can reject
        // new work while letting safe in-flight work finish.
        this.events.add("drain");
    }

    /**
     * Records final shutdown.
     *
     * <p>Shutdown should release local resources. The example owns no external
     * handles, so no cleanup side effect is required.</p>
     */
    @Shutdown
    public void shutdown() {
        this.events.add("shutdown");
    }

    /**
     * Returns the immutable lifecycle event sequence.
     *
     * @return ordered lifecycle events
     */
    public List<String> events() {
        return List.copyOf(this.events);
    }
}
