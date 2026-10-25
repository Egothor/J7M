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

package org.j7m.middleware.operations.runtime;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.j7m.middleware.deployer.api.ApplicationDeployer;
import org.j7m.middleware.operations.api.NuggetDiscovery;
import org.j7m.middleware.operations.api.NuggetPinSecretProvider;
import org.j7m.middleware.operations.api.NuggetPinSourceType;
import org.j7m.middleware.operations.api.NuggetIntrospection;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.security.defaults.SecurityDefaults;

/**
 * Runtime configuration for the headless nugget PIN admission, introspection,
 * and discovery service.
 *
 * <p>The configuration is immutable after {@link Builder#build()}. It stores
 * source descriptors and protected suppliers only; {@link #toString()} and safe
 * status summaries never expose clear PINs, verifier records, source values, or
 * derived credential material.</p>
 */
public final class NuggetAdminConfiguration {
    static final int DEFAULT_ITERATIONS = 210_000;
    static final int MAX_ITERATIONS = 1_000_000;
    static final int DEFAULT_MAX_PIN_BYTES = 256;
    static final int DEFAULT_MAX_THROTTLE_BUCKETS = 1_024;
    static final int MAX_THROTTLE_BUCKETS = 4_096;
    static final int DEFAULT_PROVIDER_CONCURRENCY = 2;
    static final int DEFAULT_PROVIDER_QUEUE_CAPACITY = 8;
    static final Duration MAX_PROVIDER_TIMEOUT = Duration.ofSeconds(30L);
    static final Duration MAX_PROVIDER_SHUTDOWN_WAIT = Duration.ofSeconds(10L);
    static final Duration DEFAULT_THROTTLE_WINDOW = Duration.ofMinutes(15L);
    static final Duration DEFAULT_MAX_THROTTLE_DELAY = Duration.ofSeconds(60L);
    static final Duration MAX_THROTTLE_WINDOW = Duration.ofHours(1L);
    static final Duration MAX_THROTTLE_DELAY = Duration.ofSeconds(60L);
    static final Duration DEFAULT_PROVIDER_TIMEOUT = Duration.ofSeconds(2L);
    static final Duration DEFAULT_PROVIDER_SHUTDOWN_WAIT = Duration.ofSeconds(2L);
    static final int DEFAULT_CONTROL_CONCURRENCY = 1;
    static final int DEFAULT_CONTROL_QUEUE_CAPACITY = 4;
    static final Duration DEFAULT_CONTROL_TIMEOUT = Duration.ofSeconds(2L);
    static final Duration DEFAULT_CONTROL_SHUTDOWN_WAIT = Duration.ofSeconds(2L);
    static final Duration MAX_CONTROL_TIMEOUT = Duration.ofSeconds(30L);
    static final Duration MAX_CONTROL_SHUTDOWN_WAIT = Duration.ofSeconds(10L);
    static final Duration DEFAULT_CONFIRMATION_TTL = Duration.ofSeconds(30L);
    static final Duration MAX_CONFIRMATION_TTL = Duration.ofMinutes(5L);
    static final int DEFAULT_MAX_CONFIRMATIONS = 128;
    static final int MAX_CONFIRMATIONS = 1024;
    static final int DEFAULT_MAX_DISCOVERY_RECORDS = 256;
    static final int DEFAULT_MAX_CONFIGURED_TARGETS = 64;
    static final Duration DEFAULT_DISCOVERY_TTL = Duration.ofSeconds(30L);
    static final Duration DEFAULT_DISCOVERY_HEARTBEAT = Duration.ofSeconds(10L);

    private final boolean enabled;
    private final String nuggetName;
    private final boolean unsafeDeveloperMode;
    private final SecurityConfiguration security;
    private final Clock clock;
    private final LongSupplier monotonicTimeSource;
    private final SecureRandom secureRandom;
    private final int iterations;
    private final int maxPinBytes;
    private final int maxThrottleBuckets;
    private final Duration throttleWindow;
    private final Duration maxThrottleDelay;
    private final int providerConcurrency;
    private final int providerQueueCapacity;
    private final Duration providerTimeout;
    private final Duration providerShutdownWait;
    private final boolean localDiscoveryEnabled;
    private final Path localDiscoveryDirectory;
    private final Duration discoveryTtl;
    private final Duration discoveryHeartbeat;
    private final int maxDiscoveryRecords;
    private final List<NuggetDiscovery.ConfiguredTarget> configuredTargets;
    private final List<PinSourceConfig> sources;
    private final boolean generatedDevelopmentPinRequested;
    private final Consumer<char[]> generatedDevelopmentPinReceiver;
    private final NuggetAdminFileOperations fileOperations;
    private final NuggetAdminLifecycleHooks lifecycleHooks;
    private final NuggetAdminResourceFactory resourceFactory;
    private final NuggetIntrospectionProviders introspectionProviders;
    private final ApplicationDeployer applicationDeployer;
    private final boolean readOnly;
    private final boolean startApplicationEnabled;
    private final boolean killEnabled;
    private final int controlConcurrency;
    private final int controlQueueCapacity;
    private final Duration controlTimeout;
    private final Duration controlShutdownWait;
    private final Duration confirmationTtl;
    private final int maxConfirmations;
    private final Runnable runtimeCloseAction;
    private final Runnable terminationCapability;

    private NuggetAdminConfiguration(final Builder builder) {
        this.enabled = builder.enabled;
        this.nuggetName = builder.nuggetName;
        this.unsafeDeveloperMode = builder.unsafeDeveloperMode;
        this.security = builder.security;
        this.clock = builder.clock;
        this.monotonicTimeSource = builder.monotonicTimeSource;
        this.secureRandom = builder.secureRandom;
        this.iterations = builder.iterations;
        this.maxPinBytes = builder.maxPinBytes;
        this.maxThrottleBuckets = builder.maxThrottleBuckets;
        this.throttleWindow = builder.throttleWindow;
        this.maxThrottleDelay = builder.maxThrottleDelay;
        this.providerConcurrency = builder.providerConcurrency;
        this.providerQueueCapacity = builder.providerQueueCapacity;
        this.providerTimeout = builder.providerTimeout;
        this.providerShutdownWait = builder.providerShutdownWait;
        this.localDiscoveryEnabled = builder.localDiscoveryEnabled;
        this.localDiscoveryDirectory = builder.localDiscoveryDirectory;
        this.discoveryTtl = builder.discoveryTtl;
        this.discoveryHeartbeat = builder.discoveryHeartbeat;
        this.maxDiscoveryRecords = builder.maxDiscoveryRecords;
        this.configuredTargets = List.copyOf(builder.configuredTargets);
        this.sources = List.copyOf(builder.sources);
        this.generatedDevelopmentPinRequested = builder.generatedDevelopmentPinRequested;
        this.generatedDevelopmentPinReceiver = builder.generatedDevelopmentPinReceiver;
        this.fileOperations = builder.fileOperations;
        this.lifecycleHooks = builder.lifecycleHooks;
        this.resourceFactory = builder.resourceFactory;
        this.introspectionProviders = builder.introspectionProviders.build();
        this.applicationDeployer = builder.applicationDeployer;
        this.readOnly = builder.readOnly;
        this.startApplicationEnabled = builder.startApplicationEnabled;
        this.killEnabled = builder.killEnabled;
        this.controlConcurrency = builder.controlConcurrency;
        this.controlQueueCapacity = builder.controlQueueCapacity;
        this.controlTimeout = builder.controlTimeout;
        this.controlShutdownWait = builder.controlShutdownWait;
        this.confirmationTtl = builder.confirmationTtl;
        this.maxConfirmations = builder.maxConfirmations;
        this.runtimeCloseAction = builder.runtimeCloseAction;
        this.terminationCapability = builder.terminationCapability;
    }

    /**
     * Creates a builder using development security defaults.
     *
     * @return configuration builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether nugget administration is enabled.
     *
     * @return enabled flag
     */
    public boolean enabled() {
        return this.enabled;
    }

    /**
     * Returns the configured human-readable nugget name.
     *
     * @return nugget name, or empty string
     */
    public String nuggetName() {
        return this.nuggetName;
    }

    /**
     * Returns whether unsafe developer mode is explicitly active.
     *
     * @return unsafe developer flag
     */
    public boolean unsafeDeveloperMode() {
        return this.unsafeDeveloperMode;
    }

    /**
     * Returns runtime security configuration used for audit and mode policy.
     *
     * @return security configuration
     */
    public SecurityConfiguration security() {
        return this.security;
    }

    /**
     * Returns the wall-clock source used for status and audit timestamps.
     *
     * @return wall clock
     */
    public Clock clock() {
        return this.clock;
    }

    LongSupplier monotonicTimeSource() {
        return this.monotonicTimeSource;
    }

    SecureRandom secureRandom() {
        return this.secureRandom;
    }

    int iterations() {
        return this.iterations;
    }

    int maxPinBytes() {
        return this.maxPinBytes;
    }

    int maxThrottleBuckets() {
        return this.maxThrottleBuckets;
    }

    Duration throttleWindow() {
        return this.throttleWindow;
    }

    Duration maxThrottleDelay() {
        return this.maxThrottleDelay;
    }

    int providerConcurrency() {
        return this.providerConcurrency;
    }

    int providerQueueCapacity() {
        return this.providerQueueCapacity;
    }

    Duration providerTimeout() {
        return this.providerTimeout;
    }

    Duration providerShutdownWait() {
        return this.providerShutdownWait;
    }

    boolean localDiscoveryEnabled() {
        return this.localDiscoveryEnabled;
    }

    Path localDiscoveryDirectory() {
        return this.localDiscoveryDirectory;
    }

    Duration discoveryTtl() {
        return this.discoveryTtl;
    }

    Duration discoveryHeartbeat() {
        return this.discoveryHeartbeat;
    }

    int maxDiscoveryRecords() {
        return this.maxDiscoveryRecords;
    }

    List<NuggetDiscovery.ConfiguredTarget> configuredTargets() {
        return this.configuredTargets;
    }

    List<PinSourceConfig> sources() {
        return this.sources;
    }

    boolean generatedDevelopmentPinRequested() {
        return this.generatedDevelopmentPinRequested;
    }

    Consumer<char[]> generatedDevelopmentPinReceiver() {
        return this.generatedDevelopmentPinReceiver;
    }

    NuggetAdminFileOperations fileOperations() {
        return this.fileOperations;
    }

    NuggetAdminLifecycleHooks lifecycleHooks() {
        return this.lifecycleHooks;
    }

    NuggetAdminResourceFactory resourceFactory() {
        return this.resourceFactory;
    }

    NuggetIntrospectionProviders introspectionProviders() {
        return this.introspectionProviders;
    }

    ApplicationDeployer applicationDeployer() {
        return this.applicationDeployer;
    }

    boolean readOnly() {
        return this.readOnly;
    }

    boolean startApplicationEnabled() {
        return this.startApplicationEnabled;
    }

    boolean killEnabled() {
        return this.killEnabled;
    }

    int controlConcurrency() {
        return this.controlConcurrency;
    }

    int controlQueueCapacity() {
        return this.controlQueueCapacity;
    }

    Duration controlTimeout() {
        return this.controlTimeout;
    }

    Duration controlShutdownWait() {
        return this.controlShutdownWait;
    }

    Duration confirmationTtl() {
        return this.confirmationTtl;
    }

    int maxConfirmations() {
        return this.maxConfirmations;
    }

    Runnable runtimeCloseAction() {
        return this.runtimeCloseAction;
    }

    Runnable terminationCapability() {
        return this.terminationCapability;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "NuggetAdminConfiguration[enabled=" + this.enabled
                + ", nuggetName=" + this.nuggetName
                + ", unsafeDeveloperMode=" + this.unsafeDeveloperMode
                + ", sources=" + this.sources.stream().map(PinSourceConfig::type).toList()
                + ", iterations=" + this.iterations
                + ", maxPinBytes=" + this.maxPinBytes
                + ", maxThrottleBuckets=" + this.maxThrottleBuckets
                + ", localDiscoveryEnabled=" + this.localDiscoveryEnabled
                + ", configuredDiscoveryTargets=" + this.configuredTargets.size()
                + ", readOnly=" + this.readOnly
                + ", startApplicationEnabled=" + this.startApplicationEnabled
                + ", killEnabled=" + this.killEnabled
                + ", controlConcurrency=" + this.controlConcurrency
                + ", controlQueueCapacity=" + this.controlQueueCapacity
                + ", maxConfirmations=" + this.maxConfirmations
                + ", runtimeControl=" + (this.runtimeCloseAction == null ? "unavailable" : "configured")
                + ", terminationCapability=" + (this.terminationCapability == null ? "unavailable" : "configured")
                + ", applicationLifecycleProvider=" + (this.applicationDeployer == null ? "unavailable" : "configured") + "]";
    }

    /**
     * Mutable builder for {@link NuggetAdminConfiguration}.
     */
    public static final class Builder {
        private boolean enabled = true;
        private String nuggetName = "";
        private boolean unsafeDeveloperMode;
        private SecurityConfiguration security = SecurityDefaults.development();
        private Clock clock = Clock.systemUTC();
        private LongSupplier monotonicTimeSource = System::nanoTime;
        private SecureRandom secureRandom = new SecureRandom();
        private int iterations = DEFAULT_ITERATIONS;
        private int maxPinBytes = DEFAULT_MAX_PIN_BYTES;
        private int maxThrottleBuckets = DEFAULT_MAX_THROTTLE_BUCKETS;
        private Duration throttleWindow = DEFAULT_THROTTLE_WINDOW;
        private Duration maxThrottleDelay = DEFAULT_MAX_THROTTLE_DELAY;
        private int providerConcurrency = DEFAULT_PROVIDER_CONCURRENCY;
        private int providerQueueCapacity = DEFAULT_PROVIDER_QUEUE_CAPACITY;
        private Duration providerTimeout = DEFAULT_PROVIDER_TIMEOUT;
        private Duration providerShutdownWait = DEFAULT_PROVIDER_SHUTDOWN_WAIT;
        private boolean localDiscoveryEnabled;
        private Path localDiscoveryDirectory;
        private Duration discoveryTtl = DEFAULT_DISCOVERY_TTL;
        private Duration discoveryHeartbeat = DEFAULT_DISCOVERY_HEARTBEAT;
        private int maxDiscoveryRecords = DEFAULT_MAX_DISCOVERY_RECORDS;
        private final List<NuggetDiscovery.ConfiguredTarget> configuredTargets = new ArrayList<>();
        private final List<PinSourceConfig> sources = new ArrayList<>();
        private boolean generatedDevelopmentPinRequested;
        private Consumer<char[]> generatedDevelopmentPinReceiver = ignored -> { };
        private NuggetAdminFileOperations fileOperations = NuggetAdminFileOperations.real();
        private NuggetAdminLifecycleHooks lifecycleHooks = NuggetAdminLifecycleHooks.noop();
        private NuggetAdminResourceFactory resourceFactory = NuggetAdminResourceFactory.standard();
        private NuggetIntrospectionProviders.Builder introspectionProviders = NuggetIntrospectionProviders.builder();
        private ApplicationDeployer applicationDeployer;
        private boolean readOnly;
        private boolean startApplicationEnabled = true;
        private boolean killEnabled;
        private int controlConcurrency = DEFAULT_CONTROL_CONCURRENCY;
        private int controlQueueCapacity = DEFAULT_CONTROL_QUEUE_CAPACITY;
        private Duration controlTimeout = DEFAULT_CONTROL_TIMEOUT;
        private Duration controlShutdownWait = DEFAULT_CONTROL_SHUTDOWN_WAIT;
        private Duration confirmationTtl = DEFAULT_CONFIRMATION_TTL;
        private int maxConfirmations = DEFAULT_MAX_CONFIRMATIONS;
        private Runnable runtimeCloseAction;
        private Runnable terminationCapability;

        private Builder() {
        }

        /**
         * Sets whether nugget administration is enabled.
         *
         * @param value enabled flag
         * @return this builder
         */
        public Builder enabled(final boolean value) {
            this.enabled = value;
            return this;
        }

        /**
         * Sets the human-readable nugget name.
         *
         * @param value nugget name
         * @return this builder
         */
        public Builder nuggetName(final String value) {
            this.nuggetName = value == null ? "" : value;
            return this;
        }

        /**
         * Enables or disables explicit unsafe developer mode.
         *
         * @param value unsafe developer flag
         * @return this builder
         */
        public Builder unsafeDeveloperMode(final boolean value) {
            this.unsafeDeveloperMode = value;
            return this;
        }

        /**
         * Sets the existing runtime security configuration.
         *
         * @param value security configuration
         * @return this builder
         */
        public Builder security(final SecurityConfiguration value) {
            this.security = Objects.requireNonNull(value, "security");
            return this;
        }

        /**
         * Sets the wall clock for deterministic tests and audit timestamps.
         *
         * @param value wall clock
         * @return this builder
         */
        public Builder clock(final Clock value) {
            this.clock = value == null ? Clock.systemUTC() : value;
            return this;
        }

        /**
         * Sets the monotonic time source for throttle calculations.
         *
         * @param value monotonic nanosecond source
         * @return this builder
         */
        public Builder monotonicTimeSource(final LongSupplier value) {
            this.monotonicTimeSource = value == null ? System::nanoTime : value;
            return this;
        }

        /**
         * Sets the secure random source for salts and explicit development PINs.
         *
         * @param value secure random source
         * @return this builder
         */
        public Builder secureRandom(final SecureRandom value) {
            this.secureRandom = value == null ? new SecureRandom() : value;
            return this;
        }

        /**
         * Sets the PBKDF2 iteration count.
         *
         * @param value iteration count, from 210000 through 1000000
         * @return this builder
         */
        public Builder iterations(final int value) {
            if (value < DEFAULT_ITERATIONS || value > MAX_ITERATIONS) {
                throw new IllegalArgumentException("iterations outside nugget PIN policy");
            }
            this.iterations = value;
            return this;
        }

        /**
         * Sets the maximum accepted UTF-8 encoded PIN length.
         *
         * @param value maximum byte length
         * @return this builder
         */
        public Builder maxPinBytes(final int value) {
            if (value <= 0 || value > DEFAULT_MAX_PIN_BYTES) {
                throw new IllegalArgumentException("maxPinBytes must be between 1 and 256");
            }
            this.maxPinBytes = value;
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
            if (maxBuckets <= 0 || maxBuckets > MAX_THROTTLE_BUCKETS) {
                throw new IllegalArgumentException("maxBuckets outside nugget throttle policy");
            }
            Duration safeWindow = window == null || window.isNegative() || window.isZero() ? DEFAULT_THROTTLE_WINDOW : window;
            Duration safeDelay = maxDelay == null || maxDelay.isNegative() ? DEFAULT_MAX_THROTTLE_DELAY : maxDelay;
            if (safeWindow.compareTo(MAX_THROTTLE_WINDOW) > 0) {
                throw new IllegalArgumentException("throttle window outside nugget throttle policy");
            }
            if (safeDelay.compareTo(MAX_THROTTLE_DELAY) > 0) {
                throw new IllegalArgumentException("throttle delay outside nugget throttle policy");
            }
            this.maxThrottleBuckets = maxBuckets;
            this.throttleWindow = safeWindow;
            this.maxThrottleDelay = safeDelay;
            return this;
        }

        /**
         * Sets bounded execution limits for read-only introspection providers.
         *
         * @param concurrency maximum concurrent provider calls
         * @param timeout per-provider timeout
         * @return this builder
         */
        public Builder providerExecutionPolicy(final int concurrency, final Duration timeout) {
            return this.providerExecutionPolicy(concurrency, DEFAULT_PROVIDER_QUEUE_CAPACITY, timeout, DEFAULT_PROVIDER_SHUTDOWN_WAIT);
        }

        /**
         * Sets bounded execution limits for read-only introspection providers.
         *
         * @param concurrency maximum concurrent provider calls
         * @param queueCapacity maximum queued provider calls
         * @param timeout per-provider timeout
         * @param shutdownWait maximum executor shutdown wait
         * @return this builder
         */
        public Builder providerExecutionPolicy(
                final int concurrency,
                final int queueCapacity,
                final Duration timeout,
                final Duration shutdownWait) {
            if (concurrency <= 0 || concurrency > DEFAULT_PROVIDER_CONCURRENCY) {
                throw new IllegalArgumentException("provider concurrency outside nugget introspection policy");
            }
            if (queueCapacity < 0 || queueCapacity > DEFAULT_PROVIDER_QUEUE_CAPACITY) {
                throw new IllegalArgumentException("provider queue capacity outside nugget introspection policy");
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_PROVIDER_TIMEOUT) > 0) {
                throw new IllegalArgumentException("provider timeout outside nugget introspection policy");
            }
            if (shutdownWait == null
                    || shutdownWait.isNegative()
                    || shutdownWait.isZero()
                    || shutdownWait.compareTo(MAX_PROVIDER_SHUTDOWN_WAIT) > 0) {
                throw new IllegalArgumentException("provider shutdown wait outside nugget introspection policy");
            }
            this.providerConcurrency = concurrency;
            this.providerQueueCapacity = queueCapacity;
            this.providerTimeout = timeout;
            this.providerShutdownWait = shutdownWait;
            return this;
        }

        /**
         * Enables or disables local J7M-owned discovery publication.
         *
         * @param directory protected local discovery directory
         * @param value enabled flag
         * @return this builder
         */
        public Builder localDiscovery(final Path directory, final boolean value) {
            this.localDiscoveryEnabled = value;
            this.localDiscoveryDirectory = directory;
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
            if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofMinutes(10L)) > 0) {
                throw new IllegalArgumentException("discovery ttl outside policy");
            }
            if (heartbeat == null || heartbeat.isNegative() || heartbeat.isZero() || heartbeat.compareTo(ttl) >= 0) {
                throw new IllegalArgumentException("discovery heartbeat outside policy");
            }
            this.discoveryTtl = ttl;
            this.discoveryHeartbeat = heartbeat;
            return this;
        }

        /**
         * Sets the maximum local discovery records scanned per operation.
         *
         * @param value maximum record count
         * @return this builder
         */
        public Builder maxDiscoveryRecords(final int value) {
            if (value <= 0 || value > DEFAULT_MAX_DISCOVERY_RECORDS) {
                throw new IllegalArgumentException("discovery record limit outside policy");
            }
            this.maxDiscoveryRecords = value;
            return this;
        }

        /**
         * Adds an explicit configured discovery target.
         *
         * @param target configured target
         * @return this builder
         */
        public Builder discoveryTarget(final NuggetDiscovery.ConfiguredTarget target) {
            if (target == null) {
                auditRejectedConfiguredTarget("invalid-target");
                throw new IllegalArgumentException("target must not be null");
            }
            if (this.configuredTargets.size() >= DEFAULT_MAX_CONFIGURED_TARGETS) {
                auditRejectedConfiguredTarget("too-many-targets");
                throw new IllegalArgumentException("too many configured discovery targets");
            }
            if (!this.configuredTargets.contains(target)) {
                this.configuredTargets.add(target);
            }
            return this;
        }

        Builder discoveryTarget(
                final String transport,
                final String scheme,
                final String host,
                final int port,
                final String localId,
                final String expectedNuggetId,
                final String expectedName,
                final int protocolMinimum,
                final int protocolMaximum) {
            try {
                return this.discoveryTarget(new NuggetDiscovery.ConfiguredTarget(
                        new NuggetDiscovery.EndpointDescriptor(transport, scheme, host, port, localId),
                        expectedNuggetId,
                        expectedName,
                        protocolMinimum,
                        protocolMaximum));
            } catch (IllegalArgumentException e) {
                auditRejectedConfiguredTarget("invalid-target");
                throw e;
            }
        }

        private void auditRejectedConfiguredTarget(final String reason) {
            this.security.auditSink().record(new org.j7m.middleware.security.api.AuditEvent(
                    this.clock.instant(),
                    "",
                    "system",
                    "nugget.discovery.configured-target.rejected",
                    "",
                    "deny",
                    this.security.policy().id(),
                    "",
                    "",
                    "",
                    reason == null || reason.isBlank() ? "invalid-target" : reason,
                    "headless",
                    "",
                    Map.of("source", NuggetDiscovery.Source.CONFIGURED_TARGET.name())));
        }

        /**
         * Adds a protected programmatic PIN source.
         *
         * @param description safe source description
         * @param provider protected provider
         * @return this builder
         */
        public Builder pinFromProgrammaticProvider(final String description, final NuggetPinSecretProvider provider) {
            this.sources.add(new PinSourceConfig(NuggetPinSourceType.PROGRAMMATIC_PROVIDER, description, provider, null, null, false));
            return this;
        }

        /**
         * Adds a configured environment variable PIN source.
         *
         * @param variableName environment variable name
         * @return this builder
         */
        public Builder pinFromEnvironment(final String variableName) {
            return this.pinFromEnvironment(variableName, System::getenv);
        }

        /**
         * Adds a configured environment variable PIN source with injectable lookup.
         *
         * @param variableName environment variable name
         * @param lookup environment lookup
         * @return this builder
         */
        public Builder pinFromEnvironment(final String variableName, final Function<String, String> lookup) {
            this.sources.add(new PinSourceConfig(NuggetPinSourceType.ENVIRONMENT_VARIABLE, variableName, null, null, lookup, false));
            return this;
        }

        /**
         * Adds a protected PIN file source.
         *
         * @param path configured file path
         * @return this builder
         */
        public Builder pinFromProtectedFile(final Path path) {
            this.sources.add(new PinSourceConfig(NuggetPinSourceType.PROTECTED_FILE, "pin-file", null, path, null, false));
            return this;
        }

        /**
         * Adds a lower-safety direct process argument PIN source.
         *
         * @param pin PIN characters copied into the configuration source
         * @return this builder
         */
        public Builder pinFromDirectProcessArgument(final char[] pin) {
            char[] copy = pin == null ? null : Arrays.copyOf(pin, pin.length);
            this.sources.add(new PinSourceConfig(NuggetPinSourceType.DIRECT_PROCESS_ARGUMENT, "direct-process-argument",
                    copy == null ? null : new DirectProcessArgumentProvider(copy), null, null, true));
            return this;
        }

        /**
         * Adds an explicit interactive bootstrap source supplied by the embedding runtime.
         *
         * @param provider terminal-safe prompt provider
         * @return this builder
         */
        public Builder pinFromInteractiveBootstrap(final NuggetPinSecretProvider provider) {
            this.sources.add(new PinSourceConfig(NuggetPinSourceType.INTERACTIVE_BOOTSTRAP, "interactive-bootstrap", provider, null, null, false));
            return this;
        }

        /**
         * Adds a protected verifier file source.
         *
         * @param path verifier record path
         * @return this builder
         */
        public Builder pinFromProtectedVerifierFile(final Path path) {
            this.sources.add(new PinSourceConfig(NuggetPinSourceType.PROTECTED_VERIFIER_FILE, "verifier-file", null, path, null, false));
            return this;
        }

        /**
         * Requests an explicit generated development PIN in unsafe developer mode.
         *
         * @param receiver local developer channel receiving a copied PIN
         * @return this builder
         */
        public Builder generatedDevelopmentPin(final Consumer<char[]> receiver) {
            this.generatedDevelopmentPinRequested = true;
            this.generatedDevelopmentPinReceiver = receiver == null ? ignored -> { } : receiver;
            return this;
        }

        Builder fileOperations(final NuggetAdminFileOperations value) {
            this.fileOperations = value == null ? NuggetAdminFileOperations.real() : value;
            return this;
        }

        Builder lifecycleHooks(final NuggetAdminLifecycleHooks value) {
            this.lifecycleHooks = value == null ? NuggetAdminLifecycleHooks.noop() : value;
            return this;
        }

        Builder resourceFactory(final NuggetAdminResourceFactory value) {
            this.resourceFactory = value == null ? NuggetAdminResourceFactory.standard() : value;
            return this;
        }

        /**
         * Adds a trusted typed read-only introspection provider.
         *
         * @param value provider to execute through bounded runtime controls
         * @return this builder
         */
        public Builder introspectionProvider(final NuggetIntrospection.Provider value) {
            this.introspectionProviders.provider(value);
            return this;
        }

        /**
         * Sets the public deployer authority used for application lifecycle
         * operations.
         *
         * <p>The runtime stores only the public API contract. Supplying
         * {@code null} disables lifecycle mutation support and causes requests
         * to return a truthful unavailable result.</p>
         *
         * @param value application deployer, or {@code null}
         * @return this builder
         */
        public Builder applicationDeployer(final ApplicationDeployer value) {
            this.applicationDeployer = value;
            return this;
        }

        /**
         * Enables or disables read-only administration mode.
         *
         * <p>Read-only mode leaves admission and inspection available but
         * rejects lifecycle mutations before the deployer is invoked.</p>
         *
         * @param value read-only flag
         * @return this builder
         */
        public Builder readOnly(final boolean value) {
            this.readOnly = value;
            return this;
        }

        /**
         * Enables or disables application start operations.
         *
         * <p>Stop, drain, and unload are controlled by the general read-only
         * gate and authorization policy. This flag exists so production
         * embedders can disable repository-coordinate activation while
         * preserving other lifecycle controls.</p>
         *
         * @param value start-enabled flag
         * @return this builder
         */
        public Builder startApplicationEnabled(final boolean value) {
            this.startApplicationEnabled = value;
            return this;
        }

        /**
         * Enables or disables explicitly gated nugget self-termination.
         *
         * <p>The default is disabled. Enabling this flag is not sufficient by
         * itself: the caller must also supply a termination capability and each
         * request must pass admission, authorization, exact self-target
         * validation, confirmation, and deadline checks.</p>
         *
         * @param value kill-enabled flag
         * @return this builder
         */
        public Builder killEnabled(final boolean value) {
            this.killEnabled = value;
            return this;
        }

        /**
         * Sets bounded execution limits for nugget-level control operations.
         *
         * @param concurrency maximum concurrent control calls, currently only
         *        {@code 1}
         * @param queueCapacity maximum queued control calls
         * @param timeout positive control-operation deadline, at most
         *        30 seconds
         * @param shutdownWait positive maximum control-executor shutdown wait,
         *        at most 10 seconds
         * @return this builder
         */
        public Builder controlExecutionPolicy(
                final int concurrency,
                final int queueCapacity,
                final Duration timeout,
                final Duration shutdownWait) {
            if (concurrency != DEFAULT_CONTROL_CONCURRENCY) {
                throw new IllegalArgumentException("control concurrency outside nugget control policy");
            }
            if (queueCapacity < 0 || queueCapacity > DEFAULT_CONTROL_QUEUE_CAPACITY) {
                throw new IllegalArgumentException("control queue capacity outside nugget control policy");
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_CONTROL_TIMEOUT) > 0) {
                throw new IllegalArgumentException("control timeout outside nugget control policy");
            }
            if (shutdownWait == null
                    || shutdownWait.isNegative()
                    || shutdownWait.isZero()
                    || shutdownWait.compareTo(MAX_CONTROL_SHUTDOWN_WAIT) > 0) {
                throw new IllegalArgumentException("control shutdown wait outside nugget control policy");
            }
            this.controlConcurrency = concurrency;
            this.controlQueueCapacity = queueCapacity;
            this.controlTimeout = timeout;
            this.controlShutdownWait = shutdownWait;
            return this;
        }

        /**
         * Sets bounded confirmation retention for nugget controls.
         *
         * @param ttl positive confirmation time-to-live, at most five minutes
         * @param maxRetained maximum retained live challenges
         * @return this builder
         */
        public Builder confirmationPolicy(final Duration ttl, final int maxRetained) {
            if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_CONFIRMATION_TTL) > 0) {
                throw new IllegalArgumentException("confirmation ttl outside nugget control policy");
            }
            if (maxRetained <= 0 || maxRetained > MAX_CONFIRMATIONS) {
                throw new IllegalArgumentException("confirmation retention outside nugget control policy");
            }
            this.confirmationTtl = ttl;
            this.maxConfirmations = maxRetained;
            return this;
        }

        /**
         * Sets the runtime close capability used for graceful nugget shutdown.
         *
         * <p>The builder-owned runtime assembly path installs the authoritative
         * {@code MiddlewareRuntime.close()} action here. The action is executed
         * asynchronously after the public result boundary and must not be the
         * admin service's own {@link AutoCloseable#close()} method.</p>
         *
         * @param value runtime close action, or {@code null}
         * @return this builder
         */
        public Builder runtimeCloseAction(final Runnable value) {
            this.runtimeCloseAction = value;
            return this;
        }

        /**
         * Sets the injected self-termination capability for forced nugget
         * termination tests or controlled embedders.
         *
         * <p>The implementation never installs a platform termination primitive
         * by default. Supplying this callback does not enable kill unless
         * {@link #killEnabled(boolean)} is also set to {@code true}; production
         * code must not use this hook to implement arbitrary process-id
         * selection or shell execution.</p>
         *
         * @param value injected self-termination callback, or {@code null}
         * @return this builder
         */
        public Builder terminationCapability(final Runnable value) {
            this.terminationCapability = value;
            return this;
        }

        /**
         * Adds a configured storage root for disk introspection.
         *
         * @param role storage role
         * @param path root path
         * @param sensitive whether path components are sensitive
         * @return this builder
         */
        public Builder storageRoot(final String role, final Path path, final boolean sensitive) {
            this.introspectionProviders.diskRoot(role, path, sensitive);
            return this;
        }

        /**
         * Builds an immutable configuration.
         *
         * @return configuration
         */
        public NuggetAdminConfiguration build() {
            return new NuggetAdminConfiguration(this);
        }
    }

    record PinSourceConfig(
            NuggetPinSourceType type,
            String description,
            NuggetPinSecretProvider provider,
            Path path,
            Function<String, String> environmentLookup,
            boolean lowerSafety) {
        PinSourceConfig {
            if (type == null) {
                throw new IllegalArgumentException("type must not be null");
            }
            description = description == null ? "" : description;
        }
    }

    private static final class DirectProcessArgumentProvider implements NuggetPinSecretProvider {
        private char[] value;

        private DirectProcessArgumentProvider(final char[] value) {
            this.value = Arrays.copyOf(value, value.length);
            Arrays.fill(value, '\0');
        }

        @Override
        public synchronized char[] resolve() {
            if (this.value == null) {
                throw new IllegalStateException("direct process argument PIN already consumed");
            }
            char[] copy = Arrays.copyOf(this.value, this.value.length);
            Arrays.fill(this.value, '\0');
            this.value = null;
            return copy;
        }
    }
}
