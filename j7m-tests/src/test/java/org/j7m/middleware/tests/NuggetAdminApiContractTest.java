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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.application.api.ActivationIdentity;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationDeploymentPlan;
import org.j7m.middleware.application.api.ApplicationDeploymentResult;
import org.j7m.middleware.application.api.ApplicationIdentity;
import org.j7m.middleware.application.api.ApplicationInstanceId;
import org.j7m.middleware.application.api.ApplicationInstanceIdentity;
import org.j7m.middleware.application.api.RuntimeServiceIdentity;
import org.j7m.middleware.operations.api.NuggetAdminCapabilities;
import org.j7m.middleware.operations.api.NuggetAdminCommandRequest;
import org.j7m.middleware.operations.api.NuggetAdminCommandResult;
import org.j7m.middleware.operations.api.NuggetAdminContract;
import org.j7m.middleware.operations.api.NuggetAdminField;
import org.j7m.middleware.operations.api.NuggetAdminOperationKind;
import org.j7m.middleware.operations.api.NuggetAdminOperationKind.PayloadBinding;
import org.j7m.middleware.operations.api.NuggetAdminOutcome;
import org.j7m.middleware.operations.api.NuggetAdminQuery;
import org.j7m.middleware.operations.api.NuggetAdminRequestContext;
import org.j7m.middleware.operations.api.NuggetAdminResultHeader;
import org.j7m.middleware.operations.api.NuggetAdminSortDirection;
import org.j7m.middleware.operations.api.NuggetAdminState;
import org.j7m.middleware.operations.api.NuggetAdminTarget;
import org.j7m.middleware.operations.api.NuggetAdminService;
import org.j7m.middleware.operations.api.NuggetApplicationLifecycle;
import org.j7m.middleware.operations.api.NuggetAuthenticationFailureReason;
import org.j7m.middleware.operations.api.NuggetAuthenticationOutcome;
import org.j7m.middleware.operations.api.NuggetAuthenticationRequest;
import org.j7m.middleware.operations.api.NuggetAuthenticationResult;
import org.j7m.middleware.operations.api.NuggetCapability;
import org.j7m.middleware.operations.api.NuggetConfigurationSummary;
import org.j7m.middleware.operations.api.NuggetControl;
import org.j7m.middleware.operations.api.NuggetDiscovery;
import org.j7m.middleware.operations.api.NuggetIdentity;
import org.j7m.middleware.operations.api.NuggetIntrospection;
import org.j7m.middleware.operations.api.NuggetPinSourceType;
import org.j7m.middleware.operations.api.NuggetState;
import org.j7m.middleware.operations.api.NuggetStatus;
import org.j7m.middleware.security.api.SecurityContext;

/**
 * Focused Phase 7.8 public administration contract tests.
 */
public final class NuggetAdminApiContractTest {
    private static final long MAX_DOCUMENT_BYTES = 262_144L;
    private static final long MAX_SOURCE_SCAN_BYTES = 1_048_576L;
    private static final Path ROOT = repositoryRoot();

    @org.junit.jupiter.api.Test
    void targetAndRequestContextExposeVersionTargetAndGenerationSemantics() {
        NuggetAdminTarget target = new NuggetAdminTarget("nugget-a", "runtime-a", 0L, "j7m-local:admin", "provider-a");

        NuggetAdminRequestContext context = new NuggetAdminRequestContext(
                NuggetAdminContract.PROTOCOL_VERSION,
                "request-a",
                "correlation-a",
                target,
                NuggetAdminOperationKind.INTROSPECTION,
                Instant.parse("2026-01-01T00:00:00Z"),
                Duration.ofSeconds(2L),
                Instant.parse("2026-01-01T00:00:02Z"),
                ordered("alpha", "one", "beta", "two"));

        assertEquals(0L, context.target().generation());
        assertEquals(List.of("alpha", "beta"), List.copyOf(context.metadata().keySet()));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminRequestContext(
                NuggetAdminContract.PROTOCOL_VERSION + 1,
                "request-b",
                "correlation-b",
                target,
                NuggetAdminOperationKind.INTROSPECTION,
                Instant.EPOCH,
                Duration.ZERO,
                Instant.EPOCH,
                Map.of()));
    }

    @org.junit.jupiter.api.Test
    void queryBoundsOrderingTieBreakerAndOverflowAreDeterministic() {
        NuggetAdminQuery query = new NuggetAdminQuery(
                7L,
                10,
                List.of(new NuggetAdminQuery.Filter(NuggetAdminField.STATE, "RUNNING")),
                List.of(new NuggetAdminQuery.Sort(NuggetAdminField.NUGGET_ID, NuggetAdminSortDirection.DESC)));

        assertEquals(7L, query.offset());
        assertEquals(NuggetAdminField.NUGGET_ID, query.sorts().get(0).field());
        assertEquals(NuggetAdminField.STABLE_ID, query.sorts().get(1).field());
        assertThrows(UnsupportedOperationException.class, () -> query.sorts().add(
                new NuggetAdminQuery.Sort(NuggetAdminField.RUNTIME_ID, NuggetAdminSortDirection.ASC)));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminQuery(
                Long.MAX_VALUE,
                1,
                List.of(),
                List.of()));

        List<NuggetAdminQuery.Filter> filters = new ArrayList<>();
        for (int index = 0; index <= NuggetAdminContract.MAX_FILTERS; index++) {
            filters.add(new NuggetAdminQuery.Filter(NuggetAdminField.STATE, "S" + index));
        }
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminQuery(0L, 1, filters, List.of()));
    }

    @org.junit.jupiter.api.Test
    void queryRejectsHostileLyingListsWithoutTrustingReportedSize() {
        List<NuggetAdminQuery.Filter> filters = new ArrayList<>(NuggetAdminContract.MAX_FILTERS + 1);
        for (int index = 0; index <= NuggetAdminContract.MAX_FILTERS; index++) {
            filters.add(new NuggetAdminQuery.Filter(NuggetAdminField.STATE, "F" + index));
        }
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminQuery(
                0L,
                1,
                new LyingList<>(filters),
                List.of()));

        List<NuggetAdminQuery.Sort> sorts = new ArrayList<>(NuggetAdminContract.MAX_SORTS + 1);
        for (int index = 0; index <= NuggetAdminContract.MAX_SORTS; index++) {
            sorts.add(new NuggetAdminQuery.Sort(NuggetAdminField.NUGGET_ID, NuggetAdminSortDirection.ASC));
        }
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminQuery(
                0L,
                1,
                List.of(),
                new LyingList<>(sorts)));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminQuery(
                0L,
                1,
                new HostileList<>(),
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminQuery(
                0L,
                1,
                List.of(),
                new HostileList<>()));
    }

    @org.junit.jupiter.api.Test
    void metadataCopiesAreOrderedBoundedRedactedAndHostileSafe() {
        NuggetAuthenticationResult result = new NuggetAuthenticationResult(
                NuggetAuthenticationOutcome.ACCEPTED,
                NuggetAuthenticationFailureReason.NONE,
                "nugget-a",
                "correlation-a",
                "admission-a",
                Duration.ZERO,
                Instant.EPOCH,
                null,
                ordered("pin", "123456", "alpha", "one", "beta", "two"));

        assertEquals(List.of("alpha", "beta", "detailsOmitted"), List.copyOf(result.metadata().keySet()));
        assertFalse(result.toString().contains("123456"));
        assertFalse(result.toString().contains("admission-a"));
        assertFalse(result.toString().contains("pin"));

        NuggetAuthenticationResult hostile = new NuggetAuthenticationResult(
                NuggetAuthenticationOutcome.REJECTED,
                NuggetAuthenticationFailureReason.REJECTED,
                "nugget-a",
                "correlation-a",
                "",
                Duration.ZERO,
                Instant.EPOCH,
                null,
                new HostileMap());
        assertEquals(Map.of("detailsOmitted", "true"), hostile.metadata());
    }

    @org.junit.jupiter.api.Test
    void authPrimaryFieldsAreBoundedAndRedacted() {
        String oversized = "nugget-" + "x".repeat(1024);
        NuggetAuthenticationResult result = new NuggetAuthenticationResult(
                NuggetAuthenticationOutcome.ACCEPTED,
                NuggetAuthenticationFailureReason.NONE,
                oversized,
                oversized,
                "admission-secret-sentinel-" + "y".repeat(1024),
                Duration.ZERO,
                Instant.EPOCH,
                null,
                ordered("metadata", "pin-secret-sentinel", "safe", "value"));

        assertEquals("", result.nuggetId());
        assertEquals("", result.correlationId());
        assertEquals("", result.admissionId());
        assertFalse(result.toString().contains("admission-secret-sentinel"));
        assertFalse(result.toString().contains("pin-secret-sentinel"));
        assertFalse(result.toString().contains(oversized));
    }

    @org.junit.jupiter.api.Test
    void optionalStringsRejectOverBudgetBlankPrefixesThroughPublicConstructors() {
        String blankTokenWithinBudget = " ".repeat(128);
        String longLeadingBlankToken = " ".repeat(10_000) + "nugget-a";
        NuggetAuthenticationResult result = new NuggetAuthenticationResult(
                NuggetAuthenticationOutcome.ACCEPTED,
                NuggetAuthenticationFailureReason.NONE,
                blankTokenWithinBudget,
                longLeadingBlankToken,
                longLeadingBlankToken,
                Duration.ZERO,
                Instant.EPOCH,
                null,
                Map.of());

        assertEquals("", result.nuggetId());
        assertEquals("", result.correlationId());
        assertEquals("", result.admissionId());

        NuggetAdminCapabilities unavailable = new NuggetAdminCapabilities(
                false,
                0,
                longLeadingBlankToken,
                0,
                List.of(),
                List.of(),
                0,
                0,
                Instant.EPOCH,
                Map.of());
        assertEquals("", unavailable.jsonFormatName());

        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminQuery.Filter(
                NuggetAdminField.STATE,
                " ".repeat(512)));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminQuery.Filter(
                NuggetAdminField.STATE,
                " ".repeat(10_000) + "RUNNING"));
    }

    @org.junit.jupiter.api.Test
    void metadataDoesNotTrustHostileMapEmptinessOrSize() {
        NuggetAuthenticationResult result = new NuggetAuthenticationResult(
                NuggetAuthenticationOutcome.ACCEPTED,
                NuggetAuthenticationFailureReason.NONE,
                "nugget-a",
                "correlation-a",
                "admission-a",
                Duration.ZERO,
                Instant.EPOCH,
                null,
                new SizeHostileMap(ordered("alpha", "one", "beta", "two")));

        assertEquals(List.of("alpha", "beta"), List.copyOf(result.metadata().keySet()));

        NuggetAdminCapabilities capabilities = new NuggetAdminCapabilities(
                true,
                NuggetAdminContract.PROTOCOL_VERSION,
                NuggetAdminContract.JSON_FORMAT_NAME,
                NuggetAdminContract.JSON_FORMAT_VERSION,
                List.of(org.j7m.middleware.operations.api.NuggetCapability.READ_INFO),
                List.of(NuggetAdminOperationKind.CAPABILITIES),
                NuggetAdminContract.MAX_PAGE_LIMIT,
                NuggetAdminContract.MAX_METADATA_ENTRIES,
                Instant.EPOCH,
                new SizeHostileMap(ordered("gamma", "three", "delta", "four")));

        assertEquals(List.of("gamma", "delta"), List.copyOf(capabilities.metadata().keySet()));
    }

    @org.junit.jupiter.api.Test
    void resultWarningsAreBoundedAndHostileSafe() {
        NuggetAdminRequestContext context = context(NuggetAdminOperationKind.INTROSPECTION);
        NuggetAdminResultHeader hostile = resultHeader(context, new HostileList<>());
        assertEquals(List.of("details-omitted"), hostile.warnings());

        List<String> warnings = new ArrayList<>(NuggetAdminContract.MAX_WARNING_ENTRIES + 1);
        for (int index = 0; index <= NuggetAdminContract.MAX_WARNING_ENTRIES; index++) {
            warnings.add("warning-" + index);
        }
        NuggetAdminResultHeader truncated = resultHeader(context, new LyingList<>(warnings));
        assertEquals(NuggetAdminContract.MAX_WARNING_ENTRIES, truncated.warnings().size());
        assertEquals("details-omitted", truncated.warnings().get(truncated.warnings().size() - 1));
    }

    @org.junit.jupiter.api.Test
    void commandEnvelopeRejectsFreeFormPayloadAndRedactsPayloadToString() {
        NuggetAdminRequestContext context = context(NuggetAdminOperationKind.NUGGET_CONTROL);
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandRequest<>(context, Map.of("method", "kill"), ""));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandRequest<>(context, "kill-nugget", ""));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandRequest<>(context, new String[] {"kill"}, ""));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandRequest<>(context, List.of("kill"), ""));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandResult<>(unavailableHeader(context), Map.of("result", "ok"), ""));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandResult<>(unavailableHeader(context), "result", ""));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandResult<>(unavailableHeader(context), new String[] {"ok"}, ""));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandResult<>(unavailableHeader(context), List.of("ok"), ""));

        LeakyPayload payload = new LeakyPayload();
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandRequest<>(context, payload, ""));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandResult<>(unavailableHeader(context), payload, ""));
    }

    @org.junit.jupiter.api.Test
    void olderServiceDefaultsReturnTruthfulUnavailable() {
        NuggetAdminCommandResult<NuggetAdminCapabilities> capabilities =
                new DefaultAdminService().adminCapabilities(context(NuggetAdminOperationKind.CAPABILITIES));
        NuggetAdminCommandResult<?> command = new DefaultAdminService().adminCommand(
                new NuggetAdminCommandRequest<>(
                        context(NuggetAdminOperationKind.NUGGET_CONTROL),
                        sampleControlRequest(),
                        ""));

        assertEquals(NuggetAdminOutcome.UNAVAILABLE, capabilities.header().outcome());
        assertFalse(capabilities.header().completed());
        assertFalse(capabilities.payload().available());
        assertEquals(NuggetAdminCapabilities.PAYLOAD_TYPE, capabilities.payloadType());
        assertEquals(NuggetAdminOutcome.UNAVAILABLE, command.header().outcome());
        assertTrue(command.header().unavailable());
        assertFalse(command.header().completed());
    }

    @org.junit.jupiter.api.Test
    void operationBindingMatrixAcceptsOnlyExactPublicFamilies() {
        for (NuggetAdminOperationKind kind : NuggetAdminOperationKind.values()) {
            NuggetAdminRequestContext requestContext = context(kind);
            if (kind.requestPayloadMayBeNull()) {
                NuggetAdminCommandRequest<?> request = new NuggetAdminCommandRequest<>(requestContext, null, "");
                assertEquals("", request.payloadType());
            } else {
                assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandRequest<>(requestContext, null, ""));
            }

            for (PayloadBinding binding : kind.requestPayloads()) {
                assertTrue(binding.publicContractValidationCovered(), binding.payloadClass().getName());
                Object payload = samplePayload(binding.payloadClass());
                NuggetAdminCommandRequest<?> inferred = new NuggetAdminCommandRequest<>(requestContext, payload, "");
                NuggetAdminCommandRequest<?> explicit = new NuggetAdminCommandRequest<>(
                        requestContext,
                        payload,
                        binding.payloadType());
                assertEquals(binding.payloadType(), inferred.payloadType());
                assertEquals(binding.payloadType(), explicit.payloadType());
                assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandRequest<>(
                        requestContext,
                        payload,
                        "wrong-payload-type"));
            }

            for (PayloadBinding binding : kind.resultPayloads()) {
                assertTrue(binding.publicContractValidationCovered(), binding.payloadClass().getName());
                Object payload = samplePayload(binding.payloadClass());
                NuggetAdminCommandResult<?> inferred = commandResult(
                        completedHeader(kind),
                        payload,
                        "");
                NuggetAdminCommandResult<?> explicit = commandResult(
                        completedHeader(kind),
                        payload,
                        binding.payloadType());
                assertEquals(binding.payloadType(), inferred.payloadType());
                assertEquals(binding.payloadType(), explicit.payloadType());
                assertThrows(IllegalArgumentException.class, () -> commandResult(
                        completedHeader(kind),
                        payload,
                        "wrong-payload-type"));
            }

            for (NuggetAdminOperationKind otherKind : NuggetAdminOperationKind.values()) {
                if (otherKind != kind) {
                    for (PayloadBinding binding : otherKind.requestPayloads()) {
                        Object payload = samplePayload(binding.payloadClass());
                        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandRequest<>(
                                requestContext,
                                payload,
                                ""));
                        assertThrows(IllegalArgumentException.class, () -> commandResult(
                                completedHeader(kind),
                                payload,
                                ""));
                    }
                    for (PayloadBinding binding : otherKind.resultPayloads()) {
                        Object payload = samplePayload(binding.payloadClass());
                        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandRequest<>(
                                requestContext,
                                payload,
                                ""));
                        assertThrows(IllegalArgumentException.class, () -> commandResult(
                                completedHeader(kind),
                                payload,
                                ""));
                    }
                }
            }
        }
    }

    @org.junit.jupiter.api.Test
    void invalidCommandEnvelopePayloadsDoNotReachProviderInvocation() {
        CountingAdminService service = new CountingAdminService();
        assertThrows(IllegalArgumentException.class, () -> {
            NuggetAdminCommandRequest<?> request = new NuggetAdminCommandRequest<>(
                    context(NuggetAdminOperationKind.NUGGET_CONTROL),
                    new CliPrivatePayload(),
                    "");
            service.adminCommand(request);
        });
        assertEquals(0, service.commandCount());
    }

    @org.junit.jupiter.api.Test
    void commandRequestRejectsRepeatedTargetMismatchBeforeProviderInvocation() {
        CountingAdminService service = new CountingAdminService();
        NuggetAdminRequestContext context = context(NuggetAdminOperationKind.NUGGET_CONTROL);
        NuggetControl.Request mismatched = new NuggetControl.Request(
                "admission-a",
                null,
                "control-operation",
                "correlation-default",
                NuggetControl.Action.DRAIN_NUGGET,
                NuggetControl.Mode.GRACEFUL,
                "nugget-b",
                "runtime-a",
                1L,
                "",
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(30L),
                Map.of());

        assertThrows(IllegalArgumentException.class, () -> {
            NuggetAdminCommandRequest<?> request = new NuggetAdminCommandRequest<>(context, mismatched, "");
            service.adminCommand(request);
        });
        assertEquals(0, service.commandCount());
    }

    @org.junit.jupiter.api.Test
    void commandResultRejectsRepeatedTargetAndOperationIdMismatchBeforePublication() {
        NuggetAdminResultHeader header = outcomeHeader(
                NuggetAdminOperationKind.NUGGET_CONTROL,
                NuggetAdminOutcome.ACCEPTED,
                "control-header");

        assertThrows(IllegalArgumentException.class, () -> commandResult(
                header,
                sampleControlResult(NuggetControl.Outcome.ACCEPTED, "control-payload"),
                ""));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandResult<>(
                header,
                sampleControlResult(NuggetControl.Outcome.ACCEPTED, "control-header"),
                ""));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandResult<>(
                new NuggetAdminTarget("nugget-b", "runtime-a", 1L, "j7m-local:admin", "provider-a"),
                header,
                sampleControlResult(NuggetControl.Outcome.ACCEPTED, "control-header"),
                ""));
        assertThrows(IllegalArgumentException.class, () -> commandResult(
                outcomeHeader(NuggetAdminOperationKind.APPLICATION_LIFECYCLE, NuggetAdminOutcome.DENIED, "lifecycle-header"),
                sampleLifecycleResult(NuggetApplicationLifecycle.Outcome.FAILED, "lifecycle-payload"),
                ""));
    }

    @org.junit.jupiter.api.Test
    void commandEnvelopesNormalizeUnsafeTransitiveJavaPayloadsBeforeRetention() {
        NuggetIntrospection.CollectionResult<NuggetIntrospection.KeyValue> unsafeCollection =
                new NuggetIntrospection.CollectionResult<>(
                        List.of(new NuggetIntrospection.KeyValue("secretKey", "<script>secret-token</script>")),
                        0,
                        50,
                        99,
                        -1,
                        false,
                        "id",
                        NuggetIntrospection.SortDirection.ASC,
                        ordered("pin", "123456", "safe", "value"),
                        Instant.EPOCH,
                        false,
                        ordered("path", "/tmp/secret", "safe", "value"));
        NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.KeyValue>> unsafe =
                new NuggetIntrospection.Result<>(
                        true,
                        true,
                        NuggetIntrospection.Category.METRICS,
                        Instant.EPOCH,
                        false,
                        unsafeCollection,
                        new RemoteError(
                                MiddlewareErrorCode.INTERNAL,
                                "<script>secret-token</script>",
                                ordered("token", "secret", "safe", "value"),
                                "invocation-secret-token",
                                "runtime-secret-token",
                                false,
                                false),
                        List.of("token-secret-warning", "safe-warning"),
                        ordered("pin", "123456", "safe", "value"));

        NuggetAdminCommandResult<?> envelope = commandResult(
                completedHeader(NuggetAdminOperationKind.INTROSPECTION),
                unsafe,
                "");
        NuggetIntrospection.Result<?> normalized = (NuggetIntrospection.Result<?>) envelope.payload();
        NuggetIntrospection.CollectionResult<?> normalizedCollection =
                (NuggetIntrospection.CollectionResult<?>) normalized.value();
        NuggetIntrospection.KeyValue normalizedItem =
                (NuggetIntrospection.KeyValue) normalizedCollection.items().get(0);

        assertNotSame(unsafe, normalized);
        assertEquals(List.of("safe-warning", "details-omitted"), normalized.warnings());
        assertEquals("value", normalized.metadata().get("safe"));
        assertEquals("true", normalized.metadata().get("detailsOmitted"));
        assertEquals(MiddlewareErrorCode.INTERNAL.name(), normalized.error().message());
        assertEquals("", normalized.error().invocationId());
        assertEquals("", normalized.error().remoteRuntimeId());
        assertEquals(1, normalizedCollection.returned());
        assertEquals(1, normalizedCollection.totalAvailable());
        assertEquals("value", normalizedCollection.metadata().get("safe"));
        assertEquals("true", normalizedCollection.metadata().get("detailsOmitted"));
        assertEquals("", normalizedItem.key());
        assertEquals("", normalizedItem.value());
    }

    @org.junit.jupiter.api.Test
    void introspectionScalarNumbersNormalizeOnlyBoundedPublicLongValues() {
        NuggetIntrospection.Result<?> integerValue = introspectionScalarResult(Integer.valueOf(7));
        NuggetAdminCommandResult<?> integerEnvelope = commandResult(
                completedHeader(NuggetAdminOperationKind.INTROSPECTION),
                integerValue,
                "");
        assertEquals(Long.valueOf(7L), ((NuggetIntrospection.Result<?>) integerEnvelope.payload()).value());

        NuggetIntrospection.Result<?> bigIntegerValue = introspectionScalarResult(BigInteger.valueOf(8L));
        NuggetAdminCommandResult<?> bigIntegerEnvelope = commandResult(
                completedHeader(NuggetAdminOperationKind.INTROSPECTION),
                bigIntegerValue,
                "");
        assertEquals(Long.valueOf(8L), ((NuggetIntrospection.Result<?>) bigIntegerEnvelope.payload()).value());

        NuggetIntrospection.Result<?> decimalValue = introspectionScalarResult(new BigDecimal("7"));
        NuggetAdminCommandResult<?> decimalEnvelope = commandResult(
                completedHeader(NuggetAdminOperationKind.INTROSPECTION),
                decimalValue,
                "");
        assertEquals(Long.valueOf(7L), ((NuggetIntrospection.Result<?>) decimalEnvelope.payload()).value());

        assertThrows(IllegalArgumentException.class, () -> commandResult(
                completedHeader(NuggetAdminOperationKind.INTROSPECTION),
                introspectionScalarResult(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
                ""));
        assertThrows(IllegalArgumentException.class, () -> commandResult(
                completedHeader(NuggetAdminOperationKind.INTROSPECTION),
                introspectionScalarResult(BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE)),
                ""));
        assertThrows(IllegalArgumentException.class, () -> commandResult(
                completedHeader(NuggetAdminOperationKind.INTROSPECTION),
                introspectionScalarResult(new BigDecimal("7.5")),
                ""));
        assertThrows(IllegalArgumentException.class, () -> commandResult(
                completedHeader(NuggetAdminOperationKind.INTROSPECTION),
                introspectionScalarResult(new BigDecimal("9223372036854775808")),
                ""));
        assertThrows(IllegalArgumentException.class, () -> commandResult(
                completedHeader(NuggetAdminOperationKind.INTROSPECTION),
                introspectionScalarResult(Double.NaN),
                ""));
        assertThrows(IllegalArgumentException.class, () -> commandResult(
                completedHeader(NuggetAdminOperationKind.INTROSPECTION),
                introspectionScalarResult(Double.POSITIVE_INFINITY),
                ""));
        assertThrows(IllegalArgumentException.class, () -> commandResult(
                completedHeader(NuggetAdminOperationKind.INTROSPECTION),
                introspectionScalarResult(Float.NEGATIVE_INFINITY),
                ""));
        assertThrows(IllegalArgumentException.class, () -> commandResult(
                completedHeader(NuggetAdminOperationKind.INTROSPECTION),
                introspectionScalarResult(new HostileNumber()),
                ""));
    }

    @org.junit.jupiter.api.Test
    void lifecycleResultNormalizesRetainedApplicationPlatformDtos() {
        NuggetApplicationLifecycle.Result unsafe = new NuggetApplicationLifecycle.Result(
                "lifecycle-operation",
                "correlation-default",
                NuggetApplicationLifecycle.Action.START,
                true,
                true,
                true,
                "nugget-a",
                "runtime-a",
                ApplicationCoordinate.parse("org.example:demo:1.0.0"),
                new ApplicationInstanceId("instance-a"),
                null,
                null,
                null,
                NuggetApplicationLifecycle.Outcome.COMPLETED,
                "secret-token-completed",
                false,
                false,
                true,
                false,
                false,
                "secret-verifier",
                "authorized",
                new ApplicationDeploymentPlan(
                        ApplicationCoordinate.parse("org.example:demo:1.0.0"),
                        null,
                        List.of("verify-bundle", "token-secret-step")),
                new ApplicationDeploymentResult(
                        true,
                        null,
                        "secret-token-message",
                        List.of("safe-detail", "token-secret-detail")),
                sampleActivation(),
                List.of("safe-warning", "token-secret-warning"),
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                Duration.ZERO,
                ordered("safe", "value", "pin", "123456"));

        NuggetAdminCommandResult<?> envelope = commandResult(
                completedHeader(NuggetAdminOperationKind.APPLICATION_LIFECYCLE),
                unsafe,
                "");
        NuggetApplicationLifecycle.Result normalized = (NuggetApplicationLifecycle.Result) envelope.payload();

        assertNotSame(unsafe, normalized);
        assertEquals("", normalized.outcomeText());
        assertEquals("", normalized.verificationSummary());
        assertEquals(List.of("verify-bundle"), normalized.plan().steps());
        assertEquals("", normalized.deploymentResult().message());
        assertEquals(List.of("safe-detail"), normalized.deploymentResult().details());
        assertEquals("", normalized.activation().exportedServices().get(0).implementationClass());
        assertEquals(List.of("safe-warning", "details-omitted"), normalized.warnings());
        assertEquals("value", normalized.metadata().get("safe"));
        assertEquals("true", normalized.metadata().get("detailsOmitted"));
        assertEquals("value", normalized.activation().metadata().get("safe"));
        assertEquals("true", normalized.activation().metadata().get("detailsOmitted"));
    }

    @org.junit.jupiter.api.Test
    void stringListNormalizerPreservesHardMaximumWhenAddingOmissionMarker() {
        final int maximum = 32;
        NuggetApplicationLifecycle.Result unsafe = new NuggetApplicationLifecycle.Result(
                "lifecycle-operation",
                "correlation-default",
                NuggetApplicationLifecycle.Action.START,
                true,
                true,
                true,
                "nugget-a",
                "runtime-a",
                ApplicationCoordinate.parse("org.example:demo:1.0.0"),
                new ApplicationInstanceId("instance-a"),
                null,
                null,
                null,
                NuggetApplicationLifecycle.Outcome.COMPLETED,
                "COMPLETED",
                false,
                false,
                true,
                false,
                false,
                "verified",
                "authorized",
                new ApplicationDeploymentPlan(
                        ApplicationCoordinate.parse("org.example:demo:1.0.0"),
                        null,
                        repeatedSafeStrings(maximum + 1, "step-")),
                new ApplicationDeploymentResult(
                        true,
                        null,
                        "completed",
                        repeatedSafeStrings(maximum + 1, "detail-")),
                null,
                List.of(),
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                Duration.ZERO,
                Map.of());

        NuggetAdminCommandResult<?> envelope = commandResult(
                completedHeader(NuggetAdminOperationKind.APPLICATION_LIFECYCLE),
                unsafe,
                "");
        NuggetApplicationLifecycle.Result normalized = (NuggetApplicationLifecycle.Result) envelope.payload();

        assertEquals(maximum, normalized.plan().steps().size());
        assertEquals("details-omitted", normalized.plan().steps().get(maximum - 1));
        assertEquals(maximum, normalized.deploymentResult().details().size());
        assertEquals("details-omitted", normalized.deploymentResult().details().get(maximum - 1));
    }

    @org.junit.jupiter.api.Test
    void stringListNormalizerCapsExaminedInputsForAllFilteredCollections() {
        final int maximum = 32;
        List<String> hostileSteps = allFilteredWithSafeTail(maximum * 100, "safe-tail");
        List<String> hostileDetails = allFilteredWithSafeTail(maximum * 100, "safe-tail");
        NuggetApplicationLifecycle.Result unsafe = new NuggetApplicationLifecycle.Result(
                "lifecycle-operation",
                "correlation-default",
                NuggetApplicationLifecycle.Action.START,
                true,
                true,
                true,
                "nugget-a",
                "runtime-a",
                ApplicationCoordinate.parse("org.example:demo:1.0.0"),
                new ApplicationInstanceId("instance-a"),
                null,
                null,
                null,
                NuggetApplicationLifecycle.Outcome.COMPLETED,
                "COMPLETED",
                false,
                false,
                true,
                false,
                false,
                "verified",
                "authorized",
                new ApplicationDeploymentPlan(
                        ApplicationCoordinate.parse("org.example:demo:1.0.0"),
                        null,
                        hostileSteps),
                new ApplicationDeploymentResult(
                        true,
                        null,
                        "completed",
                        hostileDetails),
                null,
                List.of(),
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                Duration.ZERO,
                Map.of());

        NuggetAdminCommandResult<?> envelope = commandResult(
                completedHeader(NuggetAdminOperationKind.APPLICATION_LIFECYCLE),
                unsafe,
                "");
        NuggetApplicationLifecycle.Result normalized = (NuggetApplicationLifecycle.Result) envelope.payload();

        assertEquals(List.of("details-omitted"), normalized.plan().steps());
        assertEquals(List.of("details-omitted"), normalized.deploymentResult().details());
    }

    @org.junit.jupiter.api.Test
    void admittedRequestPayloadsRedactSensitiveToStringFields() {
        NuggetAuthenticationRequest authenticationRequest = NuggetAuthenticationRequest.of(
                "correlation-default",
                org.j7m.middleware.operations.api.NuggetAuthenticationSource.LOCAL,
                "socket-secret-sentinel",
                true,
                Instant.EPOCH);
        NuggetApplicationLifecycle.Request lifecycleRequest = new NuggetApplicationLifecycle.Request(
                "admission-secret-sentinel",
                SecurityContext.system("runtime-secret-sentinel"),
                "correlation-default",
                NuggetApplicationLifecycle.Action.START,
                ApplicationCoordinate.parse("org.example:demo:1.0.0"),
                null,
                true,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(30L),
                Map.of());
        NuggetControl.Request controlRequest = new NuggetControl.Request(
                "admission-secret-sentinel",
                SecurityContext.system("runtime-secret-sentinel"),
                "control-operation",
                "correlation-default",
                NuggetControl.Action.DRAIN_NUGGET,
                NuggetControl.Mode.GRACEFUL,
                "nugget-a",
                "runtime-a",
                1L,
                "",
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(30L),
                Map.of());

        assertFalse(authenticationRequest.toString().contains("socket-secret-sentinel"));
        assertFalse(lifecycleRequest.toString().contains("admission-secret-sentinel"));
        assertFalse(lifecycleRequest.toString().contains("SecurityContext["));
        assertFalse(controlRequest.toString().contains("admission-secret-sentinel"));
        assertFalse(controlRequest.toString().contains("SecurityContext["));
    }

    @org.junit.jupiter.api.Test
    void commandResultsValidateOutcomePayloadAndMutationIdentity() {
        NuggetAdminCommandResult<NuggetAdminCapabilities> completedRead = new NuggetAdminCommandResult<>(
                completedHeader(NuggetAdminOperationKind.CAPABILITIES),
                sampleCapabilities(),
                "");
        assertEquals(NuggetAdminOutcome.COMPLETED, completedRead.header().outcome());

        NuggetAdminCommandResult<?> unavailable = new NuggetAdminCommandResult<>(
                unavailableHeader(context(NuggetAdminOperationKind.NUGGET_CONTROL)),
                null,
                "");
        assertEquals("", unavailable.payloadType());

        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandResult<>(
                inconsistentHeader(NuggetAdminOperationKind.CAPABILITIES, NuggetAdminOutcome.COMPLETED),
                sampleCapabilities(),
                ""));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandResult<>(
                mutationHeader(NuggetAdminOperationKind.INTROSPECTION, NuggetAdminOutcome.IN_PROGRESS, "read-op"),
                sampleIntrospectionResult(),
                ""));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCommandResult<>(
                mutationHeader(NuggetAdminOperationKind.NUGGET_CONTROL, NuggetAdminOutcome.ACCEPTED, ""),
                sampleControlResult(NuggetControl.Outcome.ACCEPTED),
                ""));

        NuggetAdminCommandResult<?> denied = commandResult(
                outcomeHeader(NuggetAdminOperationKind.NUGGET_CONTROL, NuggetAdminOutcome.DENIED, "control-denied"),
                sampleControlResult(NuggetControl.Outcome.DENIED, "control-denied"),
                "");
        NuggetAdminCommandResult<?> accepted = commandResult(
                mutationHeader(NuggetAdminOperationKind.NUGGET_CONTROL, NuggetAdminOutcome.ACCEPTED, "control-accepted"),
                sampleControlResult(NuggetControl.Outcome.ACCEPTED, "control-accepted"),
                "");
        NuggetAdminCommandResult<?> inProgress = commandResult(
                mutationHeader(NuggetAdminOperationKind.NUGGET_CONTROL, NuggetAdminOutcome.IN_PROGRESS, "control-progress"),
                sampleControlResult(NuggetControl.Outcome.IN_PROGRESS, "control-progress"),
                "");
        NuggetAdminCommandResult<?> unknown = commandResult(
                outcomeHeader(NuggetAdminOperationKind.NUGGET_CONTROL, NuggetAdminOutcome.OUTCOME_UNKNOWN, "control-unknown"),
                sampleControlResult(NuggetControl.Outcome.OUTCOME_UNKNOWN, "control-unknown"),
                "");
        NuggetAdminCommandResult<?> confirmation = commandResult(
                outcomeHeader(NuggetAdminOperationKind.NUGGET_CONTROL, NuggetAdminOutcome.DENIED, "control-confirm"),
                sampleControlResult(NuggetControl.Outcome.CONFIRMATION_REQUIRED, "control-confirm"),
                "");
        NuggetAdminCommandResult<?> failed = commandResult(
                outcomeHeader(NuggetAdminOperationKind.APPLICATION_LIFECYCLE, NuggetAdminOutcome.DENIED, "lifecycle-failed"),
                sampleLifecycleResult(NuggetApplicationLifecycle.Outcome.FAILED, "lifecycle-failed"),
                "");

        assertEquals("nugget-control-result", denied.payloadType());
        assertEquals("nugget-control-result", accepted.payloadType());
        assertEquals("nugget-control-result", inProgress.payloadType());
        assertEquals("nugget-control-result", unknown.payloadType());
        assertEquals("nugget-control-result", confirmation.payloadType());
        assertEquals("nugget-application-lifecycle-result", failed.payloadType());
    }

    @org.junit.jupiter.api.Test
    void resultHeaderRejectsContradictoryOutcomeFlagsAndOverlargeDurations() {
        NuggetAdminRequestContext context = context(NuggetAdminOperationKind.NUGGET_CONTROL);
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminResultHeader(
                NuggetAdminContract.PROTOCOL_VERSION,
                NuggetAdminContract.JSON_FORMAT_NAME,
                NuggetAdminContract.JSON_FORMAT_VERSION,
                context.requestId(),
                context.correlationId(),
                "",
                NuggetAdminOperationKind.NUGGET_CONTROL,
                NuggetAdminOutcome.COMPLETED,
                false,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                0,
                0L,
                false,
                List.of(),
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                Duration.ZERO,
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminResultHeader(
                NuggetAdminContract.PROTOCOL_VERSION,
                NuggetAdminContract.JSON_FORMAT_NAME,
                NuggetAdminContract.JSON_FORMAT_VERSION,
                context.requestId(),
                context.correlationId(),
                "",
                NuggetAdminOperationKind.NUGGET_CONTROL,
                NuggetAdminOutcome.COMPLETED,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                1,
                0L,
                false,
                List.of(),
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                Duration.ZERO,
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminResultHeader(
                NuggetAdminContract.PROTOCOL_VERSION,
                NuggetAdminContract.JSON_FORMAT_NAME,
                NuggetAdminContract.JSON_FORMAT_VERSION,
                context.requestId(),
                context.correlationId(),
                "",
                NuggetAdminOperationKind.NUGGET_CONTROL,
                NuggetAdminOutcome.UNAVAILABLE,
                false,
                false,
                true,
                false,
                true,
                false,
                false,
                false,
                0,
                0L,
                false,
                List.of(),
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                Duration.ZERO,
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminResultHeader(
                NuggetAdminContract.PROTOCOL_VERSION,
                NuggetAdminContract.JSON_FORMAT_NAME,
                NuggetAdminContract.JSON_FORMAT_VERSION,
                context.requestId(),
                context.correlationId(),
                "",
                NuggetAdminOperationKind.NUGGET_CONTROL,
                NuggetAdminOutcome.COMPLETED,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                NuggetAdminContract.MAX_PAGE_LIMIT + 1,
                0L,
                false,
                List.of(),
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                Duration.ZERO,
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminResultHeader(
                NuggetAdminContract.PROTOCOL_VERSION,
                NuggetAdminContract.JSON_FORMAT_NAME,
                NuggetAdminContract.JSON_FORMAT_VERSION,
                context.requestId(),
                context.correlationId(),
                "",
                NuggetAdminOperationKind.NUGGET_CONTROL,
                NuggetAdminOutcome.COMPLETED,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                0,
                0L,
                false,
                List.of(),
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                Duration.ofSeconds((Long.MAX_VALUE / 1000L) + 2L),
                Map.of()));
    }

    @org.junit.jupiter.api.Test
    void queryPageRejectsInvalidCountAndOffsetSemantics() {
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminQuery.Page(
                0L,
                0,
                0,
                0L,
                true,
                false,
                false,
                Instant.EPOCH,
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminQuery.Page(
                0L,
                1,
                2,
                2L,
                true,
                false,
                false,
                Instant.EPOCH,
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminQuery.Page(
                0L,
                2,
                1,
                0L,
                true,
                false,
                false,
                Instant.EPOCH,
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminQuery.Page(
                Long.MAX_VALUE,
                1,
                0,
                0L,
                true,
                false,
                false,
                Instant.EPOCH,
                Map.of()));
    }

    @org.junit.jupiter.api.Test
    void capabilitiesAreBoundedAndHostileCollectionsAreSafe() {
        NuggetAdminCapabilities safe = new NuggetAdminCapabilities(
                true,
                NuggetAdminContract.PROTOCOL_VERSION,
                NuggetAdminContract.JSON_FORMAT_NAME,
                NuggetAdminContract.JSON_FORMAT_VERSION,
                List.of(org.j7m.middleware.operations.api.NuggetCapability.READ_INFO),
                List.of(NuggetAdminOperationKind.CAPABILITIES),
                NuggetAdminContract.MAX_PAGE_LIMIT,
                NuggetAdminContract.MAX_METADATA_ENTRIES,
                Instant.EPOCH,
                Map.of());
        assertEquals(1, safe.capabilities().size());
        assertEquals(1, safe.operationKinds().size());

        List<org.j7m.middleware.operations.api.NuggetCapability> tooManyCapabilities = repeatedCapabilities(
                NuggetAdminCapabilities.MAX_CAPABILITIES + 1);
        assertThrows(IllegalArgumentException.class, () -> new NuggetAdminCapabilities(
                true,
                NuggetAdminContract.PROTOCOL_VERSION,
                NuggetAdminContract.JSON_FORMAT_NAME,
                NuggetAdminContract.JSON_FORMAT_VERSION,
                tooManyCapabilities,
                List.of(NuggetAdminOperationKind.CAPABILITIES),
                NuggetAdminContract.MAX_PAGE_LIMIT,
                NuggetAdminContract.MAX_METADATA_ENTRIES,
                Instant.EPOCH,
                Map.of()));

        NuggetAdminCapabilities hostile = new NuggetAdminCapabilities(
                true,
                NuggetAdminContract.PROTOCOL_VERSION,
                NuggetAdminContract.JSON_FORMAT_NAME,
                NuggetAdminContract.JSON_FORMAT_VERSION,
                new HostileList<>(),
                new HostileList<>(),
                NuggetAdminContract.MAX_PAGE_LIMIT,
                NuggetAdminContract.MAX_METADATA_ENTRIES,
                Instant.EPOCH,
                Map.of());
        assertEquals(List.of(), hostile.capabilities());
        assertEquals(List.of(), hostile.operationKinds());

        NuggetAdminCapabilities unavailable = new NuggetAdminCapabilities(
                false,
                0,
                "j7m-nugget-admin-json-" + "x".repeat(1024),
                0,
                List.of(),
                List.of(),
                0,
                0,
                Instant.EPOCH,
                Map.of());
        assertEquals("", unavailable.jsonFormatName());
    }

    @org.junit.jupiter.api.Test
    void normativeDocumentationSchemaAndResourceMapArePresent() throws IOException {
        String contract = read("docs/internal/01000 - Normative Specifications/nugget-admin-api-contract.md");
        String json = read("docs/internal/01000 - Normative Specifications/nugget-admin-json.md");
        String schema = read("docs/schema/nugget-admin-contract.schema.json");
        String resourceMap = read("docs/internal/03000 - Architecture Decisions/nugget-web-api-resource-map.md");

        assertTrue(contract.contains("j7m-nugget-admin-json"));
        assertTrue(contract.contains("unsupported request protocol version"));
        assertTrue(json.contains("Unknown fields"));
        assertTrue(schema.contains("\"formatName\""));
        assertTrue(schema.contains("\"operationKind\""));
        assertTrue(resourceMap.contains("/nuggets/{id}/objects/{objectId}"));
        assertTrue(resourceMap.contains("/nuggets/{id}/kill"));
        assertTrue(resourceMap.contains("No listener, route, cookie, browser session, OAuth"));
    }

    @org.junit.jupiter.api.Test
    void schemaParsesIsClosedAndSynchronizesWithJavaBinding() throws IOException {
        String schema = read("docs/schema/nugget-admin-contract.schema.json");
        Object parsed = JsonParser.parse(schema);
        assertTrue(parsed instanceof Map<?, ?>);
        assertTrue(schema.contains("\"additionalProperties\": false"));
        assertFalse(schema.contains("\"additionalProperties\": true"));

        assertEquals(enumNames(NuggetAdminOperationKind.values()), schemaDefinitionEnum(parsed, "operationKind"));
        assertEquals(enumNames(NuggetAdminOutcome.values()), schemaDefinitionEnum(parsed, "adminOutcome"));
        assertEquals(expectedPayloadTypes(), schemaDefinitionEnum(parsed, "payloadType"));
        assertEquals(Set.of(
                "formatName",
                "formatVersion",
                "protocolVersion",
                "requestId",
                "correlationId",
                "operationKind",
                "payloadType",
                "payload"), schemaRequiredFields(parsed, ""));
        assertTrue(schemaRequiredFields(parsed, "resultHeader").containsAll(Set.of(
                "formatName",
                "formatVersion",
                "protocolVersion",
                "requestId",
                "correlationId",
                "operationKind",
                "outcome",
                "returnedCount",
                "totalAvailable",
                "elapsedMillis",
                "metadata")));
        assertEquals((long) NuggetAdminContract.MAX_TOKEN_UTF8_BYTES, schemaMaximum(parsed, "token", null));
        assertEquals((long) NuggetAdminContract.MAX_METADATA_ENTRIES, schemaMaximum(parsed, "metadata", "maxProperties"));
        assertEquals((long) NuggetAdminContract.MAX_PAGE_LIMIT, schemaPropertyMaximum(parsed, "adminQuery", "limit"));
        assertEquals(NuggetAdminContract.MAX_OFFSET, schemaPropertyMaximum(parsed, "adminQuery", "offset"));
        assertEquals(NuggetAdminContract.MAX_COUNT, schemaPropertyMaximum(parsed, "resultHeader", "totalAvailable"));
        assertEquals(NuggetAdminContract.MAX_DURATION_MILLIS, schemaPropertyMaximum(parsed, "resultHeader", "elapsedMillis"));
        assertEquals(
                NuggetAdminContract.MAX_DURATION_MILLIS,
                schemaPropertyMaximum(parsed, "authenticationResult", "retryAfterMillis"));
        assertEquals(
                NuggetAdminContract.MAX_CLIENT_BUDGET.toMillis(),
                schemaPropertyMaximum(parsed, "requestContext", "clientBudgetMillis"));

        Set<NuggetAdminOperationKind> readOnlyKinds = new LinkedHashSet<>();
        Set<NuggetAdminOperationKind> operationIdRequiredKinds = new LinkedHashSet<>();
        for (NuggetAdminOperationKind kind : NuggetAdminOperationKind.values()) {
            if (kind.readOnly()) {
                readOnlyKinds.add(kind);
            }
            if (kind.acceptedMutationOperationIdentityRequired()) {
                operationIdRequiredKinds.add(kind);
            }
            assertTrue(schema.contains("\"" + kind.name() + "\""), kind.name());
            for (PayloadBinding binding : kind.requestPayloads()) {
                assertTrue(schema.contains("\"" + binding.payloadType() + "\""), binding.payloadType());
                assertTrue(schema.contains(binding.schemaVariantId().substring("defs.".length())), binding.schemaVariantId());
            }
            for (PayloadBinding binding : kind.resultPayloads()) {
                assertTrue(schema.contains("\"" + binding.payloadType() + "\""), binding.payloadType());
                assertTrue(schema.contains(binding.schemaVariantId().substring("defs.".length())), binding.schemaVariantId());
            }
        }
        assertEquals(Set.of(
                NuggetAdminOperationKind.CAPABILITIES,
                NuggetAdminOperationKind.DISCOVERY,
                NuggetAdminOperationKind.INTROSPECTION), readOnlyKinds);
        assertEquals(Set.of(
                NuggetAdminOperationKind.APPLICATION_LIFECYCLE,
                NuggetAdminOperationKind.NUGGET_CONTROL), operationIdRequiredKinds);

        Set<String> expectedFields = new LinkedHashSet<>();
        for (NuggetAdminField field : NuggetAdminField.values()) {
            expectedFields.add(field.name());
        }
        assertEquals(expectedFields, schemaEnum(parsed, "queryFilter", "field"));
        assertEquals(expectedFields, schemaEnum(parsed, "querySort", "field"));
    }

    @org.junit.jupiter.api.Test
    void schemaFixturesValidateAndRejectInvalidCommandShapes() throws IOException {
        JsonSchemaValidator validator = schemaValidator();
        SchemaBackedSemanticValidator semanticValidator = schemaBackedSemanticValidator();
        semanticValidator.validate(capabilitiesRequestFixture());
        semanticValidator.validate(capabilitiesFixture());
        semanticValidator.validate(discoveryQueryRequestFixture());
        semanticValidator.validate(discoveryFixture());
        semanticValidator.validate(discoveryResolutionFixture());
        semanticValidator.validate(authenticationRequestFixture());
        semanticValidator.validate(authenticationFixture());
        semanticValidator.validate(introspectionQueryRequestFixture());
        semanticValidator.validate(introspectionFixture());
        semanticValidator.validate(applicationLifecycleRequestFixture());
        semanticValidator.validate(applicationLifecycleFixture());
        semanticValidator.validate(nuggetControlRequestFixture());
        semanticValidator.validate(nuggetControlFixture(NuggetAdminOutcome.ACCEPTED, "control-operation"));
        semanticValidator.validate(unavailableFixture(NuggetAdminOperationKind.NUGGET_CONTROL));
        semanticValidator.validate(nuggetControlFixture(NuggetAdminOutcome.DENIED, "control-denied"));
        semanticValidator.validate(nuggetControlFixture(NuggetAdminOutcome.PARTIAL, "control-partial"));
        semanticValidator.validate(nuggetControlFixture(NuggetAdminOutcome.OUTCOME_UNKNOWN, "control-unknown"));

        Map<String, Object> unknownRoot = copy(capabilitiesFixture());
        unknownRoot.put("extra", "value");
        validator.reject(unknownRoot);

        Map<String, Object> unknownNested = copy(capabilitiesFixture());
        payload(unknownNested).put("extra", "value");
        validator.reject(unknownNested);

        Map<String, Object> missingRequired = copy(capabilitiesFixture());
        missingRequired.remove("payloadType");
        validator.reject(missingRequired);

        Map<String, Object> invalidEnum = copy(capabilitiesFixture());
        invalidEnum.put("operationKind", "METHOD");
        validator.reject(invalidEnum);

        Map<String, Object> unsupportedVersion = copy(capabilitiesFixture());
        unsupportedVersion.put("protocolVersion", 2);
        validator.reject(unsupportedVersion);

        Map<String, Object> tooLong = copy(capabilitiesFixture());
        tooLong.put("requestId", "x".repeat(129));
        validator.reject(tooLong);

        Map<String, Object> badNumber = copy(nuggetControlFixture(NuggetAdminOutcome.ACCEPTED, "control-operation"));
        payload(badNumber).put("targetGeneration", -1);
        validator.reject(badNumber);

        Map<String, Object> excessWarnings = copy(nuggetControlFixture(NuggetAdminOutcome.PARTIAL, "control-partial"));
        header(excessWarnings).put("warnings", repeatedStrings(NuggetAdminContract.MAX_WARNING_ENTRIES + 1));
        validator.reject(excessWarnings);

        Map<String, Object> excessMetadata = copy(capabilitiesFixture());
        payload(excessMetadata).put("metadata", metadata(NuggetAdminContract.MAX_METADATA_ENTRIES + 1));
        validator.reject(excessMetadata);

        Map<String, Object> secretField = copy(authenticationFixture());
        payload(secretField).put("pin", "123456");
        validator.reject(secretField);

        Map<String, Object> wrongPayload = copy(capabilitiesFixture());
        wrongPayload.put("operationKind", NuggetAdminOperationKind.NUGGET_CONTROL.name());
        validator.reject(wrongPayload);

        Map<String, Object> readOnlyConfirmation = copy(introspectionQueryRequestFixture());
        payload(readOnlyConfirmation).put("confirmationProvided", true);
        validator.reject(readOnlyConfirmation);

        Map<String, Object> requestWithHeader = copy(discoveryQueryRequestFixture());
        requestWithHeader.put("header", headerFixture(
                NuggetAdminOperationKind.DISCOVERY,
                NuggetAdminOutcome.COMPLETED,
                ""));
        validator.reject(requestWithHeader);

        Map<String, Object> capabilitiesRequestWithHeader = copy(capabilitiesRequestFixture());
        capabilitiesRequestWithHeader.put("header", headerFixture(
                NuggetAdminOperationKind.CAPABILITIES,
                NuggetAdminOutcome.COMPLETED,
                ""));
        validator.reject(capabilitiesRequestWithHeader);

        Map<String, Object> capabilitiesRequestWithOutcome = copy(capabilitiesRequestFixture());
        capabilitiesRequestWithOutcome.put("outcome", NuggetAdminOutcome.COMPLETED.name());
        validator.reject(capabilitiesRequestWithOutcome);

        Map<String, Object> overlongErrorCode = discoveryResolutionFixture();
        payload(overlongErrorCode).put("errorCode", "E".repeat(65));
        validator.reject(overlongErrorCode);

        Map<String, Object> overlongInspectionValue = introspectionFixture();
        inspectionItem(overlongInspectionValue).put("value", "x".repeat(513));
        validator.reject(overlongInspectionValue);

        Map<String, Object> methodMap = envelope(
                NuggetAdminOperationKind.NUGGET_CONTROL,
                "method-command",
                map("method", "kill-nugget"),
                false,
                NuggetAdminOutcome.COMPLETED,
                "");
        validator.reject(methodMap);
    }

    @org.junit.jupiter.api.Test
    void schemaFixturesCoverEveryRetainedIntrospectionValueVariant() throws IOException {
        SchemaBackedSemanticValidator validator = schemaBackedSemanticValidator();
        for (Map<String, Object> value : introspectionValueFixtures()) {
            validator.validate(introspectionFixture(value));
        }
    }

    @org.junit.jupiter.api.Test
    void schemaFixturesRequireFullDiscoveryRecordAndNuggetIdentityState() throws IOException {
        SchemaBackedSemanticValidator validator = schemaBackedSemanticValidator();
        validator.validate(discoveryFixture());
        validator.validate(introspectionFixture(overviewFixture(
                runtimeSummaryFixture(),
                map(
                        "state", "HEALTHY",
                        "components", List.of(),
                        "collectedAt", "2026-01-01T00:00:00Z",
                        "partial", false),
                map(
                        "state", "READY",
                        "components", List.of(),
                        "collectedAt", "2026-01-01T00:00:00Z",
                        "partial", false))));

        Map<String, Object> missingDiscoveryField = copy(discoveryFixture());
        List<Map<String, Object>> records = discoveryRecords(missingDiscoveryField);
        records.get(0).remove("capabilities");
        validator.reject(missingDiscoveryField);

        Map<String, Object> missingIdentityField = copy(introspectionFixture(overviewFixture(
                runtimeSummaryFixture(),
                map(
                        "state", "HEALTHY",
                        "components", List.of(),
                        "collectedAt", "2026-01-01T00:00:00Z",
                        "partial", false),
                map(
                        "state", "READY",
                        "components", List.of(),
                        "collectedAt", "2026-01-01T00:00:00Z",
                        "partial", false))));
        nuggetIdentity(missingIdentityField).remove("runtimeIdentity");
        validator.reject(missingIdentityField);
    }

    @org.junit.jupiter.api.Test
    void schemaBackedSemanticValidatorRejectsInvalidPublicContractSemantics() throws IOException {
        SchemaBackedSemanticValidator validator = schemaBackedSemanticValidator();

        Map<String, Object> rootHeaderOutcomeMismatch = copy(capabilitiesFixture());
        header(rootHeaderOutcomeMismatch).put("outcome", NuggetAdminOutcome.DENIED.name());
        validator.reject(rootHeaderOutcomeMismatch);

        Map<String, Object> operationMismatch = copy(capabilitiesFixture());
        header(operationMismatch).put("operationKind", NuggetAdminOperationKind.DISCOVERY.name());
        validator.reject(operationMismatch);

        Map<String, Object> requestMismatch = copy(capabilitiesFixture());
        header(requestMismatch).put("requestId", "request-other");
        validator.reject(requestMismatch);

        Map<String, Object> correlationMismatch = copy(capabilitiesFixture());
        header(correlationMismatch).put("correlationId", "correlation-other");
        validator.reject(correlationMismatch);

        Map<String, Object> protocolMismatch = copy(capabilitiesFixture());
        header(protocolMismatch).put("protocolVersion", 2);
        validator.reject(protocolMismatch);

        Map<String, Object> formatMismatch = copy(capabilitiesFixture());
        header(formatMismatch).put("formatName", "other-format");
        validator.reject(formatMismatch);

        Map<String, Object> targetMismatch = copy(nuggetControlFixture(
                NuggetAdminOutcome.ACCEPTED,
                "control-operation"));
        payload(targetMismatch).put("targetGeneration", 2);
        validator.reject(targetMismatch);

        Map<String, Object> completedAndInProgress = copy(capabilitiesFixture());
        header(completedAndInProgress).put("inProgress", true);
        validator.reject(completedAndInProgress);

        Map<String, Object> completedTimedOut = copy(capabilitiesFixture());
        header(completedTimedOut).put("timedOut", true);
        validator.reject(completedTimedOut);

        Map<String, Object> acceptedDenied = copy(nuggetControlFixture(NuggetAdminOutcome.DENIED, "control-denied"));
        header(acceptedDenied).put("accepted", true);
        validator.reject(acceptedDenied);

        Map<String, Object> outcomeUnknownCompleted = copy(nuggetControlFixture(
                NuggetAdminOutcome.OUTCOME_UNKNOWN,
                "control-unknown"));
        header(outcomeUnknownCompleted).put("completed", true);
        validator.reject(outcomeUnknownCompleted);

        Map<String, Object> unavailableAccepted = copy(unavailableFixture(NuggetAdminOperationKind.NUGGET_CONTROL));
        header(unavailableAccepted).put("accepted", true);
        validator.reject(unavailableAccepted);

        Map<String, Object> acceptedLifecycleWithoutOperationId = copy(applicationLifecycleFixture());
        setCommonOutcome(acceptedLifecycleWithoutOperationId, NuggetAdminOutcome.ACCEPTED, "");
        payload(acceptedLifecycleWithoutOperationId).put("operationId", "");
        payload(acceptedLifecycleWithoutOperationId).put("outcome", "ACCEPTED");
        payload(acceptedLifecycleWithoutOperationId).put("accepted", true);
        payload(acceptedLifecycleWithoutOperationId).put("completed", false);
        validator.reject(acceptedLifecycleWithoutOperationId);

        Map<String, Object> inProgressControlWithoutOperationId = nuggetControlFixture(
                NuggetAdminOutcome.IN_PROGRESS,
                "");
        validator.reject(inProgressControlWithoutOperationId);

        Map<String, Object> outcomeUnknownMutationWithoutOperationId = nuggetControlFixture(
                NuggetAdminOutcome.OUTCOME_UNKNOWN,
                "");
        validator.reject(outcomeUnknownMutationWithoutOperationId);

        Map<String, Object> readOnlyOperationId = copy(capabilitiesFixture());
        header(readOnlyOperationId).put("operationId", "read-only-operation");
        validator.reject(readOnlyOperationId);

        Map<String, Object> readOnlyInProgress = copy(capabilitiesFixture());
        setCommonOutcome(readOnlyInProgress, NuggetAdminOutcome.IN_PROGRESS, "");
        validator.reject(readOnlyInProgress);

        Map<String, Object> unrelatedConfirmation = copy(introspectionQueryRequestFixture());
        payload(unrelatedConfirmation).put("confirmationProvided", true);
        validator.reject(unrelatedConfirmation);

        Map<String, Object> negativeOffset = copy(introspectionQueryRequestFixture());
        payload(negativeOffset).put("offset", -1);
        validator.reject(negativeOffset);

        Map<String, Object> tooLargeOffset = copy(introspectionQueryRequestFixture());
        payload(tooLargeOffset).put("offset", 1.0E20);
        validator.reject(tooLargeOffset);

        Map<String, Object> offsetLimitOverflow = copy(introspectionQueryRequestFixture());
        payload(offsetLimitOverflow).put("offset", Long.MAX_VALUE);
        payload(offsetLimitOverflow).put("limit", 1);
        validator.reject(offsetLimitOverflow);

        Map<String, Object> excessiveTotalAvailable = copy(capabilitiesFixture());
        header(excessiveTotalAvailable).put("totalAvailable", 1.0E20);
        validator.reject(excessiveTotalAvailable);

        Map<String, Object> excessiveReturned = copy(capabilitiesFixture());
        header(excessiveReturned).put("returnedCount", NuggetAdminContract.MAX_PAGE_LIMIT + 1);
        validator.reject(excessiveReturned);

        Map<String, Object> excessiveElapsed = copy(capabilitiesFixture());
        header(excessiveElapsed).put("elapsedMillis", 1.0E20);
        validator.reject(excessiveElapsed);

        Map<String, Object> excessiveRequestTimeout = copy(capabilitiesFixture());
        context(excessiveRequestTimeout).put("clientBudgetMillis", 300001);
        validator.reject(excessiveRequestTimeout);

        Map<String, Object> excessiveRetryAfter = copy(authenticationFixture());
        payload(excessiveRetryAfter).put("retryAfterMillis", 1.0E20);
        validator.reject(excessiveRetryAfter);

        Map<String, Object> excessiveMetadataAggregate = copy(capabilitiesFixture());
        payload(excessiveMetadataAggregate).put("metadata", aggregateMetadata());
        validator.reject(excessiveMetadataAggregate);

        rejectBadMetadataValue(validator, "control\nvalue");
        rejectBadMetadataValue(validator, "\u001B[31mred");
        rejectBadMetadataValue(validator, "safe\u202Ehidden");
        rejectBadMetadataValue(validator, "<script>alert(1)</script>");
        rejectBadMetadataValue(validator, "secret-value");
        rejectBadMetadataValue(validator, "/home/user/.j7m/config");
        rejectBadMetadataValue(validator, "java.lang.IllegalStateException: stack");
        rejectBadMetadataValue(validator, "\uD800");
        rejectBadMetadataKey(validator, "k".repeat(65));
        rejectBadMetadataKey(validator, "safe/path");
        rejectBadMetadataKey(validator, "C:/safe");
        rejectBadMetadataValue(validator, "v".repeat(257));
    }

    private static final String PROMETHEUS_SOURCE =
            "j7m-integrations/src/main/java/org/j7m/middleware/integrations/Observability.java";

    @org.junit.jupiter.api.Test
    void productionSourcesKeepNuggetHeadlessAndOnlyExposeAuthorizedPrometheusScrape() throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(ROOT)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> productionWebPolicyInput(ROOT.relativize(path).toString().replace('\\', '/')))
                    .toList();
        }
        assertTrue(files.stream().anyMatch(path -> ROOT.relativize(path).toString().replace('\\', '/').equals(PROMETHEUS_SOURCE)),
                "The approved production endpoint must be inspected, not skipped");
        for (Path file : files) {
            String relative = ROOT.relativize(file).toString().replace('\\', '/');
            assertEquals(List.of(), productionWebPolicyViolations(relative, readBounded(file, MAX_SOURCE_SCAN_BYTES)), relative);
        }
    }

    @org.junit.jupiter.api.Test
    void productionWebPolicyAllowsOnlyTheExactGetMetricsImplementation() throws IOException {
        assertEquals(List.of(), productionWebPolicyViolations(PROMETHEUS_SOURCE, metricsServerFixture()));
        assertEquals(List.of(), productionWebPolicyViolations(PROMETHEUS_SOURCE,
                readBounded(ROOT.resolve(PROMETHEUS_SOURCE), MAX_SOURCE_SCAN_BYTES)));
        for (String unauthorized : List.of(
                "j7m-runtime/src/main/java/org/j7m/middleware/runtime/NuggetHttpServer.java",
                "j7m-api/src/main/java/org/j7m/middleware/operations/api/NuggetHttpServer.java",
                "j7m-integrations/src/main/java/org/j7m/middleware/integrations/AnotherServer.java",
                "other-module/src/main/java/org/j7m/middleware/integrations/Observability.java")) {
            assertFalse(productionWebPolicyViolations(unauthorized, metricsServerFixture()).isEmpty(), unauthorized);
        }
    }

    @org.junit.jupiter.api.Test
    void productionWebPolicyRejectsAdditionalAdminRoutesMethodChangesAndNonliteralRoutes() {
        String approved = metricsServerFixture();
        assertFalse(productionWebPolicyViolations(PROMETHEUS_SOURCE,
                approved + " server.createContext(\"/admin\", handler);").isEmpty());
        assertFalse(productionWebPolicyViolations(PROMETHEUS_SOURCE,
                approved.replace("\"/metrics\"", "\"/admin\"")).isEmpty());
        assertFalse(productionWebPolicyViolations(PROMETHEUS_SOURCE,
                approved.replace("\"GET\"", "\"POST\"")).isEmpty());
        assertFalse(productionWebPolicyViolations(PROMETHEUS_SOURCE,
                approved.replace("createContext(\"/metrics\",", "createContext(route,")).isEmpty());
        assertFalse(productionWebPolicyViolations(PROMETHEUS_SOURCE,
                approved.replace("!exchange.getRequestURI().getPath().equals(\"/metrics\")", "false")).isEmpty());
        assertFalse(productionWebPolicyViolations(PROMETHEUS_SOURCE,
                approved + " server.createContext(\"/metrics\", anotherHandler);").isEmpty());
        assertFalse(productionWebPolicyViolations(PROMETHEUS_SOURCE,
                approved + " context.setHandler(adminHandler);").isEmpty());
    }

    @org.junit.jupiter.api.Test
    void productionWebPolicyRetainsFrameworkBansAndExcludesOnlyNonproductionFixtures() {
        for (String banned : List.of("spring-boot-starter-web", "jakarta.servlet", "@GetMapping", "@PostMapping")) {
            assertFalse(productionWebPolicyViolations(PROMETHEUS_SOURCE, metricsServerFixture() + banned).isEmpty(), banned);
            assertFalse(productionWebPolicyViolations("other/build.gradle", banned).isEmpty(), banned);
            assertFalse(productionWebPolicyViolations("gradle/libs.versions.toml", banned).isEmpty(), banned);
        }
        assertTrue(productionWebPolicyInput(PROMETHEUS_SOURCE));
        assertTrue(productionWebPolicyInput("j7m-tests/src/main/java/ProductionSupport.java"));
        assertTrue(productionWebPolicyInput("j7m-runtime/src/main/java/NuggetHttpServer.java"));
        assertFalse(productionWebPolicyInput("j7m-integrations/src/test/java/CollectorIT.java"));
        assertFalse(productionWebPolicyInput("j7m-tests/src/test/java/NuggetAdminApiContractTest.java"));
        assertFalse(productionWebPolicyInput("j7m-runtime/build/generated/Generated.java"));
    }

    @org.junit.jupiter.api.Test
    void productionWebPolicyExcludesOnlyRootEvidenceArchivesAndStillChecksNestedProduction() {
        for (String archived : List.of(
                "INBOX/qualification/source/" + PROMETHEUS_SOURCE,
                "INBOX/qualification/source/build.gradle",
                "INBOX/qualification/source/gradle/libs.versions.toml")) {
            assertFalse(productionWebPolicyInput(archived), archived);
        }
        for (String live : List.of(PROMETHEUS_SOURCE, "build.gradle", "gradle/libs.versions.toml")) {
            assertTrue(productionWebPolicyInput(live), live);
        }
        for (String nested : List.of(
                "j7m-runtime/src/main/java/example/INBOX/Unauthorized.java",
                "j7m-runtime/INBOX/build.gradle",
                "j7m-runtime/config/INBOX/dependencies.toml",
                "INBOX-old/src/main/java/Observability.java")) {
            assertTrue(productionWebPolicyInput(nested), nested);
            assertFalse(productionWebPolicyViolations(nested, metricsServerFixture()).isEmpty(), nested);
        }
    }

    private static boolean productionWebPolicyInput(String relative) {
        return !relative.startsWith("INBOX/") && !relative.contains("/build/") && !relative.contains("/src/test/")
                && (relative.endsWith(".java") || relative.endsWith("build.gradle") || relative.endsWith(".toml"));
    }

    private static List<String> productionWebPolicyViolations(String relative, String source) {
        List<String> violations = new ArrayList<>();
        for (String banned : List.of("spring-boot-starter-web", "jakarta.servlet", "@GetMapping", "@PostMapping")) {
            if (source.contains(banned)) violations.add("Forbidden web framework/controller: " + banned);
        }
        boolean jdkHttp = source.contains("com.sun.net.httpserver")
                || Pattern.compile("\\b(?:HttpServer|HttpsServer|HttpHandler)\\b|\\.createContext\\s*\\(").matcher(source).find();
        if (!relative.equals(PROMETHEUS_SOURCE)) {
            if (jdkHttp) violations.add("JDK HTTP server/routes outside the approved metrics source");
            return violations;
        }
        if (!jdkHttp || Pattern.compile("\\.createContext\\s*\\(").matcher(source).results().count() != 1
                || Pattern.compile("\\bHttpServer\\.create\\s*\\(").matcher(source).results().count() != 1
                || source.contains("HttpsServer") || Pattern.compile("\\.setHandler\\s*\\(").matcher(source).find()) {
            violations.add("Metrics source requires exactly one server and one immutable literal route");
        }
        String approvedHandler = "\\.createContext\\s*\\(\\s*\"/metrics\"\\s*,\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*->\\s*\\{\\s*"
                + "if\\s*\\(\\s*!\\1\\.getRequestMethod\\(\\)\\.equals\\(\"GET\"\\)\\s*\\|\\|\\s*"
                + "!\\1\\.getRequestURI\\(\\)\\.getPath\\(\\)\\.equals\\(\"/metrics\"\\)\\s*\\)\\s*\\{\\s*"
                + "\\1\\.sendResponseHeaders\\(\\s*405\\s*,\\s*-1\\s*\\)\\s*;\\s*\\1\\.close\\(\\)\\s*;\\s*return\\s*;\\s*\\}";
        if (!Pattern.compile(approvedHandler).matcher(source).find()
                || Pattern.compile("getRequestMethod\\s*\\(").matcher(source).results().count() != 1
                || Pattern.compile("getRequestURI\\s*\\(").matcher(source).results().count() != 1) {
            violations.add("The sole metrics handler must reject non-GET and nonexact /metrics paths before handling");
        }
        return List.copyOf(violations);
    }

    private static String metricsServerFixture() {
        return """
                import com.sun.net.httpserver.HttpServer;
                final class Observability {
                    void bind() {
                        var server = HttpServer.create(address, 16);
                        server.createContext("/metrics", exchange -> {
                            if (!exchange.getRequestMethod().equals("GET") || !exchange.getRequestURI().getPath().equals("/metrics")) {
                                exchange.sendResponseHeaders(405, -1); exchange.close(); return;
                            }
                            writeMetrics(exchange);
                        });
                    }
                }
                """;
    }

    private static NuggetAdminRequestContext context(final NuggetAdminOperationKind kind) {
        return new NuggetAdminRequestContext(
                NuggetAdminContract.PROTOCOL_VERSION,
                "request-default",
                "correlation-default",
                target(),
                kind,
                Instant.EPOCH,
                Duration.ofSeconds(1L),
                Instant.EPOCH.plusSeconds(1L),
                Map.of("client", "test"));
    }

    private static NuggetAdminTarget target() {
        return new NuggetAdminTarget("nugget-a", "runtime-a", 1L, "j7m-local:admin", "provider-a");
    }

    private static <T> NuggetAdminCommandResult<T> commandResult(
            final NuggetAdminResultHeader header,
            final T payload,
            final String payloadType) {
        if (payload instanceof NuggetControl.Result || payload instanceof NuggetApplicationLifecycle.Result) {
            return new NuggetAdminCommandResult<>(target(), header, payload, payloadType);
        }
        return new NuggetAdminCommandResult<>(header, payload, payloadType);
    }

    private static NuggetAdminResultHeader unavailableHeader(final NuggetAdminRequestContext context) {
        return NuggetAdminResultHeader.unavailable(context, "", new RemoteError(
                org.j7m.middleware.api.MiddlewareErrorCode.UNAVAILABLE,
                "unavailable",
                Map.of(),
                context.correlationId(),
                context.target().runtimeId(),
                false,
                false));
    }

    private static NuggetAdminResultHeader resultHeader(
            final NuggetAdminRequestContext context,
            final List<String> warnings) {
        return new NuggetAdminResultHeader(
                NuggetAdminContract.PROTOCOL_VERSION,
                NuggetAdminContract.JSON_FORMAT_NAME,
                NuggetAdminContract.JSON_FORMAT_VERSION,
                context.requestId(),
                context.correlationId(),
                "",
                context.operationKind(),
                NuggetAdminOutcome.PARTIAL,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                0,
                0L,
                false,
                warnings,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                Duration.ZERO,
                Map.of());
    }

    private static NuggetAdminResultHeader completedHeader(final NuggetAdminOperationKind kind) {
        return outcomeHeader(kind, NuggetAdminOutcome.COMPLETED, "");
    }

    private static NuggetAdminResultHeader mutationHeader(
            final NuggetAdminOperationKind kind,
            final NuggetAdminOutcome outcome,
            final String operationId) {
        return outcomeHeader(kind, outcome, operationId);
    }

    private static NuggetAdminResultHeader inconsistentHeader(
            final NuggetAdminOperationKind kind,
            final NuggetAdminOutcome outcome) {
        NuggetAdminRequestContext context = context(kind);
        return new NuggetAdminResultHeader(
                NuggetAdminContract.PROTOCOL_VERSION,
                NuggetAdminContract.JSON_FORMAT_NAME,
                NuggetAdminContract.JSON_FORMAT_VERSION,
                context.requestId(),
                context.correlationId(),
                "",
                kind,
                outcome,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                0,
                0L,
                false,
                List.of(),
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                Duration.ZERO,
                Map.of());
    }

    private static NuggetAdminResultHeader outcomeHeader(
            final NuggetAdminOperationKind kind,
            final NuggetAdminOutcome outcome,
            final String operationId) {
        NuggetAdminRequestContext context = context(kind);
        return new NuggetAdminResultHeader(
                NuggetAdminContract.PROTOCOL_VERSION,
                NuggetAdminContract.JSON_FORMAT_NAME,
                NuggetAdminContract.JSON_FORMAT_VERSION,
                context.requestId(),
                context.correlationId(),
                operationId,
                kind,
                outcome,
                outcome == NuggetAdminOutcome.ACCEPTED,
                outcome == NuggetAdminOutcome.IN_PROGRESS,
                outcome == NuggetAdminOutcome.COMPLETED,
                outcome == NuggetAdminOutcome.DENIED,
                outcome == NuggetAdminOutcome.UNAVAILABLE,
                outcome == NuggetAdminOutcome.TIMED_OUT,
                outcome == NuggetAdminOutcome.PARTIAL,
                outcome == NuggetAdminOutcome.OUTCOME_UNKNOWN,
                0,
                0L,
                false,
                List.of(),
                outcome == NuggetAdminOutcome.DENIED ? error(MiddlewareErrorCode.PERMISSION_DENIED, context) : null,
                Instant.EPOCH,
                Instant.EPOCH,
                Duration.ZERO,
                Map.of());
    }

    private static Object samplePayload(final Class<?> payloadClass) {
        if (payloadClass == NuggetAdminCapabilities.class) {
            return sampleCapabilities();
        }
        if (payloadClass == NuggetDiscovery.Query.class) {
            return NuggetDiscovery.Query.active();
        }
        if (payloadClass == NuggetDiscovery.Selector.class) {
            return new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.ALL, "", "correlation-default");
        }
        if (payloadClass == NuggetDiscovery.Result.class) {
            return new NuggetDiscovery.Result(List.of(), 0, 0, Instant.EPOCH, false, List.of());
        }
        if (payloadClass == NuggetDiscovery.Resolution.class) {
            return new NuggetDiscovery.Resolution(
                    NuggetDiscovery.ResolutionStatus.NOT_FOUND,
                    List.of(),
                    "not-found",
                    MiddlewareErrorCode.NOT_FOUND,
                    Instant.EPOCH,
                    List.of());
        }
        if (payloadClass == NuggetDiscovery.StatusSummary.class) {
            return new NuggetDiscovery.StatusSummary(false, 0, Instant.EPOCH, Instant.EPOCH, 0, Map.of());
        }
        if (payloadClass == NuggetAuthenticationRequest.class) {
            return NuggetAuthenticationRequest.of(
                    "correlation-default",
                    org.j7m.middleware.operations.api.NuggetAuthenticationSource.LOCAL,
                    "cli",
                    true,
                    Instant.EPOCH);
        }
        if (payloadClass == NuggetAuthenticationResult.class) {
            return new NuggetAuthenticationResult(
                    NuggetAuthenticationOutcome.ACCEPTED,
                    NuggetAuthenticationFailureReason.NONE,
                    "nugget-a",
                    "correlation-default",
                    "admission-a",
                    Duration.ZERO,
                    Instant.EPOCH,
                    null,
                    Map.of());
        }
        if (payloadClass == NuggetIntrospection.Request.class) {
            return new NuggetIntrospection.Request("", null, "correlation-default", Instant.EPOCH, Map.of());
        }
        if (payloadClass == NuggetIntrospection.Query.class) {
            return NuggetIntrospection.Query.all();
        }
        if (payloadClass == NuggetIntrospection.DiskQuery.class) {
            return new NuggetIntrospection.DiskQuery("", NuggetIntrospection.Query.all());
        }
        if (payloadClass == NuggetIntrospection.RecentErrorQuery.class) {
            return new NuggetIntrospection.RecentErrorQuery("", NuggetIntrospection.Query.all());
        }
        if (payloadClass == NuggetAdminQuery.class) {
            return NuggetAdminQuery.all();
        }
        if (payloadClass == NuggetStatus.class) {
            return sampleStatus();
        }
        if (payloadClass == NuggetIntrospection.Result.class) {
            return sampleIntrospectionResult();
        }
        if (payloadClass == NuggetApplicationLifecycle.Request.class) {
            return sampleLifecycleRequest();
        }
        if (payloadClass == NuggetApplicationLifecycle.Result.class) {
            return sampleLifecycleResult(NuggetApplicationLifecycle.Outcome.COMPLETED);
        }
        if (payloadClass == NuggetControl.Request.class) {
            return sampleControlRequest();
        }
        if (payloadClass == NuggetControl.Result.class) {
            return sampleControlResult(NuggetControl.Outcome.COMPLETED);
        }
        throw new IllegalArgumentException("test fixture missing for " + payloadClass.getName());
    }

    private static NuggetAdminCapabilities sampleCapabilities() {
        return new NuggetAdminCapabilities(
                true,
                NuggetAdminContract.PROTOCOL_VERSION,
                NuggetAdminContract.JSON_FORMAT_NAME,
                NuggetAdminContract.JSON_FORMAT_VERSION,
                List.of(NuggetCapability.READ_INFO),
                List.of(NuggetAdminOperationKind.CAPABILITIES),
                NuggetAdminContract.MAX_PAGE_LIMIT,
                NuggetAdminContract.MAX_METADATA_ENTRIES,
                Instant.EPOCH,
                Map.of());
    }

    private static NuggetStatus sampleStatus() {
        RuntimeIdentity runtimeIdentity = new RuntimeIdentity("runtime-a", "default", Map.of());
        NuggetIdentity identity = new NuggetIdentity(
                "nugget-a",
                "nugget-a",
                runtimeIdentity,
                Instant.EPOCH,
                "",
                "host",
                "1",
                "25",
                NuggetState.RUNNING,
                Map.of(),
                Map.of());
        NuggetConfigurationSummary configuration = new NuggetConfigurationSummary(
                NuggetAdminState.ENABLED,
                true,
                false,
                NuggetPinSourceType.PROTECTED_FILE,
                false,
                "j7m-pin-v1",
                Map.of());
        return new NuggetStatus(identity, configuration, Instant.EPOCH, Map.of());
    }

    private static NuggetIntrospection.Result<String> sampleIntrospectionResult() {
        return new NuggetIntrospection.Result<>(
                true,
                true,
                NuggetIntrospection.Category.OVERVIEW,
                Instant.EPOCH,
                false,
                "generic-content",
                null,
                List.of(),
                Map.of());
    }

    private static NuggetIntrospection.Result<Number> introspectionScalarResult(final Number value) {
        return new NuggetIntrospection.Result<>(
                true,
                true,
                NuggetIntrospection.Category.METRICS,
                Instant.EPOCH,
                false,
                value,
                null,
                List.of(),
                Map.of());
    }

    private static NuggetApplicationLifecycle.Request sampleLifecycleRequest() {
        return new NuggetApplicationLifecycle.Request(
                "admission-a",
                null,
                "correlation-default",
                NuggetApplicationLifecycle.Action.START,
                ApplicationCoordinate.parse("org.example:demo:1.0.0"),
                null,
                true,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(30L),
                Map.of());
    }

    private static NuggetApplicationLifecycle.Result sampleLifecycleResult(
            final NuggetApplicationLifecycle.Outcome outcome) {
        return sampleLifecycleResult(outcome, "lifecycle-operation");
    }

    private static NuggetApplicationLifecycle.Result sampleLifecycleResult(
            final NuggetApplicationLifecycle.Outcome outcome,
            final String operationId) {
        return new NuggetApplicationLifecycle.Result(
                operationId,
                "correlation-default",
                NuggetApplicationLifecycle.Action.START,
                true,
                outcome != NuggetApplicationLifecycle.Outcome.REJECTED,
                true,
                "nugget-a",
                "runtime-a",
                ApplicationCoordinate.parse("org.example:demo:1.0.0"),
                new ApplicationInstanceId("instance-a"),
                null,
                null,
                null,
                outcome,
                outcome.name(),
                outcome == NuggetApplicationLifecycle.Outcome.ACCEPTED,
                false,
                outcome == NuggetApplicationLifecycle.Outcome.COMPLETED,
                false,
                outcome == NuggetApplicationLifecycle.Outcome.UNKNOWN,
                "verified",
                "authorized",
                null,
                null,
                null,
                List.of(),
                outcome == NuggetApplicationLifecycle.Outcome.FAILED
                        ? error(MiddlewareErrorCode.INTERNAL, context(NuggetAdminOperationKind.APPLICATION_LIFECYCLE))
                        : null,
                Instant.EPOCH,
                Instant.EPOCH,
                Duration.ZERO,
                Map.of());
    }

    private static ApplicationActivation sampleActivation() {
        ApplicationCoordinate coordinate = ApplicationCoordinate.parse("org.example:demo:1.0.0");
        RuntimeIdentity runtimeIdentity = new RuntimeIdentity("runtime-a", "default", Map.of());
        ApplicationInstanceIdentity instanceIdentity = new ApplicationInstanceIdentity(
                new ApplicationInstanceId("instance-a"),
                new ApplicationIdentity(
                        coordinate,
                        "Demo",
                        "default"),
                runtimeIdentity,
                "CLASSPATH");
        return new ApplicationActivation(
                new ActivationIdentity("activation-a"),
                instanceIdentity,
                null,
                List.of(new RuntimeServiceIdentity(
                        "demo-service",
                        "org.example.DemoService",
                        "org.example.internal.SecretImplementation")),
                ordered("safe", "value", "token", "secret"));
    }

    private static NuggetControl.Request sampleControlRequest() {
        return new NuggetControl.Request(
                "admission-a",
                null,
                "control-operation",
                "correlation-default",
                NuggetControl.Action.DRAIN_NUGGET,
                NuggetControl.Mode.GRACEFUL,
                "nugget-a",
                "runtime-a",
                1L,
                "",
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(30L),
                Map.of());
    }

    private static NuggetControl.Result sampleControlResult(final NuggetControl.Outcome outcome) {
        return sampleControlResult(outcome, "control-operation");
    }

    private static NuggetControl.Result sampleControlResult(
            final NuggetControl.Outcome outcome,
            final String operationId) {
        return new NuggetControl.Result(
                operationId,
                "correlation-default",
                NuggetControl.Action.DRAIN_NUGGET,
                NuggetControl.Mode.GRACEFUL,
                true,
                outcome != NuggetControl.Outcome.DENIED,
                "nugget-a",
                "runtime-a",
                1L,
                NuggetState.RUNNING,
                NuggetState.DRAINING,
                NuggetState.DRAINING,
                outcome,
                outcome.name(),
                outcome == NuggetControl.Outcome.ACCEPTED,
                outcome == NuggetControl.Outcome.IN_PROGRESS,
                outcome == NuggetControl.Outcome.COMPLETED,
                outcome == NuggetControl.Outcome.PARTIAL,
                outcome == NuggetControl.Outcome.OUTCOME_UNKNOWN,
                false,
                null,
                List.of(),
                outcome == NuggetControl.Outcome.DENIED
                        ? error(MiddlewareErrorCode.PERMISSION_DENIED, context(NuggetAdminOperationKind.NUGGET_CONTROL))
                        : null,
                Instant.EPOCH,
                Instant.EPOCH,
                Duration.ZERO,
                Map.of());
    }

    private static RemoteError error(final MiddlewareErrorCode code, final NuggetAdminRequestContext context) {
        return new RemoteError(
                code,
                code.name(),
                Map.of("reason", code.name()),
                context.correlationId(),
                context.target().runtimeId(),
                false,
                false);
    }

    private static Map<String, Object> capabilitiesRequestFixture() {
        return envelope(
                NuggetAdminOperationKind.CAPABILITIES,
                "",
                null,
                false,
                NuggetAdminOutcome.COMPLETED,
                "");
    }

    private static Map<String, Object> capabilitiesFixture() {
        return envelope(
                NuggetAdminOperationKind.CAPABILITIES,
                NuggetAdminCapabilities.PAYLOAD_TYPE,
                map(
                        "available", true,
                        "protocolVersion", 1,
                        "jsonFormatName", NuggetAdminContract.JSON_FORMAT_NAME,
                        "jsonFormatVersion", 1,
                        "capabilities", List.of("READ_INFO"),
                        "operationKinds", List.of("CAPABILITIES"),
                        "maxPageLimit", NuggetAdminContract.MAX_PAGE_LIMIT,
                        "maxMetadataEntries", NuggetAdminContract.MAX_METADATA_ENTRIES,
                        "collectedAt", "2026-01-01T00:00:00Z",
                        "metadata", Map.of("provider", "runtime")),
                true,
                NuggetAdminOutcome.COMPLETED,
                "");
    }

    private static Map<String, Object> discoveryQueryRequestFixture() {
        return envelope(
                NuggetAdminOperationKind.DISCOVERY,
                "nugget-discovery-query",
                map(
                        "includeStale", false,
                        "limit", 64,
                        "correlationId", "correlation-default"),
                false,
                NuggetAdminOutcome.COMPLETED,
                "");
    }

    private static Map<String, Object> discoveryFixture() {
        return envelope(
                NuggetAdminOperationKind.DISCOVERY,
                "nugget-discovery-result",
                map(
                        "records", List.of(discoveryRecordFixture()),
                        "returned", 1,
                        "totalAvailable", 1,
                        "collectedAt", "2026-01-01T00:00:00Z",
                        "partial", false,
                        "warnings", List.of()),
                true,
                NuggetAdminOutcome.COMPLETED,
                "");
    }

    private static Map<String, Object> discoveryRecordFixture() {
        return map(
                "nuggetId", "nugget-a",
                "nuggetName", "nugget-a",
                "runtimeId", "runtime-a",
                "endpoint", map(
                        "transport", "local",
                        "scheme", "j7m-local",
                        "host", "",
                        "port", -1,
                        "localId", "admin"),
                "protocolMinimum", 1,
                "protocolMaximum", 1,
                "compatibility", "COMPATIBLE",
                "capabilities", List.of("READ_INFO"),
                "processId", "123",
                "hostSummary", "LOCAL",
                "startedAt", "2026-01-01T00:00:00Z",
                "lastSeenAt", "2026-01-01T00:00:01Z",
                "expiresAt", "2026-01-01T00:01:00Z",
                "source", "LOCAL_RECORD",
                "status", "ACTIVE",
                "warnings", List.of());
    }

    private static Map<String, Object> discoveryResolutionFixture() {
        return envelope(
                NuggetAdminOperationKind.DISCOVERY,
                "nugget-discovery-resolution",
                map(
                        "status", "NOT_FOUND",
                        "records", List.of(),
                        "safeReason", "not-found",
                        "errorCode", "NOT_FOUND",
                        "resolvedAt", "2026-01-01T00:00:00Z",
                        "warnings", List.of()),
                true,
                NuggetAdminOutcome.COMPLETED,
                "");
    }

    private static Map<String, Object> authenticationRequestFixture() {
        return envelope(
                NuggetAdminOperationKind.AUTHENTICATION,
                "nugget-authentication-request",
                map(
                        "correlationId", "correlation-default",
                        "source", "LOCAL",
                        "sourceId", "j7m-cli",
                        "trustedSource", true,
                        "requestedAt", "2026-01-01T00:00:00Z"),
                false,
                NuggetAdminOutcome.COMPLETED,
                "");
    }

    private static Map<String, Object> authenticationFixture() {
        return envelope(
                NuggetAdminOperationKind.AUTHENTICATION,
                "nugget-authentication-result",
                map(
                        "outcome", "ACCEPTED",
                        "failureReason", "NONE",
                        "nuggetId", "nugget-a",
                        "correlationId", "correlation-default",
                        "admissionAccepted", true,
                        "retryAfterMillis", 0,
                        "timestamp", "2026-01-01T00:00:00Z",
                        "metadata", Map.of("authorization", "not-granted")),
                true,
                NuggetAdminOutcome.COMPLETED,
                "");
    }

    private static Map<String, Object> introspectionQueryRequestFixture() {
        return envelope(
                NuggetAdminOperationKind.INTROSPECTION,
                "nugget-admin-query",
                adminQueryFixture(),
                false,
                NuggetAdminOutcome.COMPLETED,
                "");
    }

    private static Map<String, Object> introspectionFixture() {
        return introspectionFixture(inspectionSummaryFixture());
    }

    private static Map<String, Object> introspectionFixture(final Object value) {
        return envelope(
                NuggetAdminOperationKind.INTROSPECTION,
                "nugget-introspection-result",
                map(
                        "admitted", true,
                        "authorized", true,
                        "category", "OBJECTS",
                        "collectedAt", "2026-01-01T00:00:00Z",
                        "partial", false,
                        "value", value,
                        "warnings", List.of(),
                        "metadata", Map.of("source", "test")),
                true,
                NuggetAdminOutcome.COMPLETED,
                "");
    }

    private static Map<String, Object> inspectionSummaryFixture() {
        return map(
                "category", "OBJECTS",
                "page", map(
                        "offset", 0,
                        "limit", 50,
                        "returned", 1,
                        "totalAvailable", 1,
                        "partial", false,
                        "truncated", false),
                "items", List.of(map(
                        "id", "object-a",
                        "name", "service-a",
                        "value", "safe-value",
                        "metadata", Map.of("source", "test"))),
                "metadata", Map.of("source", "test"));
    }

    private static List<Map<String, Object>> introspectionValueFixtures() {
        Map<String, Object> keyValue = map("key", "collector", "value", "g1");
        Map<String, Object> component = map(
                "component", "runtime",
                "availability", "AVAILABLE",
                "health", "HEALTHY",
                "readiness", "READY",
                "required", true,
                "safeReason", "ok",
                "metadata", Map.of("source", "test"));
        Map<String, Object> runtime = runtimeSummaryFixture();
        Map<String, Object> health = map(
                "state", "HEALTHY",
                "components", List.of(component),
                "collectedAt", "2026-01-01T00:00:00Z",
                "partial", false);
        Map<String, Object> readiness = map(
                "state", "READY",
                "components", List.of(component),
                "collectedAt", "2026-01-01T00:00:00Z",
                "partial", false);
        Map<String, Object> application = applicationSummaryFixture();
        Map<String, Object> object = objectSummaryFixture();
        return List.of(
                inspectionSummaryFixture(),
                collectionResultFixture(keyValue),
                overviewFixture(runtime, health, readiness),
                environmentSummaryFixture(keyValue),
                diskVolumeSummaryFixture(),
                health,
                readiness,
                runtime,
                transportSummaryFixture(),
                registrySummaryFixture(),
                repositorySummaryFixture(),
                application,
                applicationDetailFixture(application),
                object,
                objectDetailFixture(object),
                serviceSummaryFixture(),
                recentErrorEntryFixture(),
                metricsSummaryFixture(),
                metricEntryFixture(),
                keyValue);
    }

    private static Map<String, Object> collectionResultFixture(final Map<String, Object> item) {
        return map(
                "items", List.of(item),
                "offset", 0,
                "limit", 50,
                "returned", 1,
                "totalAvailable", 1,
                "totalAvailableAuthorized", true,
                "sortedBy", "id",
                "direction", "ASC",
                "filters", Map.of("source", "test"),
                "collectedAt", "2026-01-01T00:00:00Z",
                "partial", false,
                "metadata", Map.of("source", "test"));
    }

    private static Map<String, Object> overviewFixture(
            final Map<String, Object> runtime,
            final Map<String, Object> health,
            final Map<String, Object> readiness) {
        return map(
                "status", nuggetStatusFixture(),
                "runtime", runtime,
                "health", health,
                "readiness", readiness,
                "partial", false,
                "warnings", List.of());
    }

    private static Map<String, Object> nuggetStatusFixture() {
        return map(
                "identity", map(
                        "nuggetId", "nugget-a",
                        "nuggetName", "nugget-a",
                        "runtimeId", "runtime-a",
                        "runtimeIdentity", map(
                                "runtimeId", "runtime-a",
                                "registryContext", "default",
                                "metadata", Map.of("source", "test")),
                        "startedAt", "2026-01-01T00:00:00Z",
                        "processId", "123",
                        "hostSummary", "LOCAL",
                        "j7mVersion", "1",
                        "jvmVersion", "25",
                        "state", "RUNNING",
                        "capabilities", Map.of("READ_INFO", "true"),
                        "metadata", Map.of("source", "test")),
                "configuration", map(
                        "adminState", "ENABLED",
                        "adminEnabled", true,
                        "unsafeDeveloperMode", false,
                        "pinSourceType", "PROTECTED_FILE",
                        "lowerSafetyPinSource", false,
                        "verifierFormat", "j7m-pin-v1",
                        "metadata", Map.of("source", "test")),
                "statusAt", "2026-01-01T00:00:00Z",
                "metadata", Map.of("source", "test"));
    }

    private static Map<String, Object> environmentSummaryFixture(final Map<String, Object> keyValue) {
        return map(
                "jvmVersion", "25",
                "jvmVendor", "OpenJDK",
                "vmName", "J7M VM",
                "osName", "Linux",
                "osVersion", "6",
                "architecture", "amd64",
                "processId", "123",
                "nuggetStartTime", "2026-01-01T00:00:00Z",
                "processUptimeMillis", 1,
                "availableProcessors", 4,
                "heapUsedBytes", 1,
                "heapCommittedBytes", 1,
                "heapMaxBytes", -1,
                "nonHeapUsedBytes", 1,
                "nonHeapCommittedBytes", 1,
                "liveThreadCount", 1,
                "daemonThreadCount", 1,
                "peakThreadCount", 1,
                "totalStartedThreadCount", 1,
                "loadedClassCount", 1,
                "totalLoadedClassCount", 1,
                "garbageCollectors", List.of(keyValue),
                "processCpuLoad", -1,
                "systemCpuLoad", -1,
                "processCpuTimeNanos", -1,
                "workingDirectorySummary", "redacted",
                "environmentVariableNames", List.of("PATH"),
                "jvmArgumentSummaries", List.of(keyValue),
                "capabilities", Map.of("source", "test"),
                "collectedAt", "2026-01-01T00:00:00Z");
    }

    private static Map<String, Object> diskVolumeSummaryFixture() {
        return map(
                "rootId", "root-a",
                "role", "repository",
                "roles", List.of("repository"),
                "pathSummary", "redacted",
                "totalBytes", -1,
                "usableBytes", -1,
                "availableBytes", -1,
                "usedBytes", -1,
                "percentUsed", -1,
                "health", "HEALTHY",
                "readOnly", false,
                "availability", "AVAILABLE",
                "collectedAt", "2026-01-01T00:00:00Z",
                "safeErrorCode", "",
                "metadata", Map.of("source", "test"));
    }

    private static Map<String, Object> runtimeSummaryFixture() {
        return map(
                "runtimeId", "runtime-a",
                "nuggetId", "nugget-a",
                "lifecycleState", "RUNNING",
                "startTime", "2026-01-01T00:00:00Z",
                "uptimeMillis", 1,
                "activeObjectCount", 1,
                "exportedObjectCount", 1,
                "hostedApplicationCount", 1,
                "invocationCount", 1,
                "invocationFailureCount", 0,
                "recentFailureCount", 0,
                "security", Map.of("source", "test"),
                "collectedAt", "2026-01-01T00:00:00Z");
    }

    private static Map<String, Object> transportSummaryFixture() {
        return map(
                "transportId", "grpc",
                "endpointSummary", "local",
                "running", true,
                "metadata", Map.of("source", "test"));
    }

    private static Map<String, Object> registrySummaryFixture() {
        return map(
                "recordCount", 1,
                "peerCount", 1,
                "multicastListenerRunning", false,
                "bootstrapHintCount", 0,
                "lookupCacheSize", 0,
                "metrics", Map.of("records", 1),
                "peerIds", List.of("peer-a"),
                "recentEvents", List.of("joined"),
                "collectedAt", "2026-01-01T00:00:00Z");
    }

    private static Map<String, Object> repositorySummaryFixture() {
        return map(
                "rootSummary", "repository",
                "artifactCount", 1,
                "promotionCount", 0,
                "metadata", Map.of("source", "test"),
                "collectedAt", "2026-01-01T00:00:00Z");
    }

    private static Map<String, Object> applicationSummaryFixture() {
        return map(
                "applicationId", "org.example:demo",
                "coordinate", "org.example:demo:1.0.0",
                "instanceId", "instance-a",
                "displayName", "Demo",
                "version", "1.0.0",
                "state", "RUNNING",
                "activationId", "activation-a",
                "exportedServiceCount", 1,
                "health", "HEALTHY",
                "readiness", "READY",
                "lastSafeErrorCode", "",
                "metadata", Map.of("source", "test"));
    }

    private static Map<String, Object> applicationDetailFixture(final Map<String, Object> summary) {
        return map(
                "summary", summary,
                "declaredServices", List.of(serviceSummaryFixture()),
                "moduleNames", List.of("demo"),
                "capabilities", Map.of("source", "test"),
                "verificationStatus", "verified",
                "signatureStatus", "unsigned",
                "sbomStatus", "absent",
                "provenanceStatus", "unknown",
                "auditCounters", Map.of("events", 1));
    }

    private static Map<String, Object> objectSummaryFixture() {
        return map(
                "nuggetId", "nugget-a",
                "objectId", "object-a",
                "logicalName", "demo",
                "serviceName", "demo-service",
                "kind", "SERVICE",
                "visibility", "EXPORTED",
                "lifecycleState", "RUNNING",
                "registrationState", "ACTIVE",
                "ownerApplication", "org.example:demo",
                "interfaces", List.of("org.example.DemoService"),
                "registryContext", "default",
                "local", true,
                "endpointSummary", "local",
                "transportId", "grpc",
                "health", "HEALTHY",
                "readiness", "READY",
                "leaseExpiryMillis", 1,
                "generation", 1,
                "invocationCount", 1,
                "failureCount", 0,
                "lastInvocationTime", "2026-01-01T00:00:00Z",
                "lastSafeErrorCode", "",
                "sensitive", false,
                "metadata", Map.of("source", "test"));
    }

    private static Map<String, Object> objectDetailFixture(final Map<String, Object> summary) {
        return map(
                "summary", summary,
                "descriptorMetadata", Map.of("source", "test"),
                "annotationMetadata", Map.of("source", "test"));
    }

    private static Map<String, Object> serviceSummaryFixture() {
        return map(
                "serviceName", "demo-service",
                "interfaceName", "org.example.DemoService",
                "implementationSummary", "");
    }

    private static Map<String, Object> recentErrorEntryFixture() {
        return map(
                "timestamp", "2026-01-01T00:00:00Z",
                "correlationId", "correlation-default",
                "component", "runtime",
                "resourceSummary", "resource",
                "errorCode", "INTERNAL",
                "safeMessage", "failed",
                "retryable", false,
                "remoteRuntimeId", "runtime-b",
                "recurrenceCount", 1,
                "metadata", Map.of("source", "test"));
    }

    private static Map<String, Object> metricsSummaryFixture() {
        return map(
                "metrics", List.of(metricEntryFixture()),
                "collectedAt", "2026-01-01T00:00:00Z",
                "partial", false);
    }

    private static Map<String, Object> metricEntryFixture() {
        return map(
                "id", "requests",
                "unit", "count",
                "value", 1,
                "labels", Map.of("source", "test"));
    }

    private static Map<String, Object> applicationLifecycleRequestFixture() {
        return envelope(
                NuggetAdminOperationKind.APPLICATION_LIFECYCLE,
                "nugget-application-lifecycle-request",
                map(
                        "correlationId", "correlation-default",
                        "action", "START",
                        "coordinate", "org.example:demo:1.0.0",
                        "dryRun", true,
                        "requestedAt", "2026-01-01T00:00:00Z",
                        "deadlineAt", "2026-01-01T00:00:30Z",
                        "metadata", Map.of("client", "test")),
                false,
                NuggetAdminOutcome.COMPLETED,
                "");
    }

    private static Map<String, Object> applicationLifecycleFixture() {
        return envelope(
                NuggetAdminOperationKind.APPLICATION_LIFECYCLE,
                "nugget-application-lifecycle-result",
                map(
                        "operationId", "lifecycle-operation",
                        "correlationId", "correlation-default",
                        "action", "START",
                        "admitted", true,
                        "authorized", true,
                        "dryRun", true,
                        "nuggetId", "nugget-a",
                        "runtimeId", "runtime-a",
                        "coordinate", "org.example:demo:1.0.0",
                        "instanceId", "instance-a",
                        "previousState", "PLANNED",
                        "currentState", "RUNNING",
                        "resultingState", "RUNNING",
                        "outcome", "COMPLETED",
                        "outcomeText", "COMPLETED",
                        "accepted", false,
                        "inProgress", false,
                        "completed", true,
                        "partial", false,
                        "outcomeUnknown", false,
                        "verificationSummary", "verified",
                        "authorizationSummary", "authorized",
                        "plan", applicationDeploymentPlanFixture(),
                        "deploymentResult", applicationDeploymentResultFixture(),
                        "activation", applicationActivationFixture(),
                        "warnings", List.of(),
                        "error", null,
                        "requestedAt", "2026-01-01T00:00:00Z",
                        "completedAt", "2026-01-01T00:00:00Z",
                        "elapsedMillis", 0,
                        "metadata", Map.of("source", "test")),
                true,
                NuggetAdminOutcome.COMPLETED,
                "");
    }

    private static Map<String, Object> nuggetControlRequestFixture() {
        return envelope(
                NuggetAdminOperationKind.NUGGET_CONTROL,
                "nugget-control-request",
                map(
                        "operationId", "control-operation",
                        "correlationId", "correlation-default",
                        "action", "DRAIN_NUGGET",
                        "mode", "GRACEFUL",
                        "targetNuggetId", "nugget-a",
                        "targetRuntimeId", "runtime-a",
                        "targetGeneration", 1,
                        "confirmationProvided", false,
                        "requestedAt", "2026-01-01T00:00:00Z",
                        "deadlineAt", "2026-01-01T00:00:30Z",
                        "metadata", Map.of("client", "test")),
                false,
                NuggetAdminOutcome.COMPLETED,
                "");
    }

    private static Map<String, Object> nuggetControlFixture(
            final NuggetAdminOutcome outcome,
            final String operationId) {
        return envelope(
                NuggetAdminOperationKind.NUGGET_CONTROL,
                "nugget-control-result",
                map(
                        "operationId", operationId,
                        "correlationId", "correlation-default",
                        "action", "DRAIN_NUGGET",
                        "mode", "GRACEFUL",
                        "admitted", true,
                        "authorized", outcome != NuggetAdminOutcome.DENIED,
                        "targetNuggetId", "nugget-a",
                        "targetRuntimeId", "runtime-a",
                        "targetGeneration", 1,
                        "previousState", "RUNNING",
                        "currentState", "DRAINING",
                        "resultingState", "DRAINING",
                        "outcome", controlOutcome(outcome),
                        "outcomeText", controlOutcome(outcome),
                        "accepted", outcome == NuggetAdminOutcome.ACCEPTED,
                        "inProgress", outcome == NuggetAdminOutcome.IN_PROGRESS,
                        "completed", outcome == NuggetAdminOutcome.COMPLETED,
                        "partial", outcome == NuggetAdminOutcome.PARTIAL,
                        "outcomeUnknown", outcome == NuggetAdminOutcome.OUTCOME_UNKNOWN,
                        "cleanupPartial", false,
                        "confirmationRequired", outcome == NuggetAdminOutcome.DENIED,
                        "confirmation", outcome == NuggetAdminOutcome.DENIED ? controlConfirmationFixture(operationId) : null,
                        "warnings", List.of(),
                        "error", null,
                        "requestedAt", "2026-01-01T00:00:00Z",
                        "completedAt", "2026-01-01T00:00:00Z",
                        "elapsedMillis", 0,
                        "metadata", Map.of("source", "test")),
                true,
                outcome,
                operationId);
    }

    private static Map<String, Object> applicationDeploymentPlanFixture() {
        return map(
                "coordinate", applicationCoordinateFixture(),
                "state", "PLANNED",
                "steps", List.of("verify-bundle"));
    }

    private static Map<String, Object> applicationDeploymentResultFixture() {
        return map(
                "success", true,
                "state", "RUNNING",
                "message", "completed",
                "details", List.of("activated"));
    }

    private static Map<String, Object> applicationActivationFixture() {
        return map(
                "activationIdentity", map("value", "activation-a"),
                "instanceIdentity", map(
                        "instanceId", map("value", "instance-a"),
                        "application", map(
                                "coordinate", applicationCoordinateFixture(),
                                "displayName", "Demo",
                                "trustDomain", "default"),
                        "runtime", map(
                                "runtimeId", "runtime-a",
                                "registryContext", "default",
                                "metadata", Map.of("source", "test")),
                        "classLoaderMode", "CLASSPATH"),
                "state", "RUNNING",
                "exportedServices", List.of(map(
                        "serviceName", "demo-service",
                        "interfaceName", "org.example.DemoService",
                        "implementationClass", "")),
                "metadata", Map.of("source", "test"));
    }

    private static Map<String, Object> applicationCoordinateFixture() {
        return map(
                "id", map(
                        "group", "org.example",
                        "name", "demo"),
                "version", map("value", "1.0.0"));
    }

    private static Map<String, Object> controlConfirmationFixture(final String operationId) {
        return map(
                "operationId", operationId,
                "challenge", "challenge-a",
                "expiresAt", "2026-01-01T00:00:30Z",
                "action", "DRAIN_NUGGET",
                "nuggetId", "nugget-a",
                "runtimeId", "runtime-a",
                "generation", 1,
                "callerId", "caller-a",
                "state", "RUNNING");
    }

    private static Map<String, Object> adminQueryFixture() {
        return map(
                "offset", 0,
                "limit", 50,
                "filters", List.of(),
                "sorts", List.of(map("field", "STABLE_ID", "direction", "ASC")));
    }

    private static String controlOutcome(final NuggetAdminOutcome outcome) {
        if (outcome == NuggetAdminOutcome.DENIED) {
            return "CONFIRMATION_REQUIRED";
        }
        return outcome.name();
    }

    private static Map<String, Object> unavailableFixture(final NuggetAdminOperationKind kind) {
        return envelope(kind, "", null, true, NuggetAdminOutcome.UNAVAILABLE, "");
    }

    private static Map<String, Object> envelope(
            final NuggetAdminOperationKind kind,
            final String payloadType,
            final Object payload,
            final boolean result,
            final NuggetAdminOutcome outcome,
            final String operationId) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("formatName", NuggetAdminContract.JSON_FORMAT_NAME);
        values.put("formatVersion", 1);
        values.put("protocolVersion", 1);
        values.put("requestId", "request-default");
        values.put("correlationId", "correlation-default");
        values.put("operationKind", kind.name());
        values.put("payloadType", payloadType);
        values.put("target", map(
                "nuggetId", "nugget-a",
                "runtimeId", "runtime-a",
                "generation", 1,
                "endpointIdentity", "j7m-local:admin",
                "providerIdentity", "provider-a"));
        values.put("context", map(
                "requestedAt", "2026-01-01T00:00:00Z",
                "clientBudgetMillis", 30000,
                "clientDeadlineAt", "2026-01-01T00:00:30Z",
                "metadata", Map.of("client", "test")));
        if (result) {
            values.put("outcome", outcome.name());
            values.put("header", headerFixture(kind, outcome, operationId));
        }
        values.put("payload", payload);
        return values;
    }

    private static Map<String, Object> headerFixture(
            final NuggetAdminOperationKind kind,
            final NuggetAdminOutcome outcome,
            final String operationId) {
        return map(
                "formatName", NuggetAdminContract.JSON_FORMAT_NAME,
                "formatVersion", 1,
                "protocolVersion", 1,
                "requestId", "request-default",
                "correlationId", "correlation-default",
                "operationKind", kind.name(),
                "operationId", operationId,
                "outcome", outcome.name(),
                "accepted", outcome == NuggetAdminOutcome.ACCEPTED,
                "inProgress", outcome == NuggetAdminOutcome.IN_PROGRESS,
                "completed", outcome == NuggetAdminOutcome.COMPLETED,
                "denied", outcome == NuggetAdminOutcome.DENIED,
                "unavailable", outcome == NuggetAdminOutcome.UNAVAILABLE,
                "timedOut", outcome == NuggetAdminOutcome.TIMED_OUT,
                "partial", outcome == NuggetAdminOutcome.PARTIAL,
                "outcomeUnknown", outcome == NuggetAdminOutcome.OUTCOME_UNKNOWN,
                "returnedCount", 0,
                "totalAvailable", 0,
                "truncated", false,
                "warnings", List.of(),
                "requestedAt", "2026-01-01T00:00:00Z",
                "completedAt", "2026-01-01T00:00:00Z",
                "elapsedMillis", 0,
                "metadata", Map.of("source", "test"));
    }

    private static LinkedHashMap<String, Object> map(final Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private static Map<String, Object> copy(final Map<String, Object> source) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            result.put(entry.getKey(), copyJsonValue(entry.getValue()));
        }
        return result;
    }

    private static Object copyJsonValue(final Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> nestedEntry : mapValue.entrySet()) {
                nested.put((String) nestedEntry.getKey(), copyJsonValue(nestedEntry.getValue()));
            }
            return nested;
        }
        if (value instanceof List<?> listValue) {
            ArrayList<Object> nested = new ArrayList<>(listValue.size());
            for (Object item : listValue) {
                nested.add(copyJsonValue(item));
            }
            return nested;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(final Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> header(final Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("header");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> context(final Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("context");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> inspectionItem(final Map<String, Object> envelope) {
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        Map<String, Object> value = (Map<String, Object>) payload.get("value");
        List<Map<String, Object>> items = (List<Map<String, Object>>) value.get("items");
        return items.get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> discoveryRecords(final Map<String, Object> envelope) {
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        return (List<Map<String, Object>>) payload.get("records");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nuggetIdentity(final Map<String, Object> envelope) {
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        Map<String, Object> value = (Map<String, Object>) payload.get("value");
        Map<String, Object> status = (Map<String, Object>) value.get("status");
        return (Map<String, Object>) status.get("identity");
    }

    private static List<String> repeatedStrings(final int count) {
        ArrayList<String> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add("warning-" + index);
        }
        return values;
    }

    private static List<String> repeatedSafeStrings(final int count, final String prefix) {
        ArrayList<String> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(prefix + index);
        }
        return values;
    }

    private static List<String> allFilteredWithSafeTail(final int filteredCount, final String tail) {
        ArrayList<String> values = new ArrayList<>(filteredCount + 1);
        for (int index = 0; index < filteredCount; index++) {
            values.add("token-secret-" + index);
        }
        values.add(tail);
        return values;
    }

    private static Map<String, String> metadata(final int count) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            values.put("entry-" + index, "value-" + index);
        }
        return values;
    }

    private static Map<String, String> aggregateMetadata() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < NuggetAdminContract.MAX_METADATA_ENTRIES; index++) {
            values.put("entry" + index, "value-" + "x".repeat(250));
        }
        return values;
    }

    private static void rejectBadMetadataKey(
            final SchemaBackedSemanticValidator validator,
            final String key) {
        Map<String, Object> fixture = copy(capabilitiesFixture());
        payload(fixture).put("metadata", Map.of(key, "value"));
        validator.reject(fixture);
    }

    private static void rejectBadMetadataValue(
            final SchemaBackedSemanticValidator validator,
            final String value) {
        Map<String, Object> fixture = copy(capabilitiesFixture());
        payload(fixture).put("metadata", Map.of("safe", value));
        validator.reject(fixture);
    }

    private static void setCommonOutcome(
            final Map<String, Object> envelope,
            final NuggetAdminOutcome outcome,
            final String operationId) {
        envelope.put("outcome", outcome.name());
        Map<String, Object> header = header(envelope);
        header.put("operationId", operationId);
        header.put("outcome", outcome.name());
        header.put("accepted", outcome == NuggetAdminOutcome.ACCEPTED);
        header.put("inProgress", outcome == NuggetAdminOutcome.IN_PROGRESS);
        header.put("completed", outcome == NuggetAdminOutcome.COMPLETED);
        header.put("denied", outcome == NuggetAdminOutcome.DENIED);
        header.put("unavailable", outcome == NuggetAdminOutcome.UNAVAILABLE);
        header.put("timedOut", outcome == NuggetAdminOutcome.TIMED_OUT);
        header.put("partial", outcome == NuggetAdminOutcome.PARTIAL);
        header.put("outcomeUnknown", outcome == NuggetAdminOutcome.OUTCOME_UNKNOWN);
    }

    private static Map<String, String> ordered(
            final String firstKey,
            final String firstValue,
            final String secondKey,
            final String secondValue) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put(firstKey, firstValue);
        values.put(secondKey, secondValue);
        return values;
    }

    private static List<org.j7m.middleware.operations.api.NuggetCapability> repeatedCapabilities(final int count) {
        ArrayList<org.j7m.middleware.operations.api.NuggetCapability> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(org.j7m.middleware.operations.api.NuggetCapability.READ_INFO);
        }
        return values;
    }

    private static Map<String, String> ordered(
            final String firstKey,
            final String firstValue,
            final String secondKey,
            final String secondValue,
            final String thirdKey,
            final String thirdValue) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put(firstKey, firstValue);
        values.put(secondKey, secondValue);
        values.put(thirdKey, thirdValue);
        return values;
    }

    private static String read(final String relativePath) throws IOException {
        return readBounded(ROOT.resolve(relativePath).normalize(), MAX_DOCUMENT_BYTES);
    }

    private static String readBounded(final Path path, final long maximumBytes) throws IOException {
        long size = Files.size(path);
        if (size > maximumBytes) {
            throw new IOException("file exceeds bounded test read size: " + path);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static JsonSchemaValidator schemaValidator() throws IOException {
        Object schema = JsonParser.parse(read("docs/schema/nugget-admin-contract.schema.json"));
        if (!(schema instanceof Map<?, ?> schemaMap)) {
            throw new IllegalArgumentException("schema root must be an object");
        }
        return new JsonSchemaValidator(stringObjectMap(schemaMap));
    }

    private static SchemaBackedSemanticValidator schemaBackedSemanticValidator() throws IOException {
        return new SchemaBackedSemanticValidator(schemaValidator());
    }

    private static Map<?, ?> asMap(final Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("schema node must be an object");
        }
        return map;
    }

    private static Map<String, Object> stringObjectMap(final Map<?, ?> source) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("JSON object key must be a string");
            }
            values.put(key, entry.getValue());
        }
        return values;
    }

    private static Set<String> schemaEnum(
            final Object parsedSchema,
            final String definitionName,
            final String propertyName) {
        Map<String, Object> root = stringObjectMap(asMap(parsedSchema));
        Map<String, Object> definitions = stringObjectMap(asMap(root.get("$defs")));
        Map<String, Object> definition = stringObjectMap(asMap(definitions.get(definitionName)));
        Map<String, Object> properties = stringObjectMap(asMap(definition.get("properties")));
        Map<String, Object> property = stringObjectMap(asMap(properties.get(propertyName)));
        Object enumNode = property.get("enum");
        if (!(enumNode instanceof List<?> values)) {
            throw new IllegalArgumentException("schema enum is missing");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("schema enum value must be a string");
            }
            result.add(text);
        }
        return result;
    }

    private static Set<String> enumNames(final Object[] values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof Enum<?> enumValue)) {
                throw new IllegalArgumentException("expected enum value");
            }
            result.add(enumValue.name());
        }
        return result;
    }

    private static Set<String> expectedPayloadTypes() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add("");
        for (NuggetAdminOperationKind kind : NuggetAdminOperationKind.values()) {
            for (PayloadBinding binding : kind.requestPayloads()) {
                result.add(binding.payloadType());
            }
            for (PayloadBinding binding : kind.resultPayloads()) {
                result.add(binding.payloadType());
            }
        }
        return result;
    }

    private static Set<String> schemaDefinitionEnum(final Object parsedSchema, final String definitionName) {
        Map<String, Object> definition = schemaDefinition(parsedSchema, definitionName);
        Object enumNode = definition.get("enum");
        if (!(enumNode instanceof List<?> values)) {
            throw new IllegalArgumentException("schema enum is missing");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("schema enum value must be a string");
            }
            result.add(text);
        }
        return result;
    }

    private static Set<String> schemaRequiredFields(final Object parsedSchema, final String definitionName) {
        Map<String, Object> definition = definitionName.isEmpty()
                ? stringObjectMap(asMap(parsedSchema))
                : schemaDefinition(parsedSchema, definitionName);
        Object requiredNode = definition.get("required");
        if (!(requiredNode instanceof List<?> values)) {
            throw new IllegalArgumentException("schema required field list is missing");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("schema required value must be a string");
            }
            result.add(text);
        }
        return result;
    }

    private static long schemaMaximum(
            final Object parsedSchema,
            final String definitionName,
            final String maximumName) {
        Map<String, Object> definition = schemaDefinition(parsedSchema, definitionName);
        String key = maximumName == null ? "maxLength" : maximumName;
        Object maximum = definition.get(key);
        if (!(maximum instanceof Number number)) {
            throw new IllegalArgumentException("schema maximum is missing");
        }
        return number.longValue();
    }

    private static long schemaPropertyMaximum(
            final Object parsedSchema,
            final String definitionName,
            final String propertyName) {
        Map<String, Object> definition = schemaDefinition(parsedSchema, definitionName);
        Map<String, Object> properties = stringObjectMap(asMap(definition.get("properties")));
        Map<String, Object> property = stringObjectMap(asMap(properties.get(propertyName)));
        Object maximum = property.get("maximum");
        if (!(maximum instanceof Number number)) {
            throw new IllegalArgumentException("schema property maximum is missing");
        }
        return number.longValue();
    }

    private static Map<String, Object> schemaDefinition(final Object parsedSchema, final String definitionName) {
        Map<String, Object> root = stringObjectMap(asMap(parsedSchema));
        Map<String, Object> definitions = stringObjectMap(asMap(root.get("$defs")));
        return stringObjectMap(asMap(definitions.get(definitionName)));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("README.md"))
                    && Files.isDirectory(current.resolve("docs"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root was not found");
    }

    private static final class LeakyPayload {
        @Override
        public String toString() {
            return "provider-secret-sentinel";
        }
    }

    private static final class CliPrivatePayload {
    }

    private static final class SchemaBackedSemanticValidator {
        private static final int MAX_SEMANTIC_DEPTH = 64;
        private final JsonSchemaValidator schema;

        private SchemaBackedSemanticValidator(final JsonSchemaValidator schema) {
            this.schema = schema;
        }

        private void validate(final Object instance) {
            this.schema.validate(instance);
            Map<String, Object> envelope = stringObjectMap(asMap(instance));
            validateEnvelope(envelope);
            validateJsonText(envelope, 0);
            validateMetadataContainers(envelope, 0);
            validateQueriesAndPages(envelope, 0);
        }

        private void reject(final Object instance) {
            assertThrows(RuntimeException.class, () -> validate(instance));
        }

        private static void validateEnvelope(final Map<String, Object> envelope) {
            NuggetAdminOperationKind kind = NuggetAdminOperationKind.valueOf(text(envelope, "operationKind"));
            String payloadType = text(envelope, "payloadType");
            Object headerNode = envelope.get("header");
            if (headerNode instanceof Map<?, ?> rawHeader) {
                Map<String, Object> header = stringObjectMap(rawHeader);
                requireEquals(envelope.get("formatName"), header.get("formatName"), "formatName");
                requireEquals(envelope.get("formatVersion"), header.get("formatVersion"), "formatVersion");
                requireEquals(envelope.get("protocolVersion"), header.get("protocolVersion"), "protocolVersion");
                requireEquals(envelope.get("requestId"), header.get("requestId"), "requestId");
                requireEquals(envelope.get("correlationId"), header.get("correlationId"), "correlationId");
                requireEquals(envelope.get("operationKind"), header.get("operationKind"), "operationKind");
                requireEquals(envelope.get("outcome"), header.get("outcome"), "outcome");
                validateOutcomeFlags(header);
                validateOperationIdPolicy(kind, header, envelope.get("payload"));
                validateHeaderCounts(header);
                validatePayloadOperationId(kind, header, envelope.get("payload"));
            } else {
                validateRequestPayload(kind, payloadType, envelope.get("payload"));
            }
            validatePayloadBinding(kind, payloadType, headerNode instanceof Map<?, ?>);
            validateTargetConsistency(envelope);
        }

        private static void validatePayloadBinding(
                final NuggetAdminOperationKind kind,
                final String payloadType,
                final boolean result) {
            if (payloadType.isEmpty()) {
                return;
            }
            List<PayloadBinding> bindings = result ? kind.resultPayloads() : kind.requestPayloads();
            for (PayloadBinding binding : bindings) {
                if (binding.payloadType().equals(payloadType)) {
                    return;
                }
            }
            throw new IllegalArgumentException("payload type does not match operation kind");
        }

        private static void validateRequestPayload(
                final NuggetAdminOperationKind kind,
                final String payloadType,
                final Object payload) {
            if (kind.readOnly() && payload instanceof Map<?, ?> rawPayload) {
                Map<String, Object> object = stringObjectMap(rawPayload);
                if (object.containsKey("operationId")
                        || object.containsKey("confirmationProvided")
                        || object.containsKey("confirmationRequired")) {
                    throw new IllegalArgumentException("read-only request contains mutation state");
                }
            }
            if (!kind.confirmationPermitted() && payload instanceof Map<?, ?> rawPayload) {
                Map<String, Object> object = stringObjectMap(rawPayload);
                if (object.containsKey("confirmationProvided") || object.containsKey("confirmationRequired")) {
                    throw new IllegalArgumentException("confirmation data is not permitted for this operation kind");
                }
            }
            if (payloadType.equals("nugget-admin-query") && payload instanceof Map<?, ?> rawPayload) {
                validateOffsetAndLimit(stringObjectMap(rawPayload));
            }
        }

        private static void validateOperationIdPolicy(
                final NuggetAdminOperationKind kind,
                final Map<String, Object> header,
                final Object payload) {
            String operationId = text(header, "operationId");
            NuggetAdminOutcome outcome = NuggetAdminOutcome.valueOf(text(header, "outcome"));
            boolean accepted = flag(header, "accepted");
            boolean inProgress = flag(header, "inProgress");
            boolean outcomeUnknown = flag(header, "outcomeUnknown");
            boolean timedOut = flag(header, "timedOut");
            if (kind.readOnly() && (!operationId.isBlank()
                    || accepted
                    || inProgress
                    || outcomeUnknown
                    || outcome == NuggetAdminOutcome.ACCEPTED
                    || outcome == NuggetAdminOutcome.IN_PROGRESS
                    || outcome == NuggetAdminOutcome.OUTCOME_UNKNOWN)) {
                throw new IllegalArgumentException("read-only result contains mutation state");
            }
            if (outcome == NuggetAdminOutcome.UNAVAILABLE && (accepted || inProgress || !operationId.isBlank())) {
                throw new IllegalArgumentException("unavailable result contains accepted mutation state");
            }
            if (kind.acceptedMutationOperationIdentityRequired()
                    && payload != null
                    && (accepted || inProgress || outcomeUnknown || timedOut
                    || outcome == NuggetAdminOutcome.ACCEPTED
                    || outcome == NuggetAdminOutcome.IN_PROGRESS
                    || outcome == NuggetAdminOutcome.OUTCOME_UNKNOWN
                    || outcome == NuggetAdminOutcome.TIMED_OUT)
                    && operationId.isBlank()) {
                throw new IllegalArgumentException("accepted mutation result is missing operation id");
            }
        }

        private static void validatePayloadOperationId(
                final NuggetAdminOperationKind kind,
                final Map<String, Object> header,
                final Object payload) {
            if (!kind.acceptedMutationOperationIdentityRequired() || !(payload instanceof Map<?, ?> rawPayload)) {
                return;
            }
            Map<String, Object> object = stringObjectMap(rawPayload);
            if (!object.containsKey("operationId")) {
                return;
            }
            String payloadOperationId = text(object, "operationId");
            String headerOperationId = text(header, "operationId");
            if (!payloadOperationId.isBlank() && !headerOperationId.isBlank()
                    && !payloadOperationId.equals(headerOperationId)) {
                throw new IllegalArgumentException("payload operation id does not match header operation id");
            }
            NuggetAdminOutcome outcome = NuggetAdminOutcome.valueOf(text(header, "outcome"));
            if ((outcome == NuggetAdminOutcome.ACCEPTED
                    || outcome == NuggetAdminOutcome.IN_PROGRESS
                    || outcome == NuggetAdminOutcome.TIMED_OUT
                    || outcome == NuggetAdminOutcome.OUTCOME_UNKNOWN)
                    && payloadOperationId.isBlank()) {
                throw new IllegalArgumentException("mutation payload operation id is missing");
            }
        }

        private static void validateTargetConsistency(final Map<String, Object> envelope) {
            Object targetNode = envelope.get("target");
            Object payloadNode = envelope.get("payload");
            if (!(targetNode instanceof Map<?, ?> rawTarget) || !(payloadNode instanceof Map<?, ?> rawPayload)) {
                return;
            }
            Map<String, Object> target = stringObjectMap(rawTarget);
            Map<String, Object> payload = stringObjectMap(rawPayload);
            if (payload.containsKey("nuggetId")) {
                requireEquals(target.get("nuggetId"), payload.get("nuggetId"), "nuggetId");
            }
            if (payload.containsKey("runtimeId")) {
                requireEquals(target.get("runtimeId"), payload.get("runtimeId"), "runtimeId");
            }
            if (payload.containsKey("targetNuggetId")) {
                requireEquals(target.get("nuggetId"), payload.get("targetNuggetId"), "targetNuggetId");
            }
            if (payload.containsKey("targetRuntimeId")) {
                requireEquals(target.get("runtimeId"), payload.get("targetRuntimeId"), "targetRuntimeId");
            }
            if (payload.containsKey("targetGeneration")) {
                requireEquals(target.get("generation"), payload.get("targetGeneration"), "targetGeneration");
            }
        }

        private static void validateOutcomeFlags(final Map<String, Object> header) {
            NuggetAdminOutcome outcome = NuggetAdminOutcome.valueOf(text(header, "outcome"));
            int count = 0;
            count += flag(header, "accepted") ? 1 : 0;
            count += flag(header, "inProgress") ? 1 : 0;
            count += flag(header, "completed") ? 1 : 0;
            count += flag(header, "denied") ? 1 : 0;
            count += flag(header, "unavailable") ? 1 : 0;
            count += flag(header, "timedOut") ? 1 : 0;
            count += flag(header, "partial") ? 1 : 0;
            count += flag(header, "outcomeUnknown") ? 1 : 0;
            if (count != 1) {
                throw new IllegalArgumentException("result outcome flags are not exclusive");
            }
            boolean expected = switch (outcome) {
                case ACCEPTED -> flag(header, "accepted");
                case IN_PROGRESS -> flag(header, "inProgress");
                case COMPLETED -> flag(header, "completed");
                case DENIED -> flag(header, "denied");
                case UNAVAILABLE -> flag(header, "unavailable");
                case TIMED_OUT -> flag(header, "timedOut");
                case PARTIAL -> flag(header, "partial");
                case OUTCOME_UNKNOWN -> flag(header, "outcomeUnknown");
            };
            if (!expected) {
                throw new IllegalArgumentException("result outcome flag does not match outcome");
            }
        }

        private static void validateHeaderCounts(final Map<String, Object> header) {
            long returnedCount = number(header, "returnedCount");
            long totalAvailable = number(header, "totalAvailable");
            long elapsedMillis = number(header, "elapsedMillis");
            if (returnedCount > NuggetAdminContract.MAX_PAGE_LIMIT || totalAvailable < returnedCount
                    || elapsedMillis > NuggetAdminContract.MAX_DURATION_MILLIS) {
                throw new IllegalArgumentException("result header numeric semantics are invalid");
            }
        }

        private static void validateJsonText(final Object value, final int depth) {
            if (depth > MAX_SEMANTIC_DEPTH) {
                throw new IllegalArgumentException("semantic validation depth exceeded");
            }
            if (value instanceof String text) {
                requireSafeText(text, NuggetAdminContract.MAX_TEXT_UTF8_BYTES, false);
                return;
            }
            if (value instanceof Map<?, ?> rawMap) {
                Map<String, Object> object = stringObjectMap(rawMap);
                for (Map.Entry<String, Object> entry : object.entrySet()) {
                    requireSafeText(entry.getKey(), NuggetAdminContract.MAX_TOKEN_UTF8_BYTES, true);
                    validateJsonText(entry.getValue(), depth + 1);
                }
                return;
            }
            if (value instanceof List<?> values) {
                for (Object item : values) {
                    validateJsonText(item, depth + 1);
                }
            }
        }

        private static void validateMetadataContainers(final Object value, final int depth) {
            if (depth > MAX_SEMANTIC_DEPTH) {
                throw new IllegalArgumentException("semantic validation depth exceeded");
            }
            if (value instanceof Map<?, ?> rawMap) {
                Map<String, Object> object = stringObjectMap(rawMap);
                for (Map.Entry<String, Object> entry : object.entrySet()) {
                    if (entry.getValue() instanceof Map<?, ?> nested
                            && ("metadata".equals(entry.getKey())
                            || "details".equals(entry.getKey())
                            || "capabilities".equals(entry.getKey()))) {
                        validateMetadata(stringObjectMap(nested));
                    }
                    validateMetadataContainers(entry.getValue(), depth + 1);
                }
                return;
            }
            if (value instanceof List<?> values) {
                for (Object item : values) {
                    validateMetadataContainers(item, depth + 1);
                }
            }
        }

        private static void validateMetadata(final Map<String, Object> metadata) {
            if (metadata.size() > NuggetAdminContract.MAX_METADATA_ENTRIES) {
                throw new IllegalArgumentException("too many metadata entries");
            }
            int aggregateBytes = 0;
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                if (!(entry.getValue() instanceof String value)) {
                    throw new IllegalArgumentException("metadata values must be strings");
                }
                int keyBytes = requireSafeText(entry.getKey(), NuggetAdminContract.MAX_METADATA_KEY_UTF8_BYTES, true);
                int valueBytes = requireSafeText(value, NuggetAdminContract.MAX_METADATA_VALUE_UTF8_BYTES, false);
                if (sensitive(entry.getKey()) || sensitive(value) || pathLike(entry.getKey()) || pathLike(value)) {
                    throw new IllegalArgumentException("metadata contains sensitive text");
                }
                aggregateBytes += keyBytes + valueBytes;
                if (aggregateBytes > NuggetAdminContract.MAX_METADATA_AGGREGATE_UTF8_BYTES) {
                    throw new IllegalArgumentException("metadata aggregate exceeds contract bounds");
                }
            }
        }

        private static void validateQueriesAndPages(final Object value, final int depth) {
            if (depth > MAX_SEMANTIC_DEPTH) {
                throw new IllegalArgumentException("semantic validation depth exceeded");
            }
            if (value instanceof Map<?, ?> rawMap) {
                Map<String, Object> object = stringObjectMap(rawMap);
                if (object.containsKey("offset") && object.containsKey("limit")) {
                    validateOffsetAndLimit(object);
                }
                if (object.containsKey("returned") && object.containsKey("totalAvailable")) {
                    long returned = number(object, "returned");
                    long totalAvailable = number(object, "totalAvailable");
                    if (returned > totalAvailable) {
                        throw new IllegalArgumentException("returned count exceeds total available");
                    }
                    Object records = object.get("records");
                    if (records instanceof List<?> values && returned != values.size()) {
                        throw new IllegalArgumentException("returned count does not match records size");
                    }
                    Object items = object.get("items");
                    if (items instanceof List<?> values && returned != values.size()) {
                        throw new IllegalArgumentException("returned count does not match item size");
                    }
                }
                for (Object nested : object.values()) {
                    validateQueriesAndPages(nested, depth + 1);
                }
                return;
            }
            if (value instanceof List<?> values) {
                for (Object nested : values) {
                    validateQueriesAndPages(nested, depth + 1);
                }
            }
        }

        private static void validateOffsetAndLimit(final Map<String, Object> object) {
            long offset = number(object, "offset");
            long limit = number(object, "limit");
            if (offset < 0L || offset > NuggetAdminContract.MAX_OFFSET
                    || limit < 1L || limit > NuggetAdminContract.MAX_PAGE_LIMIT) {
                throw new IllegalArgumentException("query page bounds are invalid");
            }
            try {
                Math.addExact(offset, limit);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("query offset plus limit overflows", exception);
            }
            if (object.containsKey("returned")) {
                long returned = number(object, "returned");
                if (returned > limit) {
                    throw new IllegalArgumentException("returned count exceeds page limit");
                }
            }
        }

        private static String text(final Map<String, Object> object, final String key) {
            Object value = object.get(key);
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("expected string field: " + key);
            }
            return text;
        }

        private static boolean flag(final Map<String, Object> object, final String key) {
            Object value = object.get(key);
            if (!(value instanceof Boolean flag)) {
                throw new IllegalArgumentException("expected boolean field: " + key);
            }
            return flag.booleanValue();
        }

        private static long number(final Map<String, Object> object, final String key) {
            Object value = object.get(key);
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException("expected numeric field: " + key);
            }
            BigInteger integer = JsonSchemaValidator.integerValue(number);
            if (integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
                    || integer.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0) {
                throw new IllegalArgumentException("numeric field outside long range: " + key);
            }
            return integer.longValueExact();
        }

        private static void requireEquals(final Object left, final Object right, final String field) {
            if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
                if (!JsonSchemaValidator.integerValue(leftNumber).equals(JsonSchemaValidator.integerValue(rightNumber))) {
                    throw new IllegalArgumentException("duplicated field mismatch: " + field);
                }
                return;
            }
            if (left == null ? right != null : !left.equals(right)) {
                throw new IllegalArgumentException("duplicated field mismatch: " + field);
            }
        }

        private static int requireSafeText(
                final String value,
                final int maxBytes,
                final boolean tokenOnly) {
            int bytes = 0;
            for (int index = 0; index < value.length();) {
                char current = value.charAt(index);
                if (Character.isLowSurrogate(current)
                        || (Character.isHighSurrogate(current)
                        && (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))))) {
                    throw new IllegalArgumentException("malformed surrogate in safe text");
                }
                int codePoint = value.codePointAt(index);
                if (tokenOnly && !safeTokenCodePoint(codePoint)) {
                    throw new IllegalArgumentException("unsafe token code point");
                }
                if (!tokenOnly && !safeTextCodePoint(codePoint)) {
                    throw new IllegalArgumentException("unsafe text code point");
                }
                bytes += utf8Bytes(codePoint);
                if (bytes > maxBytes) {
                    throw new IllegalArgumentException("safe text exceeds UTF-8 byte budget");
                }
                index += Character.charCount(codePoint);
            }
            return bytes;
        }

        private static boolean safeTokenCodePoint(final int codePoint) {
            return (codePoint >= 'a' && codePoint <= 'z')
                    || (codePoint >= 'A' && codePoint <= 'Z')
                    || (codePoint >= '0' && codePoint <= '9')
                    || codePoint == '.'
                    || codePoint == '_'
                    || codePoint == '-'
                    || codePoint == ':'
                    || codePoint == '/';
        }

        private static boolean safeTextCodePoint(final int codePoint) {
            return codePoint >= 0x20
                    && codePoint != 0x7F
                    && codePoint != '<'
                    && codePoint != '>'
                    && !bidirectionalControl(codePoint);
        }

        private static boolean bidirectionalControl(final int codePoint) {
            return (codePoint >= 0x202A && codePoint <= 0x202E)
                    || (codePoint >= 0x2066 && codePoint <= 0x2069);
        }

        private static int utf8Bytes(final int codePoint) {
            if (codePoint <= 0x7F) {
                return 1;
            }
            if (codePoint <= 0x7FF) {
                return 2;
            }
            if (codePoint <= 0xFFFF) {
                return 3;
            }
            return 4;
        }

        private static boolean sensitive(final String text) {
            String normalized = normalized(text);
            return normalized.contains("pin")
                    || normalized.contains("admission")
                    || normalized.contains("credential")
                    || normalized.contains("password")
                    || normalized.contains("passwd")
                    || normalized.contains("secret")
                    || normalized.contains("token")
                    || normalized.contains("bearer")
                    || normalized.contains("cookie")
                    || normalized.contains("session")
                    || normalized.contains("private")
                    || normalized.contains("stack")
                    || normalized.contains("exception")
                    || normalized.contains("throwable")
                    || normalized.contains("payload")
                    || normalized.contains("configuration")
                    || normalized.contains("challenge")
                    || normalized.contains("oauth")
                    || normalized.contains("path")
                    || normalized.contains("manifest")
                    || normalized.contains("certificate")
                    || normalized.contains("signature")
                    || normalized.contains("key")
                    || normalized.contains("implementation");
        }

        private static String normalized(final String text) {
            StringBuilder builder = new StringBuilder(text.length());
            for (int index = 0; index < text.length(); index++) {
                char value = text.charAt(index);
                if (value >= 'A' && value <= 'Z') {
                    builder.append((char) (value + ('a' - 'A')));
                } else if ((value >= 'a' && value <= 'z') || (value >= '0' && value <= '9')) {
                    builder.append(value);
                }
            }
            return builder.toString();
        }

        private static boolean pathLike(final String text) {
            return text.indexOf('/') >= 0
                    || text.indexOf('\\') >= 0
                    || (text.length() >= 3
                    && ((text.charAt(0) >= 'A' && text.charAt(0) <= 'Z')
                    || (text.charAt(0) >= 'a' && text.charAt(0) <= 'z'))
                    && text.charAt(1) == ':'
                    && (text.charAt(2) == '\\' || text.charAt(2) == '/'));
        }
    }

    private static final class JsonSchemaValidator {
        private static final int MAX_SCHEMA_DEPTH = 96;
        private final Map<String, Object> root;

        private JsonSchemaValidator(final Map<String, Object> root) {
            this.root = Map.copyOf(root);
        }

        private void validate(final Object instance) {
            validateAgainst(this.root, instance, 0);
        }

        private void reject(final Object instance) {
            assertThrows(RuntimeException.class, () -> validate(instance));
        }

        private void validateAgainst(final Object schemaNode, final Object instance, final int depth) {
            if (depth > MAX_SCHEMA_DEPTH) {
                throw new IllegalArgumentException("schema reference depth exceeded");
            }
            if (schemaNode instanceof Boolean booleanSchema) {
                if (!booleanSchema.booleanValue()) {
                    throw new IllegalArgumentException("false schema rejected instance");
                }
                return;
            }
            Map<String, Object> schema = stringObjectMap(asMap(schemaNode));
            Object ref = schema.get("$ref");
            if (ref instanceof String reference) {
                validateAgainst(resolve(reference), instance, depth + 1);
            }
            validateCombinators(schema, instance, depth);
            validateType(schema.get("type"), instance);
            validateConst(schema.get("const"), instance);
            validateEnum(schema.get("enum"), instance);
            validateRequired(schema.get("required"), instance);
            validateProperties(schema, instance, depth);
            validateStringBounds(schema, instance);
            validateArrayBounds(schema, instance, depth);
            validateNumericBounds(schema, instance);
            validateObjectBounds(schema, instance, depth);
        }

        private void validateCombinators(
                final Map<String, Object> schema,
                final Object instance,
                final int depth) {
            Object allOf = schema.get("allOf");
            if (allOf instanceof List<?> allSchemas) {
                for (Object nested : allSchemas) {
                    validateAgainst(nested, instance, depth + 1);
                }
            }
            Object oneOf = schema.get("oneOf");
            if (oneOf instanceof List<?> oneSchemas) {
                int matches = 0;
                for (Object nested : oneSchemas) {
                    if (matches(nested, instance, depth + 1)) {
                        matches++;
                    }
                }
                if (matches != 1) {
                    throw new IllegalArgumentException("oneOf matched " + matches + " schemas");
                }
            }
            Object anyOf = schema.get("anyOf");
            if (anyOf instanceof List<?> anySchemas) {
                boolean matched = false;
                for (Object nested : anySchemas) {
                    if (matches(nested, instance, depth + 1)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    throw new IllegalArgumentException("anyOf matched no schemas");
                }
            }
            Object not = schema.get("not");
            if (not != null && matches(not, instance, depth + 1)) {
                throw new IllegalArgumentException("not schema matched instance");
            }
        }

        private boolean matches(final Object schemaNode, final Object instance, final int depth) {
            try {
                validateAgainst(schemaNode, instance, depth);
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        }

        private Object resolve(final String reference) {
            if (!reference.startsWith("#/")) {
                throw new IllegalArgumentException("only local schema references are supported");
            }
            Object current = this.root;
            int start = 2;
            while (start <= reference.length()) {
                int slash = reference.indexOf('/', start);
                String rawToken = slash < 0 ? reference.substring(start) : reference.substring(start, slash);
                String token = rawToken.replace("~1", "/").replace("~0", "~");
                if (!(current instanceof Map<?, ?> currentMap)) {
                    throw new IllegalArgumentException("schema reference does not resolve to an object");
                }
                current = currentMap.get(token);
                if (current == null) {
                    throw new IllegalArgumentException("schema reference target is missing");
                }
                if (slash < 0) {
                    return current;
                }
                start = slash + 1;
            }
            return current;
        }

        private static void validateType(final Object typeNode, final Object instance) {
            if (typeNode == null) {
                return;
            }
            if (typeNode instanceof String type) {
                if (!typeMatches(type, instance)) {
                    throw new IllegalArgumentException("schema type mismatch");
                }
                return;
            }
            if (typeNode instanceof List<?> types) {
                for (Object candidate : types) {
                    if (candidate instanceof String type && typeMatches(type, instance)) {
                        return;
                    }
                }
                throw new IllegalArgumentException("schema union type mismatch");
            }
        }

        private static boolean typeMatches(final String type, final Object instance) {
            return switch (type) {
                case "object" -> instance instanceof Map<?, ?>;
                case "array" -> instance instanceof List<?>;
                case "string" -> instance instanceof String;
                case "integer" -> integer(instance);
                case "number" -> instance instanceof Number;
                case "boolean" -> instance instanceof Boolean;
                case "null" -> instance == null;
                default -> throw new IllegalArgumentException("unsupported schema type");
            };
        }

        private static boolean integer(final Object instance) {
            if (instance instanceof Byte || instance instanceof Short || instance instanceof Integer
                    || instance instanceof Long) {
                return true;
            }
            if (instance instanceof Double value) {
                return Math.rint(value.doubleValue()) == value.doubleValue();
            }
            if (instance instanceof Float value) {
                return Math.rint(value.doubleValue()) == value.doubleValue();
            }
            return false;
        }

        private static void validateConst(final Object constValue, final Object instance) {
            if (constValue != null && !jsonEquals(constValue, instance)) {
                throw new IllegalArgumentException("schema const mismatch");
            }
        }

        private static void validateEnum(final Object enumNode, final Object instance) {
            if (!(enumNode instanceof List<?> values)) {
                return;
            }
            for (Object value : values) {
                if (jsonEquals(value, instance)) {
                    return;
                }
            }
            throw new IllegalArgumentException("schema enum mismatch");
        }

        private static boolean jsonEquals(final Object expected, final Object actual) {
            if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
                return Double.compare(expectedNumber.doubleValue(), actualNumber.doubleValue()) == 0;
            }
            return expected == null ? actual == null : expected.equals(actual);
        }

        private static void validateRequired(final Object requiredNode, final Object instance) {
            if (!(requiredNode instanceof List<?> required) || !(instance instanceof Map<?, ?> object)) {
                return;
            }
            for (Object name : required) {
                if (!(name instanceof String property) || !object.containsKey(property)) {
                    throw new IllegalArgumentException("required schema property missing");
                }
            }
        }

        private void validateProperties(
                final Map<String, Object> schema,
                final Object instance,
                final int depth) {
            if (!(instance instanceof Map<?, ?> rawObject)) {
                return;
            }
            Map<String, Object> object = stringObjectMap(rawObject);
            Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?> rawProperties
                    ? stringObjectMap(rawProperties) : Map.of();
            Object additionalProperties = schema.get("additionalProperties");
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                Object propertySchema = properties.get(entry.getKey());
                if (propertySchema != null) {
                    validateAgainst(propertySchema, entry.getValue(), depth + 1);
                } else if (Boolean.FALSE.equals(additionalProperties)) {
                    throw new IllegalArgumentException("additional schema property rejected");
                } else if (additionalProperties instanceof Map<?, ?> additionalSchema) {
                    validateAgainst(additionalSchema, entry.getValue(), depth + 1);
                }
            }
        }

        private static void validateStringBounds(final Map<String, Object> schema, final Object instance) {
            if (!(instance instanceof String value)) {
                return;
            }
            Object minLength = schema.get("minLength");
            if (minLength instanceof Number minimum && value.length() < minimum.intValue()) {
                throw new IllegalArgumentException("string shorter than schema minimum");
            }
            Object maxLength = schema.get("maxLength");
            if (maxLength instanceof Number maximum && value.length() > maximum.intValue()) {
                throw new IllegalArgumentException("string longer than schema maximum");
            }
            Object pattern = schema.get("pattern");
            if (pattern instanceof String regex && !Pattern.compile(regex).matcher(value).find()) {
                throw new IllegalArgumentException("string does not match schema pattern");
            }
        }

        private void validateArrayBounds(
                final Map<String, Object> schema,
                final Object instance,
                final int depth) {
            if (!(instance instanceof List<?> values)) {
                return;
            }
            Object minItems = schema.get("minItems");
            if (minItems instanceof Number minimum && values.size() < minimum.intValue()) {
                throw new IllegalArgumentException("array shorter than schema minimum");
            }
            Object maxItems = schema.get("maxItems");
            if (maxItems instanceof Number maximum && values.size() > maximum.intValue()) {
                throw new IllegalArgumentException("array longer than schema maximum");
            }
            Object items = schema.get("items");
            if (items != null) {
                for (Object value : values) {
                    validateAgainst(items, value, depth + 1);
                }
            }
        }

        private static void validateNumericBounds(final Map<String, Object> schema, final Object instance) {
            if (!(instance instanceof Number value)) {
                return;
            }
            Object minimum = schema.get("minimum");
            if (minimum instanceof Number min) {
                if (integer(value) && integer(min)) {
                    if (integerValue(value).compareTo(integerValue(min)) < 0) {
                        throw new IllegalArgumentException("number below schema minimum");
                    }
                } else if (Double.compare(value.doubleValue(), min.doubleValue()) < 0) {
                    throw new IllegalArgumentException("number below schema minimum");
                }
            }
            Object maximum = schema.get("maximum");
            if (maximum instanceof Number max) {
                if (integer(value) && integer(max)) {
                    if (integerValue(value).compareTo(integerValue(max)) > 0) {
                        throw new IllegalArgumentException("number above schema maximum");
                    }
                } else if (Double.compare(value.doubleValue(), max.doubleValue()) > 0) {
                    throw new IllegalArgumentException("number above schema maximum");
                }
            }
        }

        static BigInteger integerValue(final Number value) {
            if (value instanceof BigInteger bigInteger) {
                return bigInteger;
            }
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return BigInteger.valueOf(value.longValue());
            }
            if (value instanceof Double || value instanceof Float) {
                return BigDecimal.valueOf(value.doubleValue()).toBigIntegerExact();
            }
            throw new IllegalArgumentException("unsupported integer number type");
        }

        private void validateObjectBounds(
                final Map<String, Object> schema,
                final Object instance,
                final int depth) {
            if (!(instance instanceof Map<?, ?> rawObject)) {
                return;
            }
            Map<String, Object> object = stringObjectMap(rawObject);
            Object maxProperties = schema.get("maxProperties");
            if (maxProperties instanceof Number maximum && object.size() > maximum.intValue()) {
                throw new IllegalArgumentException("object exceeds schema property maximum");
            }
            Object propertyNames = schema.get("propertyNames");
            if (propertyNames != null) {
                for (String key : object.keySet()) {
                    validateAgainst(propertyNames, key, depth + 1);
                }
            }
        }
    }

    private static final class JsonParser {
        private final String text;
        private int offset;

        private JsonParser(final String text) {
            this.text = text;
        }

        private static Object parse(final String text) {
            JsonParser parser = new JsonParser(text);
            Object value = parser.value();
            parser.whitespace();
            if (parser.offset != parser.text.length()) {
                throw new IllegalArgumentException("trailing JSON data");
            }
            return value;
        }

        private Object value() {
            whitespace();
            char current = this.text.charAt(this.offset);
            if (current == '{') {
                return object();
            }
            if (current == '[') {
                return array();
            }
            if (current == '"') {
                return string();
            }
            if (current == 't') {
                literal("true");
                return Boolean.TRUE;
            }
            if (current == 'f') {
                literal("false");
                return Boolean.FALSE;
            }
            if (current == 'n') {
                literal("null");
                return null;
            }
            return number();
        }

        private Map<String, Object> object() {
            expect('{');
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            whitespace();
            if (peek('}')) {
                expect('}');
                return values;
            }
            while (true) {
                String key = string();
                whitespace();
                expect(':');
                values.put(key, value());
                whitespace();
                if (peek('}')) {
                    expect('}');
                    return values;
                }
                expect(',');
            }
        }

        private List<Object> array() {
            expect('[');
            ArrayList<Object> values = new ArrayList<>();
            whitespace();
            if (peek(']')) {
                expect(']');
                return values;
            }
            while (true) {
                values.add(value());
                whitespace();
                if (peek(']')) {
                    expect(']');
                    return values;
                }
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (this.offset < this.text.length()) {
                char current = this.text.charAt(this.offset++);
                if (current == '"') {
                    return builder.toString();
                }
                if (current == '\\') {
                    char escaped = this.text.charAt(this.offset++);
                    if (escaped == '"' || escaped == '\\' || escaped == '/') {
                        builder.append(escaped);
                    } else if (escaped == 'b') {
                        builder.append('\b');
                    } else if (escaped == 'f') {
                        builder.append('\f');
                    } else if (escaped == 'n') {
                        builder.append('\n');
                    } else if (escaped == 'r') {
                        builder.append('\r');
                    } else if (escaped == 't') {
                        builder.append('\t');
                    } else if (escaped == 'u') {
                        String hex = this.text.substring(this.offset, this.offset + 4);
                        builder.append((char) Integer.parseInt(hex, 16));
                        this.offset += 4;
                    } else {
                        throw new IllegalArgumentException("invalid JSON escape");
                    }
                } else {
                    builder.append(current);
                }
            }
            throw new IllegalArgumentException("unterminated JSON string");
        }

        private Number number() {
            int start = this.offset;
            while (this.offset < this.text.length()) {
                char current = this.text.charAt(this.offset);
                if ((current >= '0' && current <= '9') || current == '-' || current == '+' || current == '.'
                        || current == 'e' || current == 'E') {
                    this.offset++;
                } else {
                    break;
                }
            }
            String number = this.text.substring(start, this.offset);
            if (number.contains(".") || number.contains("e") || number.contains("E")) {
                return Double.valueOf(number);
            }
            return Long.valueOf(number);
        }

        private void literal(final String literal) {
            if (!this.text.startsWith(literal, this.offset)) {
                throw new IllegalArgumentException("invalid JSON literal");
            }
            this.offset += literal.length();
        }

        private boolean peek(final char expected) {
            return this.offset < this.text.length() && this.text.charAt(this.offset) == expected;
        }

        private void expect(final char expected) {
            whitespace();
            if (this.offset >= this.text.length() || this.text.charAt(this.offset) != expected) {
                throw new IllegalArgumentException("expected JSON character");
            }
            this.offset++;
        }

        private void whitespace() {
            while (this.offset < this.text.length()) {
                char current = this.text.charAt(this.offset);
                if (current == ' ' || current == '\n' || current == '\r' || current == '\t') {
                    this.offset++;
                } else {
                    return;
                }
            }
        }
    }

    private static final class HostileMap extends AbstractMap<String, String> {
        @Override
        public Set<Entry<String, String>> entrySet() {
            throw new IllegalStateException("provider-secret-sentinel");
        }
    }

    private static final class SizeHostileMap extends AbstractMap<String, String> {
        private final LinkedHashMap<String, String> values;

        private SizeHostileMap(final Map<String, String> values) {
            this.values = new LinkedHashMap<>(values);
        }

        @Override
        public Set<Entry<String, String>> entrySet() {
            return this.values.entrySet();
        }

        @Override
        public boolean isEmpty() {
            throw new IllegalStateException("provider-secret-sentinel");
        }

        @Override
        public int size() {
            throw new IllegalStateException("provider-secret-sentinel");
        }
    }

    private static final class HostileList<E> extends AbstractList<E> {
        @Override
        public Iterator<E> iterator() {
            throw new IllegalStateException("provider-secret-sentinel");
        }

        @Override
        public E get(final int index) {
            throw new IllegalStateException("provider-secret-sentinel");
        }

        @Override
        public int size() {
            throw new IllegalStateException("provider-secret-sentinel");
        }
    }

    private static final class LyingList<E> extends AbstractList<E> {
        private final List<E> values;

        private LyingList(final List<E> values) {
            this.values = values;
        }

        @Override
        public Iterator<E> iterator() {
            return this.values.iterator();
        }

        @Override
        public E get(final int index) {
            return this.values.get(index);
        }

        @Override
        public int size() {
            throw new IllegalStateException("provider-secret-sentinel");
        }
    }

    private static final class HostileNumber extends Number {
        @Override
        public int intValue() {
            throw new IllegalStateException("provider-secret-sentinel");
        }

        @Override
        public long longValue() {
            throw new IllegalStateException("provider-secret-sentinel");
        }

        @Override
        public float floatValue() {
            throw new IllegalStateException("provider-secret-sentinel");
        }

        @Override
        public double doubleValue() {
            throw new IllegalStateException("provider-secret-sentinel");
        }
    }

    private static class DefaultAdminService implements NuggetAdminService {
        @Override
        public NuggetStatus status() {
            return null;
        }

        @Override
        public NuggetAuthenticationResult authenticate(final NuggetAuthenticationRequest request, final char[] pin) {
            return null;
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.Overview> overview(final NuggetIntrospection.Request request) {
            return null;
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.EnvironmentSummary> environment(final NuggetIntrospection.Request request) {
            return null;
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.DiskVolumeSummary>> disk(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.DiskQuery query) {
            return null;
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.HealthSummary> health(final NuggetIntrospection.Request request) {
            return null;
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.ReadinessSummary> readiness(final NuggetIntrospection.Request request) {
            return null;
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.RuntimeSummary> runtime(final NuggetIntrospection.Request request) {
            return null;
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.TransportSummary>> transports(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.Query query) {
            return null;
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.RegistrySummary> registry(final NuggetIntrospection.Request request) {
            return null;
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.RepositorySummary> repository(final NuggetIntrospection.Request request) {
            return null;
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ApplicationSummary>> applications(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.Query query) {
            return null;
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.ApplicationDetail> application(
                final NuggetIntrospection.Request request,
                final String applicationInstanceId) {
            return null;
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary>> objects(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.Query query) {
            return null;
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.ObjectDetail> object(
                final NuggetIntrospection.Request request,
                final String objectId) {
            return null;
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.MetricsSummary> metrics(final NuggetIntrospection.Request request) {
            return null;
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry>> recentErrors(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.RecentErrorQuery query) {
            return null;
        }

        @Override
        public NuggetDiscovery.Result discover(final NuggetDiscovery.Query query) {
            return null;
        }

        @Override
        public NuggetDiscovery.Resolution resolve(final NuggetDiscovery.Selector selector) {
            return null;
        }

        @Override
        public NuggetDiscovery.StatusSummary discoveryStatus() {
            return null;
        }

        @Override
        public void close() {
        }
    }

    private static final class CountingAdminService extends DefaultAdminService {
        private int commandCount;

        @Override
        public NuggetAdminCommandResult<?> adminCommand(final NuggetAdminCommandRequest<?> request) {
            this.commandCount++;
            return super.adminCommand(request);
        }

        private int commandCount() {
            return this.commandCount;
        }
    }
}
