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

package org.j7m.middleware.registry.mcast;

import java.util.Map;

/**
 * Immutable multicast message envelope.
 *
 * @param messageType envelope message type
 * @param senderId sender registry id
 * @param receiverId receiver id or {@code *}
 * @param contextId registry context
 * @param questionId correlation id
 * @param queryType query type
 * @param createdAtMillis creation timestamp
 * @param ttlMillis message TTL
 * @param depth hop depth
 * @param protocolVersion protocol version
 * @param metadata public metadata
 */
public record MulticastMessageEnvelope(
        MulticastMessageType messageType,
        String senderId,
        String receiverId,
        String contextId,
        String questionId,
        MulticastQueryType queryType,
        long createdAtMillis,
        long ttlMillis,
        int depth,
        int protocolVersion,
        Map<String, String> metadata) {
    /**
     * Creates an immutable envelope.
     */
    public MulticastMessageEnvelope {
        messageType = messageType == null ? MulticastMessageType.QUESTION : messageType;
        senderId = senderId == null ? "" : senderId;
        receiverId = receiverId == null || receiverId.isBlank() ? "*" : receiverId;
        contextId = contextId == null || contextId.isBlank() ? "default" : contextId;
        questionId = questionId == null ? "" : questionId;
        queryType = queryType == null ? MulticastQueryType.PING : queryType;
        ttlMillis = ttlMillis <= 0L ? 500L : ttlMillis;
        protocolVersion = protocolVersion <= 0 ? 1 : protocolVersion;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
