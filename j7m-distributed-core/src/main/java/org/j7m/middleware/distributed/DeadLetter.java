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

/**
 * Immutable dead-letter content and redrive audit lineage.
 * @param messageId original stable identity
 * @param payload complete opaque bytes
 * @param sequence committed ordering sequence
 * @param receipt last delivery token required for authorized redrive
 * @param attempts exhausted delivery attempts
 * @param maximumAttempts original finite limit
 * @param parent parent redrive identity or empty
 * @param child existing redrive child identity or empty
 * @param actor authenticated administrative actor of this redrive child or empty
 * @param depth lineage depth, at most16
 */
public record DeadLetter(String messageId, byte[] payload, long sequence, long receipt, int attempts,
        int maximumAttempts, String parent, String child, String actor, int depth) {
    /**
     * Validates finite metadata and copies content.
     */
    public DeadLetter {
        if (messageId == null || messageId.isBlank() || messageId.length() > 512 || payload == null || payload.length > 32768
                || sequence <= 0 || receipt < 0 || attempts < 0 || attempts > maximumAttempts || maximumAttempts < 1 || maximumAttempts > 1000
                || parent == null || child == null || actor == null || depth < 0 || depth > 16) throw new IllegalArgumentException("Invalid dead letter");
        payload = payload.clone();
    }
    /**
     * Returns an independent content copy.
     * @return copied payload
     */
    @Override public byte[] payload() { return payload.clone(); }
    /**
     * Returns payload-free diagnostic metadata.
     * @return bounded diagnostic form
     */
    @Override public String toString() { return "DeadLetter[sequence=" + sequence + ",receipt=" + receipt + ",depth=" + depth + "]"; }
}
