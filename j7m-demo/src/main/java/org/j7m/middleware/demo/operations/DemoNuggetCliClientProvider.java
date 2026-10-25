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

package org.j7m.middleware.demo.operations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.RemoteError;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.deployer.cli.NuggetCliClient;
import org.j7m.middleware.deployer.cli.NuggetCliClientProvider;
import org.j7m.middleware.deployer.cli.NuggetCliConnection;
import org.j7m.middleware.deployer.cli.NuggetCliConnectionSecurity;
import org.j7m.middleware.operations.api.NuggetAdminState;
import org.j7m.middleware.operations.api.NuggetAuthenticationFailureReason;
import org.j7m.middleware.operations.api.NuggetAuthenticationOutcome;
import org.j7m.middleware.operations.api.NuggetAuthenticationRequest;
import org.j7m.middleware.operations.api.NuggetAuthenticationResult;
import org.j7m.middleware.operations.api.NuggetConfigurationSummary;
import org.j7m.middleware.operations.api.NuggetControl;
import org.j7m.middleware.operations.api.NuggetDiscovery;
import org.j7m.middleware.operations.api.NuggetIdentity;
import org.j7m.middleware.operations.api.NuggetIntrospection;
import org.j7m.middleware.operations.api.NuggetPinSourceType;
import org.j7m.middleware.operations.api.NuggetState;
import org.j7m.middleware.operations.api.NuggetStatus;

/**
 * Demo-only nugget CLI client provider used by {@link NuggetOperationsDemo}.
 *
 * <p>The provider is registered only in the demo module. It does not bind a
 * network endpoint, does not perform remote administration, and must not be
 * used as a production transport. Its purpose is to exercise the public CLI
 * provider seam with deterministic redacted inspection data.</p>
 */
public final class DemoNuggetCliClientProvider implements NuggetCliClientProvider {
    /**
     * Creates a demo provider.
     */
    public DemoNuggetCliClientProvider() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String providerId() {
        return "j7m-demo-nugget-cli";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NuggetCliClient createClient() {
        // J7M teaching: the demo exercises the public CLI provider seam with redacted inspection DTOs, not runtime internals.
        return new DemoClient();
    }

    private static final class DemoClient implements NuggetCliClient {
        private static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");
        private final NuggetDiscovery.Record record;

        private DemoClient() {
            this.record = new NuggetDiscovery.Record(
                    "demo-nugget",
                    "phase7-demo",
                    "phase7-demo-runtime",
                    NuggetDiscovery.EndpointDescriptor.local("demo-local"),
                    1,
                    1,
                    NuggetDiscovery.Compatibility.COMPATIBLE,
                    List.of("READ_INFO", "READ_OBJECTS", "READ_APPLICATIONS",
                            "DRAIN_NUGGET", "SHUTDOWN_NUGGET"),
                    "123",
                    "LOCAL",
                    NOW,
                    NOW,
                    NOW.plus(Duration.ofMinutes(5L)),
                    NuggetDiscovery.Source.LOCAL_RECORD,
                    NuggetDiscovery.Status.ACTIVE,
                    List.of());
        }

        @Override
        public NuggetDiscovery.Result discover(final NuggetDiscovery.Query query) {
            return new NuggetDiscovery.Result(List.of(this.record), 1, 1, NOW, false, List.of());
        }

        @Override
        public NuggetDiscovery.Resolution resolve(final NuggetDiscovery.Selector selector) {
            NuggetDiscovery.Selector safeSelector = selector == null
                    ? new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.ALL, "", "demo") : selector;
            boolean resolved = safeSelector.type() == NuggetDiscovery.SelectorType.ALL
                    || (safeSelector.type() == NuggetDiscovery.SelectorType.EXACT_NAME
                    && this.record.nuggetName().equals(safeSelector.value()))
                    || (safeSelector.type() == NuggetDiscovery.SelectorType.EXACT_ID
                    && this.record.nuggetId().equals(safeSelector.value()));
            return new NuggetDiscovery.Resolution(
                    resolved ? NuggetDiscovery.ResolutionStatus.RESOLVED : NuggetDiscovery.ResolutionStatus.NOT_FOUND,
                    resolved ? List.of(this.record) : List.of(),
                    resolved ? "resolved" : "not-found",
                    resolved ? null : MiddlewareErrorCode.NOT_FOUND,
                    NOW,
                    List.of());
        }

        @Override
        public NuggetCliConnection connect(final NuggetDiscovery.Record selected) {
            return new DemoConnection(this.record);
        }

        @Override
        public void close() {
            // demo client owns no resources
        }
    }

    private static final class DemoConnection implements NuggetCliConnection {
        private final NuggetDiscovery.Record record;

        private DemoConnection(final NuggetDiscovery.Record record) {
            this.record = record;
        }

        @Override
        public NuggetCliConnectionSecurity security() {
            return NuggetCliConnectionSecurity.trustedLocal();
        }

        @Override
        public NuggetAuthenticationResult authenticate(final NuggetAuthenticationRequest request, final char[] pin) {
            boolean accepted = pin != null && pin.length == 6;
            return new NuggetAuthenticationResult(
                    accepted ? NuggetAuthenticationOutcome.ACCEPTED : NuggetAuthenticationOutcome.REJECTED,
                    accepted ? NuggetAuthenticationFailureReason.NONE : NuggetAuthenticationFailureReason.REJECTED,
                    this.record.nuggetId(),
                    request == null ? "" : request.correlationId(),
                    accepted ? "demo-admission" : "",
                    Duration.ZERO,
                    DemoClient.NOW,
                    accepted ? null : new RemoteError(MiddlewareErrorCode.UNAUTHENTICATED, "PIN not accepted",
                            Map.of(), "", this.record.runtimeId(), false, false),
                    Map.of());
        }

        @Override
        public NuggetStatus status(final String admissionId) {
            return status();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.Overview> overview(final NuggetIntrospection.Request request) {
            NuggetIntrospection.HealthSummary health = new NuggetIntrospection.HealthSummary(
                    NuggetIntrospection.HealthState.HEALTHY, List.of(), DemoClient.NOW, false);
            NuggetIntrospection.ReadinessSummary readiness = new NuggetIntrospection.ReadinessSummary(
                    NuggetIntrospection.ReadinessState.READY, List.of(), DemoClient.NOW, false);
            NuggetIntrospection.RuntimeSummary runtime = new NuggetIntrospection.RuntimeSummary(
                    this.record.runtimeId(), this.record.nuggetId(), NuggetState.RUNNING, DemoClient.NOW,
                    Duration.ofSeconds(5L), 2, 1, 1, 12L, 1L, 1, Map.of("mode", "DEV"), DemoClient.NOW);
            return result(NuggetIntrospection.Category.OVERVIEW,
                    new NuggetIntrospection.Overview(status(), runtime, health, readiness, false, List.of()));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.EnvironmentSummary> environment(final NuggetIntrospection.Request request) {
            return result(NuggetIntrospection.Category.ENVIRONMENT, new NuggetIntrospection.EnvironmentSummary(
                    "25", "OpenJDK", "J7M Demo VM", "Linux", "6", "amd64", "123", DemoClient.NOW,
                    Duration.ofSeconds(5L), 4, 1024L, 2048L, 4096L, 512L, 1024L, 8, 2, 9, 12L,
                    200, 300L, List.of(), 0.1D, 0.2D, 100L, "<redacted>", List.of("PATH"),
                    List.of(new NuggetIntrospection.KeyValue("-Xmx", "<redacted>")), Map.of(), DemoClient.NOW));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.DiskVolumeSummary>> disk(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.DiskQuery query) {
            List<NuggetIntrospection.DiskVolumeSummary> values = List.of(new NuggetIntrospection.DiskVolumeSummary(
                    "repository:demo", "repository", List.of("repository"), "<redacted>",
                    1000L, 500L, 500L, 500L, 50.0D, NuggetIntrospection.DiskHealth.HEALTHY, false,
                    NuggetIntrospection.Availability.AVAILABLE, DemoClient.NOW, "", Map.of()));
            return result(NuggetIntrospection.Category.DISK, collection(values, query.page()));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.HealthSummary> health(final NuggetIntrospection.Request request) {
            return result(NuggetIntrospection.Category.HEALTH, new NuggetIntrospection.HealthSummary(
                    NuggetIntrospection.HealthState.HEALTHY, List.of(component("runtime")), DemoClient.NOW, false));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.ReadinessSummary> readiness(final NuggetIntrospection.Request request) {
            return result(NuggetIntrospection.Category.READINESS, new NuggetIntrospection.ReadinessSummary(
                    NuggetIntrospection.ReadinessState.READY, List.of(component("runtime")), DemoClient.NOW, false));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.RuntimeSummary> runtime(final NuggetIntrospection.Request request) {
            NuggetIntrospection.Result<NuggetIntrospection.Overview> overview = overview(request);
            return result(NuggetIntrospection.Category.RUNTIME, overview.value().runtime());
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ApplicationSummary>> applications(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.Query query) {
            return result(NuggetIntrospection.Category.APPLICATIONS, collection(applications(), query));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.ApplicationDetail> application(
                final NuggetIntrospection.Request request,
                final String applicationInstanceId) {
            return result(NuggetIntrospection.Category.APPLICATIONS, new NuggetIntrospection.ApplicationDetail(
                    applications().getFirst(), List.of(new NuggetIntrospection.ServiceSummary(
                    "orders", "com.example.Orders", "<redacted>")), List.of("com.example.orders"),
                    Map.of("inspection", "true"), "verified", "signed", "present", "present", Map.of()));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ObjectSummary>> objects(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.Query query) {
            return result(NuggetIntrospection.Category.OBJECTS, collection(objects(), query));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.ObjectDetail> object(
                final NuggetIntrospection.Request request,
                final String objectId) {
            return result(NuggetIntrospection.Category.OBJECTS, new NuggetIntrospection.ObjectDetail(
                    objects().getFirst(), Map.of("descriptor", "safe"), Map.of("annotation", "safe")));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.MetricsSummary> metrics(final NuggetIntrospection.Request request) {
            return result(NuggetIntrospection.Category.METRICS, new NuggetIntrospection.MetricsSummary(
                    List.of(new NuggetIntrospection.MetricEntry("runtime.invocations", "count", 12.0D, Map.of("component", "runtime"))),
                    DemoClient.NOW, false));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.RecentErrorEntry>> recentErrors(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.RecentErrorQuery query) {
            List<NuggetIntrospection.RecentErrorEntry> values = List.of(new NuggetIntrospection.RecentErrorEntry(
                    DemoClient.NOW, "demo", "runtime", "<redacted>", MiddlewareErrorCode.INTERNAL,
                    "safe demo failure", false, "", 1L, Map.of()));
            return result(NuggetIntrospection.Category.ERRORS, collection(values, query.page()));
        }

        @Override
        public NuggetControl.Result nuggetControl(final NuggetControl.Request request) {
            if (request.action() == NuggetControl.Action.KILL_NUGGET) {
                return controlResult(request, NuggetControl.Outcome.DISABLED,
                        "nugget control operation is disabled",
                        false,
                        false,
                        new RemoteError(MiddlewareErrorCode.PERMISSION_DENIED,
                                "nugget control operation is disabled",
                                Map.of("reason", "kill-disabled"),
                                request.correlationId(),
                                this.record.runtimeId(),
                                false,
                                false),
                        null);
            }
            if (request.action() == NuggetControl.Action.SHUTDOWN_NUGGET && request.confirmation().isBlank()) {
                NuggetControl.Confirmation confirmation = new NuggetControl.Confirmation(
                        request.operationId(),
                        "phase77-confirmation",
                        DemoClient.NOW.plus(Duration.ofMinutes(1L)),
                        request.action(),
                        this.record.nuggetId(),
                        this.record.runtimeId(),
                        request.targetGeneration(),
                        "runtime:j7m-cli",
                        NuggetState.RUNNING);
                return controlResult(request, NuggetControl.Outcome.CONFIRMATION_REQUIRED,
                        "confirmation required for nugget control operation",
                        false,
                        false,
                        null,
                        confirmation);
            }
            return controlResult(request,
                    request.action() == NuggetControl.Action.DRAIN_NUGGET
                            ? NuggetControl.Outcome.PARTIAL : NuggetControl.Outcome.ACCEPTED,
                    request.action() == NuggetControl.Action.DRAIN_NUGGET
                            ? "nugget is draining" : "nugget shutdown accepted",
                    true,
                    request.action() != NuggetControl.Action.DRAIN_NUGGET,
                    null,
                    null);
        }

        @Override
        public void close() {
            // demo connection owns no resources
        }

        private NuggetControl.Result controlResult(
                final NuggetControl.Request request,
                final NuggetControl.Outcome outcome,
                final String text,
                final boolean accepted,
                final boolean inProgress,
                final RemoteError error,
                final NuggetControl.Confirmation confirmation) {
            return new NuggetControl.Result(
                    request.operationId(),
                    request.correlationId(),
                    request.action(),
                    request.mode(),
                    !request.admissionId().isBlank(),
                    error == null || error.code() != MiddlewareErrorCode.PERMISSION_DENIED,
                    this.record.nuggetId(),
                    this.record.runtimeId(),
                    request.targetGeneration(),
                    NuggetState.RUNNING,
                    request.action() == NuggetControl.Action.DRAIN_NUGGET ? NuggetState.DRAINING : NuggetState.RUNNING,
                    request.action() == NuggetControl.Action.DRAIN_NUGGET ? NuggetState.DRAINING : NuggetState.STOPPING,
                    outcome,
                    text,
                    accepted,
                    inProgress,
                    !inProgress,
                    outcome == NuggetControl.Outcome.PARTIAL,
                    inProgress,
                    false,
                    confirmation,
                    outcome == NuggetControl.Outcome.PARTIAL ? List.of("full-work-drain-deferred") : List.of(),
                    error,
                    request.requestedAt(),
                    DemoClient.NOW,
                    Duration.ofMillis(1L),
                    error == null ? Map.of("result", outcome.name()) : Map.of("reason", "kill-disabled"));
        }

        private NuggetStatus status() {
            NuggetIdentity identity = new NuggetIdentity(this.record.nuggetId(), this.record.nuggetName(),
                    new RuntimeIdentity(this.record.runtimeId(), "demo", Map.of()), DemoClient.NOW, "123", "LOCAL",
                    "0.7.5", "25", NuggetState.RUNNING, Map.of("READ_INFO", "1"), Map.of());
            NuggetConfigurationSummary configuration = new NuggetConfigurationSummary(
                    NuggetAdminState.ENABLED, true, false, NuggetPinSourceType.PROGRAMMATIC_PROVIDER,
                    false, "j7m-pin-v1", Map.of());
            return new NuggetStatus(identity, configuration, DemoClient.NOW, Map.of());
        }

        private static List<NuggetIntrospection.ApplicationSummary> applications() {
            return List.of(new NuggetIntrospection.ApplicationSummary("orders", "com.example:orders:1.0.0",
                    "orders-1", "Orders", "1.0.0", "RUNNING", "activation-orders", 1,
                    NuggetIntrospection.HealthState.HEALTHY, NuggetIntrospection.ReadinessState.READY, "", Map.of()));
        }

        private static List<NuggetIntrospection.ObjectSummary> objects() {
            return List.of(new NuggetIntrospection.ObjectSummary("demo-nugget", "orders-object",
                    "orders/orders-object", "orders", "service", NuggetIntrospection.ObjectVisibility.EXPORTED,
                    "EXECUTED", "GLOBAL", "orders", List.of("com.example.Orders"), "default", true,
                    "<redacted>", "local", NuggetIntrospection.HealthState.HEALTHY,
                    NuggetIntrospection.ReadinessState.READY, DemoClient.NOW.plus(Duration.ofMinutes(5L)).toEpochMilli(),
                    1L, 12L, 1L, Instant.EPOCH, "", false, Map.of()));
        }

        private static NuggetIntrospection.ComponentSummary component(final String name) {
            return new NuggetIntrospection.ComponentSummary(name, NuggetIntrospection.Availability.AVAILABLE,
                    NuggetIntrospection.HealthState.HEALTHY, NuggetIntrospection.ReadinessState.READY, true, "ok", Map.of());
        }

        private static <T> NuggetIntrospection.CollectionResult<T> collection(
                final List<T> values,
                final NuggetIntrospection.Query query) {
            NuggetIntrospection.Query safeQuery = query == null ? NuggetIntrospection.Query.all() : query;
            int from = Math.min(safeQuery.offset(), values.size());
            int to = Math.min(from + safeQuery.limit(), values.size());
            List<T> selected = values.subList(from, to);
            return new NuggetIntrospection.CollectionResult<>(selected, safeQuery.offset(), safeQuery.limit(),
                    selected.size(), values.size(), true, safeQuery.sortBy(), safeQuery.direction(),
                    safeQuery.filters(), DemoClient.NOW, false, Map.of());
        }

        private static <T> NuggetIntrospection.Result<T> result(
                final NuggetIntrospection.Category category,
                final T value) {
            return new NuggetIntrospection.Result<>(true, true, category, DemoClient.NOW, false,
                    value, null, List.of(), Map.of());
        }
    }
}
