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
 * Trusted host-installed dependency-call interceptor; application annotations never install it.
 * The default preserves the existing one-attempt behavior. Implementations must not change caller
 * authority or repeat an attempt after business entry without a separately defined durable protocol.
 */
@FunctionalInterface
public interface ClientCallInterceptor {
    /**
     * Wraps one logical call within its original total deadline.
     * @param call immutable identity and pinned target
     * @param attempt host-owned state and execution closure
     * @return business result
     * @throws Throwable the final call or policy failure
     */
    Object invoke(ClientCall call, ClientAttempt attempt) throws Throwable;

    /**
     * Validates interface policy metadata before discovery or application construction.
     * @param contract declared ordinary business interface
     * @throws IllegalArgumentException for unsupported/conflicting policy metadata
     */
    default void validate(Class<?> contract) { }

    /**
     * Returns the default interceptor that executes the supplied attempt exactly once.
     * @return stateless thread-safe single-attempt interceptor
     */
    static ClientCallInterceptor singleAttempt() { return (call, attempt) -> attempt.execute(); }
}
