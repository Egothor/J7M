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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.annotations.Confidential;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.api.ObjectState;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.j7m.middleware.registry.api.RegistryRecord;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityContexts;
import org.j7m.middleware.security.api.SecurityMode;
import org.j7m.middleware.security.api.TransportSecurityLevel;
import org.j7m.middleware.security.api.TrustDomain;
import org.j7m.middleware.security.defaults.AllowAllAuthorizationPolicy;
import org.j7m.middleware.security.defaults.DefaultRedactor;
import org.j7m.middleware.security.defaults.DeterministicRegistryRecordSigner;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.j7m.middleware.security.defaults.MutableCredentialProvider;
import org.j7m.middleware.transport.api.MethodDescriptor;
import org.j7m.middleware.transport.grpc.GrpcSecurityOptions;
import org.j7m.middleware.transport.grpc.GrpcTestCertificateMaterial;
import org.j7m.middleware.transport.grpc.GrpcTlsConfiguration;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 3.1 security fidelity tests.
 */
public final class Phase31SecurityFidelityTest {
    @TempDir
    private Path temporaryDirectory;
    private final Map<String, GrpcTestCertificateMaterial> certificateMaterial;

    /**
     * Creates the test fixture.
     */
    public Phase31SecurityFidelityTest() {
        this.certificateMaterial = new HashMap<>();
    }

    @Test
    public void mtlsGrpcInvocationAuthenticatesPeerAndPreservesCallbacks() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        SecurityConfiguration security = security(SecurityMode.PRODUCTION, audit, true, false, false, Duration.ofSeconds(30L));
        GrpcTransportProvider serverTransport = new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(true, tls("server", "client")));
        GrpcTransportProvider clientTransport = new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(true, tls("client", "server")));
        try (DefaultMiddlewareRuntime server = runtime("mtls-server", store, serverTransport, security);
             DefaultMiddlewareRuntime client = runtime("mtls-client", store, clientTransport, security)) {
            SecurityContexts.run(context("publisher", true, TransportSecurityLevel.AUTHENTICATED_ENCRYPTED),
                    () -> server.export("secure", new SecureGreeterImpl("mtls"), SecureGreeter.class, export()));

            SecureGreeter greeter = SecurityContexts.call(context("lookup", true, TransportSecurityLevel.AUTHENTICATED_ENCRYPTED),
                    () -> client.lookup("secure", SecureGreeter.class, LookupOptions.defaults()));
            RecordingListener listener = new RecordingListener();
            SecurityContexts.run(context("caller", true, TransportSecurityLevel.AUTHENTICATED_ENCRYPTED), () -> {
                greeter.register(listener);
                greeter.fire("through-mtls");
            });

            assertEquals("mtls hello ada", SecurityContexts.call(context("caller", true, TransportSecurityLevel.AUTHENTICATED_ENCRYPTED),
                    () -> greeter.greet("ada")));
            assertEquals(List.of("through-mtls"), listener.messages);
            assertEquals("confidential", SecurityContexts.call(context("caller", true, TransportSecurityLevel.AUTHENTICATED_ENCRYPTED),
                    greeter::confidential));
            assertTrue(SecurityContexts.call(context("admin", true, TransportSecurityLevel.AUTHENTICATED_ENCRYPTED),
                    () -> Long.parseLong(server.status().security().get("auditEvents"))) > 0L);
        }
    }

    @Test
    public void mtlsServerRejectsInsecureClientAndConfidentialRejectsInsecureOverride() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        SecurityConfiguration secure = security(SecurityMode.PRODUCTION, new InMemoryAuditSink(32), true, false, false, Duration.ofSeconds(30L));
        GrpcTestCertificateMaterial clientMaterial = this.material("client");
        GrpcTlsConfiguration untrustedServerTls = new GrpcTlsConfiguration(clientMaterial.certificate(), clientMaterial.privateKey(),
                clientMaterial.certificate(), true, "default", "localhost");
        try (DefaultMiddlewareRuntime server = runtime("tls-only-server", store,
                new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(true, tls("server", "client"))), secure);
             DefaultMiddlewareRuntime untrustedClient = runtime("untrusted-client", store,
                     new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(true, untrustedServerTls)), developmentSecurity())) {
            SecurityContexts.run(context("publisher", true, TransportSecurityLevel.AUTHENTICATED_ENCRYPTED),
                    () -> server.export("secure", new SecureGreeterImpl("secure"), SecureGreeter.class, export()));

            MiddlewareException failure = assertThrows(MiddlewareException.class,
                    () -> untrustedClient.lookup("secure", SecureGreeter.class, LookupOptions.defaults()));
            assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, failure.code());
        }

        EmbeddedRegistryStore insecureStore = new EmbeddedRegistryStore();
        SecurityConfiguration productionInsecure = security(SecurityMode.PRODUCTION, new InMemoryAuditSink(32), false, true, false, Duration.ofSeconds(30L));
        try (DefaultMiddlewareRuntime server = runtime("confidential-server", insecureStore,
                new GrpcTransportProvider(GrpcSecurityOptions.insecureProductionOverride()), productionInsecure);
             DefaultMiddlewareRuntime client = runtime("confidential-client", insecureStore, new GrpcTransportProvider(), developmentSecurity())) {
            SecurityContexts.run(context("publisher", true, TransportSecurityLevel.TEST_AUTHENTICATED),
                    () -> server.export("secure", new SecureGreeterImpl("insecure"), SecureGreeter.class, export()));
            SecureGreeter greeter = SecurityContexts.call(context("lookup", true, TransportSecurityLevel.TEST_AUTHENTICATED),
                    () -> client.lookup("secure", SecureGreeter.class, LookupOptions.defaults()));

            MiddlewareException failure = assertThrows(MiddlewareException.class,
                    () -> SecurityContexts.call(context("caller", true, TransportSecurityLevel.TEST_AUTHENTICATED), greeter::confidential));
            assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, failure.code());
        }

        EmbeddedRegistryStore tlsNoPeerStore = new EmbeddedRegistryStore();
        GrpcTlsConfiguration serverNoPeerTls = tlsNoClientAuth("server-no-peer", "client-no-peer");
        GrpcTlsConfiguration clientNoCertificateTls = tlsNoClientAuth("client-no-peer", "server-no-peer");
        try (DefaultMiddlewareRuntime server = runtime("tls-no-peer-server", tlsNoPeerStore,
                new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(true, serverNoPeerTls)), secure);
             DefaultMiddlewareRuntime client = runtime("tls-no-peer-client", tlsNoPeerStore,
                     new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(true, clientNoCertificateTls)), secure)) {
            SecurityContexts.run(context("publisher", true, TransportSecurityLevel.AUTHENTICATED_ENCRYPTED),
                    () -> server.export("secure", new SecureGreeterImpl("tls-no-peer"), SecureGreeter.class, export()));
            SecureGreeter greeter = SecurityContexts.call(context("lookup", true, TransportSecurityLevel.AUTHENTICATED_ENCRYPTED),
                    () -> client.lookup("secure", SecureGreeter.class, LookupOptions.defaults()));

            MiddlewareException failure = assertThrows(MiddlewareException.class,
                    () -> SecurityContexts.call(context("caller", true, TransportSecurityLevel.AUTHENTICATED_ENCRYPTED), greeter::confidential));
            assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, failure.code());
        }
    }

    @Test
    public void replayAndTimestampValidationRejectSensitiveRemoteOperations() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        SecurityConfiguration replaySecurity = security(SecurityMode.DEV, new InMemoryAuditSink(64), false, true, true, Duration.ofMillis(200L));
        try (DefaultMiddlewareRuntime server = runtime("replay-server", store, new GrpcTransportProvider(), replaySecurity)) {
            ObjectRegistration<SecureGreeter> registration = server.export("secure", new SecureGreeterImpl("replay"), SecureGreeter.class, export());
            MethodDescriptor descriptor = new MethodDescriptor(SecureGreeter.class.getName(), "greet", List.of(String.class.getName()), String.class.getName(), null);
            SecurityContext context = context("caller", true, TransportSecurityLevel.TEST_AUTHENTICATED);
            InvocationOptions fixed = new InvocationOptions("fixed-invocation", Duration.ofSeconds(1L), context.toMetadata());

            Object first = server.decodeValue(server.dispatchTrusted(registration.ref(), descriptor,
                    List.of(server.encodeValue("one", String.class)), fixed, context), String.class);
            MiddlewareException replay = assertThrows(MiddlewareException.class,
                    () -> server.dispatchTrusted(registration.ref(), descriptor, List.of(server.encodeValue("two", String.class)), fixed, context));
            SecurityContext staleContext = new SecurityContext(new Principal("stale", "stale", Set.of(), Set.of()), "caller",
                    new TrustDomain("default"), new Credential("test", "1", Map.of()), "corr", "", "", true,
                    TransportSecurityLevel.TEST_AUTHENTICATED, Set.of(), Map.of(), Instant.now().minusSeconds(10L));
            MiddlewareException stale = assertThrows(MiddlewareException.class,
                    () -> server.dispatchTrusted(registration.ref(), descriptor, List.of(server.encodeValue("three", String.class)),
                            new InvocationOptions("stale-invocation", Duration.ofSeconds(1L), Map.of()), staleContext));
            Object fresh = server.decodeValue(server.dispatchTrusted(registration.ref(), descriptor,
                    List.of(server.encodeValue("fresh", String.class)),
                    new InvocationOptions("fresh-invocation", Duration.ofSeconds(1L), Map.of()), context), String.class);

            assertEquals("replay hello one", first);
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, replay.code());
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, stale.code());
            assertEquals("replay hello fresh", fresh);
            assertEquals("1", server.status().security().get("replayRejectedCount"));
            assertEquals("1", server.status().security().get("timestampRejectedCount"));
        }
    }

    @Test
    public void replayCachePressureRejectsNewEntriesWithoutEvictingLiveReplayMarkers() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        SecurityConfiguration replaySecurity = new SecurityConfiguration(SecurityMode.DEV, new AllowAllAuthorizationPolicy(), audit,
                new DefaultRedactor(), false, true, 1024 * 1024, 1024, Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("test", "1", Map.of())), true, Duration.ofSeconds(30L), 2, false);
        try (DefaultMiddlewareRuntime server = runtime("replay-cap-server", store, new GrpcTransportProvider(), replaySecurity)) {
            ObjectRegistration<SecureGreeter> registration = server.export("secure", new SecureGreeterImpl("replay-cap"), SecureGreeter.class, export());
            MethodDescriptor descriptor = new MethodDescriptor(SecureGreeter.class.getName(), "greet", List.of(String.class.getName()), String.class.getName(), null);
            SecurityContext context = context("caller", true, TransportSecurityLevel.TEST_AUTHENTICATED);

            Object first = server.decodeValue(server.dispatchTrusted(registration.ref(), descriptor,
                    List.of(server.encodeValue("one", String.class)), new InvocationOptions("live-one", Duration.ofSeconds(1L), Map.of()), context), String.class);
            Object second = server.decodeValue(server.dispatchTrusted(registration.ref(), descriptor,
                    List.of(server.encodeValue("two", String.class)), new InvocationOptions("live-two", Duration.ofSeconds(1L), Map.of()), context), String.class);
            MiddlewareException capacity = assertThrows(MiddlewareException.class,
                    () -> server.dispatchTrusted(registration.ref(), descriptor,
                            List.of(server.encodeValue("three", String.class)), new InvocationOptions("live-three", Duration.ofSeconds(1L), Map.of()), context));
            MiddlewareException replay = assertThrows(MiddlewareException.class,
                    () -> server.dispatchTrusted(registration.ref(), descriptor,
                            List.of(server.encodeValue("again", String.class)), new InvocationOptions("live-one", Duration.ofSeconds(1L), Map.of()), context));

            assertEquals("replay-cap hello one", first);
            assertEquals("replay-cap hello two", second);
            assertEquals(MiddlewareErrorCode.OVERLOADED, capacity.code());
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, replay.code());
        }
    }

    @Test
    public void registryTrustPolicyRejectsStrictAndDowngradesLenientRecords() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("trust-key");
        InMemoryAuditSink audit = new InMemoryAuditSink(32);
        RegistryRecord unsigned = record("unsigned");
        EmbeddedRegistryClient strict = new EmbeddedRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC(), signer, true, audit);

        assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, assertThrows(MiddlewareException.class,
                () -> strict.admitReceivedRecord(unsigned, "default")).code());

        RegistryRecord base = record("signed");
        RegistryRecord signed = base.withAuthentication(signer.sign(base));
        RegistryRecord admittedSigned = strict.admitReceivedRecord(signed, "default");
        assertEquals("trusted", admittedSigned.authMetadata().get("trustStatus"));
        strict.bind(admittedSigned);
        assertEquals(1, strict.list(new RegistryQuery("svc", List.of(SecureGreeter.class.getName()), Map.of(), "default", System.currentTimeMillis(), 10)).size());

        RegistryRecord tampered = base.withAuthentication(Map.of("keyId", "trust-key", "algorithm", "J7M-TEST-SHA256", "signature", "bad"));
        assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, assertThrows(MiddlewareException.class,
                () -> strict.admitReceivedRecord(tampered, "default")).code());

        EmbeddedRegistryClient lenient = new EmbeddedRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC(), signer, false, audit);
        RegistryRecord downgradedUnsigned = lenient.admitReceivedRecord(unsigned, "default");
        RegistryRecord downgradedTampered = lenient.admitReceivedRecord(tampered, "default");
        assertEquals("untrusted", downgradedUnsigned.authMetadata().get("trustStatus"));
        assertEquals("untrusted", downgradedTampered.authMetadata().get("trustStatus"));
        assertTrue(lenient.list(new RegistryQuery("svc", List.of(SecureGreeter.class.getName()), Map.of(), "default", System.currentTimeMillis(), 10)).isEmpty());
        assertEquals(0, lenient.activeRecordCount());
        assertTrue(audit.recentEvents().stream().anyMatch(event -> event.action().contains("registry-record-rejected")
                || event.action().contains("registry-record-downgraded")));
    }

    @Test
    public void downgradedEmbeddedRecordIsNotReturnedAsRuntimeCandidate() {
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("runtime-trust-key");
        InMemoryAuditSink audit = new InMemoryAuditSink(16);
        EmbeddedRegistryClient registry = new EmbeddedRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC(), signer, false, audit);
        RegistryRecord base = record("runtime-downgraded");
        RegistryRecord tampered = base.withAuthentication(Map.of("keyId", "runtime-trust-key", "algorithm", "J7M-TEST-SHA256", "signature", "bad"));
        RegistryRecord downgraded = registry.admitReceivedRecord(tampered, "default");
        assertEquals("untrusted", downgraded.authMetadata().get("trustStatus"));

        try (DefaultMiddlewareRuntime runtime = new MiddlewareRuntimeBuilder()
                .runtimeId("runtime-downgraded-client")
                .registry(registry)
                .security(developmentSecurity())
                .addTransport(new GrpcTransportProvider())
                .disableNuggetAdmin()
                .build()) {
            List<ObjectRef> refs = runtime.list("svc", List.of(SecureGreeter.class.getName()), Map.of(), LookupOptions.defaults());

            assertTrue(refs.isEmpty());
            assertEquals(0, registry.activeRecordCount());
        }
    }

    @Test
    public void credentialRotationNotifiesGrpcRefreshAndPostRotationCallsStillWork() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        MutableCredentialProvider provider = new MutableCredentialProvider(new Credential("x509", "1", Map.of("fingerprint", "one")));
        InMemoryAuditSink audit = new InMemoryAuditSink(32);
        SecurityConfiguration security = new SecurityConfiguration(SecurityMode.PRODUCTION, new AllowAllAuthorizationPolicy(), audit,
                new DefaultRedactor(), true, false, 1024 * 1024, 1024, Duration.ofSeconds(2L), provider,
                false, Duration.ofSeconds(30L), 4096, true);
        GrpcTransportProvider serverTransport = new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(true, tls("server", "client")));
        GrpcTransportProvider clientTransport = new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(true, tls("client", "server")));
        provider.addRotationListener(snapshot -> clientTransport.refreshCredentials());
        try (DefaultMiddlewareRuntime server = runtime("rotation-server", store, serverTransport, security);
             DefaultMiddlewareRuntime client = runtime("rotation-client", store, clientTransport, security)) {
            SecurityContexts.run(context("publisher", true, TransportSecurityLevel.AUTHENTICATED_ENCRYPTED),
                    () -> server.export("secure", new SecureGreeterImpl("rotation"), SecureGreeter.class, export()));
            SecureGreeter greeter = SecurityContexts.call(context("lookup", true, TransportSecurityLevel.AUTHENTICATED_ENCRYPTED),
                    () -> client.lookup("secure", SecureGreeter.class, LookupOptions.defaults()));

            provider.rotate(new Credential("x509", "2", Map.of("fingerprint", "two")));

            assertEquals(1L, clientTransport.credentialRefreshCount());
            assertEquals("rotation hello bob", SecurityContexts.call(context("caller", true, TransportSecurityLevel.AUTHENTICATED_ENCRYPTED),
                    () -> greeter.greet("bob")));
            assertTrue(audit.recentEvents().stream().anyMatch(event -> event.action().equals("credential-rotated")));
        }
    }

    private GrpcTlsConfiguration tls(final String self, final String trustedPeer) {
        GrpcTestCertificateMaterial own = this.material(self);
        GrpcTestCertificateMaterial peer = this.material(trustedPeer);
        return new GrpcTlsConfiguration(own.certificate(), own.privateKey(), peer.certificate(), true, "default", "localhost");
    }

    private GrpcTlsConfiguration tlsNoClientAuth(final String self, final String trustedPeer) {
        GrpcTestCertificateMaterial own = this.material(self);
        GrpcTestCertificateMaterial peer = this.material(trustedPeer);
        return new GrpcTlsConfiguration(own.certificate(), own.privateKey(), peer.certificate(), false, "default", "localhost");
    }

    private GrpcTestCertificateMaterial material(final String name) {
        return this.certificateMaterial.computeIfAbsent(name,
                key -> GrpcTestCertificateMaterial.generate(this.temporaryDirectory.resolve(key), "localhost"));
    }

    private static DefaultMiddlewareRuntime runtime(
            final String id,
            final EmbeddedRegistryStore store,
            final GrpcTransportProvider provider,
            final SecurityConfiguration security) {
        return new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                .security(security)
                .addTransport(provider)
                .disableNuggetAdmin()
                .build();
    }

    private static SecurityConfiguration developmentSecurity() {
        return security(SecurityMode.DEV, new InMemoryAuditSink(32), false, true, false, Duration.ofSeconds(30L));
    }

    private static SecurityConfiguration security(
            final SecurityMode mode,
            final InMemoryAuditSink audit,
            final boolean requireAuthentication,
            final boolean allowInsecure,
            final boolean replay,
            final Duration skew) {
        return new SecurityConfiguration(mode, new AllowAllAuthorizationPolicy(), audit, new DefaultRedactor(), requireAuthentication,
                allowInsecure, 1024 * 1024, 1024, Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("test", "1", Map.of())), replay, skew, 64, mode == SecurityMode.PRODUCTION);
    }

    private static SecurityContext context(final String principal, final boolean authenticated, final TransportSecurityLevel level) {
        return new SecurityContext(new Principal(principal, principal, Set.of("admin", "runtime"), Set.of()), "caller",
                new TrustDomain("default"), new Credential("test", "1", Map.of()), "corr", "", "", authenticated, level,
                Set.of("admin", "runtime"), Map.of(), Instant.now());
    }

    private static ExportOptions export() {
        return new ExportOptions(false, "grpc", "default", List.of(), Map.of(), Duration.ofSeconds(30L), Duration.ofSeconds(10L));
    }

    private static RegistryRecord record(final String id) {
        ObjectRef ref = new ObjectRef(id, "svc/" + id, "svc", "runtime-a", "default", List.of(SecureGreeter.class.getName()),
                "grpc", "grpc://127.0.0.1:1", System.currentTimeMillis() + 30_000L, 1L, Map.of());
        return new RegistryRecord(ref, Map.of(), System.currentTimeMillis(), System.currentTimeMillis(), ref.leaseUntil(), ObjectState.EXECUTED);
    }

    /**
     * Secured demo/test API.
     */
    public interface SecureGreeter {
        /**
         * Greets a caller.
         */
        String greet(String name);

        /**
         * Confidential method.
         */
        @Confidential
        String confidential();

        /**
         * Registers a listener callback.
         */
        void register(Listener listener);

        /**
         * Emits a listener event.
         */
        void fire(String message);
    }

    /**
     * Callback API.
     */
    public interface Listener {
        /**
         * Receives one event.
         */
        void onMessage(String message);
    }

    private static final class SecureGreeterImpl implements SecureGreeter {
        private final String name;
        private Listener listener;

        private SecureGreeterImpl(final String name) {
            this.name = name;
        }

        @Override
        public String greet(final String caller) {
            return this.name + " hello " + caller;
        }

        @Override
        public String confidential() {
            return "confidential";
        }

        @Override
        public void register(final Listener newListener) {
            this.listener = newListener;
        }

        @Override
        public void fire(final String message) {
            this.listener.onMessage(message);
        }
    }

    private static final class RecordingListener implements Listener {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void onMessage(final String message) {
            this.messages.add(message);
        }
    }
}
