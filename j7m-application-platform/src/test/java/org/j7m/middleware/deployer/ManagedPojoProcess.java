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

package org.j7m.middleware.deployer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.application.api.ApplicationActivationRequest;
import org.j7m.middleware.application.api.ApplicationBundle;
import org.j7m.middleware.application.api.ApplicationCoordinate;
import org.j7m.middleware.application.api.ApplicationQuery;
import org.j7m.middleware.application.api.RepositoryPublishOptions;
import org.j7m.middleware.application.repository.J7mBundleReader;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryClient;
import org.j7m.middleware.registry.embedded.EmbeddedRegistryStore;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcSecurityOptions;
import org.j7m.middleware.transport.grpc.GrpcTlsConfiguration;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;

/**
 * Separate JVM fixture: production signed activation and mTLS exports, file-based test control only.
 */
public final class ManagedPojoProcess {
    private ManagedPojoProcess() { }

    /**
     * Starts the real hosting runtime until the test requests exit.
     * @param args bundle, working directory, server certificate/key/trust roots, fail-start flag and fixture mode
     * @throws Exception when deployment or fixture control fails
     */
    public static void main(final String[] args) throws Exception {
        Path directory = Path.of(args[1]);
        System.setProperty("managed.events", directory.resolve("events").toString());
        System.setProperty("managed.entered", directory.resolve("entered").toString());
        System.setProperty("managed.release", directory.resolve("release").toString());
        System.setProperty("managed.fail", args[5]);
        var tls = new GrpcTlsConfiguration(Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), true, "default", "localhost");
        try (var runtime = new MiddlewareRuntimeBuilder().runtimeId("managed-host")
                .registry(new EmbeddedRegistryClient(new EmbeddedRegistryStore(), java.time.Clock.systemUTC()))
                .addTransport(("plaintextsecurity".equals(args[6]) ? new GrpcTransportProvider() : new GrpcTransportProvider(GrpcSecurityOptions.mutualTls(true, tls)))).disableNuggetAdmin().build()) {
            var repositoryTrust = trust(Path.of(args[7]));
            var managerTrust = trust(Path.of(args[8]));
            var repository = new LocalApplicationRepository(directory.resolve("repository"), java.time.Clock.systemUTC(),
                    org.j7m.middleware.application.repository.ApplicationPlatformLimits.defaults(), repositoryTrust);
            ApplicationCoordinate coordinate = ApplicationCoordinate.parse("fixture:pojo:1");
            ApplicationBundle bundle = new ApplicationBundle(Path.of(args[0]), coordinate, new J7mBundleReader().sha256(Path.of(args[0])));
            var published = repository.publish(bundle, new RepositoryPublishOptions("test", args.length < 7 || !"unsigned".equals(args[6])));
            if (!published.success()) {
                if ("readiness-metadata".equals(args[6]) && published.state() == org.j7m.middleware.application.api.ApplicationRunState.REJECTED
                        && published.details().contains("service metadata key is not allowed")) {
                    Files.writeString(directory.resolve("failed"), "FAILED:" + runtime.status().instances().size()); return;
                }
                throw new IllegalStateException("signed publication rejected: " + args[6]);
            }
            var installed = new ArrayList<org.j7m.middleware.application.components.ManagedInvocationPolicy>();
            installed.add(policy(directory));
            if (List.of("security", "plaintextsecurity", "nonentry", "factorypolicy", "probeauth").contains(args[6])) installed.add(securityPolicy(directory));
            var resourcePolicy = resourcePolicy();
            if (List.of("security", "plaintextsecurity", "isolation", "resource", "async").contains(args[6])) installed.add(resourcePolicy);
            if (List.of("async", "schema").contains(args[6])) installed.add(new org.j7m.middleware.application.components.ManagedCallingPolicy());
            var policies = new org.j7m.middleware.application.components.ManagedHostingOptions(
                    org.j7m.middleware.application.components.ManagedHostBindings.empty(), installed,
                    "probe-capacity".equals(args[6])
                            ? new org.j7m.middleware.application.components.ManagedProbeSettings(2, 1, java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(100), java.time.Duration.ofSeconds(2))
                            : org.j7m.middleware.application.components.ManagedProbeSettings.defaults());
            var manager = new ApplicationManager(runtime, repository,
                    org.j7m.middleware.application.repository.ApplicationPlatformLimits.defaults(), policies, managerTrust);
            org.j7m.middleware.application.api.ApplicationActivation activation;
            try {
                activation = manager.activate(coordinate, new ApplicationActivationRequest("operator", "test", true,
                        Map.of("j7m.annotations.profile", "managed-v1", "managed.marker", "kept")));
            } catch (RuntimeException failure) {
                failure.printStackTrace(System.err);
                if (failure instanceof org.j7m.middleware.application.components.ManagedInjectionException structured) {
                    var diagnostic = structured.diagnostic();
                    Files.writeString(directory.resolve("injection-code"), diagnostic.reasonCode());
                    Files.writeString(directory.resolve("injection-path"), diagnostic.metadata().getOrDefault("graphPath", ""));
                    var snapshot = manager.list(org.j7m.middleware.application.api.ApplicationQuery.all()).getFirst();
                    Files.writeString(directory.resolve("injection-status"), snapshot.metadata().getOrDefault("injection.reasonCode", "MISSING"));
                    if (failure.getCause() != null) throw new AssertionError("Raw application cause escaped structured boundary");
                }
                Files.writeString(directory.resolve("failed"), "FAILED:" + runtime.status().instances().size());
                return;
            }
            var probeInventory = manager.probes(activation.instanceIdentity().instanceId()).orElseThrow();
            if (List.of("probes", "healthonly", "probe-capacity").contains(args[6])) {
                int expected = "healthonly".equals(args[6]) ? 1 : 2;
                if (probeInventory.size() != expected || probeInventory.stream().map(org.j7m.middleware.application.api.ManagedApplicationProbe::id).distinct().count() != expected
                        || probeInventory.stream().anyMatch(probe -> probe.id().isBlank())) throw new IllegalStateException("invalid public probe inventory");
                var kinds = probeInventory.stream().map(org.j7m.middleware.application.api.ManagedApplicationProbe::kind).collect(java.util.stream.Collectors.toSet());
                if (!kinds.contains(org.j7m.middleware.application.api.ManagedProbeKind.HEALTH)
                        || expected == 2 && !kinds.contains(org.j7m.middleware.application.api.ManagedProbeKind.READINESS)) throw new IllegalStateException("probe kinds lost");
            } else if (!probeInventory.isEmpty()) throw new IllegalStateException("unexpected probe inventory");
            ObjectRef ref = List.of("probes", "healthonly", "probe-capacity").contains(args[6])
                    ? runtime.status().instances().stream().map(org.j7m.middleware.api.InstanceStatus::ref).filter(item -> item.serviceName().equals("//managed/pojo")).findFirst().orElseThrow()
                    : runtime.list("//managed/pojo", List.of("fixture.api.Api"), Map.of(), LookupOptions.defaults()).getFirst();
            writeRef(directory.resolve("reference"), ref);
            Files.writeString(directory.resolve("ready"), "RUNNING");
            String previous = "";
            for (int iteration = 0; iteration < 12_000; iteration++) {
                Path commandFile = directory.resolve("command");
                if (Files.exists(commandFile)) {
                    String command = Files.readString(commandFile);
                    if (!command.equals(previous)) {
                        previous = command;
                        String[] fields = command.split(":", 2);
                        String result;
                        switch (fields[1]) {
                            case "DRAIN" -> { var stopped = manager.drain(activation.instanceIdentity().instanceId()); result = stopped.success() + ":" + stopped.state(); }
                            case "STOP" -> {
                                var stopped = manager.deactivate(activation.instanceIdentity().instanceId());
                                if (stopped.success()) for (var old : probeInventory) {
                                    try { old.check().call(); throw new IllegalStateException("stopped probe callback was admitted"); }
                                    catch (org.j7m.middleware.api.MiddlewareException rejected) {
                                        if (rejected.code() != org.j7m.middleware.api.MiddlewareErrorCode.UNAVAILABLE) throw rejected;
                                    }
                                }
                                result = stopped.success() + ":" + stopped.state();
                            }
                            case "UPGRADE" -> {
                                var upgraded = manager.upgrade(activation.instanceIdentity().instanceId(), coordinate, "stop-start",
                                        new ApplicationActivationRequest("upgrade", "test", false, Map.of()));
                                if (upgraded.success()) {
                                    activation = manager.list(ApplicationQuery.all()).stream().filter(item -> item.state()==org.j7m.middleware.application.api.ApplicationRunState.RUNNING).findFirst().orElseThrow();
                                    writeRef(directory.resolve("reference"), runtime.list("//managed/pojo", List.of("fixture.api.Api"), Map.of(), LookupOptions.defaults()).getFirst());
                                }
                                result = upgraded.success() + ":" + upgraded.state();
                            }
                            case "ROLLBACK" -> {
                                var restored = manager.rollback(activation.instanceIdentity().instanceId(), new ApplicationActivationRequest("rollback", "test", false, Map.of()));
                                if (restored.success()) {
                                    activation = manager.list(ApplicationQuery.all()).stream().filter(item -> item.state()==org.j7m.middleware.application.api.ApplicationRunState.RUNNING).findFirst().orElseThrow();
                                    writeRef(directory.resolve("reference"), runtime.list("//managed/pojo", List.of("fixture.api.Api"), Map.of(), LookupOptions.defaults()).getFirst());
                                }
                                result = restored.success() + ":" + restored.state();
                            }
                            case "SECOND" -> {
                                try {
                                    manager.activate(coordinate, new ApplicationActivationRequest("operator", "test", true,
                                            Map.of("j7m.annotations.profile", "managed-v1", "managed.marker", "kept")));
                                    result = "UNEXPECTED_SECOND_ACTIVATION";
                                } catch (org.j7m.middleware.api.MiddlewareException failure) {
                                    if (!(failure.getCause() instanceof IllegalStateException)
                                            || !failure.getCause().getMessage().equals("probe coordinator unavailable")) throw failure;
                                    result = "CAPACITY:" + runtime.status().instances().size() + ":" + manager.active(activation.instanceIdentity().instanceId()).state();
                                }
                            }
                            case "VISIBLE" -> result = Integer.toString(runtime.list("//managed/pojo", List.of("fixture.api.Api"), Map.of(), LookupOptions.defaults()).size());
                            case "PROBE_STATUS" -> result = manager.probeStatus(activation.instanceIdentity().instanceId()).map(item -> item.state().name()).orElse("NONE");
                            case "INFLIGHT" -> result = manager.active(activation.instanceIdentity().instanceId()).metadata().get("activeInvocations");
                            case "RESOURCE_ACTIVE" -> result = Integer.toString(resourcePolicy.status("small").active());
                            case "STATUS" -> result = manager.list(ApplicationQuery.all()).getFirst().state() + ":" + runtime.status().instances().size();
                            case "EXIT" -> { return; }
                            default -> throw new IllegalArgumentException("unknown fixture command");
                        }
                        Files.writeString(directory.resolve("reply"), fields[0] + ":" + result);
                    }
                }
                Thread.sleep(10);
            }
            throw new IllegalStateException("fixture control deadline");
        }
    }

    private static org.j7m.middleware.application.repository.PublicKeyTrustStore trust(final Path path) throws Exception {
        var key = java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(Files.readAllBytes(path)));
        return new org.j7m.middleware.application.repository.PublicKeyTrustStore(Map.of(new org.j7m.middleware.application.repository.SigningKeyId("operator"), key));
    }

    private static org.j7m.middleware.application.components.ManagedInvocationPolicy policy(final Path directory) {
        return new org.j7m.middleware.application.components.ManagedInvocationPolicy() {
            public java.util.Set<Class<? extends java.lang.annotation.Annotation>> annotationTypes() { return java.util.Set.of(); }
            public void validate(org.j7m.middleware.application.api.ManagedServiceContract contract, Class<?> implementation, Class<?> business) {
                if (!contract.method().declaringType().equals(business.getName())) throw new IllegalArgumentException("wire identity is not public interface");
                append(directory.resolve("order"), "V");
            }
            public Binding bind(org.j7m.middleware.application.api.ManagedServiceContract contract, Class<?> implementation, Class<?> business) {
                append(directory.resolve("order"), "B");
                return new Binding() {
                    private boolean closed;
                    public Object invoke(org.j7m.middleware.application.api.InvocationContext context, Object[] arguments, Proceed next) throws Exception {
                        if (closed || next.target() == null || next.target().objectId().isBlank()
                                || !contract.id().equals(next.target().metadata().get("j7m.contract.id"))
                                || !Integer.toString(contract.version()).equals(next.target().metadata().get("j7m.contract.version"))
                                || !context.security().principal().equals(org.j7m.middleware.security.api.SecurityContexts.current().principal())) {
                            throw new IllegalStateException("invalid actual export context");
                        }
                        append(directory.resolve("policy-invoked"), "P");
                        return next.invoke();
                    }
                    public void close() { if (closed) throw new IllegalStateException("policy closed twice"); closed = true; append(directory.resolve("order"), "X"); }
                };
            }
        };
    }

    private static org.j7m.middleware.application.components.ManagedResourcePolicy resourcePolicy() {
        return new org.j7m.middleware.application.components.ManagedResourcePolicy(
                Map.of("small", new org.j7m.middleware.application.components.ManagedResourcePolicy.ResourceProfile(
                        1, 0, java.time.Duration.ZERO, java.time.Duration.ofMillis(1), 10)),
                Map.of("small-heap", new org.j7m.middleware.application.components.ManagedResourcePolicy.HostHeapProfile(1)));
    }

    private static org.j7m.middleware.application.components.ManagedSecurityPolicy securityPolicy(final Path directory) {
        var authorization = new org.j7m.middleware.security.api.AuthorizationPolicy() {
            public String id() { return "actual-target-policy"; }
            public org.j7m.middleware.security.api.AuthorizationDecision evaluate(org.j7m.middleware.security.api.SecurityContext context,
                    org.j7m.middleware.security.api.PermissionRequest request) {
                if (!request.serviceName().equals("//managed/pojo") || !request.runtimeId().equals("managed-host")
                        || !request.transportId().equals("grpc") || request.resource().isBlank()
                        || !request.methodDescriptor().contains("fixture.api.Api")
                        || !"orders:invoke".equals(request.metadata().get("requiredPermission"))) {
                    throw new IllegalStateException("incorrect actual target permission scope");
                }
                append(directory.resolve("permissions"), context.principal().id() + ":" + request.resource());
                return context.principal().id().equals("alice")
                        ? org.j7m.middleware.security.api.AuthorizationDecision.allow(id())
                        : org.j7m.middleware.security.api.AuthorizationDecision.deny(id(), "operator policy");
            }
        };
        var sink = new org.j7m.middleware.security.api.SecurityAuditSink() {
            private final java.util.List<org.j7m.middleware.security.api.AuditEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            public void record(org.j7m.middleware.security.api.AuditEvent event) {
                try {
                    Path control = directory.resolve("audit-fail");
                    String fail = Files.exists(control) ? Files.readString(control) : "";
                    if (fail.equals(event.decision())) throw new IllegalStateException("synthetic-secret-audit-detail");
                    events.add(event); append(directory.resolve("audit"), event.toString());
                } catch (java.io.IOException failure) { throw new IllegalStateException("fixture audit I/O"); }
            }
            public java.util.List<org.j7m.middleware.security.api.AuditEvent> recentEvents() { return java.util.List.copyOf(events); }
            public long eventCount() { return events.size(); }
        };
        return new org.j7m.middleware.application.components.ManagedSecurityPolicy(authorization, sink, java.time.Clock.systemUTC());
    }

    private static void append(final Path file, final String value) {
        try { Files.writeString(file, value + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
        catch (java.io.IOException failure) { throw new IllegalStateException("fixture event failure"); }
    }

    static void writeRef(final Path path, final ObjectRef reference) throws Exception {
        try (var output = new DataOutputStream(Files.newOutputStream(path))) {
            output.writeUTF(reference.objectId()); output.writeUTF(reference.logicalName()); output.writeUTF(reference.serviceName());
            output.writeUTF(reference.runtimeId()); output.writeUTF(reference.registryContext()); output.writeUTF(reference.transport());
            output.writeUTF(reference.endpoint()); output.writeLong(reference.leaseUntil()); output.writeLong(reference.generation());
            output.writeInt(reference.interfaces().size()); for (String contract : reference.interfaces()) output.writeUTF(contract);
            output.writeInt(reference.metadata().size()); for (var entry : reference.metadata().entrySet()) { output.writeUTF(entry.getKey()); output.writeUTF(entry.getValue()); }
        }
    }

    static ObjectRef readRef(final Path path) throws Exception {
        try (var input = new DataInputStream(Files.newInputStream(path))) {
            String id = input.readUTF(), logical = input.readUTF(), service = input.readUTF(), runtime = input.readUTF(), registry = input.readUTF();
            String transport = input.readUTF(), endpoint = input.readUTF(); long lease = input.readLong(), generation = input.readLong();
            int count = input.readInt(); if (count < 1 || count > 16) throw new IllegalArgumentException("fixture interfaces");
            List<String> interfaces = new ArrayList<>(); for (int index = 0; index < count; index++) interfaces.add(input.readUTF());
            count = input.readInt(); if (count < 0 || count > 128) throw new IllegalArgumentException("fixture metadata");
            Map<String, String> metadata = new LinkedHashMap<>(); for (int index = 0; index < count; index++) metadata.put(input.readUTF(), input.readUTF());
            return new ObjectRef(id, logical, service, runtime, registry, interfaces, transport, endpoint, lease, generation, metadata);
        }
    }
}
