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

package org.j7m.middleware.cluster;

import java.time.Instant;
import java.util.Objects;

/**
 * Handler-visible cooperative call budget for the actual hosted invocation. The handler may
 * capture this immutable context for child work and must poll cancellation at safe boundaries.
 * Cancellation means deadline expiry or interruption of the dispatch thread; it never rolls back
 * a committed/external effect and never proves the handler has finished. There is no remote early
 * cancellation operation in this version. This context grants no security or coordination authority.
 */
public final class ManagedCallContext {
    private static final ThreadLocal<ManagedCallContext> CURRENT=new ThreadLocal<>();
    private final Instant deadline;
    private final Thread dispatch;
    private ManagedCallContext(Instant deadline){this.deadline=Objects.requireNonNull(deadline);dispatch=Thread.currentThread();}
    /**
     * Returns the actual current hosted call context, without creating an unbounded budget.
     * @return current context, or null outside managed invocation
     */
    public static ManagedCallContext current(){return CURRENT.get();}
    /**
     * Returns the caller's absolute deadline propagated over the authenticated transport.
     * @return immutable caller deadline
     */
    public Instant deadline(){return deadline;}
    /**
     * Reports cooperative cancellation without clearing the interruption flag.
     * @return whether the caller budget expired or dispatch was interrupted
     */
    public boolean cancellationRequested(){return !Instant.now().isBefore(deadline)||dispatch.isInterrupted();}
    static Scope open(Instant deadline){ManagedCallContext previous=CURRENT.get();CURRENT.set(new ManagedCallContext(deadline));return new Scope(previous);}
    static final class Scope implements AutoCloseable {
        private final ManagedCallContext previous;private boolean closed;
        Scope(ManagedCallContext previous){this.previous=previous;}
        @Override public void close(){if(closed)return;closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
}
