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

package org.j7m.middleware.transport.rmi;

import java.util.List;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.transport.api.MethodDescriptor;

/**
 * Internal RMI message carriers used only before byte-envelope encoding.
 */
final class RmiWire {
    private RmiWire() {
    }

    /**
     * Invocation request.
     *
     * @param target target object reference
     * @param method method descriptor
     * @param arguments encoded invocation arguments
     * @param options invocation options
     */
    record InvokeRequest(ObjectRef target, MethodDescriptor method, List<EncodedValue> arguments, InvocationOptions options) {
        /**
         * Creates a request with immutable arguments.
         */
        public InvokeRequest {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
            options = options == null ? InvocationOptions.defaults() : options;
        }
    }

    /**
     * Invocation response.
     *
     * @param success whether invocation succeeded
     * @param returnValue encoded return value
     * @param error normalized remote error when unsuccessful
     */
    record InvokeResponse(boolean success, EncodedValue returnValue, RemoteError error) {
    }

    /**
     * Open request.
     *
     * @param target target object reference
     * @param interfaceName required interface name
     */
    record OpenRequest(ObjectRef target, String interfaceName) {
    }

    /**
     * Open response.
     *
     * @param success whether open validation succeeded
     * @param error normalized remote error when unsuccessful
     */
    record OpenResponse(boolean success, RemoteError error) {
    }

    /**
     * Describe request.
     *
     * @param target target object reference
     */
    record DescribeRequest(ObjectRef target) {
    }

    /**
     * Describe response.
     *
     * @param success whether describe succeeded
     * @param interfaces exposed interface names
     * @param error normalized remote error when unsuccessful
     */
    record DescribeResponse(boolean success, List<String> interfaces, RemoteError error) {
        /**
         * Creates a response with immutable interfaces.
         */
        public DescribeResponse {
            interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        }
    }

    /**
     * Streaming request.
     *
     * @param target target object reference
     * @param method method descriptor
     * @param items encoded stream items or server-stream arguments
     * @param options invocation options
     * @param complete whether the sender is complete
     */
    record StreamRequest(ObjectRef target, MethodDescriptor method, List<EncodedValue> items, InvocationOptions options, boolean complete) {
        /**
         * Creates a request with immutable items.
         */
        public StreamRequest {
            items = items == null ? List.of() : List.copyOf(items);
            options = options == null ? InvocationOptions.defaults() : options;
        }
    }

    /**
     * Streaming response chunk.
     *
     * @param success whether the chunk succeeded
     * @param value encoded item or aggregate value
     * @param error normalized error
     * @param complete whether the stream is complete
     * @param sequence item sequence
     */
    record StreamResponse(boolean success, EncodedValue value, RemoteError error, boolean complete, long sequence) {
    }
}
