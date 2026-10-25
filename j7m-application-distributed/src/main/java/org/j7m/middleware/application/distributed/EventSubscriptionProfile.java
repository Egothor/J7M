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

package org.j7m.middleware.application.distributed;

import java.time.Duration;
import java.util.Objects;
import org.j7m.middleware.distributed.ResourceEndpoint;

/**
 * Immutable host-owned subscription profile. Endpoints are borrowed and retain their own authorized
 * source/metadata scopes across refresh. Metadata must have a separate scope so internal manifests
 * cannot appear in the application source projection. No remote endpoint is selected by annotations.
 * @param source authorized event source endpoint
 * @param metadata separately authorized durable manifest endpoint
 * @param sourceNamespace stable host identity for this source scope, independent of network addresses
 * @param lease subscription lease, 1second..1day, advanced by the source's trusted clock service
 * @param pollInterval delay after completed processing, 1millisecond..1minute and less than lease/3
 * @param operationBudget finite source operation budget, 1millisecond..25seconds
 * @param handlerBudget original handler budget, 1millisecond..1day
 * @param maximumPage native scanned-position bound, 1..256
 * @param maximumSnapshotBytes allowed projection size, 1..64MiB
 * @param restart explicit durable-projection resume or complete projection replacement
 * @param expiry explicit stop or resnapshot on authoritative expiry
 */
public record EventSubscriptionProfile(ResourceEndpoint source, ResourceEndpoint metadata, String sourceNamespace,
        Duration lease, Duration pollInterval, Duration operationBudget, Duration handlerBudget,
        int maximumPage, int maximumSnapshotBytes, Restart restart, Expiry expiry) {
    /**
     * Chooses how a known active subscription reconstructs application state on activation.
     */
    public enum Restart {
        /**
         * The application projection is durable and resumes its source cursor.
         */ RESUME,
        /**
         * The application replaces its projection with a fresh complete snapshot.
         */ SNAPSHOT
    }
    /**
     * Chooses behavior after authoritative loss of retained history or subscription lease.
     */
    public enum Expiry {
        /**
         * Stop with a visible expiry reason; never skip missing history.
         */ FAIL,
        /**
         * Explicitly replace the projection from a new snapshot before continuing its tail.
         */ RESNAPSHOT
    }
    /**
     * Validates finite limits without accessing endpoints or starting workers.
     * @param source borrowed authorized source
     * @param metadata borrowed separately scoped manifest endpoint
     * @param sourceNamespace host-owned stable source identity
     * @param lease finite source lease
     * @param pollInterval bounded scheduling delay
     * @param operationBudget finite native operation deadline
     * @param handlerBudget finite original application deadline
     * @param maximumPage native scanned-position limit
     * @param maximumSnapshotBytes bounded snapshot bytes
     * @param restart explicit restart mode
     * @param expiry explicit expiry policy
     * @throws IllegalArgumentException for invalid limits or identical endpoint objects
     * @throws NullPointerException for null profile members
     */
    public EventSubscriptionProfile {
        Objects.requireNonNull(source); Objects.requireNonNull(metadata); Objects.requireNonNull(restart); Objects.requireNonNull(expiry);
        bounded(lease, Duration.ofSeconds(1), Duration.ofDays(1));
        bounded(pollInterval, Duration.ofMillis(1), Duration.ofMinutes(1));
        bounded(operationBudget, Duration.ofMillis(1), Duration.ofSeconds(25));
        bounded(handlerBudget, Duration.ofMillis(1), Duration.ofDays(1));
        if (source == metadata || sourceNamespace == null || !sourceNamespace.matches("[A-Za-z0-9_.-]{1,128}")
                || pollInterval.multipliedBy(3).compareTo(lease) >= 0 || maximumPage < 1 || maximumPage > 256
                || maximumSnapshotBytes < 1 || maximumSnapshotBytes > 67108864) {
            throw new IllegalArgumentException("Invalid event subscription profile");
        }
    }
    private static void bounded(Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Invalid subscription duration");
        }
    }
    /**
     * Omits endpoint identities and source namespace from incidental diagnostics.
     * @return fixed safe label
     */
    @Override public String toString() { return "EventSubscriptionProfile[host-owned]"; }
}
