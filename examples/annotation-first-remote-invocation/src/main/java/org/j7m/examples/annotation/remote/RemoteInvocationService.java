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

package org.j7m.examples.annotation.remote;

import org.j7m.middleware.annotations.Callback;
import org.j7m.middleware.annotations.Deadline;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.J7mService;
import org.j7m.middleware.annotations.NonIdempotent;
import org.j7m.middleware.annotations.OneWay;
import org.j7m.middleware.annotations.RemoteMethod;
import org.j7m.middleware.annotations.RemoteService;
import org.j7m.middleware.annotations.Retry;

/**
 * Focused remote-invocation service for retry, deadline, one-way, callback,
 * and idempotency metadata.
 *
 * <p>{@link RemoteService}, {@link J7mService}, and {@link RemoteMethod}
 * describe the methods that may be exported through ordinary J7M descriptor
 * and ObjectRef paths. Retry, deadline, callback, one-way, and idempotency
 * annotations are descriptor metadata in this example. This source does not
 * open a listener, create a proxy, or bypass authorization. It keeps
 * transport-neutral invocation policy in the descriptor where runtime dispatch
 * can consume it.</p>
 *
 * <p>{@link Retry} is paired with {@link Idempotent} on the quote method so
 * automatic retry is never implied for unsafe operations. {@link OneWay} is
 * demonstrated only on a {@code void} method. {@link Callback} is used on an
 * interface and parameter to show the ObjectRef-compatible shape without
 * fabricating a callback export policy in the example itself.</p>
 */
@RemoteService
@J7mService(name = "//apps/example/annotation/remote/InvocationService", exported = true)
public final class RemoteInvocationService {
    // J7M teaching: invocation annotations are grouped here so the simple
    // example can stay focused on first-step service and injection concepts.

    /**
     * Returns a deterministic quote summary for a retry-safe operation.
     *
     * @param invoiceId public invoice identifier
     * @return safe quote summary
     */
    @RemoteMethod
    @Idempotent
    @Retry(maxAttempts = 2)
    @Deadline(millis = 250)
    public String quote(final String invoiceId) {
        // J7M teaching: retry metadata is present only on an idempotent method;
        // non-idempotent work below explicitly disables automatic retry.
        return "quote:" + invoiceId;
    }

    /**
     * Demonstrates one-way invocation metadata on a method with no return
     * value.
     *
     * @param invoiceId public invoice identifier
     * @param callback callback reference candidate supplied by the caller
     */
    @RemoteMethod
    @OneWay
    public void notifyAccepted(final String invoiceId, @Callback final RemoteStatusCallback callback) {
        // J7M teaching: the one-way method returns void; callback invocation is
        // local smoke evidence here, not a fabricated remote callback export.
        callback.accept("callback:" + invoiceId);
    }

    /**
     * Marks an unsafe operation as unavailable for automatic retry.
     *
     * @param invoiceId public invoice identifier
     * @return stable policy summary
     */
    @RemoteMethod
    @NonIdempotent
    public String auditAdjustment(final String invoiceId) {
        return "automatic-retry-disabled";
    }

    /**
     * Callback contract used by the remote invocation example.
     *
     * <p>The annotation documents the ObjectRef-compatible intent. The example
     * does not export the callback over a transport or grant callback authority
     * by possession of the reference alone.</p>
     */
    @Callback
    public interface RemoteStatusCallback {
        /**
         * Accepts a safe callback value.
         *
         * @param value public callback message
         */
        void accept(String value);
    }
}
