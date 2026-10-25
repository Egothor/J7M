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
 * Prepared object export that is transport-ready but not discoverable.
 *
 * <p>A prepared registration is owned by the runtime that created it. Until
 * {@link #commit()} succeeds, the object reference returned by {@link #ref()}
 * is not present in runtime lookup/list results, is not bound in the registry,
 * and is not heartbeat-maintained. The runtime may already have installed
 * transport-local dispatch state needed to make a later commit inexpensive;
 * callers must therefore close the prepared handle when activation or another
 * publication transaction fails.</p>
 *
 * <p>Instances are thread-safe when supplied by a runtime implementation.
 * Closing an uncommitted handle rolls back transport state and lifecycle
 * ownership. Closing after a successful commit closes the committed
 * {@link ObjectRegistration}. The handle never exposes concrete transport
 * implementation handles.</p>
 *
 * @param <T> primary Java interface type
 */
public interface PreparedObjectRegistration<T> extends AutoCloseable {
    /**
     * Returns the object reference allocated for the prepared export.
     *
     * @return object reference, not discoverable until commit
     */
    ObjectRef ref();

    /**
     * Returns the local target instance owned by the runtime.
     *
     * @return local target instance
     */
    T instance();

    /**
     * Sets host-owned readiness with a finite freshness bound, without changing export identity.
     * Set initial false before commit to prevent a publication window. Only the owner receives this handle; invocation metadata cannot call this setter.
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
     * Installs trusted physical invocation ownership before publication.
     * The factory is local host state and is never serialized in export options. Unsupported runtimes
     * fail closed; changing it after commit is forbidden.
     * @param factory non-null host-owned invocation admission and lifetime factory
     * @throws MiddlewareException if unsupported, closed or already committed
     */
    default void setInvocationCompletionFactory(org.j7m.middleware.transport.api.InvocationCompletionFactory factory) {
        throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Physical invocation ownership is unsupported");
    }

    /**
     * Installs an immutable host-owned codec for this export before publication.
     * The codec and its verified schema allowlist are local state, never wire metadata. Both inbound
     * arguments and outbound results use it; local lookup must preserve the same dispatch boundary.
     * The host must supply a thread-safe codec without a mutable global application class registry.
     * @param codec non-null trusted export codec
     * @throws MiddlewareException if unsupported, closed, already installed or committed
     */
    default void setValueCodec(org.j7m.middleware.codec.api.ValueCodec codec) {
        throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Export codec isolation is unsupported");
    }

    /**
     * Commits the prepared export into runtime lookup/list state and registry
     * publication.
     *
     * @return committed registration handle
     * @throws MiddlewareException when registry publication, heartbeat setup, or
     *         runtime lifecycle ownership fails
     */
    ObjectRegistration<T> commit();

    /**
     * Rolls back the prepared export, or closes the committed registration when
     * commit has already succeeded. The method is idempotent.
     */
    @Override
    void close();
}
