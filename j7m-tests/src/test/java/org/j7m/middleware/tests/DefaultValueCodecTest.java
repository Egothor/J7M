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

package org.j7m.middleware.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationId;
import org.j7m.middleware.application.api.ApplicationInstanceId;
import org.j7m.middleware.application.api.ApplicationVersion;
import org.j7m.middleware.codec.api.CodecContext;
import org.j7m.middleware.codec.api.EncodedValue;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.deployer.api.DeactivationOptions;
import org.j7m.middleware.deployer.api.DeployOptions;
import org.j7m.middleware.deployer.api.RollbackOptions;
import org.j7m.middleware.deployer.api.UnloadOptions;
import org.j7m.middleware.deployer.api.UpgradeOptions;
import org.j7m.middleware.deployer.api.VerificationOptions;
import org.j7m.middleware.examples.banking.ApprovalResult;
import org.j7m.middleware.examples.banking.Money;
import org.j7m.middleware.examples.banking.PaymentId;
import org.j7m.middleware.examples.regulated.AuditVerificationResult;
import org.j7m.middleware.examples.regulated.ComplianceSummary;
import org.j7m.middleware.examples.regulated.EvidenceReport;
import org.j7m.middleware.examples.regulated.ExampleCryptoPosture;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the default Phase 1 value codec.
 */
public final class DefaultValueCodecTest {
    @Test
    public void codecPreservesScalarsBytesListsMapsAndObjectRefs() {
        DefaultValueCodec codec = new DefaultValueCodec();
        ObjectRef ref = new ObjectRef("object-1", "service/object-1", "service", "runtime", "default",
                List.of("example.Api"), "grpc", "endpoint", 0L, 1L, Map.of());

        assertEquals("text", codec.decode(codec.encode("text", String.class, noRefs()), String.class, noRefs()));
        assertEquals(42, codec.decode(codec.encode(42, Integer.class, noRefs()), Integer.class, noRefs()));

        byte[] bytes = new byte[] {1, 2, 3};
        EncodedValue encodedBytes = codec.encode(bytes, byte[].class, noRefs());
        bytes[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) codec.decode(encodedBytes, byte[].class, noRefs()));

        assertEquals(List.of("a", "b"), codec.decode(codec.encode(List.of("a", "b"), List.class, noRefs()), List.class, noRefs()));
        assertEquals(Map.of("k", "v"), codec.decode(codec.encode(Map.of("k", "v"), Map.class, noRefs()), Map.class, noRefs()));
        assertSame(ref, codec.decode(codec.encode(ref, ObjectRef.class, noRefs()), ObjectRef.class, noRefs()));
    }

    @Test
    public void codecTurnsInboundObjectRefIntoProxyThroughContext() {
        DefaultValueCodec codec = new DefaultValueCodec();
        ObjectRef ref = new ObjectRef("object-1", "service/object-1", "service", "runtime", "default",
                List.of("example.Api"), "grpc", "endpoint", 0L, 1L, Map.of());
        Marker marker = new Marker() {
        };
        CodecContext context = new CodecContext() {
            @Override
            public Optional<ObjectRef> referenceFor(final Object value, final Class<?> declaredType) {
                return Optional.empty();
            }

            @Override
            public Object proxyFor(final ObjectRef incomingRef, final Class<?> expectedType) {
                assertEquals(ref, incomingRef);
                assertEquals(Marker.class, expectedType);
                return marker;
            }
        };

        Object decoded = codec.decode(codec.encode(ref, ObjectRef.class, noRefs()), Marker.class, context);

        assertSame(marker, decoded);
    }

    @Test
    public void codecCoversEnumsAndSafeExceptionDescriptors() {
        DefaultValueCodec codec = new DefaultValueCodec();
        EncodedValue exceptionValue = codec.encode(new IllegalArgumentException("bad input"), IllegalArgumentException.class, noRefs());

        assertEquals(SampleEnum.SECOND, codec.decode(codec.encode(SampleEnum.SECOND, SampleEnum.class, noRefs()), SampleEnum.class, noRefs()));
        assertEquals("exception", exceptionValue.kind());
        assertEquals(Map.of("type", IllegalArgumentException.class.getName(), "message", "bad input"), exceptionValue.value());
    }

    @Test
    public void codecRejectsGenericRecordsWithoutInvokingAccessorsOrConstructors() {
        DefaultValueCodec codec = new DefaultValueCodec();
        HostileRecord record = new HostileRecord("alpha");
        HostileRecord.reset();

        MiddlewareException encodeFailure = assertThrows(MiddlewareException.class,
                () -> codec.encode(record, HostileRecord.class, noRefs()));

        assertEquals(MiddlewareErrorCode.CODEC_ERROR, encodeFailure.code());
        assertEquals(0, HostileRecord.ACCESSOR_CALLS.get());
        assertEquals(0, HostileRecord.CONSTRUCTOR_CALLS.get());

        EncodedValue encodedRecord = new EncodedValue("record", HostileRecord.class.getName(),
                Map.of("value", new EncodedValue("scalar", String.class.getName(), "beta", Map.of())), Map.of());
        HostileRecord.reset();

        MiddlewareException decodeFailure = assertThrows(MiddlewareException.class,
                () -> codec.decode(encodedRecord, HostileRecord.class, noRefs()));

        assertEquals(MiddlewareErrorCode.CODEC_ERROR, decodeFailure.code());
        assertEquals(0, HostileRecord.ACCESSOR_CALLS.get());
        assertEquals(0, HostileRecord.CONSTRUCTOR_CALLS.get());
    }

    @Test
    public void codecRoundTripsTrustedRegulatedExampleRecords() {
        DefaultValueCodec codec = new DefaultValueCodec();

        ComplianceSummary summary = new ComplianceSummary("strict", 4, false, "demo-only");
        EvidenceReport report = new EvidenceReport(Instant.EPOCH, List.of("control-a"), List.of("limitation-a"));
        ExampleCryptoPosture posture = new ExampleCryptoPosture(true, List.of("SHA-256"), false);
        AuditVerificationResult audit = new AuditVerificationResult(true, 3, "checkpoint");

        assertEquals(summary, codec.decode(codec.encode(summary, ComplianceSummary.class, noRefs()), ComplianceSummary.class, noRefs()));
        assertEquals(report, codec.decode(codec.encode(report, EvidenceReport.class, noRefs()), EvidenceReport.class, noRefs()));
        assertEquals(posture, codec.decode(codec.encode(posture, ExampleCryptoPosture.class, noRefs()), ExampleCryptoPosture.class, noRefs()));
        assertEquals(audit, codec.decode(codec.encode(audit, AuditVerificationResult.class, noRefs()), AuditVerificationResult.class, noRefs()));
    }

    @Test
    public void codecRoundTripsTrustedBankingExampleRecords() {
        DefaultValueCodec codec = new DefaultValueCodec();

        PaymentId paymentId = new PaymentId("payment-1");
        Money amount = new Money("USD", 4200L);
        ApprovalResult result = new ApprovalResult(true, "alice", "correlation-1");

        assertEquals(paymentId, codec.decode(codec.encode(paymentId, PaymentId.class, noRefs()), PaymentId.class, noRefs()));
        assertEquals(amount, codec.decode(codec.encode(amount, Money.class, noRefs()), Money.class, noRefs()));
        assertEquals(result, codec.decode(codec.encode(result, ApprovalResult.class, noRefs()), ApprovalResult.class, noRefs()));
    }

    @Test
    public void codecRoundTripsTrustedApplicationDeployerArgumentRecords() {
        DefaultValueCodec codec = new DefaultValueCodec();
        ApplicationCoordinate coordinate = new ApplicationCoordinate(
                new ApplicationId("org.example", "payments"),
                new ApplicationVersion("1.0.0"));
        ApplicationBundle bundle = new ApplicationBundle(Path.of("build", "tmp", "payments.j7m"), coordinate, "sha256");
        ApplicationActivationRequest activationRequest = new ApplicationActivationRequest(
                "alice",
                "dev",
                false,
                Map.of("profile", "test"));
        ApplicationInstanceId instanceId = new ApplicationInstanceId("instance-1");
        VerificationOptions verificationOptions = VerificationOptions.development();
        DeployOptions deployOptions = new DeployOptions("alice", false);
        DeactivationOptions deactivationOptions = new DeactivationOptions(false);
        UnloadOptions unloadOptions = new UnloadOptions(false);
        UpgradeOptions upgradeOptions = UpgradeOptions.stopStart();
        RollbackOptions rollbackOptions = new RollbackOptions("1.0.0");

        assertEquals(coordinate, codec.decode(codec.encode(coordinate, ApplicationCoordinate.class, noRefs()),
                ApplicationCoordinate.class, noRefs()));
        assertEquals(bundle, codec.decode(codec.encode(bundle, ApplicationBundle.class, noRefs()), ApplicationBundle.class, noRefs()));
        assertEquals(activationRequest, codec.decode(codec.encode(activationRequest, ApplicationActivationRequest.class, noRefs()),
                ApplicationActivationRequest.class, noRefs()));
        assertEquals(instanceId, codec.decode(codec.encode(instanceId, ApplicationInstanceId.class, noRefs()),
                ApplicationInstanceId.class, noRefs()));
        assertEquals(verificationOptions, codec.decode(codec.encode(verificationOptions, VerificationOptions.class, noRefs()),
                VerificationOptions.class, noRefs()));
        assertEquals(deployOptions, codec.decode(codec.encode(deployOptions, DeployOptions.class, noRefs()),
                DeployOptions.class, noRefs()));
        assertEquals(deactivationOptions, codec.decode(codec.encode(deactivationOptions, DeactivationOptions.class, noRefs()),
                DeactivationOptions.class, noRefs()));
        assertEquals(unloadOptions, codec.decode(codec.encode(unloadOptions, UnloadOptions.class, noRefs()),
                UnloadOptions.class, noRefs()));
        assertEquals(upgradeOptions, codec.decode(codec.encode(upgradeOptions, UpgradeOptions.class, noRefs()),
                UpgradeOptions.class, noRefs()));
        assertEquals(rollbackOptions, codec.decode(codec.encode(rollbackOptions, RollbackOptions.class, noRefs()),
                RollbackOptions.class, noRefs()));
    }

    @Test
    public void codecRejectsUnsupportedTypesWithoutInvokingToString() {
        DefaultValueCodec codec = new DefaultValueCodec();
        HostileToString value = new HostileToString();

        MiddlewareException failure = assertThrows(MiddlewareException.class,
                () -> codec.encode(value, HostileToString.class, noRefs()));

        assertEquals(MiddlewareErrorCode.CODEC_ERROR, failure.code());
        assertEquals(0, value.toStringCalls.get());
    }

    @Test
    public void codecRejectsEncodeCyclesAndBudgetExhaustion() {
        DefaultValueCodec codec = new DefaultValueCodec();
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);

        MiddlewareException cycleFailure = assertThrows(MiddlewareException.class,
                () -> codec.encode(cyclic, List.class, noRefs()));

        assertEquals(MiddlewareErrorCode.CODEC_ERROR, cycleFailure.code());

        Object tooDeep = "leaf";
        for (int depth = 0; depth < 18; depth++) {
            tooDeep = List.of(tooDeep);
        }
        Object excessiveDepth = tooDeep;

        MiddlewareException depthFailure = assertThrows(MiddlewareException.class,
                () -> codec.encode(excessiveDepth, List.class, noRefs()));

        assertEquals(MiddlewareErrorCode.CODEC_ERROR, depthFailure.code());

        List<List<String>> tooManyNodes = new ArrayList<>(1024);
        for (int index = 0; index < 1024; index++) {
            tooManyNodes.add(List.of("a", "b", "c", "d"));
        }

        MiddlewareException nodeFailure = assertThrows(MiddlewareException.class,
                () -> codec.encode(tooManyNodes, List.class, noRefs()));

        assertEquals(MiddlewareErrorCode.CODEC_ERROR, nodeFailure.code());
    }

    @Test
    public void codecRejectsUnsupportedMapKeysWithCodecError() {
        DefaultValueCodec codec = new DefaultValueCodec();

        MiddlewareException exception = assertThrows(MiddlewareException.class, () -> codec.encode(Map.of(1, "bad"), Map.class, noRefs()));

        assertEquals(MiddlewareErrorCode.CODEC_ERROR, exception.code());
    }

    /**
     * Marker interface used for proxy decode tests.
     */
    public interface Marker {
    }

    /**
     * Enum used for codec tests.
     */
    public enum SampleEnum {
        /**
         * First value.
         */
        FIRST,
        /**
         * Second value.
         */
        SECOND
    }

    private record HostileRecord(String value) {
        private static final AtomicInteger ACCESSOR_CALLS = new AtomicInteger();
        private static final AtomicInteger CONSTRUCTOR_CALLS = new AtomicInteger();

        HostileRecord {
            CONSTRUCTOR_CALLS.incrementAndGet();
        }

        public String value() {
            ACCESSOR_CALLS.incrementAndGet();
            return this.value;
        }

        static void reset() {
            ACCESSOR_CALLS.set(0);
            CONSTRUCTOR_CALLS.set(0);
        }
    }

    private static final class HostileToString {
        private final AtomicInteger toStringCalls = new AtomicInteger();

        @Override
        public String toString() {
            this.toStringCalls.incrementAndGet();
            return "hostile";
        }
    }

    private static CodecContext noRefs() {
        return new CodecContext() {
            @Override
            public Optional<ObjectRef> referenceFor(final Object value, final Class<?> declaredType) {
                return Optional.empty();
            }

            @Override
            public Object proxyFor(final ObjectRef ref, final Class<?> expectedType) {
                throw new AssertionError("proxyFor should not be called");
            }
        };
    }
}
