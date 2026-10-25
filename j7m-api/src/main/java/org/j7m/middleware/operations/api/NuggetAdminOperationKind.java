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

package org.j7m.middleware.operations.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Stable public operation families for the nugget administration contract.
 *
 * <p>The values identify typed operation families only. They are not method
 * names, CLI command strings, JSON dispatch keys, reflection targets, or a
 * universal router. Implementations must continue to use the existing typed
 * public families such as {@link NuggetIntrospection},
 * {@link NuggetApplicationLifecycle}, {@link NuggetControl}, and
 * {@link NuggetDiscovery} for operation semantics.</p>
 *
 * <p>Each operation kind owns the finite binding between the operation family
 * and the exact public request and result payload DTO classes that may appear
 * in {@link NuggetAdminCommandRequest} and
 * {@link NuggetAdminCommandResult}. The binding is intentionally closed: maps,
 * strings, arrays, collections, implementation classes, CLI-private models,
 * generic JSON nodes, request/result role swaps, and wrong-family DTOs are
 * invalid before provider dispatch. Payload type strings are stable public
 * schema tokens; callers may omit them and let the envelope infer the token
 * from the exact DTO class.</p>
 *
 * <p>The enum and its binding metadata are immutable and thread-safe.</p>
 */
public enum NuggetAdminOperationKind {
    /**
     * Capability discovery for this public contract.
     */
    CAPABILITIES(
            true,
            true,
            true,
            false,
            false,
            false,
            List.of(),
            List.of(new PayloadBinding(
                    NuggetAdminCapabilities.class,
                    NuggetAdminCapabilities.PAYLOAD_TYPE,
                    "defs.capabilitiesResult"))),
    /**
     * Nugget discovery and target resolution.
     */
    DISCOVERY(
            false,
            true,
            true,
            true,
            false,
            false,
            List.of(
                    new PayloadBinding(
                            NuggetDiscovery.Query.class,
                            "nugget-discovery-query",
                            "defs.discoveryQueryRequest"),
                    new PayloadBinding(
                            NuggetDiscovery.Selector.class,
                            "nugget-discovery-selector",
                            "defs.discoverySelectorRequest")),
            List.of(
                    new PayloadBinding(
                            NuggetDiscovery.Result.class,
                            "nugget-discovery-result",
                            "defs.discoveryResult"),
                    new PayloadBinding(
                            NuggetDiscovery.Resolution.class,
                            "nugget-discovery-resolution",
                            "defs.discoveryResolution"),
                    new PayloadBinding(
                            NuggetDiscovery.StatusSummary.class,
                            "nugget-discovery-status-summary",
                            "defs.discoveryStatusSummary"))),
    /**
     * PIN admission metadata and safe admission outcome.
     */
    AUTHENTICATION(
            false,
            true,
            false,
            false,
            false,
            false,
            List.of(new PayloadBinding(
                    NuggetAuthenticationRequest.class,
                    "nugget-authentication-request",
                    "defs.authenticationRequest")),
            List.of(new PayloadBinding(
                    NuggetAuthenticationResult.class,
                    "nugget-authentication-result",
                    "defs.authenticationResult"))),
    /**
     * Read-only nugget/runtime/object/application/environment inspection.
     */
    INTROSPECTION(
            false,
            true,
            true,
            true,
            false,
            false,
            List.of(
                    new PayloadBinding(
                            NuggetIntrospection.Request.class,
                            "nugget-introspection-request",
                            "defs.introspectionRequest"),
                    new PayloadBinding(
                            NuggetIntrospection.Query.class,
                            "nugget-introspection-query",
                            "defs.introspectionQuery"),
                    new PayloadBinding(
                            NuggetIntrospection.DiskQuery.class,
                            "nugget-introspection-disk-query",
                            "defs.introspectionDiskQuery"),
                    new PayloadBinding(
                            NuggetIntrospection.RecentErrorQuery.class,
                            "nugget-introspection-recent-error-query",
                            "defs.introspectionRecentErrorQuery"),
                    new PayloadBinding(
                            NuggetAdminQuery.class,
                            "nugget-admin-query",
                            "defs.adminQuery")),
            List.of(
                    new PayloadBinding(
                            NuggetStatus.class,
                            "nugget-status",
                            "defs.nuggetStatus"),
                    new PayloadBinding(
                            NuggetIntrospection.Result.class,
                            "nugget-introspection-result",
                            "defs.introspectionResult"))),
    /**
     * Application lifecycle operation through the typed lifecycle family.
     */
    APPLICATION_LIFECYCLE(
            false,
            true,
            false,
            false,
            false,
            true,
            List.of(new PayloadBinding(
                    NuggetApplicationLifecycle.Request.class,
                    "nugget-application-lifecycle-request",
                    "defs.applicationLifecycleRequest")),
            List.of(new PayloadBinding(
                    NuggetApplicationLifecycle.Result.class,
                    "nugget-application-lifecycle-result",
                    "defs.applicationLifecycleResult"))),
    /**
     * Nugget drain, shutdown, or kill-disabled control operation.
     */
    NUGGET_CONTROL(
            false,
            true,
            false,
            false,
            true,
            true,
            List.of(new PayloadBinding(
                    NuggetControl.Request.class,
                    "nugget-control-request",
                    "defs.nuggetControlRequest")),
            List.of(new PayloadBinding(
                    NuggetControl.Result.class,
                    "nugget-control-result",
                    "defs.nuggetControlResult")));

    private final boolean requestPayloadMayBeNull;
    private final boolean resultPayloadMayBeNullForUnavailable;
    private final boolean readOnly;
    private final boolean queryPermitted;
    private final boolean confirmationPermitted;
    private final boolean acceptedMutationOperationIdentityRequired;
    private final List<PayloadBinding> requestPayloads;
    private final List<PayloadBinding> resultPayloads;

    NuggetAdminOperationKind(
            final boolean requestPayloadMayBeNull,
            final boolean resultPayloadMayBeNullForUnavailable,
            final boolean readOnly,
            final boolean queryPermitted,
            final boolean confirmationPermitted,
            final boolean acceptedMutationOperationIdentityRequired,
            final List<PayloadBinding> requestPayloads,
            final List<PayloadBinding> resultPayloads) {
        this.requestPayloadMayBeNull = requestPayloadMayBeNull;
        this.resultPayloadMayBeNullForUnavailable = resultPayloadMayBeNullForUnavailable;
        this.readOnly = readOnly;
        this.queryPermitted = queryPermitted;
        this.confirmationPermitted = confirmationPermitted;
        this.acceptedMutationOperationIdentityRequired = acceptedMutationOperationIdentityRequired;
        this.requestPayloads = List.copyOf(requestPayloads);
        this.resultPayloads = List.copyOf(resultPayloads);
    }

    /**
     * Returns whether a command request envelope may omit its typed payload for
     * this operation kind.
     *
     * @return {@code true} when request payload {@code null} is a valid
     *         operation-family request
     */
    public boolean requestPayloadMayBeNull() {
        return this.requestPayloadMayBeNull;
    }

    /**
     * Returns whether a command result envelope may omit its typed payload when
     * the common result outcome is {@link NuggetAdminOutcome#UNAVAILABLE}.
     *
     * @return {@code true} when older-provider unavailable results may carry a
     *         {@code null} payload
     */
    public boolean resultPayloadMayBeNullForUnavailable() {
        return this.resultPayloadMayBeNullForUnavailable;
    }

    /**
     * Returns whether this operation family is read-only after admission and
     * authorization. Authentication is not read-only because it may update
     * admission throttles and short-lived admission sessions.
     *
     * @return read-only operation-family flag
     */
    public boolean readOnly() {
        return this.readOnly;
    }

    /**
     * Returns whether query DTOs are part of this operation family's public
     * request surface.
     *
     * @return query-permitted flag
     */
    public boolean queryPermitted() {
        return this.queryPermitted;
    }

    /**
     * Returns whether this operation family may carry confirmation semantics in
     * its typed DTOs. Confirmation challenges are sensitive and currently apply
     * only to nugget control.
     *
     * @return confirmation-permitted flag
     */
    public boolean confirmationPermitted() {
        return this.confirmationPermitted;
    }

    /**
     * Returns whether accepted or in-progress mutation results must carry a
     * stable operation id in the common header when that operation family
     * represents mutation identity.
     *
     * @return accepted-mutation operation identity flag
     */
    public boolean acceptedMutationOperationIdentityRequired() {
        return this.acceptedMutationOperationIdentityRequired;
    }

    /**
     * Returns the exact public DTO classes accepted as request payloads for
     * this operation kind.
     *
     * @return immutable request payload binding list
     */
    public List<PayloadBinding> requestPayloads() {
        return this.requestPayloads;
    }

    /**
     * Returns the exact public DTO classes accepted as result payloads for this
     * operation kind.
     *
     * @return immutable result payload binding list
     */
    public List<PayloadBinding> resultPayloads() {
        return this.resultPayloads;
    }

    /**
     * Validates and normalizes a command request payload type for this
     * operation family.
     *
     * @param payload request payload, or {@code null}
     * @param payloadType caller-supplied payload type token, or empty
     * @return canonical payload type token, or empty when the request has no
     *         payload
     * @throws IllegalArgumentException if the payload, null policy, or
     *         payload-type token is invalid
     */
    public String validateRequestPayload(final Object payload, final String payloadType) {
        return validateAndNormalizeRequestPayload(payload, payloadType).payloadType();
    }

    ValidatedPayload validateAndNormalizeRequestPayload(final Object payload, final String payloadType) {
        return validatePayload(payload, payloadType, this.requestPayloads, this.requestPayloadMayBeNull, "request");
    }

    /**
     * Validates and normalizes a command result payload type for this operation
     * family.
     *
     * @param header common result header
     * @param payload result payload, or {@code null}
     * @param payloadType caller-supplied payload type token, or empty
     * @return canonical payload type token, or empty when the result has no
     *         payload
     * @throws IllegalArgumentException if the header, payload, null policy,
     *         payload-type token, outcome flags, or mutation identity is invalid
     */
    public String validateResultPayload(
            final NuggetAdminResultHeader header,
            final Object payload,
            final String payloadType) {
        return validateAndNormalizeResultPayload(header, payload, payloadType).payloadType();
    }

    ValidatedPayload validateAndNormalizeResultPayload(
            final NuggetAdminResultHeader header,
            final Object payload,
            final String payloadType) {
        if (header == null) {
            throw new IllegalArgumentException("header must not be null");
        }
        if (header.operationKind() != this) {
            throw new IllegalArgumentException("invalid nugget admin command result operation kind");
        }
        validateHeaderOutcome(header);
        if (this.readOnly && (header.accepted()
                || header.inProgress()
                || header.outcomeUnknown()
                || header.outcome() == NuggetAdminOutcome.ACCEPTED
                || header.outcome() == NuggetAdminOutcome.IN_PROGRESS
                || header.outcome() == NuggetAdminOutcome.OUTCOME_UNKNOWN
                || !header.operationId().isBlank())) {
            throw new IllegalArgumentException("invalid nugget admin command result mutation state for read-only operation");
        }
        if (this.acceptedMutationOperationIdentityRequired
                && acceptedMutationMayExist(header)
                && payload != null
                && header.operationId().isBlank()) {
            throw new IllegalArgumentException("invalid nugget admin command result mutation operation id");
        }
        final boolean nullAllowed = header.outcome() == NuggetAdminOutcome.UNAVAILABLE
                && this.resultPayloadMayBeNullForUnavailable;
        return validatePayload(payload, payloadType, this.resultPayloads, nullAllowed, "result");
    }

    private ValidatedPayload validatePayload(
            final Object payload,
            final String payloadType,
            final List<PayloadBinding> bindings,
            final boolean nullAllowed,
            final String role) {
        if (payload == null) {
            if (!payloadType.isBlank()) {
                throw new IllegalArgumentException("invalid nugget admin command " + role + " payload type without payload");
            }
            if (!nullAllowed) {
                throw new IllegalArgumentException("invalid nugget admin command " + role + " payload for operation kind");
            }
            return new ValidatedPayload(null, "");
        }
        if (invalidGenericPayload(payload)) {
            throw new IllegalArgumentException("admin command " + role + " payload must be a typed public DTO");
        }
        final Class<?> payloadClass = payload.getClass();
        for (PayloadBinding binding : bindings) {
            if (binding.payloadClass() == payloadClass) {
                if (!payloadType.isBlank() && !binding.payloadType().equals(payloadType)) {
                    throw new IllegalArgumentException("invalid nugget admin command " + role + " payload type for operation kind");
                }
                if (!binding.publicContractValidationCovered()) {
                    throw new IllegalArgumentException("admin command " + role
                            + " payload is missing public contract validation coverage");
                }
                final Object normalized = AdminPayloadNormalizer.normalize(payload);
                return new ValidatedPayload(normalized, binding.payloadType());
            }
        }
        throw new IllegalArgumentException("invalid nugget admin command " + role + " payload for operation kind");
    }

    private static boolean invalidGenericPayload(final Object payload) {
        return payload instanceof CharSequence
                || payload instanceof Map<?, ?>
                || payload instanceof Collection<?>
                || payload.getClass().isArray();
    }

    private static boolean acceptedMutationMayExist(final NuggetAdminResultHeader header) {
        return header.accepted()
                || header.inProgress()
                || header.timedOut()
                || header.outcomeUnknown()
                || header.outcome() == NuggetAdminOutcome.ACCEPTED
                || header.outcome() == NuggetAdminOutcome.IN_PROGRESS
                || header.outcome() == NuggetAdminOutcome.TIMED_OUT
                || header.outcome() == NuggetAdminOutcome.OUTCOME_UNKNOWN;
    }

    private static void validateHeaderOutcome(final NuggetAdminResultHeader header) {
        switch (header.outcome()) {
            case ACCEPTED -> require(header.accepted(), "accepted");
            case IN_PROGRESS -> require(header.inProgress(), "in progress");
            case COMPLETED -> require(header.completed(), "completed");
            case DENIED -> require(header.denied(), "denied");
            case UNAVAILABLE -> require(header.unavailable(), "unavailable");
            case TIMED_OUT -> require(header.timedOut(), "timed out");
            case PARTIAL -> require(header.partial(), "partial");
            case OUTCOME_UNKNOWN -> require(header.outcomeUnknown(), "outcome unknown");
        }
    }

    private static void require(final boolean actual, final String expected) {
        if (!actual) {
            throw new IllegalArgumentException("invalid nugget admin command result outcome flags for " + expected);
        }
    }

    /**
     * Exact payload binding for one operation-family request or result DTO.
     *
     * <p>The binding uses exact class identity deliberately. It does not accept
     * subclasses, provider implementation DTOs, CLI-private models, maps,
     * arrays, collections, generic JSON nodes, or string command payloads. The
     * schema variant id is documentation/test metadata; production code does
     * not load or validate JSON Schema.</p>
     *
     * @param payloadClass exact public DTO class accepted by the binding
     * @param payloadType stable public payload type token used in command
     *        envelopes and JSON fixtures
     * @param schemaVariantId stable JSON Schema variant reference for
     *        documentation and conformance tests
     */
    public record PayloadBinding(Class<?> payloadClass, String payloadType, String schemaVariantId) {
        /**
         * Creates immutable binding metadata.
         */
        public PayloadBinding {
            if (payloadClass == null) {
                throw new IllegalArgumentException("payloadClass must not be null");
            }
            payloadType = AdminContractSupport.token(payloadType, "payloadType");
            schemaVariantId = AdminContractSupport.token(schemaVariantId, "schemaVariantId");
        }

        /**
         * Returns whether the exact payload class is covered by the Java public
         * contract normalizer used by command envelopes.
         *
         * <p>This method is intended for contract tests and future binding
         * reviews. A binding without normalizer coverage is not safe to admit
         * because type-correct DTO instances could retain data outside the
         * bounded JSON and semantic contract.</p>
         *
         * @return {@code true} when command envelopes can normalize this
         *         payload class before retaining it
         */
        public boolean publicContractValidationCovered() {
            return AdminPayloadNormalizer.supports(this.payloadClass);
        }
    }

    record ValidatedPayload(Object payload, String payloadType) {
    }
}
