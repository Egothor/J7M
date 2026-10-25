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

package org.j7m.middleware.distributed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.time.Instant;
import java.nio.file.*;
import java.security.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.io.TempDir;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RemoteResourceEndpointIT {
    @TempDir Path directory;
    @Test void mutualTlsAuthenticatesCertificatesAndChecksServerHostname() throws Exception {
        Path keyStore = directory.resolve("resource-tls.p12"); String password = UUID.randomUUID().toString();
        Process generate = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "resource", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-validity", "1", "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", password).redirectErrorStream(true).start();
        String generationOutput = new String(generate.getInputStream().readAllBytes());
        assertEquals(0, generate.waitFor(), generationOutput);
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyStore)) { store.load(input, password.toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keys.init(store, password.toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(keys.getKeyManagers(), trust.getTrustManagers(), new SecureRandom());
        SSLContext missingClient = SSLContext.getInstance("TLS"); missingClient.init(new KeyManager[0], trust.getTrustManagers(), new SecureRandom());
        try (var server = new RemoteResourceServer(new InetSocketAddress("127.0.0.1", 0),
                Map.of("application", new RemoteResourceServer.Binding(endpoint(), APPLICATION_KEY, false)), tls)) {
            var valid = new RemoteResourceClient(List.of(server.address()), "application", APPLICATION_KEY, tls);
            assertEquals(new ReplicatedResources.Value("secure", 9), valid.read("secure", deadline()));
            var invalid = new RemoteResourceClient(List.of(server.address()), "application", APPLICATION_KEY, missingClient);
            assertThrows(IOException.class, () -> invalid.execute("BATCH", "id", List.of(), deadline()));
            assertEquals(0, calls.get());
        }
    }
    private static final byte[] APPLICATION_KEY = new byte[32];
    private static final byte[] COORDINATOR_KEY = new byte[32];
    static { Arrays.fill(APPLICATION_KEY, (byte) 1); Arrays.fill(COORDINATOR_KEY, (byte) 2); }
    private final AtomicInteger calls = new AtomicInteger();
    private ResourceEndpoint endpoint() {
        return new ResourceEndpoint() {
            @Override public ReplicatedResources.Result execute(String operation, String request, List<String> arguments, Instant deadline) {
                calls.incrementAndGet(); return new ReplicatedResources.Result(operation, request + arguments, 7);
            }
            @Override public ReplicatedResources.Value read(String key, Instant deadline) { return key.equals("missing") ? null : new ReplicatedResources.Value(key, 9); }
            @Override public ReplicatedResources.Lease lease(String key, Instant deadline) {
                return key.equals("missing") ? null : new ReplicatedResources.Lease("owner", 9, 10000, 5, 3);
            }
            @Override public ReplicatedResources.Result advanceTime(String request, Instant deadline) {
                calls.incrementAndGet(); return new ReplicatedResources.Result("OK", "1000", 10);
            }
            @Override public ReplicatedResources.Result transaction(String tx, Instant deadline) { return tx.equals("missing") ? null : new ReplicatedResources.Result("PREPARED", tx, 8); }
            @Override public List<String> prepared(Instant deadline) { return List.of("tx"); }
            @Override public List<String> participants(String tx, Instant deadline) { return List.of("one", "two"); }
        };
    }
    private RemoteResourceServer server() throws IOException {
        return new RemoteResourceServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), Map.of(
                "application", new RemoteResourceServer.Binding(endpoint(), APPLICATION_KEY, false),
                "coordinator", new RemoteResourceServer.Binding(endpoint(), COORDINATOR_KEY, true)), null);
    }
    private Instant deadline() { return Instant.now().plusSeconds(10); }
    @Test void leaseWirePreservesFencesAndTimeMaintenanceRequiresCoordinatorIdentity() throws Exception {
        try (var server = server()) {
            var application = new RemoteResourceClient(List.of(server.address()), "application", APPLICATION_KEY, null);
            var coordinator = new RemoteResourceClient(List.of(server.address()), "coordinator", COORDINATOR_KEY, null);
            assertEquals(new ReplicatedResources.Lease("owner", 9, 10000, 5, 3), application.lease("lease", deadline()));
            assertNull(application.lease("missing", deadline()));
            assertThrows(SecurityException.class, () -> application.advanceTime("forbidden", deadline()));
            assertEquals(0, calls.get());
            assertEquals(new ReplicatedResources.Result("OK", "1000", 10), coordinator.advanceTime("time", deadline()));
            assertEquals(1, calls.get());
        }
    }
    @Test void realSocketRoundTripsEveryEndpointOperation() throws Exception {
        try (var server = server()) {
            var client = new RemoteResourceClient(List.of(server.address()), "application", APPLICATION_KEY, null);
            assertEquals(new ReplicatedResources.Result("BATCH", "request[key, 0, value]", 7), client.execute("BATCH", "request", List.of("key", "0", "value"), deadline()));
            assertEquals(new ReplicatedResources.Value("key", 9), client.read("key", deadline()));
            assertEquals(new ReplicatedResources.Result("PREPARED", "tx", 8), client.transaction("tx", deadline()));
            assertEquals(List.of("tx"), client.prepared(deadline()));
            assertEquals(List.of("one", "two"), client.participants("tx", deadline()));
            assertNull(client.read("missing", deadline()));
            assertNull(client.transaction("missing", deadline()));
            assertEquals(7, server.dispatchedRequests());
            assertEquals(0, server.rejectedRequests());
        }
    }
    @Test void serverBindsCoordinatorPrivilegeAndRejectsForeignCredentialsBeforeMutation() throws Exception {
        try (var server = server()) {
            var application = new RemoteResourceClient(List.of(server.address()), "application", APPLICATION_KEY, null);
            for (String operation : List.of("COMMIT", "ROLLBACK", "DECIDE", "SEAL", "TIME")) {
                assertThrows(SecurityException.class, () -> application.execute(operation, "id", List.of(), deadline()));
            }
            var forged = new RemoteResourceClient(List.of(server.address()), "coordinator", APPLICATION_KEY, null);
            assertThrows(IOException.class, () -> forged.execute("COMMIT", "id", List.of(), deadline()));
            assertEquals(0, calls.get());
            var coordinator = new RemoteResourceClient(List.of(server.address()), "coordinator", COORDINATOR_KEY, null);
            assertEquals("COMMIT", coordinator.execute("COMMIT", "id", List.of(), deadline()).status());
            assertEquals(1, calls.get());
        }
    }
    @Test void failsOverFromUnavailableReplicaWithoutChangingRequestIdentity() throws Exception {
        int unavailable;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { unavailable = closed.getLocalPort(); }
        try (var server = server()) {
            var client = new RemoteResourceClient(List.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), unavailable), server.address()), "application", APPLICATION_KEY, null);
            assertEquals("stable-id[v]", client.execute("BATCH", "stable-id", List.of("v"), deadline()).value());
            assertEquals(1, calls.get());
        }
    }
    @Test void rejectsFrameLimitsExpiredRequestsInsecureBindAndReplay() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new RemoteResourceServer(new InetSocketAddress("0.0.0.0", 0), Map.of("p", new RemoteResourceServer.Binding(endpoint(), APPLICATION_KEY, false)), null));
        assertThrows(IllegalArgumentException.class, () -> new RemoteResourceClient(List.of(new InetSocketAddress("192.0.2.1", 1)), "p", APPLICATION_KEY, null));
        try (var server = server()) {
            var client = new RemoteResourceClient(List.of(server.address()), "application", APPLICATION_KEY, null);
            assertThrows(IOException.class, () -> client.execute("BATCH", "id", List.of("x".repeat(65537)), deadline()));
            assertThrows(IOException.class, () -> client.read("key", Instant.now().minusSeconds(1)));
            try (Socket socket = new Socket()) {
                socket.connect(server.address()); socket.setSoTimeout(2000);
                new DataOutputStream(socket.getOutputStream()).writeInt(ResourceWire.MAX_FRAME + 1);
                assertEquals(-1, socket.getInputStream().read());
            }
            String nonce = UUID.randomUUID().toString();
            byte[] body = ResourceWire.encode(out -> {
                out.writeInt(1); ResourceWire.text(out, "application"); ResourceWire.text(out, nonce); out.writeLong(deadline().toEpochMilli());
                ResourceWire.text(out, "EXECUTE"); ResourceWire.text(out, "BATCH"); ResourceWire.text(out, "stable"); ResourceWire.strings(out, List.of());
            });
            for (int i = 0; i < 2; i++) {
                try (Socket socket = new Socket()) {
                    socket.connect(server.address()); socket.setSoTimeout(2000);
                    ResourceWire.send(new DataOutputStream(socket.getOutputStream()), body, APPLICATION_KEY);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    if (i == 0) { byte[] reply = ResourceWire.receive(input); ResourceWire.authenticate(input, reply, APPLICATION_KEY); }
                    else assertThrows(IOException.class, () -> ResourceWire.receive(input));
                }
            }
            assertEquals(1, calls.get());
        }
    }
}
