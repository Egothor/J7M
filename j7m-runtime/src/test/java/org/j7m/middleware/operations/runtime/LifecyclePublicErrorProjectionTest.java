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

package org.j7m.middleware.operations.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.operations.api.NuggetApplicationLifecycle;
import org.j7m.middleware.security.api.AuditEvent;
import org.j7m.middleware.security.defaults.DefaultRedactor;
import org.junit.jupiter.api.Test;

/**
 * Verifies lifecycle public error projection for hostile provider metadata.
 */
final class LifecyclePublicErrorProjectionTest {
    @Test
    void lifecycleProjectionDropsSensitiveAndUnknownProviderDetails() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("reason", "verification-failed");
        source.put("profile", "strict");
        source.put("token", "opaque-secret-sentinel");
        source.put("password", "password-sentinel");
        source.put("pin", "pin-sentinel");
        source.put("admissionId", "admission-sentinel");
        source.put("credential", "credential-sentinel");
        source.put("path", "/secret/repository/key.pem");
        source.put("repository_path", "/secret/repository/key.pem");
        source.put("manifest", "manifest-sentinel");
        source.put("certificate", "certificate-sentinel");
        source.put("signature", "signature-sentinel");
        source.put("privateKey", "private-key-sentinel");
        source.put("stackTrace", "stack-sentinel");
        source.put("exception", "exception-sentinel");
        source.put("cause", "cause-sentinel");
        source.put("payload", "payload-sentinel");
        source.put("configuration", "configuration-sentinel");
        source.put("unknown", "opaque-secret-sentinel");
        source.put("phase", "plan\u001b[31m");

        LifecyclePublicErrorProjection.DetailProjection projection =
                LifecyclePublicErrorProjection.projectDetails(source, "lifecycle-failed");

        String rendered = projection.details().toString();
        assertEquals("verification-failed", projection.details().get("reason"));
        assertEquals("strict", projection.details().get("profile"));
        assertEquals("true", projection.details().get("detailsOmitted"));
        assertFalse(projection.details().containsKey("metadata"));
        assertFalse(rendered.contains("opaque-secret-sentinel"));
        assertFalse(rendered.contains("password-sentinel"));
        assertFalse(rendered.contains("pin-sentinel"));
        assertFalse(rendered.contains("admission-sentinel"));
        assertFalse(rendered.contains("credential-sentinel"));
        assertFalse(rendered.contains("/secret/repository/key.pem"));
        assertFalse(rendered.contains("stack-sentinel"));
        assertFalse(rendered.contains("exception-sentinel"));
        assertFalse(rendered.contains("payload-sentinel"));
        assertFalse(rendered.contains("\u001b"));
    }

    @Test
    void lifecycleProjectionDropsUnsafeValuesUnderAllowlistedKeys() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("reason", "Bearer abc-token");
        source.put("profile", "secret-token");
        source.put("phase", "/secret/repository/key.pem");
        source.put("state", "java.lang.IllegalStateException");
        source.put("policyCategory", "stackTrace:line");
        source.put("verificationCategory", "-----BEGIN-PRIVATE-KEY-----");
        source.put("action", "https://user:password@host");
        source.put("retryable", "token");
        source.put("result", "RUNNING");

        LifecyclePublicErrorProjection.DetailProjection projection =
                LifecyclePublicErrorProjection.projectDetails(source, "lifecycle-failed");

        String rendered = projection.details().toString();
        assertEquals("lifecycle-failed", projection.reason());
        assertEquals("RUNNING", projection.details().get("result"));
        assertEquals("true", projection.details().get("detailsOmitted"));
        assertFalse(projection.details().containsKey("profile"));
        assertFalse(projection.details().containsKey("phase"));
        assertFalse(projection.details().containsKey("state"));
        assertFalse(projection.details().containsKey("policyCategory"));
        assertFalse(projection.details().containsKey("verificationCategory"));
        assertFalse(projection.details().containsKey("action"));
        assertFalse(projection.details().containsKey("retryable"));
        assertFalse(rendered.contains("secret-token"));
        assertFalse(rendered.contains("/secret/repository/key.pem"));
        assertFalse(rendered.contains("IllegalStateException"));
        assertFalse(rendered.contains("PRIVATE-KEY"));
        assertFalse(rendered.contains("password"));
    }

    @Test
    void lifecycleProjectionUsesBoundedIterationOverHostileMaps() {
        InstrumentedMap source = new InstrumentedMap(1_000, true);

        LifecyclePublicErrorProjection.DetailProjection projection =
                LifecyclePublicErrorProjection.projectDetails(source, "lifecycle-failed");

        assertTrue(source.nextCalls.get() <= LifecyclePublicErrorProjection.MAX_PROVIDER_DETAILS_EXAMINED);
        assertTrue(projection.details().size() <= LifecyclePublicErrorProjection.MAX_PUBLIC_DETAILS);
        assertEquals("true", projection.details().get("detailsTruncated"));
    }

    @Test
    void lifecycleProjectionRejectsOversizedRawStringsBeforePublication() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put(".".repeat(LifecyclePublicErrorProjection.MAX_KEY_UTF8_BYTES + 1024), "strict");
        source.put("profile", " ".repeat(LifecyclePublicErrorProjection.MAX_VALUE_UTF8_BYTES + 1024));
        source.put("phase", "\uD800");
        source.put("state", "RUNNING");

        LifecyclePublicErrorProjection.DetailProjection projection =
                LifecyclePublicErrorProjection.projectDetails(source, "lifecycle-failed");

        assertEquals("RUNNING", projection.details().get("state"));
        assertEquals("true", projection.details().get("detailsOmitted"));
        assertFalse(projection.details().containsKey("profile"));
        assertFalse(projection.details().containsKey("phase"));
    }

    @Test
    void lifecycleProjectionKeepsAggregateAccountingAndCanonicalOrder() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("profile", "strict");
        source.put("result", "STOPPED");
        source.put("retryable", "true");
        source.put("policyCategory", "resource");
        source.put("verificationCategory", "signature");
        source.put("state", "RUNNING");
        source.put("phase", "plan");
        source.put("action", "stop");
        source.put("reason", "verification-failed");

        LifecyclePublicErrorProjection.DetailProjection projection =
                LifecyclePublicErrorProjection.projectDetails(source, "lifecycle-failed");
        RemoteError error = new RemoteError(
                MiddlewareErrorCode.PERMISSION_DENIED,
                "application lifecycle operation failed",
                projection.details(),
                "correlation",
                "runtime",
                false,
                false);

        List<String> expectedOrder = List.of(
                "action",
                "phase",
                "state",
                "reason",
                "profile",
                "verificationCategory",
                "policyCategory",
                "retryable",
                "result");
        assertEquals("verification-failed", projection.reason());
        assertEquals(expectedOrder, new ArrayList<>(projection.details().keySet()));
        assertEquals(expectedOrder, new ArrayList<>(error.details().keySet()));
        assertTrue(aggregateUtf8Bytes(error.details()) <= LifecyclePublicErrorProjection.MAX_AGGREGATE_UTF8_BYTES);
    }

    @Test
    void lifecycleProjectionDoesNotInvokeUnsupportedValueToString() {
        Map<Object, Object> source = new LinkedHashMap<>();
        source.put("reason", "verification-failed");
        source.put("profile", new ExplodingValue());

        LifecyclePublicErrorProjection.DetailProjection projection =
                LifecyclePublicErrorProjection.projectDetails(source, "lifecycle-failed");

        assertEquals("verification-failed", projection.reason());
        assertEquals("true", projection.details().get("detailsOmitted"));
        assertFalse(projection.details().containsKey("profile"));
        assertThrows(AssertionError.class, () -> new ExplodingValue().toString());
    }

    @Test
    void lifecycleProjectionTreatsThrowingEntryAccessAsOmitted() {
        LifecyclePublicErrorProjection.DetailProjection projection =
                LifecyclePublicErrorProjection.projectDetails(new ThrowingEntryMap(), "lifecycle-failed");

        assertEquals("lifecycle-failed", projection.reason());
        assertEquals("true", projection.details().get("detailsOmitted"));
    }

    @Test
    void publicDtoCopiesUseBoundedOrderedHostileMapHandling() {
        List<Map.Entry<String, String>> entries = List.of(
                Map.entry("reason", "verification-failed"),
                Map.entry("profile", "strict"),
                Map.entry("p-i-n", "pin-sentinel"),
                Map.entry("category", "s-e-c-r-e-t"),
                Map.entry("token", "opaque-secret-sentinel"),
                Map.entry("verificationCategory", "signature"),
                Map.entry("retryable", "false"));
        HostileStringMap hostile = new HostileStringMap(entries);

        RemoteError error = new RemoteError(
                MiddlewareErrorCode.PERMISSION_DENIED,
                "application lifecycle operation failed",
                hostile,
                "correlation",
                "runtime",
                false,
                false);

        assertEquals(List.of("reason", "profile", "verificationCategory", "retryable", "detailsOmitted"),
                new ArrayList<>(error.details().keySet()));
        assertFalse(error.details().toString().contains("opaque-secret-sentinel"));
        assertFalse(error.details().toString().contains("pin-sentinel"));
        assertFalse(error.details().toString().contains("s-e-c-r-e-t"));

        NuggetApplicationLifecycle.Result result = new NuggetApplicationLifecycle.Result(
                "operation",
                "correlation",
                NuggetApplicationLifecycle.Action.STOP,
                true,
                true,
                false,
                "nugget",
                "runtime",
                null,
                null,
                ApplicationRunState.RUNNING,
                ApplicationRunState.RUNNING,
                ApplicationRunState.RUNNING,
                NuggetApplicationLifecycle.Outcome.FAILED,
                "application lifecycle operation failed",
                false,
                false,
                true,
                false,
                true,
                "",
                "allowed",
                null,
                null,
                null,
                List.of(),
                error,
                java.time.Instant.EPOCH,
                java.time.Instant.EPOCH,
                java.time.Duration.ZERO,
                hostile);

        assertEquals(List.of("reason", "profile", "verificationCategory", "retryable", "detailsOmitted"),
                new ArrayList<>(result.metadata().keySet()));
        assertFalse(result.metadata().toString().contains("opaque-secret-sentinel"));
        assertFalse(result.metadata().toString().contains("pin-sentinel"));
        assertFalse(result.metadata().toString().contains("s-e-c-r-e-t"));
    }

    @Test
    void auditAndRedactorUseBoundedHostileMapHandling() {
        InstrumentedMap source = new InstrumentedMap(1_000, true);

        Map<String, String> redacted = new DefaultRedactor().redactMetadata(source);
        AuditEvent event = new AuditEvent(
                Instant.EPOCH,
                "runtime",
                "principal",
                "action",
                "resource",
                "decision",
                "policy",
                "correlation",
                "invocation",
                "endpoint",
                "reason",
                "transport",
                "default",
                source);

        assertTrue(source.nextCalls.get() <= 64);
        assertTrue(redacted.size() <= 32);
        assertTrue(event.metadata().size() <= 32);
        assertEquals("true", redacted.get("detailsOmitted"));
        assertEquals("true", event.metadata().get("detailsOmitted"));
        assertFalse(redacted.toString().contains("phase-999"));
        assertFalse(event.metadata().toString().contains("phase-999"));
    }

    @Test
    void auditEventDropsSensitiveOriginalKeysWithPlainValues() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("reason", "verification-failed");
        source.put("pin", "123456");
        source.put("admissionId", "plain");
        source.put("token", "plain");
        source.put("password", "plain");
        source.put("p-a-t-h", "plain");
        source.put("safe", "<redacted>");
        source.put("safeBracket", "[redacted]");

        AuditEvent event = new AuditEvent(
                Instant.EPOCH,
                "runtime",
                "principal",
                "action",
                "resource",
                "decision",
                "policy",
                "correlation",
                "invocation",
                "endpoint",
                "reason",
                "transport",
                "default",
                source);

        assertEquals("verification-failed", event.metadata().get("reason"));
        assertEquals("<redacted>", event.metadata().get("safe"));
        assertEquals("[redacted]", event.metadata().get("safeBracket"));
        assertEquals("true", event.metadata().get("detailsOmitted"));
        assertFalse(event.metadata().containsKey("pin"));
        assertFalse(event.metadata().containsKey("admissionId"));
        assertFalse(event.metadata().containsKey("token"));
        assertFalse(event.metadata().containsKey("password"));
        assertFalse(event.metadata().containsKey("p-a-t-h"));
    }

    private static int aggregateUtf8Bytes(final Map<String, String> details) {
        int total = 0;
        for (Map.Entry<String, String> entry : details.entrySet()) {
            total += entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            total += entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        return total;
    }

    private static final class HostileStringMap extends AbstractMap<String, String> {
        private final List<Entry<String, String>> entries;

        private HostileStringMap(final List<Entry<String, String>> entries) {
            this.entries = entries;
        }

        @Override
        public boolean isEmpty() {
            throw new AssertionError("isEmpty must not be called");
        }

        @Override
        public int size() {
            throw new AssertionError("size must not be called");
        }

        @Override
        public Set<Entry<String, String>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, String>> iterator() {
                    return HostileStringMap.this.entries.iterator();
                }

                @Override
                public int size() {
                    throw new AssertionError("entrySet size must not be called");
                }
            };
        }
    }

    private static final class ThrowingEntryMap extends AbstractMap<Object, Object> {
        @Override
        public Set<Entry<Object, Object>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<Object, Object>> iterator() {
                    return List.<Entry<Object, Object>>of(new Entry<>() {
                        @Override
                        public Object getKey() {
                            throw new IllegalStateException("provider entry key access failed");
                        }

                        @Override
                        public Object getValue() {
                            return "strict";
                        }

                        @Override
                        public Object setValue(final Object value) {
                            throw new UnsupportedOperationException();
                        }
                    }).iterator();
                }

                @Override
                public int size() {
                    throw new AssertionError("entrySet size must not be called");
                }
            };
        }
    }

    private static final class InstrumentedMap extends AbstractMap<String, String> {
        private final int entries;
        private final boolean hostileSizeMethods;
        private final AtomicInteger nextCalls = new AtomicInteger();

        private InstrumentedMap(final int entries, final boolean hostileSizeMethods) {
            this.entries = entries;
            this.hostileSizeMethods = hostileSizeMethods;
        }

        @Override
        public boolean isEmpty() {
            if (this.hostileSizeMethods) {
                throw new AssertionError("isEmpty must not be called");
            }
            return super.isEmpty();
        }

        @Override
        public int size() {
            if (this.hostileSizeMethods) {
                throw new AssertionError("size must not be called");
            }
            return super.size();
        }

        @Override
        public Set<Entry<String, String>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, String>> iterator() {
                    return new Iterator<>() {
                        private int index;

                        @Override
                        public boolean hasNext() {
                            return this.index < InstrumentedMap.this.entries;
                        }

                        @Override
                        public Entry<String, String> next() {
                            int current = this.index++;
                            InstrumentedMap.this.nextCalls.incrementAndGet();
                            if (current == 0) {
                                return Map.entry("reason", "verification-failed");
                            }
                            if (current == 1) {
                                return Map.entry("profile", "strict");
                            }
                            return Map.entry("phase", "phase-" + current);
                        }
                    };
                }

                @Override
                public int size() {
                    if (InstrumentedMap.this.hostileSizeMethods) {
                        throw new AssertionError("entrySet size must not be called");
                    }
                    return InstrumentedMap.this.entries;
                }
            };
        }
    }

    private static final class ExplodingValue {
        @Override
        public String toString() {
            throw new AssertionError("toString must not be called");
        }
    }
}
