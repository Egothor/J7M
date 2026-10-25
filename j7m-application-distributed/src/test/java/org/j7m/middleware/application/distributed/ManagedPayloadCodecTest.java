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

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.lang.annotation.*;
import java.math.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.codec.api.*;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.junit.jupiter.api.Test;

class ManagedPayloadCodecTest {
    public record Payload(String message, Integer priority) { }
    public enum Choice { FIRST, SECOND }
    interface Service { @Deduplicated String apply(@IdempotencyKey String key); }
    private static SchemaRegistry registry(AtomicInteger factories) {
        return new SchemaRegistry(List.of(new SchemaDescriptor<>("payload", 1, Payload.class,
                List.of(new SchemaField("1", String.class, true, null), new SchemaField("2", Integer.class, false, 7)),
                value -> Map.of("1", value.message(), "2", value.priority()),
                values -> { factories.incrementAndGet(); return new Payload((String) values.get("1"), (Integer) values.get("2")); })));
    }
    private static ManagedPayloadCodec codec() { var schemas = registry(new AtomicInteger()); return new ManagedPayloadCodec(new DefaultValueCodec(schemas), schemas); }
    private static void rejected(org.junit.jupiter.api.function.Executable work) { assertEquals(MiddlewareErrorCode.CODEC_ERROR, assertThrows(MiddlewareException.class, work).code()); }
    @Test void typedDataAndExactNumericValuesRoundTripWithoutJavaSerialization() {
        var codec = codec();
        List<Object> values = List.of("text", true, 'x', (byte)-1, (short)7, 42, Long.MIN_VALUE, -0.0f, Double.NaN,
                new BigInteger("123456789012345678901234567890"), new BigDecimal("1E+1000000"), Instant.ofEpochSecond(-9, 27), Path.of("data/item"), Choice.SECOND, new Payload("hello", 3));
        for (Object value : values) {
            Class<?> type = value instanceof Path ? Path.class : value.getClass();
            assertEquals(value, codec.decode(codec.encode(value, type), type));
        }
        assertEquals(7, codec.decode(codec.encode(7, int.class), int.class));
        assertNull(codec.decode(codec.encode(null, String.class), String.class));
        assertNull(codec.decode(codec.encode(null, void.class), void.class));
        assertArrayEquals(new byte[]{1,2,3}, (byte[]) codec.decode(codec.encode(new byte[]{1,2,3}, byte[].class), byte[].class));
    }
    @Test void mapsAreSortedAndSurrogateCodeUnitsRemainDistinctAndLossless() {
        var codec = codec(); var first = new LinkedHashMap<String,Object>(); first.put("z", 1); first.put("a", List.of("x", 2));
        var second = new LinkedHashMap<String,Object>(); second.put("a", List.of("x", 2)); second.put("z", 1);
        assertArrayEquals(codec.encode(first, Map.class), codec.encode(second, Map.class));
        assertEquals(first, codec.decode(codec.encode(first, Map.class), Map.class));
        String surrogate = String.valueOf((char)0xd800);
        assertEquals(surrogate, codec.decode(codec.encode(surrogate, String.class), String.class));
        assertFalse(Arrays.equals(codec.encode(surrogate, String.class), codec.encode("?", String.class)));
    }
    @Test void nativeFrameBoundaryFitsBase64FieldAndOneExtraByteIsRejected() {
        var codec = codec(); int overhead = codec.encode(new byte[0], byte[].class).length;
        byte[] payload = new byte[ManagedPayloadCodec.MAX_FRAME_BYTES - overhead];
        byte[] exact = codec.encode(payload, byte[].class); assertEquals(ManagedPayloadCodec.MAX_FRAME_BYTES, exact.length);
        assertEquals(16384, Base64.getEncoder().encodeToString(exact).length()); assertArrayEquals(payload, (byte[]) codec.decode(exact, byte[].class));
        rejected(() -> codec.encode(new byte[payload.length+1], byte[].class));
        var tighter = new ManagedPayloadCodec(new DefaultValueCodec(), new SchemaRegistry(List.of()), exact.length-1);
        rejected(() -> tighter.decode(exact, byte[].class));
        assertThrows(IllegalArgumentException.class, () -> new ManagedPayloadCodec(new DefaultValueCodec(), new SchemaRegistry(List.of()), 0));
        assertThrows(IllegalArgumentException.class, () -> new ManagedPayloadCodec(new DefaultValueCodec(), new SchemaRegistry(List.of()), 12289));
    }
    @Test void fullMethodIdentityAndBusinessInputsDetermineHashWithoutTraceOrPrincipal() {
        var codec = codec(); var method = new ManagedMethod("business.Service", "apply", List.of(Map.class.getName()), String.class.getName());
        var contract = new ManagedServiceContract("business", 1, method);
        String first = codec.inputHash(contract, List.of(Map.class), new Object[]{Map.of("a", 1, "b", 2)});
        for (String name : List.of("alice", "bob")) {
            var identity = new org.j7m.middleware.security.api.Principal(name, name, Set.of(), Set.of());
            var security = new InvocationSecurityContext(identity, Set.of(), new org.j7m.middleware.security.api.TrustDomain("tenant"));
            try (var ignored = J7mContext.open(new InvocationContext(security, null, "different-trace-" + name))) {
                assertEquals(first, codec.inputHash(contract, List.of(Map.class), new Object[]{Map.of("b", 2, "a", 1)}));
            }
        }
        assertNotEquals(first, codec.inputHash(contract, List.of(Map.class), new Object[]{Map.of("a", 2, "b", 2)}));
        assertNotEquals(first, codec.inputHash(new ManagedServiceContract("business", 2, method), List.of(Map.class), new Object[]{Map.of("a", 1, "b", 2)}));
        assertTrue(first.matches("[0-9a-f]{64}"));
        rejected(() -> codec.inputHash(contract, List.of(String.class), new Object[]{"wrong signature"}));
    }
    @Test void unknownSchemasAndCorruptFramesNeverInvokeApplicationFactory() throws Exception {
        var factories = new AtomicInteger(); var schemas = registry(factories); var codec = new ManagedPayloadCodec(new DefaultValueCodec(schemas), schemas);
        byte[] frame = codec.encode(new Payload("hello", 3), Payload.class); assertEquals(0, factories.get());
        var withoutSchema = new ManagedPayloadCodec(new DefaultValueCodec(schemas), new SchemaRegistry(List.of()));
        rejected(() -> withoutSchema.decode(frame, Payload.class));
        byte[] wrongVersion = frame.clone(); wrongVersion[7] = 2; rejected(() -> codec.decode(wrongVersion, Payload.class));
        rejected(() -> codec.decode(Arrays.copyOf(frame, frame.length+1), Payload.class));
        rejected(() -> codec.decode(Arrays.copyOf(frame, frame.length-1), Payload.class));
        rejected(() -> codec.decode(mismatchedFieldFrame(), Payload.class));
        assertEquals(0, factories.get());
    }
    private static byte[] mismatchedFieldFrame() throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes); out.writeInt(0x4a374d50); out.writeInt(1);
        out.writeByte(8); text(out,"payload"); out.writeInt(1); text(out,"schemaVersion"); text(out,"1"); out.writeInt(1); text(out,"1");
        out.writeByte(1); text(out,String.class.getName()); out.writeInt(2); text(out,"schemaFieldType"); text(out,Object.class.getName()); text(out,"schemaRequired"); text(out,"true");
        out.writeByte(0); text(out,"hello"); return bytes.toByteArray();
    }
    private static void text(DataOutputStream out,String value) throws IOException { out.writeInt(value.length()); for(char c:value.toCharArray())out.writeChar(c); }
    @Test void unsupportedReferencesExceptionsCallbacksAndControlAreNeverDurableValues() {
        var codec = codec(); var reference = new ObjectRef("id", "service/id", "service", "runtime", "tenant", List.of(Runnable.class.getName()), "grpc", "grpc://127.0.0.1:1", 0, 1, Map.of());
        rejected(() -> codec.encode(reference, Object.class)); rejected(() -> codec.encode(new IllegalStateException("secret"), Object.class));
        rejected(() -> codec.encode((Runnable)()->{ throw new AssertionError("must not run"); }, Runnable.class));
        rejected(() -> codec.encode(InvocationControl.unavailable(), Object.class));
    }
    @Test void depthNodeAndAggregateHashLimitsRejectOversizedShapes() {
        var codec = codec(); Object deep = "value"; for(int n=0;n<17;n++)deep=List.of(deep); Object excessive = deep;
        rejected(() -> codec.encode(excessive, List.class));
        rejected(() -> codec.encode(Collections.nCopies(1024, "x"), List.class));
        var contract = new ManagedServiceContract("business", 1, new ManagedMethod("business.Service", "apply", List.of(String.class.getName()), String.class.getName()));
        rejected(() -> codec.inputHash(contract, List.of(String.class), new Object[]{"x".repeat(12288)}));
        rejected(() -> codec.decode(codec.encode("text", String.class), Integer.class));
    }
    @Test void annotationDeclarationsAreRuntimeMethodAndParameterMetadataOnly() throws Exception {
        assertArrayEquals(new ElementType[]{ElementType.METHOD}, Deduplicated.class.getAnnotation(Target.class).value());
        assertArrayEquals(new ElementType[]{ElementType.PARAMETER}, IdempotencyKey.class.getAnnotation(Target.class).value());
        var method = Service.class.getMethod("apply", String.class);
        assertEquals("default", method.getAnnotation(Deduplicated.class).policy());
        assertTrue(method.getParameters()[0].isAnnotationPresent(IdempotencyKey.class));
        assertEquals(RetentionPolicy.RUNTIME, Deduplicated.class.getAnnotation(Retention.class).value());
    }
}
