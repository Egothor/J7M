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

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Finite logical result shape for an asynchronous remote method.
 * Raw, wildcard, type-variable, nested-stage and custom-future declarations are unsupported.
 * @param payloadType concrete result class encoded on the wire instead of the future object
 */
public record AsyncMethodDescriptor(Class<?> payloadType) {
    /**
     * Validates a concrete logical payload class.
     */
    public AsyncMethodDescriptor {
        java.util.Objects.requireNonNull(payloadType, "payloadType");
        if (CompletionStage.class.isAssignableFrom(payloadType) || java.util.concurrent.Future.class.isAssignableFrom(payloadType)) throw new IllegalArgumentException("nested asynchronous result");
    }
    /**
     * Resolves a supported parameterized CompletionStage or CompletableFuture declaration.
     * @param method exact interface method whose generic signature is preserved
     * @return validated logical payload shape
     * @throws IllegalArgumentException for an unsupported asynchronous declaration
     */
    public static AsyncMethodDescriptor fromMethod(Method method) {
        if ((method.getReturnType() != CompletionStage.class && method.getReturnType() != CompletableFuture.class)
                || !(method.getGenericReturnType() instanceof ParameterizedType type)
                || type.getActualTypeArguments().length != 1 || !(type.getActualTypeArguments()[0] instanceof Class<?> payload)) {
            throw new IllegalArgumentException("asynchronous result requires a concrete CompletionStage<T> or CompletableFuture<T>");
        }
        return new AsyncMethodDescriptor(payload);
    }
}
