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
 * Complete immutable payload granted by one committed receipt, never an availability hint.
 * A repeated frame does not authorize repeating an external effect. ACK and renewal must use the
 * exact member/generation/receipt; applications need durable deduplication or an inbox.
 * @param queue FIFO queue identity
 * @param group server-bound consumer group
 * @param member consumer identity retained across reconnects
 * @param generation committed group generation
 * @param messageId stable business message identity
 * @param payload opaque immutable message bytes, at most32768
 * @param sequence durable message order
 * @param receipt delivery fencing token
 * @param attempt current delivery attempt
 * @param observational true for a retained DLQ observation, which grants no ACK/removal/redrive authority
 */
public record QueueDelivery(String queue, String group, String member, long generation, String messageId,
        byte[] payload, long sequence, long receipt, int attempt, boolean observational) {
    /**
     * Validates bounded identity and clones payload before publication.
     */
    public QueueDelivery {
        QueueStateMachine.validate(new ResourceCommand("Q_ACK", "validation", "validation",
                List.of(queue, group, member, Long.toString(generation), messageId, Long.toString(receipt))));
        payload = payload.clone();
        if (payload.length > 32768 || sequence <= 0 || attempt < 1 || attempt > 1000) throw new IllegalArgumentException("Invalid queue delivery");
    }
    /**
     * Creates an ordinary exclusive queue grant with the same validation and defensive copy.
     * @param queue queue identity
     * @param group group identity
     * @param member member identity
     * @param generation committed generation
     * @param messageId stable identity
     * @param payload message bytes
     * @param sequence durable ordering
     * @param receipt exact receipt
     * @param attempt attempt number
     */
    public QueueDelivery(String queue, String group, String member, long generation, String messageId, byte[] payload, long sequence, long receipt, int attempt) {
        this(queue, group, member, generation, messageId, payload, sequence, receipt, attempt, false);
    }
    /**
     * Returns an independent payload copy.
     * @return copied message bytes
     */
    @Override public byte[] payload() { return payload.clone(); }
    /**
     * Returns receipt metadata without exposing payload.
     * @return fixed bounded diagnostic form
     */
    @Override public String toString() { return "QueueDelivery[sequence=" + sequence + ",receipt=" + receipt + ",bytes=" + payload.length + "]"; }
}
