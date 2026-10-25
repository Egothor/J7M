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

package org.j7m.middleware.application.replication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationId;
import org.j7m.middleware.application.api.ApplicationVersion;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.repository.ApplicationSignature;
import org.j7m.middleware.application.repository.J7mBundleReader;
import org.j7m.middleware.application.repository.J7mBundleWriter;
import org.j7m.middleware.application.repository.LocalApplicationRepository;

final class RepositoryReplicationTestSupport {
    private RepositoryReplicationTestSupport() {
    }

    static ApplicationBundle bundle(final Path directory, final String name, final String version) throws IOException {
        return bundle(directory, name, version, "test-key");
    }

    static ApplicationBundle bundle(final Path directory, final String name, final String version, final String keyId) throws IOException {
        Files.createDirectories(directory);
        Path manifest = directory.resolve(name + "-" + version + "-manifest.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion": "j7m.application/v1",
                  "application": {
                    "group": "org.j7m.replication",
                    "name": "%s",
                    "version": "%s",
                    "displayName": "Replication Test",
                    "description": "Repository replication test bundle"
                  },
                  "runtime": {
                    "minJ7mVersion": "0.6.6",
                    "javaVersion": "25",
                    "moduleMode": "CLASSPATH",
                    "bootstrapClass": "org.j7m.replication.Main"
                  },
                  "services": [
                    {
                      "serviceName": "//apps/test/%s/Service",
                      "interfaceName": "org.j7m.replication.Service",
                      "implementationClass": "org.j7m.replication.ServiceImpl",
                      "transport": "local",
                      "globallyExported": false,
                      "metadata": {
                        "phase": "6.6"
                      }
                    }
                  ],
                  "security": {
                    "trustDomain": "dev",
                    "confidential": false
                  },
                  "resources": {
                    "cpu": "1",
                    "memory": "128MiB",
                    "maxConcurrentInvocations": 8,
                    "maxInboundPayload": "1MiB"
                  }
                }
                """.formatted(name, version, name), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("sbom.spdx.json"), "{\"spdx\":\"" + name + "\"}\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("j7m-provenance.json"), "{\"builder\":\"test\"}\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("j7m-signature.json"), new ApplicationSignature(
                ApplicationSignature.SCHEMA_VERSION, "Ed25519", keyId, "test-signer",
                java.time.Instant.EPOCH, "SHA-256", "placeholder", "AA==").toJson(), StandardCharsets.UTF_8);
        Path jar = directory.resolve(name + ".jar");
        Files.write(jar, ("jar-" + name + "-" + version).getBytes(StandardCharsets.UTF_8));
        return new J7mBundleWriter().write(manifest, directory.resolve(name + "-" + version + ".j7m"),
                List.of(jar), List.of(directory.resolve("sbom.spdx.json")));
    }

    static ApplicationCoordinate coordinate(final String name, final String version) {
        return new ApplicationCoordinate(new ApplicationId("org.j7m.replication", name), new ApplicationVersion(version));
    }

    static LocalApplicationRepository published(final Path root, final ApplicationBundle bundle) {
        LocalApplicationRepository repository = new LocalApplicationRepository(root);
        org.junit.jupiter.api.Assertions.assertTrue(repository.publish(bundle, new RepositoryPublishOptions("test", false)).success());
        return repository;
    }

    static LocalRepositoryReplicationService service(
            final Path root,
            final LocalApplicationRepository source,
            final LocalApplicationRepository target) {
        return new LocalRepositoryReplicationService(RepositoryReplicationConfig.defaults(source, target, root.resolve("events")));
    }

    static boolean hasEntry(final ApplicationBundle bundle, final String entry) {
        return new J7mBundleReader().hasEntry(bundle, entry);
    }
}
