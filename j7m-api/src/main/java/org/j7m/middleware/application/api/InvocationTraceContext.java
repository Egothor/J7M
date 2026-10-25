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

package org.j7m.middleware.application.api;

import java.util.Map;
import java.util.Objects;

/**
 * Bounded data-only W3C parent context for host-tracked work and generic remote hops.
 * Trace metadata is never authentication, authorization or an idempotency identity. Vendor state,
 * baggage and arbitrary fields are dropped. Only valid version-00 traceparent is propagated.
 * Scopes are thread-confined; current snapshots are immutable and safe to capture for another task.
 */
public final class InvocationTraceContext {
    private static final ThreadLocal<Map<String, String>> CURRENT = new ThreadLocal<>();
    private InvocationTraceContext() { }
    /**
     * Returns the immutable allowed traceparent snapshot, or an empty map.
     * @return at most one fixed-size W3C header
     */
    public static Map<String, String> current() {
        Map<String, String> current = CURRENT.get(); return current == null ? Map.of() : current;
    }
    /**
     * Filters untrusted metadata without evaluating payloads or retaining arbitrary headers.
     * Malformed, oversized, zero-identity or unsupported-version input becomes an empty carrier.
     * @param metadata source headers, nullable
     * @return immutable empty or single-header carrier
     */
    public static Map<String, String> sanitized(Map<String, String> metadata) {
        String parent = metadata == null ? null : metadata.get("traceparent");
        if (parent == null || parent.length() != 55 || !parent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                || parent.substring(3, 35).equals("0".repeat(32)) || parent.substring(36, 52).equals("0".repeat(16))) return Map.of();
        return Map.of("traceparent", parent);
    }
    /**
     * Attaches a sanitized snapshot on the current thread and restores its predecessor on close.
     * @param metadata allowed carrier or an arbitrary map which is filtered
     * @return thread-confined idempotent owner scope
     */
    public static Scope open(Map<String, String> metadata) {
        Map<String, String> previous = CURRENT.get();
        CURRENT.set(sanitized(metadata)); return new Scope(previous, Thread.currentThread());
    }
    /**
     * Thread-confined restoration handle; it grants no host invocation ownership.
     */
    public static final class Scope implements AutoCloseable {
        private final Map<String, String> previous;
        private final Thread owner;
        private boolean closed;
        private Scope(Map<String, String> previous, Thread owner) { this.previous = previous; this.owner = owner; }
        /**
         * Restores the previous trace snapshot exactly once on the creating thread.
         * @throws IllegalStateException if called from a different thread
         */
        @Override public void close() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Trace scope belongs to creating thread");
            if (!closed) { closed = true; if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
        }
    }
}
