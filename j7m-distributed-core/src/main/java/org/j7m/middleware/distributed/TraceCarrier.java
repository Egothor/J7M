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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded W3C trace context, suitable for persisted RPC, message and job metadata.
 * Baggage and arbitrary headers are deliberately excluded to avoid propagating secrets.
 * @param traceparent version-00 traceparent, or empty when no context exists
 * @param tracestate W3C vendor state, at most 512 ASCII bytes, or empty
 */
public record TraceCarrier(String traceparent, String tracestate) {
    /**
     * Validates context before it is admitted to durable storage.
     * @param traceparent version-00 traceparent or empty
     * @param tracestate bounded vendor state or empty
     * @throws IllegalArgumentException for malformed, oversized or unpaired metadata
     */
    public TraceCarrier {
        Objects.requireNonNull(traceparent); Objects.requireNonNull(tracestate);
        if (!traceparent.isEmpty() && (!traceparent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                || traceparent.substring(3, 35).equals("0".repeat(32)) || traceparent.substring(36, 52).equals("0".repeat(16))))
            throw new IllegalArgumentException("Malformed W3C traceparent");
        if (tracestate.length() > 512 || !StandardCharsets.US_ASCII.newEncoder().canEncode(tracestate)
                || tracestate.chars().anyMatch(c -> c < 32 || c == 127) || traceparent.isEmpty() && !tracestate.isEmpty())
            throw new IllegalArgumentException("Malformed W3C tracestate");
        if (!tracestate.isEmpty()) {
            String[] members = tracestate.split(",", -1);
            if (members.length > 32) throw new IllegalArgumentException("Too many tracestate members");
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (String member : members) {
                String[] pair = member.strip().split("=", -1);
                if (pair.length != 2 || !pair[0].matches("[a-z0-9][a-z0-9_*/@.-]{0,255}") || pair[1].isBlank()
                        || pair[1].length() > 256 || pair[1].contains("=") || !keys.add(pair[0])) throw new IllegalArgumentException("Malformed tracestate member");
            }
        }
    }
    /**
     * Returns a context-free carrier.
     * @return empty carrier
     */
    public static TraceCarrier empty() { return new TraceCarrier("", ""); }
    /**
     * Reads only allowed W3C fields, rejecting unknown metadata.
     * @param headers map containing traceparent and optional tracestate
     * @return validated carrier
     * @throws IllegalArgumentException for unknown keys or malformed context
     */
    public static TraceCarrier fromMap(Map<String, String> headers) {
        if (!java.util.Set.of("traceparent", "tracestate").containsAll(headers.keySet())) throw new IllegalArgumentException("Unsupported trace headers");
        return new TraceCarrier(headers.getOrDefault("traceparent", ""), headers.getOrDefault("tracestate", ""));
    }
    /**
     * Exposes an immutable propagation map.
     * @return empty map or bounded W3C headers
     */
    public Map<String, String> asMap() {
        if (traceparent.isEmpty()) return Map.of();
        if (tracestate.isEmpty()) return Map.of("traceparent", traceparent);
        return Map.of("traceparent", traceparent, "tracestate", tracestate);
    }
}
