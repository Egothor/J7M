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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.admin.RuntimeAdmin;
import org.j7m.middleware.annotations.NoRemoteExport;
import org.j7m.middleware.annotations.RequiresRole;
import org.j7m.middleware.annotations.RequiresTrustDomain;
import org.j7m.middleware.annotations.Sensitive;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.api.ObjectState;
import org.j7m.middleware.api.ProxyOptions;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
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
import org.j7m.middleware.security.defaults.DenyAllAuthorizationPolicy;
import org.j7m.middleware.security.defaults.DeterministicRegistryRecordSigner;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.j7m.middleware.security.defaults.MutableCredentialProvider;
import org.j7m.middleware.security.defaults.RuleBasedAuthorizationPolicy;
import org.j7m.middleware.transport.grpc.GrpcSecurityOptions;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.j7m.middleware.transport.rmi.RmiTransportProvider;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 security and hardening acceptance coverage.
 */
public final class Phase3SecurityIntegrationTest {
    @Test
    public void denyPolicyRejectsExportLookupListAndStatusAndAudits() {
        InMemoryAuditSink audit = new InMemoryAuditSink(16);
        SecurityConfiguration security = security(new DenyAllAuthorizationPolicy(), audit, false, 1024 * 1024);
        try (DefaultMiddlewareRuntime runtime = runtime("deny-runtime", new EmbeddedRegistryStore(), "grpc", security)) {
            MiddlewareException export = assertThrows(MiddlewareException.class,
                    () -> runtime.export("secure", new SecureServiceImpl(), SecureService.class, export("grpc")));
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, export.code());

            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED,
                    assertThrows(MiddlewareException.class, () -> runtime.lookup("secure", SecureService.class, LookupOptions.defaults())).code());
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED,
                    assertThrows(MiddlewareException.class, () -> runtime.list("secure", List.of(SecureService.class.getName()), Map.of(), LookupOptions.defaults())).code());
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED,
                    assertThrows(MiddlewareException.class, runtime::status).code());
        }
        assertTrue(audit.eventCount() >= 4L);
        assertTrue(audit.recentEvents().stream().anyMatch(event -> event.decision().equals("deny")));
    }

    @Test
    public void allowPolicyPermitsExportLookupInvokeAndAdminStatus() {
        SecurityConfiguration security = security(new AllowAllAuthorizationPolicy(), new InMemoryAuditSink(32), false, 1024 * 1024);
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime server = runtime("allow-server", store, "grpc", security);
             DefaultMiddlewareRuntime client = runtime("allow-client", store, "grpc", security)) {
            server.export("secure", new SecureServiceImpl(), SecureService.class, export("grpc"));

            SecureService service = client.lookup("secure", SecureService.class, LookupOptions.defaults());

            assertEquals("hello bob", service.greet("bob"));
            assertTrue(new RuntimeAdmin(server).formatStatus().contains("securityMode=DEV"));
        }
    }

    @Test
    public void serverSideInvokeAuthorizationRunsBeforeTargetMethod() {
        InMemoryAuditSink audit = new InMemoryAuditSink(32);
        SecurityConfiguration serverSecurity = security(
                new RuleBasedAuthorizationPolicy(List.of(new RuleBasedAuthorizationPolicy.Rule(Set.of(), Set.of(org.j7m.middleware.security.api.PermissionAction.EXPORT), ""))),
                audit, false, 1024 * 1024);
        SecurityConfiguration clientSecurity = security(new AllowAllAuthorizationPolicy(), new InMemoryAuditSink(16), false, 1024 * 1024);
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        CountingServiceImpl target = new CountingServiceImpl();
        try (DefaultMiddlewareRuntime server = runtime("auth-server", store, "grpc", serverSecurity);
             DefaultMiddlewareRuntime client = runtime("auth-client", store, "grpc", clientSecurity)) {
            server.export("counting", target, CountingService.class, export("grpc"));
            CountingService service = client.lookup("counting", CountingService.class, LookupOptions.defaults());

            MiddlewareException failure = assertThrows(MiddlewareException.class, service::touch);

            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, failure.code());
            assertEquals(0, target.invocations.get());
            assertTrue(audit.recentEvents().stream().anyMatch(event -> event.action().equals("invoke") && event.decision().equals("deny")));
        }
    }

    @Test
    public void objectRefAloneIsNotEnoughWithoutAuthentication() {
        SecurityConfiguration serverSecurity = security(new AllowAllAuthorizationPolicy(), new InMemoryAuditSink(32), true, 1024 * 1024);
        SecurityConfiguration clientSecurity = security(new AllowAllAuthorizationPolicy(), new InMemoryAuditSink(16), false, 1024 * 1024);
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime server = runtime("objectref-server", store, "grpc", serverSecurity);
             DefaultMiddlewareRuntime client = runtime("objectref-client", store, "grpc", clientSecurity)) {
            ObjectRegistration<CountingService> registration = SecurityContexts.call(system("objectref-server"),
                    () -> server.export("counting", new CountingServiceImpl(), CountingService.class, export("grpc")));
            CountingService service = client.proxyFor(registration.ref(), CountingService.class);

            MiddlewareException failure = assertThrows(MiddlewareException.class, service::touch);

            assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, failure.code());
        }
    }

    @Test
    public void securityAnnotationsEnforceRoleTrustDomainAndRedactSensitiveArguments() {
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        SecurityConfiguration security = security(new AllowAllAuthorizationPolicy(), audit, false, 1024 * 1024);
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime server = runtime("annotation-server", store, "grpc", security);
             DefaultMiddlewareRuntime client = runtime("annotation-client", store, "grpc", security)) {
            server.export("secure", new SecureServiceImpl(), SecureService.class, export("grpc"));
            SecureService service = client.lookup("secure", SecureService.class, LookupOptions.defaults());

            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED,
                    assertThrows(MiddlewareException.class, service::adminOnly).code());
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED,
                    assertThrows(MiddlewareException.class,
                            () -> SecurityContexts.call(context("alice", "trusted", true, Set.of("admin")), service::adminOnly)).code());
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED,
                    assertThrows(MiddlewareException.class,
                            () -> SecurityContexts.call(context("alice", "trusted", true, Set.of()), service::trustedOnly)).code());

            assertEquals("stored", service.storeSecret("open-sesame"));
        }
        String auditText = audit.recentEvents().toString();
        assertTrue(auditText.contains("<redacted>"));
        assertFalse(auditText.contains("open-sesame"));
    }

    @Test
    public void grpcProductionModeRejectsInsecureTransportUnlessExplicitlyOverridden() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        MiddlewareRuntimeBuilder strict = builder("grpc-prod-strict", store, security(new AllowAllAuthorizationPolicy(), new InMemoryAuditSink(8), false, 1024 * 1024));
        strict.addTransport(new GrpcTransportProvider(GrpcSecurityOptions.production()));

        MiddlewareException failure = assertThrows(MiddlewareException.class, strict::build);
        assertEquals(MiddlewareErrorCode.TRANSPORT_ERROR, failure.code());

        try (DefaultMiddlewareRuntime runtime = builder("grpc-prod-override", store,
                security(new AllowAllAuthorizationPolicy(), new InMemoryAuditSink(8), false, 1024 * 1024))
                .addTransport(new GrpcTransportProvider(GrpcSecurityOptions.insecureProductionOverride()))
                .build()) {
            assertTrue(runtime.status().transports().stream().anyMatch(status -> status.transportId().equals("grpc")));
        }
    }

    @Test
    public void authenticatedGrpcContextSucceedsAndUnauthenticatedProtectedCallFails() {
        SecurityConfiguration serverSecurity = security(new AllowAllAuthorizationPolicy(), new InMemoryAuditSink(32), true, 1024 * 1024);
        SecurityConfiguration clientSecurity = security(new AllowAllAuthorizationPolicy(), new InMemoryAuditSink(16), false, 1024 * 1024);
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime server = runtime("grpc-auth-server", store, "grpc", serverSecurity);
             DefaultMiddlewareRuntime client = runtime("grpc-auth-client", store, "grpc", clientSecurity)) {
            SecurityContexts.run(system("grpc-auth-server"), () -> server.export("secure", new SecureServiceImpl(), SecureService.class, export("grpc")));
            SecureService service = client.lookup("secure", SecureService.class, LookupOptions.defaults());

            assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, assertThrows(MiddlewareException.class, () -> service.greet("anon")).code());
            assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, assertThrows(MiddlewareException.class,
                    () -> SecurityContexts.call(context("ada", "default", true, Set.of()), () -> service.greet("ada"))).code());
        }
    }

    @Test
    public void rmiInvocationParticipatesInAuthorization() {
        SecurityConfiguration serverSecurity = security(
                new RuleBasedAuthorizationPolicy(List.of(new RuleBasedAuthorizationPolicy.Rule(Set.of(), Set.of(org.j7m.middleware.security.api.PermissionAction.EXPORT), ""))),
                new InMemoryAuditSink(32), false, 1024 * 1024);
        SecurityConfiguration clientSecurity = security(new AllowAllAuthorizationPolicy(), new InMemoryAuditSink(16), false, 1024 * 1024);
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        CountingServiceImpl target = new CountingServiceImpl();
        try (DefaultMiddlewareRuntime server = runtime("rmi-auth-server", store, "rmi", serverSecurity);
             DefaultMiddlewareRuntime client = runtime("rmi-auth-client", store, "rmi", clientSecurity)) {
            server.export("counting", target, CountingService.class, export("rmi"));
            CountingService service = client.lookup("counting", CountingService.class, LookupOptions.defaults());

            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, assertThrows(MiddlewareException.class, service::touch).code());
            assertEquals(0, target.invocations.get());
        }
    }

    @Test
    public void rmiDispatchDoesNotTrustForgedCallerMetadata() {
        SecurityConfiguration security = security(new AllowAllAuthorizationPolicy(), new InMemoryAuditSink(32), false, 1024 * 1024);
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        RmiTransportProvider serverTransport = new RmiTransportProvider();
        RmiTransportProvider clientTransport = new RmiTransportProvider();
        try (DefaultMiddlewareRuntime server = builder("rmi-context-server", store, security).addTransport(serverTransport).build();
             DefaultMiddlewareRuntime client = builder("rmi-context-client", store, security).addTransport(clientTransport).build()) {
            ObjectRegistration<CallerContextService> registration = server.export("caller-context", new CallerContextServiceImpl(),
                    CallerContextService.class, export("rmi"));
            CallerContextService service = clientTransport.createProxy(registration.ref(), CallerContextService.class,
                    new ProxyOptions(Duration.ofSeconds(2L), Map.of(
                            "sec.principal", "rmi-admin",
                            "sec.roles", "admin",
                            "sec.authenticated", "true")));

            MiddlewareException failure = assertThrows(MiddlewareException.class, service::currentPrincipal);
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, failure.code());
        }

        SecurityConfiguration authenticatedSecurity = security(new AllowAllAuthorizationPolicy(), new InMemoryAuditSink(32), true, 1024 * 1024);
        RmiTransportProvider authenticatedServerTransport = new RmiTransportProvider();
        RmiTransportProvider authenticatedClientTransport = new RmiTransportProvider();
        try (DefaultMiddlewareRuntime server = builder("rmi-auth-context-server", store, authenticatedSecurity).addTransport(authenticatedServerTransport).build();
             DefaultMiddlewareRuntime client = builder("rmi-auth-context-client", store, security).addTransport(authenticatedClientTransport).build()) {
            ObjectRegistration<SecureService> registration = SecurityContexts.call(system("rmi-auth-context-server"),
                    () -> server.export("secure-rmi-context", new SecureServiceImpl(), SecureService.class, export("rmi")));
            SecureService service = authenticatedClientTransport.createProxy(registration.ref(), SecureService.class,
                    new ProxyOptions(Duration.ofSeconds(2L), Map.of(
                            "sec.principal", "authenticated-forgery",
                            "sec.authenticated", "true")));

            MiddlewareException failure = assertThrows(MiddlewareException.class, () -> service.greet("forged"));
            assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, failure.code());
        }
    }

    @Test
    public void registryRecordSigningAcceptsValidAndRejectsTamperedMetadata() {
        ObjectRef ref = new ObjectRef("object-1", "svc/object-1", "svc", "runtime-a", "default",
                List.of(SecureService.class.getName()), "grpc", "grpc://127.0.0.1:1", 1000L, 1L, Map.of());
        RegistryRecord record = new RegistryRecord(ref, Map.of(), 1L, 1L, 1000L, ObjectState.EXECUTED);
        DeterministicRegistryRecordSigner signer = new DeterministicRegistryRecordSigner("key-a");
        RegistryRecord signed = record.withAuthentication(signer.sign(record));

        assertTrue(signer.verify(signed, signed.authMetadata()));
        assertFalse(signer.verify(signed, Map.of("keyId", "key-a", "signature", "bad")));
    }

    @Test
    public void auditSinkIsBoundedAndCredentialRotationIsAudited() {
        InMemoryAuditSink bounded = new InMemoryAuditSink(2);
        bounded.record(new org.j7m.middleware.security.api.AuditEvent(Instant.EPOCH, "rt", "a", "one", "r", "allow", "p", "", "", "", "", "", "default", Map.of()));
        bounded.record(new org.j7m.middleware.security.api.AuditEvent(Instant.EPOCH, "rt", "a", "two", "r", "allow", "p", "", "", "", "", "", "default", Map.of()));
        bounded.record(new org.j7m.middleware.security.api.AuditEvent(Instant.EPOCH, "rt", "a", "three", "r", "allow", "p", "", "", "", "", "", "default", Map.of()));
        assertEquals(2, bounded.recentEvents().size());
        assertEquals("two", bounded.recentEvents().getFirst().action());

        InMemoryAuditSink audit = new InMemoryAuditSink(8);
        MutableCredentialProvider provider = new MutableCredentialProvider(new Credential("test", "1", Map.of()));
        SecurityConfiguration security = new SecurityConfiguration(SecurityMode.DEV, new AllowAllAuthorizationPolicy(), audit,
                new DefaultRedactor(), false, true, 1024 * 1024, 1024, Duration.ofSeconds(2L), provider);
        try (DefaultMiddlewareRuntime ignored = runtime("credential-runtime", new EmbeddedRegistryStore(), "grpc", security)) {
            provider.rotate(new Credential("test", "2", Map.of()));
        }
        assertTrue(audit.recentEvents().stream().anyMatch(event -> event.action().equals("credential-rotated")));
    }

    @Test
    public void payloadLimitRejectsOversizeGrpcInvocation() {
        SecurityConfiguration serverSecurity = security(new AllowAllAuthorizationPolicy(), new InMemoryAuditSink(32), false, 32);
        SecurityConfiguration clientSecurity = security(new AllowAllAuthorizationPolicy(), new InMemoryAuditSink(16), false, 1024 * 1024);
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime server = runtime("payload-server", store, "grpc", serverSecurity);
             DefaultMiddlewareRuntime client = runtime("payload-client", store, "grpc", clientSecurity)) {
            server.export("secure", new SecureServiceImpl(), SecureService.class, export("grpc"));
            SecureService service = client.lookup("secure", SecureService.class, LookupOptions.defaults());

            MiddlewareException failure = assertThrows(MiddlewareException.class,
                    () -> service.greet("this-payload-is-longer-than-the-configured-inbound-limit"));

            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, failure.code());
        }
    }

    @Test
    public void noRemoteExportPreventsCallbackReferenceEncoding() {
        SecurityConfiguration security = security(new AllowAllAuthorizationPolicy(), new InMemoryAuditSink(16), false, 1024 * 1024);
        try (DefaultMiddlewareRuntime runtime = runtime("no-export-runtime", new EmbeddedRegistryStore(), "grpc", security)) {
            MiddlewareException failure = assertThrows(MiddlewareException.class,
                    () -> runtime.encodeValue(new LocalOnlyListenerImpl(), LocalOnlyListener.class));
            assertEquals(MiddlewareErrorCode.CODEC_ERROR, failure.code());
        }
    }

    private static DefaultMiddlewareRuntime runtime(
            final String id,
            final EmbeddedRegistryStore store,
            final String transport,
            final SecurityConfiguration security) {
        MiddlewareRuntimeBuilder builder = builder(id, store, security);
        if ("grpc".equals(transport)) {
            builder.addTransport(new GrpcTransportProvider());
        } else if ("rmi".equals(transport)) {
            builder.addTransport(new RmiTransportProvider());
        } else {
            throw new IllegalArgumentException(transport);
        }
        return builder.build();
    }

    private static MiddlewareRuntimeBuilder builder(final String id, final EmbeddedRegistryStore store, final SecurityConfiguration security) {
        return new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                .clock(Clock.systemUTC())
                .security(security)
                .disableNuggetAdmin();
    }

    private static SecurityConfiguration security(
            final org.j7m.middleware.security.api.AuthorizationPolicy policy,
            final InMemoryAuditSink audit,
            final boolean requireAuthentication,
            final int maxInboundBytes) {
        return new SecurityConfiguration(SecurityMode.DEV, policy, audit, new DefaultRedactor(), requireAuthentication,
                true, maxInboundBytes, 1024, Duration.ofSeconds(2L), new MutableCredentialProvider(new Credential("test", "1", Map.of())));
    }

    private static ExportOptions export(final String transport) {
        return new ExportOptions(false, transport, "default", List.of(), Map.of(), Duration.ofSeconds(30L), Duration.ofSeconds(10L));
    }

    private static SecurityContext system(final String runtimeId) {
        return new SecurityContext(Principal.runtime(runtimeId), runtimeId, new TrustDomain("default"), new Credential("test", "1", Map.of()),
                "corr", "", "", true, TransportSecurityLevel.TEST_AUTHENTICATED, Set.of("runtime", "admin"), Map.of(), Instant.now());
    }

    private static SecurityContext context(final String principal, final String trustDomain, final boolean authenticated, final Set<String> roles) {
        return new SecurityContext(new Principal(principal, principal, roles, Set.of()), "caller", new TrustDomain(trustDomain),
                new Credential("test", "1", Map.of()), "corr", "", "", authenticated, TransportSecurityLevel.TEST_AUTHENTICATED,
                roles, Map.of(), Instant.now());
    }

    /**
     * Test secured API.
     */
    public interface SecureService {
        /**
         * Basic method.
         */
        String greet(String name);

        /**
         * Role-protected method.
         */
        @RequiresRole("admin")
        String adminOnly();

        /**
         * Trust-domain-protected method.
         */
        @RequiresTrustDomain("trusted")
        String trustedOnly();

        /**
         * Stores a sensitive value.
         */
        String storeSecret(@Sensitive String secret);
    }

    private static final class SecureServiceImpl implements SecureService {
        @Override
        public String greet(final String name) {
            return "hello " + name;
        }

        @Override
        public String adminOnly() {
            return "admin";
        }

        @Override
        public String trustedOnly() {
            return "trusted";
        }

        @Override
        public String storeSecret(final String secret) {
            return "stored";
        }
    }

    /**
     * Counting API used to verify pre-invocation denial.
     */
    public interface CountingService {
        /**
         * Mutates invocation counter.
         */
        void touch();
    }

    private static final class CountingServiceImpl implements CountingService {
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public void touch() {
            this.invocations.incrementAndGet();
        }
    }

    /**
     * API used to verify RMI trusted caller context propagation.
     */
    public interface CallerContextService {
        /**
         * Returns the trusted caller principal after authorization.
         */
        @RequiresRole("admin")
        String currentPrincipal();

    }

    private static final class CallerContextServiceImpl implements CallerContextService {
        @Override
        public String currentPrincipal() {
            SecurityContext context = SecurityContexts.current();
            return context == null ? "" : context.principal().id();
        }
    }

    /**
     * Callback API that must never be auto-exported.
     */
    @NoRemoteExport
    public interface LocalOnlyListener {
        /**
         * Callback.
         */
        void onMessage(String message);
    }

    private static final class LocalOnlyListenerImpl implements LocalOnlyListener {
        @Override
        public void onMessage(final String message) {
        }
    }
}
