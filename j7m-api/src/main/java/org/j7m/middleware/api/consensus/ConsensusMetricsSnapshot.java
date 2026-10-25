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

package org.j7m.middleware.api.consensus;

import java.io.Serializable;

/**
 * Safe consensus metrics snapshot.
 *
 * @param elections elections started
 * @param leadersElected leaders elected
 * @param appendRequests append requests processed
 * @param appendRejections append rejections
 * @param voteRequests vote requests processed
 * @param voteRejections vote rejections
 * @param commandsCommitted committed commands
 * @param recoveries recovery count
 * @param invariantFailures invariant failure count
 */
public record ConsensusMetricsSnapshot(
        long elections,
        long leadersElected,
        long appendRequests,
        long appendRejections,
        long voteRequests,
        long voteRejections,
        long commandsCommitted,
        long recoveries,
        long invariantFailures) implements Serializable {
    /**
     * Creates metrics.
     */
    public ConsensusMetricsSnapshot {
        ConsensusValues.nonNegative("elections", elections);
        ConsensusValues.nonNegative("leaders elected", leadersElected);
        ConsensusValues.nonNegative("append requests", appendRequests);
        ConsensusValues.nonNegative("append rejections", appendRejections);
        ConsensusValues.nonNegative("vote requests", voteRequests);
        ConsensusValues.nonNegative("vote rejections", voteRejections);
        ConsensusValues.nonNegative("commands committed", commandsCommitted);
        ConsensusValues.nonNegative("recoveries", recoveries);
        ConsensusValues.nonNegative("invariant failures", invariantFailures);
    }
}
