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

package org.j7m.middleware.transport.api;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;

/**
 * Host-owned physical lifetime spanning decode, root invocation, tracked work and encoding.
 * Reply timeout/cancellation is independent of physical completion. Implementations serialize CALL
 * context access and never treat cancellation of an arbitrary application future as worker exit.
 */
public interface InvocationCompletionScope extends AutoCloseable {
    /**
     * Runs root application entry exactly once and observes its logical result before detaching context.
     * @param action decoding and actual target entry
     * @param <T> root result type
     * @return logical result, possibly a stage
     * @throws Exception if application entry fails
     */
    <T> T call(Callable<T> action) throws Exception;
    /**
     * Serializes a continuation under the same captured context without creating a second root.
     * @param action encoding or another host continuation
     * @param <T> result type
     * @return completion after continuation context has actually exited
     */
    <T> CompletionStage<T> continueInContext(Callable<T> action);
    /**
     * Reports whether this implementation supports trusted result finalizers and typed attachments.
     * @return false for compatibility-only ownership adapters
     */
    default boolean supportsResultFinalizers() { return false; }
    /**
     * Registers one host finalizer before actual application body entry.
     * Registration is bounded and frozen at body entry; applications never receive this installer.
     * @param finalizer trusted non-null finalizer
     */
    default void addResultFinalizer(InvocationResultFinalizer finalizer) {
        throw new IllegalStateException("Result finalization is unsupported");
    }
    /**
     * Installs a bounded exact-type host attachment before application body entry.
     * @param type exact attachment key
     * @param value non-null value assignable to type
     * @param <T> attachment type
     */
    default <T> void putContext(Class<T> type, T value) {
        throw new IllegalStateException("Invocation attachments are unsupported");
    }
    /**
     * Reads an installed host attachment under the current invocation's serial CALL ownership.
     * @param type exact installed attachment key
     * @param <T> attachment type
     * @return invocation-owned attachment, never a mutable attachment registry
     */
    default <T> T context(Class<T> type) {
        throw new IllegalStateException("Invocation attachments are unsupported");
    }
    /**
     * Finds an exact-type attachment only inside this invocation's actual owned CALL.
     * Empty means a missing key; unsupported, outside-owner and closed access must fail instead.
     * @param type exact attachment key
     * @param <T> attachment type
     * @return read-only optional attachment, never a mutable registry
     */
    default <T> java.util.Optional<T> findContext(Class<T> type) {
        throw new IllegalStateException("Invocation attachments are unsupported");
    }
    /**
     * Freezes host registration immediately before the generated application body executes.
     * Compatibility-only adapters without finalizers may ignore this notification.
     */
    default void markApplicationEntry() { }

    /**
     * Returns a host-owned mirror of the logical root result observed inside the captured context.
     * The kernel must await this mirror rather than invoke application-controlled future conversion hooks.
     * Cancellation of a result may complete this view exceptionally while physical ownership remains retained.
     * @return read-only host logical result completion
     */
    CompletionStage<Object> logicalResult();
    /**
     * Returns read-only completion after boundary exit, root exit, all tracked work and cleanup.
     * @return physical completion; cancellation of this view cannot release owned resources
     */
    CompletionStage<Void> physicalCompletion();
    /**
     * Signals cooperative cancellation; does not interrupt, undo or close application ownership.
     */
    void requestCancellation();
    /**
     * Aborts only an invocation whose root has never entered application decoding or target code.
     */
    void abortBeforeTarget();
    /**
     * Marks actual kernel encode/boundary exit. Idempotent; never fabricates physical completion.
     */
    @Override void close();
}
