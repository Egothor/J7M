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

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ProxyOptions;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityContexts;

/**
 * Generic in-process remote-object transport implementation.
 *
 * <p>This base class implements the Phase 1 remote-object protocol: endpoints,
 * explicit {@link ObjectRef} handles, generic method dispatch, encoded
 * arguments, encoded return values, and Java interface proxies. It is
 * thread-safe after start. Concrete modules supply stable transport ids.</p>
 */
abstract class AbstractInProcessTransportProvider implements TransportProvider {
    private final String id;
    private final ConcurrentMap<String, ExportDescriptor> exports;
    private volatile RuntimeTransportContext context;
    private volatile TransportEndpoint endpoint;
    private volatile boolean running;

    /**
     * Creates a transport provider with the supplied id.
     *
     * @param id transport identifier
     */
    AbstractInProcessTransportProvider(final String id) {
        this.id = id;
        this.exports = new ConcurrentHashMap<>();
    }

    @Override
    public final String id() {
        return this.id;
    }

    @Override
    public TransportEndpoint start(final RuntimeTransportContext newContext) {
        if (newContext == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "transport context is required");
        }
        String address = this.id + "://in-process/" + newContext.identity().runtimeId() + "/" + Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
        this.context = newContext;
        this.endpoint = new TransportEndpoint(this.id, address);
        this.running = true;
        InProcessTransportNetwork.register(address, newContext);
        return this.endpoint;
    }

    @Override
    public ExportedObject export(final ObjectRef ref, final Object target, final ExportDescriptor descriptor) {
        this.ensureRunning();
        this.exports.put(ref.objectId(), descriptor);
        return new ExportedObject(ref, descriptor);
    }

    @Override
    public void unexport(final ObjectRef ref) {
        if (ref != null) {
            this.exports.remove(ref.objectId());
        }
    }

    @Override
    public Object invoke(final ObjectRef target, final MethodDescriptor method, final List<EncodedValue> arguments, final org.j7m.middleware.api.InvocationOptions options) {
        this.ensureRunning();
        RuntimeTransportContext remoteContext = InProcessTransportNetwork.find(target.endpoint())
                .orElseThrow(() -> MiddlewareException.remote(
                        MiddlewareErrorCode.UNAVAILABLE,
                        "transport endpoint is unavailable",
                        options == null ? null : options.invocationId(),
                        target.runtimeId(),
                        true,
                        true));
        SecurityContext trustedContext = SecurityContexts.current();
        EncodedValue encodedReturn = remoteContext.dispatchTrusted(target, method, arguments == null ? List.of() : List.copyOf(arguments),
                options == null ? org.j7m.middleware.api.InvocationOptions.defaults() : options, trustedContext);
        Class<?> returnType = this.classFor(method.returnTypeName());
        return this.context.decodeValue(encodedReturn, returnType);
    }

    @Override
    public <T> T createProxy(final ObjectRef ref, final Class<T> api, final ProxyOptions options) {
        this.ensureRunning();
        if (api == null || !api.isInterface()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "proxy api must be an interface");
        }
        if (!ref.interfaces().contains(api.getName())) {
            throw MiddlewareException.of(MiddlewareErrorCode.INTERFACE_MISMATCH, "reference does not expose requested interface");
        }
        RuntimeTransportContext remoteContext = InProcessTransportNetwork.find(ref.endpoint())
                .orElseThrow(() -> MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "transport endpoint is unavailable"));
        remoteContext.validateTarget(ref, api.getName());
        RemoteProxyInvocationHandler handler = new RemoteProxyInvocationHandler(ref, api, options, this.context, this);
        Object proxy = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api}, handler);
        return api.cast(proxy);
    }

    @Override
    public void close() {
        TransportEndpoint currentEndpoint = this.endpoint;
        this.running = false;
        this.exports.clear();
        if (currentEndpoint != null) {
            InProcessTransportNetwork.unregister(currentEndpoint.endpoint());
        }
    }

    /**
     * Returns a transport status endpoint.
     *
     * @return endpoint when started
     */
    protected final TransportEndpoint endpoint() {
        return this.endpoint;
    }

    private void ensureRunning() {
        if (!this.running || this.context == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "transport is not running");
        }
    }

    private Class<?> classFor(final String typeName) {
        try {
            return switch (typeName) {
                case "void" -> Void.TYPE;
                case "boolean" -> Boolean.TYPE;
                case "byte" -> Byte.TYPE;
                case "short" -> Short.TYPE;
                case "int" -> Integer.TYPE;
                case "long" -> Long.TYPE;
                case "float" -> Float.TYPE;
                case "double" -> Double.TYPE;
                case "char" -> Character.TYPE;
                default -> Class.forName(typeName);
            };
        } catch (ClassNotFoundException exception) {
            throw new MiddlewareException(
                    new org.j7m.middleware.api.RemoteError(MiddlewareErrorCode.CODEC_ERROR, "return type is not available", Map.of(), null, null, false, true),
                    exception);
        }
    }
}
