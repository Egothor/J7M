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
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactManifestTest {
    @TempDir Path directory;
    @Test void largestAdvertisedManifestRoundTripsAndSignatureCoversAllMetadata() throws Exception {
        Path file = directory.resolve("maximum.bin"); try (RandomAccessFile output = new RandomAccessFile(file.toFile(), "rw")) { output.setLength(67108864); }
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var manifest = ArtifactManifest.sign("tenant", "example:large:1", file, 4096, "signer", key.getPrivate());
        assertTrue(manifest.encode().length > 1048576); assertTrue(manifest.encode().length < 2097152);
        var restored = ArtifactManifest.decode(manifest.encode()); assertEquals(manifest, restored); assertEquals(manifest.hashCode(), restored.hashCode());
        restored.verify(Map.of("signer", key.getPublic())); assertEquals(manifest.id(), restored.id());
        byte[] signature = restored.signature(); signature[0] ^= 1; restored.verify(Map.of("signer", key.getPublic()));
        assertThrows(SecurityException.class, () -> restored.verify(Map.of()));
        byte[] future = manifest.encode(); future[3] = 2; assertThrows(IllegalArgumentException.class, () -> ArtifactManifest.decode(future));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactManifest("../tenant", "example:large:1", manifest.length(), manifest.contentHash(), 4096, manifest.chunkHashes(), "signer", manifest.signature()));
        assertThrows(IllegalArgumentException.class, () -> ArtifactManifest.sign("tenant", "example:large:1", file, 4095, "signer", key.getPrivate()));
        var trust = Map.of("signer", key.getPublic());
        try (var store = new ResumableArtifactStore(directory.resolve("store"), trust, 268435456, 8, null);
                var server = new ArtifactRepositoryServer(new java.net.InetSocketAddress("127.0.0.1", 0), store,
                    Map.of("uploader", new ArtifactRepositoryServer.Binding("tenant", new byte[32], Set.of("UPLOAD"))), new org.j7m.middleware.application.api.RepositoryPublishOptions("test", false), null);
                var client = new ArtifactRepositoryClient(List.of(server.address()), "uploader", new byte[32], trust, directory.resolve("cache"), null)) {
            assertTrue(client.begin(manifest, java.time.Instant.now().plusSeconds(10)).isEmpty());
            assertEquals(manifest, store.manifest("tenant", manifest.id())); assertTrue(client.resume(manifest, java.time.Instant.now().plusSeconds(10)).isEmpty());
        }
    }
}
