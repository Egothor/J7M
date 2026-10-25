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

package org.j7m.middleware.codec.defaultcodec;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.codec.api.CodecContext;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.codec.api.ValueCodec;
import org.j7m.middleware.codec.api.SchemaDescriptor;
import org.j7m.middleware.codec.api.SchemaField;
import org.j7m.middleware.codec.api.SchemaRegistry;

/**
 * Deterministic Phase 1 value codec.
 *
 * <p>The codec preserves common immutable values, lists, string-keyed maps,
 * enums, exceptions as safe descriptors, explicit {@link ObjectRef} handles,
 * and a small allow-list of trusted schema records used by executable
 * examples. Applications may explicitly register DTO/record schemas with trusted
 * extractor/factory functions. Adjacent versions may add or remove optional fields;
 * payload validation finishes before the target factory runs. Unregistered records
 * are rejected because accessors and constructors are application code. The codec
 * never loads classes from wire type names and does not use Java native serialization.</p>
 */
public final class DefaultValueCodec implements ValueCodec {
    private static final int MAX_DEPTH = 16;
    private static final int MAX_NODES = 4096;
    private static final int MAX_CONTAINER_ENTRIES = 1024;
    private static final int MAX_STRING_BYTES = 64 * 1024;
    private static final int MAX_VALUE_BYTES = 1024 * 1024;
    private static final int MAX_METADATA_ENTRIES = 32;
    private static final int MAX_METADATA_BYTES = 8 * 1024;
    private static final String TRUSTED_DTO_KIND = "trusted-dto";

    private final SchemaRegistry schemas;

    /**
     * Creates a default codec with no application schemas.
     */
    public DefaultValueCodec() {
        this(new SchemaRegistry(List.of()));
    }

    /**
     * Creates a codec with an explicit application schema allowlist.
     * @param schemas immutable trusted schemas; never null
     */
    public DefaultValueCodec(final SchemaRegistry schemas) {
        this.schemas = java.util.Objects.requireNonNull(schemas, "schemas");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EncodedValue encode(final Object value, final Class<?> declaredType, final CodecContext context) {
        return this.encode(value, declaredType, context, 0, new EncodeBudget());
    }

    private EncodedValue encode(
            final Object value,
            final Class<?> declaredType,
            final CodecContext context,
            final int depth,
            final EncodeBudget budget) {
        try {
            budget.enterNode(depth);
            if (value == null) {
                return new EncodedValue("null", declaredType == null ? "java.lang.Object" : declaredType.getName(), null, Map.of());
            }
            boolean tracked = shouldTrackIdentity(value);
            if (tracked) {
                budget.enterObject(value);
            }
            try {
                if (value instanceof ObjectRef ref) {
                    validateObjectRef(ref);
                    return new EncodedValue("object-ref", ObjectRef.class.getName(), ref, Map.of());
                }
                if (context != null) {
                    java.util.Optional<ObjectRef> ref = context.referenceFor(value, declaredType == null ? value.getClass() : declaredType);
                    if (ref.isPresent()) {
                        validateObjectRef(ref.get());
                        return new EncodedValue("object-ref", ObjectRef.class.getName(), ref.get(), Map.of());
                    }
                }
                SchemaDescriptor<?> registered = this.schemas.forType(value.getClass());
                if (registered != null) {
                    return this.encodeSchema(registered, value, context, depth, budget);
                }
                if (value.getClass().isRecord()) {
                    EncodedValue trustedDto = this.encodeTrustedDto(value, context, depth, budget);
                    if (trustedDto != null) {
                        return trustedDto;
                    }
                    throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "generic record values require a trusted schema");
                }
                if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character) {
                    if (value instanceof String text) {
                        validateString(text);
                    }
                    return new EncodedValue("scalar", value.getClass().getName(), value, Map.of());
                }
                if (value instanceof byte[] bytes) {
                    validateBytes(bytes.length);
                    return new EncodedValue("bytes", byte[].class.getName(), bytes.clone(), Map.of());
                }
                if (value instanceof Enum<?> enumValue) {
                    return new EncodedValue("enum", value.getClass().getName(), enumValue.name(), Map.of());
                }
                if (value instanceof Instant instant) {
                    Map<String, EncodedValue> encodedInstant = Map.of(
                            "epochSecond", this.encode(Long.valueOf(instant.getEpochSecond()), Long.class, context, depth + 1, budget),
                            "nano", this.encode(Integer.valueOf(instant.getNano()), Integer.class, context, depth + 1, budget));
                    return new EncodedValue("instant", Instant.class.getName(), encodedInstant, Map.of());
                }
                if (value instanceof Path path) {
                    String text = path.toString();
                    validateString(text);
                    return new EncodedValue("path", Path.class.getName(), text, Map.of());
                }
                if (value instanceof List<?> list) {
                    validateContainerSize(list.size());
                    List<EncodedValue> encoded = new ArrayList<>(list.size());
                    for (Object element : list) {
                        encoded.add(this.encode(element, element == null ? Object.class : element.getClass(), context, depth + 1, budget));
                    }
                    return new EncodedValue("list", List.class.getName(), List.copyOf(encoded), Map.of());
                }
                if (value instanceof Map<?, ?> map) {
                    validateContainerSize(map.size());
                    Map<String, EncodedValue> encoded = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (!(entry.getKey() instanceof String key)) {
                            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "only string map keys are supported");
                        }
                        validateString(key);
                        Object entryValue = entry.getValue();
                        encoded.put(key, this.encode(entryValue, entryValue == null ? Object.class : entryValue.getClass(), context, depth + 1, budget));
                    }
                    return new EncodedValue("map", Map.class.getName(), Map.copyOf(encoded), Map.of());
                }
                if (value instanceof Throwable throwable) {
                    return new EncodedValue("exception", throwable.getClass().getName(),
                            Map.of("type", throwable.getClass().getName(), "message", throwable.getMessage() == null ? "" : throwable.getMessage()), Map.of());
                }
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "unsupported value type for remote codec");
            } finally {
                if (tracked) {
                    budget.exitObject(value);
                }
            }
        } catch (MiddlewareException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MiddlewareException(new org.j7m.middleware.api.RemoteError(
                    MiddlewareErrorCode.CODEC_ERROR, "failed to encode value", Map.of(), null, null, false, true), exception);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object decode(final EncodedValue value, final Class<?> expectedType, final CodecContext context) {
        try {
            validateEncodedValue(value, 0, new int[] {0});
            if (value == null || "null".equals(value.kind())) {
                return null;
            }
            if ("object-ref".equals(value.kind())) {
                ObjectRef ref = (ObjectRef) value.value();
                if (context == null || expectedType == ObjectRef.class) {
                    return ref;
                }
                return context.proxyFor(ref, expectedType);
            }
            Object raw = value.value();
            if ("bytes".equals(value.kind()) && raw instanceof byte[] bytes) {
                return bytes.clone();
            }
            if ("enum".equals(value.kind()) && expectedType != null && expectedType.isEnum()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object enumValue = Enum.valueOf((Class) expectedType, (String) raw);
                return enumValue;
            }
            if ("instant".equals(value.kind())) {
                return this.decodeInstant(raw);
            }
            if ("path".equals(value.kind())) {
                if (raw instanceof String path) {
                    return Path.of(path);
                }
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded path payload is invalid");
            }
            if ("list".equals(value.kind()) && raw instanceof List<?> rawList) {
                List<Object> decoded = new java.util.ArrayList<>(rawList.size());
                for (Object element : rawList) {
                    if (element instanceof EncodedValue encodedElement) {
                        decoded.add(this.decode(encodedElement, Object.class, context));
                    } else {
                        decoded.add(element);
                    }
                }
                return List.copyOf(decoded);
            }
            if ("map".equals(value.kind()) && raw instanceof Map<?, ?> rawMap) {
                Map<String, Object> decoded = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded map contains non-string key");
                    }
                    Object entryValue = entry.getValue();
                    if (entryValue instanceof EncodedValue encodedElement) {
                        decoded.put(key, this.decode(encodedElement, Object.class, context));
                    } else {
                        decoded.put(key, entryValue);
                    }
                }
                return Map.copyOf(decoded);
            }
            if ("registered-schema".equals(value.kind())) {
                return this.decodeSchema(value, expectedType, context);
            }
            if (TRUSTED_DTO_KIND.equals(value.kind())) {
                return this.decodeTrustedDto(value, expectedType, context);
            }
            if ("scalar".equals(value.kind())) {
                if (raw == null || raw instanceof String || raw instanceof Number || raw instanceof Boolean || raw instanceof Character) {
                    return raw;
                }
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded scalar payload is invalid");
            }
            if ("record".equals(value.kind())) {
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "generic record values require a trusted schema");
            }
            if ("dto".equals(value.kind())) {
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "raw dto fallback is not supported");
            }
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "unsupported encoded value kind");
        } catch (MiddlewareException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MiddlewareException(new org.j7m.middleware.api.RemoteError(
                    MiddlewareErrorCode.CODEC_ERROR, "failed to decode value", Map.of(), null, null, false, true), exception);
        }
    }

    private <T> EncodedValue encodeSchema(final SchemaDescriptor<T> schema, final Object value,
            final CodecContext context, final int depth, final EncodeBudget budget) {
        Map<String, Object> fields = schema.encoder().apply(schema.javaType().cast(value));
        validateSchemaFields(schema, fields);
        Map<String, EncodedValue> encoded = new LinkedHashMap<>();
        for (SchemaField field : schema.fields()) {
            if (fields.containsKey(field.name())) {
                EncodedValue item = this.encode(fields.get(field.name()), field.type(), context, depth + 1, budget);
                Map<String, String> metadata = new LinkedHashMap<>(item.metadata());
                metadata.put("schemaRequired", Boolean.toString(field.required()));
                metadata.put("schemaFieldType", field.type().getName());
                encoded.put(field.name(), new EncodedValue(item.kind(), item.typeName(), item.value(), metadata));
            }
        }
        return new EncodedValue("registered-schema", schema.schemaId(), Map.copyOf(encoded),
                Map.of("schemaVersion", Integer.toString(schema.version())));
    }

    private Object decodeSchema(final EncodedValue value, final Class<?> expectedType, final CodecContext context) {
        int version = Integer.parseInt(value.metadata().getOrDefault("schemaVersion", "0"));
        SchemaDescriptor<?> source = this.schemas.forVersion(value.typeName(), version);
        SchemaDescriptor<?> target = expectedType == Object.class ? source : this.schemas.forType(expectedType);
        if (target == null || !target.schemaId().equals(value.typeName()) || version < 1
                || Math.abs((long) target.version() - version) > 1) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "unregistered or incompatible schema");
        }
        if (source != null) {
            SchemaRegistry.checkCompatible(source, target);
        }
        if (!(value.value() instanceof Map<?, ?> raw) || raw.size() > 128) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "invalid schema payload");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof EncodedValue encoded)) {
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "invalid schema field");
            }
            SchemaField field = target.fields().stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
            String required = encoded.metadata().get("schemaRequired");
            if (!("true".equals(required) || "false".equals(required))) {
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "missing schema field contract");
            }
            if (field == null) {
                if (Boolean.parseBoolean(required) || version == target.version()) {
                    throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "unknown required schema field");
                }
                continue;
            }
            if (!field.type().getName().equals(encoded.metadata().get("schemaFieldType"))
                    || field.required() != Boolean.parseBoolean(required)) {
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "incompatible schema field contract");
            }
            fields.put(field.name(), this.decode(encoded, field.type(), context));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (SchemaField field : target.fields()) {
            result.put(field.name(), fields.containsKey(field.name()) ? fields.get(field.name()) : field.defaultValue());
        }
        validateSchemaFields(target, result);
        Object created = target.factory().apply(Collections.unmodifiableMap(result));
        if (!target.javaType().isInstance(created)) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "schema factory returned invalid type");
        }
        return created;
    }

    private static void validateSchemaFields(final SchemaDescriptor<?> schema, final Map<String, Object> fields) {
        if (fields == null || fields.keySet().stream().anyMatch(name -> schema.fields().stream().noneMatch(f -> f.name().equals(name)))) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "unknown schema field");
        }
        for (SchemaField field : schema.fields()) {
            Object value = fields.get(field.name());
            if ((field.required() && value == null) || (value != null && !field.type().isInstance(value))) {
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "incompatible schema field: " + field.name());
            }
        }
    }

    private Instant decodeInstant(final Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded instant payload is invalid");
        }
        long epochSecond = this.decodeLongComponent(map.get("epochSecond"));
        int nano = this.decodeIntComponent(map.get("nano"));
        return Instant.ofEpochSecond(epochSecond, nano);
    }

    private long decodeLongComponent(final Object value) {
        Object decoded = value instanceof EncodedValue encodedValue
                ? this.decode(encodedValue, Long.class, null)
                : value;
        if (decoded instanceof Number number) {
            return number.longValue();
        }
        throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded long component is invalid");
    }

    private int decodeIntComponent(final Object value) {
        Object decoded = value instanceof EncodedValue encodedValue
                ? this.decode(encodedValue, Integer.class, null)
                : value;
        if (decoded instanceof Number number) {
            return number.intValue();
        }
        throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded int component is invalid");
    }

    private EncodedValue encodeTrustedDto(
            final Object value,
            final CodecContext context,
            final int depth,
            final EncodeBudget budget) {
        Class<?> type = value.getClass();
        String typeName = type.getName();
        if (!isTrustedDto(typeName)) {
            return null;
        }
        RecordComponent[] components = type.getRecordComponents();
        String[] expectedFields = trustedDtoFields(typeName);
        if (components.length != expectedFields.length) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "trusted dto schema field count is invalid");
        }
        Map<String, EncodedValue> fields = new LinkedHashMap<>(components.length);
        try {
            for (int index = 0; index < components.length; index++) {
                RecordComponent component = components[index];
                if (!expectedFields[index].equals(component.getName())) {
                    throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "trusted dto schema field order is invalid");
                }
                Object fieldValue = component.getAccessor().invoke(value);
                Class<?> fieldType = component.getType();
                fields.put(component.getName(), this.encode(fieldValue, fieldType, context, depth + 1, budget));
            }
            return new EncodedValue(TRUSTED_DTO_KIND, typeName, Map.copyOf(fields), Map.of("schema", "trusted-record-v1"));
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new MiddlewareException(new org.j7m.middleware.api.RemoteError(
                    MiddlewareErrorCode.CODEC_ERROR, "failed to encode trusted dto value", Map.of(), null, null, false, true), exception);
        }
    }

    private Object decodeTrustedDto(final EncodedValue value, final Class<?> expectedType, final CodecContext context) {
        Class<?> targetType = trustedDtoTargetType(value.typeName(), expectedType);
        String[] expectedFields = trustedDtoFields(targetType.getName());
        Object raw = value.value();
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "trusted dto payload is invalid");
        }
        if (rawMap.size() != expectedFields.length) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "trusted dto field count is invalid");
        }
        RecordComponent[] components = targetType.getRecordComponents();
        if (components.length != expectedFields.length) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "trusted dto target schema is invalid");
        }
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] parameters = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            RecordComponent component = components[index];
            String fieldName = expectedFields[index];
            if (!fieldName.equals(component.getName()) || !rawMap.containsKey(fieldName)) {
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "trusted dto field set is invalid");
            }
            Object fieldValue = rawMap.get(fieldName);
            if (!(fieldValue instanceof EncodedValue encodedField)) {
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "trusted dto field payload is invalid");
            }
            parameterTypes[index] = component.getType();
            parameters[index] = this.decode(encodedField, component.getType(), context);
        }
        try {
            Constructor<?> constructor = targetType.getDeclaredConstructor(parameterTypes);
            return constructor.newInstance(parameters);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException exception) {
            throw new MiddlewareException(new org.j7m.middleware.api.RemoteError(
                    MiddlewareErrorCode.CODEC_ERROR, "failed to decode trusted dto value", Map.of(), null, null, false, true), exception);
        }
    }

    private static Class<?> trustedDtoTargetType(final String encodedTypeName, final Class<?> expectedType) {
        if (!isTrustedDto(encodedTypeName)) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "trusted dto type is not allowed");
        }
        if (expectedType != null && expectedType != Object.class) {
            if (!encodedTypeName.equals(expectedType.getName()) || !expectedType.isRecord()) {
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "trusted dto target type does not match");
            }
            return expectedType;
        }
        try {
            Class<?> resolved = Class.forName(encodedTypeName);
            if (!resolved.isRecord()) {
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "trusted dto target is not a record");
            }
            return resolved;
        } catch (ClassNotFoundException exception) {
            throw new MiddlewareException(new org.j7m.middleware.api.RemoteError(
                    MiddlewareErrorCode.CODEC_ERROR, "trusted dto target type is unavailable", Map.of(), null, null, false, true), exception);
        }
    }

    private static boolean isTrustedDto(final String typeName) {
        return switch (typeName) {
            case "org.j7m.middleware.examples.workload.TaskHandle",
                    "org.j7m.middleware.examples.workload.WorkloadTaskProgress",
                    "org.j7m.middleware.examples.workload.Checkpoint",
                    "org.j7m.middleware.examples.workload.LeaseResult",
                    "org.j7m.middleware.examples.workload.WorkloadTaskStatus",
                    "org.j7m.middleware.examples.streaming.StreamSession",
                    "org.j7m.middleware.examples.streaming.StreamOptions",
                    "org.j7m.middleware.examples.streaming.BatchResult",
                    "org.j7m.middleware.examples.streaming.StreamExchangeResult",
                    "org.j7m.middleware.examples.streaming.StreamMetrics",
                    "org.j7m.middleware.examples.regulated.ComplianceSummary",
                    "org.j7m.middleware.examples.regulated.EvidenceReport",
                    "org.j7m.middleware.examples.regulated.ExampleCryptoPosture",
                    "org.j7m.middleware.examples.regulated.AuditVerificationResult",
                    "org.j7m.middleware.examples.banking.PaymentId",
                    "org.j7m.middleware.examples.banking.Money",
                    "org.j7m.middleware.examples.banking.ApprovalResult",
                    "org.j7m.middleware.application.api.ApplicationActivationRequest",
                    "org.j7m.middleware.application.api.ApplicationBundle",
                    "org.j7m.middleware.application.api.ApplicationCoordinate",
                    "org.j7m.middleware.application.api.ApplicationId",
                    "org.j7m.middleware.application.api.ApplicationInstanceId",
                    "org.j7m.middleware.application.api.ApplicationVersion",
                    "org.j7m.middleware.deployer.api.DeactivationOptions",
                    "org.j7m.middleware.deployer.api.DeployOptions",
                    "org.j7m.middleware.deployer.api.RollbackOptions",
                    "org.j7m.middleware.deployer.api.UnloadOptions",
                    "org.j7m.middleware.deployer.api.UpgradeOptions",
                    "org.j7m.middleware.deployer.api.VerificationOptions" -> true;
            default -> false;
        };
    }

    private static String[] trustedDtoFields(final String typeName) {
        return switch (typeName) {
            case "org.j7m.middleware.examples.workload.TaskHandle" -> new String[] {"taskId"};
            case "org.j7m.middleware.examples.workload.WorkloadTaskProgress" -> new String[] {"percent", "message"};
            case "org.j7m.middleware.examples.workload.Checkpoint" -> new String[] {"checkpointId", "taskId", "createdAt", "payload"};
            case "org.j7m.middleware.examples.workload.LeaseResult" -> new String[] {"acquired", "ownerId", "fencingToken", "leaseUntil"};
            case "org.j7m.middleware.examples.workload.WorkloadTaskStatus" -> new String[] {"handle", "state", "ownerId", "fencingToken", "updatedAt"};
            case "org.j7m.middleware.examples.streaming.StreamSession" -> new String[] {"sessionId"};
            case "org.j7m.middleware.examples.streaming.StreamOptions" -> new String[] {"maxItemBytes", "queueLimit"};
            case "org.j7m.middleware.examples.streaming.BatchResult" -> new String[] {"accepted", "rejected", "backpressure"};
            case "org.j7m.middleware.examples.streaming.StreamExchangeResult" -> new String[] {"acceptedClientItems", "serverItems", "backpressure"};
            case "org.j7m.middleware.examples.streaming.StreamMetrics" -> new String[] {"submitted", "returned", "rejected", "cancelled", "backpressureSignals"};
            case "org.j7m.middleware.examples.regulated.ComplianceSummary" -> new String[] {"profile", "evidenceItems", "certificationClaim", "limitations"};
            case "org.j7m.middleware.examples.regulated.EvidenceReport" -> new String[] {"generatedAt", "controls", "limitations"};
            case "org.j7m.middleware.examples.regulated.ExampleCryptoPosture" -> new String[] {"strictMode", "allowedAlgorithms", "keyMaterialExposed"};
            case "org.j7m.middleware.examples.regulated.AuditVerificationResult" -> new String[] {"valid", "sequenceCount", "checkpointHash"};
            case "org.j7m.middleware.examples.banking.PaymentId" -> new String[] {"value"};
            case "org.j7m.middleware.examples.banking.Money" -> new String[] {"currency", "minorUnits"};
            case "org.j7m.middleware.examples.banking.ApprovalResult" -> new String[] {"approved", "approver", "correlationId"};
            case "org.j7m.middleware.application.api.ApplicationActivationRequest" -> new String[] {
                    "principalId", "environment", "strictVerification", "configuration"};
            case "org.j7m.middleware.application.api.ApplicationBundle" -> new String[] {"path", "coordinate", "sha256"};
            case "org.j7m.middleware.application.api.ApplicationCoordinate" -> new String[] {"id", "version"};
            case "org.j7m.middleware.application.api.ApplicationId" -> new String[] {"group", "name"};
            case "org.j7m.middleware.application.api.ApplicationInstanceId" -> new String[] {"value"};
            case "org.j7m.middleware.application.api.ApplicationVersion" -> new String[] {"value"};
            case "org.j7m.middleware.deployer.api.DeactivationOptions" -> new String[] {"force"};
            case "org.j7m.middleware.deployer.api.DeployOptions" -> new String[] {"publisher", "strictVerification"};
            case "org.j7m.middleware.deployer.api.RollbackOptions" -> new String[] {"targetVersion"};
            case "org.j7m.middleware.deployer.api.UnloadOptions" -> new String[] {"force"};
            case "org.j7m.middleware.deployer.api.UpgradeOptions" -> new String[] {
                    "strategy", "rollingBatchSize", "canaryInvocations", "canarySuccessThreshold", "retainPrevious"};
            case "org.j7m.middleware.deployer.api.VerificationOptions" -> new String[] {
                    "strictSignatureRequired", "verificationProfile"};
            default -> throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "trusted dto type is not allowed");
        };
    }

    private static void validateEncodedValue(final EncodedValue value, final int depth, final int[] nodes) {
        if (value == null) {
            return;
        }
        if (depth > MAX_DEPTH) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded value depth exceeds limit");
        }
        nodes[0]++;
        if (nodes[0] > MAX_NODES) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded value node count exceeds limit");
        }
        validateString(value.kind());
        validateString(value.typeName());
        validateMetadata(value.metadata());
        validatePayload(value.value(), depth, nodes);
    }

    private static void validatePayload(final Object raw, final int depth, final int[] nodes) {
        if (raw == null || raw instanceof Number || raw instanceof Boolean || raw instanceof Character) {
            return;
        }
        if (raw instanceof String text) {
            validateString(text);
            return;
        }
        if (raw instanceof byte[] bytes) {
            validateBytes(bytes.length);
            return;
        }
        if (raw instanceof ObjectRef ref) {
            validateObjectRef(ref);
            return;
        }
        if (raw instanceof EncodedValue encodedValue) {
            validateEncodedValue(encodedValue, depth + 1, nodes);
            return;
        }
        if (raw instanceof List<?> list) {
            validateContainerSize(list.size());
            for (Object item : list) {
                validatePayload(item, depth + 1, nodes);
            }
            return;
        }
        if (raw instanceof Map<?, ?> map) {
            validateContainerSize(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded map contains non-string key");
                }
                validateString(key);
                validatePayload(entry.getValue(), depth + 1, nodes);
            }
            return;
        }
        throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded value contains unsupported payload");
    }

    private static void validateObjectRef(final ObjectRef ref) {
        validateString(ref.objectId());
        validateString(ref.logicalName());
        validateString(ref.serviceName());
        validateString(ref.runtimeId());
        validateString(ref.registryContext());
        validateString(ref.transport());
        validateString(ref.endpoint());
        validateContainerSize(ref.interfaces().size());
        for (String interfaceName : ref.interfaces()) {
            validateString(interfaceName);
        }
        validateMetadata(ref.metadata());
    }

    private static void validateMetadata(final Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        if (metadata.size() > MAX_METADATA_ENTRIES) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded metadata entry count exceeds limit");
        }
        int totalBytes = 0;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null) {
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded metadata is invalid");
            }
            validateString(key);
            validateString(value);
            totalBytes += utf8Bytes(key) + utf8Bytes(value);
            if (totalBytes > MAX_METADATA_BYTES) {
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded metadata byte count exceeds limit");
            }
        }
    }

    private static boolean shouldTrackIdentity(final Object value) {
        return value instanceof List<?> || value instanceof Map<?, ?>;
    }

    private static void validateContainerSize(final int size) {
        if (size > MAX_CONTAINER_ENTRIES) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded container entry count exceeds limit");
        }
    }

    private static void validateBytes(final int length) {
        if (length > MAX_VALUE_BYTES) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded byte value exceeds limit");
        }
    }

    private static void validateString(final String value) {
        if (utf8Bytes(value) > MAX_STRING_BYTES) {
            throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded string exceeds limit");
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

    private static final class EncodeBudget {
        private final Set<Object> activeObjects = Collections.newSetFromMap(new IdentityHashMap<>());
        private int nodes;

        void enterNode(final int depth) {
            if (depth > MAX_DEPTH) {
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded value depth exceeds limit");
            }
            this.nodes++;
            if (this.nodes > MAX_NODES) {
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded value node count exceeds limit");
            }
        }

        void enterObject(final Object value) {
            if (!this.activeObjects.add(value)) {
                throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "encoded value contains a cycle");
            }
        }

        void exitObject(final Object value) {
            this.activeObjects.remove(value);
        }
    }
}
