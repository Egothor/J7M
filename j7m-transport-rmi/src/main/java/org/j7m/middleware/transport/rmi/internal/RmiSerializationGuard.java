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

package org.j7m.middleware.transport.rmi.internal;

import java.io.ObjectInputFilter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.rmi.Remote;
import java.rmi.registry.Registry;
import java.rmi.server.ObjID;
import java.rmi.server.RemoteObjectInvocationHandler;
import java.rmi.server.UID;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.RemoteError;

/**
 * Installs or validates the process-wide native serialization filter required
 * by the J7M RMI transport.
 *
 * <p>The guard is intentionally internal because Java RMI exposes native
 * serialization before the transport provider can decode the J7M byte
 * envelope. The accepted application protocol remains {@code byte[]} only.
 * This filter rejects middleware DTO graphs, application serializable objects,
 * arbitrary collections, reflection objects, broad proxy classes, and unknown
 * serializable payloads at the JVM serialization boundary.</p>
 *
 * <p>The global JDK filter is one-way process state. If a filter already
 * exists, this guard does not replace it. Instead, it verifies the existing
 * policy with required positive and negative probes and fails RMI startup if
 * the policy is too broad, too restrictive, or undecided for forbidden
 * classes.</p>
 */
public final class RmiSerializationGuard {
    /**
     * Maximum accepted byte-array envelope length at the native RMI layer.
     */
    public static final long MAX_ARRAY_LENGTH = 1024L * 1024L;
    /**
     * Maximum accepted native deserialization depth.
     */
    public static final long MAX_DEPTH = 8L;
    /**
     * Maximum accepted native deserialization references.
     */
    public static final long MAX_REFERENCES = 4096L;
    /**
     * Maximum accepted native deserialization stream bytes.
     */
    public static final long MAX_STREAM_BYTES = 2L * 1024L * 1024L;

    private RmiSerializationGuard() {
    }

    /**
     * Installs the strict J7M RMI serialization filter or validates the
     * pre-existing process filter.
     *
     * @throws MiddlewareException with {@link MiddlewareErrorCode#TRANSPORT_ERROR}
     *         when a pre-existing filter is incompatible, too broad, or when
     *         the process rejects filter installation
     */
    public static void installOrValidate() {
        ObjectInputFilter currentFilter = ObjectInputFilter.Config.getSerialFilter();
        if (currentFilter == null) {
            ObjectInputFilter strictFilter = RmiSerializationGuard::strictStatus;
            try {
                ObjectInputFilter.Config.setSerialFilter(strictFilter);
                currentFilter = strictFilter;
            } catch (IllegalStateException exception) {
                currentFilter = ObjectInputFilter.Config.getSerialFilter();
                if (currentFilter == null) {
                    throw transportError("failed to install RMI serialization filter", exception);
                }
            }
        }
        validate(currentFilter);
    }

    /**
     * Returns the decision made by the strict J7M RMI serialization policy.
     *
     * @param info JDK filter callback information
     * @return filter status
     */
    public static ObjectInputFilter.Status strictStatus(final ObjectInputFilter.FilterInfo info) {
        if (info == null) {
            return ObjectInputFilter.Status.REJECTED;
        }
        if (exceedsLimits(info)) {
            return ObjectInputFilter.Status.REJECTED;
        }
        Class<?> serialClass = info.serialClass();
        if (serialClass == null) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        if (serialClass.isPrimitive()) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        if (serialClass.isArray()) {
            return arrayStatus(serialClass);
        }
        if (serialClass == String.class
                || serialClass == Remote.class
                || serialClass == Registry.class
                || serialClass == RmiRemoteObjectService.class
                || serialClass == Proxy.class
                || serialClass == RemoteObjectInvocationHandler.class
                || serialClass == ObjID.class
                || serialClass == UID.class) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        if (Proxy.isProxyClass(serialClass)) {
            return proxyStatus(serialClass);
        }
        String name = serialClass.getName();
        if ("sun.rmi.server.UnicastRef".equals(name)
                || "sun.rmi.server.UnicastRef2".equals(name)
                || "sun.rmi.transport.LiveRef".equals(name)
                || "sun.rmi.transport.tcp.TCPEndpoint".equals(name)
                || "sun.rmi.registry.RegistryImpl_Stub".equals(name)
                || "java.rmi.server.RemoteStub".equals(name)
                || "java.rmi.server.RemoteObject".equals(name)
                || "java.rmi.dgc.Lease".equals(name)
                || "java.rmi.dgc.VMID".equals(name)) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        return ObjectInputFilter.Status.REJECTED;
    }

    private static ObjectInputFilter.Status arrayStatus(final Class<?> serialClass) {
        Class<?> component = serialClass.getComponentType();
        if (component == Byte.TYPE) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        return ObjectInputFilter.Status.REJECTED;
    }

    private static ObjectInputFilter.Status proxyStatus(final Class<?> serialClass) {
        Class<?>[] interfaces = serialClass.getInterfaces();
        if (interfaces.length == 0) {
            return ObjectInputFilter.Status.REJECTED;
        }
        boolean includesRemoteService = false;
        for (Class<?> api : interfaces) {
            if (api == RmiRemoteObjectService.class || api == Registry.class) {
                includesRemoteService = true;
            } else if (api != Remote.class) {
                return ObjectInputFilter.Status.REJECTED;
            }
        }
        if (includesRemoteService) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        return ObjectInputFilter.Status.REJECTED;
    }

    private static boolean exceedsLimits(final ObjectInputFilter.FilterInfo info) {
        long arrayLength = info.arrayLength();
        if (arrayLength >= 0L && arrayLength > MAX_ARRAY_LENGTH) {
            return true;
        }
        long depth = info.depth();
        if (depth > MAX_DEPTH) {
            return true;
        }
        long references = info.references();
        if (references > MAX_REFERENCES) {
            return true;
        }
        long streamBytes = info.streamBytes();
        return streamBytes > MAX_STREAM_BYTES;
    }

    private static void validate(final ObjectInputFilter filter) {
        requireAllowed(filter, byte[].class, MAX_ARRAY_LENGTH, 1L, 2L, MAX_ARRAY_LENGTH, "byte envelope");
        requireAllowed(filter, String.class, -1L, 1L, 2L, 64L, "RMI string");
        requireAllowed(filter, RemoteObjectInvocationHandler.class, -1L, 2L, 8L, 256L, "RMI invocation handler");
        requireAllowed(filter, RmiRemoteObjectService.class, -1L, 1L, 2L, 64L, "RMI service interface");
        requireRejected(filter, ForbiddenSerializable.class, -1L, 1L, 2L, 64L, "application serializable");
        requireRejected(filter, org.j7m.middleware.api.ObjectRef.class, -1L, 1L, 2L, 64L, "object reference DTO");
        requireRejected(filter, org.j7m.middleware.codec.api.EncodedValue.class, -1L, 1L, 2L, 64L, "encoded value DTO");
        requireRejected(filter, ArrayList.class, -1L, 1L, 2L, 64L, "arbitrary collection");
        requireRejected(filter, HashMap.class, -1L, 1L, 2L, 64L, "arbitrary collection");
        requireRejected(filter, Method.class, -1L, 1L, 2L, 64L, "reflection object");
        requireRejected(filter, BigInteger.class, -1L, 1L, 2L, 64L, "unknown serializable");
        requireRejected(filter, byte[].class, MAX_ARRAY_LENGTH + 1L, 1L, 2L, MAX_ARRAY_LENGTH, "oversized byte envelope");
        requireRejected(filter, byte[].class, 1L, MAX_DEPTH + 1L, 2L, 64L, "excessive depth");
        requireRejected(filter, byte[].class, 1L, 1L, MAX_REFERENCES + 1L, 64L, "excessive references");
        requireRejected(filter, byte[].class, 1L, 1L, 2L, MAX_STREAM_BYTES + 1L, "excessive stream bytes");
    }

    private static void requireAllowed(
            final ObjectInputFilter filter,
            final Class<?> serialClass,
            final long arrayLength,
            final long depth,
            final long references,
            final long streamBytes,
            final String probeName) {
        ObjectInputFilter.Status status = filter.checkInput(new ProbeInfo(serialClass, arrayLength, depth, references, streamBytes));
        if (status != ObjectInputFilter.Status.ALLOWED) {
            throw transportError("RMI serialization filter rejects required " + probeName, null);
        }
    }

    private static void requireRejected(
            final ObjectInputFilter filter,
            final Class<?> serialClass,
            final long arrayLength,
            final long depth,
            final long references,
            final long streamBytes,
            final String probeName) {
        ObjectInputFilter.Status status = filter.checkInput(new ProbeInfo(serialClass, arrayLength, depth, references, streamBytes));
        if (status != ObjectInputFilter.Status.REJECTED) {
            throw transportError("RMI serialization filter permits forbidden " + probeName, null);
        }
    }

    private static MiddlewareException transportError(final String message, final Throwable cause) {
        if (cause == null) {
            return MiddlewareException.of(MiddlewareErrorCode.TRANSPORT_ERROR, message);
        }
        return new MiddlewareException(new RemoteError(MiddlewareErrorCode.TRANSPORT_ERROR, message, Map.of(), null, null, true, true), cause);
    }

    private record ProbeInfo(Class<?> serialClass, long arrayLength, long depth, long references, long streamBytes)
            implements ObjectInputFilter.FilterInfo {
    }

    private static final class ForbiddenSerializable implements java.io.Serializable {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
    }
}
