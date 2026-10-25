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

import java.util.Objects;
import org.j7m.middleware.distributed.EventJournal;

/**
 * Immutable data-only input for a generated subscription callback. Cursor is the deduplication
 * identity, not authority or proof that an external application effect committed atomically.
 * @param kind explicit replacement snapshot or individual source event
 * @param cursor exact snapshot barrier or event identity
 * @param operation exact event operation, empty for snapshots
 * @param payload copied versioned source bytes; snapshots replace the whole application projection
 */
public record ManagedEventDelivery(Kind kind, EventJournal.Cursor cursor, String operation, byte[] payload) {
    /**
     * Distinguishes complete projection replacement from an incremental delivery.
     */
    public enum Kind {
        /**
         * Complete authorized projection at the exact cursor barrier.
         */ SNAPSHOT,
        /**
         * One source change; duplicate delivery is possible.
         */ EVENT
    }
    /**
     * Validates and copies a bounded delivery without decoding application types.
     * @param kind explicit delivery kind
     * @param cursor stable source position
     * @param operation event operation or empty for snapshot
     * @param payload bounded source bytes
     * @throws IllegalArgumentException for an invalid operation or payload bound
     * @throws NullPointerException for null fields
     */
    public ManagedEventDelivery {
        Objects.requireNonNull(kind); Objects.requireNonNull(cursor); Objects.requireNonNull(operation);
        if (operation.length() > 128 || operation.chars().anyMatch(Character::isISOControl)
                || kind == Kind.SNAPSHOT && !operation.isEmpty() || kind == Kind.EVENT && operation.isEmpty()
                || payload.length > (kind == Kind.SNAPSHOT ? 67108864 : 1049600)) {
            throw new IllegalArgumentException("Invalid managed event delivery");
        }
        payload = payload.clone();
    }
    /**
     * Returns independent source bytes.
     * @return copied payload
     */
    @Override public byte[] payload() { return payload.clone(); }
    /**
     * Omits source payload, scope and event identifiers from incidental diagnostics.
     * @return fixed safe label
     */
    @Override public String toString() { return "ManagedEventDelivery[" + kind + "]"; }
}
