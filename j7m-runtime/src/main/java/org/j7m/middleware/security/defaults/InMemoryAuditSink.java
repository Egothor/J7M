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

package org.j7m.middleware.security.defaults;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.security.api.AuditEvent;
import org.j7m.middleware.security.api.SecurityAuditSink;

/**
 * Bounded in-memory audit sink.
 */
public final class InMemoryAuditSink implements SecurityAuditSink {
    private static final int MAX_RETAINED_EVENTS = 4_096;

    private final int capacity;
    private final Object lock;
    private final Deque<AuditEvent> events;
    private final AtomicLong count;

    /**
     * Creates a sink.
     *
     * @param capacity maximum retained events
     */
    public InMemoryAuditSink(final int capacity) {
        this.capacity = Math.max(1, Math.min(capacity, MAX_RETAINED_EVENTS));
        this.lock = new Object();
        this.events = new ArrayDeque<>(this.capacity);
        this.count = new AtomicLong();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void record(final AuditEvent event) {
        if (event == null) {
            return;
        }
        this.count.incrementAndGet();
        synchronized (this.lock) {
            if (this.events.size() >= this.capacity) {
                this.events.removeFirst();
            }
            this.events.addLast(event);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AuditEvent> recentEvents() {
        synchronized (this.lock) {
            return List.copyOf(this.events);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long eventCount() {
        return this.count.get();
    }
}
