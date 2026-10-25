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

package org.j7m.middleware.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.operations.api.NuggetAdminState;
import org.j7m.middleware.operations.api.NuggetAuthenticationOutcome;
import org.j7m.middleware.operations.api.NuggetAuthenticationRequest;
import org.j7m.middleware.operations.api.NuggetAuthenticationSource;
import org.j7m.middleware.operations.api.NuggetAdminService;
import org.j7m.middleware.operations.api.NuggetPinSourceType;
import org.j7m.middleware.operations.api.NuggetState;
import org.j7m.middleware.registry.api.PeerQuery;
import org.j7m.middleware.registry.api.RegistryClient;
import org.j7m.middleware.registry.api.RegistryPeerStatus;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.j7m.middleware.registry.api.RegistryRecord;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.runtime.NuggetAdminRuntime;
import org.j7m.middleware.runtime.NuggetAdminRuntimeConfiguration;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.security.api.SecurityMode;
import org.j7m.middleware.security.defaults.AllowAllAuthorizationPolicy;
import org.j7m.middleware.security.defaults.DefaultRedactor;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.j7m.middleware.security.defaults.MutableCredentialProvider;
import org.j7m.middleware.transport.api.ExportDescriptor;
import org.j7m.middleware.transport.api.ExportedObject;
import org.j7m.middleware.transport.api.MethodDescriptor;
import org.j7m.middleware.transport.api.RuntimeTransportContext;
import org.j7m.middleware.transport.api.TransportEndpoint;
import org.j7m.middleware.transport.api.TransportProvider;
import org.junit.jupiter.api.Test;

/**
 * Startup and configuration tests for nugget administration admission.
 */
public final class NuggetAdminStartupTest {
    private static final char[] RUNTIME_ADMIN_PIN = new char[] {'7', '0', '0', '0', '0', '3'};

    @Test
    public void disabledAdminDoesNotRequirePinAndDoesNotAuthenticate() {
        InMemoryAuditSink audit = new InMemoryAuditSink(16);
        NuggetAdminService controller = start(baseConfig(audit)
                .enabled(false)
                .build());

        assertEquals(NuggetAdminState.DISABLED, controller.status().configuration().adminState());
        assertEquals(NuggetState.STOPPED, controller.status().identity().state());
        assertEquals(NuggetAuthenticationOutcome.UNAVAILABLE, controller.authenticate(request("disabled"), "ignored".toCharArray()).outcome());
        assertTrue(audit.recentEvents().stream().anyMatch(event -> event.action().equals("nugget.admin.disabled")));
    }

    @Test
    public void enabledAdminFailsClosedWhenPinSourceIsMissing() {
        MiddlewareException failure = assertThrows(MiddlewareException.class,
                () -> start(baseConfig(new InMemoryAuditSink(8)).build()));

        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, failure.code());
        assertFalse(failure.getMessage().contains("pin"));
    }

    @Test
    public void multiplePinSourcesFailStartup() {
        NuggetAdminRuntimeConfiguration configuration = baseConfig(new InMemoryAuditSink(8))
                .pinFromProgrammaticProvider("primary", () -> "first-secret".toCharArray())
                .pinFromEnvironment("J7M_TEST_PIN", ignored -> "second-secret")
                .build();

        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT,
                assertThrows(MiddlewareException.class, () -> start(configuration)).code());
    }

    @Test
    public void environmentSourceRequiresExplicitConfiguredName() {
        NuggetAdminService controller = start(baseConfig(new InMemoryAuditSink(8))
                .pinFromEnvironment("J7M_TEST_PIN", name -> "env-secret")
                .build());

        assertEquals(NuggetPinSourceType.ENVIRONMENT_VARIABLE, controller.status().configuration().pinSourceType());
        assertTrue(controller.authenticate(request("env"), "env-secret".toCharArray()).accepted());
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                () -> start(baseConfig(new InMemoryAuditSink(8))
                        .pinFromEnvironment("bad-name!", name -> "env-secret")
                        .build())).code());
    }

    @Test
    public void protectedFileSourceReadsOnlyAfterSafeFileValidation() throws Exception {
        Path directory = Files.createTempDirectory("j7m-pin");
        Path file = directory.resolve("pin.txt");
        try {
            trySetOwnerOnlyDirectoryPermissions(directory);
            Files.writeString(file, "file-secret");
            trySetOwnerOnlyPermissions(file);
            NuggetAdminService controller = start(baseConfig(new InMemoryAuditSink(8))
                    .pinFromProtectedFile(file)
                    .build());

            assertEquals(NuggetPinSourceType.PROTECTED_FILE, controller.status().configuration().pinSourceType());
            assertTrue(controller.authenticate(request("file"), "file-secret".toCharArray()).accepted());
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void blankPinSourcesFailStartup() throws Exception {
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                () -> start(baseConfig(new InMemoryAuditSink(8))
                        .pinFromProgrammaticProvider("protected", () -> " \t\n\u2003".toCharArray())
                        .build())).code());
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                () -> start(baseConfig(new InMemoryAuditSink(8))
                        .pinFromEnvironment("J7M_TEST_PIN", ignored -> " \t\n\u2003")
                        .build())).code());
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                () -> start(baseConfig(new InMemoryAuditSink(8))
                        .pinFromDirectProcessArgument(" \t\n\u2003".toCharArray())
                        .build())).code());
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                () -> start(baseConfig(new InMemoryAuditSink(8))
                        .pinFromInteractiveBootstrap(() -> " \t\n\u2003".toCharArray())
                        .build())).code());

        Path directory = Files.createTempDirectory("j7m-blank-pin");
        Path file = directory.resolve("pin.txt");
        try {
            trySetOwnerOnlyDirectoryPermissions(directory);
            Files.writeString(file, " \t\n\u2003");
            trySetOwnerOnlyPermissions(file);

            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                    () -> start(baseConfig(new InMemoryAuditSink(8))
                            .pinFromProtectedFile(file)
                            .build())).code());
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void configuredMaxPinBytesRejectsStartupPinBeforeVerifierCreation() {
        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                () -> start(baseConfig(new InMemoryAuditSink(8))
                        .maxPinBytes(16)
                        .secureRandom(new FailingSecureRandom())
                        .pinFromProgrammaticProvider("protected", () -> "12345678901234567".toCharArray())
                        .build())).code());
    }

    @Test
    public void unsafeGeneratedDevelopmentPinRequiresExplicitUnsafeModeAndIsAudited() {
        AtomicReference<char[]> generated = new AtomicReference<>();
        InMemoryAuditSink audit = new InMemoryAuditSink(32);
        NuggetAdminService controller = start(baseConfig(audit)
                .unsafeDeveloperMode(true)
                .generatedDevelopmentPin(pin -> generated.set(java.util.Arrays.copyOf(pin, pin.length)))
                .build());

        assertEquals(NuggetPinSourceType.GENERATED_DEVELOPMENT_PIN, controller.status().configuration().pinSourceType());
        assertTrue(controller.status().configuration().unsafeDeveloperMode());
        assertTrue(controller.authenticate(request("generated"), generated.get()).accepted());
        assertTrue(audit.recentEvents().stream().anyMatch(event -> event.action().equals("nugget.admin.unsafeDeveloperMode.enabled")));

        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                () -> start(baseConfig(new InMemoryAuditSink(8))
                        .generatedDevelopmentPin(ignored -> { })
                        .build())).code());
    }

    @Test
    public void generatedDevelopmentPinHonorsConfiguredMaxPinBytesBeforePublication() {
        AtomicReference<char[]> generated = new AtomicReference<>();

        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                () -> start(baseConfig(new InMemoryAuditSink(8))
                        .unsafeDeveloperMode(true)
                        .maxPinBytes(16)
                        .generatedDevelopmentPin(pin -> generated.set(java.util.Arrays.copyOf(pin, pin.length)))
                        .build())).code());
        assertEquals(null, generated.get());
    }

    @Test
    public void productionModeRejectsUnsafeDeveloperMode() {
        InMemoryAuditSink audit = new InMemoryAuditSink(8);
        SecurityConfiguration production = security(SecurityMode.PRODUCTION, audit);

        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                () -> start(NuggetAdminRuntimeConfiguration.builder()
                        .security(production)
                        .unsafeDeveloperMode(true)
                        .generatedDevelopmentPin(ignored -> { })
                        .build())).code());
    }

    @Test
    public void runtimeBuilderStartsConfiguredAdminAndRuntimeCloseRemovesDiscovery() throws Exception {
        Path directory = Files.createTempDirectory("j7m-runtime-admin-discovery");
        try {
            trySetOwnerOnlyDirectoryPermissions(directory);
            CloseCountingRegistry registry = new CloseCountingRegistry();
            CloseCountingTransportProvider transport = new CloseCountingTransportProvider("runtime-admin");
            NuggetAdminRuntimeConfiguration configuration = baseConfig(new InMemoryAuditSink(32))
                    .nuggetName("runtime-owned-admin")
                    .pinFromProgrammaticProvider("runtime-test", NuggetAdminStartupTest::runtimeAdminPin)
                    .localDiscovery(directory, true)
                    .discoveryTiming(Duration.ofSeconds(30L), Duration.ofSeconds(10L))
                    .build();

            DefaultMiddlewareRuntime runtime = new MiddlewareRuntimeBuilder()
                    .runtimeId("runtime-owned-admin")
                    .registry(registry)
                    .addTransport(transport)
                    .nuggetAdmin(configuration)
                    .build();

            assertEquals(1L, discoveryRecordCount(directory));
            runtime.close();

            assertEquals(0L, discoveryRecordCount(directory));
            assertEquals(1, transport.closeCount());
            assertEquals(1, registry.closeCount());
        } finally {
            deleteDirectoryContents(directory);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void runtimeBuilderExplicitDisableDoesNotRequireAdminPin() {
        CloseCountingRegistry registry = new CloseCountingRegistry();
        CloseCountingTransportProvider transport = new CloseCountingTransportProvider("runtime-disabled-admin");

        DefaultMiddlewareRuntime runtime = new MiddlewareRuntimeBuilder()
                .runtimeId("runtime-disabled-admin")
                .registry(registry)
                .addTransport(transport)
                .disableNuggetAdmin()
                .build();

        runtime.close();

        assertEquals(1, transport.closeCount());
        assertEquals(1, registry.closeCount());
    }

    @Test
    public void defaultRuntimeAdminFailsClosedAndRollsBackRuntimeAssembly() {
        CloseCountingRegistry registry = new CloseCountingRegistry();
        CloseCountingTransportProvider transport = new CloseCountingTransportProvider("runtime-admin-missing-pin");

        MiddlewareException failure = assertThrows(MiddlewareException.class, () -> new MiddlewareRuntimeBuilder()
                .runtimeId("runtime-admin-missing-pin")
                .registry(registry)
                .addTransport(transport)
                .build());

        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, failure.code());
        assertFalse(failure.getMessage().contains("700003"));
        assertEquals(1, transport.closeCount());
        assertEquals(1, registry.closeCount());
    }

    @Test
    public void runtimeOwnedAdminStartupFailureDoesNotLeakSecretsAndRollsBack() {
        CloseCountingRegistry registry = new CloseCountingRegistry();
        CloseCountingTransportProvider transport = new CloseCountingTransportProvider("runtime-admin-bad-pin");
        NuggetAdminRuntimeConfiguration configuration = baseConfig(new InMemoryAuditSink(16))
                .pinFromDirectProcessArgument("runtime-secret-pin".toCharArray())
                .pinFromEnvironment("J7M_RUNTIME_PIN", ignored -> "other-secret")
                .build();

        MiddlewareException failure = assertThrows(MiddlewareException.class, () -> new MiddlewareRuntimeBuilder()
                .runtimeId("runtime-admin-bad-pin")
                .registry(registry)
                .addTransport(transport)
                .nuggetAdmin(configuration)
                .build());

        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, failure.code());
        assertFalse(failure.getMessage().contains("runtime-secret-pin"));
        assertFalse(failure.getMessage().contains("other-secret"));
        assertEquals(1, transport.closeCount());
        assertEquals(1, registry.closeCount());
    }

    @Test
    public void runtimeOwnedAdminCloseIsIdempotent() {
        CloseCountingRegistry registry = new CloseCountingRegistry();
        CloseCountingTransportProvider transport = new CloseCountingTransportProvider("runtime-admin-close");
        NuggetAdminRuntimeConfiguration configuration = baseConfig(new InMemoryAuditSink(16))
                .pinFromProgrammaticProvider("runtime-test", NuggetAdminStartupTest::runtimeAdminPin)
                .build();

        DefaultMiddlewareRuntime runtime = new MiddlewareRuntimeBuilder()
                .runtimeId("runtime-admin-close")
                .registry(registry)
                .addTransport(transport)
                .nuggetAdmin(configuration)
                .build();

        runtime.close();
        runtime.close();

        assertEquals(1, transport.closeCount());
        assertEquals(1, registry.closeCount());
    }

    @Test
    public void operationsRuntimePackageIsNotExported() throws Exception {
        Path descriptorPath = Path.of("../j7m-runtime/src/main/java/module-info.java");
        if (!Files.exists(descriptorPath)) {
            descriptorPath = Path.of("j7m-runtime/src/main/java/module-info.java");
        }
        String descriptor = Files.readString(descriptorPath);

        assertFalse(descriptor.contains("exports org.j7m.middleware.operations.runtime"));
        assertFalse(descriptor.contains("j7m.transport.grpc"));
        assertFalse(descriptor.contains("j7m.transport.rmi"));
        assertFalse(descriptor.toLowerCase(java.util.Locale.ROOT).contains("cli"));
        assertFalse(descriptor.toLowerCase(java.util.Locale.ROOT).contains("web"));
    }

    static NuggetAdminRuntimeConfiguration.Builder baseConfig(final InMemoryAuditSink audit) {
        AtomicLong nanos = new AtomicLong();
        return NuggetAdminRuntimeConfiguration.builder()
                .security(security(SecurityMode.DEV, audit))
                .clock(Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC))
                .monotonicTimeSource(nanos::get)
                .nuggetName("test-nugget");
    }

    static NuggetAdminService start(final NuggetAdminRuntimeConfiguration configuration) {
        return NuggetAdminRuntime.start(runtimeIdentity(), configuration);
    }

    static SecurityConfiguration security(final SecurityMode mode, final InMemoryAuditSink audit) {
        return new SecurityConfiguration(mode, new AllowAllAuthorizationPolicy(), audit, new DefaultRedactor(),
                false, true, 1024 * 1024, 1024, java.time.Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("test", "1", Map.of())));
    }

    static RuntimeIdentity runtimeIdentity() {
        return new RuntimeIdentity("runtime-test", "default", Map.of("purpose", "nugget-test"));
    }

    static NuggetAuthenticationRequest request(final String correlationId) {
        return NuggetAuthenticationRequest.of(correlationId, NuggetAuthenticationSource.LOCAL, "test-local", true, Instant.EPOCH);
    }

    private static char[] runtimeAdminPin() {
        return java.util.Arrays.copyOf(RUNTIME_ADMIN_PIN, RUNTIME_ADMIN_PIN.length);
    }

    private static long discoveryRecordCount(final Path directory) throws java.io.IOException {
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".j7m-discovery")).count();
        }
    }

    private static void deleteDirectoryContents(final Path directory) throws java.io.IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void trySetOwnerOnlyPermissions(final Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platforms are covered by runtime safe-degradation policy.
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void trySetOwnerOnlyDirectoryPermissions(final Path directory) {
        try {
            Files.setPosixFilePermissions(directory, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platforms are covered by runtime safe-degradation policy.
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static final class FailingSecureRandom extends SecureRandom {
        @Override
        public void nextBytes(final byte[] bytes) {
            throw new AssertionError("verifier creation should not be reached");
        }
    }

    private static final class CloseCountingTransportProvider implements TransportProvider {
        private final String id;
        private int closeCount;
        private boolean running;

        private CloseCountingTransportProvider(final String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return this.id;
        }

        @Override
        public TransportEndpoint start(final RuntimeTransportContext context) {
            this.running = true;
            return new TransportEndpoint(this.id, this.id + "://local");
        }

        @Override
        public ExportedObject export(final ObjectRef ref, final Object target, final ExportDescriptor descriptor) {
            return new ExportedObject(ref, descriptor);
        }

        @Override
        public void unexport(final ObjectRef ref) {
        }

        @Override
        public Object invoke(
                final ObjectRef target,
                final MethodDescriptor method,
                final List<EncodedValue> arguments,
                final InvocationOptions options) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "test transport does not invoke");
        }

        @Override
        public <T> T createProxy(final ObjectRef ref, final Class<T> api, final org.j7m.middleware.api.ProxyOptions options) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "test transport does not create proxies");
        }

        @Override
        public void close() {
            if (this.running) {
                this.closeCount++;
                this.running = false;
            }
        }

        private int closeCount() {
            return this.closeCount;
        }
    }

    private static final class CloseCountingRegistry implements RegistryClient {
        private int closeCount;
        private boolean closed;

        @Override
        public void bind(final RegistryRecord record) {
        }

        @Override
        public void unbind(final ObjectRef ref) {
        }

        @Override
        public Optional<RegistryRecord> lookup(final ObjectRef ref) {
            return Optional.empty();
        }

        @Override
        public List<RegistryRecord> list(final RegistryQuery query) {
            return List.of();
        }

        @Override
        public void heartbeat(final ObjectRef ref, final long leaseUntil) {
        }

        @Override
        public List<RegistryPeerStatus> peers(final PeerQuery query) {
            return List.of();
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                this.closeCount++;
            }
        }

        private int closeCount() {
            return this.closeCount;
        }
    }
}
