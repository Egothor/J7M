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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.RemoteError;

/**
 * Headless nugget administration admission service.
 *
 * <p>This contract is transport-neutral and presentation-neutral. It does not
 * start a listener, parse CLI commands, or grant authorization. Implementations
 * are thread-safe unless their owning runtime has been closed.</p>
 */
public interface NuggetAdminService extends NuggetDiscoveryService, AutoCloseable {
    /**
     * Returns a safe status snapshot.
     *
     * @return current status
     */
    NuggetStatus status();

    /**
     * Returns the public Phase 7.8 administration contract capabilities for
     * this provider.
     *
     * <p>The result is presentation-neutral and does not grant admission or
     * authorization. Implementations that do not understand the public contract
     * must return a truthful unavailable result rather than fabricating support.
     * Unsupported request protocol versions are rejected by
     * {@link NuggetAdminRequestContext} construction before this method can be
     * invoked with such a context.</p>
     *
     * @param context request context
     * @return safe capability result
     */
    default NuggetAdminCommandResult<NuggetAdminCapabilities> adminCapabilities(
            final NuggetAdminRequestContext context) {
        final RemoteError error = new RemoteError(
                MiddlewareErrorCode.UNAVAILABLE,
                "nugget admin public contract capabilities are unavailable from this service",
                Map.of("reason", "admin-contract-default-unavailable"),
                context == null ? "" : context.correlationId(),
                context == null || context.target() == null ? "" : context.target().runtimeId(),
                false,
                false);
        final NuggetAdminResultHeader header = NuggetAdminResultHeader.unavailable(context, "", error);
        return new NuggetAdminCommandResult<>(
                header,
                NuggetAdminCapabilities.unavailable(),
                NuggetAdminCapabilities.PAYLOAD_TYPE);
    }

    /**
     * Evaluates an optional shared command envelope around existing typed
     * public operation families.
     *
     * <p>This method is an adapter surface for clients that need one public
     * envelope shape for JSON or future transport bindings. It must not replace
     * the typed methods on this interface, must not accept command-line text,
     * free-form method names, or arbitrary JSON object maps, and must not
     * fabricate success for providers that do not implement the requested typed
     * family. The default implementation returns a truthful unavailable result
     * without performing admission, authorization, discovery, introspection,
     * lifecycle, control, audit, or provider work.</p>
     *
     * @param request typed command envelope
     * @return safe unavailable command result
     */
    default NuggetAdminCommandResult<?> adminCommand(final NuggetAdminCommandRequest<?> request) {
        final NuggetAdminRequestContext context = request == null ? null : request.context();
        final RemoteError error = new RemoteError(
                MiddlewareErrorCode.UNAVAILABLE,
                "nugget admin public command envelope is unavailable from this service",
                Map.of("reason", "admin-command-default-unavailable"),
                context == null ? "" : context.correlationId(),
                context == null || context.target() == null ? "" : context.target().runtimeId(),
                false,
                false);
        return new NuggetAdminCommandResult<>(
                NuggetAdminResultHeader.unavailable(context, "", error),
                null,
                "");
    }

    /**
     * Attempts PIN admission using separate shortest-scope credential material.
     *
     * <p>The supplied {@code pin} is not retained by the service after the
     * method returns. Callers remain responsible for clearing their original
     * buffer after the call.</p>
     *
     * @param request request metadata
     * @param pin clear PIN characters
     * @return safe admission result
     */
    NuggetAuthenticationResult authenticate(NuggetAuthenticationRequest request, char[] pin);

    /**
     * Returns a read-only overview after PIN admission and authorization.
     *
     * @param request introspection request metadata
     * @return safe overview result
     */
    NuggetIntrospection.Result<NuggetIntrospection.Overview> overview(NuggetIntrospection.Request request);

    /**
     * Returns safe JVM and process environment information.
     *
     * @param request introspection request metadata
     * @return safe environment result
     */
    NuggetIntrospection.Result<NuggetIntrospection.EnvironmentSummary> environment(NuggetIntrospection.Request request);

    /**
     * Returns safe disk and storage-root summaries.
     *
     * @param request introspection request metadata
     * @param query disk query
     * @return safe disk collection result
     */
    NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.DiskVolumeSummary>> disk(
            NuggetIntrospection.Request request,
            NuggetIntrospection.DiskQuery query);

    /**
     * Returns read-only health state.
     *
     * @param request introspection request metadata
     * @return safe health result
     */
    NuggetIntrospection.Result<NuggetIntrospection.HealthSummary> health(NuggetIntrospection.Request request);

    /**
     * Returns read-only readiness state.
     *
     * @param request introspection request metadata
     * @return safe readiness result
     */
    NuggetIntrospection.Result<NuggetIntrospection.ReadinessSummary> readiness(NuggetIntrospection.Request request);

    /**
     * Returns safe runtime status projection.
     *
     * @param request introspection request metadata
     * @return safe runtime result
     */
    NuggetIntrospection.Result<NuggetIntrospection.RuntimeSummary> runtime(NuggetIntrospection.Request request);

    /**
     * Returns safe transport summaries.
     *
     * @param request introspection request metadata
     * @param query collection query
     * @return safe transport collection result
     */
    NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.TransportSummary>> transports(
            NuggetIntrospection.Request request,
            NuggetIntrospection.Query query);

    /**
     * Returns a safe registry summary.
     *
     * @param request introspection request metadata
     * @return safe registry result
     */
    NuggetIntrospection.Result<NuggetIntrospection.RegistrySummary> registry(NuggetIntrospection.Request request);

    /**
     * Returns a safe application repository summary.
     *
     * @param request introspection request metadata
     * @return safe repository result
     */
    NuggetIntrospection.Result<NuggetIntrospection.RepositorySummary> repository(NuggetIntrospection.Request request);

    /**
     * Returns safe application summaries.
     *
     * @param request introspection request metadata
     * @param query collection query
     * @return safe application collection result
     */
    NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ApplicationSummary>> applications(
            NuggetIntrospection.Request request,
            NuggetIntrospection.Query query);

    /**
     * Returns safe application detail, or a safe not-found result.
     *
     * @param request introspection request metadata
     * @param applicationInstanceId application instance id
     * @return safe application detail result
     */
    NuggetIntrospection.Result<NuggetIntrospection.ApplicationDetail> application(
            NuggetIntrospection.Request request,
            String applicationInstanceId);

    /**
     * Returns safe object/service summaries.
     *
     * @param request introspection request metadata
     * @param query collection query
     * @return safe object collection result
     */
    NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary>> objects(
            NuggetIntrospection.Request request,
            NuggetIntrospection.Query query);

    /**
     * Returns safe object/service detail, or a safe not-found result.
     *
     * @param request introspection request metadata
     * @param objectId object id
     * @return safe object detail result
     */
    NuggetIntrospection.Result<NuggetIntrospection.ObjectDetail> object(
            NuggetIntrospection.Request request,
            String objectId);

    /**
     * Returns bounded safe metrics.
     *
     * @param request introspection request metadata
     * @return safe metrics result
     */
    NuggetIntrospection.Result<NuggetIntrospection.MetricsSummary> metrics(NuggetIntrospection.Request request);

    /**
     * Returns bounded safe recent errors.
     *
     * @param request introspection request metadata
     * @param query recent-error query
     * @return safe recent-error collection result
     */
    NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry>> recentErrors(
            NuggetIntrospection.Request request,
            NuggetIntrospection.RecentErrorQuery query);

    /**
     * Executes or plans an application lifecycle operation after admission,
     * authorization, server-side feature gates, deadline checks, and redaction.
     *
     * <p>This method is presentation-neutral. Implementations must delegate to
     * the configured public application deployer contract and must not create
     * CLI-local lifecycle state or bypass repository verification,
     * authorization, resource policy, lifecycle state checks, audit, or
     * application-platform cleanup diagnostics. PIN admission is only an
     * admission gate; it is not authorization.</p>
     *
     * @param request lifecycle request metadata
     * @return safe lifecycle result
     *
     * <p>The default implementation preserves source compatibility for
     * implementations compiled against earlier versions of this interface. It
     * performs no PIN admission, authorization, provider lookup, planning,
     * activation, drain, stop, unload, audit mutation, or other application
     * lifecycle work. It returns a bounded safe
     * {@link NuggetApplicationLifecycle.Outcome#UNAVAILABLE} result with a
     * normalized {@link MiddlewareErrorCode#UNAVAILABLE} error so callers can
     * distinguish an older or non-lifecycle implementation from a successful
     * lifecycle operation.</p>
     */
    default NuggetApplicationLifecycle.Result applicationLifecycle(
            final NuggetApplicationLifecycle.Request request) {
        final Instant requestedAt;
        final String correlationId;
        final NuggetApplicationLifecycle.Action action;
        final boolean dryRun;
        if (request == null) {
            requestedAt = Instant.EPOCH;
            correlationId = "";
            action = NuggetApplicationLifecycle.Action.START;
            dryRun = false;
        } else {
            requestedAt = request.requestedAt();
            correlationId = request.correlationId();
            action = request.action();
            dryRun = request.dryRun();
        }
        final RemoteError error = new RemoteError(
                MiddlewareErrorCode.UNAVAILABLE,
                "application lifecycle operations are unavailable from this nugget admin service",
                Map.of("reason", "application-lifecycle-default-unavailable"),
                correlationId,
                "",
                false,
                false);
        return new NuggetApplicationLifecycle.Result(
                "",
                correlationId,
                action,
                false,
                false,
                dryRun,
                "",
                "",
                request == null ? null : request.coordinate(),
                request == null ? null : request.instanceId(),
                null,
                null,
                null,
                NuggetApplicationLifecycle.Outcome.UNAVAILABLE,
                "application lifecycle operations are unavailable from this nugget admin service",
                false,
                false,
                true,
                false,
                false,
                "",
                "not-authorized-because-lifecycle-is-unavailable",
                null,
                null,
                null,
                List.of("application-lifecycle-unavailable"),
                error,
                requestedAt,
                requestedAt,
                Duration.ZERO,
                Map.of("reason", "application-lifecycle-default-unavailable"));
    }

    /**
     * Executes or plans a nugget-level drain, shutdown, or self-termination
     * operation after admission, authorization, exact target validation,
     * confirmation, server-side feature gates, deadline checks, and redaction.
     *
     * <p>This method is presentation-neutral. Implementations must operate only
     * on the owning nugget subject and must not provide arbitrary operating
     * system process control, shell execution, process-id selection, or direct
     * JVM termination primitives as production behavior. PIN admission is only
     * an admission gate; it is not authorization. Confirmation challenges are
     * credentials and must remain redacted outside the direct
     * confirmation-required response.</p>
     *
     * @param request nugget-control request metadata
     * @return safe nugget-control result
     *
     * <p>The default implementation preserves source compatibility for
     * implementations compiled against earlier versions of this interface. It
     * performs no PIN admission, authorization, confirmation, drain, runtime
     * shutdown, termination, audit mutation, or other nugget-control work. It
     * returns a bounded safe {@link NuggetControl.Outcome#UNAVAILABLE} result
     * with a normalized {@link MiddlewareErrorCode#UNAVAILABLE} error.</p>
     */
    default NuggetControl.Result nuggetControl(final NuggetControl.Request request) {
        final Instant requestedAt;
        final String correlationId;
        final NuggetControl.Action action;
        final NuggetControl.Mode mode;
        if (request == null) {
            requestedAt = Instant.EPOCH;
            correlationId = "";
            action = NuggetControl.Action.DRAIN_NUGGET;
            mode = NuggetControl.Mode.GRACEFUL;
        } else {
            requestedAt = request.requestedAt();
            correlationId = request.correlationId();
            action = request.action();
            mode = request.mode();
        }
        final RemoteError error = new RemoteError(
                MiddlewareErrorCode.UNAVAILABLE,
                "nugget control operations are unavailable from this nugget admin service",
                Map.of("reason", "nugget-control-default-unavailable"),
                correlationId,
                request == null ? "" : request.targetRuntimeId(),
                false,
                false);
        return new NuggetControl.Result(
                request == null ? "" : request.operationId(),
                correlationId,
                action,
                mode,
                false,
                false,
                request == null ? "" : request.targetNuggetId(),
                request == null ? "" : request.targetRuntimeId(),
                request == null ? 0L : request.targetGeneration(),
                NuggetState.UNKNOWN,
                NuggetState.UNKNOWN,
                NuggetState.UNKNOWN,
                NuggetControl.Outcome.UNAVAILABLE,
                "nugget control operations are unavailable from this nugget admin service",
                false,
                false,
                true,
                false,
                true,
                false,
                null,
                List.of("nugget-control-unavailable"),
                error,
                requestedAt,
                requestedAt,
                Duration.ZERO,
                Map.of("reason", "nugget-control-default-unavailable"));
    }

    /**
     * Stops accepting new admission requests and releases owned secret material
     * where practical.
     */
    @Override
    void close();
}
