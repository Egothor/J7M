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

package org.j7m.middleware.adapter.spi;

import java.util.Map;

import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.RemoteError;

/**
 * Public exception for adapter SPI and TCK failures.
 *
 * <p>The exception is immutable after construction and safe to share across
 * threads. Messages and metadata must already be redacted when the exception is
 * created. Retryability follows the embedded {@link MiddlewareErrorCode}; for
 * example, timeout and overload failures may be retried by an adapter-specific
 * policy while invalid configuration should not be retried without correction.</p>
 */
public final class AdapterException extends MiddlewareException {
    /**
     * Creates an adapter exception.
     *
     * @param code stable middleware error code
     * @param message safe human-readable message
     */
    public AdapterException(final MiddlewareErrorCode code, final String message) {
        super(new RemoteError(code, message, Map.of(), null, null, false, false));
    }

    /**
     * Creates an adapter exception with a root cause.
     *
     * @param code stable middleware error code
     * @param message safe human-readable message
     * @param cause implementation cause that must not expose secrets through its message
     */
    public AdapterException(final MiddlewareErrorCode code, final String message, final Throwable cause) {
        super(new RemoteError(code, message, Map.of(), null, null, false, false), cause);
    }
}
