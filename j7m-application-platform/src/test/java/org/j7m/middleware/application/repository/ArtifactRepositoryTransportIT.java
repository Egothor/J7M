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

package org.j7m.middleware.application.repository;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.net.ssl.*;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactRepositoryTransportIT {
    @TempDir Path directory;
    @Test void mutualTlsChecksCertificatesHostnameAndAuthenticatedReplies() throws Exception {
        Path keyStore = directory.resolve("tls.p12"); String password = UUID.randomUUID().toString();
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-alias", "artifact", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-validity", "1", "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", password).redirectErrorStream(true).start();
        String diagnostic = new String(keytool.getInputStream().readAllBytes()); assertEquals(0, keytool.waitFor(), diagnostic);
        KeyStore store = KeyStore.getInstance("PKCS12"); try (InputStream input = Files.newInputStream(keyStore)) { store.load(input, password.toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keys.init(store, password.toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(keys.getKeyManagers(), trust.getTrustManagers(), new SecureRandom());
        SSLContext noClient = SSLContext.getInstance("TLS"); noClient.init(new KeyManager[0], trust.getTrustManagers(), new SecureRandom());
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); var signingTrust = Map.of("signer", signer.getPublic()); Path source = Files.write(directory.resolve("data"), new byte[4096]);
        ArtifactManifest manifest = ArtifactManifest.sign("tenant", "example:data:1", source, 4096, "signer", signer.getPrivate());
        try (var repository = new ResumableArtifactStore(directory.resolve("store"), signingTrust, 1048576, 8, null)) {
            repository.begin("tenant", manifest); repository.upload("tenant", manifest.id(), 0, Files.readAllBytes(source)); repository.commitManifest("tenant", manifest.id());
            try (var server = new ArtifactRepositoryServer(new InetSocketAddress("0.0.0.0", 0), repository,
                    Map.of("reader", new ArtifactRepositoryServer.Binding("tenant", new byte[32], Set.of("READ"))), new RepositoryPublishOptions("server", false), tls);
                    var valid = new ArtifactRepositoryClient(List.of(new InetSocketAddress("127.0.0.1", server.address().getPort())), "reader", new byte[32], signingTrust, directory.resolve("valid"), tls);
                    var absentCertificate = new ArtifactRepositoryClient(List.of(new InetSocketAddress("127.0.0.1", server.address().getPort())), "reader", new byte[32], signingTrust, directory.resolve("absent"), noClient);
                    var wrongHostname = new ArtifactRepositoryClient(List.of(new InetSocketAddress("127.0.0.2", server.address().getPort())), "reader", new byte[32], signingTrust, directory.resolve("hostname"), tls);
                    var untrusted = new ArtifactRepositoryClient(List.of(new InetSocketAddress("127.0.0.1", server.address().getPort())), "reader", new byte[32], signingTrust, directory.resolve("untrusted"), SSLContext.getDefault())) {
                assertEquals(manifest, valid.fetch(manifest.id(), Instant.now().plusSeconds(5)));
                assertThrows(IOException.class, () -> absentCertificate.fetch(manifest.id(), Instant.now().plusMillis(500)));
                assertThrows(IOException.class, () -> wrongHostname.fetch(manifest.id(), Instant.now().plusMillis(500)));
                assertThrows(IOException.class, () -> untrusted.fetch(manifest.id(), Instant.now().plusMillis(500)));
                assertTrue(server.rejectedRequests() >= 3);
            }
        }
    }
    @Test void replayOversizeAndWrongAuthenticationNeverReachArtifactStorage() throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); var trust = Map.of("signer", signer.getPublic()); byte[] key = new byte[32];
        try (var store = new ResumableArtifactStore(directory.resolve("store"), trust, 1048576, 8, null);
                var server = new ArtifactRepositoryServer(new InetSocketAddress("127.0.0.1", 0), store,
                    Map.of("reader", new ArtifactRepositoryServer.Binding("tenant", key, Set.of("READ"))), new RepositoryPublishOptions("server", false), null)) {
            String nonce = UUID.randomUUID().toString(); byte[] body = ArtifactManifest.encode(out -> { out.writeInt(1); out.writeUTF("reader"); out.writeUTF(nonce); out.writeLong(System.currentTimeMillis() + 10000);
                out.writeUTF("FETCH"); ArtifactProtocol.bytes(out, ArtifactManifest.encode(payload -> payload.writeUTF("a".repeat(64)))); });
            try (Socket socket = new Socket()) { socket.connect(server.address()); socket.setSoTimeout(2000); ArtifactProtocol.send(new DataOutputStream(socket.getOutputStream()), body, key);
                DataInputStream in = new DataInputStream(socket.getInputStream()); byte[] reply = ArtifactProtocol.receive(in); ArtifactProtocol.authenticate(in, reply, key); }
            try (Socket socket = new Socket()) { socket.connect(server.address()); socket.setSoTimeout(2000); ArtifactProtocol.send(new DataOutputStream(socket.getOutputStream()), body, key);
                assertThrows(IOException.class, () -> ArtifactProtocol.receive(new DataInputStream(socket.getInputStream()))); }
            try (Socket socket = new Socket()) { socket.connect(server.address()); socket.setSoTimeout(2000); new DataOutputStream(socket.getOutputStream()).writeInt(ArtifactProtocol.MAX_FRAME + 1);
                assertThrows(IOException.class, () -> ArtifactProtocol.receive(new DataInputStream(socket.getInputStream()))); }
            byte[] wrong = key.clone(); wrong[0] = 1;
            try (var client = new ArtifactRepositoryClient(List.of(server.address()), "reader", wrong, trust, directory.resolve("wrong"), null)) {
                assertThrows(IOException.class, () -> client.fetch("a".repeat(64), Instant.now().plusMillis(500)));
            }
            assertEquals(0, server.uploadedChunks()); assertEquals(0, server.downloadedChunks()); assertTrue(server.rejectedRequests() >= 3);
        }
    }
}
