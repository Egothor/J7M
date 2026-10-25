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

/**
 * Deterministic smoke entry point for remote invocation metadata.
 *
 * <p>The entry point runs a tiny local invocation sequence so validation can
 * compare documented output against actual output. It intentionally does not
 * start a transport or fabricate remote callback policy.</p>
 */
public final class RemoteInvocationExampleMain {
    private RemoteInvocationExampleMain() {
    }

    /**
     * Runs the remote invocation smoke flow.
     *
     * @param args ignored command-line arguments
     */
    public static void main(final String[] args) {
        // J7M teaching: local invocation proves descriptor shape without
        // starting a transport, creating a proxy, or granting callback authority.
        final RemoteInvocationService service = new RemoteInvocationService();
        final StringBuilder callbackValue = new StringBuilder();
        final RemoteInvocationService.RemoteStatusCallback callback = callbackValue::append;

        service.quote("invoice-7");
        service.notifyAccepted("invoice-7", callback);
        final String nonIdempotent = service.auditAdjustment("invoice-7");

        // J7M teaching: the output distinguishes retry-safe metadata from the
        // non-idempotent method where automatic retry is disabled.
        System.out.println("annotation-first remote descriptor: retry-safe quote attempts=2 deadline=250ms");
        System.out.println("annotation-first remote one-way accepted: void");
        System.out.println("annotation-first remote callback value: " + callbackValue);
        System.out.println("annotation-first remote non-idempotent: " + nonIdempotent);
    }
}
