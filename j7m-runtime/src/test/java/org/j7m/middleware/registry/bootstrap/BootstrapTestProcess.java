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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ObjectState;
import org.j7m.middleware.registry.api.RegistryEndpoint;
import org.j7m.middleware.registry.api.RegistryPeerQuality;
import org.j7m.middleware.registry.api.RegistryPeerState;
import org.j7m.middleware.registry.api.RegistryPeerStatus;
import org.j7m.middleware.registry.api.RegistryRecord;
import org.j7m.middleware.registry.api.RegistryTrustStatus;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.security.defaults.DeterministicRegistryRecordSigner;

/**
 * Independent registry JVM fixture with real mTLS and a two-hop registry graph.
 */
public final class BootstrapTestProcess {
    private BootstrapTestProcess() {
    }

    static SSLContext tls(final Path file, final boolean identity) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(file)) {
            store.load(input, "test-bootstrap-only".toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "test-bootstrap-only".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(identity ? keys.getKeyManagers() : new javax.net.ssl.KeyManager[0], trust.getTrustManagers(), null);
        return tls;
    }

    static RegistryRecord record(final String id, final long expires) {
        return record(id, id, expires);
    }

    static RegistryRecord record(final String id, final String service, final long expires) {
        long now = System.currentTimeMillis();
        var ref = new ObjectRef(id, id, service, "remote-runtime", "tenant", List.of("example.Service"),
                "grpc", "grpc://remote:1234", expires, 1, Map.of("region", "test"));
        return new RegistryRecord(ref, Map.of("region", "test"), now, now, expires, ObjectState.EXECUTED);
    }

    static RegistryPeerStatus peer(final String id, final String endpoint) {
        return new RegistryPeerStatus(id, true, RegistryPeerState.ALIVE, false, RegistryPeerQuality.FAST,
                false, RegistryTrustStatus.UNVERIFIED, 7, 8, 0.1, 1,
                List.of(new RegistryEndpoint("bootstrap", endpoint, Map.of("zone", "local"))),
                List.of(1), List.of("tenant"), "source", 9, Map.of("test", "peer"));
    }

    /**
     * Runs the independently hosted read-only registry until stdin closes.
     * @param arguments generated test keystore path
     * @throws Exception if startup or I/O fails
     */
    public static void main(final String[] arguments) throws Exception {
        SSLContext tls = tls(Path.of(arguments[0]), true);
        var signer = new DeterministicRegistryRecordSigner("bootstrap-test");
        try (var leaf = new EmbeddedRegistryClient(); var root = new EmbeddedRegistryClient()) {
            for (String id : List.of("valid", "invalid", "short")) {
                var record = record(id, System.currentTimeMillis() + 60_000);
                leaf.bind(record.withAuthentication(id.equals("invalid")
                        ? Map.of("signature", "invalid", "keyId", "bootstrap-test") : signer.sign(record)));
            }
            for (String id : List.of("shared-a", "shared-z")) {
                var record = record(id, "shared", System.currentTimeMillis() + 60_000);
                leaf.bind(record.withAuthentication(signer.sign(record)));
            }
            try (var leafServer = new BootstrapRegistryServer(new InetSocketAddress("127.0.0.1", 0), tls,
                    leaf, Set.of("tenant"), Duration.ofSeconds(2))) {
                String leafEndpoint = "bootstrap+tls://localhost:" + leafServer.address().getPort();
                root.updatePeer(peer("leaf", leafEndpoint));
                try (var rootServer = new BootstrapRegistryServer(new InetSocketAddress("127.0.0.1", 0), tls,
                        root, Set.of("tenant"), Duration.ofSeconds(2))) {
                    System.out.println("READY " + ProcessHandle.current().pid() + " "
                            + "bootstrap+tls://localhost:" + rootServer.address().getPort() + " " + leafEndpoint);
                    System.out.flush();
                    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                }
            }
        }
    }
}
