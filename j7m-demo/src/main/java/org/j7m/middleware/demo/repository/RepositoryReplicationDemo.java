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

package org.j7m.middleware.demo.repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.api.replication.RepositoryPromotionRecord;
import org.j7m.middleware.application.api.replication.RepositoryReplicationPlan;
import org.j7m.middleware.application.api.replication.RepositoryRollbackRecord;
import org.j7m.middleware.application.repository.ApplicationSignature;
import org.j7m.middleware.application.repository.J7mBundleWriter;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.application.replication.LocalRepositoryReplicationService;
import org.j7m.middleware.application.replication.RepositoryReplicationConfig;

/**
 * Phase 6.6 local repository replication demo.
 */
public final class RepositoryReplicationDemo {
    // J7M teaching: this demo block is self-contained so acceptance smoke runs do not require external services.
    private RepositoryReplicationDemo() {
    }

    /**
     * Runs the demo.
     *
     * @param args ignored command-line arguments
     * @throws Exception if local temporary repository setup fails
     */
    public static void main(final String[] args) throws Exception {
        Path workspace = Files.createTempDirectory("j7m-repository-replication-demo-");
        LocalApplicationRepository replicaA = new LocalApplicationRepository(workspace.resolve("replica-a"));
        LocalApplicationRepository replicaB = new LocalApplicationRepository(workspace.resolve("replica-b"));
        ApplicationBundle bundle = bundle(workspace.resolve("bundle"), "replicated-app", "1.0.0");
        replicaA.publish(bundle, new RepositoryPublishOptions("demo", false));

        try (LocalRepositoryReplicationService service = new LocalRepositoryReplicationService(
                RepositoryReplicationConfig.defaults(replicaA, replicaB, workspace.resolve("events")))) {
            // J7M teaching: digest advertisement and artifact replication prove exact
            // byte preservation for local repositories; this is not an external object
            // store dependency or cloud SDK integration.
            System.out.println("phase6.6 digest=" + service.advertiseDigest(bundle.coordinate()).value());
            System.out.println("phase6.6 artifactReplicated="
                    + service.replicate(new RepositoryReplicationPlan(List.of(bundle.coordinate()), true, false, false, true)).success());
            ApplicationBundle replicated = replicaB.fetch(bundle.coordinate());
            System.out.println("phase6.6 exactBytes=" + java.util.Arrays.equals(Files.readAllBytes(bundle.path()),
                    Files.readAllBytes(replicated.path())));

            // J7M teaching: promotion and rollback records are replicated as gates
            // with signature/SBOM/provenance and verification-policy metadata preserved
            // by the bundle, rather than as mutable deployment side effects.
            System.out.println("phase6.6 promotionReplicated="
                    + service.replicatePromotion(new RepositoryPromotionRecord("prod", bundle.coordinate(), "PROMOTED", bundle.sha256())).success());
            System.out.println("phase6.6 rollbackReplicated="
                    + service.replicateRollback(new RepositoryRollbackRecord("prod", bundle.coordinate(), bundle.coordinate(),
                            "demo rollback", java.time.Instant.EPOCH)).success());

            // J7M teaching: corrupting the replica creates repair evidence; repair
            // and reconciliation restore repository state from verified source bytes
            // instead of trusting the damaged copy.
            Files.writeString(replicaB.fetch(bundle.coordinate()).path(), "corrupted-demo-artifact", StandardCharsets.UTF_8);
            System.out.println("phase6.6 repairMissing="
                    + service.repair(new org.j7m.middleware.application.api.replication.RepositoryRepairPlan(
                            List.of(bundle.coordinate()), true, true)).success());

            // J7M teaching: the conflicting artifact has the same coordinate but
            // different bytes, so conflict detection proves digest/index comparison
            // rather than last-writer-wins replacement.
            ApplicationBundle conflicting = bundle(workspace.resolve("conflict"), "replicated-app", "1.0.0", "different-key");
            Files.copy(conflicting.path(), replicaA.fetch(bundle.coordinate()).path(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("phase6.6 conflictDetected="
                    + !service.replicate(RepositoryReplicationPlan.artifact(conflicting.coordinate())).conflicts().isEmpty());
            System.out.println("phase6.6 reconciliationMissing=" + service.reconcile().missingArtifacts().size());
            System.out.println("phase6.6 status artifacts=" + service.status().metrics().artifactsTransferred()
                    + " conflicts=" + service.status().metrics().conflictsDetected()
                    + " evidence=" + service.status().evidence().size());
        }
    }

    private static ApplicationBundle bundle(final Path directory, final String name, final String version) throws Exception {
        return bundle(directory, name, version, "demo-key");
    }

    private static ApplicationBundle bundle(final Path directory, final String name, final String version, final String keyId) throws Exception {
        Files.createDirectories(directory);
        Path manifest = directory.resolve("manifest.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion": "j7m.application/v1",
                  "application": {
                    "group": "org.j7m.demo",
                    "name": "%s",
                    "version": "%s",
                    "displayName": "Repository Replication Demo",
                    "description": "Phase 6.6 demo bundle"
                  },
                  "runtime": {
                    "minJ7mVersion": "0.6.6",
                    "javaVersion": "25",
                    "moduleMode": "CLASSPATH",
                    "bootstrapClass": "org.j7m.demo.RepositoryReplicationApplication"
                  },
                  "services": [
                    {
                      "serviceName": "//apps/demo/%s/Service",
                      "interfaceName": "org.j7m.demo.Service",
                      "implementationClass": "org.j7m.demo.ServiceImpl",
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
        Files.writeString(directory.resolve("sbom.spdx.json"), "{\"spdx\":\"demo\"}\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("j7m-provenance.json"), "{\"builder\":\"demo\"}\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("j7m-signature.json"), new ApplicationSignature(
                ApplicationSignature.SCHEMA_VERSION, "Ed25519", keyId, "demo-signer",
                java.time.Instant.EPOCH, "SHA-256", "placeholder", "AA==").toJson(), StandardCharsets.UTF_8);
        Path jar = directory.resolve(name + ".jar");
        Files.writeString(jar, "demo-artifact-" + keyId, StandardCharsets.UTF_8);
        return new J7mBundleWriter().write(manifest, directory.resolve(name + "-" + version + ".j7m"), List.of(jar), List.of());
    }
}
