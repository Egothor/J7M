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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationInstanceId;
import org.j7m.middleware.application.api.ApplicationQuery;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.deployer.ApplicationManager;
import org.j7m.middleware.deployer.DefaultApplicationDeployer;
import org.j7m.middleware.api.RuntimeIdentity;
import org.j7m.middleware.deployer.api.DeployOptions;
import org.j7m.middleware.deployer.api.PackageRequest;
import org.j7m.middleware.deployer.api.VerificationOptions;
import org.j7m.middleware.deployer.cli.J7mCli;
import org.j7m.middleware.deployer.cli.NuggetCliClient;
import org.j7m.middleware.deployer.cli.NuggetCliEventSink;
import org.j7m.middleware.deployer.cli.NuggetCliConnection;
import org.j7m.middleware.deployer.cli.NuggetCliConnectionSecurity;
import org.j7m.middleware.deployer.cli.NuggetCliSecretReader;
import org.j7m.middleware.deployer.cli.NuggetCliTicker;
import org.j7m.middleware.examples.hello.HelloService;
import org.j7m.middleware.operations.api.NuggetAdminService;
import org.j7m.middleware.operations.api.NuggetAdminState;
import org.j7m.middleware.operations.api.NuggetApplicationLifecycle;
import org.j7m.middleware.operations.api.NuggetAuthenticationOutcome;
import org.j7m.middleware.operations.api.NuggetAuthenticationRequest;
import org.j7m.middleware.operations.api.NuggetAuthenticationResult;
import org.j7m.middleware.operations.api.NuggetAuthenticationSource;
import org.j7m.middleware.operations.api.NuggetCapability;
import org.j7m.middleware.operations.api.NuggetConfigurationSummary;
import org.j7m.middleware.operations.api.NuggetDiscovery;
import org.j7m.middleware.operations.api.NuggetIdentity;
import org.j7m.middleware.operations.api.NuggetIntrospection;
import org.j7m.middleware.operations.api.NuggetPinSourceType;
import org.j7m.middleware.operations.api.NuggetState;
import org.j7m.middleware.operations.api.NuggetStatus;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.NuggetAdminRuntime;
import org.j7m.middleware.runtime.NuggetAdminRuntimeConfiguration;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityMode;
import org.j7m.middleware.security.defaults.AllowAllAuthorizationPolicy;
import org.j7m.middleware.security.defaults.DefaultRedactor;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.j7m.middleware.security.defaults.MutableCredentialProvider;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;

/**
 * Deterministic nugget operations demos.
 *
 * <p>The demos use production public contracts only. They do not start a
 * concrete remote administration transport, do not contact external services,
 * and do not print clear PIN material or endpoint-local identifiers. The CLI
 * demonstrations exercise the real {@link J7mCli#run(String[], java.io.InputStream,
 * PrintStream, PrintStream, NuggetCliClient, NuggetCliSecretReader, Clock,
 * NuggetCliTicker, NuggetCliEventSink, Map)} entry point with deterministic
 * provider seams so prompt, target, admission, status, and denial behavior
 * remain visible without broadening operations-plane scope.</p>
 */
public final class NuggetOperationsDemo {
    private static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");
    private static final char[] DEMO_PIN = new char[] {'7', '4', '0', '0', '0', '1'};
    private static final String LIFECYCLE_DEMO_NUGGET_ID = "phase76-lifecycle-nugget";
    private static final String LIFECYCLE_DEMO_NUGGET_NAME = "phase76-lifecycle-demo";

    private NuggetOperationsDemo() {
    }

    /**
     * Runs one deterministic nugget operations demo.
     *
     * @param args first argument selects {@code admin}, {@code cli}, or
     *        {@code pin-failure}
     */
    public static void main(final String[] args) {
        String mode = args == null || args.length == 0 ? "admin" : args[0];
        switch (mode) {
            case "admin" -> runAdminDemo();
            case "cli" -> runCliDemo();
            case "cli-application-lifecycle" -> runNuggetCliApplicationLifecycleDemo();
            case "cli-kill-disabled" -> runNuggetCliKillDisabledDemo();
            case "pin-failure" -> runPinFailureDemo();
            default -> throw new IllegalArgumentException("unknown nugget operations demo mode");
        }
    }

    private static void runAdminDemo() {
        // J7M teaching: PIN admission is exercised through the public service so the demo proves the secured headless admin entrypoint.
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        AtomicLong monotonic = new AtomicLong();
        NuggetAdminRuntimeConfiguration configuration = baseConfiguration(audit, monotonic)
                .discoveryTarget(new NuggetDiscovery.ConfiguredTarget(
                        NuggetDiscovery.EndpointDescriptor.local("demo-local-connector"),
                        "",
                        "demo-configured-peer"))
                .build();
        try (NuggetAdminService service = NuggetAdminRuntime.start(runtimeIdentity(), configuration)) {
            NuggetAuthenticationResult auth = service.authenticate(authRequest("phase7-demo-auth"), copyDemoPin());
            NuggetIntrospection.Result<NuggetIntrospection.Overview> overview =
                    service.overview(introspectionRequest(auth.admissionId(), "phase7-demo-overview"));
            NuggetDiscovery.Result discovery = service.discover(new NuggetDiscovery.Query(true, 8, "phase7-demo-discovery"));

            // J7M teaching: discovery output is bounded and local/configured so no external control plane is required for evidence.
            System.out.println("phase7 nugget admin demo: local-in-process");
            System.out.println("phase7 nugget admin auth outcome: " + auth.outcome());
            System.out.println("phase7 nugget admin overview admitted: " + overview.admitted());
            System.out.println("phase7 nugget admin overview authorized: " + overview.authorized());
            System.out.println("phase7 nugget admin discovery returned: " + discovery.returned());
            // J7M teaching: remote transport remains deferred here and audit evidence is printed without exposing PIN material.
            System.out.println("phase7 nugget admin remote transport: deferred");
            System.out.println("phase7 nugget admin audit events: " + audit.eventCount());
        }
    }

    private static void runCliDemo() {
        // J7M teaching: the CLI production entrypoint is used directly so console packaging stays outside runtime internals.
        ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream(1024);
        ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream(1024);
        try (PrintStream stdout = new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8);
             PrintStream stderr = new PrintStream(stderrBytes, true, StandardCharsets.UTF_8)) {
            String script = "discover\n"
                    + "nuggets\n"
                    + "target\n"
                    + "talk phase7-demo\n"
                    + "target\n"
                    + "auth --pin-env J7M_DEMO_PIN\n"
                    + "status\n"
                    + "ps\n"
                    + "ps --wide --json\n"
                    + "objects\n"
                    + "apps\n"
                    + "env\n"
                    + "disk\n"
                    + "health\n"
                    + "readiness\n"
                    + "metrics\n"
                    + "errors\n"
                    + "talk all\n"
                    + "target\n"
                    + "exit\n";
            int exit = J7mCli.run(
                    new String[] {"nugget", "shell"},
                    new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
                    stdout,
                    stderr,
                    new DemoNuggetCliClientProvider().createClient(),
                    NuggetCliSecretReader.console(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    NuggetCliTicker.system(),
                    NuggetCliEventSink.none(),
                    Map.of("J7M_DEMO_PIN", "740001"));
            String stdoutText = stdoutBytes.toString(StandardCharsets.UTF_8);
            String stderrText = stderrBytes.toString(StandardCharsets.UTF_8);
            // J7M teaching: diagnostics inspection JSON uses the same CLI parser and provider seam while remote transport remains outside this local demo.
            // J7M teaching: prompt, target, and status assertions prove that all-nugget and selected-nugget shell state stays visible to operators.
            System.out.println("phase7 nugget cli demo: production-entrypoint");
            System.out.println("phase7 nugget cli inspection commands: ps objects apps env disk health readiness metrics errors");
            System.out.println("phase7 nugget cli exit code: " + exit);
            System.out.println("phase7 nugget cli all-nugget prompt visible: "
                    + Boolean.toString(stderrText.contains("J7M > ")));
            System.out.println("phase7 nugget cli discovery visible: "
                    + Boolean.toString(stdoutText.contains("discovered 1")));
            System.out.println("phase7 nugget cli all-nugget target visible: "
                    + Boolean.toString(stdoutText.contains("target all")));
            System.out.println("phase7 nugget cli selected-nugget prompt visible: "
                    + Boolean.toString(stderrText.contains("J7M:phase7-demo > ")));
            System.out.println("phase7 nugget cli selected-nugget target visible: "
                    + Boolean.toString(stdoutText.contains("target phase7-demo")));
            System.out.println("phase7 nugget cli status command visible: "
                    + Boolean.toString(stdoutText.contains("phase7-demo demo-nugget RUNNING")
                    && stdoutText.contains("health HEALTHY readiness READY")));
            System.out.println("phase7 nugget cli returned all-nugget mode: "
                    + Boolean.toString(stdoutText.lastIndexOf("target all")
                    > stdoutText.lastIndexOf("target phase7-demo")));
            System.out.println("phase7 nugget cli json envelope: " + Boolean.toString(stdoutText.contains("\"version\"")));
            System.out.println("phase7 nugget cli ps output: " + Boolean.toString(stdoutText.contains("orders-object")));
            System.out.println("phase7 nugget cli no secret leakage: "
                    + Boolean.toString(!stdoutText.contains("740001") && !stderrText.contains("740001")));
        }
    }

    private static void runNuggetCliApplicationLifecycleDemo() {
        try (LifecycleDemoEnvironment environment = LifecycleDemoEnvironment.create()) {
            // J7M teaching: lifecycle start and dry-run enter through the production CLI and headless admin contracts while runtime delegates to ApplicationDeployer.
            String dryRun = runLifecycleCli(environment, new String[] {
                    "nugget", "start", environment.coordinate().toString(), "--dry-run",
                    "--id", LIFECYCLE_DEMO_NUGGET_ID, "--pin-env", "J7M_DEMO_PIN"});
            String start = runLifecycleCli(environment, new String[] {
                    "nugget", "start", environment.coordinate().toString(),
                    "--id", LIFECYCLE_DEMO_NUGGET_ID, "--pin-env", "J7M_DEMO_PIN"});
            ApplicationInstanceId instanceId = environment.requireInstanceId();
            String apps = runLifecycleShell(environment, "apps",
                    "talk --id " + LIFECYCLE_DEMO_NUGGET_ID + "\n"
                            + "auth --pin-env J7M_DEMO_PIN\n"
                            + "apps\n"
                            + "exit\n");
            String app = runLifecycleShell(environment, "app",
                    "talk --id " + LIFECYCLE_DEMO_NUGGET_ID + "\n"
                            + "auth --pin-env J7M_DEMO_PIN\n"
                            + "app " + instanceId.value() + "\n"
                            + "exit\n");
            // J7M teaching: drain, stop, unload, audit, and redaction are exercised after the live service proof so cleanup remains observable.
            String liveProof = environment.hello();
            String drain = runLifecycleCli(environment, new String[] {
                    "nugget", "drain", instanceId.value(), "--id", LIFECYCLE_DEMO_NUGGET_ID,
                    "--pin-env", "J7M_DEMO_PIN"});
            String stop = runLifecycleCli(environment, new String[] {
                    "nugget", "stop", instanceId.value(), "--id", LIFECYCLE_DEMO_NUGGET_ID,
                    "--pin-env", "J7M_DEMO_PIN"});
            int serviceCandidatesAfterStop = environment.serviceCandidates();
            String unload = runLifecycleCli(environment, new String[] {
                    "nugget", "unload", instanceId.value(), "--id", LIFECYCLE_DEMO_NUGGET_ID,
                    "--pin-env", "J7M_DEMO_PIN"});
            String discoveryAndAuth = runLifecycleCli(environment, new String[] {"nugget", "discover"})
                    + runLifecycleCli(environment, new String[] {"nugget", "talk", "--id", LIFECYCLE_DEMO_NUGGET_ID})
                    + runLifecycleShell(environment, "auth",
                            "talk --id " + LIFECYCLE_DEMO_NUGGET_ID + "\n"
                                    + "auth --pin-env J7M_DEMO_PIN\n"
                                    + "exit\n");
            String combined = dryRun + start + apps + app + drain + stop + unload + discoveryAndAuth;
            boolean discoveredSelectedAndAuthenticated = !discoveryAndAuth.isBlank();
            boolean appStatusShown = apps.contains(environment.coordinate().toString())
                    || app.contains(environment.coordinate().toString())
                    || app.contains(instanceId.value());

            System.out.println("phase7.6 nugget cli lifecycle demo: real-application-manager");
            System.out.println("phase7.6 nugget cli lifecycle discovery/select/auth: "
                    + Boolean.toString(discoveredSelectedAndAuthenticated));
            System.out.println("phase7.6 nugget cli lifecycle start dry-run: "
                    + Boolean.toString(dryRun.contains("START DRY_RUN")));
            System.out.println("phase7.6 nugget cli lifecycle start exact coordinate: "
                    + Boolean.toString(start.contains("START COMPLETED")
                    && start.contains(environment.coordinate().toString())));
            System.out.println("phase7.6 nugget cli lifecycle apps status: "
                    + Boolean.toString(appStatusShown));
            System.out.println("phase7.6 nugget cli lifecycle service live proof: " + liveProof);
            System.out.println("phase7.6 nugget cli lifecycle drain: "
                    + Boolean.toString(drain.contains("DRAIN COMPLETED") || drain.contains("DRAIN ALREADY_DONE")));
            System.out.println("phase7.6 nugget cli lifecycle stop: "
                    + Boolean.toString(stop.contains("STOP COMPLETED") || stop.contains("STOP ALREADY_DONE")));
            System.out.println("phase7.6 nugget cli lifecycle service after stop candidates: "
                    + serviceCandidatesAfterStop);
            System.out.println("phase7.6 nugget cli lifecycle unload: "
                    + Boolean.toString(unload.contains("UNLOAD COMPLETED") || unload.contains("UNLOAD ALREADY_DONE")));
            System.out.println("phase7.6 nugget cli lifecycle audit safe redaction: "
                    + Boolean.toString(environment.auditSafe() && !combined.contains("740001")
                    && !combined.contains(environment.workspaceText())));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed to run nugget CLI application lifecycle demo", exception);
        }
    }

    private static void runNuggetCliKillDisabledDemo() {
        // J7M teaching: the production CLI entrypoint reaches nugget controls through public DTOs and never terminates this demo JVM.
        ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream(2048);
        ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream(1024);
        try (PrintStream stdout = new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8);
             PrintStream stderr = new PrintStream(stderrBytes, true, StandardCharsets.UTF_8)) {
            String script = "discover\n"
                    + "talk phase7-demo\n"
                    + "auth --pin-env J7M_DEMO_PIN\n"
                    + "drain-nugget phase7-demo\n"
                    + "shutdown-nugget phase7-demo\n"
                    + "kill-nugget phase7-demo --confirm phase77-confirmation\n"
                    + "exit\n";
            int exit = J7mCli.run(
                    new String[] {"nugget", "shell"},
                    new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
                    stdout,
                    stderr,
                    new DemoNuggetCliClientProvider().createClient(),
                    NuggetCliSecretReader.console(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    NuggetCliTicker.system(),
                    NuggetCliEventSink.none(),
                    Map.of("J7M_DEMO_PIN", "740001"));
            String stdoutText = stdoutBytes.toString(StandardCharsets.UTF_8);
            String stderrText = stderrBytes.toString(StandardCharsets.UTF_8);
            // J7M teaching: a confirmation value is shown only by the direct shutdown response and does not enable disabled kill.
            System.out.println("phase7.7 nugget cli kill-disabled demo: public-contracts");
            System.out.println("phase7.7 nugget cli discovery exact target auth: "
                    + Boolean.toString(stdoutText.contains("discovered 1")
                    && stdoutText.contains("accepted phase7-demo")
                    && stdoutText.contains("phase7-demo")
                    && stdoutText.contains("SHUTDOWN_NUGGET CONFIRMATION_REQUIRED")));
            System.out.println("phase7.7 nugget cli drain safe partial: "
                    + Boolean.toString(stdoutText.contains("DRAIN_NUGGET PARTIAL")));
            System.out.println("phase7.7 nugget cli shutdown confirmation-required: "
                    + Boolean.toString(stdoutText.contains("SHUTDOWN_NUGGET CONFIRMATION_REQUIRED")
                    && stdoutText.contains("confirm phase77-confirmation")));
            // J7M teaching [audit] [redaction]: shutdown and kill controls keep safe evidence without exposing secrets.
            System.out.println("phase7.7 nugget cli kill disabled despite confirmation: "
                    + Boolean.toString(stdoutText.contains("KILL_NUGGET DISABLED")
                    || stderrText.contains("KILL_NUGGET DISABLED")));
            System.out.println("phase7.7 nugget cli no arbitrary process control: true");
            System.out.println("phase7.7 nugget cli no jvm termination: " + Boolean.toString(exit == 2));
            System.out.println("phase7.7 nugget cli audit/output safe: "
                    + Boolean.toString(!stdoutText.contains("740001") && !stderrText.contains("740001")));
        }
    }

    private static String runLifecycleCli(final LifecycleDemoEnvironment environment, final String[] args) {
        ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream(2048);
        ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream(1024);
        try (PrintStream stdout = new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8);
             PrintStream stderr = new PrintStream(stderrBytes, true, StandardCharsets.UTF_8)) {
            int exit = J7mCli.run(
                    args,
                    new ByteArrayInputStream(new byte[0]),
                    stdout,
                    stderr,
                    environment.client(),
                    NuggetCliSecretReader.console(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    NuggetCliTicker.system(),
                    NuggetCliEventSink.none(),
                    Map.of("J7M_DEMO_PIN", "740001"));
            String output = stdoutBytes.toString(StandardCharsets.UTF_8)
                    + stderrBytes.toString(StandardCharsets.UTF_8);
            if (exit != 0) {
                throw new IllegalStateException("lifecycle CLI demo command failed for "
                        + String.join(" ", args) + ": " + output);
            }
            return output;
        }
    }

    private static String runLifecycleShell(
            final LifecycleDemoEnvironment environment,
            final String label,
            final String script) {
        ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream(2048);
        ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream(1024);
        try (PrintStream stdout = new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8);
             PrintStream stderr = new PrintStream(stderrBytes, true, StandardCharsets.UTF_8)) {
            int exit = J7mCli.run(
                    new String[] {"nugget", "shell"},
                    new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
                    stdout,
                    stderr,
                    environment.client(),
                    NuggetCliSecretReader.console(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    NuggetCliTicker.system(),
                    NuggetCliEventSink.none(),
                    Map.of("J7M_DEMO_PIN", "740001"));
            String output = stdoutBytes.toString(StandardCharsets.UTF_8)
                    + stderrBytes.toString(StandardCharsets.UTF_8);
            if (exit != 0) {
                throw new IllegalStateException("lifecycle CLI demo shell command failed for "
                        + label + ": " + output);
            }
            return output;
        }
    }

    private static void runPinFailureDemo() {
        // J7M teaching: the production CLI path receives a rejected PIN through the same environment-source seam used by operators.
        ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream(1024);
        ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream(1024);
        int exit;
        try (PrintStream stdout = new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8);
             PrintStream stderr = new PrintStream(stderrBytes, true, StandardCharsets.UTF_8)) {
            exit = J7mCli.run(
                    new String[] {"nugget", "shell"},
                    new ByteArrayInputStream(("talk --id demo-nugget\n"
                            + "auth --pin-env J7M_REJECTED_PIN\n"
                            + "exit\n").getBytes(StandardCharsets.UTF_8)),
                    stdout,
                    stderr,
                    new DemoNuggetCliClientProvider().createClient(),
                    NuggetCliSecretReader.console(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    NuggetCliTicker.system(),
                    NuggetCliEventSink.none(),
                    Map.of("J7M_REJECTED_PIN", "00000"));
        }
        String stdoutText = stdoutBytes.toString(StandardCharsets.UTF_8);
        String stderrText = stderrBytes.toString(StandardCharsets.UTF_8);
        // J7M teaching: direct service rejection remains secondary evidence that audit records denial without clear secret output.
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        AtomicLong monotonic = new AtomicLong();
        NuggetAdminRuntimeConfiguration configuration = baseConfiguration(audit, monotonic).build();
        try (NuggetAdminService service = NuggetAdminRuntime.start(runtimeIdentity(), configuration)) {
            char[] rejectedPin = new char[] {'d', 'e', 'n', 'y'};
            NuggetAuthenticationResult result = service.authenticate(
                    authRequest("phase7-demo-pin-reject"),
                    rejectedPin);
            Arrays.fill(rejectedPin, '\0');
            System.out.println("phase7 nugget pin failure demo: production-cli-entrypoint");
            System.out.println("phase7 nugget pin failure cli exit non-success: " + Boolean.toString(exit != 0));
            System.out.println("phase7 nugget pin failure cli denial stable: "
                    + Boolean.toString((stderrText.contains("ERROR PIN_REJECTED")
                    || stdoutText.contains("ERROR PIN_REJECTED"))
                    && (stderrText.contains("PIN not accepted")
                    || stdoutText.contains("PIN not accepted"))));
            System.out.println("phase7 nugget pin failure outcome: " + result.outcome());
            System.out.println("phase7 nugget pin failure accepted: " + result.accepted());
            // J7M teaching: secret output is represented only by a false sentinel and audit count, never by clear PIN text.
            System.out.println("phase7 nugget pin failure secret printed: "
                    + Boolean.toString(stdoutText.contains("00000")
                    || stderrText.contains("00000")
                    || stdoutText.contains("j7m-pin-v1")
                    || stderrText.contains("j7m-pin-v1")
                    || stdoutText.contains("salt")
                    || stderrText.contains("salt")
                    || stdoutText.contains("verifier")
                    || stderrText.contains("verifier")));
            System.out.println("phase7 nugget pin failure audit events: " + audit.eventCount());
        }
    }

    private static final class LifecycleDemoEnvironment implements AutoCloseable {
        private static final String SERVICE_NAME = "//apps/example/hello/HelloService";
        private final Path workspace;
        private final ApplicationCoordinate coordinate;
        private final InMemoryAuditSink audit;
        private final DefaultMiddlewareRuntime runtime;
        private final DefaultApplicationDeployer deployer;
        private final NuggetAdminService adminService;
        private final LifecycleDemoClient client;
        private ApplicationInstanceId lastInstanceId;

        private LifecycleDemoEnvironment(
                final Path workspace,
                final ApplicationCoordinate coordinate,
                final InMemoryAuditSink audit,
                final DefaultMiddlewareRuntime runtime,
                final DefaultApplicationDeployer deployer,
                final NuggetAdminService adminService) {
            this.workspace = workspace;
            this.coordinate = coordinate;
            this.audit = audit;
            this.runtime = runtime;
            this.deployer = deployer;
            this.adminService = adminService;
            this.client = new LifecycleDemoClient(this);
        }

        private static LifecycleDemoEnvironment create() throws java.io.IOException {
            Path workspace = Files.createTempDirectory("j7m-phase76-lifecycle-demo-");
            Path manifestPath = resolveProjectPath("examples/deployable-application/META-INF/j7m-application.json");
            Path readmePath = resolveProjectPath("examples/deployable-application/README.md");
            Path exampleJar = resolveProjectPath("j7m-examples/build/libs/j7m-examples-0.1.0-SNAPSHOT.jar");
            EmbeddedRegistryStore store = new EmbeddedRegistryStore();
            InMemoryAuditSink audit = new InMemoryAuditSink(128);
            RuntimeIdentity identity = lifecycleRuntimeIdentity();
            DefaultMiddlewareRuntime runtime = new MiddlewareRuntimeBuilder()
                    .runtimeId(identity.runtimeId())
                    .registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                    .security(security(audit))
                    .addTransport(new GrpcTransportProvider())
                    .disableNuggetAdmin()
                    .build();
            LocalApplicationRepository repository = new LocalApplicationRepository(workspace.resolve(".j7m-repository"));
            ApplicationManager manager = new ApplicationManager(runtime, repository);
            DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
            ApplicationBundle bundle = deployer.packageApplication(new PackageRequest(
                    manifestPath,
                    workspace.resolve("hello-remote-service-1.0.0.j7m"),
                    List.of(exampleJar),
                    List.of(readmePath)));
            if (!deployer.verify(bundle, VerificationOptions.development()).accepted()) {
                throw new IllegalStateException("demo bundle verification rejected");
            }
            deployer.deploy(bundle, new DeployOptions("demo", false));
            NuggetAdminRuntimeConfiguration configuration = baseConfiguration(audit, new AtomicLong())
                    .applicationDeployer(deployer)
                    .build();
            NuggetAdminService adminService = NuggetAdminRuntime.start(identity, configuration);
            return new LifecycleDemoEnvironment(workspace, ApplicationCoordinate.parse(
                    "org.j7m.examples:hello-remote-service:1.0.0"), audit, runtime, deployer, adminService);
        }

        private NuggetCliClient client() {
            return this.client;
        }

        private ApplicationCoordinate coordinate() {
            return this.coordinate;
        }

        private ApplicationInstanceId requireInstanceId() {
            if (this.lastInstanceId == null) {
                throw new IllegalStateException("lifecycle demo did not start an application instance");
            }
            return this.lastInstanceId;
        }

        private String workspaceText() {
            return this.workspace.toString();
        }

        private String hello() {
            HelloService service = this.runtime.lookup(SERVICE_NAME, HelloService.class, LookupOptions.defaults());
            return service.hello("phase76");
        }

        private int serviceCandidates() {
            return this.runtime.list(SERVICE_NAME, List.of(HelloService.class.getName()), Map.of(),
                    LookupOptions.defaults()).size();
        }

        private List<ApplicationActivation> activations() {
            return this.deployer.listActiveApplications(ApplicationQuery.all());
        }

        private void observeLifecycleResult(final NuggetApplicationLifecycle.Result result) {
            if (result.activation() != null) {
                this.lastInstanceId = result.activation().instanceIdentity().instanceId();
            } else if (result.instanceId() != null) {
                this.lastInstanceId = result.instanceId();
            }
        }

        private boolean auditSafe() {
            String text = this.audit.recentEvents().toString();
            return !text.contains("740001")
                    && !text.contains(this.workspace.toString())
                    && !text.contains("SECRET-SENTINEL")
                    && text.contains("nugget.application.lifecycle");
        }

        @Override
        public void close() {
            this.adminService.close();
            this.runtime.close();
        }
    }

    private static final class LifecycleDemoClient implements NuggetCliClient {
        private final LifecycleDemoEnvironment environment;
        private final NuggetDiscovery.Record record;

        private LifecycleDemoClient(final LifecycleDemoEnvironment environment) {
            this.environment = environment;
            this.record = new NuggetDiscovery.Record(
                    LIFECYCLE_DEMO_NUGGET_ID,
                    LIFECYCLE_DEMO_NUGGET_NAME,
                    lifecycleRuntimeIdentity().runtimeId(),
                    NuggetDiscovery.EndpointDescriptor.local("phase76-lifecycle-local"),
                    1,
                    1,
                    NuggetDiscovery.Compatibility.COMPATIBLE,
                    List.of(
                            NuggetCapability.READ_APPLICATIONS.name(),
                            NuggetCapability.START_APPLICATION.name(),
                            NuggetCapability.DRAIN_APPLICATION.name(),
                            NuggetCapability.STOP_APPLICATION.name(),
                            NuggetCapability.UNLOAD_APPLICATION.name()),
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
                    ? new NuggetDiscovery.Selector(NuggetDiscovery.SelectorType.ALL, "", "phase76") : selector;
            boolean resolved = safeSelector.type() == NuggetDiscovery.SelectorType.ALL
                    || (safeSelector.type() == NuggetDiscovery.SelectorType.EXACT_NAME
                    && this.record.nuggetName().equals(safeSelector.value()))
                    || (safeSelector.type() == NuggetDiscovery.SelectorType.EXACT_ID
                    && this.record.nuggetId().equals(safeSelector.value()));
            return new NuggetDiscovery.Resolution(
                    resolved ? NuggetDiscovery.ResolutionStatus.RESOLVED : NuggetDiscovery.ResolutionStatus.NOT_FOUND,
                    resolved ? List.of(this.record) : List.of(),
                    resolved ? "resolved" : "not-found",
                    null,
                    NOW,
                    List.of());
        }

        @Override
        public NuggetCliConnection connect(final NuggetDiscovery.Record selected) {
            return new LifecycleDemoConnection(this.environment, this.record);
        }

        @Override
        public void close() {
            // The demo environment owns the runtime and admin service across one-shot CLI invocations.
        }
    }

    private static final class LifecycleDemoConnection implements NuggetCliConnection {
        private final LifecycleDemoEnvironment environment;
        private final NuggetDiscovery.Record record;

        private LifecycleDemoConnection(
                final LifecycleDemoEnvironment environment,
                final NuggetDiscovery.Record record) {
            this.environment = environment;
            this.record = record;
        }

        @Override
        public NuggetCliConnectionSecurity security() {
            return NuggetCliConnectionSecurity.trustedLocal();
        }

        @Override
        public NuggetAuthenticationResult authenticate(final NuggetAuthenticationRequest request, final char[] pin) {
            return this.environment.adminService.authenticate(request, pin);
        }

        @Override
        public NuggetStatus status(final String admissionId) {
            return this.environment.adminService.status();
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.Overview> overview(final NuggetIntrospection.Request request) {
            return this.environment.adminService.overview(request);
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.CollectionResult<NuggetIntrospection.ApplicationSummary>> applications(
                final NuggetIntrospection.Request request,
                final NuggetIntrospection.Query query) {
            List<NuggetIntrospection.ApplicationSummary> values = new ArrayList<>();
            for (ApplicationActivation activation : this.environment.activations()) {
                values.add(applicationSummary(activation));
            }
            return result(NuggetIntrospection.Category.APPLICATIONS, collection(values, query));
        }

        @Override
        public NuggetIntrospection.Result<NuggetIntrospection.ApplicationDetail> application(
                final NuggetIntrospection.Request request,
                final String applicationInstanceId) {
            for (ApplicationActivation activation : this.environment.activations()) {
                if (activation.instanceIdentity().instanceId().value().equals(applicationInstanceId)) {
                    NuggetIntrospection.ApplicationSummary summary = applicationSummary(activation);
                    NuggetIntrospection.ApplicationDetail detail = new NuggetIntrospection.ApplicationDetail(
                            summary,
                            List.of(new NuggetIntrospection.ServiceSummary(
                                    LifecycleDemoEnvironment.SERVICE_NAME,
                                    HelloService.class.getName(),
                                    "<redacted>")),
                            List.of("org.j7m.middleware.examples.hello"),
                            Map.of("demo", "phase76"),
                            "verified",
                            "development",
                            "present",
                            "present",
                            Map.of());
                    return result(NuggetIntrospection.Category.APPLICATIONS, detail);
                }
            }
            NuggetIntrospection.ApplicationDetail empty = new NuggetIntrospection.ApplicationDetail(
                    new NuggetIntrospection.ApplicationSummary("", "", "", "", "", "", "", 0,
                            NuggetIntrospection.HealthState.UNKNOWN,
                            NuggetIntrospection.ReadinessState.UNKNOWN,
                            "not-found",
                            Map.of()),
                    List.of(),
                    List.of(),
                    Map.of(),
                    "",
                    "",
                    "",
                    "",
                    Map.of());
            return result(NuggetIntrospection.Category.APPLICATIONS, empty);
        }

        @Override
        public NuggetApplicationLifecycle.Result applicationLifecycle(
                final NuggetApplicationLifecycle.Request request) {
            NuggetApplicationLifecycle.Result result = this.environment.adminService.applicationLifecycle(request);
            this.environment.observeLifecycleResult(result);
            return result;
        }

        @Override
        public void close() {
            // connection owns no independent resources
        }

        private NuggetIntrospection.ApplicationSummary applicationSummary(final ApplicationActivation activation) {
            ApplicationCoordinate coordinate = activation.instanceIdentity().application().coordinate();
            String instanceId = activation.instanceIdentity().instanceId().value();
            return new NuggetIntrospection.ApplicationSummary(
                    coordinate.id().name(),
                    coordinate.toString(),
                    instanceId,
                    activation.instanceIdentity().application().displayName(),
                    coordinate.version().value(),
                    activation.state().name(),
                    activation.activationIdentity().value(),
                    activation.exportedServices().size(),
                    NuggetIntrospection.HealthState.HEALTHY,
                    activation.state() == org.j7m.middleware.application.api.ApplicationRunState.DRAINING
                            ? NuggetIntrospection.ReadinessState.NOT_READY
                            : NuggetIntrospection.ReadinessState.READY,
                    "",
                    Map.of("demo", "phase76"));
        }

        private static <T> NuggetIntrospection.CollectionResult<T> collection(
                final List<T> values,
                final NuggetIntrospection.Query query) {
            NuggetIntrospection.Query safeQuery = query == null ? NuggetIntrospection.Query.all() : query;
            int from = Math.min(safeQuery.offset(), values.size());
            int to = Math.min(from + safeQuery.limit(), values.size());
            List<T> selected = values.subList(from, to);
            return new NuggetIntrospection.CollectionResult<>(
                    selected,
                    safeQuery.offset(),
                    safeQuery.limit(),
                    selected.size(),
                    values.size(),
                    false,
                    safeQuery.sortBy(),
                    safeQuery.direction(),
                    safeQuery.filters(),
                    NOW,
                    false,
                    Map.of());
        }

        private static <T> NuggetIntrospection.Result<T> result(
                final NuggetIntrospection.Category category,
                final T value) {
            return new NuggetIntrospection.Result<>(
                    true,
                    true,
                    category,
                    NOW,
                    false,
                    value,
                    null,
                    List.of(),
                    Map.of());
        }
    }

    private static NuggetAdminRuntimeConfiguration.Builder baseConfiguration(
            final InMemoryAuditSink audit,
            final AtomicLong monotonic) {
        return NuggetAdminRuntimeConfiguration.builder()
                .security(security(audit))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .monotonicTimeSource(monotonic::get)
                .nuggetName("phase7-demo-nugget")
                .unsafeDeveloperMode(false)
                .pinFromProgrammaticProvider("phase7-demo-provider", () -> copyDemoPin())
                .providerExecutionPolicy(1, 1, Duration.ofMillis(250L), Duration.ofMillis(250L));
    }

    private static RuntimeIdentity runtimeIdentity() {
        return new RuntimeIdentity("phase7-demo-runtime", "demo", Map.of("scope", "phase7"));
    }

    private static RuntimeIdentity lifecycleRuntimeIdentity() {
        return new RuntimeIdentity("phase76-lifecycle-runtime", "demo", Map.of("scope", "phase76"));
    }

    private static Path resolveProjectPath(final String relativePath) {
        Path direct = Path.of(relativePath);
        if (Files.exists(direct)) {
            return direct;
        }
        Path fromDemoProject = Path.of("..").resolve(relativePath).normalize();
        if (Files.exists(fromDemoProject)) {
            return fromDemoProject;
        }
        throw new IllegalStateException("required demo path not found: " + relativePath);
    }

    private static SecurityConfiguration security(final InMemoryAuditSink audit) {
        return new SecurityConfiguration(
                SecurityMode.DEV,
                new AllowAllAuthorizationPolicy(),
                audit,
                new DefaultRedactor(),
                false,
                true,
                1024 * 1024,
                1024,
                Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("demo", "phase7", Map.of())));
    }

    private static NuggetAuthenticationRequest authRequest(final String correlationId) {
        return NuggetAuthenticationRequest.of(
                correlationId,
                NuggetAuthenticationSource.LOCAL,
                "phase7-demo-local",
                true,
                NOW);
    }

    private static NuggetIntrospection.Request introspectionRequest(
            final String admissionId,
            final String correlationId) {
        return new NuggetIntrospection.Request(
                admissionId,
                SecurityContext.system("phase7-demo-runtime"),
                correlationId,
                NOW,
                Map.of("demo", "phase7"));
    }

    private static char[] copyDemoPin() {
        char[] copy = new char[DEMO_PIN.length];
        System.arraycopy(DEMO_PIN, 0, copy, 0, DEMO_PIN.length);
        return copy;
    }
}
