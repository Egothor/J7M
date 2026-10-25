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

package org.j7m.middleware.registry.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.registry.api.PeerQuery;
import org.j7m.middleware.registry.api.RegistryClient;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.security.defaults.DeterministicRegistryRecordSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkBootstrapEndpointResolverTest {
    @TempDir Path directory;
    private Path store;

    @BeforeEach void identity() throws Exception {
        store = directory.resolve("identity.p12");
        generate(store, "SAN=ip:127.0.0.1,dns:localhost");
    }

    private void generate(final Path target, final String names) throws Exception {
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "bootstrap", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                "-ext", names, "-validity", "1", "-storetype", "PKCS12",
                "-keystore", target.toString(), "-storepass", "test-bootstrap-only")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        assertTrue(keytool.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, keytool.exitValue(), () -> read(directory.resolve("keytool.log")));
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file);
        } catch (IOException failure) {
            return "Fixture diagnostic unavailable";
        }
    }

    private static RegistryQuery query(final String service) {
        return new RegistryQuery(service, List.of(), Map.of(), "tenant", System.currentTimeMillis(), 64);
    }

    private final class Child implements AutoCloseable {
        private final Process process;
        private final String seed;
        private final String leaf;

        private Child() throws Exception {
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("j7m.runtime.test.classpath"), BootstrapTestProcess.class.getName(), store.toString())
                    .redirectError(directory.resolve("child.stderr").toFile()).start();
            try (var reader = Executors.newSingleThreadExecutor()) {
                try {
                    String ready = reader.submit(() -> new BufferedReader(new InputStreamReader(process.getInputStream(),
                            StandardCharsets.UTF_8)).readLine()).get(15, TimeUnit.SECONDS);
                    assertTrue(ready != null && ready.startsWith("READY "), () -> read(directory.resolve("child.stderr")));
                    String[] parts = ready.split(" ");
                    assertNotEquals(ProcessHandle.current().pid(), Long.parseLong(parts[1]));
                    seed = parts[2]; leaf = parts[3];
                } catch (Exception | AssertionError failure) {
                    process.destroyForcibly();
                    throw failure;
                }
            }
        }

        @Override public void close() throws Exception {
            process.getOutputStream().close();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test void remoteJvmListLookupPeerExpansionFailoverAndStrictTrustRemainDistinct() throws Exception {
        try (var child = new Child(); var resolver = new NetworkBootstrapEndpointResolver(
                BootstrapTestProcess.tls(store, true), Duration.ofSeconds(2)); var local = new EmbeddedRegistryClient()) {
            RegistryClient direct = resolver.resolve(child.leaf).orElseThrow();
            var valid = direct.list(query("valid")).getFirst();
            assertEquals("remote-runtime", valid.ref().runtimeId());
            assertEquals(valid, direct.lookup(valid.ref()).orElseThrow());
            var shared = direct.list(query("shared"));
            assertEquals(List.of("shared-a", "shared-z"), shared.stream().map(record -> record.ref().objectId()).toList());
            assertThrows(MiddlewareException.class, () -> direct.bind(valid));
            assertThrows(MiddlewareException.class, () -> direct.unbind(valid.ref()));
            assertThrows(MiddlewareException.class, () -> direct.heartbeat(valid.ref(), 0));
            assertEquals(child.leaf, resolver.resolve(child.seed).orElseThrow().peers(new PeerQuery("tenant"))
                    .getFirst().endpoints().getFirst().endpoint());
            int absentPort;
            try (var reserved = new ServerSocket(0)) { absentPort = reserved.getLocalPort(); }
            var options = new BootstrapRegistryOptions("client", "tenant",
                    List.of("bootstrap+tls://localhost:" + absentPort, child.seed), Duration.ofSeconds(4),
                    2, 8, 64, Duration.ofMillis(1), resolver);
            try (var bootstrap = new BootstrapRegistryClient(local, Clock.systemUTC(), options,
                    new DeterministicRegistryRecordSigner("bootstrap-test"), true, null)) {
                assertEquals("valid", bootstrap.list(query("valid")).getFirst().ref().objectId());
                assertTrue(bootstrap.list(query("invalid")).isEmpty());
                assertTrue(bootstrap.discoveryStatistics().peerContactFailures() > 0);
                assertTrue(bootstrap.contactedBootstrapPeerCount() >= 3);
            }
            try (var lookup = new BootstrapRegistryClient(new EmbeddedRegistryClient(), Clock.systemUTC(), options,
                    new DeterministicRegistryRecordSigner("bootstrap-test"), true, null)) {
                assertTrue(lookup.lookup(valid.ref()).isPresent());
                assertEquals("shared-z", lookup.lookup(shared.get(1).ref()).orElseThrow().ref().objectId());
            }
            direct.close();
            assertThrows(MiddlewareException.class, () -> direct.list(query("valid")));
        }
    }

    @Test void cacheDoesNotReturnLeaseExpiredAfterItsFirstAuthenticatedRead() throws Exception {
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(final ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        try (var child = new Child(); var resolver = new NetworkBootstrapEndpointResolver(
                BootstrapTestProcess.tls(store, true), Duration.ofSeconds(2)); var local = new EmbeddedRegistryClient()) {
            var options = new BootstrapRegistryOptions("client", "tenant", List.of(child.leaf), Duration.ofSeconds(2),
                    1, 4, 64, Duration.ofMinutes(2), resolver);
            try (var bootstrap = new BootstrapRegistryClient(local, clock, options,
                    new DeterministicRegistryRecordSigner("bootstrap-test"), true, null)) {
                var first = bootstrap.list(query("short"));
                assertEquals(1, first.size());
                now.set(first.getFirst().leaseUntilMillis() + 1);
                assertTrue(bootstrap.list(query("short")).isEmpty());
            }
        }
    }

    @Test void tlsClientIdentityContextAndClosedResolverAreEnforced() throws Exception {
        try (var child = new Child(); var resolver = new NetworkBootstrapEndpointResolver(
                BootstrapTestProcess.tls(store, true), Duration.ofSeconds(2));
                var anonymous = new NetworkBootstrapEndpointResolver(BootstrapTestProcess.tls(store, false), Duration.ofSeconds(2))) {
            assertThrows(MiddlewareException.class, () -> anonymous.resolve(child.leaf).orElseThrow().list(query("valid")));
            var denied = new RegistryQuery("valid", List.of(), Map.of(), "other-tenant", System.currentTimeMillis(), 1);
            assertThrows(MiddlewareException.class, () -> resolver.resolve(child.leaf).orElseThrow().list(denied));
            for (String invalid : List.of("http://localhost:1", "bootstrap+tls://user@localhost:1", "bootstrap+tls://localhost",
                    "bootstrap+tls://localhost:1/path", "bootstrap+tls://localhost:1?token=secret")) {
                assertTrue(resolver.resolve(invalid).isEmpty());
            }
            RegistryClient facade = resolver.resolve(child.leaf).orElseThrow();
            resolver.close();
            assertThrows(MiddlewareException.class, () -> facade.list(query("valid")));
            assertThrows(MiddlewareException.class, () -> resolver.resolve(child.leaf));
        }
        assertThrows(IllegalArgumentException.class, () -> new NetworkBootstrapEndpointResolver(
                BootstrapTestProcess.tls(store, true), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new BootstrapRegistryServer(new InetSocketAddress(0),
                BootstrapTestProcess.tls(store, true), new EmbeddedRegistryClient(), Set.of(), Duration.ofSeconds(1)));
    }

    @Test void untrustedCertificateAndWrongHostnameAreRejectedByActualTls() throws Exception {
        Path other = directory.resolve("other.p12");
        generate(other, "SAN=dns:wrong.example");
        var otherTls = BootstrapTestProcess.tls(other, true);
        try (var registry = new EmbeddedRegistryClient();
                var server = new BootstrapRegistryServer(new InetSocketAddress("127.0.0.1", 0), otherTls,
                        registry, Set.of("tenant"), Duration.ofSeconds(2));
                var matchingTrust = new NetworkBootstrapEndpointResolver(otherTls, Duration.ofSeconds(2));
                var wrongTrust = new NetworkBootstrapEndpointResolver(BootstrapTestProcess.tls(store, true), Duration.ofSeconds(2))) {
            String endpoint = "bootstrap+tls://localhost:" + server.address().getPort();
            var mismatch = assertThrows(MiddlewareException.class, () -> matchingTrust.resolve(endpoint).orElseThrow().list(query("valid")));
            assertEquals("Bootstrap endpoint unavailable", mismatch.getMessage());
            assertThrows(MiddlewareException.class, () -> wrongTrust.resolve(endpoint).orElseThrow().list(query("valid")));
        }
    }

    @Test void peerReadSharesTraversalDeadlineAndServerCloseDoesNotWaitForBorrowedBackend() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        try (var registry = new EmbeddedRegistryClient()) {
            RegistryClient backend = new RegistryClient() {
                @Override public void bind(final org.j7m.middleware.registry.api.RegistryRecord value) { registry.bind(value); }
                @Override public void unbind(final org.j7m.middleware.api.ObjectRef ref) { registry.unbind(ref); }
                @Override public void heartbeat(final org.j7m.middleware.api.ObjectRef ref, final long lease) { registry.heartbeat(ref, lease); }
                @Override public java.util.Optional<org.j7m.middleware.registry.api.RegistryRecord> lookup(final org.j7m.middleware.api.ObjectRef ref) { return registry.lookup(ref); }
                @Override public List<org.j7m.middleware.registry.api.RegistryRecord> list(final RegistryQuery value) { return registry.list(value); }
                @Override public List<org.j7m.middleware.registry.api.RegistryPeerStatus> peers(final PeerQuery value) {
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exited.countDown();
                    }
                    return List.of();
                }
                @Override public void close() { }
            };
            try (var server = new BootstrapRegistryServer(new InetSocketAddress("127.0.0.1", 0),
                    BootstrapTestProcess.tls(store, true), backend, Set.of("tenant"), Duration.ofSeconds(2));
                    var resolver = new NetworkBootstrapEndpointResolver(BootstrapTestProcess.tls(store, true), Duration.ofSeconds(2))) {
                String endpoint = "bootstrap+tls://localhost:" + server.address().getPort();
                assertTrue(resolver.resolve(endpoint).orElseThrow().list(query("absent")).isEmpty());
                var options = new BootstrapRegistryOptions("client", "tenant", List.of(endpoint), Duration.ofMillis(400),
                        1, 2, 64, Duration.ofMillis(1), resolver);
                long start = System.nanoTime();
                try (var bootstrap = new BootstrapRegistryClient(new EmbeddedRegistryClient(), options)) {
                    assertTrue(bootstrap.list(query("absent")).isEmpty());
                    assertEquals(0L, entered.getCount());
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1200);
                }
                server.close();
                assertTrue(registry.list(query("absent")).isEmpty());
            } finally {
                release.countDown();
            }
            assertTrue(exited.await(2, TimeUnit.SECONDS));
        }
    }

    @Test void malformedAuthenticatedResponseBecomesSafeUnavailableRatherThanEscapingCodecFailure() throws Exception {
        var tls = BootstrapTestProcess.tls(store, true);
        try (var server = (javax.net.ssl.SSLServerSocket) tls.getServerSocketFactory().createServerSocket(0);
                var executor = Executors.newSingleThreadExecutor();
                var resolver = new NetworkBootstrapEndpointResolver(tls, Duration.ofSeconds(2))) {
            server.setNeedClientAuth(true);
            var served = executor.submit(() -> {
                try (var socket = (javax.net.ssl.SSLSocket) server.accept()) {
                    socket.setSoTimeout(2000); socket.startHandshake();
                    BootstrapWire.receive(new java.io.DataInputStream(socket.getInputStream()));
                    BootstrapWire.send(new java.io.DataOutputStream(socket.getOutputStream()), BootstrapWire.encode(output -> {
                        output.writeInt(BootstrapWire.MAGIC); output.writeInt(BootstrapWire.VERSION);
                        BootstrapWire.bytes(output, new byte[]{0, 1, 2});
                    }));
                }
                return true;
            });
            var failure = assertThrows(MiddlewareException.class, () -> resolver.resolve(
                    "bootstrap+tls://localhost:" + server.getLocalPort()).orElseThrow().list(query("valid")));
            assertEquals("Bootstrap endpoint unavailable", failure.getMessage());
            assertTrue(served.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void silentTlsEndpointAndAllSeedsUseOriginalOverallDeadline() throws Exception {
        try (var silent = new ServerSocket(0); var resolver = new NetworkBootstrapEndpointResolver(
                BootstrapTestProcess.tls(store, true), Duration.ofSeconds(2)); var local = new EmbeddedRegistryClient()) {
            String endpoint = "bootstrap+tls://localhost:" + silent.getLocalPort();
            assertTrue(resolver.resolve(endpoint).isPresent());
            long started = System.nanoTime();
            var options = new BootstrapRegistryOptions("client", "tenant", List.of(endpoint,
                    "bootstrap+tls://127.0.0.1:" + silent.getLocalPort()), Duration.ofMillis(250), 2, 8, 64,
                    Duration.ofMillis(1), resolver);
            try (var bootstrap = new BootstrapRegistryClient(local, options)) {
                assertTrue(bootstrap.list(query("valid")).isEmpty());
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertTrue(elapsed < 1200, "Overall bootstrap deadline must cover all seeds");
                assertTrue(bootstrap.contactedBootstrapPeerCount() <= 2);
            }
        }
    }
}
