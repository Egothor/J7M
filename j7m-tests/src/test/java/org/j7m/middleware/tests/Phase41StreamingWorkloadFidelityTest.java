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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.annotations.Async;
import org.j7m.middleware.annotations.Confidential;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.OneWay;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.scheduler.InMemorySchedulerOptions;
import org.j7m.middleware.scheduler.InMemoryWorkloadScheduler;
import org.j7m.middleware.security.api.AuthorizationDecision;
import org.j7m.middleware.security.api.AuthorizationPolicy;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.PermissionAction;
import org.j7m.middleware.security.api.PermissionRequest;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityContexts;
import org.j7m.middleware.security.api.SecurityMode;
import org.j7m.middleware.security.api.TransportSecurityLevel;
import org.j7m.middleware.security.api.TrustDomain;
import org.j7m.middleware.security.defaults.AllowAllAuthorizationPolicy;
import org.j7m.middleware.security.defaults.DefaultRedactor;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.j7m.middleware.security.defaults.MutableCredentialProvider;
import org.j7m.middleware.transport.api.MethodDescriptor;
import org.j7m.middleware.transport.api.StreamingTransportProvider;
import org.j7m.middleware.transport.grpc.GrpcSecurityOptions;
import org.j7m.middleware.transport.grpc.GrpcTestCertificateMaterial;
import org.j7m.middleware.transport.grpc.GrpcTlsConfiguration;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.j7m.middleware.transport.rmi.RmiTransportProvider;
import org.j7m.middleware.workload.api.FanOutRequest;
import org.j7m.middleware.workload.api.FanOutResult;
import org.j7m.middleware.workload.api.FencingToken;
import org.j7m.middleware.workload.api.LeaseOwner;
import org.j7m.middleware.workload.api.ScatterGatherRequest;
import org.j7m.middleware.workload.api.ScatterGatherResult;
import org.j7m.middleware.workload.api.StreamHandle;
import org.j7m.middleware.workload.api.TaskDescriptor;
import org.j7m.middleware.workload.api.TaskLease;
import org.j7m.middleware.workload.api.TaskOptions;
import org.j7m.middleware.workload.api.TaskProgress;
import org.j7m.middleware.workload.api.TaskRef;
import org.j7m.middleware.workload.api.TaskSubmission;
import org.j7m.middleware.workload.api.WorkloadMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 4.1 streaming and remote workload fidelity tests.
 */
public final class Phase41StreamingWorkloadFidelityTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    public void grpcServerClientAndBidirectionalStreamingUseRealTransport() throws Exception {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        GrpcTransportProvider serverTransport = new GrpcTransportProvider();
        GrpcTransportProvider clientTransport = new GrpcTransportProvider();
        try (DefaultMiddlewareRuntime server = runtime("grpc-stream-server", store, serverTransport, security(new InMemoryAuditSink(64), allowAuth(), false));
             DefaultMiddlewareRuntime client = runtime("grpc-stream-client", store, clientTransport, security(new InMemoryAuditSink(64), allowAuth(), false))) {
            ObjectRegistration<StreamingApi> registration = server.export("streaming", new StreamingApiImpl(), StreamingApi.class, export("grpc"));
            StreamingTransportProvider streaming = clientTransport;

            Method serverMethod = StreamingApi.class.getMethod("serverItems");
            try (StreamHandle<Object> stream = streaming.serverStream(registration.ref(), MethodDescriptor.fromMethod(serverMethod),
                    List.of(), InvocationOptions.defaults(), String.class)) {
                assertEquals("one", stream.poll(Duration.ofSeconds(2L)).orElseThrow());
                assertEquals("two", stream.poll(Duration.ofSeconds(2L)).orElseThrow());
                assertFalse(stream.poll(Duration.ofMillis(200L)).isPresent());
            }

            Method aggregate = StreamingApi.class.getMethod("aggregate", List.class);
            Object sum = streaming.clientStream(registration.ref(), MethodDescriptor.fromMethod(aggregate),
                    List.of(client.encodeValue("a", String.class), client.encodeValue("b", String.class)), InvocationOptions.defaults());
            assertEquals("a,b", sum);

            Method echo = StreamingApi.class.getMethod("echo", String.class);
            try (StreamHandle<Object> bidi = streaming.bidirectionalStream(registration.ref(), MethodDescriptor.fromMethod(echo),
                    List.of(client.encodeValue("x", String.class), client.encodeValue("y", String.class)), InvocationOptions.defaults(), String.class)) {
                assertEquals("echo-x", bidi.poll(Duration.ofSeconds(2L)).orElseThrow());
                assertEquals("echo-y", bidi.poll(Duration.ofSeconds(2L)).orElseThrow());
            }

            Method refs = StreamingApi.class.getMethod("refs", ObjectRef.class);
            try (StreamHandle<Object> refStream = streaming.serverStream(registration.ref(), MethodDescriptor.fromMethod(refs),
                    List.of(client.encodeValue(registration.ref(), ObjectRef.class)), InvocationOptions.defaults(), ObjectRef.class)) {
                Object value = refStream.poll(Duration.ofSeconds(2L)).orElseThrow();
                assertInstanceOf(ObjectRef.class, value);
                assertEquals(registration.ref().objectId(), ((ObjectRef) value).objectId());
            }

            assertTrue(serverTransport.serverStreamCount() == 0L);
            assertEquals(2L, clientTransport.serverStreamCount());
            assertEquals(1L, clientTransport.clientStreamCount());
            assertEquals(1L, clientTransport.bidiStreamCount());
        }
    }

    @Test
    public void grpcStreamingEnforcesCancellationDeadlinePayloadAuthorizationAndMtlsConfidentiality() throws Exception {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        GrpcTransportProvider serverTransport = new GrpcTransportProvider();
        GrpcTransportProvider clientTransport = new GrpcTransportProvider();
        try (DefaultMiddlewareRuntime server = runtime("grpc-stream-guard-server", store, serverTransport, security(new InMemoryAuditSink(64), allowAuth(), true));
             DefaultMiddlewareRuntime client = runtime("grpc-stream-guard-client", store, clientTransport, security(new InMemoryAuditSink(64), allowAuth(), false))) {
            ObjectRegistration<StreamingApi> registration = SecurityContexts.call(context("publisher", true, TransportSecurityLevel.TEST_AUTHENTICATED),
                    () -> server.export("streaming.guard", new StreamingApiImpl(), StreamingApi.class, export("grpc")));
            Method serverItems = StreamingApi.class.getMethod("serverItems");

            MiddlewareException unauthorized;
            try (StreamHandle<Object> stream = clientTransport.serverStream(registration.ref(), MethodDescriptor.fromMethod(serverItems),
                    List.of(), InvocationOptions.defaults(), String.class)) {
                unauthorized = assertThrows(MiddlewareException.class, () -> stream.poll(Duration.ofSeconds(2L)));
            }
            assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, unauthorized.code());

        }

        GrpcTestCertificateMaterial serverMaterial = GrpcTestCertificateMaterial.generate(this.temporaryDirectory.resolve("stream-server"), "localhost");
        GrpcTestCertificateMaterial clientMaterial = GrpcTestCertificateMaterial.generate(this.temporaryDirectory.resolve("stream-client"), "localhost");
        GrpcTlsConfiguration serverTls = new GrpcTlsConfiguration(serverMaterial.certificate(), serverMaterial.privateKey(), clientMaterial.certificate(), true, "default", "localhost");
        GrpcTlsConfiguration clientTls = new GrpcTlsConfiguration(clientMaterial.certificate(), clientMaterial.privateKey(), serverMaterial.certificate(), true, "default", "localhost");
        EmbeddedRegistryStore tlsStore = new EmbeddedRegistryStore();
        SecurityConfiguration production = security(new InMemoryAuditSink(64), allowAuth(), true, SecurityMode.PRODUCTION);
        GrpcTransportProvider tlsServerTransport = new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(true, serverTls));
        GrpcTransportProvider tlsClientTransport = new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(true, clientTls));
        try (DefaultMiddlewareRuntime server = runtime("grpc-stream-mtls-server", tlsStore, tlsServerTransport, production);
             DefaultMiddlewareRuntime client = runtime("grpc-stream-mtls-client", tlsStore, tlsClientTransport, production)) {
            ObjectRegistration<StreamingApi> registration = SecurityContexts.call(context("publisher", true, TransportSecurityLevel.AUTHENTICATED_ENCRYPTED),
                    () -> server.export("streaming.mtls", new StreamingApiImpl(), StreamingApi.class, export("grpc")));
            Method confidential = StreamingApi.class.getMethod("confidentialItems");
            try (StreamHandle<Object> stream = tlsClientTransport.serverStream(registration.ref(), MethodDescriptor.fromMethod(confidential),
                    List.of(), new InvocationOptions("stream-mtls", Duration.ofSeconds(2L), Map.of()), String.class)) {
                assertEquals("secret-ok", stream.poll(Duration.ofSeconds(2L)).orElseThrow());
            }
            Method large = StreamingApi.class.getMethod("largeItem");
            try (StreamHandle<Object> stream = tlsClientTransport.serverStream(registration.ref(), MethodDescriptor.fromMethod(large),
                    List.of(), new InvocationOptions("stream-large", Duration.ofSeconds(2L),
                            Map.of("stream.maxItemBytes", "8")), String.class)) {
                assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                        () -> stream.poll(Duration.ofSeconds(2L))).code());
            }

            Method slow = StreamingApi.class.getMethod("slowItems");
            try (StreamHandle<Object> stream = tlsClientTransport.serverStream(registration.ref(), MethodDescriptor.fromMethod(slow),
                    List.of(), new InvocationOptions("stream-deadline", Duration.ofMillis(50L), Map.of()), String.class)) {
                assertEquals(MiddlewareErrorCode.DEADLINE_EXCEEDED, assertThrows(MiddlewareException.class,
                        () -> stream.poll(Duration.ofSeconds(2L))).code());
                stream.cancel();
                assertTrue(stream.closed());
            }
        }
    }

    @Test
    public void grpcStreamingEnforcesConcurrentStreamLimitAcrossActiveLifetime() throws Exception {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        GrpcTransportProvider serverTransport = new GrpcTransportProvider();
        GrpcTransportProvider clientTransport = new GrpcTransportProvider();
        CountDownLatch release = new CountDownLatch(1);
        try (DefaultMiddlewareRuntime server = runtime("grpc-stream-limit-server", store, serverTransport, security(new InMemoryAuditSink(64), allowAuth(), false));
             DefaultMiddlewareRuntime client = runtime("grpc-stream-limit-client", store, clientTransport, security(new InMemoryAuditSink(64), allowAuth(), false))) {
            ObjectRegistration<StreamingApi> registration = server.export("streaming.limit", new StreamingApiImpl(release), StreamingApi.class, export("grpc"));
            Method blocking = StreamingApi.class.getMethod("blockingItems");
            List<StreamHandle<Object>> handles = new ArrayList<>(64);
            try {
                for (int index = 0; index < 64; index++) {
                    handles.add(clientTransport.serverStream(registration.ref(), MethodDescriptor.fromMethod(blocking),
                            List.of(), InvocationOptions.defaults(), String.class));
                }

                MiddlewareException overloaded = assertThrows(MiddlewareException.class,
                        () -> clientTransport.serverStream(registration.ref(), MethodDescriptor.fromMethod(blocking),
                                List.of(), InvocationOptions.defaults(), String.class));
                assertEquals(MiddlewareErrorCode.OVERLOADED, overloaded.code());
            } finally {
                release.countDown();
                for (StreamHandle<Object> handle : handles) {
                    handle.close();
                }
            }

            Method serverItems = StreamingApi.class.getMethod("serverItems");
            try (StreamHandle<Object> stream = awaitGrpcStreamAdmission(clientTransport, registration.ref(), serverItems)) {
                assertEquals("one", stream.poll(Duration.ofSeconds(2L)).orElseThrow());
            }
        }
    }

    @Test
    public void grpcServerStreamRejectsOversizedRequestItemList() throws Exception {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        GrpcTransportProvider serverTransport = new GrpcTransportProvider();
        GrpcTransportProvider clientTransport = new GrpcTransportProvider();
        try (DefaultMiddlewareRuntime server = runtime("grpc-stream-items-server", store, serverTransport, security(new InMemoryAuditSink(64), allowAuth(), false));
             DefaultMiddlewareRuntime client = runtime("grpc-stream-items-client", store, clientTransport, security(new InMemoryAuditSink(64), allowAuth(), false))) {
            ObjectRegistration<StreamingApi> registration = server.export("streaming.items", new StreamingApiImpl(), StreamingApi.class, export("grpc"));
            Method serverItems = StreamingApi.class.getMethod("serverItems");
            List<org.j7m.middleware.codec.api.EncodedValue> arguments = new ArrayList<>(4097);
            for (int index = 0; index < 4097; index++) {
                arguments.add(client.encodeValue(Integer.valueOf(index), Integer.class));
            }

            MiddlewareException overloaded = assertThrows(MiddlewareException.class,
                    () -> clientTransport.serverStream(registration.ref(), MethodDescriptor.fromMethod(serverItems),
                            arguments, InvocationOptions.defaults(), String.class));
            assertEquals(MiddlewareErrorCode.OVERLOADED, overloaded.code());
        }
    }

    @Test
    public void rmiLegacyStreamingFallbackDeliversChunksAndWrapsErrors() throws Exception {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        RmiTransportProvider serverTransport = new RmiTransportProvider();
        RmiTransportProvider clientTransport = new RmiTransportProvider();
        try (DefaultMiddlewareRuntime server = runtime("rmi-stream-server", store, serverTransport, security(new InMemoryAuditSink(64), allowAuth(), false));
             DefaultMiddlewareRuntime client = runtime("rmi-stream-client", store, clientTransport, security(new InMemoryAuditSink(64), allowAuth(), false))) {
            ObjectRegistration<StreamingApi> registration = server.export("streaming.rmi", new StreamingApiImpl(), StreamingApi.class, export("rmi"));
            Method serverItems = StreamingApi.class.getMethod("serverItems");
            try (StreamHandle<Object> stream = clientTransport.serverStream(registration.ref(), MethodDescriptor.fromMethod(serverItems),
                    List.of(), InvocationOptions.defaults(), String.class)) {
                assertEquals("one", stream.poll(Duration.ofSeconds(2L)).orElseThrow());
                assertEquals("two", stream.poll(Duration.ofSeconds(2L)).orElseThrow());
                stream.cancel();
                assertTrue(stream.closed());
            }

            Method fail = StreamingApi.class.getMethod("fail");
            try (StreamHandle<Object> stream = clientTransport.serverStream(registration.ref(), MethodDescriptor.fromMethod(fail),
                    List.of(), InvocationOptions.defaults(), String.class)) {
                assertEquals(MiddlewareErrorCode.REMOTE_EXCEPTION, assertThrows(MiddlewareException.class,
                        () -> stream.poll(Duration.ofSeconds(2L))).code());
            }
        }
    }

    @Test
    public void remoteOneWayAndSchedulerFacadeWorkAcrossGrpcRuntimes() throws Exception {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        CountDownLatch oneWayObserved = new CountDownLatch(1);
        try (InMemoryWorkloadScheduler scheduler = new InMemoryWorkloadScheduler(InMemorySchedulerOptions.defaults("remote-scheduler", "scheduler-runtime"));
             DefaultMiddlewareRuntime server = runtime("workload-server", store, new GrpcTransportProvider(), security(new InMemoryAuditSink(64), allowAuth(), false));
             DefaultMiddlewareRuntime client = runtime("workload-client", store, new GrpcTransportProvider(), security(new InMemoryAuditSink(64), allowAuth(), false))) {
            server.export("oneway", new OneWayApiImpl(oneWayObserved), OneWayApi.class, export("grpc"));
            RemoteSchedulerFacadeImpl facade = new RemoteSchedulerFacadeImpl(scheduler);
            server.export("scheduler", facade, RemoteSchedulerFacade.class, export("grpc"));

            OneWayApi oneWay = client.lookup("oneway", OneWayApi.class, LookupOptions.defaults());
            long started = System.nanoTime();
            oneWay.fire("queued");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500L);
            assertTrue(oneWayObserved.await(2L, TimeUnit.SECONDS));

            RemoteSchedulerFacade remote = client.lookup("scheduler", RemoteSchedulerFacade.class, LookupOptions.defaults());
            String taskId = remote.submitAndStart("remote-task");
            assertTrue(remote.status(taskId).contains("RUNNING") || remote.status(taskId).contains("LEASED"));
            assertTrue(remote.progress(taskId).contains("50.0"));
            assertEquals("STALE_FENCING_TOKEN", remote.staleComplete(taskId));
            assertTrue(remote.checkpoint(taskId).contains("checkpoint"));
            assertTrue(remote.cancel(taskId).contains("CANCEL"));

            String asyncId = remote.asyncComplete("async-task");
            assertEquals("COMPLETED", awaitStatus(remote, asyncId, "COMPLETED"));
        }
    }

    @Test
    public void remoteSchedulerAuthorizationAndOverloadMapToMiddlewareErrors() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        try (InMemoryWorkloadScheduler scheduler = new InMemoryWorkloadScheduler(new InMemorySchedulerOptions("limited", "limited-runtime",
                "default", Clock.systemUTC(), security(audit, allowAuth(), false), 1, 1, 4, 1024, Duration.ofSeconds(5L)));
             DefaultMiddlewareRuntime server = runtime("limited-server", store, new GrpcTransportProvider(), security(audit, allowAuth(), false));
             DefaultMiddlewareRuntime client = runtime("limited-client", store, new GrpcTransportProvider(), security(new InMemoryAuditSink(32), allowAuth(), false))) {
            server.export("limited-scheduler", new RemoteSchedulerFacadeImpl(scheduler), RemoteSchedulerFacade.class, export("grpc"));
            RemoteSchedulerFacade remote = client.lookup("limited-scheduler", RemoteSchedulerFacade.class, LookupOptions.defaults());
            remote.submitOnly("one");
            assertEquals(MiddlewareErrorCode.OVERLOADED, assertThrows(MiddlewareException.class, () -> remote.submitOnly("two")).code());
        }

        try (InMemoryWorkloadScheduler scheduler = new InMemoryWorkloadScheduler(InMemorySchedulerOptions.defaults("denied", "denied-runtime"));
             DefaultMiddlewareRuntime server = runtime("denied-server", store, new GrpcTransportProvider(), security(new InMemoryAuditSink(32), denyInvoke(), false));
             DefaultMiddlewareRuntime client = runtime("denied-client", store, new GrpcTransportProvider(), security(new InMemoryAuditSink(32), allowAuth(), false))) {
            server.export("denied-scheduler", new RemoteSchedulerFacadeImpl(scheduler), RemoteSchedulerFacade.class, export("grpc"));
            RemoteSchedulerFacade remote = client.lookup("denied-scheduler", RemoteSchedulerFacade.class, LookupOptions.defaults());
            assertEquals(MiddlewareErrorCode.PERMISSION_DENIED, assertThrows(MiddlewareException.class, () -> remote.submitOnly("denied")).code());
        }
    }

    @Test
    public void fanOutAndScatterGatherUseDiscoveredRemoteGrpcCandidates() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (InMemoryWorkloadScheduler helper = new InMemoryWorkloadScheduler(InMemorySchedulerOptions.defaults("fanout", "fanout-runtime"));
             DefaultMiddlewareRuntime a = runtime("fanout-a", store, new GrpcTransportProvider(), security(new InMemoryAuditSink(32), allowAuth(), false));
             DefaultMiddlewareRuntime b = runtime("fanout-b", store, new GrpcTransportProvider(), security(new InMemoryAuditSink(32), allowAuth(), false));
             DefaultMiddlewareRuntime c = runtime("fanout-c", store, new GrpcTransportProvider(), security(new InMemoryAuditSink(32), allowAuth(), false))) {
            a.export("fanout.service", new FanoutApiImpl("a", false), FanoutApi.class, export("grpc", Map.of("role", "worker")));
            b.export("fanout.service", new FanoutApiImpl("b", true), FanoutApi.class, export("grpc", Map.of("role", "worker", "load", "9.0")));

            List<ObjectRef> candidates = c.list("fanout.service", List.of(FanoutApi.class.getName()), Map.of("role", "worker"), LookupOptions.defaults());
            FanOutResult fanOut = SecurityContexts.call(context("fanout-caller", true, TransportSecurityLevel.TEST_AUTHENTICATED),
                    () -> helper.fanOut(new FanOutRequest(candidates, 2, Duration.ofSeconds(2L), Duration.ofSeconds(1L),
                            Map.of("j7m.reliability.idempotent", "true")),
                            candidate -> c.proxyFor(candidate, FanoutApi.class).value()));
            ScatterGatherResult partial = SecurityContexts.call(context("fanout-caller", true, TransportSecurityLevel.TEST_AUTHENTICATED),
                    () -> helper.scatterGather(new ScatterGatherRequest(candidates, 2, Duration.ofSeconds(2L), true,
                            Map.of("j7m.reliability.idempotent", "true")),
                            candidate -> c.proxyFor(candidate, FanoutApi.class).maybeFail()));

            assertEquals(2, fanOut.successes().size());
            assertEquals(1, partial.successes().size());
            assertEquals(1, partial.failures().size());
            assertTrue(partial.partial());
        }
    }

    @Test
    public void dispatchRejectsExpiredDeadlineBeforeTargetExecution() throws Exception {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        AtomicBoolean invoked = new AtomicBoolean();
        try (DefaultMiddlewareRuntime runtime = runtime("deadline-dispatch", store, new GrpcTransportProvider(),
                security(new InMemoryAuditSink(32), allowAuth(), false))) {
            ObjectRegistration<DeadlineApi> registration = runtime.export("deadline-api", new DeadlineApiImpl(invoked), DeadlineApi.class, export("grpc"));
            Method method = DeadlineApi.class.getMethod("value");
            InvocationOptions expired = new InvocationOptions("expired-dispatch", Duration.ofMillis(1L),
                    Map.of("j7m.reliability.deadlineEpochMillis", "1"));

            MiddlewareException failure = assertThrows(MiddlewareException.class,
                    () -> runtime.dispatch(registration.ref(), MethodDescriptor.fromMethod(method), List.of(), expired));
            assertEquals(MiddlewareErrorCode.DEADLINE_EXCEEDED, failure.code());
            assertFalse(invoked.get());
        }
    }

    private static DefaultMiddlewareRuntime runtime(
            final String id,
            final EmbeddedRegistryStore store,
            final org.j7m.middleware.transport.api.TransportProvider provider,
            final SecurityConfiguration security) {
        return new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                .security(security)
                .addTransport(provider)
                .disableNuggetAdmin()
                .build();
    }

    private static ExportOptions export(final String transport) {
        return export(transport, Map.of());
    }

    private static ExportOptions export(final String transport, final Map<String, String> metadata) {
        return new ExportOptions(false, transport, "default", List.of(), metadata, Duration.ofSeconds(30L), Duration.ofSeconds(10L));
    }

    private static SecurityConfiguration security(final InMemoryAuditSink audit, final AuthorizationPolicy policy, final boolean requireAuthentication) {
        return security(audit, policy, requireAuthentication, SecurityMode.DEV);
    }

    private static SecurityConfiguration security(
            final InMemoryAuditSink audit,
            final AuthorizationPolicy policy,
            final boolean requireAuthentication,
            final SecurityMode mode) {
        return new SecurityConfiguration(mode, policy, audit, new DefaultRedactor(), requireAuthentication,
                mode != SecurityMode.PRODUCTION, 1024 * 1024, 1024 * 1024, 64, Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("test", "1", Map.of())), false, Duration.ofSeconds(30L), 128,
                mode == SecurityMode.PRODUCTION, null);
    }

    private static AuthorizationPolicy allowAuth() {
        return new AllowAllAuthorizationPolicy();
    }

    private static AuthorizationPolicy denyInvoke() {
        return new AuthorizationPolicy() {
            @Override
            public String id() {
                return "deny-invoke";
            }

            @Override
            public AuthorizationDecision evaluate(final SecurityContext context, final PermissionRequest request) {
                if (request.action() == PermissionAction.INVOKE) {
                    return AuthorizationDecision.deny(this.id(), "invoke-denied");
                }
                return AuthorizationDecision.allow(this.id());
            }
        };
    }

    private static SecurityContext context(final String principal, final boolean authenticated, final TransportSecurityLevel level) {
        return new SecurityContext(new Principal(principal, principal, Set.of("admin", "runtime"), Set.of()), "caller",
                new TrustDomain("default"), new Credential("test", "1", Map.of()), "corr", "", "", authenticated, level,
                Set.of("admin", "runtime"), Map.of(), Instant.now());
    }

    private static String awaitStatus(final RemoteSchedulerFacade remote, final String taskId, final String state) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (System.nanoTime() < deadline) {
            String status = remote.status(taskId);
            if (status.contains(state)) {
                return state;
            }
            awaitTestDelay(Duration.ofMillis(20L));
        }
        return remote.status(taskId);
    }

    private static StreamHandle<Object> awaitGrpcStreamAdmission(
            final GrpcTransportProvider transport,
            final ObjectRef ref,
            final Method method) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        MiddlewareException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return transport.serverStream(ref, MethodDescriptor.fromMethod(method), List.of(), InvocationOptions.defaults(), String.class);
            } catch (MiddlewareException exception) {
                last = exception;
                if (exception.code() != MiddlewareErrorCode.OVERLOADED) {
                    throw exception;
                }
                awaitTestDelay(Duration.ofMillis(20L));
            }
        }
        if (last != null) {
            throw last;
        }
        throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "grpc stream admission did not recover");
    }

    /**
     * Streaming test service.
     */
    public interface StreamingApi {
        /**
         * @return stream items
         */
        List<String> serverItems();

        /**
         * @return slow stream items
         */
        List<String> slowItems();

        /**
         * @return oversize item
         */
        List<String> largeItem();

        /**
         * @return confidential stream items
         */
        @Confidential
        List<String> confidentialItems();

        /**
         * @return stream items after a test-controlled release
         */
        List<String> blockingItems();

        /**
         * Aggregates client stream items.
         */
        String aggregate(List<String> items);

        /**
         * Echoes one bidirectional item.
         */
        String echo(String item);

        /**
         * Returns object references as stream items.
         */
        List<ObjectRef> refs(ObjectRef ref);

        /**
         * Fails stream method.
         */
        List<String> fail();
    }

    private static final class StreamingApiImpl implements StreamingApi {
        private final CountDownLatch blockingRelease;

        private StreamingApiImpl() {
            this(null);
        }

        private StreamingApiImpl(final CountDownLatch blockingRelease) {
            this.blockingRelease = blockingRelease;
        }

        @Override
        public List<String> serverItems() {
            return List.of("one", "two");
        }

        @Override
        public List<String> slowItems() {
            awaitTestDelayUnchecked(Duration.ofMillis(250L));
            return List.of("slow");
        }

        @Override
        public List<String> largeItem() {
            return List.of("this item is intentionally too large");
        }

        @Override
        public List<String> confidentialItems() {
            return List.of("secret-ok");
        }

        @Override
        public List<String> blockingItems() {
            if (this.blockingRelease != null) {
                try {
                    boolean released = this.blockingRelease.await(5L, TimeUnit.SECONDS);
                    if (!released) {
                        throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "blocking stream release was not observed");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "blocking stream interrupted");
                }
            }
            return List.of("released");
        }

        @Override
        public String aggregate(final List<String> items) {
            return String.join(",", items);
        }

        @Override
        public String echo(final String item) {
            return "echo-" + item;
        }

        @Override
        public List<ObjectRef> refs(final ObjectRef ref) {
            return List.of(ref);
        }

        @Override
        public List<String> fail() {
            throw MiddlewareException.of(MiddlewareErrorCode.REMOTE_EXCEPTION, "safe stream failure");
        }
    }

    private static void awaitTestDelay(final Duration duration) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        latch.await(Math.max(1L, duration.toMillis()), TimeUnit.MILLISECONDS);
    }

    private static void awaitTestDelayUnchecked(final Duration duration) {
        try {
            awaitTestDelay(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One-way test service.
     */
    public interface OneWayApi {
        /**
         * Fires a one-way event.
         */
        @OneWay
        void fire(String value);
    }

    private static final class OneWayApiImpl implements OneWayApi {
        private final CountDownLatch observed;

        private OneWayApiImpl(final CountDownLatch observed) {
            this.observed = observed;
        }

        @Override
        public void fire(final String value) {
            this.observed.countDown();
        }
    }

    /**
     * Remote scheduler facade using primitive wire-friendly values.
     */
    public interface RemoteSchedulerFacade {
        /**
         * Submits and starts a task.
         */
        String submitAndStart(String name);

        /**
         * Submits a queued task.
         */
        String submitOnly(String name);

        /**
         * Returns status text.
         */
        String status(String taskId);

        /**
         * Returns progress text.
         */
        String progress(String taskId);

        /**
         * Records checkpoint.
         */
        String checkpoint(String taskId);

        /**
         * Cancels task.
         */
        String cancel(String taskId);

        /**
         * Attempts stale completion.
         */
        String staleComplete(String taskId);

        /**
         * Starts async completion and returns task id.
         */
        @Async
        String asyncComplete(String name);
    }

    private static final class RemoteSchedulerFacadeImpl implements RemoteSchedulerFacade {
        private final InMemoryWorkloadScheduler scheduler;
        private final java.util.concurrent.ConcurrentMap<String, TaskLease> leases;
        private final java.util.concurrent.ConcurrentMap<String, LeaseOwner> owners;

        private RemoteSchedulerFacadeImpl(final InMemoryWorkloadScheduler scheduler) {
            this.scheduler = scheduler;
            this.leases = new java.util.concurrent.ConcurrentHashMap<>();
            this.owners = new java.util.concurrent.ConcurrentHashMap<>();
        }

        @Override
        public String submitAndStart(final String name) {
            TaskRef ref = this.scheduler.submit(submission(name)).ref();
            LeaseOwner owner = new LeaseOwner("remote", "worker", Map.of());
            TaskLease lease = this.scheduler.acquireLease(ref.taskId(), owner, Duration.ofSeconds(5L));
            this.owners.put(ref.taskId().value(), owner);
            this.leases.put(ref.taskId().value(), lease);
            this.scheduler.reportProgress(ref.taskId(), owner, lease.fencingToken(), new TaskProgress(50.0D, "remote", Instant.now(), Map.of()));
            return ref.taskId().value();
        }

        @Override
        public String submitOnly(final String name) {
            return this.scheduler.submit(submission(name)).ref().taskId().value();
        }

        @Override
        public String status(final String taskId) {
            return this.scheduler.status(new org.j7m.middleware.workload.api.TaskId(taskId)).state().name();
        }

        @Override
        public String progress(final String taskId) {
            return Double.toString(this.scheduler.status(new org.j7m.middleware.workload.api.TaskId(taskId)).progress().percent());
        }

        @Override
        public String checkpoint(final String taskId) {
            org.j7m.middleware.workload.api.TaskId id = new org.j7m.middleware.workload.api.TaskId(taskId);
            return this.scheduler.checkpoint(id, this.owners.get(taskId), this.leases.get(taskId).fencingToken(), Map.of("checkpoint", "remote")).metadata().toString();
        }

        @Override
        public String cancel(final String taskId) {
            this.scheduler.cancel(new org.j7m.middleware.workload.api.TaskId(taskId));
            return this.scheduler.status(new org.j7m.middleware.workload.api.TaskId(taskId)).state().name();
        }

        @Override
        public String staleComplete(final String taskId) {
            try {
                this.scheduler.complete(new org.j7m.middleware.workload.api.TaskId(taskId), this.owners.get(taskId), new FencingToken(0L), "bad");
                return "unexpected";
            } catch (MiddlewareException exception) {
                return exception.code().name();
            }
        }

        @Override
        public String asyncComplete(final String name) {
            TaskRef ref = this.scheduler.submit(submission(name)).ref();
            Thread.startVirtualThread(() -> {
                LeaseOwner owner = new LeaseOwner("remote", "async", Map.of());
                TaskLease lease = this.scheduler.acquireLease(ref.taskId(), owner, Duration.ofSeconds(5L));
                this.scheduler.complete(ref.taskId(), owner, lease.fencingToken(), "done");
            });
            return ref.taskId().value();
        }

        private static TaskSubmission submission(final String name) {
            return new TaskSubmission(new TaskDescriptor(name, "remote", "payload", WorkloadMetadata.empty(), Map.of()),
                    TaskOptions.defaults(), Map.of());
        }
    }

    /**
     * Fan-out service.
     */
    public interface FanoutApi {
        /**
         * Returns value.
         */
        @Idempotent
        String value();

        /**
         * May fail.
         */
        @Idempotent
        String maybeFail();
    }

    /**
     * API used to prove pre-decode deadline rejection.
     */
    public interface DeadlineApi {
        /**
         * Returns a value if invoked.
         */
        String value();
    }

    private static final class DeadlineApiImpl implements DeadlineApi {
        private final AtomicBoolean invoked;

        private DeadlineApiImpl(final AtomicBoolean invoked) {
            this.invoked = invoked;
        }

        @Override
        public String value() {
            this.invoked.set(true);
            return "value";
        }
    }

    private static final class FanoutApiImpl implements FanoutApi {
        private final String value;
        private final boolean fail;

        private FanoutApiImpl(final String value, final boolean fail) {
            this.value = value;
            this.fail = fail;
        }

        @Override
        public String value() {
            return this.value;
        }

        @Override
        public String maybeFail() {
            if (this.fail) {
                throw MiddlewareException.of(MiddlewareErrorCode.REMOTE_EXCEPTION, "safe fanout failure");
            }
            return this.value;
        }
    }
}
