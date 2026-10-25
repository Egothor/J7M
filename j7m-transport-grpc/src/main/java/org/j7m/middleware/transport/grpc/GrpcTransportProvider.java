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

package org.j7m.middleware.transport.grpc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import org.j7m.middleware.transport.api.AsyncMethodDescriptor;
import io.grpc.CallOptions;
import io.grpc.ChannelCredentials;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Channel;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerCallHandler;
import io.grpc.ServerCredentials;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ProxyOptions;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityContexts;
import org.j7m.middleware.security.api.TransportSecurityLevel;
import org.j7m.middleware.security.api.TrustDomain;
import org.j7m.middleware.transport.api.ExportDescriptor;
import org.j7m.middleware.transport.api.ExportedObject;
import org.j7m.middleware.transport.api.MethodDescriptor;
import org.j7m.middleware.transport.api.RemoteProxyInvocationHandler;
import org.j7m.middleware.transport.api.RuntimeTransportContext;
import org.j7m.middleware.transport.api.StreamingTransportProvider;
import org.j7m.middleware.transport.api.TransportEndpoint;
import org.j7m.middleware.transport.api.TransportProvider;
import org.j7m.middleware.workload.api.StreamHandle;

/**
 * Real gRPC transport provider for the Phase 1 generic remote-object protocol.
 *
 * <p>The provider starts a loopback HTTP/2 gRPC server, registers generic
 * {@code Invoke}, {@code Open}, and {@code Describe} unary methods, and creates
 * dynamic Java proxies that invoke those generic methods. It does not expose
 * application-specific static gRPC services.</p>
 */
public final class GrpcTransportProvider implements StreamingTransportProvider {
    private static final String SERVICE_NAME = "org.j7m.middleware.transport.grpc.RemoteObjectService";
    private static final String TYPE = "application/protobuf";
    private static final Context.Key<SecurityContext> PEER_SECURITY_CONTEXT = Context.key("j7m-grpc-peer-security");
    private static final int MAX_ENDPOINT_CACHE = 256;
    static final int MAX_STREAM_ITEMS = 4096;
    private static final int DEFAULT_MAX_CONCURRENT_STREAMS = 64;
    private static final int MAX_GRPC_MESSAGE_BYTES = 1024 * 1024;

    private static final io.grpc.MethodDescriptor<GrpcWire.InvokeRequest, GrpcWire.InvokeResponse> INVOKE_METHOD =
            io.grpc.MethodDescriptor.<GrpcWire.InvokeRequest, GrpcWire.InvokeResponse>newBuilder()
                    .setType(MethodType.UNARY)
                    .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(SERVICE_NAME, "Invoke"))
                    .setRequestMarshaller(new Marshaller<>(GrpcWireCodec::encodeInvokeRequest, GrpcWireCodec::decodeInvokeRequest))
                    .setResponseMarshaller(new Marshaller<>(GrpcWireCodec::encodeInvokeResponse, GrpcWireCodec::decodeInvokeResponse))
                    .build();

    // A distinct method fails before dispatch on old peers; future objects never enter the legacy codec.
    private static final io.grpc.MethodDescriptor<GrpcWire.InvokeRequest, GrpcWire.InvokeResponse> ASYNC_METHOD =
            INVOKE_METHOD.toBuilder().setFullMethodName(
                    io.grpc.MethodDescriptor.generateFullMethodName(SERVICE_NAME, "InvokeAsyncV2")).build();

    private final ThreadPoolExecutor asyncWorkers = new ThreadPoolExecutor(16, 16, 1L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), work -> {
                Thread thread = Thread.ofPlatform().daemon(true).inheritInheritableThreadLocals(false)
                        .name("j7m-grpc-logical-async").unstarted(work);
                thread.setContextClassLoader(GrpcTransportProvider.class.getClassLoader());
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private static final io.grpc.MethodDescriptor<GrpcWire.OpenRequest, GrpcWire.OpenResponse> OPEN_METHOD =
            io.grpc.MethodDescriptor.<GrpcWire.OpenRequest, GrpcWire.OpenResponse>newBuilder()
                    .setType(MethodType.UNARY)
                    .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(SERVICE_NAME, "Open"))
                    .setRequestMarshaller(new Marshaller<>(GrpcWireCodec::encodeOpenRequest, GrpcWireCodec::decodeOpenRequest))
                    .setResponseMarshaller(new Marshaller<>(GrpcWireCodec::encodeOpenResponse, GrpcWireCodec::decodeOpenResponse))
                    .build();

    private static final io.grpc.MethodDescriptor<GrpcWire.DescribeRequest, GrpcWire.DescribeResponse> DESCRIBE_METHOD =
            io.grpc.MethodDescriptor.<GrpcWire.DescribeRequest, GrpcWire.DescribeResponse>newBuilder()
                    .setType(MethodType.UNARY)
                    .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(SERVICE_NAME, "Describe"))
                    .setRequestMarshaller(new Marshaller<>(GrpcWireCodec::encodeDescribeRequest, GrpcWireCodec::decodeDescribeRequest))
                    .setResponseMarshaller(new Marshaller<>(GrpcWireCodec::encodeDescribeResponse, GrpcWireCodec::decodeDescribeResponse))
                    .build();

    private static final io.grpc.MethodDescriptor<GrpcWire.StreamRequest, GrpcWire.StreamResponse> SERVER_STREAM_METHOD =
            io.grpc.MethodDescriptor.<GrpcWire.StreamRequest, GrpcWire.StreamResponse>newBuilder()
                    .setType(MethodType.SERVER_STREAMING)
                    .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(SERVICE_NAME, "ServerStream"))
                    .setRequestMarshaller(new Marshaller<>(GrpcWireCodec::encodeStreamRequest, GrpcWireCodec::decodeStreamRequest))
                    .setResponseMarshaller(new Marshaller<>(GrpcWireCodec::encodeStreamResponse, GrpcWireCodec::decodeStreamResponse))
                    .build();

    private static final io.grpc.MethodDescriptor<GrpcWire.StreamRequest, GrpcWire.StreamResponse> CLIENT_STREAM_METHOD =
            io.grpc.MethodDescriptor.<GrpcWire.StreamRequest, GrpcWire.StreamResponse>newBuilder()
                    .setType(MethodType.CLIENT_STREAMING)
                    .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(SERVICE_NAME, "ClientStream"))
                    .setRequestMarshaller(new Marshaller<>(GrpcWireCodec::encodeStreamRequest, GrpcWireCodec::decodeStreamRequest))
                    .setResponseMarshaller(new Marshaller<>(GrpcWireCodec::encodeStreamResponse, GrpcWireCodec::decodeStreamResponse))
                    .build();

    private static final io.grpc.MethodDescriptor<GrpcWire.StreamRequest, GrpcWire.StreamResponse> BIDI_STREAM_METHOD =
            io.grpc.MethodDescriptor.<GrpcWire.StreamRequest, GrpcWire.StreamResponse>newBuilder()
                    .setType(MethodType.BIDI_STREAMING)
                    .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(SERVICE_NAME, "BidiStream"))
                    .setRequestMarshaller(new Marshaller<>(GrpcWireCodec::encodeStreamRequest, GrpcWireCodec::decodeStreamRequest))
                    .setResponseMarshaller(new Marshaller<>(GrpcWireCodec::encodeStreamResponse, GrpcWireCodec::decodeStreamResponse))
                    .build();

    private final GrpcSecurityOptions securityOptions;
    private final ConcurrentMap<String, ExportDescriptor> exports;
    private final ConcurrentMap<String, ManagedChannel> channels;
    private final AtomicLong credentialRefreshCount;
    private final AtomicLong serverStreamCount;
    private final AtomicLong clientStreamCount;
    private final AtomicLong bidiStreamCount;
    private final AtomicLong streamFailureCount;
    private final Semaphore streamPermits;
    private final Object channelCacheLock;
    private volatile RuntimeTransportContext context;
    private volatile Server server;
    private volatile TransportEndpoint endpoint;
    private volatile boolean running;

    /**
     * Creates a gRPC transport provider.
     */
    public GrpcTransportProvider() {
        this(GrpcSecurityOptions.development());
    }

    /**
     * Creates a gRPC transport provider with explicit security options.
     *
     * @param securityOptions security options
     */
    public GrpcTransportProvider(final GrpcSecurityOptions securityOptions) {
        this.securityOptions = securityOptions == null ? GrpcSecurityOptions.development() : securityOptions;
        this.exports = new ConcurrentHashMap<>();
        this.channels = new ConcurrentHashMap<>();
        this.credentialRefreshCount = new AtomicLong();
        this.serverStreamCount = new AtomicLong();
        this.clientStreamCount = new AtomicLong();
        this.bidiStreamCount = new AtomicLong();
        this.streamFailureCount = new AtomicLong();
        this.streamPermits = new Semaphore(DEFAULT_MAX_CONCURRENT_STREAMS);
        this.channelCacheLock = new Object();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String id() {
        return "grpc";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TransportEndpoint start(final RuntimeTransportContext newContext) {
        if (newContext == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "transport context is required");
        }
        if (this.securityOptions.productionMode() && !this.securityOptions.tlsEnabled() && !this.securityOptions.allowInsecureProductionTransport()) {
            throw MiddlewareException.of(MiddlewareErrorCode.TRANSPORT_ERROR, "insecure grpc transport is rejected in production mode");
        }
        this.asyncWorkers.allowCoreThreadTimeOut(true);
        try {
            io.grpc.ServerServiceDefinition service = io.grpc.ServerServiceDefinition.builder(SERVICE_NAME)
                            .addMethod(INVOKE_METHOD, unary(this::handleInvoke))
                            .addMethod(ASYNC_METHOD, this.logicalAsyncHandler())
                            .addMethod(OPEN_METHOD, unary(this::handleOpen))
                            .addMethod(DESCRIBE_METHOD, unary(this::handleDescribe))
                            .addMethod(SERVER_STREAM_METHOD, serverStreaming(this::handleServerStream))
                            .addMethod(CLIENT_STREAM_METHOD, clientStreaming(this::handleClientStream))
                            .addMethod(BIDI_STREAM_METHOD, bidiStreaming(this::handleBidiStreamItem))
                            .build();
            if (this.securityOptions.tlsEnabled()) {
                service = ServerInterceptors.intercept(service, new PeerSecurityInterceptor(this.securityOptions.tlsConfiguration()));
            }
            Server grpcServer = Grpc.newServerBuilderForPort(0, this.serverCredentials())
                    .maxInboundMessageSize(MAX_GRPC_MESSAGE_BYTES)
                    .addService(service)
                    .build()
                    .start();
            this.context = newContext;
            this.server = grpcServer;
            this.running = true;
            this.endpoint = new TransportEndpoint(this.id(), "grpc://127.0.0.1:" + grpcServer.getPort());
            return this.endpoint;
        } catch (Exception exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.TRANSPORT_ERROR, "failed to start grpc transport",
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
    public CompletableFuture<Object> invokeAsync(final ObjectRef target, final MethodDescriptor method,
            final List<EncodedValue> arguments, final InvocationOptions options, final AsyncMethodDescriptor result) {
        this.ensureRunning();
        InvocationOptions safe = options == null ? InvocationOptions.defaults() : options;
        CompletableFuture<Object> reply = new CompletableFuture<>();
        if (safe.deadline().isNegative() || safe.deadline().isZero()) {
            reply.completeExceptionally(MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "Async budget expired"));
            return reply;
        }
        if (this.isLocalTarget(target)) return this.invokeLocalAsync(target, method, arguments, safe, result, reply);
        var call = this.channel(target.endpoint()).newCall(ASYNC_METHOD,
                CallOptions.DEFAULT.withDeadlineAfter(safe.deadline().toNanos(), TimeUnit.NANOSECONDS));
        var remote = ClientCalls.futureUnaryCall(call,
                new GrpcWire.InvokeRequest(target, method, List.copyOf(arguments), safe));
        reply.whenComplete((value, failure) -> {
            if (reply.isCancelled()) {
                // Cancelling a Guava future without interruption does not cancel its gRPC call.
                // Signal the wire call explicitly; this never interrupts an application worker.
                call.cancel("Caller cancelled logical reply", null);
                remote.cancel(false);
            }
        });
        remote.addListener(() -> {
            try {
                this.asyncWorkers.execute(() -> {
                    try {
                        GrpcWire.InvokeResponse response = remote.get();
                        if (!response.success()) throw new MiddlewareException(response.error());
                        reply.complete(this.context.decodeValue(response.returnValue(), result.payloadType()));
                    } catch (java.util.concurrent.ExecutionException failure) {
                        Throwable cause = failure.getCause();
                        reply.completeExceptionally(cause instanceof StatusRuntimeException status
                                ? this.mapStatus(status, safe, target.runtimeId()) : cause);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); reply.completeExceptionally(interrupted);
                    } catch (RuntimeException failure) { reply.completeExceptionally(failure); }
                });
            } catch (RejectedExecutionException rejected) {
                reply.completeExceptionally(MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "Async decode capacity exhausted"));
            }
        }, Runnable::run);
        return reply;
    }

    private CompletableFuture<Object> invokeLocalAsync(final ObjectRef target, final MethodDescriptor method,
            final List<EncodedValue> arguments, final InvocationOptions options, final AsyncMethodDescriptor result,
            final CompletableFuture<Object> reply) {
        final SecurityContext caller = SecurityContexts.current();
        final Map<String, String> trace = org.j7m.middleware.application.api.InvocationTraceContext.current();
        final List<EncodedValue> captured = List.copyOf(arguments);
        final long admittedAt = System.nanoTime();
        final long budget = options.deadline().compareTo(Duration.ofDays(1)) > 0
                ? Duration.ofDays(1).toNanos() : options.deadline().toNanos();
        final LocalCancellation cancellation = new LocalCancellation();
        reply.whenComplete((value, failure) -> { if (reply.isCancelled()) cancellation.cancel(); });
        try {
            this.asyncWorkers.execute(() -> {
                if (reply.isCancelled()) return;
                long remaining = budget - Math.max(0L, System.nanoTime() - admittedAt);
                if (remaining <= 0) {
                    reply.completeExceptionally(MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "Local async queue budget expired"));
                    return;
                }
                try (var traceScope = org.j7m.middleware.application.api.InvocationTraceContext.open(trace)) {
                    Object value = SecurityContexts.call(caller, () -> {
                        EncodedValue encoded = this.context.dispatchLogicalAsyncTrusted(target, method, captured,
                                new InvocationOptions(options.invocationId(), Duration.ofNanos(remaining), options.metadata()), caller, cancellation);
                        return this.context.decodeValue(encoded, result.payloadType());
                    });
                    reply.complete(value);
                } catch (RuntimeException failure) { reply.completeExceptionally(failure); }
            });
        } catch (RejectedExecutionException rejected) {
            reply.completeExceptionally(MiddlewareException.of(MiddlewareErrorCode.OVERLOADED, "Local async capacity exhausted"));
        }
        return reply;
    }

    private static final class LocalCancellation implements org.j7m.middleware.transport.api.InvocationCancellation {
        private boolean cancelled;
        private Runnable listener;
        @Override public AutoCloseable listen(final Runnable callback) {
            java.util.Objects.requireNonNull(callback);
            final boolean notify;
            synchronized (this) {
                if (listener != null) throw new IllegalStateException("Cancellation listener already registered");
                listener = callback; notify = cancelled;
            }
            if (notify) callback.run();
            return () -> { synchronized (this) { if (listener == callback) listener = null; } };
        }
        void cancel() {
            final Runnable notify;
            synchronized (this) { if (cancelled) return; cancelled = true; notify = listener; }
            if (notify != null) notify.run();
        }
    }

    private ServerCallHandler<GrpcWire.InvokeRequest, GrpcWire.InvokeResponse> logicalAsyncHandler() {
        return ServerCalls.asyncUnaryCall((request, observer) -> {
            Context incoming = Context.current();
            try {
                this.asyncWorkers.execute(incoming.wrap(() -> {
                    try {
                        InvocationOptions original = request.options() == null ? InvocationOptions.defaults() : request.options();
                        long remaining = original.deadline().toNanos();
                        if (incoming.getDeadline() != null) remaining = Math.min(remaining, incoming.getDeadline().timeRemaining(TimeUnit.NANOSECONDS));
                        if (incoming.isCancelled() || remaining <= 0) {
                            throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "Async admission budget expired");
                        }
                        InvocationOptions effective = new InvocationOptions(original.invocationId(), Duration.ofNanos(remaining), original.metadata());
                        EncodedValue encoded = this.context.dispatchLogicalAsyncTrusted(request.target(), request.method(),
                                request.arguments(), effective, PEER_SECURITY_CONTEXT.get(), listener -> {
                                    Context.CancellationListener registered = ignored -> listener.run();
                                    incoming.addListener(registered, Runnable::run);
                                    return () -> incoming.removeListener(registered);
                                });
                        observer.onNext(new GrpcWire.InvokeResponse(true, encoded, null)); observer.onCompleted();
                    } catch (MiddlewareException failure) {
                        observer.onNext(new GrpcWire.InvokeResponse(false, null, failure.remoteError())); observer.onCompleted();
                    } catch (RuntimeException failure) {
                        observer.onError(Status.INTERNAL.withDescription("Async invocation failed").asRuntimeException());
                    }
                }));
            } catch (RejectedExecutionException rejected) {
                observer.onError(Status.RESOURCE_EXHAUSTED.withDescription("Async admission capacity exhausted").asRuntimeException());
            }
        });
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
        ManagedChannel channel = this.channel(target.endpoint());
        try {
            GrpcWire.InvokeResponse response = ClientCalls.blockingUnaryCall(
                    channel,
                    INVOKE_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(Math.max(1L, safeOptions.deadline().toMillis()), TimeUnit.MILLISECONDS),
                    new GrpcWire.InvokeRequest(target, method, safeArguments, safeOptions));
            if (!response.success()) {
                throw new MiddlewareException(response.error());
            }
            return this.context.decodeValue(response.returnValue(), returnType);
        } catch (StatusRuntimeException exception) {
            throw this.mapStatus(exception, safeOptions, target.runtimeId());
        } catch (UncheckedIOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.CODEC_ERROR, "grpc wire codec failed",
                    Map.of(), safeOptions.invocationId(), target.runtimeId(), false, true), exception);
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
        this.open(ref, api.getName(), options == null ? ProxyOptions.defaults() : options);
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
        this.serverStreamCount.incrementAndGet();
        InvocationOptions safeOptions = options == null ? InvocationOptions.defaults() : options;
        this.checkStreamListSize(arguments == null ? 0 : arguments.size(), safeOptions, target.runtimeId());
        AtomicBoolean permitReleased = this.acquireStreamPermit(safeOptions, target.runtimeId());
        try {
            ManagedChannel channel = this.channel(target.endpoint());
            ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(streamBuffer(safeOptions));
            AtomicBoolean closedFlag = new AtomicBoolean();
            AtomicBoolean cancelledFlag = new AtomicBoolean();
            io.grpc.ClientCall<GrpcWire.StreamRequest, GrpcWire.StreamResponse> call = channel.newCall(
                    SERVER_STREAM_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(Math.max(1L, safeOptions.deadline().toMillis()), TimeUnit.MILLISECONDS));
            ClientCalls.asyncServerStreamingCall(call,
                    new GrpcWire.StreamRequest(target, method, arguments == null ? List.of() : arguments, safeOptions, true),
                    new QueueingStreamObserver(queue, closedFlag, cancelledFlag, itemType == null ? Object.class : itemType,
                            call, target.runtimeId(), safeOptions, permitReleased));
            return new GrpcQueueStreamHandle(queue, closedFlag, cancelledFlag, call, permitReleased);
        } catch (RuntimeException exception) {
            this.releaseStreamPermit(permitReleased);
            throw exception;
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
        this.clientStreamCount.incrementAndGet();
        InvocationOptions safeOptions = options == null ? InvocationOptions.defaults() : options;
        this.checkStreamListSize(items == null ? 0 : items.size(), safeOptions, target.runtimeId());
        AtomicBoolean permitReleased = this.acquireStreamPermit(safeOptions, target.runtimeId());
        try {
            ManagedChannel channel = this.channel(target.endpoint());
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReferenceValue response = new AtomicReferenceValue();
            StreamObserver<GrpcWire.StreamResponse> responseObserver = new StreamObserver<>() {
                @Override
                public void onNext(final GrpcWire.StreamResponse value) {
                    response.value = value;
                }

                @Override
                public void onError(final Throwable throwable) {
                    response.failure = throwable;
                    completed.countDown();
                }

                @Override
                public void onCompleted() {
                    completed.countDown();
                }
            };
            StreamObserver<GrpcWire.StreamRequest> requestObserver = ClientCalls.asyncClientStreamingCall(
                    channel.newCall(CLIENT_STREAM_METHOD, CallOptions.DEFAULT.withDeadlineAfter(Math.max(1L, safeOptions.deadline().toMillis()), TimeUnit.MILLISECONDS)),
                    responseObserver);
            for (EncodedValue item : items == null ? List.<EncodedValue>of() : items) {
                requestObserver.onNext(new GrpcWire.StreamRequest(target, method, List.of(item), safeOptions, false));
            }
            requestObserver.onCompleted();
            this.awaitStreamCompletion(completed, safeOptions, target.runtimeId());
            if (response.failure != null) {
                throw this.streamFailure(response.failure, safeOptions, target.runtimeId());
            }
            if (response.value == null) {
                throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "grpc client stream returned no response");
            }
            if (!response.value.success()) {
                throw new MiddlewareException(response.value.error());
            }
            return this.context.decodeValue(response.value.value(), this.classFor(method.returnTypeName()));
        } finally {
            this.releaseStreamPermit(permitReleased);
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
        this.bidiStreamCount.incrementAndGet();
        InvocationOptions safeOptions = options == null ? InvocationOptions.defaults() : options;
        this.checkStreamListSize(items == null ? 0 : items.size(), safeOptions, target.runtimeId());
        AtomicBoolean permitReleased = this.acquireStreamPermit(safeOptions, target.runtimeId());
        try {
            ManagedChannel channel = this.channel(target.endpoint());
            ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(streamBuffer(safeOptions));
            AtomicBoolean closedFlag = new AtomicBoolean();
            AtomicBoolean cancelledFlag = new AtomicBoolean();
            io.grpc.ClientCall<GrpcWire.StreamRequest, GrpcWire.StreamResponse> call = channel.newCall(
                    BIDI_STREAM_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(Math.max(1L, safeOptions.deadline().toMillis()), TimeUnit.MILLISECONDS));
            StreamObserver<GrpcWire.StreamRequest> requestObserver = ClientCalls.asyncBidiStreamingCall(call,
                    new QueueingStreamObserver(queue, closedFlag, cancelledFlag, itemType == null ? Object.class : itemType,
                            call, target.runtimeId(), safeOptions, permitReleased));
            for (EncodedValue item : items == null ? List.<EncodedValue>of() : items) {
                requestObserver.onNext(new GrpcWire.StreamRequest(target, method, List.of(item), safeOptions, false));
            }
            requestObserver.onCompleted();
            return new GrpcQueueStreamHandle(queue, closedFlag, cancelledFlag, call, permitReleased);
        } catch (RuntimeException exception) {
            this.releaseStreamPermit(permitReleased);
            throw exception;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        this.running = false;
        this.exports.clear();
        this.asyncWorkers.shutdown();
        this.closeChannels();
        Server currentServer = this.server;
        if (currentServer != null) {
            currentServer.shutdownNow();
        }
    }

    /**
     * Refreshes client channels after credential rotation.
     *
     * <p>Existing in-flight calls are allowed to fail or complete according to
     * gRPC semantics. Future calls build channels from the currently configured
     * credential files.</p>
     */
    public void refreshCredentials() {
        this.credentialRefreshCount.incrementAndGet();
        this.closeChannels();
    }

    /**
     * Returns the number of credential refresh notifications handled.
     *
     * @return refresh count
     */
    public long credentialRefreshCount() {
        return this.credentialRefreshCount.get();
    }

    /**
     * Returns the number of server-streaming calls opened by this provider.
     *
     * @return number of server-streaming calls opened by this provider
     */
    public long serverStreamCount() {
        return this.serverStreamCount.get();
    }

    /**
     * Returns the number of client-streaming calls opened by this provider.
     *
     * @return number of client-streaming calls opened by this provider
     */
    public long clientStreamCount() {
        return this.clientStreamCount.get();
    }

    /**
     * Returns the number of bidirectional streaming calls opened by this provider.
     *
     * @return number of bidirectional streaming calls opened by this provider
     */
    public long bidiStreamCount() {
        return this.bidiStreamCount.get();
    }

    /**
     * Returns the number of streaming failures observed by this provider.
     *
     * @return number of streaming failures observed by this provider
     */
    public long streamFailureCount() {
        return this.streamFailureCount.get();
    }

    private void open(final ObjectRef ref, final String interfaceName, final ProxyOptions options) {
        long budget = Math.min(TimeUnit.SECONDS.toNanos(5L), options.invocationDeadline().toNanos());
        String localDeadline = options.metadata().get(ProxyOptions.LOCAL_DEADLINE_NANOS);
        if (localDeadline != null) {
            try { budget = Math.min(budget, Long.parseLong(localDeadline) - System.nanoTime()); }
            catch (NumberFormatException invalid) { throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "invalid local deadline"); }
        }
        if (budget <= 0) throw MiddlewareException.of(MiddlewareErrorCode.DEADLINE_EXCEEDED, "reference deadline expired");
        ManagedChannel channel = this.channel(ref.endpoint());
        try {
            GrpcWire.OpenResponse response = ClientCalls.blockingUnaryCall(
                    channel,
                    OPEN_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(budget, TimeUnit.NANOSECONDS),
                    new GrpcWire.OpenRequest(ref, interfaceName));
            if (!response.success()) {
                throw new MiddlewareException(response.error());
            }
        } catch (StatusRuntimeException exception) {
            throw this.mapStatus(exception, InvocationOptions.defaults(), ref.runtimeId());
        }
    }

    GrpcWire.DescribeResponse describe(final ObjectRef ref) {
        ManagedChannel channel = this.channel(ref.endpoint());
        try {
            GrpcWire.DescribeResponse response = ClientCalls.blockingUnaryCall(
                    channel,
                    DESCRIBE_METHOD,
                    CallOptions.DEFAULT.withDeadlineAfter(5L, TimeUnit.SECONDS),
                    new GrpcWire.DescribeRequest(ref));
            if (!response.success()) {
                throw new MiddlewareException(response.error());
            }
            return response;
        } catch (StatusRuntimeException exception) {
            throw this.mapStatus(exception, InvocationOptions.defaults(), ref.runtimeId());
        }
    }

    private GrpcWire.InvokeResponse handleInvoke(final GrpcWire.InvokeRequest request) {
        try {
            InvocationOptions effectiveOptions = request.options() == null ? InvocationOptions.defaults() : request.options();
            EncodedValue encodedValue = this.context.dispatchTrusted(request.target(), request.method(), request.arguments(), effectiveOptions,
                    PEER_SECURITY_CONTEXT.get());
            return new GrpcWire.InvokeResponse(true, encodedValue, null);
        } catch (MiddlewareException exception) {
            return new GrpcWire.InvokeResponse(false, null, exception.remoteError());
        } catch (RuntimeException exception) {
            return new GrpcWire.InvokeResponse(false, null, new RemoteError(MiddlewareErrorCode.INTERNAL,
                    "grpc invocation dispatch failed", Map.of(), request.options().invocationId(), this.context.identity().runtimeId(), false, true));
        }
    }

    private GrpcWire.OpenResponse handleOpen(final GrpcWire.OpenRequest request) {
        try {
            this.context.validateTarget(request.target(), request.interfaceName());
        } catch (MiddlewareException exception) {
            return new GrpcWire.OpenResponse(false, exception.remoteError());
        }
        return new GrpcWire.OpenResponse(true, null);
    }

    private GrpcWire.DescribeResponse handleDescribe(final GrpcWire.DescribeRequest request) {
        try {
            return new GrpcWire.DescribeResponse(true, this.context.describeTarget(request.target()), null);
        } catch (MiddlewareException exception) {
            return new GrpcWire.DescribeResponse(false, List.of(), exception.remoteError());
        }
    }

    private void handleServerStream(final GrpcWire.StreamRequest request, final StreamObserver<GrpcWire.StreamResponse> observer) {
        try {
            InvocationOptions effectiveOptions = request.options() == null ? InvocationOptions.defaults() : request.options();
            this.checkStreamListSize(request.items().size(), effectiveOptions, request.target().runtimeId());
            EncodedValue encodedValue = this.context.dispatchTrusted(request.target(), request.method(), request.items(), effectiveOptions,
                    PEER_SECURITY_CONTEXT.get());
            long sequence = this.emitStreamResponses(encodedValue, effectiveOptions, request.target().runtimeId(), observer);
            observer.onNext(new GrpcWire.StreamResponse(true, null, null, true, sequence));
        } catch (MiddlewareException exception) {
            this.streamFailureCount.incrementAndGet();
            observer.onNext(new GrpcWire.StreamResponse(false, null, exception.remoteError(), true, 0L));
        } catch (RuntimeException exception) {
            this.streamFailureCount.incrementAndGet();
            InvocationOptions effectiveOptions = request.options() == null ? InvocationOptions.defaults() : request.options();
            observer.onNext(new GrpcWire.StreamResponse(false, null, new RemoteError(MiddlewareErrorCode.INTERNAL,
                    "grpc stream dispatch failed", Map.of(), effectiveOptions.invocationId(), this.context.identity().runtimeId(), false, true), true, 0L));
        }
    }

    private GrpcWire.StreamResponse handleClientStream(final List<GrpcWire.StreamRequest> requests) {
        if (requests.isEmpty()) {
            return new GrpcWire.StreamResponse(false, null, new RemoteError(MiddlewareErrorCode.INVALID_ARGUMENT,
                    "client stream contained no items", Map.of(), null, this.context.identity().runtimeId(), false, false), true, 0L);
        }
        GrpcWire.StreamRequest first = requests.getFirst();
        try {
            InvocationOptions effectiveOptions = first.options() == null ? InvocationOptions.defaults() : first.options();
            this.checkStreamListSize(requests.size(), effectiveOptions, first.target().runtimeId());
            List<Object> values = new ArrayList<>(requests.size());
            for (GrpcWire.StreamRequest request : requests) {
                for (EncodedValue item : request.items()) {
                    this.checkStreamItemSize(item, effectiveOptions, first.target().runtimeId());
                    values.add(this.context.decodeValue(item, Object.class));
                }
            }
            EncodedValue argument = new EncodedValue("list", List.class.getName(), List.copyOf(values), Map.of());
            EncodedValue result = this.context.dispatchTrusted(first.target(), first.method(), List.of(argument), effectiveOptions,
                    PEER_SECURITY_CONTEXT.get());
            return new GrpcWire.StreamResponse(true, result, null, true, 0L);
        } catch (MiddlewareException exception) {
            this.streamFailureCount.incrementAndGet();
            return new GrpcWire.StreamResponse(false, null, exception.remoteError(), true, 0L);
        } catch (RuntimeException exception) {
            this.streamFailureCount.incrementAndGet();
            return new GrpcWire.StreamResponse(false, null, new RemoteError(MiddlewareErrorCode.INTERNAL,
                    "grpc client stream dispatch failed", Map.of(), first.options().invocationId(), this.context.identity().runtimeId(), false, true), true, 0L);
        }
    }

    private GrpcWire.StreamResponse handleBidiStreamItem(final GrpcWire.StreamRequest request, final long sequence) {
        try {
            InvocationOptions effectiveOptions = request.options() == null ? InvocationOptions.defaults() : request.options();
            this.checkStreamListSize(request.items().size(), effectiveOptions, request.target().runtimeId());
            EncodedValue argument = request.items().isEmpty()
                    ? new EncodedValue("null", Object.class.getName(), null, Map.of())
                    : request.items().getFirst();
            this.checkStreamItemSize(argument, effectiveOptions, request.target().runtimeId());
            EncodedValue result = this.context.dispatchTrusted(request.target(), request.method(), List.of(argument), effectiveOptions,
                    PEER_SECURITY_CONTEXT.get());
            this.checkStreamItemSize(result, effectiveOptions, request.target().runtimeId());
            return new GrpcWire.StreamResponse(true, result, null, false, sequence);
        } catch (MiddlewareException exception) {
            this.streamFailureCount.incrementAndGet();
            return new GrpcWire.StreamResponse(false, null, exception.remoteError(), true, sequence);
        }
    }

    private long emitStreamResponses(
            final EncodedValue encodedValue,
            final InvocationOptions options,
            final String runtimeId,
            final StreamObserver<GrpcWire.StreamResponse> observer) {
        Object raw = encodedValue == null ? null : encodedValue.value();
        if (raw == null) {
            return 0L;
        }
        long sequence = 0L;
        if (raw instanceof List<?> list) {
            this.checkStreamListSize(list.size(), options, runtimeId);
            for (Object item : list) {
                this.emitStreamItem(item, sequence, options, runtimeId, observer);
                sequence++;
            }
            return sequence;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (sequence >= MAX_STREAM_ITEMS) {
                    throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.OVERLOADED, "grpc stream item count exceeds limit",
                            Map.of("limit", Integer.toString(MAX_STREAM_ITEMS)), options.invocationId(), runtimeId, true, true));
                }
                this.emitStreamItem(item, sequence, options, runtimeId, observer);
                sequence++;
            }
            return sequence;
        }
        this.emitStreamItem(raw, 0L, options, runtimeId, observer);
        return 1L;
    }

    private void emitStreamItem(
            final Object item,
            final long sequence,
            final InvocationOptions options,
            final String runtimeId,
            final StreamObserver<GrpcWire.StreamResponse> observer) {
        EncodedValue encodedItem = this.encodedStreamItem(item);
        this.checkStreamItemSize(encodedItem, options, runtimeId);
        observer.onNext(new GrpcWire.StreamResponse(true, encodedItem, null, false, sequence));
    }

    private EncodedValue encodedStreamItem(final Object item) {
        if (item instanceof EncodedValue encodedValue) {
            return encodedValue;
        }
        return this.context.encodeValue(item, item == null ? Object.class : item.getClass());
    }

    private void checkStreamItemSize(final EncodedValue value, final InvocationOptions options, final String runtimeId) {
        int limit = streamItemLimit(options);
        if (limit <= 0) {
            return;
        }
        int size = GrpcWireCodec.encodeValue(value).length;
        if (size > limit) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.INVALID_ARGUMENT, "stream item exceeds configured limit",
                    Map.of("limit", Integer.toString(limit)), options.invocationId(), runtimeId, false, false));
        }
    }

    private ManagedChannel channel(final String endpointValue) {
        try {
            synchronized (this.channelCacheLock) {
                ManagedChannel existing = this.channels.get(endpointValue);
                if (existing != null) {
                    return existing;
                }
                URI uri = URI.create(endpointValue);
                if (!"grpc".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() <= 0) {
                    throw MiddlewareException.of(MiddlewareErrorCode.INVALID_ARGUMENT, "invalid grpc endpoint");
                }
                ManagedChannelBuilder<?> builder = Grpc.newChannelBuilder(uri.getHost() + ":" + uri.getPort(), this.channelCredentials())
                        .maxInboundMessageSize(MAX_GRPC_MESSAGE_BYTES);
                GrpcTlsConfiguration tls = this.securityOptions.tlsConfiguration();
                if (tls != null && !tls.authorityOverride().isBlank()) {
                    builder.overrideAuthority(tls.authorityOverride());
                }
                ManagedChannel created = builder.build();
                this.channels.put(endpointValue, created);
                this.trimChannels(endpointValue);
                return created;
            }
        } catch (IllegalArgumentException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.TRANSPORT_ERROR, "invalid grpc endpoint",
                    Map.of(), null, null, true, true), exception);
        }
    }

    private void trimChannels(final String retainedEndpoint) {
        if (this.channels.size() <= MAX_ENDPOINT_CACHE) {
            return;
        }
        List<String> keys = new ArrayList<>(this.channels.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            if (key.equals(retainedEndpoint) && this.channels.size() <= MAX_ENDPOINT_CACHE) {
                return;
            }
            if (key.equals(retainedEndpoint)) {
                continue;
            }
            ManagedChannel channel = this.channels.remove(key);
            if (channel != null) {
                channel.shutdownNow();
            }
            if (this.channels.size() <= MAX_ENDPOINT_CACHE) {
                return;
            }
        }
    }

    private MiddlewareException mapStatus(final StatusRuntimeException exception, final InvocationOptions options, final String runtimeId) {
        MiddlewareErrorCode code = switch (exception.getStatus().getCode()) {
            case DEADLINE_EXCEEDED -> MiddlewareErrorCode.DEADLINE_EXCEEDED;
            case UNAVAILABLE -> this.securityOptions.tlsEnabled() || looksLikeTlsFailure(exception)
                    ? MiddlewareErrorCode.UNAUTHENTICATED
                    : MiddlewareErrorCode.UNAVAILABLE;
            case CANCELLED -> MiddlewareErrorCode.CANCELLED;
            case RESOURCE_EXHAUSTED -> MiddlewareErrorCode.OVERLOADED;
            default -> MiddlewareErrorCode.TRANSPORT_ERROR;
        };
        return new MiddlewareException(new RemoteError(code, "grpc transport failure", Map.of("status", exception.getStatus().getCode().name()),
                options.invocationId(), runtimeId, true, true), exception);
    }

    private static boolean looksLikeTlsFailure(final StatusRuntimeException exception) {
        String text = exception.toString();
        Throwable cause = exception.getCause();
        while (cause != null) {
            text = text + " " + cause.getClass().getName() + " " + cause.getMessage();
            cause = cause.getCause();
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("ssl") || lower.contains("tls") || lower.contains("notsslrecord") || lower.contains("certificate");
    }

    private void ensureRunning() {
        if (!this.running || this.context == null) {
            throw MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE, "grpc transport is not running");
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

    private ServerCredentials serverCredentials() {
        GrpcTlsConfiguration tls = this.securityOptions.tlsConfiguration();
        if (tls == null) {
            return InsecureServerCredentials.create();
        }
        try {
            TlsServerCredentials.Builder builder = TlsServerCredentials.newBuilder()
                    .keyManager(tls.certificateChain().toFile(), tls.privateKey().toFile())
                    .trustManager(tls.trustedCertificates().toFile());
            if (tls.requireClientAuthentication()) {
                builder.clientAuth(TlsServerCredentials.ClientAuth.REQUIRE);
            }
            return builder.build();
        } catch (IOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.TRANSPORT_ERROR, "grpc tls server credentials are invalid",
                    Map.of(), null, null, true, true), exception);
        }
    }

    private ChannelCredentials channelCredentials() {
        GrpcTlsConfiguration tls = this.securityOptions.tlsConfiguration();
        if (tls == null) {
            return InsecureChannelCredentials.create();
        }
        try {
            TlsChannelCredentials.Builder builder = TlsChannelCredentials.newBuilder()
                    .trustManager(tls.trustedCertificates().toFile());
            if (tls.requireClientAuthentication()) {
                builder.keyManager(tls.certificateChain().toFile(), tls.privateKey().toFile());
            }
            return builder.build();
        } catch (IOException exception) {
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.TRANSPORT_ERROR, "grpc tls client credentials are invalid",
                    Map.of(), null, null, true, true), exception);
        }
    }

    private void closeChannels() {
        synchronized (this.channelCacheLock) {
            for (ManagedChannel channel : this.channels.values()) {
                channel.shutdownNow();
            }
            this.channels.clear();
        }
    }

    private int streamBuffer(final InvocationOptions options) {
        String value = options.metadata().get("stream.buffer");
        if (value == null || value.isBlank()) {
            return 64;
        }
        try {
            return Math.max(1, Math.min(4096, Integer.parseInt(value)));
        } catch (NumberFormatException exception) {
            return 64;
        }
    }

    private static int streamItemLimit(final InvocationOptions options) {
        String value = options.metadata().get("stream.maxItemBytes");
        if (value == null || value.isBlank()) {
            return 1024 * 1024;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return 1024 * 1024;
        }
    }

    private void checkStreamListSize(final int itemCount, final InvocationOptions options, final String runtimeId) {
        if (itemCount > MAX_STREAM_ITEMS) {
            this.streamFailureCount.incrementAndGet();
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.OVERLOADED, "grpc stream item count exceeds limit",
                    Map.of("limit", Integer.toString(MAX_STREAM_ITEMS)), options.invocationId(), runtimeId, true, true));
        }
    }

    static int checkedInboundStreamItemCount(final int currentCount, final GrpcWire.StreamRequest request, final boolean countEmptyFrame) {
        int incoming = request == null || request.items() == null ? 0 : request.items().size();
        if (incoming == 0 && countEmptyFrame) {
            incoming = 1;
        }
        if (currentCount < 0 || incoming < 0 || currentCount > MAX_STREAM_ITEMS - incoming) {
            throw Status.RESOURCE_EXHAUSTED.withDescription("j7m stream item count exceeds limit").asRuntimeException();
        }
        return currentCount + incoming;
    }

    private AtomicBoolean acquireStreamPermit(final InvocationOptions options, final String runtimeId) {
        InvocationOptions safeOptions = options == null ? InvocationOptions.defaults() : options;
        if (!this.streamPermits.tryAcquire()) {
            this.streamFailureCount.incrementAndGet();
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.OVERLOADED,
                    "grpc concurrent stream limit exceeded", Map.of(), safeOptions.invocationId(), runtimeId, true, true));
        }
        return new AtomicBoolean(false);
    }

    private void releaseStreamPermit(final AtomicBoolean released) {
        if (released != null && released.compareAndSet(false, true)) {
            this.streamPermits.release();
        }
    }

    private void awaitStreamCompletion(final CountDownLatch completed, final InvocationOptions options, final String runtimeId) {
        try {
            boolean done = completed.await(Math.max(1L, options.deadline().toMillis()), TimeUnit.MILLISECONDS);
            if (!done) {
                this.streamFailureCount.incrementAndGet();
                throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.DEADLINE_EXCEEDED, "grpc stream deadline exceeded",
                        Map.of(), options.invocationId(), runtimeId, true, true));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            this.streamFailureCount.incrementAndGet();
            throw new MiddlewareException(new RemoteError(MiddlewareErrorCode.CANCELLED, "grpc stream wait interrupted",
                    Map.of(), options.invocationId(), runtimeId, true, true), exception);
        }
    }

    private MiddlewareException streamFailure(final Throwable throwable, final InvocationOptions options, final String runtimeId) {
        this.streamFailureCount.incrementAndGet();
        if (throwable instanceof StatusRuntimeException statusRuntimeException) {
            return this.mapStatus(statusRuntimeException, options, runtimeId);
        }
        return new MiddlewareException(new RemoteError(MiddlewareErrorCode.TRANSPORT_ERROR, "grpc stream transport failure",
                Map.of(), options.invocationId(), runtimeId, true, true), throwable);
    }

    private static <ReqT, RespT> ServerCallHandler<ReqT, RespT> unary(final UnaryHandler<ReqT, RespT> handler) {
        return ServerCalls.asyncUnaryCall((ReqT request, StreamObserver<RespT> observer) -> {
            try {
                observer.onNext(handler.handle(request));
                observer.onCompleted();
            } catch (RuntimeException exception) {
                observer.onError(Status.INTERNAL.withCause(exception).asRuntimeException());
            }
        });
    }

    private ServerCallHandler<GrpcWire.StreamRequest, GrpcWire.StreamResponse> serverStreaming(
            final ServerStreamHandler handler) {
        return ServerCalls.asyncServerStreamingCall((GrpcWire.StreamRequest request, StreamObserver<GrpcWire.StreamResponse> observer) -> {
            AtomicBoolean permitReleased;
            try {
                permitReleased = this.acquireStreamPermit(request.options(), request.target() == null ? this.context.identity().runtimeId() : request.target().runtimeId());
            } catch (MiddlewareException exception) {
                observer.onNext(new GrpcWire.StreamResponse(false, null, exception.remoteError(), true, 0L));
                observer.onCompleted();
                return;
            }
            try {
                handler.handle(request, observer);
                observer.onCompleted();
            } catch (RuntimeException exception) {
                observer.onError(Status.INTERNAL.withCause(exception).asRuntimeException());
            } finally {
                this.releaseStreamPermit(permitReleased);
            }
        });
    }

    private ServerCallHandler<GrpcWire.StreamRequest, GrpcWire.StreamResponse> clientStreaming(
            final ClientStreamHandler handler) {
        return ServerCalls.asyncClientStreamingCall((StreamObserver<GrpcWire.StreamResponse> observer) -> new StreamObserver<>() {
            private final List<GrpcWire.StreamRequest> requests = new ArrayList<>();
            private AtomicBoolean permitReleased;
            private int itemCount;
            private boolean rejected;

            @Override
            public void onNext(final GrpcWire.StreamRequest value) {
                if (this.rejected) {
                    return;
                }
                if (this.permitReleased == null) {
                    try {
                        this.permitReleased = acquireStreamPermit(value.options(), value.target() == null ? context.identity().runtimeId() : value.target().runtimeId());
                    } catch (MiddlewareException exception) {
                        this.rejected = true;
                        observer.onError(Status.RESOURCE_EXHAUSTED.withCause(exception).asRuntimeException());
                        return;
                    }
                }
                if (this.requests.size() >= MAX_STREAM_ITEMS) {
                    this.rejected = true;
                    releaseStreamPermit(this.permitReleased);
                    observer.onError(Status.RESOURCE_EXHAUSTED.withDescription("j7m stream item count exceeds limit").asRuntimeException());
                    return;
                }
                try {
                    this.itemCount = checkedInboundStreamItemCount(this.itemCount, value, false);
                } catch (StatusRuntimeException exception) {
                    this.rejected = true;
                    releaseStreamPermit(this.permitReleased);
                    observer.onError(exception);
                    return;
                }
                this.requests.add(value);
            }

            @Override
            public void onError(final Throwable throwable) {
                releaseStreamPermit(this.permitReleased);
                if (!this.rejected) {
                    observer.onError(Status.CANCELLED.withCause(throwable).asRuntimeException());
                }
            }

            @Override
            public void onCompleted() {
                try {
                    if (!this.rejected) {
                        observer.onNext(handler.handle(List.copyOf(this.requests)));
                        observer.onCompleted();
                    }
                } finally {
                    releaseStreamPermit(this.permitReleased);
                }
            }
        });
    }

    private ServerCallHandler<GrpcWire.StreamRequest, GrpcWire.StreamResponse> bidiStreaming(
            final BidiStreamHandler handler) {
        return ServerCalls.asyncBidiStreamingCall((StreamObserver<GrpcWire.StreamResponse> observer) -> new StreamObserver<>() {
            private long sequence;
            private AtomicBoolean permitReleased;
            private int itemCount;
            private boolean rejected;

            @Override
            public void onNext(final GrpcWire.StreamRequest value) {
                if (this.rejected) {
                    return;
                }
                if (this.permitReleased == null) {
                    try {
                        this.permitReleased = acquireStreamPermit(value.options(), value.target() == null ? context.identity().runtimeId() : value.target().runtimeId());
                    } catch (MiddlewareException exception) {
                        this.rejected = true;
                        observer.onError(Status.RESOURCE_EXHAUSTED.withCause(exception).asRuntimeException());
                        return;
                    }
                }
                try {
                    this.itemCount = checkedInboundStreamItemCount(this.itemCount, value, true);
                } catch (StatusRuntimeException exception) {
                    this.rejected = true;
                    releaseStreamPermit(this.permitReleased);
                    observer.onError(exception);
                    return;
                }
                try {
                    GrpcWire.StreamResponse response = handler.handle(value, this.sequence++);
                    observer.onNext(response);
                    if (response.complete()) {
                        this.rejected = true;
                        releaseStreamPermit(this.permitReleased);
                    }
                } catch (RuntimeException exception) {
                    releaseStreamPermit(this.permitReleased);
                    throw exception;
                }
            }

            @Override
            public void onError(final Throwable throwable) {
                releaseStreamPermit(this.permitReleased);
                if (!this.rejected) {
                    observer.onError(Status.CANCELLED.withCause(throwable).asRuntimeException());
                }
            }

            @Override
            public void onCompleted() {
                try {
                    if (!this.rejected) {
                        observer.onNext(new GrpcWire.StreamResponse(true, null, null, true, this.sequence));
                        observer.onCompleted();
                    }
                } finally {
                    releaseStreamPermit(this.permitReleased);
                }
            }
        });
    }

    @FunctionalInterface
    private interface UnaryHandler<ReqT, RespT> {
        RespT handle(ReqT request);
    }

    @FunctionalInterface
    private interface ServerStreamHandler {
        void handle(GrpcWire.StreamRequest request, StreamObserver<GrpcWire.StreamResponse> observer);
    }

    @FunctionalInterface
    private interface ClientStreamHandler {
        GrpcWire.StreamResponse handle(List<GrpcWire.StreamRequest> requests);
    }

    @FunctionalInterface
    private interface BidiStreamHandler {
        GrpcWire.StreamResponse handle(GrpcWire.StreamRequest request, long sequence);
    }

    private final class QueueingStreamObserver implements StreamObserver<GrpcWire.StreamResponse> {
        private final ArrayBlockingQueue<Object> queue;
        private final AtomicBoolean closedFlag;
        private final AtomicBoolean cancelledFlag;
        private final Class<?> itemType;
        private final io.grpc.ClientCall<?, ?> call;
        private final String runtimeId;
        private final InvocationOptions options;
        private final AtomicBoolean permitReleased;

        private QueueingStreamObserver(
                final ArrayBlockingQueue<Object> queue,
                final AtomicBoolean closedFlag,
                final AtomicBoolean cancelledFlag,
                final Class<?> itemType,
                final io.grpc.ClientCall<?, ?> call,
                final String runtimeId,
                final InvocationOptions options,
                final AtomicBoolean permitReleased) {
            this.queue = queue;
            this.closedFlag = closedFlag;
            this.cancelledFlag = cancelledFlag;
            this.itemType = itemType;
            this.call = call;
            this.runtimeId = runtimeId;
            this.options = options;
            this.permitReleased = permitReleased;
        }

        @Override
        public void onNext(final GrpcWire.StreamResponse value) {
            if (!value.success()) {
                this.offerTerminal(new MiddlewareException(value.error()));
                this.closedFlag.set(true);
                releaseStreamPermit(this.permitReleased);
                return;
            }
            if (value.complete()) {
                this.closedFlag.set(true);
                this.offerTerminal(StreamEnd.INSTANCE);
                releaseStreamPermit(this.permitReleased);
                return;
            }
            Object decoded = context.decodeValue(value.value(), this.itemType);
            this.offer(decoded);
        }

        @Override
        public void onError(final Throwable throwable) {
            this.offerTerminal(streamFailure(throwable, this.options, this.runtimeId));
            this.closedFlag.set(true);
            releaseStreamPermit(this.permitReleased);
        }

        @Override
        public void onCompleted() {
            this.closedFlag.set(true);
            this.offerTerminal(StreamEnd.INSTANCE);
            releaseStreamPermit(this.permitReleased);
        }

        private void offer(final Object value) {
            try {
                boolean accepted = this.queue.offer(value, 1L, TimeUnit.SECONDS);
                if (!accepted) {
                    this.cancelledFlag.set(true);
                    this.call.cancel("j7m stream consumer backpressure capacity exceeded", null);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                this.cancelledFlag.set(true);
                this.call.cancel("j7m stream observer interrupted", exception);
            }
        }

        private void offerTerminal(final Object value) {
            boolean accepted = this.queue.offer(value);
            while (!accepted) {
                this.queue.poll();
                accepted = this.queue.offer(value);
            }
        }
    }

    private final class GrpcQueueStreamHandle implements StreamHandle<Object> {
        private final ArrayBlockingQueue<Object> queue;
        private final AtomicBoolean closedFlag;
        private final AtomicBoolean cancelledFlag;
        private final io.grpc.ClientCall<?, ?> call;
        private final AtomicBoolean permitReleased;

        private GrpcQueueStreamHandle(
                final ArrayBlockingQueue<Object> queue,
                final AtomicBoolean closedFlag,
                final AtomicBoolean cancelledFlag,
                final io.grpc.ClientCall<?, ?> call,
                final AtomicBoolean permitReleased) {
            this.queue = queue;
            this.closedFlag = closedFlag;
            this.cancelledFlag = cancelledFlag;
            this.call = call;
            this.permitReleased = permitReleased;
        }

        @Override
        public Optional<Object> poll(final Duration timeout) {
            if (this.closedFlag.get() && this.queue.isEmpty()) {
                return Optional.empty();
            }
            try {
                Object value = this.queue.poll(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
                if (value == null || value == StreamEnd.INSTANCE) {
                    return Optional.empty();
                }
                if (value instanceof MiddlewareException middlewareException) {
                    throw middlewareException;
                }
                return Optional.of(value);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw MiddlewareException.of(MiddlewareErrorCode.CANCELLED, "stream poll interrupted");
            }
        }

        @Override
        public void cancel() {
            if (this.cancelledFlag.compareAndSet(false, true)) {
                this.call.cancel("j7m stream cancelled", null);
            }
            this.closedFlag.set(true);
            releaseStreamPermit(this.permitReleased);
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

    private enum StreamEnd {
        INSTANCE
    }

    private static final class AtomicReferenceValue {
        private volatile GrpcWire.StreamResponse value;
        private volatile Throwable failure;
    }

    private static final class Marshaller<T> implements io.grpc.MethodDescriptor.Marshaller<T> {
        private final Encoder<T> encoder;
        private final Decoder<T> decoder;

        private Marshaller(final Encoder<T> encoder, final Decoder<T> decoder) {
            this.encoder = encoder;
            this.decoder = decoder;
        }

        @Override
        public InputStream stream(final T value) {
            return GrpcWireCodec.inputStream(this.encoder.encode(value));
        }

        @Override
        public T parse(final InputStream stream) {
            return this.decoder.decode(stream);
        }
    }

    @FunctionalInterface
    private interface Encoder<T> {
        byte[] encode(T value);
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T decode(InputStream input);
    }

    private static final class PeerSecurityInterceptor implements ServerInterceptor {
        private final GrpcTlsConfiguration tlsConfiguration;

        private PeerSecurityInterceptor(final GrpcTlsConfiguration tlsConfiguration) {
            this.tlsConfiguration = tlsConfiguration;
        }

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                final ServerCall<ReqT, RespT> call,
                final Metadata headers,
                final ServerCallHandler<ReqT, RespT> next) {
            SecurityContext context = this.securityContext(call);
            return Contexts.interceptCall(Context.current().withValue(PEER_SECURITY_CONTEXT, context), call, headers, next);
        }

        private SecurityContext securityContext(final ServerCall<?, ?> call) {
            SSLSession sslSession = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
            if (sslSession == null) {
                return unauthenticated(call);
            }
            try {
                Certificate[] certificates = sslSession.getPeerCertificates();
                if (certificates.length > 0 && certificates[0] instanceof X509Certificate certificate) {
                    GrpcCertificateIdentity identity = GrpcCertificateIdentity.fromCertificate(certificate);
                    Principal principal = new Principal(identity.principalId(), identity.principalId(), Set.of("runtime"), Set.of("mtls"));
                    Map<String, String> metadata = new LinkedHashMap<>(identity.metadata());
                    metadata.put("sec.mtls", "true");
                    metadata.put("sec.remoteEndpoint", String.valueOf(call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR)));
                    return new SecurityContext(principal, identity.runtimeId(), new TrustDomain(this.tlsConfiguration.trustDomain()),
                            new Credential("x509", identity.fingerprint(), Map.of("fingerprint", identity.fingerprint())),
                            "", "", metadata.getOrDefault("sec.remoteEndpoint", ""), true,
                            TransportSecurityLevel.AUTHENTICATED_ENCRYPTED, principal.roles(), metadata, Instant.now());
                }
                return unauthenticated(call);
            } catch (SSLPeerUnverifiedException exception) {
                return unauthenticated(call);
            }
        }

        private static SecurityContext unauthenticated(final ServerCall<?, ?> call) {
            String remoteEndpoint = String.valueOf(call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));
            return new SecurityContext(Principal.anonymous(), "", new TrustDomain("default"), new Credential("none", "0", Map.of()),
                    "", "", remoteEndpoint, false, TransportSecurityLevel.INSECURE, Set.of(), Map.of("sec.mtls", "false"), Instant.now());
        }
    }
}
