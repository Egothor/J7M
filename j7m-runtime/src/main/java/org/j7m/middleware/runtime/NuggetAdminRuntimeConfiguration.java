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

package org.j7m.middleware.runtime;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.j7m.middleware.deployer.api.ApplicationDeployer;
import org.j7m.middleware.operations.api.NuggetDiscovery;
import org.j7m.middleware.operations.api.NuggetIntrospection;
import org.j7m.middleware.operations.api.NuggetPinSecretProvider;
import org.j7m.middleware.operations.runtime.NuggetAdminConfiguration;
import org.j7m.middleware.security.api.SecurityConfiguration;

/**
 * Supported runtime assembly configuration for headless nugget PIN admission,
 * read-only introspection, and local/configured discovery.
 *
 * <p>The configuration is immutable after {@link Builder#build()}. It contains
 * source descriptors and protected providers only; public status and
 * {@link #toString()} never expose clear PINs, verifier records, source values,
 * salts, derived verifier bytes, or throttle state.</p>
 */
public final class NuggetAdminRuntimeConfiguration {
    private final NuggetAdminConfiguration internal;

    private NuggetAdminRuntimeConfiguration(final NuggetAdminConfiguration internal) {
        this.internal = internal;
    }

    /**
     * Creates a builder using development security defaults.
     *
     * @return configuration builder
     */
    public static Builder builder() {
        return new Builder();
    }

    NuggetAdminConfiguration toInternalConfiguration() {
        return this.internal;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return this.internal.toString();
    }

    /**
     * Mutable builder for {@link NuggetAdminRuntimeConfiguration}.
     */
    public static final class Builder {
        private final NuggetAdminConfiguration.Builder internal;

        private Builder() {
            this.internal = NuggetAdminConfiguration.builder();
        }

        /**
         * Sets whether nugget administration is enabled.
         *
         * @param value enabled flag
         * @return this builder
         */
        public Builder enabled(final boolean value) {
            this.internal.enabled(value);
            return this;
        }

        /**
         * Sets the human-readable nugget name.
         *
         * @param value nugget name
         * @return this builder
         */
        public Builder nuggetName(final String value) {
            this.internal.nuggetName(value);
            return this;
        }

        /**
         * Enables or disables explicit unsafe developer mode.
         *
         * @param value unsafe developer flag
         * @return this builder
         */
        public Builder unsafeDeveloperMode(final boolean value) {
            this.internal.unsafeDeveloperMode(value);
            return this;
        }

        /**
         * Sets the existing runtime security configuration.
         *
         * @param value security configuration
         * @return this builder
         */
        public Builder security(final SecurityConfiguration value) {
            this.internal.security(value);
            return this;
        }

        /**
         * Sets the wall clock for deterministic tests and audit timestamps.
         *
         * @param value wall clock
         * @return this builder
         */
        public Builder clock(final Clock value) {
            this.internal.clock(value);
            return this;
        }

        /**
         * Sets the monotonic time source for throttle calculations.
         *
         * @param value monotonic nanosecond source
         * @return this builder
         */
        public Builder monotonicTimeSource(final LongSupplier value) {
            this.internal.monotonicTimeSource(value);
            return this;
        }

        /**
         * Sets the secure random source for salts and explicit development PINs.
         *
         * @param value secure random source
         * @return this builder
         */
        public Builder secureRandom(final SecureRandom value) {
            this.internal.secureRandom(value);
            return this;
        }

        /**
         * Sets the PBKDF2 iteration count.
         *
         * @param value iteration count within the Phase 7.1 verifier policy
         * @return this builder
         */
        public Builder iterations(final int value) {
            this.internal.iterations(value);
            return this;
        }

        /**
         * Sets the maximum accepted UTF-8 encoded PIN length.
         *
         * @param value maximum byte length
         * @return this builder
         */
        public Builder maxPinBytes(final int value) {
            this.internal.maxPinBytes(value);
            return this;
        }

        /**
         * Sets throttle policy limits.
         *
         * @param maxBuckets maximum retained throttle buckets
         * @param window failure window
         * @param maxDelay maximum retry delay
         * @return this builder
         */
        public Builder throttlePolicy(final int maxBuckets, final Duration window, final Duration maxDelay) {
            this.internal.throttlePolicy(maxBuckets, window, maxDelay);
            return this;
        }

        /**
         * Adds a protected programmatic PIN source.
         *
         * @param description safe source description
         * @param provider protected provider
         * @return this builder
         */
        public Builder pinFromProgrammaticProvider(final String description, final NuggetPinSecretProvider provider) {
            this.internal.pinFromProgrammaticProvider(description, provider);
            return this;
        }

        /**
         * Adds a configured environment variable PIN source.
         *
         * @param variableName environment variable name
         * @return this builder
         */
        public Builder pinFromEnvironment(final String variableName) {
            this.internal.pinFromEnvironment(variableName);
            return this;
        }

        /**
         * Adds a configured environment variable PIN source with injectable lookup.
         *
         * @param variableName environment variable name
         * @param lookup environment lookup
         * @return this builder
         */
        public Builder pinFromEnvironment(final String variableName, final Function<String, String> lookup) {
            this.internal.pinFromEnvironment(variableName, lookup);
            return this;
        }

        /**
         * Adds a protected PIN file source.
         *
         * @param path configured file path
         * @return this builder
         */
        public Builder pinFromProtectedFile(final Path path) {
            this.internal.pinFromProtectedFile(path);
            return this;
        }

        /**
         * Adds a lower-safety direct process argument PIN source.
         *
         * @param pin PIN characters copied into the configuration source
         * @return this builder
         */
        public Builder pinFromDirectProcessArgument(final char[] pin) {
            this.internal.pinFromDirectProcessArgument(pin);
            return this;
        }

        /**
         * Adds an explicit interactive bootstrap source supplied by the embedding runtime.
         *
         * @param provider terminal-safe prompt provider
         * @return this builder
         */
        public Builder pinFromInteractiveBootstrap(final NuggetPinSecretProvider provider) {
            this.internal.pinFromInteractiveBootstrap(provider);
            return this;
        }

        /**
         * Adds a protected verifier file source.
         *
         * @param path verifier record path
         * @return this builder
         */
        public Builder pinFromProtectedVerifierFile(final Path path) {
            this.internal.pinFromProtectedVerifierFile(path);
            return this;
        }

        /**
         * Requests an explicit generated development PIN in unsafe developer mode.
         *
         * @param receiver local developer channel receiving a copied PIN
         * @return this builder
         */
        public Builder generatedDevelopmentPin(final Consumer<char[]> receiver) {
            this.internal.generatedDevelopmentPin(receiver);
            return this;
        }

        /**
         * Adds one trusted bounded provider for read-only introspection.
         *
         * <p>The provider is invoked by the nugget admin runtime through an
         * internally bounded executor with provider deadlines. Returned values
         * are still projected, authorized, redacted, filtered, sorted, and paged
         * by the runtime before public DTO construction.</p>
         *
         * @param value typed read-only provider
         * @return this builder
         */
        public Builder introspectionProvider(final NuggetIntrospection.Provider value) {
            this.internal.introspectionProvider(value);
            return this;
        }

        /**
         * Sets the public deployer authority used by nugget application
         * lifecycle operations.
         *
         * <p>The runtime depends only on {@link ApplicationDeployer}; it does
         * not depend on the application-platform implementation module. A
         * missing deployer returns truthful unavailable lifecycle results.</p>
         *
         * @param value deployer authority, or {@code null}
         * @return this builder
         */
        public Builder applicationDeployer(final ApplicationDeployer value) {
            this.internal.applicationDeployer(value);
            return this;
        }

        /**
         * Enables or disables read-only administration mode.
         *
         * @param value read-only flag
         * @return this builder
         */
        public Builder readOnly(final boolean value) {
            this.internal.readOnly(value);
            return this;
        }

        /**
         * Enables or disables repository-coordinate start operations.
         *
         * @param value start-enabled flag
         * @return this builder
         */
        public Builder startApplicationEnabled(final boolean value) {
            this.internal.startApplicationEnabled(value);
            return this;
        }

        /**
         * Enables or disables explicitly gated nugget self-termination.
         *
         * <p>The default is disabled. Enabling this flag does not install a
         * termination primitive and does not bypass PIN admission,
         * authorization, exact target validation, confirmation, deadlines, or
         * audit.</p>
         *
         * @param value kill-enabled flag
         * @return this builder
         */
        public Builder killEnabled(final boolean value) {
            this.internal.killEnabled(value);
            return this;
        }

        /**
         * Sets bounded execution policy for nugget-level control operations.
         *
         * @param concurrency maximum concurrent control calls, currently only
         *        {@code 1}
         * @param queueCapacity maximum queued control calls, currently
         *        {@code 0} through {@code 4}
         * @param timeout positive control deadline, at most 30 seconds
         * @param shutdownWait positive control-executor shutdown wait, at most
         *        10 seconds
         * @return this builder
         * @throws IllegalArgumentException when a value is outside policy
         */
        public Builder controlExecutionPolicy(
                final int concurrency,
                final int queueCapacity,
                final Duration timeout,
                final Duration shutdownWait) {
            this.internal.controlExecutionPolicy(concurrency, queueCapacity, timeout, shutdownWait);
            return this;
        }

        /**
         * Sets bounded confirmation challenge retention for nugget controls.
         *
         * @param ttl positive confirmation time-to-live, at most five minutes
         * @param maxRetained maximum retained live confirmation challenges
         * @return this builder
         * @throws IllegalArgumentException when a value is outside policy
         */
        public Builder confirmationPolicy(final Duration ttl, final int maxRetained) {
            this.internal.confirmationPolicy(ttl, maxRetained);
            return this;
        }

        /**
         * Sets the authoritative runtime close action used for graceful nugget
         * shutdown.
         *
         * <p>Embedders normally receive this automatically from
         * {@link MiddlewareRuntimeBuilder}. The callback must invoke the owning
         * runtime close path and must not call nugget-admin close directly.</p>
         *
         * @param value runtime close action, or {@code null}
         * @return this builder
         */
        public Builder runtimeCloseAction(final Runnable value) {
            this.internal.runtimeCloseAction(value);
            return this;
        }

        /**
         * Sets an injected self-termination capability for explicitly enabled
         * forced nugget termination.
         *
         * <p>No default capability is installed. Tests may supply a fake
         * callback. Production embedders must not use this method for arbitrary
         * process control, process-id selection, shell execution, or direct
         * unbounded JVM termination.</p>
         *
         * @param value injected self-termination callback, or {@code null}
         * @return this builder
         */
        public Builder terminationCapability(final Runnable value) {
            this.internal.terminationCapability(value);
            return this;
        }

        /**
         * Sets bounded provider execution policy for read-only introspection.
         *
         * @param concurrency maximum concurrent provider calls, currently 1 or 2
         * @param timeout positive per-provider deadline, at most 30 seconds
         * @return this builder
         * @throws IllegalArgumentException when a value is outside the runtime policy
         */
        public Builder providerExecutionPolicy(final int concurrency, final Duration timeout) {
            this.internal.providerExecutionPolicy(concurrency, timeout);
            return this;
        }

        /**
         * Sets bounded provider execution policy for read-only introspection.
         *
         * @param concurrency maximum concurrent provider calls, currently 1 or 2
         * @param queueCapacity maximum queued provider calls, currently 0 through 8
         * @param timeout positive per-provider deadline, at most 30 seconds
         * @param shutdownWait positive maximum shutdown wait, at most 10 seconds
         * @return this builder
         * @throws IllegalArgumentException when a value is outside the runtime policy
         */
        public Builder providerExecutionPolicy(
                final int concurrency,
                final int queueCapacity,
                final Duration timeout,
                final Duration shutdownWait) {
            this.internal.providerExecutionPolicy(concurrency, queueCapacity, timeout, shutdownWait);
            return this;
        }

        /**
         * Adds a configured storage root for disk introspection.
         *
         * @param role storage role
         * @param path root path
         * @param sensitive whether path components require redaction
         * @return this builder
         */
        public Builder storageRoot(final String role, final Path path, final boolean sensitive) {
            this.internal.storageRoot(role, path, sensitive);
            return this;
        }

        /**
         * Enables or disables protected local nugget discovery publication.
         *
         * <p>The directory is validated by the runtime before publication. It is
         * not exposed through public discovery records.</p>
         *
         * @param directory local J7M-owned discovery directory
         * @param enabled enabled flag
         * @return this builder
         */
        public Builder localDiscovery(final Path directory, final boolean enabled) {
            this.internal.localDiscovery(directory, enabled);
            return this;
        }

        /**
         * Sets local discovery lease and heartbeat timing.
         *
         * @param ttl record time-to-live
         * @param heartbeat heartbeat refresh interval
         * @return this builder
         */
        public Builder discoveryTiming(final Duration ttl, final Duration heartbeat) {
            this.internal.discoveryTiming(ttl, heartbeat);
            return this;
        }

        /**
         * Sets the maximum local discovery records scanned per discovery call.
         *
         * @param value record limit
         * @return this builder
         */
        public Builder maxDiscoveryRecords(final int value) {
            this.internal.maxDiscoveryRecords(value);
            return this;
        }

        /**
         * Adds an explicit configured discovery target.
         *
         * @param target configured target
         * @return this builder
         */
        public Builder discoveryTarget(final NuggetDiscovery.ConfiguredTarget target) {
            this.internal.discoveryTarget(target);
            return this;
        }

        /**
         * Builds an immutable runtime configuration.
         *
         * @return runtime configuration
         */
        public NuggetAdminRuntimeConfiguration build() {
            return new NuggetAdminRuntimeConfiguration(this.internal.build());
        }
    }
}
