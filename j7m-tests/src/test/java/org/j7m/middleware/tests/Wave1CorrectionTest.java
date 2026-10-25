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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.annotations.OneWay;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
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
import org.j7m.middleware.security.api.SecurityMode;
import org.j7m.middleware.security.api.TransportSecurityLevel;
import org.j7m.middleware.security.api.TrustDomain;
import org.j7m.middleware.security.defaults.AllowAllAuthorizationPolicy;
import org.j7m.middleware.security.defaults.DefaultRedactor;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.j7m.middleware.security.defaults.MutableCredentialProvider;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.j7m.middleware.transport.rmi.RmiTransportProvider;
import org.j7m.middleware.transport.rmi.internal.RmiRemoteObjectService;
import org.j7m.middleware.transport.rmi.internal.RmiSerializationGuard;
import org.junit.jupiter.api.Test;

/**
 * Focused regression coverage for the consolidated Wave 1 correction package.
 */
public final class Wave1CorrectionTest {
    @Test
    public void rmiRemoteBoundaryUsesOnlyByteArrayEnvelopes() {
        for (Method method : RmiRemoteObjectService.class.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            assertEquals(byte[].class, method.getReturnType(), method.getName());
            assertEquals(1, method.getParameterCount(), method.getName());
            assertEquals(byte[].class, method.getParameterTypes()[0], method.getName());
        }
    }

    @Test
    public void rmiSerializationGuardRejectsForbiddenProbeShapes() {
        assertAllowed(byte[].class, 16L, 1L, 2L, 32L);
        assertAllowed(String.class, -1L, 1L, 2L, 32L);
        assertRejected(HostileSerializable.class, -1L, 1L, 2L, 32L);
        assertRejected(ObjectRef.class, -1L, 1L, 2L, 32L);
        assertRejected(org.j7m.middleware.codec.api.EncodedValue.class, -1L, 1L, 2L, 32L);
        assertRejected(ArrayList.class, -1L, 1L, 2L, 32L);
        assertRejected(HashMap.class, -1L, 1L, 2L, 32L);
        assertRejected(Method.class, -1L, 1L, 2L, 32L);
        assertRejected(BigInteger.class, -1L, 1L, 2L, 32L);
        Object proxy = Proxy.newProxyInstance(Wave1CorrectionTest.class.getClassLoader(), new Class<?>[] {Runnable.class}, (instance, method, arguments) -> null);
        assertRejected(proxy.getClass(), -1L, 1L, 2L, 32L);
        assertRejected(byte[].class, RmiSerializationGuard.MAX_ARRAY_LENGTH + 1L, 1L, 2L, 32L);
        assertRejected(byte[].class, 16L, RmiSerializationGuard.MAX_DEPTH + 1L, 2L, 32L);
        assertRejected(byte[].class, 16L, 1L, RmiSerializationGuard.MAX_REFERENCES + 1L, 32L);
        assertRejected(byte[].class, 16L, 1L, 2L, RmiSerializationGuard.MAX_STREAM_BYTES + 1L);
    }

    @Test
    public void preexistingPermissiveGlobalRmiFilterFailsStartupSafely() throws Exception {
        ChildResult result = runFilterChild("permissive");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("code=TRANSPORT_ERROR"), result.output());
        assertTrue(result.output().contains("permits forbidden"), result.output());
        assertFalse(result.output().contains("HostileSerializable"), result.output());
        assertFalse(result.output().contains("SECRET"), result.output());
    }

    @Test
    public void preexistingIncompatibleGlobalRmiFilterFailsStartupSafely() throws Exception {
        ChildResult result = runFilterChild("reject-byte-envelope");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("code=TRANSPORT_ERROR"), result.output());
        assertTrue(result.output().contains("rejects required byte envelope"), result.output());
        assertFalse(result.output().contains("SECRET"), result.output());
    }

    @Test
    public void hostileRmiDeserializationSentinelDoesNotExecuteAndEndpointRemainsUsable() throws Exception {
        ChildResult result = runFilterChild("hostile-rejection");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("hostileRejected=true"), result.output());
        assertTrue(result.output().contains("sentinel=0"), result.output());
        assertTrue(result.output().contains("ping=pong"), result.output());
    }

    @Test
    public void rmiWireRejectsMalformedUtf8Strings() throws Exception {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime server = runtime("w1-rmi-utf8-server", store, new RmiTransportProvider(), security(false))) {
            server.export("wave", new WaveServiceImpl(), WaveService.class, export("rmi"));
            String endpoint = server.status().transports().stream()
                    .filter(status -> "rmi".equals(status.transportId()))
                    .findFirst()
                    .orElseThrow()
                    .endpoint();
            RmiRemoteObjectService service = (RmiRemoteObjectService) Naming.lookup(endpoint);

            assertThrows(RemoteException.class, () -> service.open(malformedOpenRequestWithBadObjectId()));
        }
    }

    @Test
    public void callerSecurityMetadataDoesNotAuthenticateGrpcInvocation() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        SecurityConfiguration serverSecurity = security(true);
        SecurityConfiguration clientSecurity = security(false);
        try (DefaultMiddlewareRuntime server = runtime("w1-sec-server", store, new GrpcTransportProvider(), serverSecurity);
             DefaultMiddlewareRuntime client = runtime("w1-sec-client", store, new GrpcTransportProvider(), clientSecurity)) {
            ObjectRegistration<WaveService> registration = org.j7m.middleware.security.api.SecurityContexts.call(serverContext("w1-sec-server"),
                    () -> server.export("wave", new WaveServiceImpl(), WaveService.class, export("grpc")));
            WaveService proxy = client.proxyFor(registration.ref(), WaveService.class);

            MiddlewareException failure = assertThrows(MiddlewareException.class,
                    () -> org.j7m.middleware.security.api.SecurityContexts.call(authenticatedSpoof(), proxy::ping));

            assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, failure.code());
        }
    }

    @Test
    public void inboundReferenceTamperingIsRejectedBeforeInvocation() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime server = runtime("w1-ref-server", store, new GrpcTransportProvider(), security(false));
             DefaultMiddlewareRuntime client = runtime("w1-ref-client", store, new GrpcTransportProvider(), security(false))) {
            ObjectRegistration<WaveService> registration = server.export("wave", new WaveServiceImpl(), WaveService.class, export("grpc"));
            ObjectRef tampered = new ObjectRef(registration.ref().objectId(), registration.ref().logicalName(), registration.ref().serviceName(),
                    registration.ref().runtimeId(), registration.ref().registryContext(), registration.ref().interfaces(), registration.ref().transport(),
                    registration.ref().endpoint(), registration.ref().leaseUntil(), registration.ref().generation() + 1L, registration.ref().metadata());

            MiddlewareException failure = assertThrows(MiddlewareException.class, () -> client.proxyFor(tampered, WaveService.class));

            assertEquals(MiddlewareErrorCode.NOT_FOUND, failure.code());
        }
    }

    @Test
    public void failedInvocationReleasesAutoExportedCallback() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime server = runtime("w1-callback-server", store, new GrpcTransportProvider(), security(false));
             DefaultMiddlewareRuntime client = runtime("w1-callback-client", store, new GrpcTransportProvider(), security(false))) {
            server.export("wave", new WaveServiceImpl(), WaveService.class, export("grpc"));
            WaveService proxy = client.lookup("wave", WaveService.class, LookupOptions.defaults());

            assertEquals(MiddlewareErrorCode.REMOTE_EXCEPTION, assertThrows(MiddlewareException.class,
                    () -> proxy.failWithCallback(new CallbackImpl())).code());

            assertFalse(client.status().instances().stream().anyMatch(status -> "true".equals(status.ref().metadata().get("callback.auto"))));
        }
    }

    @Test
    public void encodingFailureReleasesAlreadyAutoExportedCallback() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime server = runtime("w1-callback-encode-server", store, new GrpcTransportProvider(), security(false));
             DefaultMiddlewareRuntime client = runtime("w1-callback-encode-client", store, new GrpcTransportProvider(), security(false))) {
            server.export("wave", new WaveServiceImpl(), WaveService.class, export("grpc"));
            WaveService proxy = client.lookup("wave", WaveService.class, LookupOptions.defaults());

            assertEquals(MiddlewareErrorCode.CODEC_ERROR, assertThrows(MiddlewareException.class,
                    () -> proxy.callbackThenUnsupported(new CallbackImpl(), new Object())).code());

            assertFalse(client.status().instances().stream().anyMatch(status -> "true".equals(status.ref().metadata().get("callback.auto"))));
            assertEquals(1, activeRegistryRecords(store));
        }
    }

    @Test
    public void successfulAndOneWayCallbackRegistrationsLiveUntilRuntimeClose() {
        assertRuntimeScopedCallbacks("grpc", new GrpcTransportProvider(), new GrpcTransportProvider());
        assertRuntimeScopedCallbacks("rmi", new RmiTransportProvider(), new RmiTransportProvider());
    }

    @Test
    public void explicitLongLivedCallbackExportSurvivesInvocationCompletion() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime server = runtime("w1-explicit-callback-server", store, new GrpcTransportProvider(), security(false));
             DefaultMiddlewareRuntime client = runtime("w1-explicit-callback-client", store, new GrpcTransportProvider(), security(false));
             ObjectRegistration<Callback> callbackRegistration = client.export("explicit-callback", new CallbackImpl(), Callback.class, export("grpc"))) {
            server.export("wave", new WaveServiceImpl(), WaveService.class, export("grpc"));
            WaveService proxy = client.lookup("wave", WaveService.class, LookupOptions.defaults());
            Callback callbackProxy = client.proxyFor(callbackRegistration.ref(), Callback.class);

            proxy.rememberAndCall(callbackProxy);
            proxy.triggerStored("explicit-late");

            assertTrue(client.status().instances().stream().anyMatch(status -> status.ref().objectId().equals(callbackRegistration.ref().objectId())));
        }
    }

    @Test
    public void codecRejectsRawDtoFallbackAndOversizedValues() {
        DefaultValueCodec codec = new DefaultValueCodec();

        assertEquals(MiddlewareErrorCode.CODEC_ERROR, assertThrows(MiddlewareException.class,
                () -> codec.encode(new Object(), Object.class, null)).code());
        assertEquals(MiddlewareErrorCode.CODEC_ERROR, assertThrows(MiddlewareException.class,
                () -> codec.encode(new byte[(1024 * 1024) + 1], byte[].class, null)).code());
    }

    @Test
    public void registryAndDiagnosticsAreBoundedAndRedacted() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore(4);
        EmbeddedRegistryClient registry = new EmbeddedRegistryClient(store, Clock.systemUTC());
        for (int index = 0; index < 8; index++) {
            String secretKey = "SECRET-KEY-" + index + "\ncontrol";
            ObjectRef ref = new ObjectRef("object-" + index, "svc/object-" + index, "svc", "runtime-" + index, "default",
                    List.of(WaveService.class.getName()), "grpc", "secret-endpoint-" + index, Clock.systemUTC().millis() + 60_000L,
                    1L, Map.of(secretKey, "SECRET-" + index));
            registry.bind(new RegistryRecord(ref, Map.of(), 1L, 1L, ref.leaseUntil(), org.j7m.middleware.api.ObjectState.EXECUTED));
            assertTrue(registry.activeRecordCount() <= 4);
            assertFalse(ref.toString().contains("SECRET-"));
            assertFalse(ref.toString().contains("control"));
            assertFalse(ref.toString().contains("secret-endpoint-"));
            assertTrue(ref.toString().contains("metadata=<redacted:1>"));
        }
        assertTrue(registry.list(new RegistryQuery("svc", List.of(), Map.of(), "default", Clock.systemUTC().millis(), 32)).size() <= 4);
    }

    @Test
    public void oneWayUsesRuntimeAdmissionPath() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime server = runtime("w1-oneway-server", store, new GrpcTransportProvider(), security(false));
             DefaultMiddlewareRuntime client = runtime("w1-oneway-client", store, new GrpcTransportProvider(), security(false))) {
            server.export("wave", new WaveServiceImpl(), WaveService.class, export("grpc"));
            WaveService proxy = client.lookup("wave", WaveService.class, LookupOptions.defaults());

            proxy.notifyOnly("event");

            assertEquals("1024", client.status().security().get("oneWay.queuedLimit"));
            assertEquals("64", client.status().security().get("oneWay.activeLimit"));
        }
    }

    private static DefaultMiddlewareRuntime runtime(
            final String id,
            final EmbeddedRegistryStore store,
            final org.j7m.middleware.transport.api.TransportProvider transport,
            final SecurityConfiguration security) {
        return new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                .clock(Clock.systemUTC())
                .security(security)
                .addTransport(transport)
                .disableNuggetAdmin()
                .build();
    }

    private static ExportOptions export(final String transport) {
        return new ExportOptions(false, transport, "default", List.of(), Map.of(), Duration.ofSeconds(30L), Duration.ofSeconds(10L));
    }

    private static void assertRuntimeScopedCallbacks(
            final String transport,
            final org.j7m.middleware.transport.api.TransportProvider serverTransport,
            final org.j7m.middleware.transport.api.TransportProvider clientTransport) {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime server = runtime("w1-scoped-callback-server-" + transport, store, serverTransport, security(false));
             DefaultMiddlewareRuntime client = runtime("w1-scoped-callback-client-" + transport, store, clientTransport, security(false))) {
            server.export("wave", new WaveServiceImpl(), WaveService.class, export(transport));
            WaveService proxy = client.lookup("wave", WaveService.class, LookupOptions.defaults());
            CallbackImpl callback = new CallbackImpl();

            proxy.rememberAndCall(callback);
            assertEquals(1, callback.count());
            assertTrue(client.status().instances().stream().anyMatch(status -> "true".equals(status.ref().metadata().get("callback.auto"))));
            proxy.triggerStored("late");
            assertEquals(2, callback.count());

            CallbackImpl oneWayCallback = new CallbackImpl();
            proxy.rememberOneWay(oneWayCallback);
            awaitCallbackCount(oneWayCallback, 1);
            assertTrue(client.status().instances().stream().anyMatch(status -> "true".equals(status.ref().metadata().get("callback.auto"))));
            proxy.triggerStored("late-one-way");
            assertEquals(2, oneWayCallback.count());

            client.close();
            assertEquals(1, activeRegistryRecords(store));
            assertThrows(MiddlewareException.class, () -> proxy.triggerStored("after-client-close"));
        }
    }

    private static int activeRegistryRecords(final EmbeddedRegistryStore store) {
        return new EmbeddedRegistryClient(store, Clock.systemUTC()).activeRecordCount();
    }

    private static void awaitCallbackCount(final CallbackImpl callback, final int expectedCount) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            if (callback.count() == expectedCount) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("callback invocation was not observed");
    }

    private static byte[] malformedOpenRequestWithBadObjectId() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(2);
        output.writeByte(0xc3);
        output.writeByte(0x28);
        writeWireString(output, "svc/object");
        writeWireString(output, "svc");
        writeWireString(output, "runtime");
        writeWireString(output, "default");
        output.writeInt(1);
        writeWireString(output, WaveService.class.getName());
        writeWireString(output, "rmi");
        writeWireString(output, "rmi://127.0.0.1:1/test");
        output.writeLong(0L);
        output.writeLong(1L);
        output.writeInt(0);
        writeWireString(output, WaveService.class.getName());
        output.flush();
        return bytes.toByteArray();
    }

    private static void writeWireString(final DataOutputStream output, final String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void assertAllowed(
            final Class<?> serialClass,
            final long arrayLength,
            final long depth,
            final long references,
            final long streamBytes) {
        assertEquals(ObjectInputFilter.Status.ALLOWED, RmiSerializationGuard.strictStatus(
                new ProbeInfo(serialClass, arrayLength, depth, references, streamBytes)));
    }

    private static void assertRejected(
            final Class<?> serialClass,
            final long arrayLength,
            final long depth,
            final long references,
            final long streamBytes) {
        assertEquals(ObjectInputFilter.Status.REJECTED, RmiSerializationGuard.strictStatus(
                new ProbeInfo(serialClass, arrayLength, depth, references, streamBytes)));
    }

    private static ChildResult runFilterChild(final String mode) throws Exception {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        Path javaPath = Path.of(System.getProperty("java.home"), "bin", executable);
        ProcessBuilder builder = new ProcessBuilder(
                javaPath.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                RmiFilterProbeMain.class.getName(),
                mode);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean completed = process.waitFor(30L, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("RMI filter child process did not finish");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ChildResult(process.exitValue(), output);
    }

    private static SecurityConfiguration security(final boolean requireAuthentication) {
        return new SecurityConfiguration(SecurityMode.DEV, new AllowAllAuthorizationPolicy(), new InMemoryAuditSink(64),
                new DefaultRedactor(), requireAuthentication, true, 1024 * 1024, 1024, Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("test", "1", Map.of())));
    }

    private static SecurityContext authenticatedSpoof() {
        return new SecurityContext(new Principal("spoofed", "spoofed", Set.of("admin"), Set.of()), "spoofed-runtime",
                new TrustDomain("default"), new Credential("test", "1", Map.of()), "corr", "", "", true,
                TransportSecurityLevel.TEST_AUTHENTICATED, Set.of("admin"), Map.of(), Instant.now());
    }

    private static SecurityContext serverContext(final String runtimeId) {
        return new SecurityContext(Principal.runtime(runtimeId), runtimeId, new TrustDomain("default"),
                new Credential("test", "1", Map.of()), "corr", "", "", true, TransportSecurityLevel.TEST_AUTHENTICATED,
                Set.of("runtime"), Map.of(), Instant.now());
    }

    private record ChildResult(int exitCode, String output) {
    }

    private record ProbeInfo(Class<?> serialClass, long arrayLength, long depth, long references, long streamBytes)
            implements ObjectInputFilter.FilterInfo {
    }

    /**
     * Forked helper for process-global RMI serialization filter scenarios.
     */
    public static final class RmiFilterProbeMain {
        private RmiFilterProbeMain() {
        }

        /**
         * Executes one forked filter probe.
         *
         * @param arguments first argument selects the probe mode
         */
        public static void main(final String[] arguments) throws Exception {
            if (arguments.length != 1) {
                System.out.println("missing mode");
                System.exit(2);
            }
            switch (arguments[0]) {
                case "permissive" -> {
                    ObjectInputFilter.Config.setSerialFilter(info -> ObjectInputFilter.Status.ALLOWED);
                    expectStartupFailure();
                }
                case "reject-byte-envelope" -> {
                    ObjectInputFilter.Config.setSerialFilter(info -> info.serialClass() == byte[].class
                            ? ObjectInputFilter.Status.REJECTED
                            : ObjectInputFilter.Status.ALLOWED);
                    expectStartupFailure();
                }
                case "hostile-rejection" -> verifyHostileRejectionAndUsableEndpoint();
                default -> {
                    System.out.println("unknown mode");
                    System.exit(3);
                }
            }
        }

        private static void expectStartupFailure() {
            EmbeddedRegistryStore store = new EmbeddedRegistryStore();
            try (DefaultMiddlewareRuntime ignored = runtime("w1-filter-fail", store, new RmiTransportProvider(), security(false))) {
                System.out.println("unexpected startup");
                System.exit(4);
            } catch (MiddlewareException exception) {
                System.out.println("code=" + exception.code());
                System.out.println("message=" + exception.getMessage());
            }
        }

        private static void verifyHostileRejectionAndUsableEndpoint() throws Exception {
            EmbeddedRegistryStore store = new EmbeddedRegistryStore();
            try (DefaultMiddlewareRuntime server = runtime("w1-hostile-server", store, new RmiTransportProvider(), security(false));
                 DefaultMiddlewareRuntime client = runtime("w1-hostile-client", store, new RmiTransportProvider(), security(false))) {
                server.export("wave", new WaveServiceImpl(), WaveService.class, export("rmi"));
                byte[] hostileBytes = serializeHostile();
                HostileSerializable.resetSentinel();
                boolean rejected = false;
                try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(hostileBytes))) {
                    input.readObject();
                } catch (InvalidClassException exception) {
                    rejected = true;
                }
                WaveService proxy = client.lookup("wave", WaveService.class, LookupOptions.defaults());
                System.out.println("hostileRejected=" + rejected);
                System.out.println("sentinel=" + HostileSerializable.sentinel());
                System.out.println("ping=" + proxy.ping());
            }
        }

        private static byte[] serializeHostile() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeObject(new HostileSerializable());
            }
            return bytes.toByteArray();
        }
    }

    private static final class HostileSerializable implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private static final AtomicInteger SENTINEL = new AtomicInteger();

        private HostileSerializable() {
            SENTINEL.incrementAndGet();
        }

        @Serial
        private void readObject(final ObjectInputStream input) throws IOException, ClassNotFoundException {
            SENTINEL.incrementAndGet();
            input.defaultReadObject();
        }

        @Serial
        private Object readResolve() {
            SENTINEL.incrementAndGet();
            return this;
        }

        private static void resetSentinel() {
            SENTINEL.set(0);
        }

        private static int sentinel() {
            return SENTINEL.get();
        }
    }

    /**
     * Service API used by Wave 1 transport and callback tests.
     */
    public interface WaveService {
        /**
         * Returns a constant value.
         */
        String ping();

        /**
         * Fails after receiving a callback argument.
         */
        void failWithCallback(Callback callback);

        /**
         * Stores and invokes a callback during this invocation.
         */
        void rememberAndCall(Callback callback);

        /**
         * Accepts a callback before an intentionally unsupported value.
         */
        void callbackThenUnsupported(Callback callback, Object unsupported);

        /**
         * Stores and invokes a callback from a one-way invocation.
         */
        @OneWay
        void rememberOneWay(Callback callback);

        /**
         * Invokes the previously stored callback.
         */
        void triggerStored(String value);

        /**
         * One-way notification method.
         */
        @OneWay
        void notifyOnly(String value);
    }

    /**
     * Callback API used to verify failed invocation cleanup.
     */
    public interface Callback {
        /**
         * Handles one callback.
         */
        void onMessage(String value);
    }

    private static final class WaveServiceImpl implements WaveService {
        private volatile Callback callback;

        @Override
        public String ping() {
            return "pong";
        }

        @Override
        public void failWithCallback(final Callback callback) {
            throw new IllegalStateException("expected failure");
        }

        @Override
        public void rememberAndCall(final Callback newCallback) {
            this.callback = newCallback;
            newCallback.onMessage("during");
        }

        @Override
        public void callbackThenUnsupported(final Callback callback, final Object unsupported) {
        }

        @Override
        public void rememberOneWay(final Callback newCallback) {
            this.callback = newCallback;
            newCallback.onMessage("during-one-way");
        }

        @Override
        public void triggerStored(final String value) {
            this.callback.onMessage(value);
        }

        @Override
        public void notifyOnly(final String value) {
        }
    }

    private static final class CallbackImpl implements Callback {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public void onMessage(final String value) {
            this.count.incrementAndGet();
        }

        int count() {
            return this.count.get();
        }
    }
}
