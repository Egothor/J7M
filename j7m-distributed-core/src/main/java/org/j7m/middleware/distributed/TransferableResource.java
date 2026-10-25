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

package org.j7m.middleware.distributed;

import org.j7m.middleware.consensus.ReplicatedStateMachine;

/**
 * Deterministic resource whose complete snapshot can move between consensus groups.
 * Snapshots include results, deduplication, event cursors and every recovery dependency.
 * Implementations must not expose a mutation path bypassing their partition wrapper.
 */
public interface TransferableResource extends ReplicatedStateMachine {
    /**
     * Reports whether migration can freeze this resource without stranding an unresolved transaction.
     * Called under the serialized partition apply lock; it must not perform external I/O.
     * @return true when all prepared or otherwise immovable work has resolved
     */
    boolean transferReady();

    /**
     * Receives the committed ownership epoch before mutations are accepted.
     * @param epoch positive topology ownership epoch
     */
    default void ownershipEpoch(long epoch) { }

    /**
     * Binds event identity to the committed partition and ownership epoch.
     * @param partition stable topology partition identity
     * @param epoch positive committed ownership epoch
     */
    default void ownershipChanged(String partition, long epoch) { ownershipEpoch(epoch); }

    /**
     * Identifies host-only logical-time controls, forbidden on ordinary network mutation paths.
     * @param command nested operation bytes
     * @return whether only the trusted measured-clock host may generate this command
     */
    default boolean timeControl(byte[] command) { return false; }

    /**
     * Identifies operations requiring fresh healthy committed time before admission.
     * @param command nested operation bytes
     * @return whether a trusted time control must precede the operation
     */
    default boolean timeSensitive(byte[] command) { return false; }

    /**
     * Encodes a deterministic trusted time control, never supplied by ordinary callers.
     * @param milliseconds authenticated measured host time
     * @return encoded control command
     */
    default byte[] timeCommand(long milliseconds) { throw new UnsupportedOperationException("No time adapter"); }

    /**
     * Extracts the durable business request identity from a validated nested command.
     * @param command resource-specific command bytes
     * @return request identity used by the resource deduplication journal
     */
    default String requestIdentity(byte[] command) { throw new UnsupportedOperationException("No identity adapter"); }

    /**
     * Validates every untrusted command before consensus admission and binds its tenant to the principal.
     * @param command nested resource command
     * @param authenticatedScope server-bound tenant/principal scope
     * @param administrative whether this principal has explicit resource maintenance permission
     * @throws SecurityException when authorization fails
     * @throws IllegalArgumentException for malformed input
     */
    default void validate(byte[] command, String authenticatedScope, boolean administrative) {
        throw new UnsupportedOperationException("No admission adapter");
    }

    /**
     * Rechecks durable identity conflicts under serialized committed application.
     * Pre-admission checks alone race when different payloads reuse the same tenant/request identity.
     * Maintenance controls without a durable client identity must return false.
     * @param command original nested resource command
     * @return true when its persisted identity belongs to different command bytes
     */
    default boolean identityConflict(byte[] command) { throw new UnsupportedOperationException("No committed identity adapter"); }

    /**
     * Creates a fully independent candidate with the same configuration and complete current state.
     * Migration validates and applies a suffix on this candidate before publishing its snapshot,
     * so a bad later entry cannot leave a partially mutated live resource.
     * @return independently mutable full-state copy
     */
    default TransferableResource copyForTransfer() { throw new UnsupportedOperationException("No transactional replay adapter"); }

    /**
     * Validates syntax of a trusted source command without consulting current target state.
     * Target replay may contain a subscription creation immediately followed by its ack; admission
     * validation against the pre-batch state must not reject that valid sequence.
     * @param command authenticated source's original committed command
     * @throws IllegalArgumentException for malformed schema or bounds
     */
    default void validateReplayCommand(byte[] command) { throw new UnsupportedOperationException("No replay validation adapter"); }

    /**
     * Fully validates a transfer image without mutating this live resource.
     * @param snapshot complete resource snapshot bytes
     * @throws IllegalArgumentException for invalid schema, bounds or recovery metadata
     */
    default void validateSnapshot(byte[] snapshot) { throw new UnsupportedOperationException("No snapshot validation adapter"); }

    /**
     * Validates tenant binding of a resource query before dispatch.
     * @param query resource query bytes
     * @param authenticatedScope server-bound scope
     * @throws SecurityException when scope or query permission fails
     */
    default void validateQuery(byte[] query, String authenticatedScope) {
        throw new UnsupportedOperationException("No query admission adapter");
    }

    /**
     * Executes an opaque resource-specific read after the partition host established a quorum barrier.
     * The wrapper serializes this call with apply; implementations must not mutate state.
     * @param query bounded resource-specific query bytes
     * @return independent encoded result bytes
     * @throws UnsupportedOperationException when the resource exposes reads through another adapter
     */
    default byte[] query(byte[] query) { throw new UnsupportedOperationException("No query adapter"); }
}
