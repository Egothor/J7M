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

package org.j7m.middleware.application.distributed;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.application.repository.*;
import org.j7m.middleware.deployer.ApplicationManager;
import org.j7m.middleware.distributed.RemoteResourceClient;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.security.api.*;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;

/**
 * Separate real signed-host subscriber JVM, controlled only by synthetic loopback test endpoints.
 */
public final class EventSubscriptionFrontendProcess {
    private EventSubscriptionFrontendProcess() { }
    /**
     * Runs the ordinary generated application under the production manager and owned CALL pipeline.
     * @param arguments signed bundle, public key, work directory, source ports CSV, metadata port, sink port, optional negative-gate mode
     * @throws Exception if qualification setup, activation or command processing fails
     */
    public static void main(String[] arguments) throws Exception {
        Path bundlePath = Path.of(arguments[0]); Path work = Path.of(arguments[2]);
        var key = java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(Files.readAllBytes(Path.of(arguments[1]))));
        var trust = new PublicKeyTrustStore(Map.of(new SigningKeyId("operator"), key));
        var source = new RemoteResourceClient(Arrays.stream(arguments[3].split(",")).map(port -> new InetSocketAddress("127.0.0.1", Integer.parseInt(port))).toList(), "app", new byte[32], null);
        var metadata = new RemoteResourceClient(List.of(new InetSocketAddress("127.0.0.1", Integer.parseInt(arguments[4]))), "app", new byte[32], null);
        var profile = new EventSubscriptionProfile(source, metadata, "source", Duration.ofSeconds(60), Duration.ofMillis(10),
                Duration.ofSeconds(2), Duration.ofSeconds(20), 8, 1048576, EventSubscriptionProfile.Restart.RESUME, EventSubscriptionProfile.Expiry.FAIL);
        try (var runtime = new MiddlewareRuntimeBuilder().runtimeId("subscriber").addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
                var factory = new ManagedEventSubscriptionFactory(Map.of(arguments.length > 6 && arguments[6].equals("unknown-policy") ? "other" : "default", profile), 2, 4)) {
            var repository = new LocalApplicationRepository(work.resolve("repository"), Clock.systemUTC(), ApplicationPlatformLimits.defaults(), trust);
            var coordinate = ApplicationCoordinate.parse("fixture:events:1");
            var bundle = new ApplicationBundle(bundlePath, coordinate, new J7mBundleReader().sha256(bundlePath));
            if (!repository.listVersions(coordinate.id()).contains(coordinate.version())) {
                var published = repository.publish(bundle, new RepositoryPublishOptions("test", true));
                if (!published.success()) throw new IllegalStateException("Signed subscription publication failed: " + published.details());
            } else if (!repository.fetch(coordinate).sha256().equals(bundle.sha256())) throw new IllegalStateException("Fixture artifact changed across restart");
            var worker = new SecurityContext(new Principal("event-worker", "event-worker", Set.of("consumer"), Set.of()), "subscriber", new TrustDomain("default"),
                    null, "", "", "", false, null, Set.of("consumer"), Map.of(), Instant.EPOCH);
            String mode = arguments.length > 6 ? arguments[6] : "normal";
            FaultFactory fault = mode.startsWith("partial-") ? new FaultFactory(factory, mode) : null;
            var resources = new ManagedHostedResources(arguments.length > 6 && arguments[6].equals("missing-factory") ? Map.of()
                    : Map.of("event-subscription", new ManagedHostedResources.Entry(fault == null ? factory : fault, worker, Duration.ofSeconds(20))));
            var options = new ManagedHostingOptions(ManagedHostBindings.empty(), List.of(new ManagedCallingPolicy()), ManagedProbeSettings.defaults(),
                    ManagedInvocationSettings.defaults(), resources);
            var manager = new ApplicationManager(runtime, repository, ApplicationPlatformLimits.defaults(), options, trust);
            ApplicationActivation activation;
            try {
                activation = manager.activate(coordinate, new ApplicationActivationRequest("test", "test", true,
                        Map.of("j7m.annotations.profile", "managed-v1", "sink.port", arguments[5], "ctor.marker", work.resolve("constructed").toString())));
            } catch (RuntimeException failure) {
                for (var snapshot : manager.list(ApplicationQuery.all())) {
                    System.err.println("A14_ACTIVATION_DIAGNOSTIC " + snapshot.state() + " cleanup=" + snapshot.metadata().get("stopCleanupFailureCount")
                            + " reason=" + snapshot.metadata().get("stopCleanupFailureSummary") + " active=" + snapshot.metadata().get("activeInvocations"));
                }
                try { System.err.println("A14_FACTORY_DIAGNOSTIC " + factory.status("source", "projection")); }
                catch (IllegalArgumentException absent) { System.err.println("A14_FACTORY_DIAGNOSTIC UNBOUND"); }
                if (fault != null) System.err.println(fault.diagnostic());
                var retained = manager.list(ApplicationQuery.all()).stream().filter(snapshot -> snapshot.state() == ApplicationRunState.DRAINING).findFirst();
                if (mode.equals("partial-held") && retained.isPresent()) activation = retained.orElseThrow();
                else throw failure;
            }
            System.out.println("READY " + activation.state()); System.out.flush();
            try (var input = new BufferedReader(new InputStreamReader(System.in))) {
                String command;
                while ((command = input.readLine()) != null) {
                    if (command.equals("STATUS")) {
                        var status = factory.status("source", "projection");
                        System.out.println("STATUS " + status.phase() + " " + status.reason() + " " + (status.cursor() == null ? -1 : status.cursor().sequence()));
                    } else if (command.equals("STATE")) {
                        System.out.println("STATE " + manager.active(activation.instanceIdentity().instanceId()).state());
                    } else if (command.equals("RELEASE") && fault != null) {
                        fault.release(); System.out.println("RELEASED");
                    } else if (command.equals("STOP")) {
                        var result = manager.deactivate(activation.instanceIdentity().instanceId());
                        System.out.println("STOP " + result.success() + " " + result.state()); System.out.flush();
                        if (result.success()) return;
                    } else throw new IllegalArgumentException("Unknown subscriber fixture command");
                    System.out.flush();
                }
            }
        }
    }

    /**
     * Synthetic lifecycle failure after another real binding has armed or attempted admission.
     */
    private static final class FaultFactory implements ManagedHostedResources.Factory {
        private final ManagedEventSubscriptionFactory delegate;
        private final String mode;
        private final CountDownLatch closedGuardObserved = new CountDownLatch(1);
        private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        private volatile boolean holdsWork;
        private final AtomicInteger stopped = new AtomicInteger(), drained = new AtomicInteger(), closed = new AtomicInteger();
        FaultFactory(ManagedEventSubscriptionFactory delegate, String mode) { this.delegate = delegate; this.mode = mode; }
        public Set<Class<? extends java.lang.annotation.Annotation>> annotationTypes() { return delegate.annotationTypes(); }
        public void validate(ManagedHostedFeature feature, ManagedComponentDefinition<?> definition, ManagedHostedResources.Bundle bundle) { delegate.validate(feature, definition, bundle); }
        public ManagedHostedResources.Binding bind(ManagedHostedFeature feature, ManagedComponentDefinition<?> definition, Object component,
                ManagedHostedResources.Bundle bundle, ManagedHostedResources.Invoker invoker) {
            var watched = new ManagedHostedResources.Invoker() {
                public CompletionStage<Object> invokeDecoded(ManagedMethod method, Callable<Object[]> decoder, org.j7m.middleware.api.InvocationOptions options) {
                    return invoker.invokeDecoded(method, decoder, options);
                }
                public CompletionStage<Object> invokeDecoded(ManagedMethod method, Callable<Object[]> decoder, org.j7m.middleware.api.InvocationOptions options, int bytes) {
                    return invoker.invokeDecoded(method, decoder, options, bytes);
                }
                public boolean admissionOpen() {
                    boolean open = invoker.admissionOpen(); if (!open && feature.id().equals("projection")) closedGuardObserved.countDown(); return open;
                }
            };
            var binding = delegate.bind(feature, definition, component, bundle, watched);
            return new ManagedHostedResources.Binding() {
                public void prepare() throws Exception { binding.prepare(); }
                public void start() throws Exception {
                    binding.start();
                    if (feature.id().equals("zz-fail") && mode.equals("partial-held")) {
                        holdsWork = true;
                        Thread worker = new Thread(null, () -> {
                            entered.countDown();
                            try { release.await(); Objects.requireNonNull(component.getClass()); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                            finally { exited.countDown(); }
                        }, "held-hosted-backend", 0, false);
                        worker.setDaemon(true); worker.setContextClassLoader(component.getClass().getClassLoader()); worker.start();
                        if (!entered.await(2, TimeUnit.SECONDS)) throw new AssertionError("Actual backend worker never entered");
                        throw new IllegalStateException("Synthetic backend failure with actual retained work");
                    }
                    if (feature.id().equals("zz-fail") && mode.equals("partial-start")) throw new IllegalStateException("Synthetic backend start failure");
                }
                public void commitAdmission() {
                    if (feature.id().equals("zz-fail") && mode.equals("partial-commit")) {
                        try { if (!closedGuardObserved.await(2, TimeUnit.SECONDS)) throw new AssertionError("Actual worker did not observe closed global admission"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                        throw new IllegalStateException("Synthetic backend admission failure");
                    }
                    binding.commitAdmission();
                }
                public void stopAdmission() { binding.stopAdmission(); stopped.incrementAndGet(); }
                public boolean drain(Duration budget) throws Exception {
                    if (holdsWork && exited.getCount() != 0) return false;
                    boolean result = binding.drain(budget); if (result) drained.incrementAndGet(); return result;
                }
                public void close() throws Exception { binding.close(); closed.incrementAndGet(); }
            };
        }
        void release() throws InterruptedException { release.countDown(); if (!exited.await(2, TimeUnit.SECONDS)) throw new AssertionError("Backend finally did not exit"); }
        String diagnostic() { return "A14_PARTIAL stopped=" + stopped + " drained=" + drained + " closed=" + closed + " guarded=" + (closedGuardObserved.getCount() == 0); }
    }
}
