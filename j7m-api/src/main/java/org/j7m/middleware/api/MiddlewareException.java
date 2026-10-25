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

package org.j7m.middleware.api;

import java.util.Map;

/**
 * Base unchecked exception for all public middleware failures.
 *
 * <p>The exception is immutable after construction and safe to share across
 * threads. Low-level exceptions are available only as causes and should not be
 * inspected by portable callers.</p>
 */
public class MiddlewareException extends RuntimeException {
    /**
     * Normalized immutable failure payload.
     */
    private final RemoteError remoteError;

    /**
     * Creates an exception from a normalized remote error.
     *
     * @param remoteError normalized error payload
     */
    public MiddlewareException(final RemoteError remoteError) {
        super(remoteError == null ? MiddlewareErrorCode.INTERNAL.name() : remoteError.message());
        this.remoteError = remoteError == null
                ? new RemoteError(MiddlewareErrorCode.INTERNAL, "internal middleware error", Map.of(), null, null, false, true)
                : remoteError;
    }

    /**
     * Creates an exception from a normalized remote error and root cause.
     *
     * @param remoteError normalized error payload
     * @param cause implementation exception that caused the failure
     */
    public MiddlewareException(final RemoteError remoteError, final Throwable cause) {
        super(remoteError == null ? MiddlewareErrorCode.INTERNAL.name() : remoteError.message(), cause);
        this.remoteError = remoteError == null
                ? new RemoteError(MiddlewareErrorCode.INTERNAL, "internal middleware error", Map.of(), null, null, false, true)
                : remoteError;
    }

    /**
     * Creates a simple exception with no transport metadata.
     *
     * @param code stable error code
     * @param message safe message
     * @return new middleware exception
     */
    public static MiddlewareException of(final MiddlewareErrorCode code, final String message) {
        return new MiddlewareException(new RemoteError(code, message, Map.of(), null, null, false, false));
    }

    /**
     * Creates a simple exception with transport metadata.
     *
     * @param code stable error code
     * @param message safe message
     * @param invocationId invocation identifier
     * @param remoteRuntimeId remote runtime identifier
     * @param retryable retry hint
     * @param dirtyTarget target health hint
     * @return new middleware exception
     */
    public static MiddlewareException remote(
            final MiddlewareErrorCode code,
            final String message,
            final String invocationId,
            final String remoteRuntimeId,
            final boolean retryable,
            final boolean dirtyTarget) {
        return new MiddlewareException(new RemoteError(code, message, Map.of(), invocationId, remoteRuntimeId, retryable, dirtyTarget));
    }

    /**
     * Returns the normalized error payload.
     *
     * @return immutable error payload
     */
    public RemoteError remoteError() {
        return this.remoteError;
    }

    /**
     * Returns the stable error code.
     *
     * @return error code
     */
    public MiddlewareErrorCode code() {
        return this.remoteError.code();
    }

    /**
     * Returns whether retrying the operation may succeed.
     *
     * @return retryable flag
     */
    public boolean retryable() {
        return this.remoteError.retryable();
    }

    /**
     * Returns whether the current target should be replaced before retry.
     *
     * @return dirty-target flag
     */
    public boolean dirtyTarget() {
        return this.remoteError.dirtyTarget();
    }
}
