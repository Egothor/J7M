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
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.workload.api.StreamHandle;

/**
 * Optional transport extension for generic remote-object streaming.
 *
 * <p>Implementations must preserve the same target model as unary invocation:
 * every stream targets a concrete {@link ObjectRef} and identifies the
 * operation through {@link MethodDescriptor}. Implementations must map
 * transport failures to middleware exceptions, preserve explicit
 * {@code ObjectRef} values inside stream items, and enforce the same
 * authorization and dispatch checks as unary invocation by routing server-side
 * work through {@link RuntimeTransportContext#dispatch(ObjectRef, MethodDescriptor, List, InvocationOptions)}.</p>
 *
 * <p>This interface is transport-neutral and thread-safe. It is intentionally
 * optional so existing transports can expose capability-specific diagnostics
 * without changing {@link TransportProvider}.</p>
 */
public interface StreamingTransportProvider extends TransportProvider {
    /**
     * Opens a server-streaming invocation.
     *
     * @param target target object reference
     * @param method method descriptor
     * @param arguments encoded unary arguments for the stream-producing method
     * @param options invocation and stream metadata
     * @param itemType expected item type
     * @return stream handle
     */
    StreamHandle<Object> serverStream(
            ObjectRef target,
            MethodDescriptor method,
            List<EncodedValue> arguments,
            InvocationOptions options,
            Class<?> itemType);

    /**
     * Opens a client-streaming invocation and returns the aggregate result.
     *
     * @param target target object reference
     * @param method method descriptor
     * @param items encoded stream items
     * @param options invocation and stream metadata
     * @return decoded aggregate result
     */
    Object clientStream(ObjectRef target, MethodDescriptor method, List<EncodedValue> items, InvocationOptions options);

    /**
     * Opens a bidirectional stream.
     *
     * @param target target object reference
     * @param method method descriptor
     * @param items initial encoded outbound items
     * @param options invocation and stream metadata
     * @param itemType expected inbound item type
     * @return stream handle
     */
    StreamHandle<Object> bidirectionalStream(
            ObjectRef target,
            MethodDescriptor method,
            List<EncodedValue> items,
            InvocationOptions options,
            Class<?> itemType);
}
