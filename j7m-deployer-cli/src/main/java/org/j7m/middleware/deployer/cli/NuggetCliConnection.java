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

package org.j7m.middleware.deployer.cli;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.operations.api.NuggetAdminCapabilities;
import org.j7m.middleware.operations.api.NuggetAdminCommandRequest;
import org.j7m.middleware.operations.api.NuggetAdminCommandResult;
import org.j7m.middleware.operations.api.NuggetAdminRequestContext;
import org.j7m.middleware.operations.api.NuggetAdminResultHeader;
import org.j7m.middleware.operations.api.NuggetApplicationLifecycle;
import org.j7m.middleware.operations.api.NuggetAuthenticationRequest;
import org.j7m.middleware.operations.api.NuggetAuthenticationResult;
import org.j7m.middleware.operations.api.NuggetControl;
import org.j7m.middleware.operations.api.NuggetIntrospection;
import org.j7m.middleware.operations.api.NuggetState;
import org.j7m.middleware.operations.api.NuggetStatus;

/**
 * Transport-neutral connection from the J7M CLI to one selected nugget.
 *
 * <p>The connection is owned by the CLI session that created it and must be
 * closed by that session. Implementations must report trusted connection
 * security through {@link #security()} before any PIN is submitted. Discovery
 * endpoint syntax, source classification, or object-reference possession is not
 * sufficient to make credential submission safe.</p>
 */
public interface NuggetCliConnection extends AutoCloseable {
    /**
     * Returns the trusted security descriptor for this established connection.
     *
     * @return connection security descriptor
     */
    NuggetCliConnectionSecurity security();

    /**
     * Performs PIN admission using shortest-scope mutable credential material.
     *
     * @param request authentication request metadata
     * @param pin mutable PIN characters; implementations must not retain the
     *        array
     * @return authentication result
     */
    NuggetAuthenticationResult authenticate(NuggetAuthenticationRequest request, char[] pin);

    /**
     * Returns public Phase 7.8 contract capabilities for this connection.
     *
     * <p>The default implementation is intentionally unavailable so older
     * providers do not appear to support the public command contract. The
     * result is not admission or authorization.</p>
     *
     * @param context request context
     * @return safe capability result
     */
    default NuggetAdminCommandResult<NuggetAdminCapabilities> adminCapabilities(
            final NuggetAdminRequestContext context) {
        RemoteError error = unsupportedError();
        return new NuggetAdminCommandResult<>(
                NuggetAdminResultHeader.unavailable(context, "", error),
                NuggetAdminCapabilities.unavailable(),
                NuggetAdminCapabilities.PAYLOAD_TYPE);
    }

    /**
     * Evaluates an optional shared command envelope around existing typed
     * public operation families.
     *
     * <p>The default implementation performs no operation work and returns
     * truthful unavailable. CLI parsing remains owned by the CLI; this method is
     * only a public envelope adapter for providers that explicitly implement
     * it.</p>
     *
     * @param request typed command envelope
     * @return safe unavailable result
     */
    default NuggetAdminCommandResult<?> adminCommand(final NuggetAdminCommandRequest<?> request) {
        NuggetAdminRequestContext context = request == null ? null : request.context();
        RemoteError error = unsupportedError();
        return new NuggetAdminCommandResult<>(
                NuggetAdminResultHeader.unavailable(context, "", error),
                null,
                "");
    }

    /**
     * Reads compact status after successful admission and server authorization.
     *
     * @param admissionId admission identifier
     * @return status summary
     */
    NuggetStatus status(String admissionId);

    /**
     * Reads compact overview after successful admission and server
     * authorization.
     *
     * @param request introspection request
     * @return overview result
     */
    NuggetIntrospection.Result<NuggetIntrospection.Overview> overview(NuggetIntrospection.Request request);

    /**
     * Reads safe JVM, process, and environment-name information after
     * successful admission and server authorization.
     *
     * @param request introspection request
     * @return environment result
     */
    default NuggetIntrospection.Result<NuggetIntrospection.EnvironmentSummary> environment(
            final NuggetIntrospection.Request request) {
        return unsupported(NuggetIntrospection.Category.ENVIRONMENT);
    }

    /**
     * Reads safe disk and configured storage-root summaries after successful
     * admission and server authorization.
     *
     * @param request introspection request
     * @param query disk query
     * @return disk result
     */
    default NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.DiskVolumeSummary>> disk(
            final NuggetIntrospection.Request request,
            final NuggetIntrospection.DiskQuery query) {
        NuggetIntrospection.Query safeQuery = query == null ? NuggetIntrospection.Query.all() : query.page();
        return unsupportedCollection(NuggetIntrospection.Category.DISK, safeQuery);
    }

    /**
     * Reads the health summary after successful admission and server
     * authorization.
     *
     * @param request introspection request
     * @return health result
     */
    default NuggetIntrospection.Result<NuggetIntrospection.HealthSummary> health(
            final NuggetIntrospection.Request request) {
        return unsupported(NuggetIntrospection.Category.HEALTH);
    }

    /**
     * Reads the readiness summary after successful admission and server
     * authorization.
     *
     * @param request introspection request
     * @return readiness result
     */
    default NuggetIntrospection.Result<NuggetIntrospection.ReadinessSummary> readiness(
            final NuggetIntrospection.Request request) {
        return unsupported(NuggetIntrospection.Category.READINESS);
    }

    /**
     * Reads the runtime summary after successful admission and server
     * authorization.
     *
     * @param request introspection request
     * @return runtime result
     */
    default NuggetIntrospection.Result<NuggetIntrospection.RuntimeSummary> runtime(
            final NuggetIntrospection.Request request) {
        return unsupported(NuggetIntrospection.Category.RUNTIME);
    }

    /**
     * Reads safe application summaries after successful admission and server
     * authorization.
     *
     * @param request introspection request
     * @param query collection query
     * @return application collection result
     */
    default NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ApplicationSummary>> applications(
            final NuggetIntrospection.Request request,
            final NuggetIntrospection.Query query) {
        return unsupportedCollection(NuggetIntrospection.Category.APPLICATIONS, query);
    }

    /**
     * Reads safe application detail after successful admission and server
     * authorization.
     *
     * @param request introspection request
     * @param applicationInstanceId application instance identifier
     * @return application detail result
     */
    default NuggetIntrospection.Result<NuggetIntrospection.ApplicationDetail> application(
            final NuggetIntrospection.Request request,
            final String applicationInstanceId) {
        return unsupported(NuggetIntrospection.Category.APPLICATIONS);
    }

    /**
     * Reads safe object summaries after successful admission and server
     * authorization.
     *
     * @param request introspection request
     * @param query collection query
     * @return object collection result
     */
    default NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary>> objects(
            final NuggetIntrospection.Request request,
            final NuggetIntrospection.Query query) {
        return unsupportedCollection(NuggetIntrospection.Category.OBJECTS, query);
    }

    /**
     * Reads safe object detail after successful admission and server
     * authorization.
     *
     * @param request introspection request
     * @param objectId object identifier
     * @return object detail result
     */
    default NuggetIntrospection.Result<NuggetIntrospection.ObjectDetail> object(
            final NuggetIntrospection.Request request,
            final String objectId) {
        return unsupported(NuggetIntrospection.Category.OBJECTS);
    }

    /**
     * Reads bounded safe metrics after successful admission and server
     * authorization.
     *
     * @param request introspection request
     * @return metrics result
     */
    default NuggetIntrospection.Result<NuggetIntrospection.MetricsSummary> metrics(
            final NuggetIntrospection.Request request) {
        return unsupported(NuggetIntrospection.Category.METRICS);
    }

    /**
     * Reads bounded recent safe errors after successful admission and server
     * authorization.
     *
     * @param request introspection request
     * @param query recent-error query
     * @return recent-error result
     */
    default NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry>> recentErrors(
            final NuggetIntrospection.Request request,
            final NuggetIntrospection.RecentErrorQuery query) {
        NuggetIntrospection.Query safeQuery = query == null ? NuggetIntrospection.Query.all() : query.page();
        return unsupportedCollection(NuggetIntrospection.Category.ERRORS, safeQuery);
    }

    /**
     * Executes or plans an application lifecycle operation after successful
     * admission and server authorization.
     *
     * <p>The default implementation is intentionally unavailable so older
     * connection providers cannot accidentally report successful mutation for a
     * lifecycle operation they do not implement.</p>
     *
     * @param request lifecycle request
     * @return safe lifecycle result
     */
    default NuggetApplicationLifecycle.Result applicationLifecycle(
            final NuggetApplicationLifecycle.Request request) {
        Instant now = Instant.EPOCH;
        NuggetApplicationLifecycle.Action action = request == null
                ? NuggetApplicationLifecycle.Action.START : request.action();
        RemoteError error = unsupportedError();
        return new NuggetApplicationLifecycle.Result(
                "",
                request == null ? "" : request.correlationId(),
                action,
                request != null && !request.admissionId().isBlank(),
                true,
                request != null && request.dryRun(),
                "",
                "",
                request == null ? null : request.coordinate(),
                request == null ? null : request.instanceId(),
                null,
                null,
                null,
                NuggetApplicationLifecycle.Outcome.UNAVAILABLE,
                "application lifecycle method is not available from this provider",
                false,
                false,
                true,
                false,
                true,
                "",
                "",
                null,
                null,
                null,
                List.of("unavailable"),
                error,
                request == null ? now : request.requestedAt(),
                now,
                java.time.Duration.ZERO,
                Map.of("reason", "provider-unavailable"));
    }

    /**
     * Executes or plans a nugget-level drain, shutdown, or self-termination
     * operation after successful admission and server authorization.
     *
     * <p>The default implementation is intentionally unavailable so older
     * connection providers cannot accidentally report successful nugget control
     * for an operation they do not implement.</p>
     *
     * @param request nugget-control request
     * @return safe nugget-control result
     */
    default NuggetControl.Result nuggetControl(final NuggetControl.Request request) {
        Instant now = Instant.EPOCH;
        NuggetControl.Action action = request == null ? NuggetControl.Action.DRAIN_NUGGET : request.action();
        NuggetControl.Mode mode = request == null ? NuggetControl.Mode.GRACEFUL : request.mode();
        RemoteError error = unsupportedError();
        return new NuggetControl.Result(
                request == null ? "" : request.operationId(),
                request == null ? "" : request.correlationId(),
                action,
                mode,
                request != null && !request.admissionId().isBlank(),
                true,
                request == null ? "" : request.targetNuggetId(),
                request == null ? "" : request.targetRuntimeId(),
                request == null ? 0L : request.targetGeneration(),
                NuggetState.UNKNOWN,
                NuggetState.UNKNOWN,
                NuggetState.UNKNOWN,
                NuggetControl.Outcome.UNAVAILABLE,
                "nugget control method is not available from this provider",
                false,
                false,
                true,
                false,
                true,
                false,
                null,
                List.of("unavailable"),
                error,
                request == null ? now : request.requestedAt(),
                now,
                java.time.Duration.ZERO,
                Map.of("reason", "provider-unavailable"));
    }

    /**
     * Closes resources owned by the connection.
     */
    @Override
    void close();

    private static <T> NuggetIntrospection.Result<T> unsupported(
            final NuggetIntrospection.Category category) {
        return new NuggetIntrospection.Result<>(
                true,
                true,
                category,
                Instant.EPOCH,
                true,
                null,
                unsupportedError(),
                List.of(),
                Map.of());
    }

    private static <T> NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<T>> unsupportedCollection(
            final NuggetIntrospection.Category category,
            final NuggetIntrospection.Query query) {
        NuggetIntrospection.Query safeQuery = query == null ? NuggetIntrospection.Query.all() : query;
        NuggetIntrospection.CollectionResult<T> empty = new NuggetIntrospection.CollectionResult<>(
                List.of(),
                safeQuery.offset(),
                safeQuery.limit(),
                0,
                0,
                true,
                safeQuery.sortBy(),
                safeQuery.direction(),
                safeQuery.filters(),
                Instant.EPOCH,
                true,
                Map.of());
        return new NuggetIntrospection.Result<>(
                true,
                true,
                category,
                Instant.EPOCH,
                true,
                empty,
                unsupportedError(),
                List.of(),
                Map.of());
    }

    private static RemoteError unsupportedError() {
        return new RemoteError(
                MiddlewareErrorCode.UNAVAILABLE,
                "nugget inspection method is not available from this provider",
                Map.of(),
                "",
                "",
                false,
                false);
    }
}
