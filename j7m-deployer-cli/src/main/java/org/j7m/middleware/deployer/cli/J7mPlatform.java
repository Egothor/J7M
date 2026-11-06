/* Copyright (c) 2026, Leo Galambos and the Egothor team
 * SPDX-License-Identifier: BSD-4-Clause
 * Licensed under the BSD 4-Clause License; see LICENSE for the full terms.
 */
package org.j7m.middleware.deployer.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.api.MiddlewareRuntime;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.components.ManagedHostingOptions;
import org.j7m.middleware.application.repository.ApplicationPlatformLimits;
import org.j7m.middleware.application.repository.J7mBundleReader;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.application.repository.PublicKeyTrustStore;
import org.j7m.middleware.application.repository.SigningKeyId;
import org.j7m.middleware.deployer.ApplicationManager;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.j7m.middleware.transport.rmi.RmiTransportProvider;

/**
 * Foreground installed-platform host for one operator-configured signed application.
 * The host owns its runtime and activation until shutdown. Trust comes only from
 * the operator's public key file; application metadata cannot install trusted keys.
 */
public final class J7mPlatform {
    private static final Set<String> SETTINGS = Set.of("application.bundle", "repository.path", "trust.key-id",
            "trust.public-key", "runtime.id", "application.environment");

    private J7mPlatform() { }

    /**
     * Starts a configured host and waits until interrupted or terminated.
     * Configuration uses Java properties; relative paths are resolved against its directory.
     * Shutdown deactivates and unloads the application before closing the runtime.
     * @param args exactly {@code --config FILE}, or {@code --help}
     */
    public static void main(final String[] args) {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println("Usage: j7m-platform --config FILE");
            return;
        }
        if (args.length != 2 || !"--config".equals(args[0])) {
            System.err.println("Usage: j7m-platform --config FILE");
            System.exit(2);
        }
        try {
            var stopped = new CountDownLatch(1);
            try (var session = start(Path.of(args[1]), System.out)) {
                Thread shutdown = new Thread(() -> {
                    session.close();
                    stopped.countDown();
                }, "j7m-platform-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdown);
                System.out.println("J7M_PLATFORM_PID " + ProcessHandle.current().pid());
                System.out.println("J7M_PLATFORM_RUNNING " + session.activation.instanceIdentity().application().coordinate());
                System.out.flush();
                try {
                    stopped.await();
                } finally {
                    try {
                        Runtime.getRuntime().removeShutdownHook(shutdown);
                    } catch (IllegalStateException shuttingDown) {
                        // Wait for the hook that owns shutdown after VM termination has begun.
                        shutdown.join();
                    }
                }
            }
        } catch (Exception failure) {
            System.err.println("J7M_PLATFORM_FAILED: configuration or application activation rejected");
            System.exit(1);
        }
    }

    static Session start(final Path configurationFile, final PrintStream output) throws Exception {
        Path absolute = configurationFile.toAbsolutePath().normalize();
        if (Files.size(absolute) > 65536) throw new IllegalArgumentException("Configuration exceeds limit");
        var settings = new Properties();
        try (var input = Files.newInputStream(absolute)) { settings.load(input); }
        for (String name : settings.stringPropertyNames()) {
            if (!SETTINGS.contains(name) && !name.startsWith("application.config.")) {
                throw new IllegalArgumentException("Unknown platform configuration key");
            }
        }
        Path base = absolute.getParent();
        Path application = resolve(base, required(settings, "application.bundle"));
        Path storage = resolve(base, required(settings, "repository.path"));
        Path publicKey = resolve(base, required(settings, "trust.public-key"));
        if (Files.size(publicKey) > 8192) throw new IllegalArgumentException("Public key exceeds limit");
        var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicKey)));
        var trust = new PublicKeyTrustStore(Map.of(new SigningKeyId(required(settings, "trust.key-id")), key));
        var limits = ApplicationPlatformLimits.defaults();
        var reader = new J7mBundleReader(limits);
        var handle = new ApplicationBundle(application, null, reader.sha256(application));
        var manifest = reader.readManifest(handle);
        var bundle = new ApplicationBundle(application, manifest.identity().coordinate(), handle.sha256());
        var repository = new LocalApplicationRepository(storage, Clock.systemUTC(), limits, trust);
        // Immutable republishing is unnecessary on an ordinary host restart; the
        // existing coordinate must still have exactly the selected bundle digest.
        if (repository.listVersions(bundle.coordinate().id()).contains(bundle.coordinate().version())) {
            if (!repository.fetch(bundle.coordinate()).sha256().equals(bundle.sha256())) {
                throw new IllegalArgumentException("Installed coordinate has different contents");
            }
        } else if (!repository.publish(bundle, new RepositoryPublishOptions("platform", true, "strict-production")).success()) {
            throw new IllegalArgumentException("Application publication rejected");
        }
        Map<String, String> applicationConfiguration = new HashMap<>();
        for (String name : settings.stringPropertyNames()) {
            if (name.startsWith("application.config.")) {
                applicationConfiguration.put(name.substring("application.config.".length()), settings.getProperty(name));
            }
        }
        MiddlewareRuntime runtime = new MiddlewareRuntimeBuilder()
                .runtimeId(settings.getProperty("runtime.id", "j7m-platform"))
                .disableNuggetAdmin()
                .addTransport(new GrpcTransportProvider())
                .addTransport(new RmiTransportProvider())
                .build();
        try {
            var manager = new ApplicationManager(runtime, repository, limits, ManagedHostingOptions.defaults(), trust);
            var activation = manager.activate(bundle.coordinate(), new ApplicationActivationRequest("platform-operator",
                    settings.getProperty("application.environment", "production"), true, applicationConfiguration));
            if (activation.state() != ApplicationRunState.RUNNING) {
                throw new IllegalArgumentException("Application did not enter running state");
            }
            return new Session(runtime, manager, activation, output);
        } catch (Exception failure) {
            runtime.close();
            throw failure;
        }
    }

    private static String required(final Properties settings, final String name) {
        String value = settings.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required platform setting missing");
        return value;
    }

    private static Path resolve(final Path base, final String value) {
        return base.resolve(value).toAbsolutePath().normalize();
    }

    static final class Session implements AutoCloseable {
        private final MiddlewareRuntime runtime;
        private final ApplicationManager manager;
        private final ApplicationActivation activation;
        private final PrintStream output;
        private final AtomicBoolean closed = new AtomicBoolean();

        Session(final MiddlewareRuntime runtime, final ApplicationManager manager,
                final ApplicationActivation activation, final PrintStream output) {
            this.runtime = runtime;
            this.manager = manager;
            this.activation = activation;
            this.output = output;
        }

        @Override public void close() {
            if (this.closed.compareAndSet(false, true)) {
                try {
                    var stopped = this.manager.deactivate(this.activation.instanceIdentity().instanceId());
                    if (!stopped.success()) throw new IllegalStateException("Application did not stop");
                    var unloaded = this.manager.unload(this.activation.instanceIdentity().instanceId());
                    if (!unloaded.success()) throw new IllegalStateException("Application did not unload");
                    this.output.println("J7M_PLATFORM_STOPPED");
                } finally {
                    this.runtime.close();
                    this.output.flush();
                }
            }
        }
    }
}
