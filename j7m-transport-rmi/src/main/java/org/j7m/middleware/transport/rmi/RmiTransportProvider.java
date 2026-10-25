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

import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.URI;
import java.rmi.AccessException;
import java.rmi.AlreadyBoundException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ProxyOptions;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.security.api.SecurityContexts;
import org.j7m.middleware.transport.api.ExportDescriptor;
import org.j7m.middleware.transport.api.ExportedObject;
import org.j7m.middleware.transport.api.MethodDescriptor;
import org.j7m.middleware.transport.api.RemoteProxyInvocationHandler;
import org.j7m.middleware.transport.api.RuntimeTransportContext;
import org.j7m.middleware.transport.api.StreamingTransportProvider;
import org.j7m.middleware.transport.api.TransportEndpoint;
import org.j7m.middleware.transport.rmi.internal.RmiRemoteObjectService;
import org.j7m.middleware.transport.rmi.internal.RmiSerializationGuard;
import org.j7m.middleware.workload.api.StreamHandle;

/**
 * Native Java RMI implementation of the Phase 1 generic remote-object
 * transport.
 */
public final class RmiTransportProvider implements StreamingTransportProvider {
    private static final int MAX_STREAM_ITEMS = 4096;
    private static final int MAX_ENDPOINT_CACHE = 256;
    private final ConcurrentMap<String, ExportDescriptor> exports;
    private final ConcurrentMap<String, RmiRemoteObjectService> clients;
    private final AtomicLong streamCount;
    private final AtomicLong streamFailureCount;
    private volatile RuntimeTransportContext context;
    private volatile Registry registry;
    private volatile RmiRemoteObjectServiceImpl service;
    private volatile String bindingName;
    private volatile TransportEndpoint endpoint;
    private volatile boolean running;

    /**
     * Creates an RMI transport provider.
     */
    public RmiTransportProvider() {
        this.exports = new ConcurrentHashMap<>();
        this.clients = new ConcurrentHashMap<>();
        this.streamCount = new AtomicLong();
        this.streamFailureCount = new AtomicLong();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String id() {
        return "rmi";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TransportEndpoint start(final RuntimeTransportContext newContext) {
        if (newContext == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "transport context is required");
        }
        try {
            RmiSerializationGuard.installOrValidate();
            int port = freePort();
            Registry newRegistry = LocateRegistry.createRegistry(port);
            this.context = newContext;
            this.service = new RmiRemoteObjectServiceImpl();
            this.bindingName = "j7m-" + newContext.identity().runtimeId();
            newRegistry.bind(this.bindingName, this.service);
            this.registry = newRegistry;
            this.endpoint = new TransportEndpoint(this.id(), "rmi://127.0.0.1:" + port + "/" + this.bindingName);
            this.running = true;
            return this.endpoint;
        } catch (AlreadyBoundException | RemoteException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.TRANSPORT_ERROR, "failed to start rmi transport",
                    Map.of(), null, null, true, true), exception);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExportedObject export(final ObjectRef ref, final Object target, final ExportDescriptor descriptor) {
        this.ensureRunning();
        this.exports.put(ref.objectId(), descriptor);
        return new ExportedObject(ref, descriptor);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unexport(final ObjectRef ref) {
        if (ref != null) {
            this.exports.remove(ref.objectId());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object invoke(final ObjectRef target, final MethodDescriptor method, final List<EncodedValue> arguments, final InvocationOptions options) {
        return invoke(target, method, arguments, options, this.classFor(method.returnTypeName()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object invoke(final ObjectRef target, final MethodDescriptor method, final List<EncodedValue> arguments, final InvocationOptions options, final Class<?> returnType) {
        if (returnType == null || !returnType.getName().equals(method.returnTypeName())) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "Return contract mismatch");
        }
        this.ensureRunning();
        InvocationOptions safeOptions = options == null ? InvocationOptions.defaults() : options;
        List<EncodedValue> safeArguments = arguments == null ? List.of() : arguments;
        if (this.isLocalTarget(target)) {
            EncodedValue encodedValue = this.context.dispatchTrusted(target, method, safeArguments, safeOptions, SecurityContexts.current());
            return this.context.decodeValue(encodedValue, returnType);
        }
        try {
            RmiWire.InvokeRequest request = new RmiWire.InvokeRequest(target, method, safeArguments,
                    safeOptions);
            RmiWire.InvokeResponse response = RmiWireCodec.decodeInvokeResponse(this.client(target.endpoint())
                    .invoke(RmiWireCodec.encodeInvokeRequest(request)));
            if (!response.success()) {
                throw new MiddlewareException(response.error());
            }
            return this.context.decodeValue(response.returnValue(), returnType);
        } catch (RemoteException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.TRANSPORT_ERROR, "rmi transport failure",
                    Map.of(), safeOptions.invocationId(), target.runtimeId(), true, true), exception);
        }
    }

    private boolean isLocalTarget(final ObjectRef target) {
        TransportEndpoint currentEndpoint = this.endpoint;
        RuntimeTransportContext currentContext = this.context;
        return target != null
                && currentEndpoint != null
                && currentContext != null
                && this.id().equals(target.transport())
                && currentContext.identity().runtimeId().equals(target.runtimeId())
                && currentEndpoint.endpoint().equals(target.endpoint());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T createProxy(final ObjectRef ref, final Class<T> api, final ProxyOptions options) {
        this.ensureRunning();
        if (api == null || !api.isInterface()) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "proxy api must be an interface");
        }
        if (!ref.interfaces().contains(api.getName())) {
            throw MiddlewareException.of(MiddlewareErrorCode.INTERFACE_MISMATCH, "reference does not expose requested interface");
        }
        this.open(ref, api.getName());
        RemoteProxyInvocationHandler handler = new RemoteProxyInvocationHandler(ref, api, options, this.context, this);
        Object proxy = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api}, handler);
        return api.cast(proxy);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StreamHandle<Object> serverStream(
            final ObjectRef target,
            final MethodDescriptor method,
            final List<EncodedValue> arguments,
            final InvocationOptions options,
            final Class<?> itemType) {
        this.ensureRunning();
        this.streamCount.incrementAndGet();
        InvocationOptions safeOptions = options == null ? InvocationOptions.defaults() : options;
        try {
            RmiWire.StreamRequest request = new RmiWire.StreamRequest(target, method, arguments == null ? List.of() : arguments,
                    safeOptions, true);
            List<RmiWire.StreamResponse> chunks = RmiWireCodec.decodeStreamResponses(this.client(target.endpoint())
                    .serverStream(RmiWireCodec.encodeStreamRequest(request)));
            return this.handleFromChunks(chunks, itemType == null ? Object.class : itemType);
        } catch (RemoteException exception) {
            this.streamFailureCount.incrementAndGet();
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.TRANSPORT_ERROR, "rmi stream failure",
                    Map.of(), safeOptions.invocationId(), target.runtimeId(), true, true), exception);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object clientStream(
            final ObjectRef target,
            final MethodDescriptor method,
            final List<EncodedValue> items,
            final InvocationOptions options) {
        this.ensureRunning();
        this.streamCount.incrementAndGet();
        InvocationOptions safeOptions = options == null ? InvocationOptions.defaults() : options;
        this.checkStreamListSize(items == null ? 0 : items.size(), safeOptions, target.runtimeId());
        List<RmiWire.StreamRequest> requests = new ArrayList<>();
        for (EncodedValue item : items == null ? List.<EncodedValue>of() : items) {
            requests.add(new RmiWire.StreamRequest(target, method, List.of(item), safeOptions, false));
        }
        try {
            RmiWire.StreamResponse response = RmiWireCodec.decodeStreamResponse(this.client(target.endpoint())
                    .clientStream(RmiWireCodec.encodeStreamRequests(List.copyOf(requests))));
            if (!response.success()) {
                throw new MiddlewareException(response.error());
            }
            return this.context.decodeValue(response.value(), this.classFor(method.returnTypeName()));
        } catch (RemoteException exception) {
            this.streamFailureCount.incrementAndGet();
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.TRANSPORT_ERROR, "rmi client stream failure",
                    Map.of(), safeOptions.invocationId(), target.runtimeId(), true, true), exception);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StreamHandle<Object> bidirectionalStream(
            final ObjectRef target,
            final MethodDescriptor method,
            final List<EncodedValue> items,
            final InvocationOptions options,
            final Class<?> itemType) {
        this.ensureRunning();
        this.streamCount.incrementAndGet();
        InvocationOptions safeOptions = options == null ? InvocationOptions.defaults() : options;
        this.checkStreamListSize(items == null ? 0 : items.size(), safeOptions, target.runtimeId());
        List<RmiWire.StreamRequest> requests = new ArrayList<>();
        for (EncodedValue item : items == null ? List.<EncodedValue>of() : items) {
            requests.add(new RmiWire.StreamRequest(target, method, List.of(item), safeOptions, false));
        }
        try {
            return this.handleFromChunks(RmiWireCodec.decodeStreamResponses(this.client(target.endpoint())
                            .bidirectionalStream(RmiWireCodec.encodeStreamRequests(List.copyOf(requests)))),
                    itemType == null ? Object.class : itemType);
        } catch (RemoteException exception) {
            this.streamFailureCount.incrementAndGet();
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.TRANSPORT_ERROR, "rmi bidi stream failure",
                    Map.of(), safeOptions.invocationId(), target.runtimeId(), true, true), exception);
        }
    }

    /**
     * Returns the legacy stream call count.
     *
     * @return legacy stream call count
     */
    public long streamCount() {
        return this.streamCount.get();
    }

    /**
     * Returns the legacy stream failure count.
     *
     * @return legacy stream failure count
     */
    public long streamFailureCount() {
        return this.streamFailureCount.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        this.running = false;
        this.exports.clear();
        this.clients.clear();
        Registry currentRegistry = this.registry;
        String currentBinding = this.bindingName;
        try {
            if (currentRegistry != null && currentBinding != null) {
                currentRegistry.unbind(currentBinding);
            }
        } catch (AccessException | NotBoundException ignored) {
            // Close is idempotent.
        } catch (RemoteException ignored) {
            // Close is idempotent.
        }
        RmiRemoteObjectServiceImpl currentService = this.service;
        if (currentService != null) {
            try {
                UnicastRemoteObject.unexportObject(currentService, true);
            } catch (java.rmi.NoSuchObjectException ignored) {
                // Close is idempotent.
            }
        }
        if (currentRegistry != null) {
            try {
                UnicastRemoteObject.unexportObject(currentRegistry, true);
            } catch (java.rmi.NoSuchObjectException ignored) {
                // Close is idempotent.
            }
        }
    }

    private StreamHandle<Object> handleFromChunks(final List<RmiWire.StreamResponse> chunks, final Class<?> itemType) {
        if (chunks.size() > MAX_STREAM_ITEMS) {
            throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "rmi stream item count exceeds limit");
        }
        ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(Math.max(1, Math.min(4096, chunks.size() + 1)));
        AtomicBoolean closedFlag = new AtomicBoolean();
        AtomicBoolean cancelledFlag = new AtomicBoolean();
        for (RmiWire.StreamResponse chunk : chunks) {
            if (!chunk.success()) {
                queue.offer(new MiddlewareException(chunk.error()));
                closedFlag.set(true);
                break;
            }
            if (chunk.complete()) {
                closedFlag.set(true);
                break;
            }
            queue.offer(this.context.decodeValue(chunk.value(), itemType));
        }
        closedFlag.set(true);
        return new RmiChunkStreamHandle(queue, closedFlag, cancelledFlag);
    }

    RmiWire.DescribeResponse describe(final ObjectRef ref) {
        try {
            RmiWire.DescribeResponse response = RmiWireCodec.decodeDescribeResponse(this.client(ref.endpoint())
                    .describe(RmiWireCodec.encodeDescribeRequest(new RmiWire.DescribeRequest(ref))));
            if (!response.success()) {
                throw new MiddlewareException(response.error());
            }
            return response;
        } catch (RemoteException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.TRANSPORT_ERROR, "rmi describe failed",
                    Map.of(), null, ref.runtimeId(), true, true), exception);
        }
    }

    private void open(final ObjectRef ref, final String interfaceName) {
        try {
            RmiWire.OpenResponse response = RmiWireCodec.decodeOpenResponse(this.client(ref.endpoint())
                    .open(RmiWireCodec.encodeOpenRequest(new RmiWire.OpenRequest(ref, interfaceName))));
            if (!response.success()) {
                throw new MiddlewareException(response.error());
            }
        } catch (RemoteException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.TRANSPORT_ERROR, "rmi open failed",
                    Map.of(), null, ref.runtimeId(), true, true), exception);
        }
    }

    private RmiRemoteObjectService client(final String endpointValue) {
        this.evictClientIfNeeded();
        return this.clients.computeIfAbsent(endpointValue, ignored -> {
            try {
                URI uri = URI.create(endpointValue);
                Registry remoteRegistry = LocateRegistry.getRegistry(uri.getHost(), uri.getPort());
                String name = uri.getPath().startsWith("/") ? uri.getPath().substring(1) : uri.getPath();
                return (RmiRemoteObjectService) remoteRegistry.lookup(name);
            } catch (NotBoundException | RemoteException exception) {
                throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.UNAVAILABLE, "rmi endpoint is unavailable",
                        Map.of(), null, null, true, true), exception);
            }
        });
    }

    private void evictClientIfNeeded() {
        if (this.clients.size() < MAX_ENDPOINT_CACHE) {
            return;
        }
        List<String> keys = new ArrayList<>(this.clients.keySet());
        keys.sort(String::compareTo);
        int removeCount = Math.max(1, MAX_ENDPOINT_CACHE / 16);
        for (String key : keys) {
            this.clients.remove(key);
            removeCount--;
            if (removeCount == 0) {
                return;
            }
        }
    }

    private void checkStreamListSize(final int itemCount, final InvocationOptions options, final String runtimeId) {
        if (itemCount > MAX_STREAM_ITEMS) {
            this.streamFailureCount.incrementAndGet();
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.OVERLOADED, "rmi stream item count exceeds limit",
                    Map.of("limit", Integer.toString(MAX_STREAM_ITEMS)), options.invocationId(), runtimeId, true, true));
        }
    }

    private void ensureRunning() {
        if (!this.running || this.context == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "rmi transport is not running");
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
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.CODEC_ERROR, "return type is not available",
                    Map.of(), null, null, false, true), exception);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (java.io.IOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.TRANSPORT_ERROR, "failed to allocate rmi port",
                    Map.of(), null, null, true, true), exception);
        }
    }

    private final class RmiRemoteObjectServiceImpl extends UnicastRemoteObject implements RmiRemoteObjectService {
        private RmiRemoteObjectServiceImpl() throws RemoteException {
            super(0);
        }

        @Override
        public byte[] invoke(final byte[] requestBytes) {
            RmiWire.InvokeRequest request = RmiWireCodec.decodeInvokeRequest(requestBytes);
            try {
                EncodedValue encodedValue = context.dispatch(request.target(), request.method(), request.arguments(), request.options());
                return RmiWireCodec.encodeInvokeResponse(new RmiWire.InvokeResponse(true, encodedValue, null));
            } catch (MiddlewareException exception) {
                return RmiWireCodec.encodeInvokeResponse(new RmiWire.InvokeResponse(false, null, exception.remoteError()));
            } catch (RuntimeException exception) {
                return RmiWireCodec.encodeInvokeResponse(new RmiWire.InvokeResponse(false, null, new RemoteError(MiddlewareErrorCode.INTERNAL,
                        "rmi invocation dispatch failed", Map.of(), request.options().invocationId(), context.identity().runtimeId(), false, true)));
            }
        }

        @Override
        public byte[] open(final byte[] requestBytes) {
            RmiWire.OpenRequest request = RmiWireCodec.decodeOpenRequest(requestBytes);
            try {
                context.validateTarget(request.target(), request.interfaceName());
            } catch (MiddlewareException exception) {
                return RmiWireCodec.encodeOpenResponse(new RmiWire.OpenResponse(false, exception.remoteError()));
            }
            return RmiWireCodec.encodeOpenResponse(new RmiWire.OpenResponse(true, null));
        }

        @Override
        public byte[] describe(final byte[] requestBytes) {
            RmiWire.DescribeRequest request = RmiWireCodec.decodeDescribeRequest(requestBytes);
            try {
                return RmiWireCodec.encodeDescribeResponse(new RmiWire.DescribeResponse(true, context.describeTarget(request.target()), null));
            } catch (MiddlewareException exception) {
                return RmiWireCodec.encodeDescribeResponse(new RmiWire.DescribeResponse(false, List.of(), exception.remoteError()));
            }
        }

        @Override
        public byte[] serverStream(final byte[] requestBytes) {
            RmiWire.StreamRequest request = RmiWireCodec.decodeStreamRequest(requestBytes);
            try {
                EncodedValue encodedValue = context.dispatch(request.target(), request.method(), request.items(), request.options());
                List<Object> items = streamItems(encodedValue);
                checkStreamListSize(items.size(), request.options(), request.target().runtimeId());
                List<RmiWire.StreamResponse> responses = new ArrayList<>(items.size() + 1);
                long sequence = 0L;
                for (Object item : items) {
                    responses.add(new RmiWire.StreamResponse(true, encodedStreamItem(item), null, false, sequence++));
                }
                responses.add(new RmiWire.StreamResponse(true, null, null, true, sequence));
                return RmiWireCodec.encodeStreamResponses(List.copyOf(responses));
            } catch (MiddlewareException exception) {
                streamFailureCount.incrementAndGet();
                return RmiWireCodec.encodeStreamResponses(List.of(new RmiWire.StreamResponse(false, null, exception.remoteError(), true, 0L)));
            } catch (RuntimeException exception) {
                streamFailureCount.incrementAndGet();
                return RmiWireCodec.encodeStreamResponses(List.of(new RmiWire.StreamResponse(false, null, new RemoteError(MiddlewareErrorCode.INTERNAL,
                        "rmi stream dispatch failed", Map.of(), request.options().invocationId(), context.identity().runtimeId(), false, true), true, 0L)));
            }
        }

        @Override
        public byte[] clientStream(final byte[] requestBytes) {
            List<RmiWire.StreamRequest> requests = RmiWireCodec.decodeStreamRequests(requestBytes);
            if (requests == null || requests.isEmpty()) {
                return RmiWireCodec.encodeStreamResponse(new RmiWire.StreamResponse(false, null, new RemoteError(MiddlewareErrorCode.INVALID_ARGUMENT,
                        "client stream contained no items", Map.of(), null, context.identity().runtimeId(), false, false), true, 0L));
            }
            RmiWire.StreamRequest first = requests.getFirst();
            try {
                checkStreamListSize(requests.size(), first.options(), first.target().runtimeId());
                List<Object> values = new ArrayList<>(requests.size());
                for (RmiWire.StreamRequest request : requests) {
                    for (EncodedValue item : request.items()) {
                        values.add(context.decodeValue(item, Object.class));
                    }
                }
                EncodedValue result = context.dispatch(first.target(), first.method(),
                        List.of(new EncodedValue("list", List.class.getName(), List.copyOf(values), Map.of())), first.options());
                return RmiWireCodec.encodeStreamResponse(new RmiWire.StreamResponse(true, result, null, true, 0L));
            } catch (MiddlewareException exception) {
                streamFailureCount.incrementAndGet();
                return RmiWireCodec.encodeStreamResponse(new RmiWire.StreamResponse(false, null, exception.remoteError(), true, 0L));
            }
        }

        @Override
        public byte[] bidirectionalStream(final byte[] requestBytes) {
            List<RmiWire.StreamRequest> requests = RmiWireCodec.decodeStreamRequests(requestBytes);
            if (requests == null || requests.isEmpty()) {
                return RmiWireCodec.encodeStreamResponses(List.of(new RmiWire.StreamResponse(true, null, null, true, 0L)));
            }
            checkStreamListSize(requests.size(), requests.getFirst().options(), requests.getFirst().target().runtimeId());
            List<RmiWire.StreamResponse> responses = new ArrayList<>(requests.size() + 1);
            long sequence = 0L;
            for (RmiWire.StreamRequest request : requests) {
                try {
                    EncodedValue argument = request.items().isEmpty()
                            ? new EncodedValue("null", Object.class.getName(), null, Map.of())
                            : request.items().getFirst();
                    EncodedValue result = context.dispatch(request.target(), request.method(), List.of(argument), request.options());
                    responses.add(new RmiWire.StreamResponse(true, result, null, false, sequence++));
                } catch (MiddlewareException exception) {
                    streamFailureCount.incrementAndGet();
                    responses.add(new RmiWire.StreamResponse(false, null, exception.remoteError(), true, sequence));
                    return RmiWireCodec.encodeStreamResponses(List.copyOf(responses));
                }
            }
            responses.add(new RmiWire.StreamResponse(true, null, null, true, sequence));
            return RmiWireCodec.encodeStreamResponses(List.copyOf(responses));
        }
    }

    private EncodedValue encodedStreamItem(final Object item) {
        if (item instanceof EncodedValue encodedValue) {
            return encodedValue;
        }
        return this.context.encodeValue(item, item == null ? Object.class : item.getClass());
    }

    private static List<Object> streamItems(final EncodedValue encodedValue) {
        Object raw = encodedValue == null ? null : encodedValue.value();
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            if (list.size() > MAX_STREAM_ITEMS) {
                throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "rmi stream item count exceeds limit");
            }
            return List.copyOf(list);
        }
        if (raw instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            for (Object item : iterable) {
                if (values.size() >= MAX_STREAM_ITEMS) {
                    throw MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "rmi stream item count exceeds limit");
                }
                values.add(item);
            }
            return List.copyOf(values);
        }
        return List.of(raw);
    }

    private static final class RmiChunkStreamHandle implements StreamHandle<Object> {
        private final ArrayBlockingQueue<Object> queue;
        private final AtomicBoolean closedFlag;
        private final AtomicBoolean cancelledFlag;

        private RmiChunkStreamHandle(final ArrayBlockingQueue<Object> queue, final AtomicBoolean closedFlag, final AtomicBoolean cancelledFlag) {
            this.queue = queue;
            this.closedFlag = closedFlag;
            this.cancelledFlag = cancelledFlag;
        }

        @Override
        public Optional<Object> poll(final Duration timeout) {
            if (this.queue.isEmpty()) {
                return Optional.empty();
            }
            try {
                Object value = this.queue.poll(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
                if (value instanceof MiddlewareException middlewareException) {
                    throw middlewareException;
                }
                return Optional.ofNullable(value);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "rmi stream poll interrupted");
            }
        }

        @Override
        public void cancel() {
            this.cancelledFlag.set(true);
            this.closedFlag.set(true);
            this.queue.clear();
        }

        @Override
        public boolean closed() {
            return this.closedFlag.get();
        }

        @Override
        public void close() {
            this.cancel();
        }
    }
}
