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

/**
 * Accessor for the current application invocation context.
 *
 * <p>The implementation uses a private {@link ThreadLocal}. Runtime and
 * deployer code must use the returned {@link Scope} with try-with-resources so
 * context is cleared after dispatch. Access outside a managed invocation
 * returns an empty safe context.</p>
 */
public final class J7mContext {
    private static final ThreadLocal<InvocationContext> CURRENT = new ThreadLocal<>();

    private J7mContext() {
    }

    /**
     * Returns the current invocation context or an empty safe context.
     *
     * @return current context
     */
    public static InvocationContext currentInvocation() {
        InvocationContext context = CURRENT.get();
        return context == null ? InvocationContext.empty() : context;
    }

    /**
     * Installs a context until the returned scope is closed.
     *
     * @param context context to install
     * @return closeable scope
     */
    public static Scope open(final InvocationContext context) {
        InvocationContext previous = CURRENT.get();
        CURRENT.set(context == null ? InvocationContext.empty() : context);
        return new Scope(previous);
    }

    /**
     * Scope returned by {@link #open(InvocationContext)}.
     */
    public static final class Scope implements AutoCloseable {
        private final InvocationContext previous;
        private boolean closed;

        private Scope(final InvocationContext previous) {
            this.previous = previous;
        }

        /**
         * Restores the previous context.
         */
        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            if (this.previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(this.previous);
            }
        }
    }
}
