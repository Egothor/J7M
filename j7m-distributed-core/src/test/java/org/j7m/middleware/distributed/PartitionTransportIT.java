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
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.net.ssl.*;
import org.j7m.middleware.consensus.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PartitionTransportIT {
    @TempDir Path directory;
    @Test void actualMutualTlsRequiresClientCertificateAndVerifiesHostname() throws Exception {
        Path keyStore = directory.resolve("partition.p12"); String password = UUID.randomUUID().toString();
        Process generation = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "partition", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-validity", "1", "-storetype", "PKCS12",
                "-keystore", keyStore.toString(), "-storepass", password).redirectErrorStream(true).start();
        String diagnostic = new String(generation.getInputStream().readAllBytes()); assertEquals(0, generation.waitFor(), diagnostic);
        KeyStore store = KeyStore.getInstance("PKCS12"); try (InputStream input = Files.newInputStream(keyStore)) { store.load(input, password.toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keys.init(store, password.toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(keys.getKeyManagers(), trust.getTrustManagers(), new SecureRandom());
        SSLContext missingCertificate = SSLContext.getInstance("TLS"); missingCertificate.init(new KeyManager[0], trust.getTrustManagers(), new SecureRandom());
        int consensusPort; try (ServerSocket reservation = new ServerSocket(0)) { consensusPort = reservation.getLocalPort(); }
        var peers = Map.of("n", new InetSocketAddress("127.0.0.1", consensusPort));
        var state = new PartitionStateMachine("p", "g", 1, new ReplicatedResources(), new byte[32]);
        var config = new NetworkConsensusConfig("n", peers, peers.keySet(), directory.resolve("node"), new byte[32],
                Duration.ofMillis(450), Duration.ofMillis(70), 1048576, 16777216);
        var clock = new MeasuredClockHealth(System::currentTimeMillis, System::nanoTime, System::currentTimeMillis, () -> 1);
        try (var node = new NetworkConsensusNode(config, state)) {
            node.start();
            try (var server = new PartitionServer(new InetSocketAddress("0.0.0.0", 0), node, state, null,
                    Map.of("app", new PartitionServer.Binding("tenant/test", new byte[32], false, false)), clock, tls)) {
                int port = server.address().getPort();
                var client = new PartitionClient(List.of(new InetSocketAddress("127.0.0.1", port)), "app", new byte[32], tls);
                assertEquals("ACTIVE", client.status(Instant.now().plusSeconds(5)).phase());
                var noCertificate = new PartitionClient(List.of(new InetSocketAddress("127.0.0.1", port)), "app", new byte[32], missingCertificate);
                assertThrows(IOException.class, () -> noCertificate.status(Instant.now().plusSeconds(1)));
                var wrongHostname = new PartitionClient(List.of(new InetSocketAddress("127.0.0.2", port)), "app", new byte[32], tls);
                assertThrows(IOException.class, () -> wrongHostname.status(Instant.now().plusSeconds(1)));
                assertEquals("ACTIVE", client.status(Instant.now().plusSeconds(5)).phase());
                assertTrue(server.rejectedRequests() >= 2);
            }
        }
    }
}
