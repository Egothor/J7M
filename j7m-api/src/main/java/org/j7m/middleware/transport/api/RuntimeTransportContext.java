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

import java.util.List;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.codec.api.ValueCodec;
import org.j7m.middleware.security.api.SecurityContext;

/**
 * Runtime services exposed to transport providers.
 *
 * <p>Implementations are thread-safe. Transport providers may call these
 * methods concurrently from transport worker threads or virtual threads. The
 * returned encoded values are owned by the caller.</p>
 */
public interface RuntimeTransportContext {
    /**
     * Returns the owning runtime identity.
     *
     * @return runtime identity
     */
    RuntimeIdentity identity();

    /**
     * Returns the runtime value codec.
     *
     * @return codec
     */
    ValueCodec codec();

    /**
     * Encodes a value using runtime reference-passing policy.
     *
     * @param value Java value
     * @param declaredType declared Java type
     * @return encoded value
     */
    EncodedValue encodeValue(Object value, Class<?> declaredType);

    /**
     * Decodes a value using runtime reference-passing policy.
     *
     * @param value encoded value
     * @param expectedType expected Java type
     * @return decoded Java value
     */
    Object decodeValue(EncodedValue value, Class<?> expectedType);

    /**
     * Dispatches a generic invocation to a local exported object.
     *
     * @param target target object reference
     * @param method method descriptor
     * @param arguments encoded arguments
     * @param options invocation options
     * @return encoded return value
     */
    EncodedValue dispatch(ObjectRef target, MethodDescriptor method, List<EncodedValue> arguments, InvocationOptions options);

    /**
     * Dispatches an invocation using identity established by a trusted
     * transport or local boundary.
     *
     * <p>Implementations must ignore authentication claims in
     * {@link InvocationOptions#metadata()} and use {@code trustedContext} only
     * when the caller is an authenticated transport or local in-process
     * boundary. The default implementation falls back to untrusted dispatch for
     * compatibility with existing providers.</p>
     *
     * @param target target object reference
     * @param method method descriptor
     * @param arguments encoded arguments
     * @param options invocation options
     * @param trustedContext trusted peer context
     * @return encoded return value
     */
    default EncodedValue dispatchTrusted(
            final ObjectRef target,
            final MethodDescriptor method,
            final List<EncodedValue> arguments,
            final InvocationOptions options,
            final SecurityContext trustedContext) {
        return this.dispatch(target, method, arguments, options);
    }

    /**
     * Dispatches a versioned logical asynchronous result under authenticated transport ownership.
     * Unsupported runtimes fail before decoding or target execution; providers must not fall back to legacy dispatch.
     * @param target authoritative target
     * @param method complete erased method identity; the server verifies its generic result shape
     * @param arguments encoded arguments
     * @param options finite invocation budget
     * @param trustedContext authenticated peer
     * @param cancellation transport-owned cooperative cancellation registration
     * @return encoded logical result after actual result/encoding completion
     */
    default EncodedValue dispatchLogicalAsyncTrusted(ObjectRef target, MethodDescriptor method,
            List<EncodedValue> arguments, InvocationOptions options, SecurityContext trustedContext,
            InvocationCancellation cancellation) {
        throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "Logical async dispatch is unsupported");
    }

    /**
     * Validates that a received reference names a live authoritative export.
     *
     * @param target target object reference received from a client
     * @param requiredInterface required interface name, or an empty string when
     *         no additional interface is required
     */
    default void validateTarget(final ObjectRef target, final String requiredInterface) {
        throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "transport context does not support target validation");
    }

    /**
     * Returns authoritative interfaces for a live exported target.
     *
     * @param target target object reference received from a client
     * @return immutable authoritative interface names
     */
    default List<String> describeTarget(final ObjectRef target) {
        this.validateTarget(target, "");
        return target == null ? List.of() : target.interfaces();
    }

    /**
     * Creates or finds a local reference for a callback argument.
     *
     * @param value callback implementation
     * @param declaredType declared interface type
     * @return exported object reference
     */
    ObjectRef exportCallback(Object value, Class<?> declaredType);

    /**
     * Releases automatic callback references exported for an invocation that
     * did not complete or could not be admitted.
     *
     * <p>Implementations must only release automatic callback exports owned by
     * the current runtime. Successful callback registration may intentionally
     * outlive the parent invocation so a remote endpoint can call the listener
     * later; those automatic exports remain bounded by runtime-owned live
     * callback limits and are closed with the owning runtime. Explicit
     * long-lived exports are not automatic callback exports and remain
     * available until their registration is closed.</p>
     *
     * @param encodedArguments encoded invocation arguments to inspect
     */
    default void releaseInvocationCallbacks(final List<EncodedValue> encodedArguments) {
    }

    /**
     * Releases callback references that were exported for a failed invocation.
     *
     * @param encodedArguments encoded invocation arguments to inspect
     * @deprecated use {@link #releaseInvocationCallbacks(List)} for failed,
     *         cancelled, timed-out, or admission-failed invocation cleanup
     */
    @Deprecated(since = "0.1.0", forRemoval = false)
    default void releaseFailedInvocationCallbacks(final List<EncodedValue> encodedArguments) {
        this.releaseInvocationCallbacks(encodedArguments);
    }

    /**
     * Submits a one-way invocation to runtime-owned bounded execution.
     *
     * @param task invocation task
     * @throws MiddlewareException when the task cannot be admitted
     */
    default void executeOneWay(final Runnable task) {
        throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "one-way invocation execution is not configured");
    }

    /**
     * Creates a proxy for the supplied reference.
     *
     * @param ref target reference
     * @param api required Java interface
     * @param <T> interface type
     * @return proxy
     */
    <T> T proxyFor(ObjectRef ref, Class<T> api);
}
