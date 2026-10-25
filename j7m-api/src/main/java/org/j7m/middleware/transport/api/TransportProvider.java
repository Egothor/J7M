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
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ProxyOptions;
import org.j7m.middleware.codec.api.EncodedValue;

/**
 * Stable SPI for middleware transports.
 *
 * <p>Implementations must be thread-safe after {@link #start(RuntimeTransportContext)}.
 * A provider is owned by exactly one runtime instance in Phase 1. Providers
 * must wrap low-level failures in middleware exceptions.</p>
 */
public interface TransportProvider extends AutoCloseable {
    /**
     * Returns the stable transport identifier.
     *
     * @return transport id
     */
    String id();

    /**
     * Starts this provider for one runtime.
     *
     * @param context runtime context
     * @return endpoint descriptor
     */
    TransportEndpoint start(RuntimeTransportContext context);

    /**
     * Exports one local object through this transport.
     *
     * @param ref object reference
     * @param target local target
     * @param descriptor export descriptor
     * @return exported object handle
     */
    ExportedObject export(ObjectRef ref, Object target, ExportDescriptor descriptor);

    /**
     * Unexports one object. The method is idempotent.
     *
     * @param ref object reference
     */
    void unexport(ObjectRef ref);

    /**
     * Invokes a method on a target reference.
     *
     * @param target target reference
     * @param method method descriptor
     * @param arguments encoded arguments
     * @param options invocation options
     * @return decoded return value
     */
    Object invoke(ObjectRef target, MethodDescriptor method, List<EncodedValue> arguments, InvocationOptions options);
    /**
     * Invokes with the exact return class established from the local Java contract.
     * This host-local token preserves an application classloader's schema allowlist; it is never
     * read from the reply or loaded from a wire class name. Descriptor-only calls retain their
     * transport's documented class-resolution fallback. Providers supporting isolated application
     * schemas override this method and decode local and remote replies with this exact token.
     * @param target pinned reference
     * @param method verified method identity
     * @param arguments bounded encoded arguments
     * @param options remaining invocation budget
     * @param returnType exact local declared return class, matching the descriptor
     * @return decoded value
     * @throws org.j7m.middleware.api.MiddlewareException for a mismatched local contract token
     */
    default Object invoke(ObjectRef target, MethodDescriptor method, List<EncodedValue> arguments, InvocationOptions options, Class<?> returnType) {
        if (returnType == null || !returnType.getName().equals(method.returnTypeName())) {
            throw org.j7m.middleware.api.MiddlewareException.of(org.j7m.middleware.api.MiddlewareErrorCode.INVALID_ARGUMENT, "Return contract mismatch");
        }
        return invoke(target, method, arguments, options);
    }

    /**
     * Invokes the dedicated logical asynchronous protocol without falling back to a legacy method.
     * Implementations return promptly and do not encode or serialize a future object.
     * @param target pinned target reference
     * @param method complete erased method identity
     * @param arguments immutable encoded arguments
     * @param options remaining total invocation budget
     * @param result validated concrete logical result shape
     * @return cancellable reply stage; cancellation does not prove physical completion or undo
     */
    default java.util.concurrent.CompletableFuture<Object> invokeAsync(ObjectRef target, MethodDescriptor method,
            List<EncodedValue> arguments, InvocationOptions options, AsyncMethodDescriptor result) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                org.j7m.middleware.api.MiddlewareException.of(org.j7m.middleware.api.MiddlewareErrorCode.UNAVAILABLE,
                        "Logical async transport is unsupported"));
    }

    /**
     * Creates a Java proxy for a target reference.
     *
     * @param ref target reference
     * @param api Java interface
     * @param options proxy options
     * @param <T> interface type
     * @return proxy implementing {@code api}
     */
    <T> T createProxy(ObjectRef ref, Class<T> api, ProxyOptions options);

    /**
     * Closes provider-owned resources.
     */
    @Override
    void close();
}
