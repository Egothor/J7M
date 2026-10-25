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

import java.util.Objects;
import java.util.Optional;
import org.j7m.middleware.annotations.J7mComponent;
import org.j7m.middleware.annotations.Produces;
import org.j7m.middleware.annotations.Scope;
import org.j7m.middleware.annotations.ServiceRef;
import org.j7m.middleware.application.api.ManagedProvider;

/**
 * Ordinary application objects consumed by the optional managed annotation processor/container.
 * This API-only reference declares metadata; it does not implement a container, discovery or codec.
 * A host supplies configuration and the explicitly registered optional service-binding policy.
 */
@J7mComponent
@Scope(Scope.Kind.ACTIVATION)
public final class ManagedAnnotationUsage {
    private final ManagedUsageSettings settings;
    private final RemoteConsumer remote;
    private final Formatter formatter;

    /**
     * Receives a validated configuration snapshot and an exactly qualified factory product.
     * @param settings immutable application configuration
     * @param formatter product selected by the formal style qualifier
     * @param remote product backed by the host optional service policy
     * @throws NullPointerException if a dependency is absent
     */
    public ManagedAnnotationUsage(final ManagedUsageSettings settings, @ManagedUsageStyle("formal") final Formatter formatter, final RemoteConsumer remote) {
        // J7M teaching: constructor wiring keeps application objects independent of container implementation classes.
        this.settings = Objects.requireNonNull(settings);
        this.formatter = Objects.requireNonNull(formatter);
        this.remote = Objects.requireNonNull(remote);
    }

    /**
     * Formats a greeting using injected configuration and the qualified product.
     * @param name caller-supplied display text
     * @return greeting containing the configured region
     */
    public String greet(final String name) { return this.formatter.format(this.settings.region(), name); }

    /**
     * Returns the typed configuration value captured for this activation.
     * @return configured positive attempt limit
     */
    public int attempts() { return this.settings.attempts(); }

    /**
     * Produces a new immutable product for each prototype resolution without a factory receiver.
     * @return formal formatter owned by the resolving managed scope
     */
    @Produces
    @ManagedUsageStyle("formal")
    @Scope(Scope.Kind.PROTOTYPE)
    public static Formatter formatter() { return new Formatter("formal"); }

    /**
     * Produces a consumer whose provider is prepared by the host's explicit optional binding policy.
     * The static factory avoids a dependency cycle through its declaring component.
     * @param remote host-authorized optional service provider
     * @return activation-owned consumer product
     * @throws NullPointerException if the provider is absent
     */
    @Produces
    public static RemoteConsumer consumer(@ServiceRef(name = "//annotation/remote", contractId = "examples.annotation.remote",
            policy = "optional") final ManagedProvider<Optional<RemoteApi>> remote) {
        return new RemoteConsumer(remote);
    }

    /**
     * Uses an optional remote product without injecting null when discovery has no target.
     * @param name display text
     * @return remote greeting or explicit fallback
     */
    public String greetOrFallback(final String name) { return this.remote.greetOrFallback(name); }

    /**
     * Factory product with no middleware-specific interface.
     * @param style exact formatting category
     */
    public record Formatter(String style) {
        /**
         * Formats ordinary application text.
         * @param region application region
         * @param name display text
         * @return deterministic greeting
         */
        public String format(final String region, final String name) { return this.style + ":" + region + ":hello " + name; }
    }

    /**
     * Ordinary remote business contract; no specialized binding interface is required.
     */
    public interface RemoteApi {
        /**
         * Requests a greeting from an authorized remote service.
         * @param name display text
         * @return remote greeting
         */
        String greet(String name);
    }

    /**
     * Demonstrates explicit optional availability rather than a null injected service.
     */
    public static final class RemoteConsumer {
        private final ManagedProvider<Optional<RemoteApi>> remote;

        /**
         * Receives a host-authorized optional binding; the annotation installs no policy itself.
         * @param remote typed optional service provider using the host's optional policy
         * @throws NullPointerException if the provider is absent
         */
        public RemoteConsumer(final ManagedProvider<Optional<RemoteApi>> remote) {
            this.remote = Objects.requireNonNull(remote);
        }

        /**
         * Calls an available service or reports explicit absence.
         * @param name display text
         * @return remote greeting, or an application-owned fallback
         */
        public String greetOrFallback(final String name) {
            return this.remote.get().map(service -> service.greet(name)).orElse("unavailable:" + name);
        }
    }
}
