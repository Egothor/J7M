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

package org.j7m.middleware.api.durablelog;

import java.io.Serializable;
import java.time.Duration;

/**
 * Durable-log retention policy.
 *
 * @param mode retention mode
 * @param maxBytes maximum retained bytes for size retention
 * @param maxAge maximum retained age for age retention
 */
public record RetentionPolicy(RetentionMode mode, long maxBytes, Duration maxAge) implements Serializable {
    /**
     * Retention mode.
     */
    public enum RetentionMode {
        /**
         * Retention disabled.
         */
        DISABLED,
        /**
         * Size based retention.
         */
        SIZE,
        /**
         * Age based retention.
         */
        AGE
    }

    /**
     * Creates a retention policy.
     */
    public RetentionPolicy {
        mode = mode == null ? RetentionMode.DISABLED : mode;
        if (maxBytes < 0L) {
            throw new IllegalArgumentException("maxBytes must be non-negative");
        }
        maxAge = maxAge == null ? Duration.ZERO : maxAge;
        if (maxAge.isNegative()) {
            throw new IllegalArgumentException("maxAge must be non-negative");
        }
    }

    /**
     * Disabled retention.
     *
     * @return disabled retention policy
     */
    public static RetentionPolicy disabled() {
        return new RetentionPolicy(RetentionMode.DISABLED, 0L, Duration.ZERO);
    }

    /**
     * Size-based retention.
     *
     * @param bytes maximum retained bytes
     * @return size-based retention policy
     */
    public static RetentionPolicy maxBytes(final long bytes) {
        return new RetentionPolicy(RetentionMode.SIZE, bytes, Duration.ZERO);
    }

    /**
     * Age-based retention.
     *
     * @param age maximum retained age
     * @return age-based retention policy
     */
    public static RetentionPolicy maxAge(final Duration age) {
        return new RetentionPolicy(RetentionMode.AGE, 0L, age);
    }
}
