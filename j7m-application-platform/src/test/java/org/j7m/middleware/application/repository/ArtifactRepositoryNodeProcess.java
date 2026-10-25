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

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import org.j7m.middleware.application.api.RepositoryPublishOptions;

/**
 * Independent server/client JVM fixture using only public artifact APIs for completed operations.
 */
public final class ArtifactRepositoryNodeProcess {
    private ArtifactRepositoryNodeProcess() { }
    /**
     * Starts a durable transfer actor; stdin commands identify deterministic crash boundaries.
     * @param args mode, actor disk, port, public key file, source, manifest, output
     * @throws Exception for startup or fixture failure
     */
    public static void main(String[] args) throws Exception {
        PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Files.readAllBytes(Path.of(args[3]))));
        if (args[0].equals("ownership-probe")) {
            try (var ownership = new ArtifactFileOwnership(Path.of(args[1]))) { System.out.println("ACQUIRED"); }
            catch (IOException owned) { System.out.println("DENIED"); }
            return;
        }
        byte[] key = new byte[32]; Arrays.fill(key, (byte) 1); int port = Integer.parseInt(args[2]); Path disk = Path.of(args[1]);
        if (args[0].endsWith("server")) {
            var applications = new LocalApplicationRepository(disk.resolve("apps"));
            try (var store = new ResumableArtifactStore(disk.resolve("blobs"), Map.of("signer", publicKey), 268435456, 128, applications);
                    var server = new ArtifactRepositoryServer(new InetSocketAddress("127.0.0.1", port), store,
                    Map.of("publisher", new ArtifactRepositoryServer.Binding("tenant", key, Set.of("READ", "UPLOAD", "COMMIT", "PUBLISH"))), new RepositoryPublishOptions("transfer", false), null);
                    var input = new BufferedReader(new InputStreamReader(System.in))) {
                System.out.println("READY"); System.out.flush(); String line;
                while ((line = input.readLine()) != null) {
                    if (line.equals("STATS")) System.out.println("ACK " + server.uploadedChunks() + " " + server.downloadedChunks());
                    else if (line.startsWith("APP ")) {
                        try { System.out.println("ACK " + applications.fetch(org.j7m.middleware.application.api.ApplicationCoordinate.parse(line.substring(4))).sha256()); }
                        catch (org.j7m.middleware.api.MiddlewareException missing) { System.out.println("ACK MISSING"); }
                    }
                    else throw new IllegalArgumentException("Unknown server fixture command"); System.out.flush();
                }
            }
        } else {
            ArtifactManifest manifest = ArtifactManifest.decode(Files.readAllBytes(Path.of(args[5])));
            try (var client = new ArtifactRepositoryClient(List.of(new InetSocketAddress("127.0.0.1", port)), "publisher", key, Map.of("signer", publicKey), disk, null);
                    var input = new BufferedReader(new InputStreamReader(System.in))) {
                System.out.println("READY"); System.out.flush(); String line;
                while ((line = input.readLine()) != null) {
                    String[] command = line.split(" "); Instant deadline = Instant.now().plusSeconds(5);
                    switch (command[0]) {
                        case "BEGIN" -> System.out.println("ACK " + client.begin(manifest, deadline));
                        case "RESUME" -> System.out.println("ACK " + client.resume(manifest, deadline));
                        case "PUT" -> { int index = Integer.parseInt(command[1]); client.uploadChunk(manifest, index, chunk(Path.of(args[4]), manifest, index), deadline); System.out.println("ACK"); }
                        case "PARTIAL" -> { int index = Integer.parseInt(command[1]); byte[] bytes = chunk(Path.of(args[4]), manifest, index);
                            byte[] payload = ArtifactManifest.encode(out -> { out.writeUTF(manifest.id()); out.writeInt(index); ArtifactProtocol.bytes(out, bytes); });
                            byte[] body = ArtifactManifest.encode(out -> { out.writeInt(1); out.writeUTF("publisher"); out.writeUTF(UUID.randomUUID().toString()); out.writeLong(deadline.toEpochMilli()); out.writeUTF("PUT"); ArtifactProtocol.bytes(out, payload); });
                            try (Socket socket = new Socket("127.0.0.1", port)) { DataOutputStream out = new DataOutputStream(socket.getOutputStream()); out.writeInt(body.length); out.write(body, 0, body.length / 2); out.flush(); }
                            System.out.println("ACK"); }
                        case "FINISH" -> System.out.println("ACK " + client.upload(Path.of(args[4]), manifest, deadline));
                        case "GET" -> { int index = Integer.parseInt(command[1]); System.out.println("ACK " + client.downloadChunk(client.fetch(manifest.id(), deadline), index, deadline)); }
                        case "DOWNLOAD" -> System.out.println("ACK " + client.download(manifest.id(), Path.of(args[6]), deadline));
                        default -> throw new IllegalArgumentException("Unknown client fixture command");
                    }
                    System.out.flush();
                }
            }
        }
    }
    private static byte[] chunk(Path source, ArtifactManifest manifest, int index) throws IOException {
        try (InputStream in = Files.newInputStream(source)) { in.skipNBytes((long) index * manifest.chunkSize()); return in.readNBytes(manifest.chunkSize()); }
    }
}
