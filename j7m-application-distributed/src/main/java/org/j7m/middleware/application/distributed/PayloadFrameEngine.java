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

package org.j7m.middleware.application.distributed;

import java.io.*;
import java.math.*;
import java.nio.file.Path;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.ManagedServiceContract;
import org.j7m.middleware.codec.api.*;

/**
 * Deterministic, versioned data-only frames for durable invocation fingerprints and replay.
 * The borrowed codec and explicitly registered schema functions must be thread-safe and free of
 * external effects. Decoding/encoding can execute those trusted functions; it is not a sandbox.
 * References, callbacks, exceptions, arbitrary objects and control capabilities are never frames.
 * There is no Java object serialization or class loading from a frame-provided name.
 */
final class PayloadFrameEngine {
    /**
     * Internal upper bound for encoded state; public durable outcomes retain their smaller facade cap.
     */
    static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    private static final int MAGIC = 0x4a374d50, HASH_MAGIC = 0x4a374d48, VERSION = 1, MAX_NODES = 1024, MAX_DEPTH = 16;
    private static final List<String> KINDS = List.of("null", "scalar", "bytes", "enum", "instant", "path", "list", "map", "registered-schema");
    private static final Map<Class<?>, Class<?>> BOXES = Map.of(boolean.class, Boolean.class, byte.class, Byte.class,
            short.class, Short.class, int.class, Integer.class, long.class, Long.class, float.class, Float.class,
            double.class, Double.class, char.class, Character.class, void.class, Void.class);
    private static final List<Class<?>> SCALARS = List.of(String.class, Boolean.class, Character.class, Byte.class, Short.class,
            Integer.class, Long.class, Float.class, Double.class, BigInteger.class, BigDecimal.class);
    private static final CodecContext DATA_ONLY = new CodecContext() {
        @Override public Optional<ObjectRef> referenceFor(Object value, Class<?> type) { return Optional.empty(); }
        @Override public Object proxyFor(ObjectRef reference, Class<?> type) { throw invalid(); }
    };
    private final ValueCodec codec;
    private final SchemaRegistry schemas;
    private final int maximumBytes;

    /**
     * Creates a data frame codec with the native maximum frame size.
     * @param codec borrowed thread-safe value codec; its schema functions must have no external effects
     * @param schemas explicit allowlist, including any supported previous wire versions
     * @throws NullPointerException if a collaborator is null
     */
    public PayloadFrameEngine(ValueCodec codec, SchemaRegistry schemas) {
        this(codec, schemas, MAX_FRAME_BYTES);
    }

    /**
     * Creates a codec with a stricter host byte limit; all frames include their format headers.
     * UTF16 code units are length-framed, preserving Java strings without lossy surrogate replacement.
     * @param codec borrowed trusted codec
     * @param schemas explicit schema allowlist
     * @param maximumBytes positive frame cap, at most16MiB
     * @throws NullPointerException if a collaborator is null
     * @throws IllegalArgumentException if the frame cap is invalid
     */
    public PayloadFrameEngine(ValueCodec codec, SchemaRegistry schemas, int maximumBytes) {
        this.codec = Objects.requireNonNull(codec); this.schemas = Objects.requireNonNull(schemas);
        if (maximumBytes < 1 || maximumBytes > MAX_FRAME_BYTES) throw new IllegalArgumentException("Invalid durable frame limit");
        this.maximumBytes = maximumBytes;
    }

    /**
     * Encodes an explicitly declared data value; maps and metadata use sorted string keys.
     * @param value nullable data value
     * @param type explicit declared Java data type, never a frame-provided class name
     * @return owned version-one frame, bounded by the configured cap
     * @throws MiddlewareException with CODEC_ERROR for unsupported types, invalid schemas or bounds
     */
    public byte[] encode(Object value, Class<?> type) {
        try {
            declared(type); EncodedValue encoded = codec.encode(value, type, DATA_ONLY);
            validate(encoded, type, null, 0, new Budget());
            var bytes = new LimitedBytes(maximumBytes); var out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(VERSION); write(out, encoded, 0, new Budget()); out.flush(); return bytes.bytes();
        } catch (IOException | RuntimeException failure) { throw invalid(); }
    }

    /**
     * Fully validates a frame and its schema allowlist before invoking the borrowed value decoder.
     * @param frame complete version-one frame; trailing bytes are rejected
     * @param type explicitly trusted expected Java data type
     * @return decoded value, possibly null for a nullable/void result
     * @throws MiddlewareException with CODEC_ERROR for corruption, unsupported data or incompatible types
     */
    public Object decode(byte[] frame, Class<?> type) {
        try {
            declared(type); if (frame == null || frame.length > maximumBytes) throw invalid();
            var in = new DataInputStream(new ByteArrayInputStream(frame));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw invalid();
            EncodedValue value = read(in, 0, new Budget()); if (in.available() != 0) throw invalid();
            validate(value, type, null, 0, new Budget()); Object result = codec.decode(value, type, DATA_ONLY);
            if (result != null && type != Object.class && !box(type).isInstance(result)) throw invalid();
            if (result == null && type.isPrimitive() && type != void.class) throw invalid(); return result;
        } catch (IOException | RuntimeException failure) { throw invalid(); }
    }

    /**
     * Fingerprints one complete method's ordered business inputs using the same canonical framing.
     * No target, trace carrier, deadline, physical attempt or principal is added to the input hash;
     * authenticated tenant/principal identity belongs to the separate durable key scope.
     * @param contract stable service ID/version and full erased method identity
     * @param types explicit parameter classes matching the method signature
     * @param arguments ordered values, including a declared business key parameter when present
     * @return lowercase SHA-256 digest of version-one canonical business input bytes
     * @throws MiddlewareException with CODEC_ERROR for a signature mismatch or unsupported/bounded input
     */
    public String inputHash(ManagedServiceContract contract, List<Class<?>> types, Object[] arguments) {
        try {
            Objects.requireNonNull(contract); Objects.requireNonNull(types); Objects.requireNonNull(arguments);
            if (types.size() != arguments.length || !types.stream().map(Class::getName).toList().equals(contract.method().parameterTypes())) throw invalid();
            var bytes = new LimitedBytes(maximumBytes); var out = new DataOutputStream(bytes); out.writeInt(HASH_MAGIC); out.writeInt(VERSION);
            text(out, contract.id()); out.writeInt(contract.version()); text(out, contract.method().declaringType()); text(out, contract.method().name());
            text(out, contract.method().returnType()); out.writeInt(types.size()); var budget = new Budget();
            for (int index = 0; index < types.size(); index++) {
                Class<?> type = types.get(index); declared(type); text(out, type.getName());
                EncodedValue value = codec.encode(arguments[index], type, DATA_ONLY); validate(value, type, null, 0, new Budget()); write(out, value, 0, budget);
            }
            out.flush(); return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.bytes()));
        } catch (IOException | GeneralSecurityException | RuntimeException failure) { throw invalid(); }
    }

    void validateDeclaredType(Class<?> type) { declared(type); }

    private void declared(Class<?> type) {
        Objects.requireNonNull(type);
        if (SCALARS.contains(box(type)) || type == Object.class || type == void.class || type == Void.class || type == byte[].class
                || type == Instant.class || type == Path.class || type == List.class || type == Map.class || type.isEnum() || schemas.forType(type) != null) return;
        throw invalid();
    }
    private static Class<?> box(Class<?> type) { return BOXES.getOrDefault(type, type); }
    private void validate(EncodedValue node, Class<?> expected, SchemaField field, int depth, Budget budget) {
        budget.enter(depth); if (node == null || !KINDS.contains(node.kind())) throw invalid();
        Set<String> allowed = new HashSet<>();
        if (node.kind().equals("registered-schema")) allowed.add("schemaVersion");
        if (field != null) {
            allowed.addAll(List.of("schemaFieldType", "schemaRequired"));
            if (!field.type().getName().equals(node.metadata().get("schemaFieldType")) || !Boolean.toString(field.required()).equals(node.metadata().get("schemaRequired"))) throw invalid();
        }
        if (!node.metadata().keySet().equals(allowed)) throw invalid();
        Object raw = node.value();
        switch (node.kind()) {
            case "null" -> { if (raw != null || expected.isPrimitive() && expected != void.class || !node.typeName().equals(expected.getName())) throw invalid(); }
            case "scalar" -> {
                if (raw == null || !SCALARS.contains(raw.getClass()) || !node.typeName().equals(raw.getClass().getName())
                        || expected != Object.class && !box(expected).isInstance(raw)) throw invalid();
            }
            case "bytes" -> { if (!(raw instanceof byte[]) || !node.typeName().equals(byte[].class.getName()) || expected != Object.class && expected != byte[].class) throw invalid(); }
            case "enum" -> { if (!expected.isEnum() || !node.typeName().equals(expected.getName()) || !(raw instanceof String)) throw invalid(); }
            case "path" -> { if (!(raw instanceof String) || !node.typeName().equals(Path.class.getName()) || expected != Object.class && expected != Path.class) throw invalid(); }
            case "list" -> {
                if (!(raw instanceof List<?> list) || !node.typeName().equals(List.class.getName()) || expected != Object.class && expected != List.class) throw invalid();
                for (Object child : list) validate(child(child), Object.class, null, depth + 1, budget);
            }
            case "map" -> {
                if (!node.typeName().equals(Map.class.getName()) || expected != Object.class && expected != Map.class) throw invalid();
                for (Object child : map(raw).values()) validate(child(child), Object.class, null, depth + 1, budget);
            }
            case "instant" -> {
                if (!node.typeName().equals(Instant.class.getName()) || expected != Object.class && expected != Instant.class || !map(raw).keySet().equals(Set.of("epochSecond", "nano"))) throw invalid();
                validate(child(map(raw).get("epochSecond")), Long.class, null, depth + 1, budget);
                validate(child(map(raw).get("nano")), Integer.class, null, depth + 1, budget);
            }
            case "registered-schema" -> {
                int version = Integer.parseInt(node.metadata().getOrDefault("schemaVersion", "0"));
                SchemaDescriptor<?> source = schemas.forVersion(node.typeName(), version);
                SchemaDescriptor<?> target = expected == Object.class ? source : schemas.forType(expected);
                if (source == null || target == null) throw invalid(); SchemaRegistry.checkCompatible(source, target);
                Map<String, Object> fields = map(raw);
                for (var entry : fields.entrySet()) {
                    var definition = source.fields().stream().filter(candidate -> candidate.name().equals(entry.getKey())).findFirst().orElseThrow(PayloadFrameEngine::invalid);
                    validate(child(entry.getValue()), definition.type(), definition, depth + 1, budget);
                }
                for (var definition : source.fields()) if (definition.required() && (!fields.containsKey(definition.name()) || child(fields.get(definition.name())).kind().equals("null"))) throw invalid();
            }
            default -> throw invalid();
        }
    }
    private static EncodedValue child(Object value) { if (value instanceof EncodedValue encoded) return encoded; throw invalid(); }
    private static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> source) || source.size() > MAX_NODES) throw invalid();
        Map<String, Object> result = new TreeMap<>();
        source.forEach((key, value) -> { if (!(key instanceof String name) || value == null) throw invalid(); result.put(name, value); }); return result;
    }
    private void write(DataOutputStream out, EncodedValue node, int depth, Budget budget) throws IOException {
        budget.enter(depth); out.writeByte(KINDS.indexOf(node.kind())); text(out, node.typeName()); out.writeInt(node.metadata().size());
        for (var entry : new TreeMap<>(node.metadata()).entrySet()) { text(out, entry.getKey()); text(out, entry.getValue()); }
        switch (node.kind()) {
            case "null" -> { }
            case "scalar" -> scalar(out, node.value());
            case "bytes" -> { byte[] bytes = (byte[]) node.value(); out.writeInt(bytes.length); out.write(bytes); }
            case "enum", "path" -> text(out, (String) node.value());
            case "list" -> { var list = (List<?>) node.value(); out.writeInt(list.size()); for (Object value : list) write(out, child(value), depth + 1, budget); }
            default -> { var values = map(node.value()); out.writeInt(values.size()); for (var entry : values.entrySet()) { text(out, entry.getKey()); write(out, child(entry.getValue()), depth + 1, budget); } }
        }
    }
    private EncodedValue read(DataInputStream in, int depth, Budget budget) throws IOException {
        budget.enter(depth); int tag = in.readUnsignedByte(); if (tag >= KINDS.size()) throw invalid(); String kind = KINDS.get(tag), type = text(in);
        int metadataSize = count(in, 32); Map<String, String> metadata = new TreeMap<>();
        for (int index = 0; index < metadataSize; index++) if (metadata.put(text(in), text(in)) != null) throw invalid();
        Object value = switch (kind) {
            case "null" -> null;
            case "scalar" -> scalar(in);
            case "bytes" -> { int length = count(in, maximumBytes); if (length > in.available()) throw invalid(); yield in.readNBytes(length); }
            case "enum", "path" -> text(in);
            case "list" -> { int length = count(in, MAX_NODES); List<EncodedValue> values = new ArrayList<>(); for (int index = 0; index < length; index++) values.add(read(in, depth + 1, budget)); yield List.copyOf(values); }
            default -> { int length = count(in, MAX_NODES); Map<String, EncodedValue> values = new TreeMap<>(); for (int index = 0; index < length; index++) if (values.put(text(in), read(in, depth + 1, budget)) != null) throw invalid(); yield Map.copyOf(values); }
        };
        return new EncodedValue(kind, type, value, metadata);
    }
    private void scalar(DataOutputStream out, Object value) throws IOException {
        int tag = SCALARS.indexOf(value.getClass()); if (tag < 0) throw invalid(); out.writeByte(tag);
        switch (tag) {
            case 0 -> text(out, (String) value);
            case 1 -> out.writeBoolean((Boolean) value);
            case 2 -> out.writeChar((Character) value);
            case 3 -> out.writeByte((Byte) value);
            case 4 -> out.writeShort((Short) value);
            case 5 -> out.writeInt((Integer) value);
            case 6 -> out.writeLong((Long) value);
            case 7 -> out.writeInt(Float.floatToIntBits((Float) value));
            case 8 -> out.writeLong(Double.doubleToLongBits((Double) value));
            case 9 -> { if (((BigInteger) value).bitLength() > maximumBytes * 2) throw invalid(); text(out, value.toString()); }
            case 10 -> { if (((BigDecimal) value).unscaledValue().bitLength() > maximumBytes * 2) throw invalid(); text(out, value.toString()); }
            default -> throw invalid();
        }
    }
    private Object scalar(DataInputStream in) throws IOException {
        return switch (in.readUnsignedByte()) {
            case 0 -> text(in); case 1 -> { int value = in.readUnsignedByte(); if (value > 1) throw invalid(); yield value == 1; } case 2 -> in.readChar(); case 3 -> in.readByte(); case 4 -> in.readShort();
            case 5 -> in.readInt(); case 6 -> in.readLong(); case 7 -> Float.intBitsToFloat(in.readInt()); case 8 -> Double.longBitsToDouble(in.readLong());
            case 9 -> new BigInteger(text(in)); case 10 -> new BigDecimal(text(in)); default -> throw invalid();
        };
    }
    private static void text(DataOutputStream out, String value) throws IOException { out.writeInt(value.length()); for (int index = 0; index < value.length(); index++) out.writeChar(value.charAt(index)); }
    private String text(DataInputStream in) throws IOException { int length = count(in, maximumBytes / 2); if (length > in.available() / 2) throw invalid(); char[] value = new char[length]; for (int index = 0; index < length; index++) value[index] = in.readChar(); return new String(value); }
    private static int count(DataInputStream in, int maximum) throws IOException { int value = in.readInt(); if (value < 0 || value > maximum) throw invalid(); return value; }
    private static MiddlewareException invalid() { return MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "Invalid or unsupported durable payload frame"); }
    private static final class Budget { private int nodes; void enter(int depth) { if (depth > MAX_DEPTH || ++nodes > MAX_NODES) throw invalid(); } }
    private static final class LimitedBytes extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(); private final int maximum;
        LimitedBytes(int maximum) { this.maximum = maximum; }
        @Override public void write(int value) { if (bytes.size() >= maximum) throw invalid(); bytes.write(value); }
        @Override public void write(byte[] value, int offset, int length) { if (length < 0 || length > maximum - bytes.size()) throw invalid(); bytes.write(value, offset, length); }
        byte[] bytes() { return bytes.toByteArray(); }
    }
}
