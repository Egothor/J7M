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

import java.nio.file.Path;
import java.time.Clock;
import org.j7m.middleware.application.api.replication.RepositoryReplicaId;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.application.repository.SignaturePolicy;

/**
 * Configuration for local repository replication.
 *
 * @param localReplica local replica id
 * @param peerReplica peer replica id
 * @param source source repository
 * @param target target repository
 * @param eventLogDirectory durable replication event log directory
 * @param maxArtifactBytes maximum accepted artifact size
 * @param maxChunkBytes maximum transfer chunk size
 * @param maxPendingTransferTasks maximum pending transfer tasks
 * @param maxInFlightTransfers maximum in-flight transfers
 * @param maxReconciliationBatchSize maximum reconciliation batch size
 * @param maxIndexDeltaBatchSize maximum index delta batch size
 * @param maxAuditReplicationBatchSize maximum audit batch size
 * @param targetVerificationPolicy minimum verification policy enforced at the target
 * @param clock event clock
 */
public record RepositoryReplicationConfig(
        RepositoryReplicaId localReplica,
        RepositoryReplicaId peerReplica,
        LocalApplicationRepository source,
        LocalApplicationRepository target,
        Path eventLogDirectory,
        long maxArtifactBytes,
        int maxChunkBytes,
        int maxPendingTransferTasks,
        int maxInFlightTransfers,
        int maxReconciliationBatchSize,
        int maxIndexDeltaBatchSize,
        int maxAuditReplicationBatchSize,
        SignaturePolicy targetVerificationPolicy,
        Clock clock) {
    /**
     * Creates a validated configuration.
     */
    public RepositoryReplicationConfig {
        if (localReplica == null || peerReplica == null || source == null || target == null || eventLogDirectory == null) {
            throw new IllegalArgumentException("replica ids, repositories, and event log directory are required");
        }
        if (maxArtifactBytes <= 0L || maxChunkBytes <= 0 || maxPendingTransferTasks <= 0 || maxInFlightTransfers <= 0
                || maxReconciliationBatchSize <= 0 || maxIndexDeltaBatchSize <= 0 || maxAuditReplicationBatchSize <= 0) {
            throw new IllegalArgumentException("replication limits must be positive");
        }
        targetVerificationPolicy = targetVerificationPolicy == null ? SignaturePolicy.DEVELOPMENT : targetVerificationPolicy;
        clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Compatibility constructor using development target verification policy.
     *
     * @param localReplica local replica id
     * @param peerReplica peer replica id
     * @param source source repository
     * @param target target repository
     * @param eventLogDirectory durable replication event log directory
     * @param maxArtifactBytes maximum accepted artifact size
     * @param maxChunkBytes maximum transfer chunk size
     * @param maxPendingTransferTasks maximum pending transfer tasks
     * @param maxInFlightTransfers maximum in-flight transfers
     * @param maxReconciliationBatchSize maximum reconciliation batch size
     * @param maxIndexDeltaBatchSize maximum index delta batch size
     * @param maxAuditReplicationBatchSize maximum audit batch size
     * @param clock event clock
     */
    public RepositoryReplicationConfig(
            final RepositoryReplicaId localReplica,
            final RepositoryReplicaId peerReplica,
            final LocalApplicationRepository source,
            final LocalApplicationRepository target,
            final Path eventLogDirectory,
            final long maxArtifactBytes,
            final int maxChunkBytes,
            final int maxPendingTransferTasks,
            final int maxInFlightTransfers,
            final int maxReconciliationBatchSize,
            final int maxIndexDeltaBatchSize,
            final int maxAuditReplicationBatchSize,
            final Clock clock) {
        this(localReplica, peerReplica, source, target, eventLogDirectory, maxArtifactBytes, maxChunkBytes,
                maxPendingTransferTasks, maxInFlightTransfers, maxReconciliationBatchSize, maxIndexDeltaBatchSize,
                maxAuditReplicationBatchSize, SignaturePolicy.DEVELOPMENT, clock);
    }

    /**
     * Creates a conservative local replication configuration.
     *
     * @param source source repository
     * @param target target repository
     * @param eventLogDirectory durable event log directory
     * @return default configuration
     */
    public static RepositoryReplicationConfig defaults(
            final LocalApplicationRepository source,
            final LocalApplicationRepository target,
            final Path eventLogDirectory) {
        return new RepositoryReplicationConfig(new RepositoryReplicaId("local"), new RepositoryReplicaId("peer"),
                source, target, eventLogDirectory, 64L * 1024L * 1024L, 1024 * 1024, 1024, 8, 1024, 1024, 4096,
                SignaturePolicy.DEVELOPMENT, Clock.systemUTC());
    }
}
