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

package org.j7m.middleware.demo;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.j7m.middleware.annotations.Idempotent;
import org.j7m.middleware.annotations.OneWay;
import org.j7m.middleware.annotations.Sensitive;
import org.j7m.middleware.admin.ComplianceAdmin;
import org.j7m.middleware.admin.RuntimeAdmin;
import org.j7m.middleware.api.ExportOptions;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.api.ObjectRegistration;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.application.api.ApplicationActivation;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationDeploymentResult;
import org.j7m.middleware.application.api.ApplicationQuery;
import org.j7m.middleware.application.api.ApplicationRunState;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.repository.ApplicationBundleVerifier;
import org.j7m.middleware.application.repository.DefaultBundleSigner;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.application.repository.SignaturePolicy;
import org.j7m.middleware.attestation.ReleaseAttestationMetadata;
import org.j7m.middleware.compliance.api.ComplianceReport;
import org.j7m.middleware.compliance.api.ComplianceScope;
import org.j7m.middleware.compliance.api.EvidenceExportFormat;
import org.j7m.middleware.compliance.defaults.BuiltInComplianceProfiles;
import org.j7m.middleware.compliance.defaults.DefaultComplianceReportService;
import org.j7m.middleware.compliance.defaults.DefaultEvidenceExporter;
import org.j7m.middleware.core.DefaultMiddlewareRuntime;
import org.j7m.middleware.crypto.policy.CryptoPolicy;
import org.j7m.middleware.crypto.policy.CryptoPolicyEvaluator;
import org.j7m.middleware.crypto.policy.CryptoPostureReport;
import org.j7m.middleware.crypto.policy.CryptoProviderPolicy;
import org.j7m.middleware.crypto.policy.FipsModePolicy;
import org.j7m.middleware.crypto.policy.KeyRotationPolicy;
import org.j7m.middleware.crypto.policy.TestKeyManagementProvider;
import org.j7m.middleware.deployer.ApplicationManager;
import org.j7m.middleware.deployer.DefaultApplicationDeployer;
import org.j7m.middleware.deployer.api.DeactivationOptions;
import org.j7m.middleware.deployer.api.DeployOptions;
import org.j7m.middleware.deployer.api.PackageRequest;
import org.j7m.middleware.deployer.api.RollbackOptions;
import org.j7m.middleware.deployer.api.UnloadOptions;
import org.j7m.middleware.deployer.api.UpgradeOptions;
import org.j7m.middleware.deployer.api.VerificationOptions;
import org.j7m.middleware.enterprise.iam.DeterministicPdpClient;
import org.j7m.middleware.enterprise.iam.ExternalPdpAuthorizationPolicy;
import org.j7m.middleware.evidence.OperationalResilienceEvidence;
import org.j7m.middleware.evidence.RuntimeEvidenceCollector;
import org.j7m.middleware.evidence.ValidationSummary;
import org.j7m.middleware.examples.hello.HelloService;
import org.j7m.middleware.examples.banking.ApprovalResult;
import org.j7m.middleware.examples.banking.BankingApprovalService;
import org.j7m.middleware.examples.banking.Money;
import org.j7m.middleware.examples.banking.PaymentId;
import org.j7m.middleware.examples.cluster.ClusteredGreetingService;
import org.j7m.middleware.examples.cluster.ClusteredGreetingServiceImpl;
import org.j7m.middleware.examples.events.EventService;
import org.j7m.middleware.examples.events.EventServiceImpl;
import org.j7m.middleware.examples.events.RecordingEventCallback;
import org.j7m.middleware.examples.regulated.AuditVerificationResult;
import org.j7m.middleware.examples.regulated.ComplianceSummary;
import org.j7m.middleware.examples.regulated.EvidenceReport;
import org.j7m.middleware.examples.regulated.ExampleCryptoPosture;
import org.j7m.middleware.examples.regulated.RegulatedEnterpriseService;
import org.j7m.middleware.examples.regulated.RegulatedExampleVerifier;
import org.j7m.middleware.examples.regulated.RegulatedVerificationResult;
import org.j7m.middleware.examples.streaming.BatchResult;
import org.j7m.middleware.examples.streaming.StreamExchangeResult;
import org.j7m.middleware.examples.streaming.StreamMetrics;
import org.j7m.middleware.examples.streaming.StreamOptions;
import org.j7m.middleware.examples.streaming.StreamSession;
import org.j7m.middleware.examples.streaming.StreamingPipelineService;
import org.j7m.middleware.examples.workload.Checkpoint;
import org.j7m.middleware.examples.workload.LeaseResult;
import org.j7m.middleware.examples.workload.TaskHandle;
import org.j7m.middleware.examples.workload.WorkloadProcessingService;
import org.j7m.middleware.examples.workload.WorkloadTaskProgress;
import org.j7m.middleware.examples.workload.WorkloadTaskStatus;
import org.j7m.middleware.registry.api.DiscoveryEvent;
import org.j7m.middleware.registry.api.DiscoveryStatistics;
import org.j7m.middleware.registry.api.PeerQuery;
import org.j7m.middleware.registry.api.RegistryClient;
import org.j7m.middleware.registry.api.RegistryDiagnostics;
import org.j7m.middleware.registry.api.RegistryEndpoint;
import org.j7m.middleware.registry.api.RegistryPeerQuality;
import org.j7m.middleware.registry.api.RegistryPeerState;
import org.j7m.middleware.registry.api.RegistryPeerStatus;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.j7m.middleware.registry.api.RegistryRecord;
import org.j7m.middleware.registry.api.RegistryRecordSigner;
import org.j7m.middleware.registry.api.RegistryTrustStatus;
import org.j7m.middleware.registry.bootstrap.BootstrapRegistryClient;
import org.j7m.middleware.registry.bootstrap.BootstrapRegistryOptions;
import org.j7m.middleware.registry.bootstrap.InMemoryBootstrapEndpointResolver;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.registry.mcast.MulticastRegistryBus;
import org.j7m.middleware.registry.mcast.MulticastRegistryClient;
import org.j7m.middleware.registry.mcast.MulticastRegistryOptions;
import org.j7m.middleware.registry.mcast.UdpMulticastBus;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.scheduler.InMemorySchedulerOptions;
import org.j7m.middleware.scheduler.InMemoryWorkloadScheduler;
import org.j7m.middleware.scheduler.StableHashShardRoutingStrategy;
import org.j7m.middleware.scheduler.WorkloadAdmin;
import org.j7m.middleware.security.api.Credential;
import org.j7m.middleware.security.api.AuthorizationDecision;
import org.j7m.middleware.security.api.PermissionAction;
import org.j7m.middleware.security.api.PermissionRequest;
import org.j7m.middleware.security.api.Principal;
import org.j7m.middleware.security.api.SecurityConfiguration;
import org.j7m.middleware.security.api.SecurityContext;
import org.j7m.middleware.security.api.SecurityContexts;
import org.j7m.middleware.security.api.SecurityMode;
import org.j7m.middleware.security.api.TransportSecurityLevel;
import org.j7m.middleware.security.api.TrustDomain;
import org.j7m.middleware.security.defaults.AllowAllAuthorizationPolicy;
import org.j7m.middleware.security.defaults.DefaultRedactor;
import org.j7m.middleware.security.defaults.DeterministicRegistryRecordSigner;
import org.j7m.middleware.security.defaults.InMemoryAuditSink;
import org.j7m.middleware.security.defaults.MutableCredentialProvider;
import org.j7m.middleware.security.defaults.RuleBasedAuthorizationPolicy;
import org.j7m.middleware.security.defaults.SecurityDefaults;
import org.j7m.middleware.security.defaults.TamperEvidentAuditSink;
import org.j7m.middleware.transport.grpc.GrpcSecurityOptions;
import org.j7m.middleware.transport.grpc.GrpcTestCertificateMaterial;
import org.j7m.middleware.transport.grpc.GrpcTlsConfiguration;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
import org.j7m.middleware.transport.rmi.RmiTransportProvider;
import org.j7m.middleware.transport.api.MethodDescriptor;
import org.j7m.middleware.transport.api.StreamingTransportProvider;
import org.j7m.middleware.workload.api.FanOutRequest;
import org.j7m.middleware.workload.api.FencingToken;
import org.j7m.middleware.workload.api.LeaseOwner;
import org.j7m.middleware.workload.api.ScatterGatherRequest;
import org.j7m.middleware.workload.api.ShardKey;
import org.j7m.middleware.workload.api.ShardRoute;
import org.j7m.middleware.workload.api.StreamHandle;
import org.j7m.middleware.workload.api.TaskDescriptor;
import org.j7m.middleware.workload.api.TaskLease;
import org.j7m.middleware.workload.api.TaskOptions;
import org.j7m.middleware.workload.api.TaskProgress;
import org.j7m.middleware.workload.api.TaskRef;
import org.j7m.middleware.workload.api.TaskState;
import org.j7m.middleware.workload.api.TaskSubmission;
import org.j7m.middleware.workload.api.WorkloadHandle;
import org.j7m.middleware.workload.api.WorkloadMetadata;

/**
 * Minimal Phase 1 and Phase 2 demo.
 *
 * <p>The demo runs two runtimes in one JVM, exports one logical service through
 * the default gRPC path and then through the legacy RMI path, passes a callback
 * by remote reference, closes one instance, and demonstrates HA failover. It
 * also shows Phase 2 multicast discovery, bootstrap fallback, and HP
 * selection.</p>
 */
public final class Phase1Demo {
    private static final DeterministicRegistryRecordSigner DEMO_REGISTRY_TRUST =
            new DeterministicRegistryRecordSigner("phase1-demo-registry");

        // J7M teaching: this demo block is self-contained so acceptance smoke runs do not require external services.
    private Phase1Demo() {
    }

    /**
     * Runs the demo.
     *
     * @param args ignored
     */
    public static void main(final String[] args) {
        // J7M teaching: Phase1Demo is kept as the legacy task-backed dispatcher,
        // so each argument is mapped to a documented method flow instead of
        // relying on a generic class-level demo comment.
        if (args != null && args.length > 0 && "udp".equals(args[0])) {
            runUdpMulticastOnly();
            return;
        }
        if (args != null && args.length > 0 && "security".equals(args[0])) {
            runSecurityDemo();
            return;
        }
        if (args != null && args.length > 0 && "mtls".equals(args[0])) {
            runMtlsSecurityDemo();
            return;
        }
        if (args != null && args.length > 0 && "workload".equals(args[0])) {
            runWorkloadDemo();
            return;
        }
        if (args != null && args.length > 0 && "streaming-workload".equals(args[0])) {
            runStreamingWorkloadDemo();
            return;
        }
        if (args != null && args.length > 0 && "compliance".equals(args[0])) {
            runComplianceDemo();
            return;
        }
        if (args != null && args.length > 0 && "enterprise-readiness".equals(args[0])) {
            runComplianceDemo();
            return;
        }
        if (args != null && args.length > 0 && "application-deployment".equals(args[0])) {
            runApplicationDeploymentDemo();
            return;
        }
        if (args != null && args.length > 0 && "modular-application-deployment".equals(args[0])) {
            runModularApplicationDeploymentDemo();
            return;
        }
        if (args != null && args.length > 0 && "application-upgrade-rollback".equals(args[0])) {
            runApplicationUpgradeRollbackDemo();
            return;
        }
        if (args != null && args.length > 0 && "rolling-upgrade".equals(args[0])) {
            runUpgradeStrategyDemo("rolling", UpgradeOptions.rolling());
            return;
        }
        if (args != null && args.length > 0 && "blue-green-upgrade".equals(args[0])) {
            runUpgradeStrategyDemo("blue-green", UpgradeOptions.blueGreen());
            return;
        }
        if (args != null && args.length > 0 && "canary-baseline-upgrade".equals(args[0])) {
            runUpgradeStrategyDemo("canary-baseline", UpgradeOptions.canaryBaseline());
            return;
        }
        if (args != null && args.length > 0 && "application-failure-diagnostics".equals(args[0])) {
            runApplicationFailureDiagnosticsDemo();
            return;
        }
        if (args != null && args.length > 0 && "application-admin-status".equals(args[0])) {
            runApplicationAdminStatusDemo();
            return;
        }
        if (args != null && args.length > 0 && "hello-remote-service-example".equals(args[0])) {
            runHelloRemoteServiceExample();
            return;
        }
        if (args != null && args.length > 0 && "callbacks-and-events-example".equals(args[0])) {
            runCallbacksAndEventsExample();
            return;
        }
        if (args != null && args.length > 0 && "secure-banking-workflow-example".equals(args[0])) {
            runSecureBankingWorkflowExample();
            return;
        }
        if (args != null && args.length > 0 && "ha-hp-cluster-example".equals(args[0])) {
            runHaHpClusterExample();
            return;
        }
        if (args != null && args.length > 0 && "workload-processing-example".equals(args[0])) {
            runWorkloadProcessingExample();
            return;
        }
        if (args != null && args.length > 0 && "streaming-pipeline-example".equals(args[0])) {
            runStreamingPipelineExample();
            return;
        }
        if (args != null && args.length > 0 && "regulated-enterprise-app-example".equals(args[0])) {
            runRegulatedEnterpriseAppExample();
            return;
        }
        runTransport("grpc");
        runTransport("rmi");
        runMulticastAndHp();
        runBootstrapFallback();
    }

    private static void runHelloRemoteServiceExample() {
        try {
            Path workspace = Files.createTempDirectory("j7m-hello-example-");
            Path manifestPath = resolveProjectPath("examples/hello-remote-service/META-INF/j7m-application.json");
            Path readmePath = resolveProjectPath("examples/hello-remote-service/README.md");
            Path exampleJar = resolveProjectPath("j7m-examples/build/libs/j7m-examples-0.1.0-SNAPSHOT.jar");
            EmbeddedRegistryStore store = new EmbeddedRegistryStore();
            try (DefaultMiddlewareRuntime runtime = runtime("demo-hello-example", store, "grpc")) {
                // J7M teaching: this flow packages and activates a normal
                // classpath service so developers can copy the bundle, verify,
                // deploy, lookup, and unload sequence without touching runtime internals.
                LocalApplicationRepository repository = new LocalApplicationRepository(workspace.resolve(".j7m-repository"));
                ApplicationManager manager = new ApplicationManager(runtime, repository);
                DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
                ApplicationBundle bundle = deployer.packageApplication(new PackageRequest(manifestPath,
                        workspace.resolve("hello-remote-service-1.0.0.j7m"), List.of(exampleJar), List.of(readmePath)));
                System.out.println("phase5.5 hello example package: " + bundle.path().getFileName());
                System.out.println("phase5.5 hello example verify accepted: " + deployer.verify(bundle, VerificationOptions.development()).accepted());
                deployer.deploy(bundle, new DeployOptions("demo", false));
                ApplicationActivation activation = deployer.activate(ApplicationCoordinate.parse("org.j7m.examples:hello-remote-service:1.0.0"),
                        new ApplicationActivationRequest("demo-operator", "dev", false, Map.of()));
                HelloService service = runtime.lookup("//apps/example/hello/HelloService", HelloService.class, LookupOptions.defaults());
                System.out.println("phase5.5 hello example greeting: " + service.hello("phase55"));
                System.out.println("phase5.5 hello example active: " + deployer.listActiveApplications(ApplicationQuery.all()).size());
                // J7M teaching: stopping and unloading prove service
                // registrations are lifecycle-owned by the application, not
                // permanent global ObjectRef entries.
                deployer.deactivate(activation.instanceIdentity().instanceId(), new DeactivationOptions(false));
                deployer.unload(activation.instanceIdentity().instanceId(), new UnloadOptions(false));
                System.out.println("phase5.5 hello example active after unload: " + deployer.listActiveApplications(ApplicationQuery.all()).size());
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed to run hello remote service example", exception);
        }
    }

    private static void runCallbacksAndEventsExample() {
        // J7M teaching: the callbacks example intentionally runs both
        // transports so ObjectRef callback behavior is shown as a middleware
        // contract rather than as gRPC-only implementation detail.
        runCallbackExampleForTransport("grpc");
        runCallbackExampleForTransport("rmi");
    }

    private static void runCallbackExampleForTransport(final String transport) {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        EventServiceImpl implementation = new EventServiceImpl();
        try (DefaultMiddlewareRuntime server = runtime("demo-events-server-" + transport, store, transport);
             DefaultMiddlewareRuntime client = runtime("demo-events-client-" + transport, store, transport)) {
            // J7M teaching: the callback instance crosses the service boundary
            // as an ObjectRef, demonstrating what developers may copy for
            // event callbacks without copying transport-specific plumbing.
            server.export("example.events", implementation, EventService.class, export(transport, Map.of("example", "callbacks")));
            EventService service = client.lookup("example.events", EventService.class, LookupOptions.defaults());
            RecordingEventCallback callback = new RecordingEventCallback();
            String result = service.registerAndPing(callback, transport + "-event");
            System.out.println("phase5.5 callbacks " + transport + " result: " + result);
            System.out.println("phase5.5 callbacks " + transport + " values: " + callback.values());
            System.out.println("phase5.5 callbacks " + transport + " object-ref-exported: "
                    + client.status().instances().stream().anyMatch(status -> status.ref().metadata().containsKey("callback")));
            System.out.println("phase5.5 callbacks " + transport + " invocation-log: " + implementation.invocationLog());
        }
    }

    private static void runSecureBankingWorkflowExample() {
        try {
            Path workspace = Files.createTempDirectory("j7m-banking-example-");
            Path manifestPath = resolveProjectPath("examples/secure-banking-workflow/META-INF/j7m-application.json");
            Path readmePath = resolveProjectPath("examples/secure-banking-workflow/README.md");
            Path exampleJar = resolveProjectPath("j7m-examples/build/libs/j7m-examples-0.1.0-SNAPSHOT.jar");
            InMemoryAuditSink audit = new InMemoryAuditSink(64);
            RuleBasedAuthorizationPolicy policy = new RuleBasedAuthorizationPolicy(List.of(
                    new RuleBasedAuthorizationPolicy.Rule(Set.of("banking-approver"), Set.of(
                            PermissionAction.EXPORT,
                            PermissionAction.LOOKUP,
                            PermissionAction.LIST,
                            PermissionAction.INVOKE,
                            PermissionAction.UNBIND,
                            PermissionAction.READ_STATUS), "")));
            SecurityConfiguration security = new SecurityConfiguration(SecurityMode.DEV, policy, audit,
                    new DefaultRedactor(), false, true, 1024 * 1024, 1024, Duration.ofSeconds(2L),
                    new MutableCredentialProvider(new Credential("demo", "phase55", Map.of())));
            EmbeddedRegistryStore store = new EmbeddedRegistryStore();
            try (DefaultMiddlewareRuntime runtime = runtime("demo-banking-example", store, "grpc", security)) {
                // J7M teaching: the bundle is activated under a publisher
                // identity, while service lookup and invocation use caller
                // identities so authorization evidence is visible in the flow.
                LocalApplicationRepository repository = new LocalApplicationRepository(workspace.resolve(".j7m-repository"));
                ApplicationManager manager = new ApplicationManager(runtime, repository);
                DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
                ApplicationBundle bundle = deployer.packageApplication(new PackageRequest(manifestPath,
                        workspace.resolve("secure-banking-workflow-1.0.0.j7m"), List.of(exampleJar), List.of(readmePath)));
                deployer.deploy(bundle, new DeployOptions("demo", false));
                ApplicationActivation activation = SecurityContexts.call(context("publisher", "dev", true, Set.of("banking-approver")),
                        () -> deployer.activate(ApplicationCoordinate.parse("org.j7m.examples:secure-banking-workflow:1.0.0"),
                                new ApplicationActivationRequest("publisher", "dev", false, Map.of())));
                BankingApprovalService service = SecurityContexts.call(context("alice", "dev", true, Set.of("banking-approver")),
                        () -> runtime.lookup("//apps/example/banking/ApprovalService",
                                BankingApprovalService.class, LookupOptions.defaults()));
                ApprovalResult result = SecurityContexts.call(context("alice", "dev", true, Set.of("banking-approver")),
                        () -> service.approvePayment(new PaymentId("pay-123"), new Money("USD", 42_00L)));
                System.out.println("phase5.5 banking authorized approver: " + result.approver());
                System.out.println("phase5.5 banking correlation present: " + !result.correlationId().isBlank());
                // J7M teaching: the denied path is part of the example, not an
                // error case; it shows role policy enforcement and safe
                // MiddlewareErrorCode reporting for unauthorized callers.
                try {
                    SecurityContexts.call(context("mallory", "dev", true, Set.of("viewer")),
                            () -> runtime.lookup("//apps/example/banking/ApprovalService",
                                    BankingApprovalService.class, LookupOptions.defaults())
                                    .approvePayment(new PaymentId("pay-456"), new Money("USD", 99_00L)));
                } catch (MiddlewareException exception) {
                    System.out.println("phase5.5 banking unauthorized denied: " + exception.code());
                }
                String auditText = SecurityContexts.call(context("alice", "dev", true, Set.of("banking-approver")), service::auditLog).toString();
                // J7M teaching: audit output is treated as developer-visible
                // evidence, so the example proves sensitive payment amounts
                // are redacted before status text is printed.
                System.out.println("phase5.5 banking audit events: " + audit.eventCount());
                System.out.println("phase5.5 banking sensitive redacted: " + (auditText.contains("<redacted>") && !auditText.contains("4200")));
                SecurityContexts.run(context("alice", "dev", true, Set.of("banking-approver")), () -> {
                    deployer.deactivate(activation.instanceIdentity().instanceId(), new DeactivationOptions(false));
                    deployer.unload(activation.instanceIdentity().instanceId(), new UnloadOptions(false));
                });
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed to run secure banking workflow example", exception);
        }
    }

    private static void runHaHpClusterExample() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime runtimeA = runtime("demo-cluster-a", store, "grpc");
             DefaultMiddlewareRuntime runtimeB = runtime("demo-cluster-b", store, "grpc");
             DefaultMiddlewareRuntime client = runtime("demo-cluster-client", store, "grpc")) {
            ObjectRegistration<ClusteredGreetingService> first = runtimeA.export("example.cluster.greeting",
                    new ClusteredGreetingServiceImpl("cluster-a"), ClusteredGreetingService.class,
                    export("grpc", Map.of("role", "primary", "load", "0.0")));
            // J7M teaching: two equivalent exports make HA/HP selection
            // observable; metadata guides candidate choice but does not
            // promise consensus or durable queue semantics.
            runtimeB.export("example.cluster.greeting",
                    new ClusteredGreetingServiceImpl("cluster-b"), ClusteredGreetingService.class,
                    export("grpc", Map.of("role", "primary", "load", "0.1")));
            ClusteredGreetingService service = client.lookup("example.cluster.greeting", ClusteredGreetingService.class,
                    new LookupOptions("default", Map.of("role", "primary"), Duration.ofSeconds(5L), true, true));
            String firstTarget = service.instanceId();
            System.out.println("phase5.5 cluster first target: " + firstTarget);
            // J7M teaching: closing one registration demonstrates failover
            // through live registry state, which developers can copy as a
            // high-availability pattern without copying the demo runtimes.
            first.close();
            String afterFailover = service.instanceId();
            System.out.println("phase5.5 cluster after failover: " + afterFailover);
            System.out.println("phase5.5 cluster greeting: " + service.greet("phase55"));
            System.out.println("phase5.5 cluster status contains hp: " + new RuntimeAdmin(client).formatStatus().contains("hp"));
        }
    }

    private static void runWorkloadProcessingExample() {
        try {
            Path workspace = Files.createTempDirectory("j7m-workload-example-");
            ApplicationBundle bundle = packageClasspathExample(workspace, "workload-processing");
            EmbeddedRegistryStore store = new EmbeddedRegistryStore();
            try (DefaultMiddlewareRuntime runtime = runtime("demo-workload-processing-example", store, "grpc")) {
                // J7M teaching: the deployable workload example proves the
                // application-platform packaging path before exercising task
                // progress, checkpoint, lease, and cancellation APIs.
                LocalApplicationRepository repository = new LocalApplicationRepository(workspace.resolve(".j7m-repository"));
                ApplicationManager manager = new ApplicationManager(runtime, repository);
                DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
                System.out.println("phase5.5 workload verify accepted: " + deployer.verify(bundle, VerificationOptions.development()).accepted());
                deployer.deploy(bundle, new DeployOptions("demo", false));
                ApplicationActivation activation = deployer.activate(ApplicationCoordinate.parse("org.j7m.examples:workload-processing:1.0.0"),
                        new ApplicationActivationRequest("demo-operator", "dev", false, Map.of()));
                WorkloadProcessingService service = runtime.lookup("//apps/example/workload/ProcessingService",
                        WorkloadProcessingService.class, LookupOptions.defaults());
                TaskHandle task = service.submitTask("demo-payload");
                WorkloadTaskProgress progress = service.progress(task);
                Checkpoint checkpoint = service.checkpoint(task);
                LeaseResult lease = service.acquireLease(task, "worker-a");
                // J7M teaching: stale fencing is expected to fail; developers
                // should copy the fencing discipline, not treat task completion
                // as an unfenced global side effect.
                boolean staleAccepted = service.completeWithFence(task, lease.fencingToken() - 1L);
                boolean completed = service.completeWithFence(task, lease.fencingToken());
                TaskHandle cancelledTask = service.submitTask("cancel-me");
                service.cancel(cancelledTask);
                WorkloadTaskStatus cancelled = service.status(cancelledTask);
                System.out.println("phase5.5 workload progress: " + progress.percent() + " " + progress.message());
                System.out.println("phase5.5 workload checkpoint: " + checkpoint.checkpointId());
                System.out.println("phase5.5 workload stale fence accepted: " + staleAccepted);
                System.out.println("phase5.5 workload completed: " + completed);
                System.out.println("phase5.5 workload cancelled state: " + cancelled.state());
                System.out.println("phase5.5 workload status: " + service.adminStatus());
                System.out.println("phase5.5 workload active: "
                        + deployer.listActiveApplications(new ApplicationQuery(null, ApplicationRunState.RUNNING)).size());
                deployer.deactivate(activation.instanceIdentity().instanceId(), new DeactivationOptions(false));
                System.out.println("phase5.5 workload registry after stop: "
                        + runtime.list("//apps/example/workload/ProcessingService", List.of(WorkloadProcessingService.class.getName()),
                                Map.of(), LookupOptions.defaults()).size());
                deployer.unload(activation.instanceIdentity().instanceId(), new UnloadOptions(false));
                System.out.println("phase5.5 workload active after unload: " + deployer.listActiveApplications(ApplicationQuery.all()).size());
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed to run workload processing example", exception);
        }
    }

    private static void runStreamingPipelineExample() {
        try {
            Path workspace = Files.createTempDirectory("j7m-streaming-example-");
            ApplicationBundle bundle = packageClasspathExample(workspace, "streaming-pipeline");
            EmbeddedRegistryStore store = new EmbeddedRegistryStore();
            try (DefaultMiddlewareRuntime runtime = runtime("demo-streaming-pipeline-example", store, "grpc")) {
                // J7M teaching: this flow packages the streaming example first
                // so the subsequent session, backpressure, and cleanup evidence
                // comes from a managed application rather than direct test wiring.
                LocalApplicationRepository repository = new LocalApplicationRepository(workspace.resolve(".j7m-repository"));
                ApplicationManager manager = new ApplicationManager(runtime, repository);
                DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
                System.out.println("phase5.5 streaming verify accepted: " + deployer.verify(bundle, VerificationOptions.development()).accepted());
                deployer.deploy(bundle, new DeployOptions("demo", false));
                ApplicationActivation activation = deployer.activate(ApplicationCoordinate.parse("org.j7m.examples:streaming-pipeline:1.0.0"),
                        new ApplicationActivationRequest("demo-operator", "dev", false, Map.of()));
                StreamingPipelineService service = runtime.lookup("//apps/example/streaming/PipelineService",
                        StreamingPipelineService.class, LookupOptions.defaults());
                StreamSession session = service.openSession(new StreamOptions(8, 2));
                BatchResult accepted = service.submitBatch(session, List.of("a", "b"));
                BatchResult backpressure = service.submitBatch(session, List.of("c"));
                // J7M teaching: accepted, backpressured, oversized, exchanged,
                // and cancelled operations are shown together so developers see
                // bounded streaming behavior without a broker or exactly-once claim.
                BatchResult oversized = service.submitBatch(session, List.of("this-item-is-too-large"));
                List<String> firstBatch = service.readBatch(session, 2);
                StreamExchangeResult exchange = service.exchange(session, List.of("d"), 1);
                service.cancel(session);
                StreamMetrics metrics = service.metrics(session);
                System.out.println("phase5.5 streaming accepted: " + accepted.accepted());
                System.out.println("phase5.5 streaming server batch: " + firstBatch);
                System.out.println("phase5.5 streaming exchange server items: " + exchange.serverItems());
                System.out.println("phase5.5 streaming oversized rejected: " + oversized.rejected());
                System.out.println("phase5.5 streaming backpressure: " + backpressure.backpressure());
                System.out.println("phase5.5 streaming metrics: submitted=" + metrics.submitted()
                        + ", returned=" + metrics.returned() + ", rejected=" + metrics.rejected()
                        + ", cancelled=" + metrics.cancelled());
                System.out.println("phase5.5 streaming status: " + service.adminStatus());
                deployer.deactivate(activation.instanceIdentity().instanceId(), new DeactivationOptions(false));
                System.out.println("phase5.5 streaming registry after stop: "
                        + runtime.list("//apps/example/streaming/PipelineService", List.of(StreamingPipelineService.class.getName()),
                                Map.of(), LookupOptions.defaults()).size());
                deployer.unload(activation.instanceIdentity().instanceId(), new UnloadOptions(false));
                System.out.println("phase5.5 streaming active after unload: " + deployer.listActiveApplications(ApplicationQuery.all()).size());
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed to run streaming pipeline example", exception);
        }
    }

    private static void runRegulatedEnterpriseAppExample() {
        try {
            Path workspace = Files.createTempDirectory("j7m-regulated-example-");
            // J7M teaching: regulated verification is demonstrated with
            // accepted and rejected bundles so developers see SBOM,
            // provenance, and signature gates without any certification claim.
            ApplicationBundle unsignedBundle = packageClasspathExample(workspace, "regulated-enterprise-app");
            ApplicationBundle bundle = new DefaultBundleSigner().sign(packageClasspathExample(workspace.resolve("signed"), "regulated-enterprise-app"));
            ApplicationBundleVerifier strictVerifier = new ApplicationBundleVerifier();
            RegulatedExampleVerifier verifier = new RegulatedExampleVerifier();
            RegulatedVerificationResult accepted = verifier.verify(bundle);
            ApplicationBundle missingSbom = copyWithoutEntry(bundle, workspace.resolve("regulated-missing-sbom.j7m"),
                    RegulatedExampleVerifier.SBOM_ENTRY);
            ApplicationBundle missingProvenance = copyWithoutEntry(bundle, workspace.resolve("regulated-missing-provenance.j7m"),
                    org.j7m.middleware.application.repository.J7mBundleReader.PROVENANCE_ENTRY);
            System.out.println("phase5.5 regulated verify accepted: " + accepted.accepted());
            System.out.println("phase5.5 regulated strict verify accepted: "
                    + strictVerifier.verify(bundle, SignaturePolicy.STRICT_REGULATED).accepted());
            System.out.println("phase5.5 regulated unsigned strict accepted: "
                    + strictVerifier.verify(unsignedBundle, SignaturePolicy.STRICT_REGULATED).accepted());
            System.out.println("phase5.5 regulated missing sbom accepted: " + verifier.verify(missingSbom).accepted());
            System.out.println("phase5.5 regulated missing provenance accepted: " + verifier.verify(missingProvenance).accepted());
            System.out.println("phase5.5 regulated invalid signature accepted: "
                    + strictVerifier.verify(copyWithoutEntry(bundle, workspace.resolve("regulated-invalid-signature.j7m"),
                            org.j7m.middleware.application.repository.J7mBundleReader.SIGNATURE_ENTRY),
                    SignaturePolicy.STRICT_REGULATED).accepted());
            EmbeddedRegistryStore store = new EmbeddedRegistryStore();
            try (DefaultMiddlewareRuntime runtime = runtime("demo-regulated-enterprise-example", store, "grpc")) {
                // J7M teaching: the signed bundle is published under the
                // strict-regulated profile, then activated to collect safe
                // compliance, crypto, and audit evidence.
                LocalApplicationRepository repository = new LocalApplicationRepository(workspace.resolve(".j7m-repository"));
                ApplicationManager manager = new ApplicationManager(runtime, repository);
                DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
                repository.publish(bundle, new RepositoryPublishOptions("demo", true, "strict-regulated"));
                ApplicationActivation activation = deployer.activate(ApplicationCoordinate.parse("org.j7m.examples:regulated-enterprise-app:1.0.0"),
                        new ApplicationActivationRequest("demo-operator", "prod", true, Map.of("verificationProfile", "strict-regulated")));
                RegulatedEnterpriseService service = runtime.lookup("//apps/example/regulated/EnterpriseService",
                        RegulatedEnterpriseService.class, LookupOptions.defaults());
                ComplianceSummary summary = service.complianceSummary();
                EvidenceReport report = service.exportEvidence();
                ExampleCryptoPosture posture = service.cryptoPosture();
                String controlled = SecurityContexts.call(context("auditor", "dev", true, Set.of("admin")),
                        () -> service.processControlledRequest("request-123"));
                AuditVerificationResult audit = service.verifyAuditTrail();
                System.out.println("phase5.5 regulated evidence controls: " + report.controls().size());
                System.out.println("phase5.5 regulated crypto strict: " + posture.strictMode());
                System.out.println("phase5.5 regulated audit valid: " + audit.valid());
                System.out.println("phase5.5 regulated controlled result: " + controlled);
                System.out.println("phase5.5 regulated certification claim: " + summary.certificationClaim());
                deployer.deactivate(activation.instanceIdentity().instanceId(), new DeactivationOptions(false));
                System.out.println("phase5.5 regulated registry after stop: "
                        + runtime.list("//apps/example/regulated/EnterpriseService", List.of(RegulatedEnterpriseService.class.getName()),
                                Map.of(), LookupOptions.defaults()).size());
                deployer.unload(activation.instanceIdentity().instanceId(), new UnloadOptions(false));
                System.out.println("phase5.5 regulated active after unload: " + deployer.listActiveApplications(ApplicationQuery.all()).size());
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed to run regulated enterprise app example", exception);
        }
    }

    private static void runApplicationDeploymentDemo() {
        try {
            Path workspace = Files.createTempDirectory("j7m-app-demo-");
            Path bundlePath = workspace.resolve("hello-remote-service-1.0.0.j7m");
            Path repositoryPath = workspace.resolve(".j7m-repository");
            Path manifestPath = resolveProjectPath("examples/deployable-application/META-INF/j7m-application.json");
            Path readmePath = resolveProjectPath("examples/deployable-application/README.md");
            Path exampleJar = resolveProjectPath("j7m-examples/build/libs/j7m-examples-0.1.0-SNAPSHOT.jar");
            EmbeddedRegistryStore store = new EmbeddedRegistryStore();
            try (DefaultMiddlewareRuntime runtime = runtime("demo-application-runtime", store, "grpc")) {
                // J7M teaching: this Phase 5.2 flow shows local packaging,
                // development verification, strict unsigned rejection, deploy,
                // activation, lookup, and cleanup as one copyable lifecycle.
                LocalApplicationRepository repository = new LocalApplicationRepository(repositoryPath);
                ApplicationManager manager = new ApplicationManager(runtime, repository);
                DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
                ApplicationBundle bundle = deployer.packageApplication(new PackageRequest(manifestPath, bundlePath, List.of(exampleJar), List.of(readmePath)));
                System.out.println("phase5.2 packaged bundle: " + bundle.path().getFileName());
                System.out.println("phase5.2 verify accepted: " + deployer.verify(bundle, VerificationOptions.development()).accepted());
                System.out.println("phase5.2 strict unsigned accepted: " + deployer.verify(bundle, new VerificationOptions(true)).accepted());
                deployer.deploy(bundle, new DeployOptions("demo", false));
                ApplicationCoordinate coordinate = ApplicationCoordinate.parse("org.j7m.examples:hello-remote-service:1.0.0");
                System.out.println("phase5.2 activation plan steps: " + deployer.planActivation(coordinate, null).steps().size());
                ApplicationActivation activation = deployer.activate(coordinate,
                        new ApplicationActivationRequest("demo-operator", "dev", false, Map.of("greeting", "hello")));
                HelloService hello = runtime.lookup("//apps/example/hello/HelloService", HelloService.class, LookupOptions.defaults());
                System.out.println("phase5.2 deployed hello: " + hello.hello("platform"));
                System.out.println("phase5.2 active applications: " + deployer.listActiveApplications(ApplicationQuery.all()).size());
                System.out.println("phase5.2 activation state: " + activation.state());
                deployer.deactivate(activation.instanceIdentity().instanceId(), new DeactivationOptions(false));
                System.out.println("phase5.2 registry after stop: "
                        + runtime.list("//apps/example/hello/HelloService", List.of(HelloService.class.getName()), Map.of(), LookupOptions.defaults()).size());
                deployer.unload(activation.instanceIdentity().instanceId(), new UnloadOptions(false));
                System.out.println("phase5.2 active after unload: " + deployer.listActiveApplications(ApplicationQuery.all()).size());
                System.out.println("phase5.2 repository artifacts: " + repository.status().artifactCount());
                System.out.println("phase5.2 non-goals: no durable messaging, no consensus, no exactly-once, no distributed transactions");
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed to run application deployment demo", exception);
        }
    }

    private static void runModularApplicationDeploymentDemo() {
        try {
            Path workspace = Files.createTempDirectory("j7m-modular-app-demo-");
            Path repositoryPath = workspace.resolve(".j7m-repository");
            ApplicationBundle bundle = packageModularBundle(workspace, "1.0.0");
            EmbeddedRegistryStore store = new EmbeddedRegistryStore();
            try (DefaultMiddlewareRuntime runtime = runtime("demo-modular-application-runtime", store, "grpc")) {
                // J7M teaching: JPMS activation validates a ModuleLayer-backed
                // application while preserving the same ObjectRef lookup
                // semantics as the classpath deployment example.
                LocalApplicationRepository repository = new LocalApplicationRepository(repositoryPath);
                ApplicationManager manager = new ApplicationManager(runtime, repository);
                DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
                System.out.println("phase5.2 jpms verify accepted: " + deployer.verify(bundle, VerificationOptions.development()).accepted());
                deployer.deploy(bundle, new DeployOptions("demo", false));
                ApplicationCoordinate coordinate = ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.0.0");
                ApplicationActivation activation = deployer.activate(coordinate,
                        new ApplicationActivationRequest("demo-operator", "dev", false, Map.of()));
                Class<?> api = manager.serviceInterface(activation.instanceIdentity().instanceId(),
                        "com.example.j7m.modular.hello.api.ModularHelloService");
                Object proxy = runtime.lookup("//apps/example/modular/hello/ModularHelloService", api, LookupOptions.defaults());
                Object result = api.getMethod("hello", String.class).invoke(proxy, "jpms");
                System.out.println("phase5.2 jpms hello: " + result);
                System.out.println("phase5.2 jpms module mode: " + activation.metadata().get("moduleMode"));
                System.out.println("phase5.2 jpms module layer: " + activation.metadata().get("moduleLayer"));
                System.out.println("phase5.2 jpms modules: " + activation.metadata().get("modules"));
                deployer.deactivate(activation.instanceIdentity().instanceId(), new DeactivationOptions(false));
                deployer.unload(activation.instanceIdentity().instanceId(), new UnloadOptions(false));
                System.out.println("phase5.2 jpms active after unload: " + deployer.listActiveApplications(ApplicationQuery.all()).size());
            }
        } catch (ReflectiveOperationException | java.io.IOException exception) {
            throw new IllegalStateException("failed to run modular application demo", exception);
        }
    }

    private static void runApplicationUpgradeRollbackDemo() {
        try {
            Path workspace = Files.createTempDirectory("j7m-upgrade-demo-");
            ApplicationBundle v1 = packageModularBundle(workspace, "1.0.0");
            ApplicationBundle v2 = packageModularBundle(workspace, "1.1.0");
            EmbeddedRegistryStore store = new EmbeddedRegistryStore();
            try (DefaultMiddlewareRuntime runtime = runtime("demo-upgrade-runtime", store, "grpc")) {
                // J7M teaching: stop-start upgrade and rollback are shown with
                // two immutable bundles so developers can observe versioned
                // activation without assuming live code mutation.
                LocalApplicationRepository repository = new LocalApplicationRepository(workspace.resolve(".j7m-repository"));
                ApplicationManager manager = new ApplicationManager(runtime, repository);
                DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
                deployer.deploy(v1, new DeployOptions("demo", false));
                deployer.deploy(v2, new DeployOptions("demo", false));
                ApplicationActivation activation = deployer.activate(ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.0.0"),
                        new ApplicationActivationRequest("demo-operator", "dev", false, Map.of()));
                ApplicationDeploymentResult upgrade = deployer.upgrade(activation.instanceIdentity().instanceId(),
                        ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.1.0"), UpgradeOptions.stopStart());
                ApplicationActivation upgraded = deployer.listActiveApplications(new ApplicationQuery(null, ApplicationRunState.RUNNING)).getLast();
                Class<?> api = manager.serviceInterface(upgraded.instanceIdentity().instanceId(),
                        "com.example.j7m.modular.hello.api.ModularHelloService");
                Object proxy = runtime.lookup("//apps/example/modular/hello/ModularHelloService", api, LookupOptions.defaults());
                System.out.println("phase5.4 upgrade result: " + upgrade.success());
                System.out.println("phase5.4 upgraded hello: " + api.getMethod("hello", String.class).invoke(proxy, "upgrade"));
                ApplicationDeploymentResult rollback = deployer.rollback(upgraded.instanceIdentity().instanceId(), new RollbackOptions(""));
                System.out.println("phase5.4 rollback result: " + rollback.success());
                System.out.println("phase5.4 upgrade history: " + manager.upgradeHistory().size());
                // J7M teaching: unsupported strategy rejection is part of the
                // behavior; developers should not infer arbitrary orchestrator
                // strategies from the local demo.
                ApplicationActivation restored = deployer.listActiveApplications(new ApplicationQuery(null, ApplicationRunState.RUNNING)).getLast();
                MiddlewareException unsupported = null;
                try {
                    deployer.upgrade(restored.instanceIdentity().instanceId(),
                            ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.1.0"), new UpgradeOptions("unsupported"));
                } catch (MiddlewareException exception) {
                    unsupported = exception;
                }
                System.out.println("phase5.4 unknown strategy rejected: " + (unsupported == null ? "none" : unsupported.code()));
            }
        } catch (ReflectiveOperationException | java.io.IOException exception) {
            throw new IllegalStateException("failed to run upgrade rollback demo", exception);
        }
    }

    private static void runUpgradeStrategyDemo(final String label, final UpgradeOptions options) {
        try {
            Path workspace = Files.createTempDirectory("j7m-" + label + "-demo-");
            ApplicationBundle v1 = packageModularBundle(workspace, "1.0.0");
            ApplicationBundle v2 = packageModularBundle(workspace, "1.1.0");
            EmbeddedRegistryStore store = new EmbeddedRegistryStore();
            try (DefaultMiddlewareRuntime runtime = runtime("demo-" + label + "-runtime", store, "grpc")) {
                // J7M teaching: named strategy demos reuse the same bounded
                // local upgrade engine so status and rollback evidence are
                // comparable across rolling, blue-green, and canary-baseline modes.
                LocalApplicationRepository repository = new LocalApplicationRepository(workspace.resolve(".j7m-repository"));
                ApplicationManager manager = new ApplicationManager(runtime, repository);
                DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
                deployer.deploy(v1, new DeployOptions("demo", false));
                deployer.deploy(v2, new DeployOptions("demo", false));
                ApplicationActivation baseline = deployer.activate(ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.0.0"),
                        new ApplicationActivationRequest("demo-operator", "dev", false, Map.of()));
                ApplicationDeploymentResult upgrade = deployer.upgrade(baseline.instanceIdentity().instanceId(),
                        ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.1.0"), options);
                ApplicationActivation selected = deployer.listActiveApplications(new ApplicationQuery(null, ApplicationRunState.RUNNING)).stream()
                        .filter(candidate -> "true".equals(candidate.metadata().get("selectedActiveVersion")))
                        .findFirst().orElseThrow();
                System.out.println("phase5.4 " + label + " upgrade result: " + upgrade.success());
                System.out.println("phase5.4 " + label + " selected version: "
                        + selected.instanceIdentity().application().coordinate().version().value());
                System.out.println("phase5.4 " + label + " status: " + manager.applicationStatus());
                if (upgrade.success()) {
                    ApplicationDeploymentResult rollback = deployer.rollback(selected.instanceIdentity().instanceId(), new RollbackOptions(""));
                    System.out.println("phase5.4 " + label + " rollback result: " + rollback.success());
                } else {
                    System.out.println("phase5.4 " + label + " rollback result: false");
                }
                for (ApplicationActivation activation : deployer.listActiveApplications(ApplicationQuery.all())) {
                    if (activation.state() == ApplicationRunState.RUNNING) {
                        deployer.deactivate(activation.instanceIdentity().instanceId(), new DeactivationOptions(false));
                    }
                    if (activation.state() != ApplicationRunState.UNLOADED) {
                        deployer.unload(activation.instanceIdentity().instanceId(), new UnloadOptions(false));
                    }
                }
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed to run upgrade strategy demo", exception);
        }
    }

    private static void runApplicationFailureDiagnosticsDemo() {
        try {
            Path workspace = Files.createTempDirectory("j7m-failure-diagnostics-demo-");
            // J7M teaching: this intentionally corrupts application metadata
            // so activation fails with stable diagnostics, safe messages, and
            // registry cleanup instead of leaking internal stack details.
            Path source = "1.1.0".equals("1.1.0")
                    ? resolveProjectPath("examples/modular-deployable-application/META-INF/j7m-application-1.1.0.json")
                    : resolveProjectPath("examples/modular-deployable-application/META-INF/j7m-application.json");
            Path manifest = workspace.resolve("bad-manifest.json");
            Files.writeString(manifest, Files.readString(source)
                    .replace("com.example.j7m.modular.hello.impl.ModularHelloApplication", "java.lang.String")
                    .replace("\"version\": \"1.1.0\"", "\"version\": \"9.9.9\""));
            Path apiJar = resolveProjectPath("examples/modular-deployable-application/api/build/libs/com.example.j7m.modular.hello.api.jar");
            Path implJar = resolveProjectPath("examples/modular-deployable-application/impl/build/libs/com.example.j7m.modular.hello.impl.jar");
            Path readme = resolveProjectPath("examples/modular-deployable-application/README.md");
            ApplicationBundle bad = new org.j7m.middleware.application.repository.J7mBundleWriter().write(manifest,
                    workspace.resolve("bad.j7m"), List.of(), List.of(apiJar, implJar), List.of(readme));
            EmbeddedRegistryStore store = new EmbeddedRegistryStore();
            try (DefaultMiddlewareRuntime runtime = runtime("demo-failure-diagnostics-runtime", store, "grpc")) {
                LocalApplicationRepository repository = new LocalApplicationRepository(workspace.resolve(".j7m-repository"));
                ApplicationManager manager = new ApplicationManager(runtime, repository);
                repository.publish(bad, new RepositoryPublishOptions("demo", false));
                try {
                    manager.activate(bad.coordinate(), new ApplicationActivationRequest("demo-operator", "dev", false, Map.of()));
                } catch (MiddlewareException exception) {
                    System.out.println("phase5.4 failure diagnostics code: " + exception.code());
                }
                ApplicationActivation failed = manager.list(new ApplicationQuery(null, ApplicationRunState.FAILED)).getFirst();
                System.out.println("phase5.4 failure diagnostics safe stage: " + failed.metadata().get("stage"));
                System.out.println("phase5.4 failure diagnostics safe message: " + failed.metadata().get("safeMessage"));
                System.out.println("phase5.4 failure diagnostics registry cleaned: " + failed.metadata().get("registryRecordsCleanedCount"));
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed to run failure diagnostics demo", exception);
        }
    }

    private static void runApplicationAdminStatusDemo() {
        try {
            Path workspace = Files.createTempDirectory("j7m-admin-status-demo-");
            ApplicationBundle v1 = packageModularBundle(workspace, "1.0.0");
            ApplicationBundle v2 = packageModularBundle(workspace, "1.1.0");
            EmbeddedRegistryStore store = new EmbeddedRegistryStore();
            try (DefaultMiddlewareRuntime runtime = runtime("demo-admin-status-runtime", store, "grpc")) {
                // J7M teaching: the admin-status flow produces operator-facing
                // evidence about active versions, repository verification
                // status, audit counters, and upgrade history without exposing secrets.
                LocalApplicationRepository repository = new LocalApplicationRepository(workspace.resolve(".j7m-repository"));
                ApplicationManager manager = new ApplicationManager(runtime, repository);
                DefaultApplicationDeployer deployer = new DefaultApplicationDeployer(repository, manager);
                deployer.deploy(v1, new DeployOptions("demo", false));
                deployer.deploy(v2, new DeployOptions("demo", false));
                ApplicationActivation activation = deployer.activate(ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.0.0"),
                        new ApplicationActivationRequest("demo-operator", "dev", false, Map.of()));
                deployer.upgrade(activation.instanceIdentity().instanceId(),
                        ApplicationCoordinate.parse("org.j7m.examples:modular-hello-service:1.1.0"), UpgradeOptions.rolling());
                System.out.println("phase5.4 admin status: " + manager.applicationStatus());
                System.out.println("phase5.4 active applications: " + deployer.listActiveApplications(ApplicationQuery.all()).size());
                System.out.println("phase5.4 historical applications: " + manager.historicalApplications().size());
                System.out.println("phase5.4 upgrade history: " + manager.upgradeHistory());
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed to run admin status demo", exception);
        }
    }

    private static void runComplianceDemo() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        TamperEvidentAuditSink audit = new TamperEvidentAuditSink(64, 2);
        SecurityConfiguration security = new SecurityConfiguration(SecurityMode.DEV, new AllowAllAuthorizationPolicy(), audit,
                new DefaultRedactor(), true, true, 1024 * 1024, 1024, Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("demo", "5", Map.of("fingerprint", "safe-demo-fingerprint"))));
        try (DefaultMiddlewareRuntime runtime = runtime("demo-compliance", store, "grpc", security)) {
            // J7M teaching: compliance evidence is assembled from runtime
            // status, crypto posture, release metadata, and tamper-evident
            // audit records; it supports review, not certification.
            SecurityContexts.run(context("publisher", "default", true, Set.of("admin")),
                    () -> runtime.export("demo.compliance", new DemoGreeter("compliance"), Greeter.class, export("grpc")));
            audit.record(new org.j7m.middleware.security.api.AuditEvent(Instant.now(), "demo-compliance", "auditor",
                    "compliance-report", "phase5", "allowed", "allow-all", "phase5", "phase5-invoke",
                    "local", "evidence collection", "grpc", "default", Map.of("note", "<redacted>")));

            CryptoPolicy cryptoPolicy = new CryptoPolicy(
                    new CryptoProviderPolicy(Set.of("AES/GCM/NoPadding", "SHA-256"), Set.of(), true),
                    new FipsModePolicy(false, ""),
                    new KeyRotationPolicy(Duration.ofDays(90L), true));
            CryptoPostureReport crypto = new CryptoPolicyEvaluator().report(cryptoPolicy, new TestKeyManagementProvider());
            ReleaseAttestationMetadata release = new ReleaseAttestationMetadata("sbom/demo.spdx.json", "SPDX", "sha256:demo",
                    "provenance/demo", "working-tree", Instant.now(), "demo-signer", "not-evaluated", "vex/demo",
                    Map.of("attestationSignature", "deterministic-trusted"));
            OperationalResilienceEvidence resilience = new OperationalResilienceEvidence(Duration.ofMinutes(15L), Duration.ofMinutes(5L),
                    "ha-hp-enabled", "embedded+bootstrap+multicast", "external-hook", "last-demo-failover=passed",
                    "payload/concurrency/overload limits enabled", "dependencies healthy in demo", "docs/final-acceptance.md",
                    Instant.now(), Map.of("scope", "demo"));
            ValidationSummary validation = new ValidationSummary(Instant.now(), 71L, 69L, 2L, 0L,
                    Map.of("udpSkipped", "loopback multicast host capability"));
            org.j7m.middleware.api.RuntimeStatus status = SecurityContexts.call(context("auditor", "default", true, Set.of("admin")),
                    runtime::status);
            RuntimeEvidenceCollector collector = new RuntimeEvidenceCollector(status,
                    List.of("j7m-api", "j7m-runtime", "j7m-runtime", "j7m-runtime",
                            "j7m-runtime"),
                    release, crypto, resilience, validation);
            DefaultComplianceReportService reports = new DefaultComplianceReportService();
            ComplianceReport report = reports.createReport(BuiltInComplianceProfiles.profileById("regulated-baseline"),
                    new ComplianceScope("demo", List.of("demo-compliance"), List.of("j7m-demo"), "phase5 compliance demo"),
                    collector.collect(ComplianceScope.local()));
            String markdown = new DefaultEvidenceExporter().export(report, EvidenceExportFormat.MARKDOWN);
            ExternalPdpAuthorizationPolicy pdp = new ExternalPdpAuthorizationPolicy(new DeterministicPdpClient(Set.of("blocked-admin")));
            AuthorizationDecision allowed = pdp.evaluate(context("auditor", "default", true, Set.of("admin")),
                    new PermissionRequest(PermissionAction.ADMIN, "allowed-admin", "default", "demo-compliance", "",
                            "", "", "", Map.of()));
            AuthorizationDecision denied = pdp.evaluate(context("auditor", "default", true, Set.of("admin")),
                    new PermissionRequest(PermissionAction.ADMIN, "blocked-admin", "default", "demo-compliance", "",
                            "", "", "", Map.of()));
            System.out.println("phase5 compliance summary: " + new ComplianceAdmin().format(report));
            System.out.println("phase5 audit chain valid: " + audit.verify().valid() + ", checkpoints="
                    + audit.chain().stream().filter(record -> !record.checkpointHash().isBlank()).count());
            System.out.println("phase5 external pdp allowed=" + allowed.allowed() + ", denied=" + denied.allowed());
            System.out.println("phase5 crypto posture: strict=" + crypto.strictMode() + ", kms=" + crypto.keyProviderStatus());
            System.out.println("phase5 markdown report lines=" + markdown.lines().count());
            System.out.println("phase5 certification claim: false; evidence support only");
        }
    }

    private static void runTransport(final String transport) {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        try (DefaultMiddlewareRuntime runtimeA = runtime("demo-a-" + transport, store, transport);
             DefaultMiddlewareRuntime runtimeB = runtime("demo-b-" + transport, store, transport)) {
            // J7M teaching: the legacy transport flow exports the same service
            // through gRPC and RMI so developers see ObjectRef and callback
            // behavior without tying business code to one transport.
            runtimeA.export("demo.greeter", new DemoGreeter("first"), Greeter.class, export(transport));
            ObjectRegistration<Greeter> second = runtimeB.export("demo.greeter", new DemoGreeter("second"), Greeter.class, export(transport));

            Greeter greeter = runtimeB.lookup("demo.greeter", Greeter.class, LookupOptions.highAvailabilityOptions());
            RecordingListener listener = new RecordingListener();
            greeter.register(listener);
            greeter.fire("callback over " + transport);
            System.out.println("real " + transport + " greeting: " + greeter.greet("client"));
            System.out.println("real " + transport + " callback messages: " + listener.messages);

            // J7M teaching: closing one registration demonstrates HA failover
            // at the runtime lookup layer; it does not prove consensus,
            // durable messaging, or exactly-once behavior.
            second.close();
            System.out.println("real " + transport + " after failover: " + greeter.greet("client"));
            System.out.println(new RuntimeAdmin(runtimeA).formatStatus());
            System.out.println(new RuntimeAdmin(runtimeB).formatStatus());
        }
    }

    private static void runMtlsSecurityDemo() {
        try {
            Path directory = Files.createTempDirectory("j7m-mtls-demo-");
            GrpcTestCertificateMaterial serverMaterial = GrpcTestCertificateMaterial.generate(directory.resolve("server"), "localhost");
            GrpcTestCertificateMaterial clientMaterial = GrpcTestCertificateMaterial.generate(directory.resolve("client"), "localhost");
            GrpcTlsConfiguration serverTls = new GrpcTlsConfiguration(serverMaterial.certificate(), serverMaterial.privateKey(),
                    clientMaterial.certificate(), true, "default", "localhost");
            GrpcTlsConfiguration clientTls = new GrpcTlsConfiguration(clientMaterial.certificate(), clientMaterial.privateKey(),
                    serverMaterial.certificate(), true, "default", "localhost");
            GrpcTlsConfiguration untrustedTls = new GrpcTlsConfiguration(clientMaterial.certificate(), clientMaterial.privateKey(),
                    clientMaterial.certificate(), true, "default", "localhost");
            EmbeddedRegistryStore store = new EmbeddedRegistryStore();
            InMemoryAuditSink audit = new InMemoryAuditSink(64);
            SecurityConfiguration security = new SecurityConfiguration(SecurityMode.PRODUCTION, new AllowAllAuthorizationPolicy(), audit,
                    new DefaultRedactor(), true, false, 1024 * 1024, 1024, Duration.ofSeconds(2L),
                    new MutableCredentialProvider(new Credential("x509", "demo", Map.of("fingerprint", "test-material"))),
                    false, Duration.ofSeconds(30L), 4096, true);
            try (DefaultMiddlewareRuntime server = runtime("demo-mtls-server", store,
                    new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(true, serverTls)), security);
                 DefaultMiddlewareRuntime client = runtime("demo-mtls-client", store,
                         new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(true, clientTls)), security);
                 DefaultMiddlewareRuntime untrusted = runtime("demo-mtls-untrusted", store,
                         new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(true, untrustedTls)), SecurityDefaults.development())) {
                // J7M teaching: trusted and untrusted TLS materials are both
                // exercised so developers can see mTLS admission and audit
                // evidence without copying test certificates into production.
                SecurityContexts.run(context("publisher", "default", true, Set.of("admin")),
                        () -> server.export("demo.mtls.secure", new SecureGreeterImpl(), SecureGreeter.class, export("grpc")));
                SecureGreeter greeter = SecurityContexts.call(context("caller", "default", true, Set.of("admin")),
                        () -> client.lookup("demo.mtls.secure", SecureGreeter.class, LookupOptions.defaults()));
                RecordingListener listener = new RecordingListener();
                SecurityContexts.run(context("caller", "default", true, Set.of("admin")), () -> {
                    greeter.register(listener);
                    greeter.fire("mtls callback");
                });
                System.out.println("phase3.1 mtls greeting: "
                        + SecurityContexts.call(context("caller", "default", true, Set.of("admin")), () -> greeter.greet("client")));
                System.out.println("phase3.1 mtls callback messages: " + listener.messages);
                try {
                    untrusted.lookup("demo.mtls.secure", SecureGreeter.class, LookupOptions.defaults());
                } catch (MiddlewareException exception) {
                    System.out.println("phase3.1 rejected untrusted mtls client: " + exception.code());
                }
                System.out.println("phase3.1 mtls audit events=" + audit.eventCount());
                System.out.println(SecurityContexts.call(context("admin", "default", true, Set.of("admin")),
                        () -> new RuntimeAdmin(server).formatStatus()));
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed to run mtls demo", exception);
        }
    }

    private static void runSecurityDemo() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        SecurityConfiguration security = new SecurityConfiguration(SecurityMode.DEV, new AllowAllAuthorizationPolicy(), audit,
                new DefaultRedactor(), true, true, 1024 * 1024, 1024, Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("demo", "1", Map.of())));
        try (DefaultMiddlewareRuntime runtimeA = runtime("demo-security-a", store, "grpc", security)) {
            // J7M teaching: authenticated security context is explicit around
            // export, lookup, callback, and sensitive operation calls, which
            // demonstrates caller identity propagation.
            ObjectRegistration<SecureGreeter> registration = SecurityContexts.call(context("publisher", "default", true, Set.of("admin")),
                    () -> runtimeA.export("demo.secure", new SecureGreeterImpl(), SecureGreeter.class, export("grpc")));

            SecureGreeter greeter = SecurityContexts.call(context("caller", "default", true, Set.of("user")),
                    () -> runtimeA.proxyFor(registration.ref(), SecureGreeter.class));
            RecordingListener listener = new RecordingListener();
            SecurityContexts.run(context("caller", "default", true, Set.of("user")), () -> {
                greeter.register(listener);
                greeter.fire("security callback");
            });
            System.out.println("phase3 authenticated grpc greeting: "
                    + SecurityContexts.call(context("caller", "default", true, Set.of("user")), () -> greeter.greet("client")));
            System.out.println("phase3 callback messages under security: " + listener.messages);
            System.out.println("phase3 sensitive operation: "
                    + SecurityContexts.call(context("caller", "default", true, Set.of("user")), () -> greeter.storeSecret("do-not-print")));
            try {
                greeter.greet("anonymous");
            } catch (MiddlewareException exception) {
                System.out.println("phase3 denied unauthenticated call: " + exception.code());
            }
            // J7M teaching: the sensitive method intentionally sends a raw
            // secret through the security path, then checks audit output so
            // developers see redaction as an observable contract.
            boolean redacted = audit.recentEvents().toString().contains("<redacted>")
                    && !audit.recentEvents().toString().contains("do-not-print");
            System.out.println("phase3 audit events=" + audit.eventCount() + ", sensitiveRedacted=" + redacted);
            System.out.println(SecurityContexts.call(context("admin", "default", true, Set.of("admin")),
                    () -> new RuntimeAdmin(runtimeA).formatStatus()));
        }
        try {
            new MiddlewareRuntimeBuilder()
                    .runtimeId("demo-security-prod-reject")
                    .registry(new EmbeddedRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC()))
                    .security(new SecurityConfiguration(SecurityMode.PRODUCTION, new AllowAllAuthorizationPolicy(), new InMemoryAuditSink(8),
                            new DefaultRedactor(), true, false, 1024 * 1024, 1024, Duration.ofSeconds(2L),
                            new MutableCredentialProvider(new Credential("demo", "1", Map.of()))))
                    .addTransport(new GrpcTransportProvider(GrpcSecurityOptions.production()))
                    .disableNuggetAdmin()
                    .build()
                    .close();
        } catch (MiddlewareException exception) {
            // J7M teaching: production mode rejects insecure transport setup,
            // showing a configuration guard rather than a runtime fallback.
            if (exception.code() == MiddlewareErrorCode.TRANSPORT_ERROR) {
                System.out.println("phase3 production insecure grpc rejected: " + exception.code());
            }
        }
    }

    private static void runMulticastAndHp() {
        MulticastRegistryBus bus = new MulticastRegistryBus();
        try (DefaultMiddlewareRuntime runtimeA = runtime("demo-mcast-a", multicastRegistry("demo-mcast-a", bus), "grpc");
             DefaultMiddlewareRuntime runtimeB = runtime("demo-mcast-b", multicastRegistry("demo-mcast-b", bus), "grpc");
             DefaultMiddlewareRuntime runtimeC = runtime("demo-mcast-c", multicastRegistry("demo-mcast-c", bus), "grpc")) {
            // J7M teaching: multicast discovery publishes multiple candidates
            // and HP metadata, then the client observes selection and failover
            // without assuming an external registry service.
            runtimeA.export("demo.greeter", new DemoGreeter("slow"), Greeter.class,
                    export("grpc", Map.of("role", "primary", "load", "5.0", "latencyNanos", "9000000")));
            ObjectRegistration<Greeter> fast = runtimeB.export("demo.greeter", new DemoGreeter("fast"), Greeter.class,
                    export("grpc", Map.of("role", "primary", "load", "0.0", "latencyNanos", "1000")));

            List<ObjectRef> refs = runtimeC.list("demo.greeter", List.of(Greeter.class.getName()), Map.of("role", "primary"), LookupOptions.defaults());
            Greeter greeter = runtimeC.lookup("demo.greeter", Greeter.class,
                    new LookupOptions("default", Map.of("role", "primary"), Duration.ofSeconds(5L), true, true));

            System.out.println("phase2 multicast candidates: " + refs.size());
            System.out.println("phase2 hp selected greeting: " + greeter.greet("client"));
            fast.close();
            System.out.println("phase2 hp/ha after failover: " + greeter.greet("client"));
            System.out.println(new RuntimeAdmin(runtimeC).formatStatus());
        }
    }

    private static void runBootstrapFallback() {
        EmbeddedRegistryClient registryA = new EmbeddedRegistryClient();
        RegistryClient registryB = new SigningRegistryClient(new EmbeddedRegistryClient(), DEMO_REGISTRY_TRUST);
        InMemoryBootstrapEndpointResolver resolver = new InMemoryBootstrapEndpointResolver();
        resolver.register("bootstrap-a", registryA);
        resolver.register("bootstrap-b", registryB);
        registryA.updatePeer(new RegistryPeerStatus("bootstrap-b", true, RegistryPeerState.ALIVE, false,
                RegistryPeerQuality.FAST, false, RegistryTrustStatus.UNVERIFIED, System.currentTimeMillis(), 100_000L,
                0.0D, 1.0D, List.of(new RegistryEndpoint("bootstrap", "bootstrap-b", Map.of())),
                List.of(1), List.of("default"), "demo", System.currentTimeMillis(), Map.of()));
        // J7M teaching: bootstrap fallback seeds discovery from known peers,
        // then normal runtime lookup proves the bootstrap path remains
        // metadata-driven rather than hardcoded service wiring.
        BootstrapRegistryClient bootstrap = new BootstrapRegistryClient(new EmbeddedRegistryClient(), Clock.systemUTC(),
                new BootstrapRegistryOptions("demo-bootstrap-client", "default", List.of("bootstrap-a"),
                        Duration.ofSeconds(2L), 2, 8, 64, Duration.ofSeconds(2L), resolver),
                DEMO_REGISTRY_TRUST, true, null);
        try (DefaultMiddlewareRuntime runtimeB = runtime("demo-bootstrap-b", registryB, "grpc");
             DefaultMiddlewareRuntime runtimeC = runtime("demo-bootstrap-c", bootstrap, "grpc")) {
            runtimeB.export("demo.bootstrap.greeter", new DemoGreeter("bootstrap"), Greeter.class, export("grpc"));
            Greeter greeter = runtimeC.lookup("demo.bootstrap.greeter", Greeter.class, LookupOptions.defaults());

            System.out.println("phase2 bootstrap fallback greeting: " + greeter.greet("client"));
            System.out.println(new RuntimeAdmin(runtimeC).formatStatus());
        }
    }

    private static void runWorkloadDemo() {
        InMemoryAuditSink audit = new InMemoryAuditSink(64);
        SecurityConfiguration security = new SecurityConfiguration(SecurityMode.DEV, new AllowAllAuthorizationPolicy(), audit,
                new DefaultRedactor(), false, true, 1024 * 1024, 1024 * 1024, 4, Duration.ofSeconds(2L),
                new MutableCredentialProvider(new Credential("demo", "phase4", Map.of())),
                false, Duration.ofSeconds(30L), 4096, false, null);
        try (InMemoryWorkloadScheduler scheduler = new InMemoryWorkloadScheduler(new InMemorySchedulerOptions("demo-workload",
                "demo-workload-runtime", "default", Clock.systemUTC(), security, 32, 4, 4, 1024, Duration.ofSeconds(10L)))) {
            SecurityContexts.run(context("workload-demo", "default", true, Set.of("user")), () -> {
            TaskSubmission submission = workloadSubmission("phase4.task");
            WorkloadHandle handle = scheduler.submit(submission);
            LeaseOwner owner = new LeaseOwner("demo-workload-runtime", "worker-1", Map.of());
            // J7M teaching: task leasing and fencing are the central safety
            // concept here; stale completion is rejected so developers know
            // to carry fencing tokens through worker side effects.
            TaskLease lease = scheduler.acquireLease(handle.ref().taskId(), owner, Duration.ofSeconds(10L));
            scheduler.reportProgress(handle.ref().taskId(), owner, lease.fencingToken(),
                    new TaskProgress(25.0D, "started", Instant.now(), Map.of("stage", "load")));
            scheduler.checkpoint(handle.ref().taskId(), owner, lease.fencingToken(), Map.of("checkpoint", "one"));
            scheduler.complete(handle.ref().taskId(), owner, lease.fencingToken(), "task-complete");
            try {
                scheduler.complete(handle.ref().taskId(), owner, new FencingToken(0L), "stale");
            } catch (MiddlewareException exception) {
                System.out.println("phase4 stale fencing rejected: " + exception.code());
            }
            System.out.println("phase4 task state: " + scheduler.status(handle.ref().taskId()).state());

            CountDownLatch oneWayRan = new CountDownLatch(1);
            TaskRef oneWay = scheduler.submitOneWay(workloadSubmission("phase4.one-way"), (context, cancellationToken) -> {
                oneWayRan.countDown();
                return "one-way-observed";
            });
            // J7M teaching: async, one-way, stream, fan-out, and scatter-gather
            // are demonstrated as scheduler patterns with bounded local state,
            // not as durable queue, consensus, or exactly-once guarantees.
            await(oneWayRan);
            System.out.println("phase4 one-way state: " + scheduler.status(oneWay.taskId()).state());

            Object async = scheduler.submitAsync(workloadSubmission("phase4.async"), (context, cancellationToken) -> "async-result")
                    .await(Duration.ofSeconds(2L));
            System.out.println("phase4 async result: " + async);

            try (StreamHandle<String> stream = scheduler.stream(workloadSubmission("phase4.stream"), (context, cancellationToken, emitter) -> {
                emitter.emit("chunk-1", Duration.ofSeconds(1L));
                emitter.emit("chunk-2", Duration.ofSeconds(1L));
            })) {
                System.out.println("phase4 stream first: " + stream.poll(Duration.ofSeconds(2L)).orElse("missing"));
                System.out.println("phase4 stream second: " + stream.poll(Duration.ofSeconds(2L)).orElse("missing"));
            }

            ObjectRef left = workloadRef("left", "0.0");
            ObjectRef right = workloadRef("right", "1.0");
            ShardRoute route = new StableHashShardRoutingStrategy().route(new ShardKey("customer-42"), List.of(left, right));
            System.out.println("phase4 shard route: " + route.shardId() + " -> " + route.target().objectId());
            System.out.println("phase4 fan-out successes: "
                    + scheduler.fanOut(new FanOutRequest(List.of(left, right), 2, Duration.ofSeconds(2L), Duration.ofSeconds(1L), Map.of()),
                            candidate -> "ok-" + candidate.objectId()).successes().size());
            System.out.println("phase4 scatter partial: "
                    + scheduler.scatterGather(new ScatterGatherRequest(List.of(left, right), 2, Duration.ofSeconds(2L), true, Map.of()),
                            candidate -> {
                                if ("right".equals(candidate.objectId())) {
                                    throw new IllegalStateException("demo failure");
                                }
                                return candidate.objectId();
                            }).partial());
            scheduler.drain();
            System.out.println("phase4 scheduler metrics: submitted=" + scheduler.metrics().submitted()
                    + ", completed=" + scheduler.metrics().completed()
                    + ", streams=" + scheduler.metrics().streamsStarted()
                    + ", audit=" + audit.eventCount());
            System.out.println(new WorkloadAdmin(scheduler).formatStatus());
            System.out.println("phase4 non-goals: no consensus, no durable queue, no exactly-once guarantee");
            });
        }
    }

    private static void runStreamingWorkloadDemo() {
        EmbeddedRegistryStore store = new EmbeddedRegistryStore();
        CountDownLatch oneWayObserved = new CountDownLatch(1);
        GrpcTransportProvider serverTransport = new GrpcTransportProvider();
        GrpcTransportProvider clientTransport = new GrpcTransportProvider();
        try (DefaultMiddlewareRuntime server = runtime("demo-stream-server", store, serverTransport, SecurityDefaults.development());
             DefaultMiddlewareRuntime client = runtime("demo-stream-client", store, clientTransport, SecurityDefaults.development());
             InMemoryWorkloadScheduler helper = new InMemoryWorkloadScheduler(InMemorySchedulerOptions.defaults("demo-stream-helper", "demo-stream-helper"))) {
            // J7M teaching: remote streaming is shown through transport
            // providers and ObjectRef metadata, while the helper scheduler
            // supplies fan-out/scatter evidence without durable queues.
            ObjectRegistration<StreamingGreeter> streaming = server.export("demo.streaming", new StreamingGreeterImpl("primary", oneWayObserved),
                    StreamingGreeter.class, export("grpc", Map.of("role", "stream")));
            server.export("demo.scatter", new ScatterGreeter("left", false), ScatterApi.class, export("grpc", Map.of("role", "scatter")));
            server.export("demo.scatter", new ScatterGreeter("right", true), ScatterApi.class, export("grpc", Map.of("role", "scatter")));

            StreamingTransportProvider streams = clientTransport;
            try (StreamHandle<Object> handle = streams.serverStream(streaming.ref(),
                    MethodDescriptor.fromMethod(StreamingGreeter.class.getMethod("items")),
                    List.of(), new org.j7m.middleware.api.InvocationOptions("demo-stream", Duration.ofSeconds(2L), Map.of()), String.class)) {
                System.out.println("phase4.1 grpc stream item: " + handle.poll(Duration.ofSeconds(2L)).orElse("missing"));
                System.out.println("phase4.1 grpc stream item: " + handle.poll(Duration.ofSeconds(2L)).orElse("missing"));
            }

            StreamingGreeter proxy = client.lookup("demo.streaming", StreamingGreeter.class, LookupOptions.defaults());
            proxy.fire("one-way");
            await(oneWayObserved);
            System.out.println("phase4.1 one-way observed: true");

            List<ObjectRef> candidates = client.list("demo.scatter", List.of(ScatterApi.class.getName()), Map.of("role", "scatter"), LookupOptions.defaults());
            SecurityContexts.run(context("streaming-workload-demo", "default", true, Set.of("user")), () -> {
            System.out.println("phase4.1 fan-out successes: " + helper.fanOut(new FanOutRequest(candidates, 2,
                    Duration.ofSeconds(2L), Duration.ofSeconds(1L), Map.of()),
                    candidate -> client.proxyFor(candidate, ScatterApi.class).value()).successes().size());
            System.out.println("phase4.1 scatter partial: " + helper.scatterGather(new ScatterGatherRequest(candidates, 2,
                    Duration.ofSeconds(2L), true, Map.of()),
                    candidate -> client.proxyFor(candidate, ScatterApi.class).maybeFail()).partial());
            });
            System.out.println("phase4.1 stream metrics: serverOpened=" + clientTransport.serverStreamCount());
            System.out.println("phase4.1 non-goals: no durable queue, no consensus, no exactly-once guarantee");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("streaming workload demo reflection failure", exception);
        }
    }

    private static void runUdpMulticastOnly() {
        try {
            NetworkInterface networkInterface = loopbackMulticastInterface();
            if (networkInterface == null) {
                System.out.println("real UDP multicast discovery unavailable: loopback multicast is not supported");
                System.out.println("using deterministic multicast adapter for required demo coverage");
                // J7M teaching: host UDP multicast support is environment
                // dependent, so the demo falls back to deterministic multicast
                // coverage instead of skipping the discovery concept.
                MulticastRegistryBus bus = new MulticastRegistryBus();
                runMulticastDemo("deterministic UDP-compatible multicast", bus);
                return;
            }
            int port = freeUdpPort();
            try (DefaultMiddlewareRuntime runtimeA = runtime("demo-udp-a", udpRegistry("demo-udp-a", port, networkInterface), "grpc");
                 DefaultMiddlewareRuntime runtimeB = runtime("demo-udp-b", udpRegistry("demo-udp-b", port, networkInterface), "grpc");
                 DefaultMiddlewareRuntime runtimeC = runtime("demo-udp-c", udpRegistry("demo-udp-c", port, networkInterface), "grpc")) {
                // J7M teaching: the real UDP path demonstrates discovery when
                // the host permits loopback multicast; developers should keep
                // the deterministic fallback for portable acceptance runs.
                runtimeA.export("demo.udp.greeter", new DemoGreeter("udp-one"), Greeter.class, export("grpc", Map.of("role", "primary")));
                runtimeB.export("demo.udp.greeter", new DemoGreeter("udp-two"), Greeter.class, export("grpc", Map.of("role", "primary")));

                List<ObjectRef> refs = runtimeC.list("demo.udp.greeter", List.of(Greeter.class.getName()), Map.of("role", "primary"), LookupOptions.defaults());
                Greeter greeter = runtimeC.lookup("demo.udp.greeter", Greeter.class,
                        new LookupOptions("default", Map.of("role", "primary"), Duration.ofSeconds(5L), true, true));

                System.out.println("real UDP multicast candidates: " + refs.size());
                System.out.println("real UDP multicast greeting: " + greeter.greet("client"));
                System.out.println(new RuntimeAdmin(runtimeC).formatStatus());
            }
        } catch (RuntimeException | java.io.IOException exception) {
            System.out.println("real UDP multicast discovery unavailable: " + exception.getClass().getSimpleName());
            System.out.println("using deterministic multicast adapter for required demo coverage");
            MulticastRegistryBus bus = new MulticastRegistryBus();
            runMulticastDemo("deterministic UDP-compatible multicast", bus);
        }
    }

    private static void runMulticastDemo(final String label, final MulticastRegistryBus bus) {
        try (DefaultMiddlewareRuntime runtimeA = runtime("demo-udp-fallback-a", multicastRegistry("demo-udp-fallback-a", bus), "grpc");
             DefaultMiddlewareRuntime runtimeB = runtime("demo-udp-fallback-b", multicastRegistry("demo-udp-fallback-b", bus), "grpc");
             DefaultMiddlewareRuntime runtimeC = runtime("demo-udp-fallback-c", multicastRegistry("demo-udp-fallback-c", bus), "grpc")) {
            // J7M teaching: the deterministic multicast adapter preserves the
            // same discovery/list/lookup flow as UDP, giving reviewers stable
            // smoke evidence without requiring external network services.
            runtimeA.export("demo.udp.greeter", new DemoGreeter("udp-one"), Greeter.class, export("grpc", Map.of("role", "primary")));
            runtimeB.export("demo.udp.greeter", new DemoGreeter("udp-two"), Greeter.class, export("grpc", Map.of("role", "primary")));

            List<ObjectRef> refs = runtimeC.list("demo.udp.greeter", List.of(Greeter.class.getName()), Map.of("role", "primary"), LookupOptions.defaults());
            Greeter greeter = runtimeC.lookup("demo.udp.greeter", Greeter.class,
                    new LookupOptions("default", Map.of("role", "primary"), Duration.ofSeconds(5L), true, true));

            System.out.println(label + " candidates: " + refs.size());
            System.out.println(label + " greeting: " + greeter.greet("client"));
            System.out.println(new RuntimeAdmin(runtimeC).formatStatus());
        }
    }

    private static DefaultMiddlewareRuntime runtime(final String id, final EmbeddedRegistryStore store, final String transport) {
        return runtime(id, new EmbeddedRegistryClient(store, Clock.systemUTC()), transport);
    }

    private static DefaultMiddlewareRuntime runtime(
            final String id,
            final EmbeddedRegistryStore store,
            final String transport,
            final SecurityConfiguration security) {
        MiddlewareRuntimeBuilder builder = new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                .security(security);
        if ("grpc".equals(transport)) {
            builder.addTransport(new GrpcTransportProvider());
        } else {
            builder.addTransport(new RmiTransportProvider());
        }
        builder.disableNuggetAdmin();
        return builder.build();
    }

    private static DefaultMiddlewareRuntime runtime(
            final String id,
            final EmbeddedRegistryStore store,
            final GrpcTransportProvider provider,
            final SecurityConfiguration security) {
        return new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(new EmbeddedRegistryClient(store, Clock.systemUTC()))
                .security(security)
                .addTransport(provider)
                .disableNuggetAdmin()
                .build();
    }

    private static DefaultMiddlewareRuntime runtime(final String id, final org.j7m.middleware.registry.api.RegistryClient registry, final String transport) {
        MiddlewareRuntimeBuilder builder = new MiddlewareRuntimeBuilder()
                .runtimeId(id)
                .registry(registry);
        if ("grpc".equals(transport)) {
            builder.addTransport(new GrpcTransportProvider());
        } else {
            builder.addTransport(new RmiTransportProvider());
        }
        builder.disableNuggetAdmin();
        return builder.build();
    }

    private static ExportOptions export(final String transport) {
        return new ExportOptions(false, transport, "default", List.of(), Map.of(), Duration.ofSeconds(30L), Duration.ofSeconds(10L));
    }

    private static ExportOptions export(final String transport, final Map<String, String> metadata) {
        return new ExportOptions(false, transport, "default", List.of(), metadata, Duration.ofSeconds(30L), Duration.ofSeconds(10L));
    }

    private static RegistryClient multicastRegistry(final String id, final MulticastRegistryBus bus) {
        MulticastRegistryClient client = new MulticastRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC(),
                new MulticastRegistryOptions(id, "default", "239.7.0.1", 47070, null,
                        Duration.ofMillis(500L), Duration.ofMillis(10L), Duration.ofSeconds(2L), 64, 32, bus),
                DEMO_REGISTRY_TRUST, true, null);
        return new SigningRegistryClient(client, DEMO_REGISTRY_TRUST);
    }

    private static RegistryClient udpRegistry(final String id, final int port, final NetworkInterface networkInterface) {
        MulticastRegistryClient client = new MulticastRegistryClient(new EmbeddedRegistryStore(), Clock.systemUTC(),
                new MulticastRegistryOptions(id, "default", "230.7.0.2", port, networkInterface,
                        Duration.ofMillis(750L), Duration.ofMillis(50L), Duration.ofSeconds(2L), 64, 32,
                        1, 64 * 1024, 60 * 1024, new UdpMulticastBus()),
                DEMO_REGISTRY_TRUST, true, null);
        return new SigningRegistryClient(client, DEMO_REGISTRY_TRUST);
    }

    private static NetworkInterface loopbackMulticastInterface() throws java.io.IOException {
        NetworkInterface networkInterface = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
        if (networkInterface != null && networkInterface.isUp() && networkInterface.supportsMulticast()) {
            return networkInterface;
        }
        return null;
    }

    private static int freeUdpPort() throws java.io.IOException {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static final class SigningRegistryClient implements RegistryClient, RegistryDiagnostics {
        private final RegistryClient delegate;
        private final RegistryDiagnostics diagnostics;
        private final RegistryRecordSigner signer;

        private SigningRegistryClient(final RegistryClient delegate, final RegistryRecordSigner signer) {
            this.delegate = delegate;
            this.diagnostics = delegate instanceof RegistryDiagnostics registryDiagnostics ? registryDiagnostics : null;
            this.signer = signer;
        }

        @Override
        public void bind(final RegistryRecord record) {
            RegistryRecord signed = record.withAuthentication(this.signer.sign(record));
            this.delegate.bind(signed);
        }

        @Override
        public void unbind(final ObjectRef ref) {
            this.delegate.unbind(ref);
        }

        @Override
        public Optional<RegistryRecord> lookup(final ObjectRef ref) {
            return this.delegate.lookup(ref);
        }

        @Override
        public List<RegistryRecord> list(final RegistryQuery query) {
            return this.delegate.list(query);
        }

        @Override
        public void heartbeat(final ObjectRef ref, final long leaseUntil) {
            this.delegate.heartbeat(ref, leaseUntil);
        }

        @Override
        public List<RegistryPeerStatus> peers(final PeerQuery query) {
            return this.delegate.peers(query);
        }

        @Override
        public void close() {
            this.delegate.close();
        }

        @Override
        public DiscoveryStatistics discoveryStatistics() {
            return this.diagnostics == null
                    ? new DiscoveryStatistics(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, Map.of())
                    : this.diagnostics.discoveryStatistics();
        }

        @Override
        public List<DiscoveryEvent> recentDiscoveryEvents() {
            return this.diagnostics == null ? List.of() : this.diagnostics.recentDiscoveryEvents();
        }

        @Override
        public int activeDiscoveryQuestions() {
            return this.diagnostics == null ? 0 : this.diagnostics.activeDiscoveryQuestions();
        }

        @Override
        public int lookupCacheSize() {
            return this.diagnostics == null ? 0 : this.diagnostics.lookupCacheSize();
        }

        @Override
        public int bootstrapHintCount() {
            return this.diagnostics == null ? 0 : this.diagnostics.bootstrapHintCount();
        }

        @Override
        public int contactedBootstrapPeerCount() {
            return this.diagnostics == null ? 0 : this.diagnostics.contactedBootstrapPeerCount();
        }

        @Override
        public boolean multicastListenerRunning() {
            return this.diagnostics != null && this.diagnostics.multicastListenerRunning();
        }

        @Override
        public String multicastAdapterType() {
            return this.diagnostics == null ? RegistryDiagnostics.super.multicastAdapterType() : this.diagnostics.multicastAdapterType();
        }

        @Override
        public String multicastEndpoint() {
            return this.diagnostics == null ? RegistryDiagnostics.super.multicastEndpoint() : this.diagnostics.multicastEndpoint();
        }
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

    private static ApplicationBundle packageModularBundle(final Path workspace, final String version) {
        Path manifest = "1.1.0".equals(version)
                ? resolveProjectPath("examples/modular-deployable-application/META-INF/j7m-application-1.1.0.json")
                : resolveProjectPath("examples/modular-deployable-application/META-INF/j7m-application.json");
        Path apiJar = resolveProjectPath("examples/modular-deployable-application/api/build/libs/com.example.j7m.modular.hello.api.jar");
        Path implJar = resolveProjectPath("examples/modular-deployable-application/impl/build/libs/com.example.j7m.modular.hello.impl.jar");
        Path readme = resolveProjectPath("examples/modular-deployable-application/README.md");
        return new org.j7m.middleware.application.repository.J7mBundleWriter().write(manifest,
                workspace.resolve("modular-hello-service-" + version + ".j7m"),
                List.of(), List.of(apiJar, implJar), List.of(readme));
    }

    private static ApplicationBundle packageClasspathExample(final Path workspace, final String exampleName) {
        Path manifest = resolveProjectPath("examples/" + exampleName + "/META-INF/j7m-application.json");
        Path readme = resolveProjectPath("examples/" + exampleName + "/README.md");
        Path exampleJar = resolveProjectPath("j7m-examples/build/libs/j7m-examples-0.1.0-SNAPSHOT.jar");
        return new org.j7m.middleware.application.repository.J7mBundleWriter().write(manifest,
                workspace.resolve(exampleName + "-1.0.0.j7m"), List.of(exampleJar), List.of(readme));
    }

    private static ApplicationBundle copyWithoutEntry(
            final ApplicationBundle source,
            final Path target,
            final String excludedEntry) throws java.io.IOException {
        try (ZipFile zip = new ZipFile(source.path().toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (excludedEntry.equals(entry.getName())) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(0L);
                output.putNextEntry(copy);
                output.write(zip.getInputStream(entry).readAllBytes());
                output.closeEntry();
            }
        }
        return new ApplicationBundle(target, source.coordinate(),
                new org.j7m.middleware.application.repository.J7mBundleReader().sha256(target));
    }

    private static SecurityContext context(final String principal, final String trustDomain, final boolean authenticated, final Set<String> roles) {
        return new SecurityContext(new Principal(principal, principal, roles, Set.of()), "demo-caller", new TrustDomain(trustDomain),
                new Credential("demo", "1", Map.of()), "demo-correlation", "", "", authenticated, TransportSecurityLevel.TEST_AUTHENTICATED,
                roles, Map.of(), Instant.now());
    }

    private static TaskSubmission workloadSubmission(final String name) {
        return new TaskSubmission(new TaskDescriptor(name, "demo-handler", "payload", WorkloadMetadata.empty(), Map.of()),
                TaskOptions.defaults(), Map.of("source", "demo"));
    }

    private static ObjectRef workloadRef(final String id, final String load) {
        return new ObjectRef(id, "demo.workload." + id, "demo.workload", "runtime-" + id, "default", List.of(Greeter.class.getName()),
                "grpc", "127.0.0.1:" + id, System.currentTimeMillis() + 30_000L, 1L,
                Map.of("load", load, "trustStatus", "trusted", "fencingToken", "1", "topologyGeneration", "1"));
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(2L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("demo workload operation timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("demo workload operation interrupted", exception);
        }
    }

    /**
     * Demo service API.
     */
    public interface Greeter {
        /**
         * Returns a greeting.
         *
         * @param name caller name
         * @return greeting text
         */
        @Idempotent
        String greet(String name);

        /**
         * Registers a listener.
         *
         * @param listener listener callback
         */
        void register(Listener listener);

        /**
         * Emits a listener event.
         *
         * @param message event message
         */
        void fire(String message);
    }

    /**
     * Demo callback API.
     */
    public interface Listener {
        /**
         * Receives a message.
         *
         * @param message callback message
         */
        void onMessage(String message);
    }

    private static final class DemoGreeter implements Greeter {
        private final String name;
        private Listener listener;

        private DemoGreeter(final String name) {
            this.name = name;
        }

        @Override
        public String greet(final String caller) {
            return this.name + " says hello to " + caller;
        }

        @Override
        public void register(final Listener newListener) {
            this.listener = newListener;
        }

        @Override
        public void fire(final String message) {
            this.listener.onMessage(message);
        }
    }

    /**
     * Security demo API.
     */
    public interface SecureGreeter extends Greeter {
        /**
         * Stores a sensitive value.
         *
         * @param secret sensitive value
         * @return safe result
         */
        String storeSecret(@Sensitive String secret);
    }

    private static final class SecureGreeterImpl implements SecureGreeter {
        private final DemoGreeter delegate;

        private SecureGreeterImpl() {
            this.delegate = new DemoGreeter("secure");
        }

        @Override
        public String greet(final String name) {
            return this.delegate.greet(name);
        }

        @Override
        public void register(final Listener listener) {
            this.delegate.register(listener);
        }

        @Override
        public void fire(final String message) {
            this.delegate.fire(message);
        }

        @Override
        public String storeSecret(final String secret) {
            return "stored securely";
        }
    }

    private static final class RecordingListener implements Listener {
        private final List<String> messages;

        private RecordingListener() {
            this.messages = new ArrayList<>();
        }

        @Override
        public void onMessage(final String message) {
            this.messages.add(message);
        }
    }

    /**
     * Streaming demo service.
     */
    public interface StreamingGreeter {
        /**
         * Returns stream items.
         *
         * @return stream items
         */
        List<String> items();

        /**
         * Records a one-way event.
         *
         * @param value event value
         */
        @OneWay
        void fire(String value);
    }

    private static final class StreamingGreeterImpl implements StreamingGreeter {
        private final String name;
        private final CountDownLatch observed;

        private StreamingGreeterImpl(final String name, final CountDownLatch observed) {
            this.name = name;
            this.observed = observed;
        }

        @Override
        public List<String> items() {
            return List.of(this.name + "-chunk-1", this.name + "-chunk-2");
        }

        @Override
        public void fire(final String value) {
            this.observed.countDown();
        }
    }

    /**
     * Scatter demo API.
     */
    public interface ScatterApi {
        /**
         * Returns a value.
         *
         * @return value
         */
        @Idempotent
        String value();

        /**
         * Returns a value or fails for partial-result demonstration.
         *
         * @return value
         */
        @Idempotent
        String maybeFail();
    }

    private static final class ScatterGreeter implements ScatterApi {
        private final String name;
        private final boolean fail;

        private ScatterGreeter(final String name, final boolean fail) {
            this.name = name;
            this.fail = fail;
        }

        @Override
        public String value() {
            return this.name;
        }

        @Override
        public String maybeFail() {
            if (this.fail) {
                throw MiddlewareException.of(MiddlewareErrorCode.REMOTE_EXCEPTION, "demo scatter failure");
            }
            return this.name;
        }
    }
}
