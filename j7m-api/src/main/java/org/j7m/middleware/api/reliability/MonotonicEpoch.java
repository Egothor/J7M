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

package org.j7m.middleware.api.reliability;

import java.io.Serializable;

/**
 * Monotonic epoch within a named authority and scope.
 *
 * <p>The record is immutable, serializable, thread-safe, and comparable only
 * within the same authority and scope.</p>
 *
 * @param authority epoch authority
 * @param scope epoch scope
 * @param value epoch value
 */
public record MonotonicEpoch(String authority, String scope, long value) implements Comparable<MonotonicEpoch>, Serializable {
    private static final int MAX_LENGTH = 128;

    /**
     * Creates a validated epoch.
     *
     * @throws IllegalArgumentException when authority or scope is invalid, or the value is negative
     */
    public MonotonicEpoch {
        authority = ReliabilityValues.requireLogSafe("epoch authority", authority, MAX_LENGTH);
        scope = ReliabilityValues.requireLogSafe("epoch scope", scope, MAX_LENGTH);
        if (value < 0L) {
            throw new IllegalArgumentException("epoch value must be non-negative");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(final MonotonicEpoch other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        if (!this.authority.equals(other.authority) || !this.scope.equals(other.scope)) {
            throw new IllegalArgumentException("epochs have different authority or scope");
        }
        return Long.compare(this.value, other.value);
    }
}
