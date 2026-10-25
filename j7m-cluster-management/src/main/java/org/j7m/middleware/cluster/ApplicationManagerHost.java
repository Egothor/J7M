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

package org.j7m.middleware.cluster;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.j7m.middleware.api.MiddlewareRuntime;
import org.j7m.middleware.api.LookupOptions;
import org.j7m.middleware.application.repository.LocalApplicationRepository;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.repository.ArtifactRepositoryClient;
import org.j7m.middleware.deployer.ApplicationManager;

/**
 * Actual ApplicationManager hosting adapter with revision-pinned admission and counted in-flight
 * calls. ArtifactRepositoryClient verifies signed D14 metadata and every byte before repository
 * publication; the application manager also enforces its configured bundle verification policy.
 * Managed readiness and canary admission consume fresh application probe observations; missing,
 * failed or expired managed observations hold traffic. Legacy readiness remains RUNNING-based.
 * Inspection never waits for application probe code while holding this host's monitor.
 * This adapter upgrades hosted applications, not the middleware executable.
 */
public final class ApplicationManagerHost implements HostingSupervisor {
    /**
     * Server-configured service binding; remote callers supply its alias only.
     * @param serviceName runtime service name
     * @param interfaceName exported interface declaring invoke(String,byte[])
     */
    public record Contract(String serviceName, String interfaceName) {}
    private final String nodeId;
    private final MiddlewareRuntime runtime;
    private final ApplicationManager manager;
    private final LocalApplicationRepository repository;
    private final ArtifactRepositoryClient artifacts;
    private final Path staging;
    private final Map<String,Contract> contracts;
    private final ApplicationActivationRequest activationRequest;
    private ApplicationActivation active;
    private String releaseId = "";
    private boolean draining = true;
    private int inFlight;
    private long generation;
    /**
     * Creates a host around the application's real manager.
     * @param nodeId configured identity
     * @param runtime actual runtime
     * @param manager actual manager owning this application
     * @param repository actual manager repository
     * @param artifacts authenticated signed artifact source
     * @param staging private artifact staging directory
     * @param contracts fixed invocation aliases
     * @param activationRequest server-selected activation policy
     */
    public ApplicationManagerHost(String nodeId, MiddlewareRuntime runtime, ApplicationManager manager,
            LocalApplicationRepository repository, ArtifactRepositoryClient artifacts, Path staging,
            Map<String,Contract> contracts, ApplicationActivationRequest activationRequest) {
        this.nodeId=Objects.requireNonNull(nodeId); this.runtime=Objects.requireNonNull(runtime);
        this.manager=Objects.requireNonNull(manager); this.repository=Objects.requireNonNull(repository);
        this.artifacts=Objects.requireNonNull(artifacts); this.staging=Objects.requireNonNull(staging);
        this.contracts=Map.copyOf(contracts); this.activationRequest=Objects.requireNonNull(activationRequest);
    }
    /**
     * {@inheritDoc}
     */
    @Override public NodeView inspect() {
        ApplicationActivation selected; long selectedGeneration;
        synchronized (this) {
            if (active == null || draining) return new NodeView(nodeId, releaseId, false, draining, inFlight, generation);
            selected = active; selectedGeneration = generation;
        }
        boolean ready = false;
        try {
            var current = manager.active(selected.instanceIdentity().instanceId());
            if (current != null && current.state() == ApplicationRunState.RUNNING) {
                var managed = manager.probes(selected.instanceIdentity().instanceId());
                // Managed canary readiness requires an actual fresh observation, not merely RUNNING.
                ready = managed.isEmpty() || manager.probeStatus(selected.instanceIdentity().instanceId())
                        .map(snapshot -> snapshot.state() == org.j7m.middleware.application.components.ManagedProbeCoordinator.State.HEALTHY)
                        .orElse(false);
            }
        } catch (RuntimeException unavailable) { ready = false; }
        synchronized (this) {
            boolean same = active == selected && generation == selectedGeneration && !draining;
            return new NodeView(nodeId, releaseId, same && ready, draining, inFlight, generation);
        }
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void drain(Instant deadline) throws IOException {
        draining=true;
        while(inFlight>0) {
            long remaining=deadline.toEpochMilli()-System.currentTimeMillis();
            if(remaining<=0) throw new IOException("Drain deadline");
            try { wait(Math.min(remaining,100)); } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IOException("Drain interrupted",interrupted);
            }
        }
        if(active!=null && manager.active(active.instanceIdentity().instanceId()).state()==ApplicationRunState.RUNNING)
            manager.drain(active.instanceIdentity().instanceId());
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void replace(ReleaseDescriptor release,Instant deadline) throws IOException {
        if(!draining || inFlight!=0) throw new IOException("Host must drain before replacement");
        if(!Instant.now().isBefore(deadline)) throw new IOException("Replacement deadline");
        var fetched=artifacts.fetch(release.artifact().id(),deadline);
        if(!fetched.id().equals(release.artifact().id())) throw new IOException("Artifact identity mismatch");
        Path bundle=staging.resolve(fetched.id()+".j7m");
        artifacts.download(fetched.id(),bundle,deadline);
        var coordinate=ApplicationCoordinate.parse(fetched.coordinate());
        boolean present=false;
        try {
            var existing=repository.fetch(coordinate);
            if(!existing.sha256().equals(fetched.contentHash())) throw new IOException("Existing artifact content conflict");
            present=true;
        } catch(org.j7m.middleware.api.MiddlewareException failure) {
            if(failure.code()!=org.j7m.middleware.api.MiddlewareErrorCode.NOT_FOUND) throw new IOException("Existing artifact verification failed");
        }
        if(!present) {
            var published=repository.publish(new ApplicationBundle(bundle,coordinate,fetched.contentHash()),
                    new RepositoryPublishOptions(activationRequest.principalId(),activationRequest.strictVerification(),
                            activationRequest.configuration().getOrDefault("verificationProfile","development")));
            if(!published.success()) throw new IOException("Repository verification rejected release");
        }
        var verified=repository.verify(coordinate);
        if(!verified.accepted()) throw new IOException("Stored artifact verification rejected release");
        var plan=manager.planActivation(coordinate,activationRequest.strictVerification(),activationRequest.configuration());
        if(plan.state()!=ApplicationRunState.PLANNED) throw new IOException("Manager verification rejected release");
        active=afterStopped(() -> active==null ? null : manager.deactivate(active.instanceIdentity().instanceId()),
                () -> manager.activate(coordinate,activationRequest));
        if(active.state()!=ApplicationRunState.RUNNING) throw new IOException("Application activation not running");
        releaseId=release.releaseId(); generation++; draining=false;
    }
    // A host alias counter does not include direct ObjectRef calls; the manager owns the final physical stop gate.
    static <T> T afterStopped(java.util.function.Supplier<ApplicationDeploymentResult> stop, java.util.function.Supplier<T> activate) throws IOException {
        ApplicationDeploymentResult stopped=stop.get();
        if(stopped!=null&&!stopped.success()) throw new IOException("Application has not completed deactivation");
        return activate.get();
    }
    /**
     * {@inheritDoc}
     */
    @Override public byte[] invoke(String requestId,String contract,byte[] payload,Instant deadline) throws IOException {
        return invokeRevision("",requestId,contract,payload,deadline);
    }
    /**
     * {@inheritDoc}
     */
    @Override public byte[] invokeRevision(String expectedRelease,String requestId,String contract,
            byte[] payload,Instant deadline) throws IOException {
        NodeView observed = inspect();
        Contract binding; ApplicationActivation selected;
        synchronized(this) {
            if(!observed.ready() || observed.version()!=generation || draining || active==null
                    || !expectedRelease.isEmpty()&&!releaseId.equals(expectedRelease))
                throw new IOException("Revision unavailable");
            if(payload.length>32768 || !Instant.now().isBefore(deadline)) throw new IOException("Invocation bounds");
            binding=contracts.get(contract); if(binding==null) throw new SecurityException("Unknown contract");
            selected=active; inFlight++;
        }
        try(var callScope=ManagedCallContext.open(deadline)) {
            java.time.Duration remaining=java.time.Duration.between(Instant.now(),deadline);
            if(remaining.isNegative()||remaining.isZero())throw new IOException("Invocation deadline expired");
            Class<?> api=manager.serviceInterface(selected.instanceIdentity().instanceId(),binding.interfaceName());
            Object service=runtime.lookup(binding.serviceName(),api,new LookupOptions("default",Map.of(),remaining,true,false));
            Object result=api.getMethod("invoke",String.class,byte[].class).invoke(service,requestId,payload.clone());
            if(!(result instanceof byte[] bytes) || bytes.length>32768) throw new IOException("Application response bounds");
            return bytes.clone();
        } catch(ReflectiveOperationException | RuntimeException failure) {
            Throwable cause=failure instanceof InvocationTargetException invocation?invocation.getCause():failure;
            throw new IOException("Application invocation failed",cause);
        } finally { synchronized(this) { inFlight--; notifyAll(); } }
    }
}
