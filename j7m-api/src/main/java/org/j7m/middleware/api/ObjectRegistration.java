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

import java.time.Duration;

/**
 * Registration handle returned from {@link MiddlewareRuntime#export}.
 *
 * <p>The handle is thread-safe when implemented by the runtime. Closing it is
 * idempotent and unexports the concrete instance represented by {@link #ref()}.</p>
 *
 * @param <T> primary Java interface type
 */
public interface ObjectRegistration<T> extends AutoCloseable {
    /**
     * Returns the exported object reference.
     *
     * @return object reference
     */
    ObjectRef ref();

    /**
     * Returns the local target instance owned by the runtime.
     *
     * @return local target instance
     */
    T instance();

    /**
     * Returns the current instance status.
     *
     * @return status snapshot
     */
    InstanceStatus status();

    /**
     * Sets host-owned readiness with a finite freshness bound, without changing export identity.
     * Only the registration owner receives this handle; invocation metadata cannot call this setter.
     * Once enabled, expiry is unready even if the probe/heartbeat scheduler stops. Legacy exports
     * which never opt in retain their existing unlimited readiness behavior. A false observation
     * never becomes ready merely because it expires. Unsupported runtimes fail closed.
     * @param ready latest trusted host readiness result
     * @param validity freshness duration from1millisecond through5minutes
     * @throws MiddlewareException for unsupported runtimes, invalid validity or closed registrations
     */
    default void setReadiness(boolean ready, Duration validity) {
        throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Host readiness control is unsupported");
    }

    /**
     * Unexports the object. The method is idempotent.
     */
    @Override
    void close();
}
