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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
 * Primitive-only binary envelope for the RMI transport.
 *
 * <p>The codec intentionally uses only strict UTF-8 strings, primitive values,
 * and byte arrays. It is decoded after the RMI boundary has accepted a
 * {@code byte[]}, so no application DTO, {@link EncodedValue}, {@link ObjectRef},
 * or collection graph is deserialized by RMI itself.</p>
 */
final class RmiWireCodec {
    private static final int MAX_DEPTH = 16;
    private static final int MAX_NODES = 4096;
    private static final int MAX_CONTAINER_ENTRIES = 1024;
    private static final int MAX_STRING_BYTES = 64 * 1024;
    private static final int MAX_VALUE_BYTES = 1024 * 1024;
    private static final int MAX_METADATA_ENTRIES = 32;
    private static final int MAX_METADATA_BYTES = 8 * 1024;
    private static final byte VALUE_NULL = 0;
    private static final byte VALUE_STRING = 1;
    private static final byte VALUE_BYTES = 2;
    private static final byte VALUE_BOOLEAN = 3;
    private static final byte VALUE_LONG = 4;
    private static final byte VALUE_DOUBLE = 5;
    private static final byte VALUE_CHAR = 6;
    private static final byte VALUE_OBJECT_REF = 7;
    private static final byte VALUE_LIST = 8;
    private static final byte VALUE_MAP = 9;

    private RmiWireCodec() {
    }

    static byte[] encodeInvokeRequest(final RmiWire.InvokeRequest request) {
        return write(output -> {
            writeObjectRef(output, request.target());
            writeMethod(output, request.method());
            writeValues(output, request.arguments());
            writeInvocationOptions(output, request.options());
        });
    }

    static RmiWire.InvokeRequest decodeInvokeRequest(final byte[] bytes) {
        return read(bytes, input -> new RmiWire.InvokeRequest(readObjectRef(input), readMethod(input), readValues(input),
                readInvocationOptions(input)));
    }

    static byte[] encodeInvokeResponse(final RmiWire.InvokeResponse response) {
        return write(output -> {
            output.writeBoolean(response.success());
            output.writeBoolean(response.returnValue() != null);
            if (response.returnValue() != null) {
                writeValue(output, response.returnValue(), 0, new int[] {0});
            }
            output.writeBoolean(response.error() != null);
            if (response.error() != null) {
                writeRemoteError(output, response.error());
            }
        });
    }

    static RmiWire.InvokeResponse decodeInvokeResponse(final byte[] bytes) {
        return read(bytes, input -> {
            boolean success = input.readBoolean();
            EncodedValue value = input.readBoolean() ? readValue(input, 0, new int[] {0}) : null;
            RemoteError error = input.readBoolean() ? readRemoteError(input) : null;
            return new RmiWire.InvokeResponse(success, value, error);
        });
    }

    static byte[] encodeOpenRequest(final RmiWire.OpenRequest request) {
        return write(output -> {
            writeObjectRef(output, request.target());
            writeString(output, request.interfaceName());
        });
    }

    static RmiWire.OpenRequest decodeOpenRequest(final byte[] bytes) {
        return read(bytes, input -> new RmiWire.OpenRequest(readObjectRef(input), readString(input)));
    }

    static byte[] encodeOpenResponse(final RmiWire.OpenResponse response) {
        return write(output -> {
            output.writeBoolean(response.success());
            output.writeBoolean(response.error() != null);
            if (response.error() != null) {
                writeRemoteError(output, response.error());
            }
        });
    }

    static RmiWire.OpenResponse decodeOpenResponse(final byte[] bytes) {
        return read(bytes, input -> new RmiWire.OpenResponse(input.readBoolean(), input.readBoolean() ? readRemoteError(input) : null));
    }

    static byte[] encodeDescribeRequest(final RmiWire.DescribeRequest request) {
        return write(output -> writeObjectRef(output, request.target()));
    }

    static RmiWire.DescribeRequest decodeDescribeRequest(final byte[] bytes) {
        return read(bytes, input -> new RmiWire.DescribeRequest(readObjectRef(input)));
    }

    static byte[] encodeDescribeResponse(final RmiWire.DescribeResponse response) {
        return write(output -> {
            output.writeBoolean(response.success());
            writeStrings(output, response.interfaces());
            output.writeBoolean(response.error() != null);
            if (response.error() != null) {
                writeRemoteError(output, response.error());
            }
        });
    }

    static RmiWire.DescribeResponse decodeDescribeResponse(final byte[] bytes) {
        return read(bytes, input -> new RmiWire.DescribeResponse(input.readBoolean(), readStrings(input),
                input.readBoolean() ? readRemoteError(input) : null));
    }

    static byte[] encodeStreamRequest(final RmiWire.StreamRequest request) {
        return write(output -> {
            writeObjectRef(output, request.target());
            writeMethod(output, request.method());
            writeValues(output, request.items());
            writeInvocationOptions(output, request.options());
            output.writeBoolean(request.complete());
        });
    }

    static RmiWire.StreamRequest decodeStreamRequest(final byte[] bytes) {
        return read(bytes, input -> new RmiWire.StreamRequest(readObjectRef(input), readMethod(input), readValues(input),
                readInvocationOptions(input), input.readBoolean()));
    }

    static byte[] encodeStreamResponse(final RmiWire.StreamResponse response) {
        return write(output -> {
            output.writeBoolean(response.success());
            output.writeBoolean(response.value() != null);
            if (response.value() != null) {
                writeValue(output, response.value(), 0, new int[] {0});
            }
            output.writeBoolean(response.error() != null);
            if (response.error() != null) {
                writeRemoteError(output, response.error());
            }
            output.writeBoolean(response.complete());
            output.writeLong(response.sequence());
        });
    }

    static RmiWire.StreamResponse decodeStreamResponse(final byte[] bytes) {
        return read(bytes, input -> {
            boolean success = input.readBoolean();
            EncodedValue value = input.readBoolean() ? readValue(input, 0, new int[] {0}) : null;
            RemoteError error = input.readBoolean() ? readRemoteError(input) : null;
            boolean complete = input.readBoolean();
            long sequence = input.readLong();
            return new RmiWire.StreamResponse(success, value, error, complete, sequence);
        });
    }

    static byte[] encodeStreamResponses(final List<RmiWire.StreamResponse> responses) {
        return write(output -> {
            writeCount(output, responses.size());
            for (RmiWire.StreamResponse response : responses) {
                writeBytes(output, encodeStreamResponse(response));
            }
        });
    }

    static List<RmiWire.StreamResponse> decodeStreamResponses(final byte[] bytes) {
        return read(bytes, input -> {
            int count = readCount(input);
            List<RmiWire.StreamResponse> responses = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                responses.add(decodeStreamResponse(readBytes(input)));
            }
            return List.copyOf(responses);
        });
    }

    static byte[] encodeStreamRequests(final List<RmiWire.StreamRequest> requests) {
        return write(output -> {
            writeCount(output, requests.size());
            for (RmiWire.StreamRequest request : requests) {
                writeBytes(output, encodeStreamRequest(request));
            }
        });
    }

    static List<RmiWire.StreamRequest> decodeStreamRequests(final byte[] bytes) {
        return read(bytes, input -> {
            int count = readCount(input);
            List<RmiWire.StreamRequest> requests = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                requests.add(decodeStreamRequest(readBytes(input)));
            }
            return List.copyOf(requests);
        });
    }

    private static void writeValue(final DataOutputStream output, final EncodedValue value, final int depth, final int[] nodes) throws IOException {
        checkDepthAndNode(depth, nodes);
        writeString(output, value.kind());
        writeString(output, value.typeName());
        writeStringMap(output, value.metadata());
        Object raw = value.value();
        if (raw == null) {
            output.writeByte(VALUE_NULL);
        } else if (raw instanceof String text) {
            output.writeByte(VALUE_STRING);
            writeString(output, text);
        } else if (raw instanceof byte[] bytes) {
            output.writeByte(VALUE_BYTES);
            writeBytes(output, bytes);
        } else if (raw instanceof Boolean bool) {
            output.writeByte(VALUE_BOOLEAN);
            output.writeBoolean(bool.booleanValue());
        } else if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
            output.writeByte(VALUE_LONG);
            output.writeLong(((Number) raw).longValue());
        } else if (raw instanceof Float || raw instanceof Double) {
            output.writeByte(VALUE_DOUBLE);
            output.writeDouble(((Number) raw).doubleValue());
        } else if (raw instanceof Character character) {
            output.writeByte(VALUE_CHAR);
            output.writeChar(character.charValue());
        } else if (raw instanceof ObjectRef ref) {
            output.writeByte(VALUE_OBJECT_REF);
            writeObjectRef(output, ref);
        } else if (raw instanceof List<?> list) {
            output.writeByte(VALUE_LIST);
            writeCount(output, list.size());
            for (Object item : list) {
                writeValue(output, encodedForWire(item), depth + 1, nodes);
            }
        } else if (raw instanceof Map<?, ?> map) {
            output.writeByte(VALUE_MAP);
            writeCount(output, map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IOException("encoded map key must be a string");
                }
                writeString(output, key);
                writeValue(output, encodedForWire(entry.getValue()), depth + 1, nodes);
            }
        } else {
            throw new IOException("unsupported RMI wire value payload");
        }
    }

    private static EncodedValue readValue(final DataInputStream input, final int depth, final int[] nodes) throws IOException {
        checkDepthAndNode(depth, nodes);
        String kind = readString(input);
        String typeName = readString(input);
        Map<String, String> metadata = readStringMap(input);
        byte type = input.readByte();
        Object raw = switch (type) {
            case VALUE_NULL -> null;
            case VALUE_STRING -> readString(input);
            case VALUE_BYTES -> readBytes(input);
            case VALUE_BOOLEAN -> Boolean.valueOf(input.readBoolean());
            case VALUE_LONG -> numberValue(typeName, input.readLong());
            case VALUE_DOUBLE -> doubleValue(typeName, input.readDouble());
            case VALUE_CHAR -> Character.valueOf(input.readChar());
            case VALUE_OBJECT_REF -> readObjectRef(input);
            case VALUE_LIST -> readList(input, depth, nodes);
            case VALUE_MAP -> readMap(input, depth, nodes);
            default -> throw new IOException("unknown RMI wire value payload");
        };
        return new EncodedValue(kind, typeName, raw, metadata);
    }

    private static List<Object> readList(final DataInputStream input, final int depth, final int[] nodes) throws IOException {
        int count = readCount(input);
        List<Object> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(readValue(input, depth + 1, nodes));
        }
        return List.copyOf(values);
    }

    private static Map<String, Object> readMap(final DataInputStream input, final int depth, final int[] nodes) throws IOException {
        int count = readCount(input);
        Map<String, Object> values = new LinkedHashMap<>(count);
        for (int index = 0; index < count; index++) {
            values.put(readString(input), readValue(input, depth + 1, nodes));
        }
        return Map.copyOf(values);
    }

    private static void writeObjectRef(final DataOutputStream output, final ObjectRef ref) throws IOException {
        writeString(output, ref.objectId());
        writeString(output, ref.logicalName());
        writeString(output, ref.serviceName());
        writeString(output, ref.runtimeId());
        writeString(output, ref.registryContext());
        writeStrings(output, ref.interfaces());
        writeString(output, ref.transport());
        writeString(output, ref.endpoint());
        output.writeLong(ref.leaseUntil());
        output.writeLong(ref.generation());
        writeStringMap(output, ref.metadata());
    }

    private static ObjectRef readObjectRef(final DataInputStream input) throws IOException {
        String objectId = readString(input);
        String logicalName = readString(input);
        String serviceName = readString(input);
        String runtimeId = readString(input);
        String registryContext = readString(input);
        List<String> interfaces = readStrings(input);
        String transport = readString(input);
        String endpoint = readString(input);
        long leaseUntil = input.readLong();
        long generation = input.readLong();
        Map<String, String> metadata = readStringMap(input);
        return new ObjectRef(objectId, logicalName, serviceName, runtimeId, registryContext, interfaces, transport, endpoint, leaseUntil, generation, metadata);
    }

    private static void writeMethod(final DataOutputStream output, final MethodDescriptor method) throws IOException {
        writeString(output, method.declaringInterfaceName());
        writeString(output, method.methodName());
        writeStrings(output, method.parameterTypeNames());
        writeString(output, method.returnTypeName());
        writeString(output, method.signatureHash());
    }

    private static MethodDescriptor readMethod(final DataInputStream input) throws IOException {
        return new MethodDescriptor(readString(input), readString(input), readStrings(input), readString(input), readString(input));
    }

    private static void writeInvocationOptions(final DataOutputStream output, final InvocationOptions options) throws IOException {
        InvocationOptions safeOptions = options == null ? InvocationOptions.defaults() : options;
        writeString(output, safeOptions.invocationId());
        output.writeLong(safeOptions.deadline().toMillis());
        writeStringMap(output, safeOptions.metadata());
    }

    private static InvocationOptions readInvocationOptions(final DataInputStream input) throws IOException {
        return new InvocationOptions(readString(input), Duration.ofMillis(input.readLong()), readStringMap(input));
    }

    private static void writeRemoteError(final DataOutputStream output, final RemoteError error) throws IOException {
        writeString(output, error.code().name());
        writeString(output, error.message());
        writeStringMap(output, error.details());
        writeString(output, error.invocationId());
        writeString(output, error.remoteRuntimeId());
        output.writeBoolean(error.retryable());
        output.writeBoolean(error.dirtyTarget());
    }

    private static RemoteError readRemoteError(final DataInputStream input) throws IOException {
        return new RemoteError(MiddlewareErrorCode.valueOf(readString(input)), readString(input), readStringMap(input),
                readString(input), readString(input), input.readBoolean(), input.readBoolean());
    }

    private static void writeValues(final DataOutputStream output, final List<EncodedValue> values) throws IOException {
        List<EncodedValue> safeValues = values == null ? List.of() : values;
        writeCount(output, safeValues.size());
        for (EncodedValue value : safeValues) {
            writeValue(output, value, 0, new int[] {0});
        }
    }

    private static List<EncodedValue> readValues(final DataInputStream input) throws IOException {
        int count = readCount(input);
        List<EncodedValue> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(readValue(input, 0, new int[] {0}));
        }
        return List.copyOf(values);
    }

    private static void writeStrings(final DataOutputStream output, final List<String> values) throws IOException {
        List<String> safeValues = values == null ? List.of() : values;
        writeCount(output, safeValues.size());
        for (String value : safeValues) {
            writeString(output, value);
        }
    }

    private static List<String> readStrings(final DataInputStream input) throws IOException {
        int count = readCount(input);
        List<String> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(readString(input));
        }
        return List.copyOf(values);
    }

    private static void writeStringMap(final DataOutputStream output, final Map<String, String> map) throws IOException {
        Map<String, String> safeMap = map == null ? Map.of() : map;
        if (safeMap.size() > MAX_METADATA_ENTRIES) {
            throw new IOException("metadata entry count exceeds limit");
        }
        int totalBytes = 0;
        writeCount(output, safeMap.size());
        for (Map.Entry<String, String> entry : safeMap.entrySet()) {
            totalBytes += utf8Bytes(entry.getKey()) + utf8Bytes(entry.getValue());
            if (totalBytes > MAX_METADATA_BYTES) {
                throw new IOException("metadata byte size exceeds limit");
            }
            writeString(output, entry.getKey());
            writeString(output, entry.getValue());
        }
    }

    private static Map<String, String> readStringMap(final DataInputStream input) throws IOException {
        int count = readCount(input);
        if (count > MAX_METADATA_ENTRIES) {
            throw new IOException("metadata entry count exceeds limit");
        }
        Map<String, String> values = new LinkedHashMap<>(count);
        int totalBytes = 0;
        for (int index = 0; index < count; index++) {
            String key = readString(input);
            String value = readString(input);
            totalBytes += utf8Bytes(key) + utf8Bytes(value);
            if (totalBytes > MAX_METADATA_BYTES) {
                throw new IOException("metadata byte size exceeds limit");
            }
            values.put(key, value);
        }
        return Map.copyOf(values);
    }

    private static void writeString(final DataOutputStream output, final String value) throws IOException {
        String safeValue = value == null ? "" : value;
        byte[] bytes = safeValue.getBytes(StandardCharsets.UTF_8);
        writeLength(output, bytes.length, MAX_STRING_BYTES);
        output.write(bytes);
    }

    private static String readString(final DataInputStream input) throws IOException {
        byte[] bytes = readBytes(input, MAX_STRING_BYTES);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("malformed UTF-8 string in RMI wire envelope", exception);
        }
    }

    private static void writeBytes(final DataOutputStream output, final byte[] bytes) throws IOException {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        writeLength(output, safeBytes.length, MAX_VALUE_BYTES);
        output.write(safeBytes);
    }

    private static byte[] readBytes(final DataInputStream input) throws IOException {
        return readBytes(input, MAX_VALUE_BYTES);
    }

    private static byte[] readBytes(final DataInputStream input, final int limit) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > limit) {
            throw new IOException("byte field length exceeds limit");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static void writeCount(final DataOutputStream output, final int count) throws IOException {
        if (count < 0 || count > MAX_CONTAINER_ENTRIES) {
            throw new IOException("container entry count exceeds limit");
        }
        output.writeInt(count);
    }

    private static int readCount(final DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_CONTAINER_ENTRIES) {
            throw new IOException("container entry count exceeds limit");
        }
        return count;
    }

    private static void writeLength(final DataOutputStream output, final int length, final int limit) throws IOException {
        if (length < 0 || length > limit) {
            throw new IOException("byte field length exceeds limit");
        }
        output.writeInt(length);
    }

    private static void checkDepthAndNode(final int depth, final int[] nodes) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("encoded value depth exceeds limit");
        }
        nodes[0]++;
        if (nodes[0] > MAX_NODES) {
            throw new IOException("encoded value node count exceeds limit");
        }
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

    private static EncodedValue encodedForWire(final Object item) throws IOException {
        if (item instanceof EncodedValue encodedValue) {
            return encodedValue;
        }
        String kind;
        if (item == null) {
            kind = "null";
        } else if (item instanceof ObjectRef) {
            kind = "object-ref";
        } else if (item instanceof byte[]) {
            kind = "bytes";
        } else if (item instanceof List<?>) {
            kind = "list";
        } else if (item instanceof Map<?, ?>) {
            kind = "map";
        } else if (item instanceof String || item instanceof Number || item instanceof Boolean || item instanceof Character) {
            kind = "scalar";
        } else {
            throw new IOException("unsupported RMI wire nested payload");
        }
        return new EncodedValue(kind, item == null ? "java.lang.Object" : item.getClass().getName(), item, Map.of());
    }

    private static byte[] write(final Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
            DataOutputStream output = new DataOutputStream(bytes);
            writer.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static <T> T read(final byte[] bytes, final Reader<T> reader) {
        if (bytes == null || bytes.length > MAX_VALUE_BYTES) {
            throw new UncheckedIOException(new IOException("RMI wire envelope exceeds limit"));
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            return reader.read(input);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static int utf8Bytes(final String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(DataInputStream input) throws IOException;
    }
}
