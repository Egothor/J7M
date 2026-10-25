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

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.transport.api.MethodDescriptor;

/**
 * Protobuf-wire codec for the generic gRPC remote-object protocol.
 *
 * <p>The codec writes protobuf-compatible binary messages that match
 * {@code src/main/proto/j7m_remote_object.proto}. It intentionally does not use
 * Java native serialization.</p>
 */
final class GrpcWireCodec {
    private static final int MAX_DEPTH = 16;
    private static final int MAX_NODES = 4096;
    private static final int MAX_CONTAINER_ENTRIES = 1024;
    private static final int MAX_STRING_BYTES = 64 * 1024;
    private static final int MAX_VALUE_BYTES = 1024 * 1024;
    private static final int MAX_METADATA_ENTRIES = 32;
    private static final int MAX_METADATA_BYTES = 8 * 1024;

    private GrpcWireCodec() {
    }

    static byte[] encodeInvokeRequest(final GrpcWire.InvokeRequest request) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(512);
        try {
            CodedOutputStream coded = CodedOutputStream.newInstance(output);
            writeMessage(coded, 1, encodeObjectRef(request.target()));
            writeMessage(coded, 2, encodeMethod(request.method()));
            for (EncodedValue argument : request.arguments()) {
                writeMessage(coded, 3, encodeValue(argument));
            }
            writeString(coded, 4, request.options().invocationId());
            writeInt64(coded, 5, request.options().deadline().toMillis());
            writeStringMap(coded, 6, request.options().metadata());
            coded.flush();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static GrpcWire.InvokeRequest decodeInvokeRequest(final InputStream input) {
        try {
            CodedInputStream coded = CodedInputStream.newInstance(input);
            ObjectRef target = null;
            MethodDescriptor method = null;
            List<EncodedValue> arguments = new ArrayList<>();
            String invocationId = null;
            long deadlineMillis = 5_000L;
            Map<String, String> metadata = new LinkedHashMap<>();
            int tag;
            while ((tag = coded.readTag()) != 0) {
                int field = WireFormat.getTagFieldNumber(tag);
                if (field == 1) {
                    target = decodeObjectRef(readBytes(coded));
                } else if (field == 2) {
                    method = decodeMethod(readBytes(coded));
                } else if (field == 3) {
                    arguments.add(decodeValue(readBytes(coded)));
                } else if (field == 4) {
                    invocationId = coded.readStringRequireUtf8();
                } else if (field == 5) {
                    deadlineMillis = coded.readInt64();
                } else if (field == 6) {
                    metadata.putAll(readStringMapEntry(coded));
                } else {
                    coded.skipField(tag);
                }
            }
            return new GrpcWire.InvokeRequest(target, method, List.copyOf(arguments),
                    new InvocationOptions(invocationId, Duration.ofMillis(deadlineMillis), metadata));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] encodeInvokeResponse(final GrpcWire.InvokeResponse response) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(512);
        try {
            CodedOutputStream coded = CodedOutputStream.newInstance(output);
            coded.writeBool(1, response.success());
            if (response.returnValue() != null) {
                writeMessage(coded, 2, encodeValue(response.returnValue()));
            }
            if (response.error() != null) {
                writeMessage(coded, 3, encodeRemoteError(response.error()));
            }
            coded.flush();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static GrpcWire.InvokeResponse decodeInvokeResponse(final InputStream input) {
        try {
            CodedInputStream coded = CodedInputStream.newInstance(input);
            boolean success = false;
            EncodedValue returnValue = null;
            RemoteError error = null;
            int tag;
            while ((tag = coded.readTag()) != 0) {
                int field = WireFormat.getTagFieldNumber(tag);
                if (field == 1) {
                    success = coded.readBool();
                } else if (field == 2) {
                    returnValue = decodeValue(readBytes(coded));
                } else if (field == 3) {
                    error = decodeRemoteError(readBytes(coded));
                } else {
                    coded.skipField(tag);
                }
            }
            return new GrpcWire.InvokeResponse(success, returnValue, error);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] encodeOpenRequest(final GrpcWire.OpenRequest request) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(256);
        try {
            CodedOutputStream coded = CodedOutputStream.newInstance(output);
            writeMessage(coded, 1, encodeObjectRef(request.target()));
            writeString(coded, 2, request.interfaceName());
            coded.flush();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static GrpcWire.OpenRequest decodeOpenRequest(final InputStream input) {
        try {
            CodedInputStream coded = CodedInputStream.newInstance(input);
            ObjectRef target = null;
            String interfaceName = "";
            int tag;
            while ((tag = coded.readTag()) != 0) {
                int field = WireFormat.getTagFieldNumber(tag);
                if (field == 1) {
                    target = decodeObjectRef(readBytes(coded));
                } else if (field == 2) {
                    interfaceName = coded.readStringRequireUtf8();
                } else {
                    coded.skipField(tag);
                }
            }
            return new GrpcWire.OpenRequest(target, interfaceName);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] encodeOpenResponse(final GrpcWire.OpenResponse response) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(128);
        try {
            CodedOutputStream coded = CodedOutputStream.newInstance(output);
            coded.writeBool(1, response.success());
            if (response.error() != null) {
                writeMessage(coded, 2, encodeRemoteError(response.error()));
            }
            coded.flush();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static GrpcWire.OpenResponse decodeOpenResponse(final InputStream input) {
        try {
            CodedInputStream coded = CodedInputStream.newInstance(input);
            boolean success = false;
            RemoteError error = null;
            int tag;
            while ((tag = coded.readTag()) != 0) {
                int field = WireFormat.getTagFieldNumber(tag);
                if (field == 1) {
                    success = coded.readBool();
                } else if (field == 2) {
                    error = decodeRemoteError(readBytes(coded));
                } else {
                    coded.skipField(tag);
                }
            }
            return new GrpcWire.OpenResponse(success, error);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] encodeDescribeRequest(final GrpcWire.DescribeRequest request) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(256);
        try {
            CodedOutputStream coded = CodedOutputStream.newInstance(output);
            writeMessage(coded, 1, encodeObjectRef(request.target()));
            coded.flush();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static GrpcWire.DescribeRequest decodeDescribeRequest(final InputStream input) {
        try {
            CodedInputStream coded = CodedInputStream.newInstance(input);
            ObjectRef target = null;
            int tag;
            while ((tag = coded.readTag()) != 0) {
                if (WireFormat.getTagFieldNumber(tag) == 1) {
                    target = decodeObjectRef(readBytes(coded));
                } else {
                    coded.skipField(tag);
                }
            }
            return new GrpcWire.DescribeRequest(target);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] encodeDescribeResponse(final GrpcWire.DescribeResponse response) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(256);
        try {
            CodedOutputStream coded = CodedOutputStream.newInstance(output);
            coded.writeBool(1, response.success());
            for (String interfaceName : response.interfaces()) {
                writeString(coded, 2, interfaceName);
            }
            if (response.error() != null) {
                writeMessage(coded, 3, encodeRemoteError(response.error()));
            }
            coded.flush();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static GrpcWire.DescribeResponse decodeDescribeResponse(final InputStream input) {
        try {
            CodedInputStream coded = CodedInputStream.newInstance(input);
            boolean success = false;
            List<String> interfaces = new ArrayList<>();
            RemoteError error = null;
            int tag;
            while ((tag = coded.readTag()) != 0) {
                int field = WireFormat.getTagFieldNumber(tag);
                if (field == 1) {
                    success = coded.readBool();
                } else if (field == 2) {
                    interfaces.add(coded.readStringRequireUtf8());
                } else if (field == 3) {
                    error = decodeRemoteError(readBytes(coded));
                } else {
                    coded.skipField(tag);
                }
            }
            return new GrpcWire.DescribeResponse(success, List.copyOf(interfaces), error);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] encodeStreamRequest(final GrpcWire.StreamRequest request) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(512);
        try {
            CodedOutputStream coded = CodedOutputStream.newInstance(output);
            writeMessage(coded, 1, encodeObjectRef(request.target()));
            writeMessage(coded, 2, encodeMethod(request.method()));
            for (EncodedValue item : request.items()) {
                writeMessage(coded, 3, encodeValue(item));
            }
            writeString(coded, 4, request.options().invocationId());
            writeInt64(coded, 5, request.options().deadline().toMillis());
            writeStringMap(coded, 6, request.options().metadata());
            coded.writeBool(7, request.complete());
            coded.flush();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static GrpcWire.StreamRequest decodeStreamRequest(final InputStream input) {
        try {
            CodedInputStream coded = CodedInputStream.newInstance(input);
            ObjectRef target = null;
            MethodDescriptor method = null;
            List<EncodedValue> items = new ArrayList<>();
            String invocationId = null;
            long deadlineMillis = 5_000L;
            Map<String, String> metadata = new LinkedHashMap<>();
            boolean complete = false;
            int tag;
            while ((tag = coded.readTag()) != 0) {
                int field = WireFormat.getTagFieldNumber(tag);
                if (field == 1) {
                    target = decodeObjectRef(readBytes(coded));
                } else if (field == 2) {
                    method = decodeMethod(readBytes(coded));
                } else if (field == 3) {
                    if (items.size() >= GrpcTransportProvider.MAX_STREAM_ITEMS) {
                        throw new IOException("stream request item count exceeds limit");
                    }
                    items.add(decodeValue(readBytes(coded)));
                } else if (field == 4) {
                    invocationId = coded.readStringRequireUtf8();
                } else if (field == 5) {
                    deadlineMillis = coded.readInt64();
                } else if (field == 6) {
                    metadata.putAll(readStringMapEntry(coded));
                } else if (field == 7) {
                    complete = coded.readBool();
                } else {
                    coded.skipField(tag);
                }
            }
            return new GrpcWire.StreamRequest(target, method, List.copyOf(items),
                    new InvocationOptions(invocationId, Duration.ofMillis(deadlineMillis), metadata), complete);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] encodeStreamResponse(final GrpcWire.StreamResponse response) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(512);
        try {
            CodedOutputStream coded = CodedOutputStream.newInstance(output);
            coded.writeBool(1, response.success());
            if (response.value() != null) {
                writeMessage(coded, 2, encodeValue(response.value()));
            }
            if (response.error() != null) {
                writeMessage(coded, 3, encodeRemoteError(response.error()));
            }
            coded.writeBool(4, response.complete());
            writeInt64(coded, 5, response.sequence());
            coded.flush();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static GrpcWire.StreamResponse decodeStreamResponse(final InputStream input) {
        try {
            CodedInputStream coded = CodedInputStream.newInstance(input);
            boolean success = false;
            EncodedValue value = null;
            RemoteError error = null;
            boolean complete = false;
            long sequence = 0L;
            int tag;
            while ((tag = coded.readTag()) != 0) {
                int field = WireFormat.getTagFieldNumber(tag);
                if (field == 1) {
                    success = coded.readBool();
                } else if (field == 2) {
                    value = decodeValue(readBytes(coded));
                } else if (field == 3) {
                    error = decodeRemoteError(readBytes(coded));
                } else if (field == 4) {
                    complete = coded.readBool();
                } else if (field == 5) {
                    sequence = coded.readInt64();
                } else {
                    coded.skipField(tag);
                }
            }
            return new GrpcWire.StreamResponse(success, value, error, complete, sequence);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] encodeObjectRef(final ObjectRef ref) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(256);
        CodedOutputStream coded = CodedOutputStream.newInstance(output);
        writeString(coded, 1, ref.objectId());
        writeString(coded, 2, ref.logicalName());
        writeString(coded, 3, ref.serviceName());
        writeString(coded, 4, ref.runtimeId());
        writeString(coded, 5, ref.registryContext());
        for (String interfaceName : ref.interfaces()) {
            writeString(coded, 6, interfaceName);
        }
        writeString(coded, 7, ref.transport());
        writeString(coded, 8, ref.endpoint());
        writeInt64(coded, 9, ref.leaseUntil());
        writeInt64(coded, 10, ref.generation());
        writeStringMap(coded, 11, ref.metadata());
        coded.flush();
        return output.toByteArray();
    }

    private static ObjectRef decodeObjectRef(final byte[] bytes) throws IOException {
        CodedInputStream coded = CodedInputStream.newInstance(bytes);
        String objectId = "";
        String logicalName = "";
        String serviceName = "";
        String runtimeId = "";
        String registryContext = "default";
        List<String> interfaces = new ArrayList<>();
        String transport = "";
        String endpoint = "";
        long leaseUntil = 0L;
        long generation = 0L;
        Map<String, String> metadata = new LinkedHashMap<>();
        int tag;
        while ((tag = coded.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (field == 1) {
                objectId = coded.readStringRequireUtf8();
            } else if (field == 2) {
                logicalName = coded.readStringRequireUtf8();
            } else if (field == 3) {
                serviceName = coded.readStringRequireUtf8();
            } else if (field == 4) {
                runtimeId = coded.readStringRequireUtf8();
            } else if (field == 5) {
                registryContext = coded.readStringRequireUtf8();
            } else if (field == 6) {
                interfaces.add(coded.readStringRequireUtf8());
            } else if (field == 7) {
                transport = coded.readStringRequireUtf8();
            } else if (field == 8) {
                endpoint = coded.readStringRequireUtf8();
            } else if (field == 9) {
                leaseUntil = coded.readInt64();
            } else if (field == 10) {
                generation = coded.readInt64();
            } else if (field == 11) {
                metadata.putAll(readStringMapEntry(coded));
            } else {
                coded.skipField(tag);
            }
        }
        return new ObjectRef(objectId, logicalName, serviceName, runtimeId, registryContext, interfaces, transport, endpoint, leaseUntil, generation, metadata);
    }

    private static byte[] encodeMethod(final MethodDescriptor method) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(128);
        CodedOutputStream coded = CodedOutputStream.newInstance(output);
        writeString(coded, 1, method.declaringInterfaceName());
        writeString(coded, 2, method.methodName());
        for (String parameterTypeName : method.parameterTypeNames()) {
            writeString(coded, 3, parameterTypeName);
        }
        writeString(coded, 4, method.returnTypeName());
        writeString(coded, 5, method.signatureHash());
        coded.flush();
        return output.toByteArray();
    }

    private static MethodDescriptor decodeMethod(final byte[] bytes) throws IOException {
        CodedInputStream coded = CodedInputStream.newInstance(bytes);
        String declaring = "";
        String methodName = "";
        List<String> params = new ArrayList<>();
        String returnType = "void";
        String hash = "";
        int tag;
        while ((tag = coded.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (field == 1) {
                declaring = coded.readStringRequireUtf8();
            } else if (field == 2) {
                methodName = coded.readStringRequireUtf8();
            } else if (field == 3) {
                params.add(coded.readStringRequireUtf8());
            } else if (field == 4) {
                returnType = coded.readStringRequireUtf8();
            } else if (field == 5) {
                hash = coded.readStringRequireUtf8();
            } else {
                coded.skipField(tag);
            }
        }
        return new MethodDescriptor(declaring, methodName, params, returnType, hash);
    }

    static byte[] encodeValue(final EncodedValue value) {
        try {
            validateEncodedValue(value, 0, new int[] {0});
            ByteArrayOutputStream output = new ByteArrayOutputStream(128);
            CodedOutputStream coded = CodedOutputStream.newInstance(output);
            writeString(coded, 1, value.kind());
            writeString(coded, 2, value.typeName());
            Object raw = value.value();
            if (raw instanceof ObjectRef ref) {
                writeMessage(coded, 8, encodeObjectRef(ref));
            } else if (raw instanceof String text) {
                writeString(coded, 3, text);
            } else if (raw instanceof byte[] bytes) {
                coded.writeBytes(4, ByteString.copyFrom(bytes));
            } else if (raw instanceof Boolean bool) {
                coded.writeBool(5, bool.booleanValue());
            } else if (raw instanceof Float number) {
                coded.writeDouble(7, number.doubleValue());
            } else if (raw instanceof Double number) {
                coded.writeDouble(7, number.doubleValue());
            } else if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
                coded.writeInt64(6, ((Number) raw).longValue());
            } else if (raw instanceof Character character) {
                writeString(coded, 3, character.toString());
            } else if (raw instanceof List<?> list) {
                for (Object item : list) {
                    writeMessage(coded, 9, encodeValue(encodedForWire(item)));
                }
            } else if (raw instanceof Map<?, ?> map) {
                if ("exception".equals(value.kind())) {
                    writeStringMap(coded, 11, stringMap(map));
                } else {
                    writeValueMap(coded, 10, map);
                }
            } else if (raw != null) {
                throw new IllegalArgumentException("unsupported grpc wire value payload");
            }
            writeStringMap(coded, 12, value.metadata());
            coded.flush();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static EncodedValue decodeValue(final byte[] bytes) {
        try {
            checkLength(bytes.length, MAX_VALUE_BYTES, "encoded value");
            CodedInputStream coded = CodedInputStream.newInstance(bytes);
            String kind = "null";
            String typeName = "java.lang.Object";
            String stringValue = null;
            byte[] bytesValue = null;
            Boolean boolValue = null;
            Long longValue = null;
            Double doubleValue = null;
            ObjectRef objectRef = null;
            List<Object> list = new ArrayList<>();
            Map<String, Object> map = new LinkedHashMap<>();
            Map<String, String> stringMap = new LinkedHashMap<>();
            Map<String, String> metadata = new LinkedHashMap<>();
            int metadataEntries = 0;
            int tag;
            while ((tag = coded.readTag()) != 0) {
                int field = WireFormat.getTagFieldNumber(tag);
                if (field == 1) {
                    kind = coded.readStringRequireUtf8();
                } else if (field == 2) {
                    typeName = coded.readStringRequireUtf8();
                } else if (field == 3) {
                    stringValue = coded.readStringRequireUtf8();
                    checkString(stringValue);
                } else if (field == 4) {
                    bytesValue = coded.readBytes().toByteArray();
                    checkLength(bytesValue.length, MAX_VALUE_BYTES, "byte value");
                } else if (field == 5) {
                    boolValue = Boolean.valueOf(coded.readBool());
                } else if (field == 6) {
                    longValue = Long.valueOf(coded.readInt64());
                } else if (field == 7) {
                    doubleValue = Double.valueOf(coded.readDouble());
                } else if (field == 8) {
                    objectRef = decodeObjectRef(readBytes(coded));
                } else if (field == 9) {
                    checkContainerGrowth(list.size() + 1);
                    list.add(decodeValue(readBytes(coded)));
                } else if (field == 10) {
                    checkContainerGrowth(map.size() + 1);
                    Map.Entry<String, EncodedValue> entry = readValueMapEntry(coded);
                    map.put(entry.getKey(), entry.getValue());
                } else if (field == 11) {
                    if (stringMap.size() >= MAX_METADATA_ENTRIES) {
                        throw new IOException("string metadata entry count exceeds limit");
                    }
                    stringMap.putAll(readStringMapEntry(coded));
                } else if (field == 12) {
                    metadataEntries++;
                    if (metadataEntries > MAX_METADATA_ENTRIES) {
                        throw new IOException("encoded value metadata entry count exceeds limit");
                    }
                    metadata.putAll(readStringMapEntry(coded));
                } else {
                    coded.skipField(tag);
                }
            }
            Object raw = rawValue(kind, typeName, stringValue, bytesValue, boolValue, longValue, doubleValue, objectRef, list, map, stringMap);
            EncodedValue decoded = new EncodedValue(kind, typeName, raw, Map.copyOf(metadata));
            validateEncodedValue(decoded, 0, new int[] {0});
            return decoded;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Object rawValue(
            final String kind,
            final String typeName,
            final String stringValue,
            final byte[] bytesValue,
            final Boolean boolValue,
            final Long longValue,
            final Double doubleValue,
            final ObjectRef objectRef,
            final List<Object> list,
            final Map<String, Object> map,
            final Map<String, String> stringMap) {
        if ("null".equals(kind)) {
            return null;
        }
        if ("object-ref".equals(kind)) {
            return objectRef;
        }
        if ("bytes".equals(kind)) {
            return bytesValue == null ? new byte[0] : bytesValue.clone();
        }
        if ("list".equals(kind)) {
            return List.copyOf(list);
        }
        if ("map".equals(kind) || "record".equals(kind) || "instant".equals(kind) || "trusted-dto".equals(kind) || "registered-schema".equals(kind)) {
            return Map.copyOf(map);
        }
        if ("exception".equals(kind)) {
            return Map.copyOf(stringMap);
        }
        if (boolValue != null) {
            return boolValue;
        }
        if (longValue != null) {
            return numberValue(typeName, longValue.longValue());
        }
        if (doubleValue != null) {
            return doubleValue(typeName, doubleValue.doubleValue());
        }
        return stringValue == null ? "" : stringValue;
    }

    private static Object numberValue(final String typeName, final long value) {
        return switch (typeName) {
            case "byte", "java.lang.Byte" -> Byte.valueOf((byte) value);
            case "short", "java.lang.Short" -> Short.valueOf((short) value);
            case "int", "java.lang.Integer" -> Integer.valueOf((int) value);
            default -> Long.valueOf(value);
        };
    }

    private static Object doubleValue(final String typeName, final double value) {
        if ("float".equals(typeName) || "java.lang.Float".equals(typeName)) {
            return Float.valueOf((float) value);
        }
        return Double.valueOf(value);
    }

    private static byte[] encodeRemoteError(final RemoteError error) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(128);
        CodedOutputStream coded = CodedOutputStream.newInstance(output);
        writeString(coded, 1, error.code().name());
        writeString(coded, 2, error.message());
        writeStringMap(coded, 3, error.details());
        writeString(coded, 4, error.invocationId());
        writeString(coded, 5, error.remoteRuntimeId());
        coded.writeBool(6, error.retryable());
        coded.writeBool(7, error.dirtyTarget());
        coded.flush();
        return output.toByteArray();
    }

    private static RemoteError decodeRemoteError(final byte[] bytes) throws IOException {
        CodedInputStream coded = CodedInputStream.newInstance(bytes);
        MiddlewareErrorCode code = MiddlewareErrorCode.INTERNAL;
        String message = "";
        Map<String, String> details = new LinkedHashMap<>();
        String invocationId = null;
        String runtimeId = null;
        boolean retryable = false;
        boolean dirty = false;
        int tag;
        while ((tag = coded.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (field == 1) {
                code = MiddlewareErrorCode.valueOf(coded.readStringRequireUtf8());
            } else if (field == 2) {
                message = coded.readStringRequireUtf8();
            } else if (field == 3) {
                details.putAll(readStringMapEntry(coded));
            } else if (field == 4) {
                invocationId = coded.readStringRequireUtf8();
            } else if (field == 5) {
                runtimeId = coded.readStringRequireUtf8();
            } else if (field == 6) {
                retryable = coded.readBool();
            } else if (field == 7) {
                dirty = coded.readBool();
            } else {
                coded.skipField(tag);
            }
        }
        return new RemoteError(code, message, details, invocationId, runtimeId, retryable, dirty);
    }

    private static void writeString(final CodedOutputStream coded, final int field, final String value) throws IOException {
        if (value != null && !value.isEmpty()) {
            coded.writeString(field, value);
        }
    }

    private static void writeInt64(final CodedOutputStream coded, final int field, final long value) throws IOException {
        if (value != 0L) {
            coded.writeInt64(field, value);
        }
    }

    private static void writeMessage(final CodedOutputStream coded, final int field, final byte[] message) throws IOException {
        coded.writeByteArray(field, message);
    }

    private static byte[] readBytes(final CodedInputStream coded) throws IOException {
        byte[] bytes = coded.readBytes().toByteArray();
        checkLength(bytes.length, MAX_VALUE_BYTES, "nested message");
        return bytes;
    }

    private static void writeStringMap(final CodedOutputStream coded, final int field, final Map<String, String> map) throws IOException {
        validateStringMap(map);
        for (Map.Entry<String, String> entry : map.entrySet()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(64);
            CodedOutputStream entryCoded = CodedOutputStream.newInstance(output);
            writeString(entryCoded, 1, entry.getKey());
            writeString(entryCoded, 2, entry.getValue());
            entryCoded.flush();
            writeMessage(coded, field, output.toByteArray());
        }
    }

    private static Map<String, String> readStringMapEntry(final CodedInputStream coded) throws IOException {
        CodedInputStream entryCoded = CodedInputStream.newInstance(readBytes(coded));
        String key = "";
        String value = "";
        int tag;
        while ((tag = entryCoded.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (field == 1) {
                key = entryCoded.readStringRequireUtf8();
                checkString(key);
            } else if (field == 2) {
                value = entryCoded.readStringRequireUtf8();
                checkString(value);
            } else {
                entryCoded.skipField(tag);
            }
        }
        return key.isEmpty() ? Map.of() : Map.of(key, value);
    }

    private static void writeValueMap(final CodedOutputStream coded, final int field, final Map<?, ?> map) throws IOException {
        checkContainerGrowth(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IOException("value map key must be a string");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(128);
            CodedOutputStream entryCoded = CodedOutputStream.newInstance(output);
            writeString(entryCoded, 1, (String) entry.getKey());
            Object value = entry.getValue();
            writeMessage(entryCoded, 2, encodeValue(encodedForWire(value)));
            entryCoded.flush();
            writeMessage(coded, field, output.toByteArray());
        }
    }

    private static Map.Entry<String, EncodedValue> readValueMapEntry(final CodedInputStream coded) throws IOException {
        CodedInputStream entryCoded = CodedInputStream.newInstance(readBytes(coded));
        String key = "";
        EncodedValue value = new EncodedValue("null", "java.lang.Object", null, Map.of());
        int tag;
        while ((tag = entryCoded.readTag()) != 0) {
            int field = WireFormat.getTagFieldNumber(tag);
            if (field == 1) {
                key = entryCoded.readStringRequireUtf8();
                checkString(key);
            } else if (field == 2) {
                value = decodeValue(readBytes(entryCoded));
            } else {
                entryCoded.skipField(tag);
            }
        }
        return Map.entry(key, value);
    }

    private static Map<String, String> stringMap(final Map<?, ?> map) {
        checkContainerGrowth(map.size());
        Map<String, String> strings = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException("string map requires string keys and values");
            }
            strings.put(key, value);
        }
        return strings;
    }

    private static String kindFor(final Object item) {
        if (item == null) {
            return "null";
        }
        if (item instanceof EncodedValue encodedValue) {
            return encodedValue.kind();
        }
        if (item instanceof ObjectRef) {
            return "object-ref";
        }
        if (item instanceof byte[]) {
            return "bytes";
        }
        if (item instanceof List<?>) {
            return "list";
        }
        if (item instanceof Map<?, ?>) {
            return "map";
        }
        return "scalar";
    }

    private static EncodedValue encodedForWire(final Object item) {
        if (item instanceof EncodedValue encodedValue) {
            return encodedValue;
        }
        return new EncodedValue(kindFor(item), item == null ? "java.lang.Object" : item.getClass().getName(), item, Map.of());
    }

    static InputStream inputStream(final byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private static void validateEncodedValue(final EncodedValue value, final int depth, final int[] nodes) {
        if (value == null) {
            return;
        }
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("grpc encoded value depth exceeds limit");
        }
        nodes[0]++;
        if (nodes[0] > MAX_NODES) {
            throw new IllegalArgumentException("grpc encoded value node count exceeds limit");
        }
        checkString(value.kind());
        checkString(value.typeName());
        validateStringMap(value.metadata());
        validatePayload(value.value(), depth, nodes);
    }

    private static void validatePayload(final Object raw, final int depth, final int[] nodes) {
        if (raw == null || raw instanceof Number || raw instanceof Boolean || raw instanceof Character) {
            return;
        }
        if (raw instanceof String text) {
            checkString(text);
            return;
        }
        if (raw instanceof byte[] bytes) {
            checkLength(bytes.length, MAX_VALUE_BYTES, "byte value");
            return;
        }
        if (raw instanceof ObjectRef ref) {
            checkString(ref.objectId());
            checkString(ref.logicalName());
            checkString(ref.serviceName());
            checkString(ref.runtimeId());
            checkString(ref.registryContext());
            checkString(ref.transport());
            checkString(ref.endpoint());
            checkContainerGrowth(ref.interfaces().size());
            validateStringMap(ref.metadata());
            return;
        }
        if (raw instanceof EncodedValue encodedValue) {
            validateEncodedValue(encodedValue, depth + 1, nodes);
            return;
        }
        if (raw instanceof List<?> list) {
            checkContainerGrowth(list.size());
            for (Object item : list) {
                validatePayload(item, depth + 1, nodes);
            }
            return;
        }
        if (raw instanceof Map<?, ?> map) {
            checkContainerGrowth(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("grpc encoded map key must be a string");
                }
                checkString(key);
                validatePayload(entry.getValue(), depth + 1, nodes);
            }
            return;
        }
        throw new IllegalArgumentException("grpc encoded value contains unsupported payload");
    }

    private static void validateStringMap(final Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        if (map.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("metadata entry count exceeds limit");
        }
        int total = 0;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            checkString(entry.getKey());
            checkString(entry.getValue());
            total += utf8Bytes(entry.getKey()) + utf8Bytes(entry.getValue());
            if (total > MAX_METADATA_BYTES) {
                throw new IllegalArgumentException("metadata byte size exceeds limit");
            }
        }
    }

    private static void checkContainerGrowth(final int size) {
        if (size > MAX_CONTAINER_ENTRIES) {
            throw new IllegalArgumentException("container entry count exceeds limit");
        }
    }

    private static void checkLength(final int length, final int limit, final String label) {
        if (length > limit) {
            throw new IllegalArgumentException(label + " exceeds limit");
        }
    }

    private static void checkString(final String value) {
        if (utf8Bytes(value) > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("string value exceeds limit");
        }
    }

    private static int utf8Bytes(final String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x7f) {
                total++;
            } else if (character <= 0x7ff) {
                total += 2;
            } else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                total += 4;
                index++;
            } else {
                total += 3;
            }
            if (total > MAX_STRING_BYTES) {
                return total;
            }
        }
        return total;
    }
}
