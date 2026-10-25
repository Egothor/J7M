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

package org.j7m.middleware.consensus;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkConsensusTransportIT {
    @TempDir Path directory;
    @Test void mutualTlsCommitsAndRejectsMissingClientCertificate() throws Exception {
        Path keyStore = directory.resolve("ephemeral.p12"); String password = UUID.randomUUID().toString();
        Process generate = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "node", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=ephemeral-loopback-test",
                "-validity", "1", "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", password).redirectErrorStream(true).start();
        String generationOutput = new String(generate.getInputStream().readAllBytes()); assertEquals(0, generate.waitFor(), generationOutput);
        KeyStore store = KeyStore.getInstance("PKCS12"); try (InputStream in = Files.newInputStream(keyStore)) { store.load(in, password.toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keys.init(store, password.toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(keys.getKeyManagers(), trust.getTrustManagers(), new SecureRandom());
        int port; try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var config = new NetworkConsensusConfig("a", Map.of("a", new InetSocketAddress("127.0.0.1", port)), Set.of("a"), directory.resolve("node"),
                new byte[32], Duration.ofMillis(180), Duration.ofMillis(30), 65536, 1 << 20, tls);
        var state = new NetworkConsensusNodeProcess.Counter();
        try (var node = new NetworkConsensusNode(config, state)) {
            node.start(); NetworkConsensusNodeIT.await(() -> node.status().leaderId().equals("a"), 5);
            node.append("tls", new byte[8], Instant.now().plusSeconds(3));
            SSLContext missingClient = SSLContext.getInstance("TLS"); missingClient.init(new KeyManager[0], trust.getTrustManagers(), new SecureRandom());
            try (SSLSocket socket = (SSLSocket) missingClient.getSocketFactory().createSocket("127.0.0.1", port)) {
                socket.setSoTimeout(2000);
                assertThrows(IOException.class, () -> { socket.startHandshake(); socket.getOutputStream().write(new byte[4]); socket.getInputStream().read(); });
            }
            assertEquals("1,0,2", state.text());
        }
    }
    @Test void unknownIdentityInvalidMacAndOversizedFramesNeverChangeTerm() throws Exception {
        int port; try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var config = new NetworkConsensusConfig("a", Map.of("a", new InetSocketAddress("127.0.0.1", port)), Set.of("a"), directory,
                new byte[32], Duration.ofMillis(180), Duration.ofMillis(30), 4096, 65536);
        try (var node = new NetworkConsensusNode(config, new NetworkConsensusNodeProcess.Counter())) {
            node.start(); NetworkConsensusNodeIT.await(() -> node.status().leaderId().equals("a"), 5); long term = node.status().term();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream frame = new DataOutputStream(bytes);
            frame.writeInt(1); frame.writeUTF(config.groupId()); frame.writeUTF("intruder"); frame.writeUTF("a"); frame.writeUTF("nonce"); frame.writeByte(1);
            frame.writeLong(9999); frame.writeLong(9999); frame.writeLong(9999); frame.close();
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            for (boolean validMac : List.of(true, false)) try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(2000); DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeInt(bytes.size()); out.write(bytes.toByteArray()); out.write(validMac ? mac.doFinal(bytes.toByteArray()) : new byte[32]); out.flush();
                assertEquals(-1, socket.getInputStream().read());
            }
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(2000); new DataOutputStream(socket.getOutputStream()).writeInt(4097);
                assertEquals(-1, socket.getInputStream().read());
            }
            assertEquals(term, node.status().term()); assertTrue(node.readIndex(Instant.now().plusSeconds(3)) > 0);
        }
    }
    @Test void authenticatedCrossGroupPacketsAndWrongGroupRecoveryAreRejected() throws Exception {
        int port; try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var peers = Map.of("a", new InetSocketAddress("127.0.0.1", port));
        var config = new NetworkConsensusConfig("a", peers, Set.of("a"), directory, new byte[32], Duration.ofMillis(180), Duration.ofMillis(30), 4096, 65536, null, "group-one");
        try (var node = new NetworkConsensusNode(config, new NetworkConsensusNodeProcess.Counter())) {
            node.start(); NetworkConsensusNodeIT.await(() -> node.status().leaderId().equals("a"), 5); long term = node.status().term();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(1); out.writeUTF("group-two"); out.writeUTF("a"); out.writeUTF("a"); out.writeUTF("nonce"); out.writeByte(1);
            out.writeLong(9999); out.writeLong(9999); out.writeLong(9999); out.close();
            assertRejected(port, bytes.toByteArray()); assertEquals(term, node.status().term());
        }
        var other = new NetworkConsensusConfig("a", peers, Set.of("a"), directory, new byte[32], Duration.ofMillis(180), Duration.ofMillis(30), 4096, 65536, null, "group-two");
        assertThrows(IOException.class, () -> new NetworkConsensusNode(other, new NetworkConsensusNodeProcess.Counter()));
    }
    @Test void corruptedAndFutureSnapshotNeverPublishAndOnlyOnePartialIsRetained() throws Exception {
        int port; try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var config = new NetworkConsensusConfig("a", Map.of("a", new InetSocketAddress("127.0.0.1", port)), Set.of("a"), directory,
                new byte[32], Duration.ofMillis(180), Duration.ofMillis(30), 4096, 65536);
        var state = new NetworkConsensusNodeProcess.Counter();
        try (var node = new NetworkConsensusNode(config, state)) {
            node.start(); NetworkConsensusNodeIT.await(() -> node.status().leaderId().equals("a"), 5);
            node.append("seed", new byte[8], Instant.now().plusSeconds(3)); long committed = node.status().commitIndex(); String before = state.text();
            for (int variant = 0; variant < 3; variant++) {
                byte[] manifest = java.nio.ByteBuffer.allocate(4).putInt(2 + variant).array();
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(manifest); if (variant == 0) digest[0] ^= 1;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
                out.writeInt(1); out.writeUTF(config.groupId()); out.writeUTF("a"); out.writeUTF("a"); out.writeUTF("snapshot" + variant); out.writeByte(3);
                out.writeLong(100 + variant); out.writeLong(100); out.writeLong(manifest.length); out.writeLong(0); out.write(digest); out.writeInt(manifest.length); out.write(manifest); out.close();
                assertRejected(port, bytes.toByteArray());
                assertEquals(committed, node.status().commitIndex()); assertEquals(before, state.text());
                try (var files = Files.list(directory)) { assertEquals(1, files.filter(path -> path.toString().endsWith(".partial")).count()); }
            }
        }
        try (var restored = new NetworkConsensusNode(config, new NetworkConsensusNodeProcess.Counter())) { assertEquals(2, restored.status().commitIndex()); }
    }
    private static void assertRejected(int port, byte[] frame) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000); DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(frame.length); out.write(frame); out.write(mac.doFinal(frame)); out.flush();
            assertEquals(-1, socket.getInputStream().read());
        }
    }

}
