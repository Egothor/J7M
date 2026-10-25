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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.operations.api.NuggetAdminService;
import org.j7m.middleware.operations.api.NuggetAuthenticationFailureReason;
import org.j7m.middleware.operations.api.NuggetAuthenticationOutcome;
import org.j7m.middleware.operations.api.NuggetAuthenticationRequest;
import org.j7m.middleware.operations.api.NuggetAuthenticationSource;
import org.j7m.middleware.operations.api.NuggetIntrospection;
import org.j7m.middleware.runtime.NuggetAdminRuntimeConfiguration;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.junit.jupiter.api.Test;

/**
 * PIN verification, throttling, and concurrency tests for nugget admission.
 */
public final class NuggetPinAuthTest {
    private static final String FORMAT_VERSION = "j7m-pin-v1";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int VERIFIER_BYTES = 32;
    private static final int MIN_ITERATIONS = 210_000;
    private static final int MAX_ITERATIONS = 1_000_000;

    @Test
    public void verifierFileSourceAcceptsValidRecordAndRejectsMalformedRecords() throws Exception {
        Path directory = Files.createTempDirectory("j7m-verifier");
        Path verifierFile = directory.resolve("verifier.txt");
        try {
            trySetOwnerOnlyDirectoryPermissions(directory);
            Files.writeString(verifierFile, verifierRecord("correct-pin".toCharArray(), MIN_ITERATIONS));
            trySetOwnerOnlyPermissions(verifierFile);
            NuggetAdminService controller = NuggetAdminStartupTest.start(NuggetAdminStartupTest.baseConfig(new InMemoryAuditSink(16))
                    .pinFromProtectedVerifierFile(verifierFile)
                    .build());

            assertTrue(controller.authenticate(request("ok"), "correct-pin".toCharArray()).accepted());
            controller.close();

            assertVerifierFileRejected(verifierFile, verifierRecord("correct-pin".toCharArray(), MIN_ITERATIONS).replace(ALGORITHM, "plain"));
            assertVerifierFileRejected(verifierFile, verifierRecord("correct-pin".toCharArray(), MIN_ITERATIONS).replace("|210000|", "|209999|"));
            assertVerifierFileRejected(verifierFile, "j7m-pin-v1|bad");
            assertVerifierFileRejected(verifierFile, verifierRecord("correct-pin".toCharArray(), MIN_ITERATIONS) + "|extra");
            assertVerifierFileRejected(verifierFile, verifierRecord("correct-pin".toCharArray(), MIN_ITERATIONS).replace("|210000|", "|1000001|"));
            assertVerifierFileRejected(verifierFile, verifierRecord("correct-pin".toCharArray(), MIN_ITERATIONS).replace("|210000|", "|9999999999|"));
            assertVerifierFileRejected(verifierFile, verifierRecord("correct-pin".toCharArray(), MIN_ITERATIONS).replace(FORMAT_VERSION, "j7m-pin-v2"));
            assertVerifierFileRejected(verifierFile, verifierRecordWithSaltSize("correct-pin".toCharArray(), 64, MIN_ITERATIONS));
            assertVerifierFileRejected(verifierFile, verifierRecordWithVerifierSize("correct-pin".toCharArray(), 64, MIN_ITERATIONS));
            assertVerifierFileRejected(verifierFile, replaceField(verifierRecord("correct-pin".toCharArray(), MIN_ITERATIONS), 3, "!".repeat(24)));
            assertVerifierFileRejected(verifierFile, "x".repeat(1_025));
        } finally {
            Files.deleteIfExists(verifierFile);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void successfulAndWrongPinReturnSafeOutcomes() {
        InMemoryAuditSink audit = new InMemoryAuditSink(32);
        NuggetAdminService controller = NuggetAdminStartupTest.start(NuggetAdminStartupTest.baseConfig(audit)
                .pinFromProgrammaticProvider("protected", () -> "correct-pin".toCharArray())
                .build());

        assertTrue(controller.authenticate(request("ok"), "correct-pin".toCharArray()).accepted());
        assertEquals(NuggetAuthenticationOutcome.REJECTED, controller.authenticate(request("bad"), "wrong-pin".toCharArray()).outcome());
        assertEquals(NuggetAuthenticationFailureReason.REJECTED, controller.authenticate(request("bad2"), "wrong-pin".toCharArray()).failureReason());
        assertTrue(audit.recentEvents().stream().anyMatch(event -> event.action().equals("nugget.auth.accepted")));
        assertTrue(audit.recentEvents().stream().anyMatch(event -> event.action().equals("nugget.auth.rejected")));
    }

    @Test
    public void boundedPinInputIsRejectedBeforeVerification() {
        NuggetAdminService controller = NuggetAdminStartupTest.start(NuggetAdminStartupTest.baseConfig(new InMemoryAuditSink(16))
                .pinFromProgrammaticProvider("protected", () -> "short-pin".toCharArray())
                .maxPinBytes(16)
                .build());
        char[] overlong = "this-pin-is-longer-than-sixteen-bytes".toCharArray();

        assertEquals(NuggetAuthenticationOutcome.INVALID_REQUEST, controller.authenticate(request("long"), overlong).outcome());
    }

    @Test
    public void malformedBlankAndOversizedAuthenticationAttemptsThrottle() {
        AtomicLong nanos = new AtomicLong();
        NuggetAdminService controller = NuggetAdminStartupTest.start(NuggetAdminRuntimeConfiguration.builder()
                .security(NuggetAdminStartupTest.security(org.j7m.middleware.security.api.SecurityMode.DEV, new InMemoryAuditSink(64)))
                .clock(Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC))
                .monotonicTimeSource(nanos::get)
                .throttlePolicy(4, Duration.ofSeconds(30L), Duration.ofSeconds(4L))
                .pinFromProgrammaticProvider("protected", () -> "correct-pin".toCharArray())
                .build());

        assertEquals(NuggetAuthenticationOutcome.INVALID_REQUEST,
                controller.authenticate(request("blank"), " \t\n\u2003".toCharArray()).outcome());
        assertEquals(NuggetAuthenticationOutcome.INVALID_REQUEST,
                controller.authenticate(request("oversized"), "x".repeat(257).toCharArray()).outcome());
        assertEquals(NuggetAuthenticationOutcome.INVALID_REQUEST,
                controller.authenticate(request("malformed"), new char[] {'a', '\uD800'}).outcome());
        assertEquals(NuggetAuthenticationOutcome.THROTTLED,
                controller.authenticate(request("throttled"), "correct-pin".toCharArray()).outcome());
    }

    @Test
    public void hostileAuthenticationSourceAndCorrelationTextAreNormalizedBeforeAudit() {
        String sourceSecret = "source-secret-pin-sentinel";
        String correlationSecret = "correlation-token-sentinel";
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        NuggetAdminService controller = NuggetAdminStartupTest.start(NuggetAdminRuntimeConfiguration.builder()
                .security(NuggetAdminStartupTest.security(org.j7m.middleware.security.api.SecurityMode.DEV, audit))
                .clock(Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC))
                .monotonicTimeSource(new AtomicLong()::get)
                .throttlePolicy(4, Duration.ofSeconds(30L), Duration.ofSeconds(4L))
                .pinFromProgrammaticProvider("protected", () -> "correct-pin".toCharArray())
                .build());
        NuggetAuthenticationRequest request = NuggetAuthenticationRequest.of(
                correlationSecret + "-".repeat(100),
                NuggetAuthenticationSource.REMOTE,
                "https://user:" + sourceSecret + "@example.invalid/adm",
                true,
                Instant.EPOCH);

        assertEquals("", request.sourceId());
        assertFalse(request.correlationId().contains(correlationSecret));
        assertEquals(NuggetAuthenticationOutcome.REJECTED,
                controller.authenticate(request, "wrong-pin".toCharArray()).outcome());

        String publicText = request.toString()
                + controller.authenticate(request, "wrong-pin".toCharArray())
                + audit.recentEvents();
        assertFalse(publicText.contains(sourceSecret));
        assertFalse(publicText.contains(correlationSecret));
        assertFalse(publicText.contains("https://user:"));
    }

    @Test
    public void throttlingUsesDeterministicMonotonicTimeAndResetsOnSuccess() {
        AtomicLong nanos = new AtomicLong();
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        NuggetAdminService controller = NuggetAdminStartupTest.start(NuggetAdminRuntimeConfiguration.builder()
                .security(NuggetAdminStartupTest.security(org.j7m.middleware.security.api.SecurityMode.DEV, audit))
                .clock(Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC))
                .monotonicTimeSource(nanos::get)
                .throttlePolicy(4, Duration.ofSeconds(30L), Duration.ofSeconds(4L))
                .pinFromProgrammaticProvider("protected", () -> "correct-pin".toCharArray())
                .build());

        assertEquals(NuggetAuthenticationOutcome.REJECTED, controller.authenticate(request("bad1"), "bad-pin".toCharArray()).outcome());
        assertEquals(NuggetAuthenticationOutcome.REJECTED, controller.authenticate(request("bad2"), "bad-pin".toCharArray()).outcome());
        assertEquals(NuggetAuthenticationOutcome.REJECTED, controller.authenticate(request("bad3"), "bad-pin".toCharArray()).outcome());
        assertEquals(NuggetAuthenticationOutcome.THROTTLED, controller.authenticate(request("bad4"), "bad-pin".toCharArray()).outcome());
        nanos.addAndGet(Duration.ofSeconds(2L).toNanos());
        assertTrue(controller.authenticate(request("ok"), "correct-pin".toCharArray()).accepted());
        assertEquals(NuggetAuthenticationOutcome.REJECTED, controller.authenticate(request("after-reset"), "bad-pin".toCharArray()).outcome());
        assertTrue(audit.recentEvents().stream().anyMatch(event -> event.action().equals("nugget.auth.throttled")));
    }

    @Test
    public void remoteUntrustedAttributionDoesNotCreateUnlimitedBuckets() {
        AtomicLong nanos = new AtomicLong();
        NuggetAdminService controller = NuggetAdminStartupTest.start(NuggetAdminRuntimeConfiguration.builder()
                .security(NuggetAdminStartupTest.security(org.j7m.middleware.security.api.SecurityMode.DEV, new InMemoryAuditSink(64)))
                .clock(Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC))
                .monotonicTimeSource(nanos::get)
                .throttlePolicy(2, Duration.ofMinutes(15L), Duration.ofSeconds(4L))
                .pinFromProgrammaticProvider("protected", () -> "correct-pin".toCharArray())
                .build());

        for (int index = 0; index < 8; index++) {
            NuggetAuthenticationRequest request = NuggetAuthenticationRequest.of("remote-" + index,
                    NuggetAuthenticationSource.REMOTE, "attacker-" + index, false, Instant.EPOCH);
            controller.authenticate(request, "bad-pin".toCharArray());
        }
        assertEquals(NuggetAuthenticationOutcome.THROTTLED, controller.authenticate(NuggetAuthenticationRequest.of("remote-throttled",
                NuggetAuthenticationSource.REMOTE, "new-attacker", false, Instant.EPOCH), "bad-pin".toCharArray()).outcome());
    }

    @Test
    public void concurrentAuthenticationIsDeterministicAndSafe() throws Exception {
        NuggetAdminService controller = NuggetAdminStartupTest.start(NuggetAdminStartupTest.baseConfig(new InMemoryAuditSink(64))
                .pinFromProgrammaticProvider("protected", () -> "correct-pin".toCharArray())
                .build());
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            calls.add(() -> controller.authenticate(request("concurrent"), "correct-pin".toCharArray()).accepted());
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Boolean> results = executor.invokeAll(calls).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    })
                    .toList();
            assertEquals(12, results.size());
            assertTrue(results.stream().allMatch(Boolean::booleanValue));
        }
    }

    @Test
    public void verifierFileUpgradeOnSuccessPersistsNewPolicy() throws Exception {
        Path directory = Files.createTempDirectory("j7m-verifier");
        Path verifierFile = directory.resolve("verifier.txt");
        try {
            trySetOwnerOnlyDirectoryPermissions(directory);
            Files.writeString(verifierFile, verifierRecord("correct-pin".toCharArray(), MIN_ITERATIONS));
            trySetOwnerOnlyPermissions(verifierFile);
            NuggetAdminService controller = NuggetAdminStartupTest.start(NuggetAdminStartupTest.baseConfig(new InMemoryAuditSink(32))
                    .iterations(MIN_ITERATIONS + 1)
                    .pinFromProtectedVerifierFile(verifierFile)
                    .build());

            assertTrue(controller.authenticate(request("upgrade"), "correct-pin".toCharArray()).accepted());
            assertTrue(Files.readString(verifierFile).contains("|" + (MIN_ITERATIONS + 1) + "|"));
        } finally {
            Files.deleteIfExists(verifierFile);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void verifierFileUpgradeFailureKeepsExistingVerifier() throws Exception {
        Path directory = Files.createTempDirectory("j7m-verifier");
        Path verifierFile = directory.resolve("verifier.txt");
        try {
            Files.writeString(verifierFile, verifierRecord("correct-pin".toCharArray(), MIN_ITERATIONS));
            trySetOwnerOnlyPermissions(verifierFile);
            trySetOwnerOnlyDirectoryPermissions(directory);
            NuggetAdminService controller = NuggetAdminStartupTest.start(NuggetAdminStartupTest.baseConfig(new InMemoryAuditSink(32))
                    .iterations(MIN_ITERATIONS + 1)
                    .pinFromProtectedVerifierFile(verifierFile)
                    .build());
            trySetReadOnlyDirectory(directory);

            assertTrue(controller.authenticate(request("upgrade-fails"), "correct-pin".toCharArray()).accepted());
            assertTrue(controller.authenticate(request("still-valid"), "correct-pin".toCharArray()).accepted());
            assertTrue(Files.readString(verifierFile).contains("|" + MIN_ITERATIONS + "|"));
        } finally {
            trySetOwnerOnlyDirectoryPermissions(directory);
            Files.deleteIfExists(verifierFile);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void pinByteLimitUsesDeterministicUtf8WithoutTruncation() {
        char[] asciiAtLimit = "a".repeat(256).toCharArray();
        char[] asciiOverLimit = "a".repeat(257).toCharArray();
        char[] unicodeAtLimit = "€".repeat(85).concat("a").toCharArray();
        char[] unicodeOverLimit = "€".repeat(86).toCharArray();

        NuggetAdminService ascii = NuggetAdminStartupTest.start(NuggetAdminStartupTest.baseConfig(new InMemoryAuditSink(8))
                .pinFromProgrammaticProvider("protected", () -> Arrays.copyOf(asciiAtLimit, asciiAtLimit.length))
                .build());
        assertTrue(ascii.authenticate(request("ascii-limit"), asciiAtLimit).accepted());
        assertEquals(NuggetAuthenticationOutcome.INVALID_REQUEST, ascii.authenticate(request("ascii-over"), asciiOverLimit).outcome());

        NuggetAdminService unicode = NuggetAdminStartupTest.start(NuggetAdminStartupTest.baseConfig(new InMemoryAuditSink(8))
                .pinFromProgrammaticProvider("protected", () -> Arrays.copyOf(unicodeAtLimit, unicodeAtLimit.length))
                .build());
        assertTrue(unicode.authenticate(request("unicode-limit"), unicodeAtLimit).accepted());
        assertEquals(NuggetAuthenticationOutcome.INVALID_REQUEST, unicode.authenticate(request("unicode-over"), unicodeOverLimit).outcome());
    }

    @Test
    public void hugeOversizedPinFailsWithoutFullUtf8Encoding() {
        NuggetAdminService controller = NuggetAdminStartupTest.start(NuggetAdminStartupTest.baseConfig(new InMemoryAuditSink(8))
                .pinFromProgrammaticProvider("protected", () -> "short-pin".toCharArray())
                .maxPinBytes(16)
                .build());
        char[] huge = new char[1_000_000];
        Arrays.fill(huge, 'x');

        assertEquals(NuggetAuthenticationOutcome.INVALID_REQUEST, controller.authenticate(request("huge"), huge).outcome());
    }

    @Test
    public void admissionIdIsReusableUntilItExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-10T10:00:00Z"));
        NuggetAdminService controller = NuggetAdminStartupTest.start(NuggetAdminRuntimeConfiguration.builder()
                .security(NuggetAdminStartupTest.security(org.j7m.middleware.security.api.SecurityMode.DEV, new InMemoryAuditSink(32)))
                .clock(clock)
                .monotonicTimeSource(new AtomicLong()::get)
                .pinFromProgrammaticProvider("protected", () -> "correct-pin".toCharArray())
                .build());
        String admission = controller.authenticate(request("admit"), "correct-pin".toCharArray()).admissionId();

        NuggetIntrospection.Request firstRequest = new NuggetIntrospection.Request(admission, null, "first", clock.instant(), Map.of());
        assertTrue(controller.environment(firstRequest).admitted());
        assertTrue(controller.environment(firstRequest).admitted());

        clock.advance(Duration.ofMinutes(16L));
        NuggetIntrospection.Request expiredRequest = new NuggetIntrospection.Request(admission, null, "expired", clock.instant(), Map.of());
        assertFalse(controller.environment(expiredRequest).admitted());
    }

    @Test
    public void concurrentAuthenticationAndShutdownAreDeterministic() throws Exception {
        NuggetAdminService controller = NuggetAdminStartupTest.start(NuggetAdminStartupTest.baseConfig(new InMemoryAuditSink(32))
                .pinFromProgrammaticProvider("protected", () -> "correct-pin".toCharArray())
                .build());
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var auth = executor.submit(() -> {
                assertTrue(start.await(5L, TimeUnit.SECONDS));
                return controller.authenticate(request("auth"), "correct-pin".toCharArray()).outcome();
            });
            var close = executor.submit(() -> {
                assertTrue(start.await(5L, TimeUnit.SECONDS));
                controller.close();
                return Boolean.TRUE;
            });
            start.countDown();
            NuggetAuthenticationOutcome outcome = auth.get();
            assertTrue(close.get());
            assertTrue(outcome == NuggetAuthenticationOutcome.ACCEPTED || outcome == NuggetAuthenticationOutcome.UNAVAILABLE);
            assertEquals(NuggetAuthenticationOutcome.UNAVAILABLE,
                    controller.authenticate(request("after-close"), "correct-pin".toCharArray()).outcome());
        }
    }

    @Test
    public void repeatedShutdownIsSafe() {
        NuggetAdminService controller = NuggetAdminStartupTest.start(NuggetAdminStartupTest.baseConfig(new InMemoryAuditSink(8))
                .pinFromProgrammaticProvider("protected", () -> "correct-pin".toCharArray())
                .build());

        controller.close();
        controller.close();

        assertEquals(NuggetAuthenticationOutcome.UNAVAILABLE,
                controller.authenticate(request("closed"), "correct-pin".toCharArray()).outcome());
    }

    @Test
    public void configurationRejectsExcessiveIterations() {
        assertThrows(IllegalArgumentException.class, () -> NuggetAdminStartupTest.baseConfig(new InMemoryAuditSink(8))
                .iterations(MAX_ITERATIONS + 1));
    }

    @Test
    public void configurationRejectsExcessiveThrottlePolicy() {
        assertThrows(IllegalArgumentException.class, () -> NuggetAdminStartupTest.baseConfig(new InMemoryAuditSink(8))
                .throttlePolicy(4_097, Duration.ofMinutes(15L), Duration.ofSeconds(60L)));
        assertThrows(IllegalArgumentException.class, () -> NuggetAdminStartupTest.baseConfig(new InMemoryAuditSink(8))
                .throttlePolicy(1_024, Duration.ofHours(2L), Duration.ofSeconds(60L)));
        assertThrows(IllegalArgumentException.class, () -> NuggetAdminStartupTest.baseConfig(new InMemoryAuditSink(8))
                .throttlePolicy(1_024, Duration.ofMinutes(15L), Duration.ofSeconds(61L)));
    }

    private static NuggetAuthenticationRequest request(final String correlationId) {
        return NuggetAuthenticationRequest.of(correlationId, NuggetAuthenticationSource.LOCAL, "test-local", true, Instant.EPOCH);
    }

    private static void assertVerifierFileRejected(final Path verifierFile, final String record) throws Exception {
        Files.writeString(verifierFile, record);
        trySetOwnerOnlyPermissions(verifierFile);

        assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, assertThrows(MiddlewareException.class,
                () -> NuggetAdminStartupTest.start(NuggetAdminStartupTest.baseConfig(new InMemoryAuditSink(8))
                        .pinFromProtectedVerifierFile(verifierFile)
                        .build())).code());
    }

    private static String replaceField(final String record, final int fieldIndex, final String replacement) {
        String[] parts = record.split("\\|", -1);
        parts[fieldIndex] = replacement;
        return String.join("|", parts);
    }

    private static String verifierRecord(final char[] pin, final int iterations) {
        return verifierRecordWithSaltSize(pin, SALT_BYTES, VERIFIER_BYTES, iterations);
    }

    private static String verifierRecordWithSaltSize(final char[] pin, final int saltBytes, final int iterations) {
        return verifierRecordWithSaltSize(pin, saltBytes, VERIFIER_BYTES, iterations);
    }

    private static String verifierRecordWithVerifierSize(final char[] pin, final int verifierBytes, final int iterations) {
        return verifierRecordWithSaltSize(pin, SALT_BYTES, verifierBytes, iterations);
    }

    private static String verifierRecordWithSaltSize(
            final char[] pin,
            final int saltBytes,
            final int verifierBytes,
            final int iterations) {
        byte[] salt = new byte[saltBytes];
        Arrays.fill(salt, (byte) 7);
        byte[] verifier = derive(pin, salt, iterations, verifierBytes);
        try {
            return String.join("|",
                    FORMAT_VERSION,
                    ALGORITHM,
                    Integer.toString(iterations),
                    Base64.getEncoder().encodeToString(salt),
                    Base64.getEncoder().encodeToString(verifier),
                    "2026-07-10T10:00:00Z",
                    "2026-07-10T10:00:00Z",
                    "test-policy");
        } finally {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(verifier, (byte) 0);
        }
    }

    private static byte[] derive(final char[] pin, final byte[] salt, final int iterations, final int verifierBytes) {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, iterations, verifierBytes * 8);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException e) {
            throw new AssertionError(e);
        } finally {
            spec.clearPassword();
        }
    }

    private static void trySetOwnerOnlyPermissions(final Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platforms are covered by runtime safe-degradation policy.
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void trySetReadOnlyDirectory(final Path directory) {
        try {
            Files.setPosixFilePermissions(directory, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platforms exercise the same failed-upgrade policy through unavailable atomic replacement.
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void trySetOwnerOnlyDirectoryPermissions(final Path directory) {
        try {
            Files.setPosixFilePermissions(directory, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platforms are covered by runtime safe-degradation policy.
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(final Instant initial) {
            this.now = initial;
        }

        private void advance(final Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }
}
