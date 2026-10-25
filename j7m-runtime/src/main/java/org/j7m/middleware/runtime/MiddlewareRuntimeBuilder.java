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

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.operations.api.NuggetAdminService;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.codec.api.ValueCodec;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.core.RuntimeConfiguration;
import org.j7m.middleware.registry.api.RegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.security.defaults.SecurityDefaults;
import org.j7m.middleware.transport.api.TransportProvider;

/**
 * Builder for Phase 1 runtime instances.
 *
 * <p>The builder is not thread-safe. The runtime returned by {@link #build()}
 * owns the supplied registry and transport providers and closes them during
 * runtime shutdown.</p>
 */
public final class MiddlewareRuntimeBuilder {
    private static final int MAX_SERVICE_LOADER_TRANSPORTS = 16;

    private RuntimeIdentity identity;
    private RegistryClient registry;
    private ValueCodec codec;
    private Clock clock;
    private SecurityConfiguration security;
    private final List<TransportProvider> transports;
    private NuggetAdminRuntimeConfiguration nuggetAdminConfiguration;
    private boolean nuggetAdminDisabled;

    /**
     * Creates a builder with default codec, registry, and clock.
     */
    public MiddlewareRuntimeBuilder() {
        this.identity = new RuntimeIdentity("runtime-" + UUID.randomUUID(), "default", Map.of());
        this.registry = new EmbeddedRegistryClient();
        this.codec = new DefaultValueCodec();
        this.clock = Clock.systemUTC();
        this.security = SecurityDefaults.development();
        this.transports = new ArrayList<>();
        this.nuggetAdminDisabled = false;
    }

    /**
     * Sets the runtime id.
     *
     * @param runtimeId runtime identifier
     * @return this builder
     */
    public MiddlewareRuntimeBuilder runtimeId(final String runtimeId) {
        this.identity = new RuntimeIdentity(runtimeId, this.identity.registryContext(), this.identity.metadata());
        return this;
    }

    /**
     * Sets the registry context.
     *
     * @param registryContext registry context
     * @return this builder
     */
    public MiddlewareRuntimeBuilder registryContext(final String registryContext) {
        this.identity = new RuntimeIdentity(this.identity.runtimeId(), registryContext, this.identity.metadata());
        return this;
    }

    /**
     * Sets the registry client.
     *
     * @param registry registry client
     * @return this builder
     */
    public MiddlewareRuntimeBuilder registry(final RegistryClient registry) {
        this.registry = registry;
        return this;
    }

    /**
     * Sets the value codec.
     *
     * @param codec value codec
     * @return this builder
     */
    public MiddlewareRuntimeBuilder codec(final ValueCodec codec) {
        this.codec = codec;
        return this;
    }

    /**
     * Sets the runtime clock.
     *
     * @param clock clock
     * @return this builder
     */
    public MiddlewareRuntimeBuilder clock(final Clock clock) {
        this.clock = clock;
        return this;
    }

    /**
     * Adds a transport provider.
     *
     * @param provider provider owned by the runtime
     * @return this builder
     */
    public MiddlewareRuntimeBuilder addTransport(final TransportProvider provider) {
        this.transports.add(provider);
        return this;
    }

    /**
     * Sets runtime security configuration.
     *
     * @param security security configuration
     * @return this builder
     */
    public MiddlewareRuntimeBuilder security(final SecurityConfiguration security) {
        this.security = security;
        return this;
    }

    /**
     * Configures the runtime-owned nugget administration service.
     *
     * <p>Supplying this configuration keeps nugget administration enabled and
     * makes the returned runtime own the started service. The runtime closes the
     * service during shutdown before closing transports and the registry. The
     * configuration must include a valid PIN source unless it explicitly
     * disables admin or explicitly enables unsafe developer mode.</p>
     *
     * @param configuration nugget administration configuration
     * @return this builder
     * @throws IllegalArgumentException when {@code configuration} is
     *         {@code null}
     */
    public MiddlewareRuntimeBuilder nuggetAdmin(final NuggetAdminRuntimeConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("nugget admin configuration must not be null");
        }
        this.nuggetAdminConfiguration = configuration;
        this.nuggetAdminDisabled = false;
        return this;
    }

    /**
     * Explicitly disables runtime-owned nugget administration.
     *
     * <p>This method is intended for tests, demos, and embedders that do not
     * expose the Phase 7 operations plane. Production runtime construction is
     * otherwise fail-closed: nugget administration is enabled by default and a
     * missing PIN source rejects startup.</p>
     *
     * @return this builder
     */
    public MiddlewareRuntimeBuilder disableNuggetAdmin() {
        this.nuggetAdminConfiguration = null;
        this.nuggetAdminDisabled = true;
        return this;
    }

    /**
     * Builds and starts the runtime.
     *
     * <p>When no transport providers were explicitly supplied through
     * {@link #addTransport(TransportProvider)}, the builder loads
     * {@link TransportProvider} implementations through {@link ServiceLoader}.
     * Provider loading is bounded, sorted by provider class name, and fails
     * fast when no provider is available. Explicitly supplied providers always
     * take precedence and do not trigger service loading.</p>
     *
     * @return runtime
     */
    public DefaultMiddlewareRuntime build() {
        List<TransportProvider> resolvedTransports = this.transports.isEmpty()
                ? this.loadServiceProviders()
                : List.copyOf(this.transports);
        RuntimeConfiguration runtimeConfiguration =
                new RuntimeConfiguration(this.identity, this.registry, this.codec, resolvedTransports, this.clock, this.security);
        if (this.nuggetAdminDisabled) {
            return new DefaultMiddlewareRuntime(runtimeConfiguration);
        }
        OwnedRuntimeResources ownedResources = new OwnedRuntimeResources();
        DefaultMiddlewareRuntime runtime = new DefaultMiddlewareRuntime(runtimeConfiguration, List.of(ownedResources));
        try {
            NuggetAdminRuntimeConfiguration configuration = this.nuggetAdminConfiguration == null
                    ? this.defaultNuggetAdminConfiguration()
                    : this.nuggetAdminConfiguration;
            NuggetAdminService service = NuggetAdminRuntime.start(this.identity, configuration, runtime::close);
            ownedResources.add(service);
            return runtime;
        } catch (RuntimeException exception) {
            runtime.close();
            throw exception;
        }
    }

    private NuggetAdminRuntimeConfiguration defaultNuggetAdminConfiguration() {
        return NuggetAdminRuntimeConfiguration.builder()
                .security(this.security)
                .clock(this.clock)
                .nuggetName(this.identity.runtimeId())
                .build();
    }

    private List<TransportProvider> loadServiceProviders() {
        List<TransportProvider> providers = new ArrayList<>();
        ServiceLoader<TransportProvider> loader = ServiceLoader.load(TransportProvider.class);
        for (TransportProvider provider : loader) {
            providers.add(provider);
            if (providers.size() > MAX_SERVICE_LOADER_TRANSPORTS) {
                throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.OVERLOADED,
                        "transport provider service loading exceeded configured limit", Map.of(), null, this.identity.runtimeId(), false, true));
            }
        }
        if (providers.isEmpty()) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.TRANSPORT_ERROR,
                    "no transport providers configured or discoverable", Map.of(), null, this.identity.runtimeId(), false, false));
        }
        providers.sort(Comparator.comparing(provider -> provider.getClass().getName()));
        return List.copyOf(providers);
    }

    private static final class OwnedRuntimeResources implements AutoCloseable {
        private final List<AutoCloseable> resources;
        private final AtomicBoolean closed;

        private OwnedRuntimeResources() {
            this.resources = new ArrayList<>();
            this.closed = new AtomicBoolean();
        }

        private synchronized void add(final AutoCloseable resource) {
            if (resource == null) {
                return;
            }
            if (this.closed.get()) {
                closeLateResource(resource);
                throw new IllegalStateException("runtime is already closed");
            }
            this.resources.add(resource);
        }

        @Override
        public synchronized void close() throws Exception {
            if (!this.closed.compareAndSet(false, true)) {
                return;
            }
            Exception primary = null;
            for (int index = this.resources.size() - 1; index >= 0; index--) {
                try {
                    this.resources.get(index).close();
                } catch (Exception exception) {
                    if (primary == null) {
                        primary = exception;
                    } else {
                        primary.addSuppressed(exception);
                    }
                }
            }
            this.resources.clear();
            if (primary != null) {
                throw primary;
            }
        }

        private static void closeLateResource(final AutoCloseable resource) {
            try {
                resource.close();
            } catch (Exception exception) {
                IllegalStateException failure = new IllegalStateException("late runtime resource close failed", exception);
                failure.addSuppressed(exception);
                throw failure;
            }
        }
    }
}
