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
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.deployer.ApplicationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactRepositoryIT {
    @TempDir Path directory;
    private static Instant deadline() { return Instant.now().plusSeconds(5); }
    private static byte[] key(int value) { byte[] key = new byte[32]; Arrays.fill(key, (byte) value); return key; }
    private Path payload(String name, int size) throws IOException { byte[] bytes = new byte[size]; new Random(37245L).nextBytes(bytes); return Files.write(directory.resolve(name), bytes); }
    @Test void realSocketRejectsTamperingRolesTraversalAndIncompletePublication() throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); var trust = Map.of("signer", signer.getPublic());
        Path source = payload("payload.bin", 13000); var manifest = ArtifactManifest.sign("tenant", "example:data:1", source, 4096, "signer", signer.getPrivate());
        try (var store = new ResumableArtifactStore(directory.resolve("store"), trust, 1048576, 2, null);
                var server = new ArtifactRepositoryServer(new InetSocketAddress("127.0.0.1", 0), store,
                Map.of("writer", new ArtifactRepositoryServer.Binding("tenant", key(1), Set.of("READ", "UPLOAD", "COMMIT", "PUBLISH")),
                    "reader", new ArtifactRepositoryServer.Binding("tenant", key(2), Set.of("READ")),
                    "foreign", new ArtifactRepositoryServer.Binding("other", key(3), Set.of("READ", "UPLOAD"))), new RepositoryPublishOptions("server", false), null);
                var client = new ArtifactRepositoryClient(List.of(server.address()), "writer", key(1), trust, directory.resolve("client"), null);
                var reader = new ArtifactRepositoryClient(List.of(server.address()), "reader", key(2), trust, directory.resolve("reader"), null);
                var foreign = new ArtifactRepositoryClient(List.of(server.address()), "foreign", key(3), trust, directory.resolve("foreign"), null)) {
            byte[] bad = manifest.encode(); bad[bad.length - 1] ^= 1;
            assertThrows(SecurityException.class, () -> client.exchange("BEGIN", bad, deadline()));
            assertThrows(SecurityException.class, () -> foreign.begin(manifest, deadline()));
            assertThrows(SecurityException.class, () -> reader.begin(manifest, deadline()));
            assertTrue(client.begin(manifest, deadline()).isEmpty());
            assertThrows(IOException.class, () -> client.fetch(manifest.id(), deadline()));
            assertThrows(IOException.class, () -> client.commitManifest(manifest.id(), deadline()));
            assertThrows(IOException.class, () -> client.publishApplication(manifest.id(), deadline()));
            byte[] first = Arrays.copyOf(Files.readAllBytes(source), 4096); byte[] corrupted = first.clone(); corrupted[0] ^= 1;
            assertThrows(IOException.class, () -> client.uploadChunk(manifest, 0, corrupted, deadline()));
            client.uploadChunk(manifest, 0, first, deadline()); assertEquals(Set.of(0), client.resume(manifest, deadline()));
            assertEquals(Set.of(0), store.retained("tenant", manifest.id())); assertEquals(manifest, store.manifest("tenant", manifest.id()));
            assertThrows(IOException.class, () -> store.retained("tenant", "a".repeat(64)));
            assertThrows(IllegalArgumentException.class, () -> store.manifest("tenant", "../manifest"));
            assertEquals(3, client.upload(source, manifest, deadline())); assertEquals(0, client.upload(source, manifest, deadline()));
            assertEquals(manifest, reader.fetch(manifest.id(), deadline())); Path output = directory.resolve("output.bin");
            assertEquals(4, reader.download(manifest.id(), output, deadline())); assertEquals(0, reader.download(manifest.id(), output, deadline())); assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(output));
            assertEquals(4, client.uploadedChunks()); assertEquals(4, reader.downloadedChunks()); assertEquals(4, server.uploadedChunks()); assertEquals(4, server.downloadedChunks());
            assertThrows(IllegalArgumentException.class, () -> client.fetch("../../artifact", deadline()));
            assertThrows(IllegalArgumentException.class, () -> client.exchange("FETCH", ArtifactManifest.encode(out -> out.writeUTF("../../artifact")), deadline()));
            assertThrows(IOException.class, () -> store.download("tenant", manifest.id(), -1));
            Path persistedChunk = directory.resolve("store").resolve(ArtifactManifest.hash("tenant".getBytes(java.nio.charset.StandardCharsets.UTF_8))).resolve(manifest.id()).resolve("chunk-0");
            Files.write(persistedChunk, corrupted); assertThrows(IOException.class, () -> store.download("tenant", manifest.id(), 0)); Files.write(persistedChunk, first);
            assertThrows(IOException.class, () -> new ResumableArtifactStore(directory.resolve("store"), trust, 1048576, 2, null));
        }
    }
    @Test void signedBytesReachExistingApplicationRepositoryOnlyAfterCompleteVerifiedCommit() throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); var trust = Map.of("signer", signer.getPublic());
        Path descriptor = directory.resolve("application.json"); Files.writeString(descriptor, """
            {"schemaVersion":"j7m.application/v1","application":{"group":"example","name":"imported","version":"1","displayName":"Imported","description":"Transferred bundle"},
             "runtime":{"minJ7mVersion":"0.6.6","javaVersion":"25","moduleMode":"CLASSPATH","bootstrapClass":"example.Main"},"services":[{"serviceName":"//apps/test/replicated/Service","interfaceName":"example.Service","implementationClass":"example.ServiceImpl","transport":"local","globallyExported":false,"metadata":{}}],
             "security":{"trustDomain":"dev","confidential":false},"resources":{"cpu":"1","memory":"128MiB","maxConcurrentInvocations":8,"maxInboundPayload":"1MiB"}}
            """);
        ApplicationBundle bundle = new J7mBundleWriter().write(descriptor, directory.resolve("application.j7m"), List.of(), List.of());
        var originalVerification = new ApplicationBundleVerifier().verify(bundle, false); assertTrue(originalVerification.accepted(), originalVerification.messages().toString());
        var manifest = ArtifactManifest.sign("tenant", "example:imported:1", bundle.path(), 4096, "signer", signer.getPrivate());
        var applications = new LocalApplicationRepository(directory.resolve("applications"));
        MiddlewareRuntime runtime = (MiddlewareRuntime) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {MiddlewareRuntime.class},
                (proxy, method, args) -> { throw new AssertionError("Incomplete activation must not reach runtime"); });
        var manager = new ApplicationManager(runtime, applications);
        try (var store = new ResumableArtifactStore(directory.resolve("store"), trust, 1048576, 8, applications);
                var server = new ArtifactRepositoryServer(new InetSocketAddress("127.0.0.1", 0), store,
                    Map.of("publisher", new ArtifactRepositoryServer.Binding("tenant", key(1), Set.of("UPLOAD", "COMMIT", "PUBLISH", "READ"))), new RepositoryPublishOptions("server", false), null);
                var client = new ArtifactRepositoryClient(List.of(server.address()), "publisher", key(1), trust, directory.resolve("client"), null)) {
            client.begin(manifest, deadline()); assertThrows(IOException.class, () -> client.publishApplication(manifest.id(), deadline()));
            assertThrows(MiddlewareException.class, () -> manager.activate(ApplicationCoordinate.parse(manifest.coordinate()), null));
            client.upload(bundle.path(), manifest, deadline());
            assertThrows(MiddlewareException.class, () -> applications.fetch(ApplicationCoordinate.parse(manifest.coordinate())));
            var publication = client.publishApplication(manifest.id(), deadline()); assertTrue(publication.success(), publication.details().toString());
            var fetched = applications.fetch(ApplicationCoordinate.parse(manifest.coordinate())); assertEquals(manifest.contentHash(), fetched.sha256()); assertTrue(applications.verify(fetched.coordinate()).accepted());
        }
    }
    @Test void completeDigestAndBothPersistentQuotasFailClosed() throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); var trust = Map.of("signer", signer.getPublic());
        Path source = payload("source", 4096); ArtifactManifest valid = ArtifactManifest.sign("tenant", "example:data:1", source, 4096, "signer", signer.getPrivate());
        var unsigned = new ArtifactManifest("tenant", "example:data:1", valid.length(), "0".repeat(64), valid.chunkSize(), valid.chunkHashes(), "signer", new byte[64]);
        Signature sign = Signature.getInstance("Ed25519"); sign.initSign(signer.getPrivate()); sign.update(unsigned.canonical());
        var incorrect = new ArtifactManifest("tenant", unsigned.coordinate(), unsigned.length(), unsigned.contentHash(), unsigned.chunkSize(), unsigned.chunkHashes(), "signer", sign.sign());
        try (var store = new ResumableArtifactStore(directory.resolve("store"), trust, 1048576, 1, null)) {
            store.begin("tenant", incorrect); store.upload("tenant", incorrect.id(), 0, Files.readAllBytes(source));
            assertThrows(IOException.class, () -> store.commitManifest("tenant", incorrect.id())); assertThrows(IOException.class, () -> store.fetch("tenant", incorrect.id()));
            assertThrows(IOException.class, () -> store.begin("tenant", valid));
        }
        try (var store = new ResumableArtifactStore(directory.resolve("bytes-quota"), trust, 8192, 8, null)) { assertThrows(IOException.class, () -> store.begin("tenant", valid)); }
        long insufficientRetryQuota = 2 * valid.length() + 2L * valid.encode().length + 4096;
        Path retryCache = directory.resolve("retry-quota");
        try (var client = new ArtifactRepositoryClient(List.of(new InetSocketAddress("127.0.0.1", 1)), "writer", key(1), trust, retryCache, null, insufficientRetryQuota, 1)) {
            IOException rejected = assertThrows(IOException.class, () -> client.begin(valid, Instant.now().plusMillis(50)));
            assertTrue(rejected.getMessage().contains("cache quota")); assertFalse(Files.exists(retryCache.resolve(valid.id()).resolve("manifest")));
        }
        Path orphanStore = directory.resolve("orphan-store"); Files.createDirectories(orphanStore.resolve("a".repeat(64)).resolve("b".repeat(64)));
        try (var store = new ResumableArtifactStore(orphanStore, trust, 1048576, 1, null)) { assertThrows(IOException.class, () -> store.begin("tenant", valid)); }
        Path orphanCache = directory.resolve("orphan-cache"); Files.createDirectories(orphanCache.resolve("a".repeat(64))); Files.write(orphanCache.resolve("a".repeat(64)).resolve("manifest.pending"), new byte[] {1});
        try (var client = new ArtifactRepositoryClient(List.of(new InetSocketAddress("127.0.0.1", 1)), "writer", key(1), trust, orphanCache, null, 1048576, 1)) {
            assertThrows(IOException.class, () -> client.begin(valid, deadline()));
        }
        try (var client = new ArtifactRepositoryClient(List.of(new InetSocketAddress("127.0.0.1", 1)), "writer", key(1), trust, directory.resolve("cache-quota"), null, 8192, 1)) {
            assertThrows(IOException.class, () -> client.begin(valid, deadline())); assertEquals(0, client.uploadedChunks());
        }
    }
}
