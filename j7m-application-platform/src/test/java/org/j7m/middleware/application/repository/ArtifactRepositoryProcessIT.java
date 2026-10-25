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
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactRepositoryProcessIT {
    @TempDir Path directory;
    private final class Actor implements AutoCloseable {
        final String mode;
        final int port;
        final Path source, manifest, publicKey, output;
        Process process; BufferedWriter input; BufferedReader reader;
        Actor(String mode, int port, Path source, Path manifest, Path publicKey, Path output) throws Exception {
            this.mode = mode; this.port = port; this.source = source; this.manifest = manifest; this.publicKey = publicKey; this.output = output; start();
        }
        void start() throws Exception {
            String classpath = System.getProperty("j7m.artifact.test.classpath", System.getProperty("java.class.path"));
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx192m", "-cp", classpath,
                    ArtifactRepositoryNodeProcess.class.getName(), mode, directory.resolve(mode).toString(), Integer.toString(port), publicKey.toString(), source.toString(), manifest.toString(), output.toString())
                    .redirectError(directory.resolve(mode + ".stderr").toFile()).start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream())); reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            assertEquals("READY", read(), () -> { try { return Files.readString(directory.resolve(mode + ".stderr")); } catch (IOException failure) { return failure.toString(); } });
        }
        String command(String command) throws Exception { input.write(command); input.newLine(); input.flush(); return read(); }
        String read() throws Exception { return CompletableFuture.supplyAsync(() -> { try { return reader.readLine(); } catch (IOException failure) { throw new UncheckedIOException(failure); } }).get(15, TimeUnit.SECONDS); }
        void kill() throws Exception { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
        @Override public void close() { if (process != null && process.isAlive()) { process.destroyForcibly(); try { process.waitFor(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } } }
    }
    @Test void publicReplicationMovesApplicationAcrossTwoIndependentRepositoryJvmsAndResumesDestination() throws Exception {
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); Path descriptor = directory.resolve("application.json");
        Files.writeString(descriptor, """
            {"schemaVersion":"j7m.application/v1","application":{"group":"example","name":"replicated","version":"1","displayName":"Replicated","description":"Network replication"},
             "runtime":{"minJ7mVersion":"0.6.6","javaVersion":"25","moduleMode":"CLASSPATH","bootstrapClass":"example.Main"},"services":[{"serviceName":"//apps/test/replicated/Service","interfaceName":"example.Service","implementationClass":"example.ServiceImpl","transport":"local","globallyExported":false,"metadata":{}}],
             "security":{"trustDomain":"dev","confidential":false},"resources":{"cpu":"1","memory":"128MiB","maxConcurrentInvocations":8,"maxInboundPayload":"1MiB"}}
            """);
        Path documentation = directory.resolve("data.txt"); byte[] padding = new byte[16000]; new Random(37334L).nextBytes(padding); Files.write(documentation, padding);
        var bundle = new J7mBundleWriter().write(descriptor, directory.resolve("application.j7m"), List.of(), List.of(documentation));
        Path publicKey = Files.write(directory.resolve("public.der"), key.getPublic().getEncoded());
        var manifest = ArtifactManifest.sign("tenant", "example:replicated:1", bundle.path(), 4096, "signer", key.getPrivate());
        Path metadata = Files.write(directory.resolve("signed-manifest"), manifest.encode()), output = directory.resolve("unused");
        int sourcePort, targetPort; try (ServerSocket socket = new ServerSocket(0)) { sourcePort = socket.getLocalPort(); } try (ServerSocket socket = new ServerSocket(0)) { targetPort = socket.getLocalPort(); }
        byte[] authentication = new byte[32]; Arrays.fill(authentication, (byte) 1); var trust = Map.of("signer", key.getPublic());
        try (var source = new Actor("source-server", sourcePort, bundle.path(), metadata, publicKey, output); var target = new Actor("target-server", targetPort, bundle.path(), metadata, publicKey, output);
                var sourceClient = new ArtifactRepositoryClient(List.of(new InetSocketAddress("127.0.0.1", sourcePort)), "publisher", authentication, trust, directory.resolve("source-client"), null);
                var targetClient = new ArtifactRepositoryClient(List.of(new InetSocketAddress("127.0.0.1", targetPort)), "publisher", authentication, trust, directory.resolve("target-client"), null)) {
            sourceClient.upload(bundle.path(), manifest, java.time.Instant.now().plusSeconds(10)); assertTrue(sourceClient.publishApplication(manifest.id(), java.time.Instant.now().plusSeconds(5)).success());
            targetClient.begin(manifest, java.time.Instant.now().plusSeconds(5));
            try (InputStream input = Files.newInputStream(bundle.path())) { targetClient.uploadChunk(manifest, 0, input.readNBytes(4096), java.time.Instant.now().plusSeconds(5)); }
            assertEquals("ACK MISSING", target.command("APP " + manifest.coordinate()));
            source.kill(); target.kill(); source.start(); target.start();
            assertTrue(sourceClient.replicateTo(targetClient, manifest.id(), java.time.Instant.now().plusSeconds(15)).success());
            assertEquals("ACK " + manifest.contentHash(), target.command("APP " + manifest.coordinate()));
            assertEquals("ACK " + (manifest.chunkHashes().size() - 1) + " 0", target.command("STATS"));
            long uploaded = targetClient.uploadedChunks();
            assertTrue(sourceClient.replicateTo(targetClient, manifest.id(), java.time.Instant.now().plusSeconds(15)).success()); assertEquals(uploaded, targetClient.uploadedChunks());
        }
    }
    @Test void interruptsEveryChunkAndRestartsBothJvmsWithoutRetransmittingVerifiedPayloads() throws Exception {
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); Path source = directory.resolve("source.bin"), metadata = directory.resolve("manifest.bin"), publicKey = directory.resolve("public.der"), output = directory.resolve("download.bin");
        byte[] payload = new byte[24599]; new Random(734837L).nextBytes(payload); Files.write(source, payload); Files.write(publicKey, key.getPublic().getEncoded());
        ArtifactManifest manifest = ArtifactManifest.sign("tenant", "example:data:1", source, 8192, "signer", key.getPrivate()); Files.write(metadata, manifest.encode());
        int port; try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        try (var server = new Actor("server", port, source, metadata, publicKey, output); var client = new Actor("client", port, source, metadata, publicKey, output)) {
            assertEquals("ACK []", client.command("BEGIN")); Set<Integer> retained = new TreeSet<>();
            for (int n = 0; n < manifest.chunkHashes().size(); n++) {
                assertEquals("ACK", client.command("PARTIAL " + n)); client.kill(); server.kill(); server.start(); client.start();
                assertEquals("ACK " + retained, client.command("RESUME"));
                assertEquals("ACK", client.command("PUT " + n)); retained.add(n); client.kill(); server.kill(); server.start(); client.start();
                assertEquals("ACK " + retained, client.command("RESUME")); assertEquals("ACK 0 0", server.command("STATS"));
            }
            assertEquals("ACK 0", client.command("FINISH")); assertEquals("ACK 0 0", server.command("STATS"));
            for (int n = 0; n < manifest.chunkHashes().size(); n++) {
                assertEquals("ACK true", client.command("GET " + n)); client.kill(); server.kill(); server.start(); client.start();
                assertEquals("ACK false", client.command("GET " + n)); assertEquals("ACK 0 0", server.command("STATS"));
            }
            assertEquals("ACK 0", client.command("DOWNLOAD")); assertArrayEquals(payload, Files.readAllBytes(output)); assertEquals(manifest.contentHash(), ArtifactManifest.hash(Files.readAllBytes(output)));
            assertEquals("ACK 0 0", server.command("STATS"));
        }
    }
}
