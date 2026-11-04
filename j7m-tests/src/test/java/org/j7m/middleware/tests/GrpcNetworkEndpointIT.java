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

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.j7m.middleware.api.*;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.transport.grpc.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrpcNetworkEndpointIT {
    @TempDir Path directory;

    public interface Greeting { String greet(String name); }
    public static final class GreetingImpl implements Greeting {
        @Override public String greet(String name) { return "hello " + name; }
    }

    @Test void aliasPortZeroUsesGeneratedAuthoritativeReferenceThroughMutualTls() throws Exception {
        var clientMaterial = GrpcTestCertificateMaterial.generate(directory.resolve("client"), "localhost");
        Path certificate = fixture("alias-test.crt");
        var serverTls = new GrpcTlsConfiguration(certificate, fixture("alias-test.key"), clientMaterial.certificate(), true, "default");
        var clientTls = new GrpcTlsConfiguration(clientMaterial.certificate(), clientMaterial.privateKey(), certificate, true, "default");
        var store = new EmbeddedRegistryStore();
        var configuration = new GrpcNetworkConfiguration("127.0.0.2", 0, "127.0.0.2", 0);
        try (var server = runtime("alias-server", store, new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(false, serverTls), configuration));
             var client = runtime("alias-client", store, new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(false, clientTls)));
             var exported = server.export("greeting", new GreetingImpl(), Greeting.class, ExportOptions.defaults())) {
            var ref = exported.ref();
            URI endpoint = URI.create(ref.endpoint());
            assertEquals("127.0.0.2", endpoint.getHost());
            assertTrue(endpoint.getPort() > 0);
            assertEquals("hello Ada", client.reference(ref, Greeting.class, ProxyOptions.defaults()).greet("Ada"));
            var modified = ref.withTransport("grpc", "grpc://127.0.0.1:" + endpoint.getPort());
            var wrongRuntime = new ObjectRef(ref.objectId(), ref.logicalName(), ref.serviceName(), "different-runtime", ref.registryContext(),
                    ref.interfaces(), ref.transport(), ref.endpoint(), ref.leaseUntil(), ref.generation(), ref.metadata());
            assertThrows(MiddlewareException.class, () -> client.reference(modified, Greeting.class, ProxyOptions.defaults()));
            MiddlewareException authorityFailure = assertThrows(MiddlewareException.class,
                    () -> client.reference(wrongRuntime, Greeting.class, ProxyOptions.defaults()));
            assertEquals(MiddlewareErrorCode.NOT_FOUND, authorityFailure.code());
            assertTrue(causes(authorityFailure).contains("not authoritative"), causes(authorityFailure));
        }
    }

    @Test void wildcardListenerPreservesTlsSanValidationForChangedAndGeneratedEndpoints() throws Exception {
        var material = GrpcTestCertificateMaterial.generate(directory.resolve("client"), "localhost");
        Path certificate = fixture("alias-test.crt");
        var serverTls = new GrpcTlsConfiguration(certificate, fixture("alias-test.key"), material.certificate(), true, "default");
        var clientTls = new GrpcTlsConfiguration(material.certificate(), material.privateKey(), certificate, true, "default");
        var store = new EmbeddedRegistryStore();
        try (var server = runtime("wildcard-server", store, new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(false, serverTls),
                    new GrpcNetworkConfiguration("0.0.0.0", 0, "127.0.0.2", 0)));
             var client = runtime("wildcard-client", store, new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(false, clientTls)));
             var exported = server.export("wildcard", new GreetingImpl(), Greeting.class, ExportOptions.defaults())) {
            assertEquals("hello Ada", client.reference(exported.ref(), Greeting.class, ProxyOptions.defaults()).greet("Ada"));
            var changed = exported.ref().withTransport("grpc", "grpc://localhost:" + URI.create(exported.ref().endpoint()).getPort());
            MiddlewareException failure = assertThrows(MiddlewareException.class,
                    () -> client.reference(changed, Greeting.class, ProxyOptions.defaults()));
            assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, failure.code());
            assertTrue(hasCause(failure, javax.net.ssl.SSLHandshakeException.class), causes(failure));
            assertTrue(hasCause(failure, java.security.cert.CertificateException.class), causes(failure));
        }
        // This is a provider-generated authoritative reference, but the certificate SAN does not match its host.
        try (var server = runtime("wrong-san-server", new EmbeddedRegistryStore(), new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(false, serverTls),
                    new GrpcNetworkConfiguration("0.0.0.0", 0, "127.0.0.1", 0)));
             var client = runtime("wrong-san-client", new EmbeddedRegistryStore(), new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(false, clientTls)));
             var exported = server.export("wrong-san", new GreetingImpl(), Greeting.class, ExportOptions.defaults())) {
            MiddlewareException failure = assertThrows(MiddlewareException.class,
                    () -> client.reference(exported.ref(), Greeting.class, ProxyOptions.defaults()));
            assertEquals(MiddlewareErrorCode.UNAUTHENTICATED, failure.code());
            assertTrue(hasCause(failure, javax.net.ssl.SSLHandshakeException.class), causes(failure));
            assertTrue(hasCause(failure, java.security.cert.CertificateException.class), causes(failure));
        }
    }

    @Test void configuredFixedPortAndDefaultsRemainCallableAndChangedReferenceIsRejected() throws Exception {
        int port = availablePort();
        var store = new EmbeddedRegistryStore();
        var provider = new GrpcTransportProvider(GrpcSecurityOptions.development(),
                new GrpcNetworkConfiguration("0.0.0.0", port, "127.0.0.2", port));
        try (var server = runtime("fixed-server", store, provider);
             var client = runtime("default-client", store, new GrpcTransportProvider());
             var exported = server.export("fixed", new GreetingImpl(), Greeting.class, ExportOptions.defaults());
             var defaultExport = client.export("default", new GreetingImpl(), Greeting.class, ExportOptions.defaults())) {
            assertThrows(MiddlewareException.class, () -> provider.start(server));
            assertEquals("grpc://127.0.0.2:" + port, exported.ref().endpoint());
            assertEquals("hello Ada", client.reference(exported.ref(), Greeting.class, ProxyOptions.defaults()).greet("Ada"));
            URI defaultEndpoint = URI.create(defaultExport.ref().endpoint());
            assertEquals("127.0.0.1", defaultEndpoint.getHost());
            assertTrue(defaultEndpoint.getPort() > 0);
            assertEquals("hello Bob", server.reference(defaultExport.ref(), Greeting.class, ProxyOptions.defaults()).greet("Bob"));
            MiddlewareException failure = assertThrows(MiddlewareException.class, () -> client.reference(
                    exported.ref().withTransport("grpc", "grpc://127.0.0.1:" + port), Greeting.class, ProxyOptions.defaults()));
            assertTrue(causes(failure).contains("not authoritative"), causes(failure));
        }
        try (var rebound = new ServerSocket()) {
            rebound.bind(new InetSocketAddress("127.0.0.2", port));
        }
    }

    @Test void invalidStartupDoesNotLeakListenerOrUsableExport() throws Exception {
        int port = availablePort();
        var provider = new GrpcTransportProvider(GrpcSecurityOptions.development(), new GrpcNetworkConfiguration("127.0.0.1", port, "unresolved.invalid", 0));
        assertThrows(MiddlewareException.class, () -> runtime("invalid-network", new EmbeddedRegistryStore(), provider));
        provider.close();
        var nonlocal = new GrpcTransportProvider(GrpcSecurityOptions.development(),
                new GrpcNetworkConfiguration("192.0.2.1", port, "127.0.0.1", 0));
        assertEquals(MiddlewareErrorCode.TRANSPORT_ERROR, assertThrows(MiddlewareException.class,
                () -> runtime("nonlocal", new EmbeddedRegistryStore(), nonlocal)).code());
        nonlocal.close();
        try (var reservation = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))) {
            var occupied = new GrpcTransportProvider(GrpcSecurityOptions.development(), new GrpcNetworkConfiguration("127.0.0.1", port, "127.0.0.1", 0));
            assertThrows(MiddlewareException.class, () -> runtime("occupied", new EmbeddedRegistryStore(), occupied));
            occupied.close();
        }
        try (var rebound = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, rebound.getLocalPort());
        }
    }

    private static Path fixture(String name) throws Exception {
        return Path.of(GrpcNetworkEndpointIT.class.getResource("/grpc-network/" + name).toURI());
    }
    private static DefaultMiddlewareRuntime runtime(String id, EmbeddedRegistryStore store, GrpcTransportProvider transport) {
        return new MiddlewareRuntimeBuilder().runtimeId(id).registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                .addTransport(transport).disableNuggetAdmin().build();
    }
    private static int availablePort() throws Exception {
        try (var socket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }
    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) if (type.isInstance(current)) return true;
        return false;
    }
    private static String causes(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) messages.append(current).append('\n');
        return messages.toString();
    }
}
