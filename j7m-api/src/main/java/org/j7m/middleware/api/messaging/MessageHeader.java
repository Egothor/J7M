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

package org.j7m.middleware.api.messaging;

import java.io.Serializable;
import java.util.Map;

/**
 * Safe public metadata attached to a durable queue message.
 *
 * <p>The record is immutable, serializable, and thread-safe. Implementations
 * must preserve this metadata through durable enqueue, outbox recording, and
 * recovery. Header keys and values are intended for routing and diagnostics and
 * must not contain raw payload data or secrets.</p>
 *
 * @param metadata safe metadata map
 */
public record MessageHeader(Map<String, String> metadata) implements Serializable {
    /**
     * Creates validated message header metadata.
     *
     * @throws IllegalArgumentException when a key or value is blank, too long, or not log-safe
     */
    public MessageHeader {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            MessagingValues.safeText("message header key", entry.getKey(), 128);
            MessagingValues.safeText("message header value", entry.getValue(), 512);
        }
    }

    /**
     * Returns an empty immutable header.
     *
     * @return empty header
     */
    public static MessageHeader empty() {
        return new MessageHeader(Map.of());
    }
}
