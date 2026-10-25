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

package org.j7m.middleware.application.api.replication;

import org.j7m.middleware.application.api.ApplicationCoordinate;

/**
 * J7M-native repository replication service.
 *
 * <p>The service replicates immutable J7M application artifacts, repository
 * index metadata, promotion state, rollback metadata, and audit-chain records
 * between J7M repositories. Implementations own local repository and durable
 * replication-event state and may block on local file I/O, verification,
 * digest calculation, durable-log append, and recovery. Implementations are
 * expected to be thread-safe for administrative callers unless a narrower
 * ownership model is documented.</p>
 *
 * <p>Replication is digest-addressed and verification-preserving. It must not
 * weaken strict or regulated repository policy, promote unverified artifacts,
 * silently overwrite conflicts, expose artifact payload bytes, or introduce
 * external object-storage/cloud semantics. Overload, conflict, verification
 * failure, malformed replication events, and recovery failure must map to
 * stable middleware error codes and redacted safe evidence.</p>
 */
public interface RepositoryReplicationService extends AutoCloseable {
    /**
     * Advertises the digest for one local artifact.
     *
     * <p>The digest is calculated or read from verified repository metadata.
     * The result is safe metadata and never contains artifact bytes.</p>
     *
     * @param coordinate exact artifact coordinate
     * @return digest descriptor
     */
    RepositoryArtifactDigest advertiseDigest(ApplicationCoordinate coordinate);

    /**
     * Replicates artifacts and selected repository metadata.
     *
     * <p>The plan controls artifact, index, promotion, rollback, and audit-chain
     * work. Implementations enforce configured transfer and backpressure limits
     * and reject conflicts instead of overwriting local state.</p>
     *
     * @param plan replication plan
     * @return replication result
     */
    RepositoryReplicationResult replicate(RepositoryReplicationPlan plan);

    /**
     * Replicates one promotion state.
     *
     * <p>The target artifact must already be present and verified under the
     * effective target policy before promotion state is accepted.</p>
     *
     * @param record promotion or demotion record
     * @return replication result
     */
    RepositoryReplicationResult replicatePromotion(RepositoryPromotionRecord record);

    /**
     * Replicates rollback metadata after verifying the target artifact exists.
     *
     * <p>Rollback records are metadata only; the service does not perform a
     * distributed transaction with runtime activation.</p>
     *
     * @param record rollback record
     * @return replication result
     */
    RepositoryReplicationResult replicateRollback(RepositoryRollbackRecord record);

    /**
     * Compares local and peer repository state.
     *
     * <p>The reconciliation plan is a deterministic safe summary of missing,
     * corrupted, or conflicting artifact/index/audit state.</p>
     *
     * @return reconciliation plan
     */
    RepositoryReconciliationPlan reconcile();

    /**
     * Repairs selected local state from the peer.
     *
     * <p>Repair must re-verify bytes, signatures, SBOM, provenance, and index
     * metadata before repaired artifacts are eligible for promotion.</p>
     *
     * @param plan repair plan
     * @return repair result
     */
    RepositoryReplicationResult repair(RepositoryRepairPlan plan);

    /**
     * Returns current safe status.
     *
     * @return immutable redacted replication status and metrics snapshot
     */
    RepositoryReplicationStatus status();

    /**
     * Closes owned resources.
     *
     * <p>Close should be idempotent. After close, operations should reject with
     * a stable unavailable/closed error and must not leak repository secrets or
     * artifact payloads in exception details.</p>
     */
    @Override
    void close();
}
