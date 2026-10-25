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

import java.lang.annotation.Annotation;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.transport.api.InvocationResult;

/**
 * General bounded POJO saga host and mandatory replay-phase policy. Native intents precede handlers;
 * result encoding and completion run in owned finalization after actual USER work. No callback runs
 * on timers or consensus threads. The same instance must be installed as factory and required policy.
 */
public final class ManagedSagaFactory implements ManagedHostedResources.Factory, ManagedInvocationPolicy, AutoCloseable {
    /**
     * Safe host lifecycle observation; failureCategory contains only an exception class name.
     * @param running whether local admission is armed and open
     * @param working whether an actual worker is still owned
     * @param failureCategory last framework failure type, empty before any failure
     */
    public record Status(boolean running,boolean working,String failureCategory) { }
    private enum Mode { FORWARD, COMPENSATION, RECONCILIATION }
    private final Map<String,ManagedSagaProfile> profiles;
    private final Set<Runner> runners=new HashSet<>();
    private final ScheduledThreadPoolExecutor timer;
    private final ThreadPoolExecutor workers;
    private final ThreadLocal<Attempt> current=new ThreadLocal<>();
    private boolean closed;
    private final java.util.function.LongSupplier wallClock;
    interface Source extends Flow.Publisher<ExecutionDelivery>, AutoCloseable { @Override void close(); }
    @FunctionalInterface interface Connector {
        Source open(ManagedExecutionBackend.Session session,String prefix,String owner,long lease,Instant deadline) throws java.io.IOException;
    }
    private final Connector connector;
    private static Source source(ManagedExecutionBackend.Session session,String prefix,String owner,long lease,Instant deadline) throws java.io.IOException {
        var transport=session.subscribe(prefix,"SAGA",owner,lease,deadline);
        return new Source() {
            public void subscribe(Flow.Subscriber<? super ExecutionDelivery> subscriber) { transport.subscribe(subscriber); }
            public void close() { transport.close(); }
        };
    }
    /**
     * Creates finite host executors with middleware TCCL and no inherited application locals.
     * @param profiles at most64 trusted named profiles
     * @param concurrency worker count, 1..8
     * @param pending bounded worker backlog, 1..64
     * @throws IllegalArgumentException for invalid names or capacities
     */
    public ManagedSagaFactory(Map<String,ManagedSagaProfile> profiles,int concurrency,int pending) {
        this(profiles,concurrency,pending,System::currentTimeMillis,ManagedSagaFactory::source);
    }
    ManagedSagaFactory(Map<String,ManagedSagaProfile> profiles,int concurrency,int pending,java.util.function.LongSupplier wallClock) {
        this(profiles,concurrency,pending,wallClock,ManagedSagaFactory::source);
    }
    ManagedSagaFactory(Map<String,ManagedSagaProfile> profiles,int concurrency,int pending,java.util.function.LongSupplier wallClock,Connector connector) {
        this.connector=Objects.requireNonNull(connector);
        this.wallClock=Objects.requireNonNull(wallClock);
        if(profiles==null||profiles.isEmpty()||profiles.size()>64||concurrency<1||concurrency>8||pending<1||pending>64
                ||profiles.keySet().stream().anyMatch(name->!ManagedSagaProgram.identity(name))) throw ManagedSagaProgram.invalid();
        this.profiles=Map.copyOf(profiles);
        ThreadFactory threads=work->{var thread=new Thread(null,work,"j7m-saga-worker",0,false);thread.setDaemon(true);thread.setContextClassLoader(ManagedSagaFactory.class.getClassLoader());return thread;};
        timer=new ScheduledThreadPoolExecutor(1,threads);timer.setRemoveOnCancelPolicy(true);
        workers=new ThreadPoolExecutor(concurrency,concurrency,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(pending),threads,new ThreadPoolExecutor.AbortPolicy());
    }
    /**
     * Returns the supported managed saga annotation contract.
     * @return exact immutable marker set
     */
    @Override public Set<Class<? extends Annotation>> annotationTypes() { return Set.of(SagaDefinition.class,SagaStep.class,Compensation.class,ReconciliationHandler.class); }
    /**
     * Requires the exact host policy instance, not a matching annotation token from another object.
     * @return required policy identity
     */
    public Set<ManagedInvocationPolicy> requiredPolicies() { return Set.of(this); }
    /**
     * Places durable result recording outside participating transaction finalization.
     * @return replay phase
     */
    @Override public Phase phase() { return Phase.REPLAY; }
    private ManagedSagaProgram program(ManagedHostedFeature feature,ManagedComponentDefinition<?> definition,ManagedHostedResources.Bundle bundle) {
        var profile=profiles.get(feature.attributes().get("policy"));if(profile==null) throw ManagedSagaProgram.invalid();
        return new ManagedSagaProgram(feature,definition,bundle,profile);
    }
    /**
     * Validates complete finite callback and schema metadata before constructors.
     * @param feature generated feature
     * @param definition actual generated adapter
     * @param bundle verified program identity
     * @throws IllegalArgumentException for unsupported shapes, profiles or forged descriptors
     */
    @Override public void validate(ManagedHostedFeature feature,ManagedComponentDefinition<?> definition,ManagedHostedResources.Bundle bundle) { program(feature,definition,bundle); }
    /**
     * Rejects saga callback metadata without a concrete hosted definition.
     * @param component actual component class
     * @param exported whether it is a hosted/exported entry
     * @throws IllegalArgumentException for unhosted saga metadata
     */
    @Override public void validateComponent(Class<?> component,boolean exported) {
        boolean marked=component.isAnnotationPresent(SagaDefinition.class)||Arrays.stream(component.getMethods()).anyMatch(method->method.isAnnotationPresent(SagaStep.class)||method.isAnnotationPresent(Compensation.class)||method.isAnnotationPresent(ReconciliationHandler.class));
        if(marked&&(!exported||!component.isAnnotationPresent(SagaDefinition.class))) throw ManagedSagaProgram.invalid();
    }
    /**
     * Validates policy attachment to a generated saga callback, never an ordinary RPC alias.
     * @param contract actual hosted identity
     * @param implementation concrete POJO
     * @param businessInterface generated callback declaration type
     * @throws IllegalArgumentException for missing profile or invalid exposure
     */
    @Override public void validate(ManagedServiceContract contract,Class<?> implementation,Class<?> businessInterface) {
        var definition=implementation.getAnnotation(SagaDefinition.class);
        if(definition==null) return;
        if(!contract.id().equals("hosted.saga."+definition.name())||!profiles.containsKey(definition.policy())) throw ManagedSagaProgram.invalid();
    }
    /**
     * Installs only trusted attempt context and owned result finalization; a raw Java call cannot acquire it.
     * @param contract validated hosted callback
     * @param implementation actual POJO
     * @param businessInterface actual callback type
     * @return per-export borrowed policy binding
     */
    @Override public ManagedInvocationPolicy.Binding bind(ManagedServiceContract contract,Class<?> implementation,Class<?> businessInterface) {
        validate(contract,implementation,businessInterface);
        return new ManagedInvocationPolicy.Binding() {
            public Object invoke(InvocationContext identity,Object[] arguments,Proceed next) throws Exception {
                if(!implementation.isAnnotationPresent(SagaDefinition.class)) return next.invoke();
                var attempt=current.get();
                if(attempt==null||!attempt.method().equals(contract.method())||!next.supportsResultFinalizers()) throw MiddlewareException.of(MiddlewareErrorCode.PERMISSION_DENIED,"Saga host context is unavailable");
                var context=new ManagedSagaContext(attempt.logical,attempt.step.name(),attempt.externalKey,attempt.fence,attempt.session.epoch(),attempt.mode!=Mode.FORWARD,
                        "saga.effects."+OwnershipData.hash(attempt.runner.program.profile.namespace(),attempt.runner.program.feature.id())+".",attempt.validUntil,identity.control());
                next.putContext(ManagedSagaContext.class,context);
                next.addResultFinalizer(result->{
                    try { return CompletableFuture.completedFuture(attempt.finish(result,context,identity.control())); }
                    finally { context.revoke(); }
                });
                attempt.entered=true;return next.invoke();
            }
            public void close() { }
        };
    }
    /**
     * Binds one activation without starting work before global admission commit.
     * @param feature verified feature
     * @param definition actual adapter
     * @param component actual constructed POJO
     * @param bundle verified identity
     * @param invoker trusted fixed-authority owned callback handle
     * @return owned lifecycle binding
     * @throws IllegalArgumentException for invalid component or conflicting live definition
     * @throws IllegalStateException for a closed/full factory
     */
    @Override public synchronized ManagedHostedResources.Binding bind(ManagedHostedFeature feature,ManagedComponentDefinition<?> definition,Object component,
            ManagedHostedResources.Bundle bundle,ManagedHostedResources.Invoker invoker) {
        var program=program(feature,definition,bundle);if(!definition.type().isInstance(component)) throw ManagedSagaProgram.invalid();
        if(closed||runners.size()>=64) throw new IllegalStateException("Saga factory capacity");
        for(var runner:runners) if(runner.program.feature.id().equals(feature.id())&&runner.program.feature.version()==feature.version()
                &&runner.program.profile.namespace().equals(program.profile.namespace())&&!runner.program.fingerprint.equals(program.fingerprint)) throw ManagedSagaProgram.invalid();
        var runner=new Runner(program,Objects.requireNonNull(invoker));runners.add(runner);return runner;
    }
    /**
     * Creates a resource-injectable facade for a host-selected definition and version.
     * The facade resolves only a currently admitted matching binding; it never chooses an endpoint
     * from application input and cannot continue a different persisted artifact.
     * @param policy exact host-selected backend profile
     * @param definition host-registered definition name
     * @param version positive definition version
     * @return facade that remains unavailable until actual activation admission
     * @throws IllegalArgumentException for invalid identity
     */
    public ManagedSagaClient client(String policy,String definition,int version) {
        if(!profiles.containsKey(policy)||!ManagedSagaProgram.identity(definition)||version<1) throw ManagedSagaProgram.invalid();
        return new ManagedSagaClient(this,policy,definition,version,()->true,false);
    }
    /**
     * Explicitly grants a separate host-selected administrative client view.
     * The ordinary client never inherits the backend capability configured for internal
     * reconciliation. Applications receive this view only through an explicit host registration;
     * annotations and client metadata cannot turn an ordinary view into this capability.
     * @param policy exact host-selected backend profile
     * @param definition trusted definition name
     * @param version positive definition version
     * @return administrative view still requiring the profile's real server authorization
     * @throws IllegalArgumentException for invalid identity
     */
    public ManagedSagaClient administrativeClient(String policy,String definition,int version) {
        if(!profiles.containsKey(policy)||!ManagedSagaProgram.identity(definition)||version<1) throw ManagedSagaProgram.invalid();
        return new ManagedSagaClient(this,policy,definition,version,()->true,true);
    }
    /**
     * Returns bounded lifecycle evidence without payloads or exception messages.
     * @param definition exact hosted definition
     * @param version definition version
     * @return first matching local binding's actual status
     * @throws IllegalArgumentException if no binding exists
     */
    public synchronized Status status(String definition,int version) {
        var runner=runners.stream().filter(value->value.program.feature.id().equals(definition)&&value.program.feature.version()==version).findFirst().orElseThrow(ManagedSagaProgram::invalid);
        synchronized(runner) { return new Status(runner.running,runner.working,runner.failureCategory); }
    }
    synchronized Runner runner(String policy,String definition,int version) {
        return runners.stream().filter(runner->runner.program.feature.attributes().get("policy").equals(policy)&&runner.program.feature.id().equals(definition)&&runner.program.feature.version()==version&&runner.admitted()).findFirst()
                .orElseThrow(()->MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE,"Saga definition is unavailable"));
    }
    /**
     * Closes only after every activation has actually drained and released its binding.
     * @throws IllegalStateException while a binding still owns application work
     */
    @Override public synchronized void close() {
        if(closed) return;if(!runners.isEmpty()) throw new IllegalStateException("Saga bindings must drain before close");closed=true;timer.shutdown();workers.shutdown();
    }
    final class Runner implements ManagedHostedResources.Binding {
        final ManagedSagaProgram program;final ManagedHostedResources.Invoker invoker;
        private boolean prepared,armed,running,removed,working;
        private volatile boolean stopping;
        private volatile String failureCategory="";
        private Map<String,String> trace=Map.of();
        private ScheduledFuture<?> scheduled;
        private Source source;
        private volatile Flow.Subscription subscription;
        private ManagedExecutionBackend.Session session;
        private String owner;
        private ExecutionDelivery pendingDelivery;
        Runner(ManagedSagaProgram program,ManagedHostedResources.Invoker invoker) { this.program=program;this.invoker=invoker; }
        synchronized boolean admitted() { return running&&!stopping&&invoker.admissionOpen(); }
        public void prepare() {
            synchronized(this) { if(prepared) throw new IllegalStateException("Saga already prepared"); }
            try {
                var registration=program.profile.backend().open(deadline());
                DefinitionManifests.verify(registration,"saga",program.profile.namespace(),program.feature.id(),program.feature.version(),program.fingerprint,deadline());
                for(var entry:program.profile.events().runs(program.prefix(),deadline())) {
                    var state=entry.getValue();
                    if(Set.of("COMPLETED","COMPENSATED","CANCELLED").contains(state.status())) continue;
                    var input=program.input(state.fields().getOrDefault("input",""));
                    if(!input.fingerprint().equals(program.fingerprint)) throw ManagedSagaProgram.invalid();
                }
            } catch(java.io.IOException failure) { throw new IllegalStateException("Saga program verification unavailable",failure); }
            synchronized(this) { prepared=true; }
        }
        public synchronized void start() { if(!prepared||armed) throw new IllegalStateException("Saga is not prepared");armed=true; }
        public synchronized void commitAdmission() { if(!armed||running) throw new IllegalStateException("Saga is not armed");running=true;schedule(Duration.ZERO); }
        private synchronized void schedule(Duration delay) { if(!running||stopping||scheduled!=null) return;scheduled=timer.schedule(this::dispatch,delay.toNanos(),TimeUnit.NANOSECONDS); }
        private void dispatch() {
            synchronized(this) { scheduled=null;if(!running||stopping||working) return;working=true; }
            try { workers.execute(this::work); }
            catch(RejectedExecutionException rejected) { synchronized(this) { working=false;notifyAll();schedule(program.profile.pollInterval()); } }
        }
        private void work() {
            try {
                if(!admitted()) return;
                ExecutionDelivery delivery;
                synchronized(this) { delivery=pendingDelivery;pendingDelivery=null; }
                if(delivery!=null) {
                    var input=program.input(delivery.claim().fields().getOrDefault("input",""));
                    if(!input.fingerprint().equals(program.fingerprint)||!delivery.run().equals(program.nativeId(input.logicalId()))) throw ManagedSagaProgram.invalid();
                    session.verify(deadline());
                    if(delivery.claim().status().equals("UNKNOWN")) reconcile(delivery.run(),input,delivery.claim());
                    else run(delivery.run(),input,delivery.claim());
                }
                Source connected;
                synchronized(this) { connected=source; }
                if(connected==null&&admitted()) {
                    var pinned=program.profile.backend().open(deadline());
                    String identity=UUID.randomUUID().toString();
                    var opened=connector.open(pinned,program.prefix(),identity,program.profile.lease().toMillis(),deadline());
                    boolean rejected;
                    synchronized(this) {
                        rejected=stopping;
                        if(!rejected) { source=opened;session=pinned;owner=identity; }
                    }
                    if(rejected) { opened.close();return; }
                    var subscribed=new java.util.concurrent.atomic.AtomicReference<Flow.Subscription>();
                    opened.subscribe(new Flow.Subscriber<ExecutionDelivery>() {
                        public void onSubscribe(Flow.Subscription value) { subscribed.set(value); }
                        public void onNext(ExecutionDelivery value) {
                            synchronized(Runner.this) {
                                if(stopping||source!=opened) return;
                                if(pendingDelivery!=null) throw new IllegalStateException("Saga delivery exceeded credit");
                                pendingDelivery=value;schedule(Duration.ZERO);
                            }
                        }
                        public void onError(Throwable failure) { failedSource(opened,failure.getClass().getName()); }
                        public void onComplete() { failedSource(opened,""); }
                    });
                    boolean accept;
                    synchronized(this) { accept=!stopping&&source==opened;if(accept) subscription=subscribed.get(); }
                    if(!accept&&subscribed.get()!=null) subscribed.get().cancel();
                }
                Flow.Subscription credit;
                synchronized(this) { credit=stopping?null:subscription; }
                if(credit!=null) credit.request(1);
            } catch(java.io.IOException|RuntimeException unavailable) {
                failureCategory=unavailable.getClass().getName();disconnect();
            } finally {
                synchronized(this) { working=false;notifyAll();if(pendingDelivery!=null) schedule(Duration.ZERO);else if(source==null) schedule(program.profile.pollInterval()); }
            }
        }
        private void failedSource(Source failed,String category) {
            synchronized(this) { if(source!=failed) return;source=null;subscription=null;failureCategory=category; }
            failed.close();schedule(program.profile.pollInterval());
        }
        private void disconnect() {
            Source old;
            synchronized(this) { subscription=null;old=source;source=null; }
            if(old!=null) old.close();
        }
        private Instant deadline() { return Instant.now().plus(program.profile.operationBudget()); }
        private void run(String nativeId,ManagedSagaProgram.Input input,ExecutionResult claimed) throws java.io.IOException {
            var sagas=session.sagas();
            if(!Set.of("RUNNING","COMPLETING","COMPENSATING").contains(claimed.status())||!owner.equals(claimed.fields().get("owner"))) return;
            trace=Map.copyOf(claimed.context());
            long fence=Long.parseLong(claimed.fields().get("fence"));
            long remaining=Long.parseLong(claimed.fields().get("expires"))-wallClock.getAsLong()-1000;
            if(remaining<=0) return;
            long validUntil=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.min(remaining,program.profile.lease().toMillis()-1000));
            List<ExecutionResult> history=sagas.steps(nativeId,deadline());
            if(Boolean.parseBoolean(claimed.fields().getOrDefault("compensationRequested","false"))||claimed.status().equals("COMPENSATING")) {
                for(int i=history.size()-1;i>=0&&admitted();i--) {
                    var saved=history.get(i);if(Set.of("COMPENSATED","FAILED").contains(saved.status())) continue;
                    var step=program.steps.stream().filter(candidate->candidate.name().equals(saved.fields().get("step"))).findFirst().orElseThrow(ManagedSagaProgram::invalid);
                    if(!Set.of("DONE","RETRY","COMPENSATING").contains(saved.status())||validUntil-System.nanoTime()<program.profile.handlerBudget().plus(program.profile.operationBudget()).toNanos()) return;
                    if(!saved.status().equals("COMPENSATING")) {
                        var intent=sagas.compensate("undo."+OwnershipData.hash(nativeId,step.name(),Long.toString(fence),saved.fields().get("attempts")),nativeId,owner,fence,step.name(),step.compensationIdempotent(),deadline());
                        if(!intent.status().equals("COMPENSATING")) return;
                    }
                    if(step.compensation()==null) {
                        sagas.resolveStep("missing-inverse."+OwnershipData.hash(nativeId,step.name(),Long.toString(fence)),nativeId,owner,fence,step.name(),DurableSagas.StepResolution.UNKNOWN,"NO_COMPENSATION_DECLARED",deadline());
                        return;
                    }
                    deliver(new Attempt(this,session,Mode.COMPENSATION,nativeId,input.logicalId(),step,owner,fence,validUntil,saved.fields().get("idempotencyKey")+".comp",saved.fields().get("compensation"),null));
                    if(!sagas.stepStatus(nativeId,step.name(),deadline()).status().equals("COMPENSATED")) return;
                }
                return;
            }
            String frame=Base64.getEncoder().encodeToString(input.payload());
            for(var step:program.steps) {
                if(!admitted()||validUntil-System.nanoTime()<program.profile.handlerBudget().plus(program.profile.operationBudget()).toNanos()) return;
                var prior=history.stream().filter(saved->saved.fields().get("step").equals(step.name())).findFirst().orElse(null);
                if(prior!=null&&prior.status().equals("DONE")) {frame=prior.fields().get("stepResult");continue;}
                if(prior!=null&&!prior.status().equals("ACTIVE")) return;
                String key="step."+OwnershipData.hash(nativeId,step.name());
                if(prior==null) {
                    var intent=sagas.step("intent."+OwnershipData.hash(nativeId,step.name(),Long.toString(fence)),nativeId,owner,fence,step.name(),key,frame,step.idempotent(),step.order()==program.steps.size()-1,deadline());
                    if(!Set.of("RUNNING","COMPLETING").contains(intent.status())) return;
                } else { if(!step.idempotent()) return;frame=prior.fields().get("compensation");key=prior.fields().get("idempotencyKey"); }
                deliver(new Attempt(this,session,Mode.FORWARD,nativeId,input.logicalId(),step,owner,fence,validUntil,key,frame,null));
                var saved=sagas.stepStatus(nativeId,step.name(),deadline());if(!saved.status().equals("DONE")) return;frame=saved.fields().get("stepResult");
            }
        }
        private void reconcile(String nativeId,ManagedSagaProgram.Input input,ExecutionResult state) throws java.io.IOException {
            if(program.reconciliation==null||program.profile.administration()==null) return;
            var session=program.profile.administration().open(deadline());
            var history=session.sagas().steps(nativeId,deadline());
            var saved=history.stream().filter(step->Set.of("ACTIVE","COMPENSATING").contains(step.status())).findFirst().orElse(null);if(saved==null) return;
            var step=program.steps.stream().filter(candidate->candidate.name().equals(saved.fields().get("step"))).findFirst().orElseThrow(ManagedSagaProgram::invalid);
            trace=Map.copyOf(state.context());
            var attempt=new Attempt(this,session,Mode.RECONCILIATION,nativeId,input.logicalId(),step,"",Long.parseLong(state.fields().get("fence")),
                    System.nanoTime()+program.profile.handlerBudget().plus(program.profile.operationBudget()).toNanos(),saved.fields().get("idempotencyKey")+(saved.status().equals("COMPENSATING")?".comp":""),saved.fields().get("compensation"),saved);
            deliver(attempt);
        }
        private boolean deliver(Attempt attempt) {
            current.set(attempt);
            try {
                int bytes=Math.addExact(Base64.getDecoder().decode(attempt.input).length,256);
                invoker.invokeDecoded(attempt.method(),attempt::arguments,new InvocationOptions("saga-callback."+UUID.randomUUID(),program.profile.handlerBudget(),trace),bytes).toCompletableFuture().join();
                return attempt.finished;
            } catch(RuntimeException failure) {
                failureCategory=failure.getClass().getName();
                if(attempt.mode!=Mode.RECONCILIATION&&!attempt.finished) {
                    try { attempt.session.sagas().resolveStep("unknown."+OwnershipData.hash(attempt.nativeId,attempt.step.name(),Long.toString(attempt.fence),attempt.mode.name()),attempt.nativeId,attempt.owner,attempt.fence,attempt.step.name(),DurableSagas.StepResolution.UNKNOWN,"callback-outcome-unavailable",deadline()); }
                    catch(java.io.IOException|RuntimeException uncertain) { failureCategory=uncertain.getClass().getName(); /* Native takeover retains non-idempotent uncertainty. */ }
                }
                return false;
            } finally { current.remove(); }
        }
        public void stopAdmission() {
            synchronized(this) { stopping=true;running=false;pendingDelivery=null;if(scheduled!=null) scheduled.cancel(false);scheduled=null; }
            disconnect();
        }
        public boolean drain(Duration budget) throws InterruptedException {
            stopAdmission();long end=System.nanoTime()+budget.toNanos();
            synchronized(this) {
                while(working) {long left=end-System.nanoTime();if(left<=0) return false;TimeUnit.NANOSECONDS.timedWait(this,left);}
                return true;
            }
        }
        public void close() { synchronized(this) { if(working||running) throw new IllegalStateException("Saga binding is not drained");if(removed) return;removed=true; }
            synchronized(ManagedSagaFactory.this) { runners.remove(this); }
        }
    }
    private final class Attempt {
        final Runner runner;final ManagedExecutionBackend.Session session;final Mode mode;final String nativeId,logical,owner,externalKey,input;final ManagedSagaProgram.Step step;final long fence,validUntil;final ExecutionResult uncertain;
        boolean entered,finished;
        Attempt(Runner runner,ManagedExecutionBackend.Session session,Mode mode,String nativeId,String logical,ManagedSagaProgram.Step step,String owner,long fence,long validUntil,String externalKey,String input,ExecutionResult uncertain) {
            this.runner=runner;this.session=session;this.mode=mode;this.nativeId=nativeId;this.logical=logical;this.step=step;this.owner=owner;this.fence=fence;this.validUntil=validUntil;this.externalKey=externalKey;this.input=input;this.uncertain=uncertain;
        }
        ManagedMethod method() { return mode==Mode.FORWARD?step.callback():mode==Mode.COMPENSATION?step.compensation():runner.program.reconciliation; }
        Object[] arguments() throws java.io.IOException {
            Object argument=argument();
            if(mode!=Mode.RECONCILIATION) {
                var actual=session.sagas().verify("verify."+UUID.randomUUID(),nativeId,owner,fence,runner.deadline());
                if(!Set.of("RUNNING","COMPLETING","COMPENSATING").contains(actual.status())||!owner.equals(actual.fields().get("owner"))||!Long.toString(fence).equals(actual.fields().get("fence")))
                    throw MiddlewareException.of(MiddlewareErrorCode.STALE_FENCING_TOKEN,"Saga invocation ownership is unavailable");
            }
            return new Object[]{argument};
        }
        Object argument() {
            Object value=runner.program.value(input,step.input());
            return mode==Mode.RECONCILIATION?new ManagedSagaUncertainty(logical,step.name(),externalKey,uncertain.status().equals("COMPENSATING"),Long.parseLong(uncertain.fields().get("version")),value):value;
        }
        InvocationResult finish(InvocationResult result,ManagedSagaContext context,InvocationControl control) {
            var profile=runner.program.profile;Duration left=control.remainingBudget();
            Instant deadline=Instant.now().plus(left.compareTo(profile.operationBudget())<0?left:profile.operationBudget());
            try {
                if(result.failure()!=null||left.isZero()||System.nanoTime()>=validUntil) {
                    if(mode!=Mode.RECONCILIATION) session.sagas().resolveStep("failed."+OwnershipData.hash(nativeId,step.name(),Long.toString(fence),mode.name()),nativeId,owner,fence,step.name(),DurableSagas.StepResolution.UNKNOWN,"callback-outcome-unavailable",Instant.now().plus(profile.operationBudget()));
                    return InvocationResult.failure(MiddlewareException.of(MiddlewareErrorCode.STALE_FENCING_TOKEN,"Saga completion is uncertain"));
                }
                ExecutionResult persisted;
                String request="complete."+OwnershipData.hash(nativeId,step.name(),Long.toString(fence),mode.name());
                if(mode==Mode.FORWARD) persisted=session.sagas().completeStep(request,nativeId,owner,fence,step.name(),runner.program.frame(result.value(),step.output()),context.effects(),deadline);
                else if(mode==Mode.COMPENSATION) {
                    if(result.value()!=null) throw ManagedSagaProgram.invalid();
                    persisted=session.sagas().compensationResult(request,nativeId,owner,fence,step.name(),true,"compensated",deadline);
                } else {
                    if(!(result.value() instanceof ManagedSagaDecision decision)) throw ManagedSagaProgram.invalid();
                    if(decision.outcome()==ManagedSagaDecision.Outcome.UNRESOLVED) { finished=true;return InvocationResult.success(null); }
                    String frame=uncertain.status().equals("COMPENSATING")?"compensation-established":runner.program.frame(decision.result(),step.output());
                    persisted=session.sagas().reconcile("reconcile."+OwnershipData.hash(nativeId,step.name(),uncertain.fields().get("version")),nativeId,step.name(),Long.parseLong(uncertain.fields().get("version")),decision.outcome()==ManagedSagaDecision.Outcome.SUCCEEDED,frame,deadline);
                }
                if(!Set.of("RUNNING","COMPLETING","COMPLETED","COMPENSATING","COMPENSATED").contains(persisted.status())) throw MiddlewareException.of(MiddlewareErrorCode.STALE_FENCING_TOKEN,"Saga completion was not accepted");
                finished=true;return InvocationResult.success(null);
            } catch(java.io.IOException|RuntimeException failure) { context.revoke();return InvocationResult.failure(MiddlewareException.of(MiddlewareErrorCode.UNAVAILABLE,"Saga completion is uncertain")); }
        }
    }
}
