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

import java.util.List;

/**
 * One bounded page within a fixed initial sequence high-water.
 * @param entries immutable page, at most256 entries
 * @param through last returned sequence, unchanged for an empty terminal page
 * @param highWater first-read upper bound; subsequent arrivals cannot extend iteration
 * @param continuation authenticated opaque continuation, empty only for trusted local adapters
 */
public record DeadLetterPage(List<DeadLetter> entries, long through, long highWater, String continuation) {
    /**
     * Validates monotonic bounded page metadata.
     */
    public DeadLetterPage {
        entries = List.copyOf(entries);
        if (continuation == null || continuation.length() > 256) throw new IllegalArgumentException("Invalid page continuation");
        if (entries.size() > 256 || through < 0 || highWater < through) throw new IllegalArgumentException("Invalid dead-letter page");
        long previous = 0;
        for (DeadLetter entry : entries) {
            if (entry.sequence() <= previous || entry.sequence() > through) throw new IllegalArgumentException("Unordered dead-letter page");
            previous = entry.sequence();
        }
    }
    /**
     * Creates a trusted local page without a remote authentication token.
     * @param entries immutable bounded entries
     * @param through last returned sequence
     * @param highWater fixed upper bound
     */
    public DeadLetterPage(List<DeadLetter> entries, long through, long highWater) { this(entries, through, highWater, ""); }
}
