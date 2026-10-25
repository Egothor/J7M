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

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.j7m.middleware.annotations.Checkpoint;
import org.j7m.middleware.annotations.DurableJob;
import org.j7m.middleware.annotations.Progress;
import org.j7m.middleware.annotations.Restore;
import org.j7m.middleware.annotations.Schedule;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.MiddlewareException;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.application.api.InvocationControl;
import org.j7m.middleware.application.api.J7mContext;
import org.j7m.middleware.application.api.ManagedComponentDefinition;
import org.j7m.middleware.application.api.ManagedHostedFeature;
import org.j7m.middleware.application.api.ManagedServiceContract;
import org.j7m.middleware.application.components.ManagedHostedResources;
import org.j7m.middleware.application.components.ManagedInvocationPolicy;
import org.j7m.middleware.distributed.ExecutionDelivery;
import org.j7m.middleware.distributed.ExecutionResult;
import org.j7m.middleware.distributed.ExecutionSession;
import org.j7m.middleware.distributed.ReplicatedResources;
import org.j7m.middleware.transport.api.InvocationResult;

/**
 * General POJO durable job host using actual owned calls and reactive native claim delivery.
 * Install this exact instance as both hosted factory and REPLAY policy. Native completion follows
 * USER settlement and inner transaction finalizers; checkpoint/progress are explicit safe points.
 * Bounded workers, reconnect timers and renewals do not turn cancellation into physical completion.
 */
public final class ManagedJobFactory implements ManagedHostedResources.Factory,ManagedInvocationPolicy,AutoCloseable {
    /**
     * Actual local lifecycle, independent of a public stage's cancellation.
     */
    public enum Phase {
        /**
         * Bound without initialized resources.
         */ NEW,
        /**
         * Prepared with application admission closed.
         */ PREPARED,
        /**
         * Started locally, awaiting global admission commit.
         */ ARMED,
        /**
         * Admitting native deliveries.
         */ RUNNING,
        /**
         * Admission closed while actual work exits.
         */ DRAINING,
        /**
         * Explicit local failure requiring attention.
         */ FAILED,
        /**
         * Fully drained and released.
         */ CLOSED
    }
    /**
     * Fixed redacted diagnostics; none contains input, exception text or credentials.
     */
    public enum Reason {
        /**
         * No outstanding local failure.
         */ NONE,
        /**
         * Native transport/quorum outcome is unavailable.
         */ SOURCE_UNAVAILABLE,
        /**
         * Bounded host worker admission refused work.
         */ OVERLOADED,
        /**
         * Native ownership, lease or original budget is no longer live.
         */ CLAIM_REVOKED,
        /**
         * Program, schema or immutable definition is incompatible.
         */ DEFINITION_CONFLICT,
        /**
         * A host policy rejected the callback before job entry.
         */ HANDLER_REJECTED,
        /**
         * An unsupported native protocol outcome was observed.
         */ PROTOCOL_REJECTED
    }
    /**
     * Immutable local host observation.
     * @param phase actual lifecycle
     * @param reason bounded diagnostic category
     * @param working whether actual connection, handler or finalization work remains
     */
    public record Status(Phase phase,Reason reason,boolean working) { }
    private final Map<String,ManagedJobProfile> profiles;
    private final Map<String,ManagedScheduleProfile> triggers;
    private final ThreadPoolExecutor workers,maintenance;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Set<Runner> runners=new HashSet<>();
    private final ThreadLocal<Frame> handoff=new ThreadLocal<>();
    private boolean closed;
    /**
     * Creates a bounded host without scheduled definitions.
     * @param profiles immutable named native job sources, at most64
     * @param maximumWorkers concurrent synchronous callback roots,1..16
     * @param maximumQueued bounded ready queue,1..64
     */
    public ManagedJobFactory(Map<String,ManagedJobProfile> profiles,int maximumWorkers,int maximumQueued){this(profiles,Map.of(),maximumWorkers,maximumQueued);}
    /**
     * Creates explicit native job and durable trigger capabilities without starting work.
     * @param profiles immutable named job profiles, at most64
     * @param triggers immutable named durable trigger profiles, at most64
     * @param maximumWorkers concurrent synchronous roots,1..16
     * @param maximumQueued bounded ready queue,1..64
     * @throws IllegalArgumentException for invalid names or capacity
     * @throws NullPointerException for missing profiles
     */
    public ManagedJobFactory(Map<String,ManagedJobProfile> profiles,Map<String,ManagedScheduleProfile> triggers,int maximumWorkers,int maximumQueued){
        this.profiles=Map.copyOf(profiles);this.triggers=Map.copyOf(triggers);
        if(this.profiles.size()>64||this.triggers.size()>64||this.profiles.keySet().stream().anyMatch(key->!id(key))||this.triggers.keySet().stream().anyMatch(key->!id(key))||maximumWorkers<1||maximumWorkers>16||maximumQueued<1||maximumQueued>64)throw new IllegalArgumentException("Invalid job host capacity");
        workers=pool(maximumWorkers,maximumQueued,"j7m-job-worker");maintenance=pool(2,64,"j7m-job-renew");
        scheduler=new ScheduledThreadPoolExecutor(1,action->thread("j7m-job-timer",action));scheduler.setRemoveOnCancelPolicy(true);scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setKeepAliveTime(1,TimeUnit.SECONDS);scheduler.allowCoreThreadTimeOut(true);
    }
    private static ThreadPoolExecutor pool(int count,int maximum,String name){var pool=new ThreadPoolExecutor(count,count,1,TimeUnit.SECONDS,new ArrayBlockingQueue<>(maximum),action->thread(name,action),new ThreadPoolExecutor.AbortPolicy());pool.allowCoreThreadTimeOut(true);return pool;}
    private static Thread thread(String name,Runnable action){Thread thread=new Thread(null,action,name,0,false);thread.setDaemon(true);thread.setContextClassLoader(ManagedJobFactory.class.getClassLoader());return thread;}
    private static boolean id(String value){return value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");}
    /**
     * Returns actual finite job/hook capabilities.
     * @return immutable marker allowlist
     */
    @Override public Set<Class<? extends java.lang.annotation.Annotation>> annotationTypes(){return Set.of(DurableJob.class,Schedule.class,Checkpoint.class,Restore.class,Progress.class);}
    /**
     * Requires the same actual guard instance before component construction.
     * @return this exact policy installation
     */
    @Override public Set<ManagedInvocationPolicy> requiredPolicies(){return Set.of(this);}
    /**
     * Places native replay/completion outside transaction commit and inside security/admission.
     * @return REPLAY phase
     */
    @Override public ManagedInvocationPolicy.Phase phase(){return ManagedInvocationPolicy.Phase.REPLAY;}
    private ManagedJobProgram program(ManagedHostedFeature feature,ManagedComponentDefinition<?> definition,ManagedHostedResources.Bundle bundle){
        var profile=profiles.get(feature.attributes().get("policy"));if(profile==null)throw ManagedJobProgram.invalid();var program=new ManagedJobProgram(feature,definition,bundle,profile);
        if(!program.schedule.isEmpty()){var trigger=triggers.get(program.schedule);if(trigger==null||profile.serial()!=(trigger.overlap()==ManagedScheduleProfile.Overlap.FORBID))throw ManagedJobProgram.invalid();}
        return program;
    }
    /**
     * Validates the complete finite plan, schema allowlist and selected policies before constructors.
     * @param feature generated job declaration
     * @param definition exact generated adapter
     * @param bundle verified artifact/configuration identity
     */
    @Override public void validate(ManagedHostedFeature feature,ManagedComponentDefinition<?> definition,ManagedHostedResources.Bundle bundle){program(feature,definition,bundle);}
    /**
     * Rejects job metadata without an actual hosted entry boundary.
     * @param component declared finite component
     * @param exported whether the host has actual service/callback exposure
     */
    @Override public void validateComponent(Class<?> component,boolean exported){if(marked(component)&&!exported)throw ManagedJobProgram.invalid();}
    private static boolean marked(Class<?> component){return Arrays.stream(component.getMethods()).anyMatch(method->method.isAnnotationPresent(DurableJob.class)||method.isAnnotationPresent(Checkpoint.class)||method.isAnnotationPresent(Restore.class)||method.isAnnotationPresent(Progress.class)||method.isAnnotationPresent(Schedule.class));}
    /**
     * Allows only the actual hosted contract for a job component, never an RPC alias.
     * @param contract full hosted identity
     * @param implementation concrete component
     * @param businessInterface finite host callback type
     */
    @Override public void validate(ManagedServiceContract contract,Class<?> implementation,Class<?> businessInterface){
        var entries=Arrays.stream(implementation.getMethods()).filter(method->method.isAnnotationPresent(DurableJob.class)).toList();if(entries.isEmpty())return;
        if(entries.size()!=1||!contract.id().equals("hosted.durable-job."+entries.getFirst().getAnnotation(DurableJob.class).value()))throw ManagedJobProgram.invalid();
    }
    /**
     * Installs only a genuine captured claim context and owned result finalizer.
     * @param contract validated hosted contract
     * @param implementation concrete component
     * @param businessInterface finite generated host type
     * @return borrowed per-boundary policy state
     */
    @Override public ManagedInvocationPolicy.Binding bind(ManagedServiceContract contract,Class<?> implementation,Class<?> businessInterface){
        validate(contract,implementation,businessInterface);
        return new ManagedInvocationPolicy.Binding(){
            @Override public Object invoke(InvocationContext identity,Object[] arguments,Proceed next)throws Exception{
                var frame=handoff.get();if(!marked(implementation))return next.invoke();
                if(frame==null||!frame.runner.program.entry.equals(contract.method())||!next.supportsResultFinalizers())throw failure(MiddlewareErrorCode.PERMISSION_DENIED);
                frame.control=identity.control();var context=new ManagedJobContext(frame.logical,frame.fence,frame.session.epoch(),identity.control(),frame);frame.context=context;
                next.putContext(ManagedJobContext.class,context);next.addResultFinalizer(result->CompletableFuture.completedFuture(frame.finish(result)));
                frame.entered=true;if(!frame.valid())throw failure(MiddlewareErrorCode.STALE_FENCING_TOKEN);
                if(frame.runner.program.stateType!=Void.class){frame.hook=true;try{Object state=frame.saved.state().length==0?null:frame.runner.program.codec.decode(frame.saved.state(),frame.runner.program.stateType);frame.runner.program.definition.restore(frame.runner.component,state);}finally{frame.hook=false;}}
                if(!frame.live())throw failure(MiddlewareErrorCode.STALE_FENCING_TOKEN);
                return next.invoke();
            }
            @Override public void close(){ }
        };
    }
    /**
     * Binds one real activation component without admitting work until global commit.
     * @param feature validated feature
     * @param definition actual generated job adapter
     * @param component activation-owned POJO, never closed by this factory
     * @param bundle verified immutable program identity
     * @param invoker fixed-principal owned call handle
     * @return activation-owned lifecycle
     */
    @Override public synchronized ManagedHostedResources.Binding bind(ManagedHostedFeature feature,ManagedComponentDefinition<?> definition,Object component,ManagedHostedResources.Bundle bundle,ManagedHostedResources.Invoker invoker){
        var program=program(feature,definition,bundle);if(!definition.type().isInstance(component)||closed||runners.size()>=64)throw ManagedJobProgram.invalid();
        for(var runner:runners)if(runner.program.profile==program.profile&&runner.program.feature.id().equals(feature.id())&&runner.program.feature.version()==feature.version()&&!runner.program.fingerprint.equals(program.fingerprint))throw ManagedJobProgram.invalid();
        var runner=new Runner(program,component,Objects.requireNonNull(invoker));runners.add(runner);return runner;
    }
    /**
     * Selects producer metadata by the exact trusted profile, definition and version.
     * @param policy installed profile name
     * @param definition stable job definition identity
     * @param version positive logical contract version
     * @return independently closeable borrowed-endpoint producer/query facade
     * @throws IllegalArgumentException if absent or ambiguous
     */
    public synchronized ManagedJobClient client(String policy,String definition,int version){
        if(closed||!profiles.containsKey(policy)||!id(definition)||version<1)throw ManagedJobProgram.invalid();
        return new ManagedJobClient(()->selected(policy,definition,version));
    }
    private synchronized ManagedJobProgram selected(String policy,String definition,int version){
        ManagedJobProgram found=null;
        for(var runner:runners)if(runner.program.feature.attributes().get("policy").equals(policy)&&runner.program.feature.id().equals(definition)&&runner.program.feature.version()==version&&runner.admitted()){
            if(found!=null&&!found.fingerprint.equals(runner.program.fingerprint))throw ManagedJobProgram.invalid();found=runner.program;
        }
        if(closed||found==null)throw failure(MiddlewareErrorCode.UNAVAILABLE);return found;
    }
    /**
     * Returns bounded local observations without application data.
     * @return at most64 immutable lifecycle observations
     */
    public synchronized List<Status> statuses(){return runners.stream().map(Runner::status).toList();}
    /**
     * Closes worker resources only after all bound resources have actually drained and closed.
     * @throws IllegalStateException if a binding still owns actual work
     */
    @Override public synchronized void close(){if(closed)return;if(!runners.isEmpty())throw new IllegalStateException("Jobs must drain before host close");closed=true;workers.shutdown();maintenance.shutdown();scheduler.shutdown();}
    private static MiddlewareException failure(MiddlewareErrorCode code){return MiddlewareException.of(code,"Managed job ownership is unavailable");}
    private final class Runner implements ManagedHostedResources.Binding {
        final ManagedJobProgram program;final Object component;final ManagedHostedResources.Invoker invoker;
        volatile Phase phase=Phase.NEW;volatile Reason reason=Reason.NONE;
        boolean working,triggerWorking;ScheduledFuture<?> scheduled,triggerTimer;
        ExecutionSession source;Flow.Subscription subscription;ManagedExecutionBackend.Session session;String owner;
        ExecutionDelivery pending;volatile Frame frame;DurableSchedules trigger;
        Runner(ManagedJobProgram program,Object component,ManagedHostedResources.Invoker invoker){this.program=program;this.component=component;this.invoker=invoker;}
        synchronized Status status(){return new Status(phase,reason,working||triggerWorking);}
        boolean admitted(){return phase==Phase.RUNNING&&invoker.admissionOpen();}
        Instant deadline(){return Instant.now().plus(program.profile.operationBudget());}
        @Override public void prepare()throws IOException{
            synchronized(this){if(phase!=Phase.NEW)throw new IllegalStateException("Job already prepared");working=true;}
            try{
                DefinitionManifests.verify(program.profile.backend().open(deadline()),"job",program.profile.namespace(),program.feature.id(),program.feature.version(),program.fingerprint,deadline());
                if(!program.schedule.isEmpty()){
                    var profile=triggers.get(program.schedule);byte[] input=program.codec.encode(profile.input(),program.inputType);program.inputBytes("validation",input);
                    var client=new ManagedJobClient(program,this::admitted);trigger=new DurableSchedules(profile,program.profile.namespace()+"/"+program.feature.id()+"/"+program.feature.version(),OwnershipData.hash(program.fingerprint,Base64.getEncoder().encodeToString(input)),program.profile.serial(),(id,ignored,deadline)->client.submitEncoded(id,input,deadline));trigger.prepare(deadline());
                }
                synchronized(this){if(phase==Phase.NEW)phase=Phase.PREPARED;}
            }finally{synchronized(this){working=false;notifyAll();}}
        }
        @Override public synchronized void start(){if(phase!=Phase.PREPARED)throw new IllegalStateException("Job not prepared");phase=Phase.ARMED;}
        @Override public synchronized void commitAdmission(){if(phase!=Phase.ARMED)throw new IllegalStateException("Job not armed");phase=Phase.RUNNING;schedule(Duration.ZERO);if(trigger!=null)scheduleTrigger(Duration.ZERO);}
        private synchronized void schedule(Duration delay){if(phase!=Phase.RUNNING||scheduled!=null)return;scheduled=scheduler.schedule(this::submit,Math.max(0,delay.toNanos()),TimeUnit.NANOSECONDS);}
        private void submit(){synchronized(this){scheduled=null;if(phase!=Phase.RUNNING||working)return;working=true;}try{workers.execute(this::run);}catch(RejectedExecutionException overloaded){synchronized(this){working=false;reason=Reason.OVERLOADED;notifyAll();schedule(program.profile.reconnectDelay());}}}
        private void run(){
            boolean transferred=false;
            try{
                if(!admitted())return;
                ExecutionDelivery delivery;synchronized(this){delivery=pending;pending=null;}
                if(delivery!=null){
                    var input=program.input(delivery.claim().fields().getOrDefault("input",""));if(!delivery.run().equals(program.nativeId(input.logical()))||delivery.partitionEpoch()!=session.epoch()||!owner.equals(delivery.claim().fields().get("owner")))throw ManagedJobProgram.invalid();
                    var attempt=new Frame(this,session,delivery,input);frame=attempt;
                    long start=System.nanoTime();var renewed=session.jobs().renew(UUID.randomUUID().toString(),delivery.run(),owner,attempt.fence,program.profile.lease().toMillis(),deadline());attempt.acceptRenew(renewed,start);
                    handoff.set(attempt);
                    try{
                        var result=invoker.invokeDecoded(program.entry,()->{
                            attempt.control=J7mContext.currentInvocation().control();attempt.scheduleRenew();
                            if(!attempt.valid())throw failure(MiddlewareErrorCode.STALE_FENCING_TOKEN);
                            return new Object[]{program.codec.decode(input.payload(),program.inputType)};
                        },new InvocationOptions(delivery.run(),program.profile.handlerBudget(),delivery.claim().context()),delivery.encodedBytes());
                        transferred=true;result.whenComplete((value,error)->completed(attempt,error));
                    }finally{handoff.remove();}
                }else{
                    synchronized(this){if(source!=null)return;}
                    var pinned=program.profile.backend().open(deadline());String member=UUID.randomUUID().toString();var opened=pinned.subscribe(program.prefix(),"JOB",member,program.profile.lease().toMillis(),deadline());
                    synchronized(this){if(!admitted()){opened.close();return;}source=opened;session=pinned;owner=member;}
                    opened.subscribe(new Flow.Subscriber<ExecutionDelivery>(){
                        @Override public void onSubscribe(Flow.Subscription value){synchronized(Runner.this){subscription=value;if(!admitted()){value.cancel();return;}}value.request(1);}
                        @Override public void onNext(ExecutionDelivery value){synchronized(Runner.this){if(!admitted())return;if(pending!=null)throw new IllegalStateException("Job exceeded delivery credit");pending=value;schedule(Duration.ZERO);}}
                        @Override public void onError(Throwable error){disconnect();schedule(program.profile.reconnectDelay());}
                        @Override public void onComplete(){disconnect();schedule(program.profile.reconnectDelay());}
                    });
                }
            }catch(IOException unavailable){reason=Reason.SOURCE_UNAVAILABLE;disconnect();}
            catch(MiddlewareException denied){reason=denied.code()==MiddlewareErrorCode.STALE_FENCING_TOKEN?Reason.CLAIM_REVOKED:Reason.HANDLER_REJECTED;if(denied.code()!=MiddlewareErrorCode.STALE_FENCING_TOKEN)phase=Phase.FAILED;disconnect();}
            catch(RuntimeException invalid){reason=Reason.DEFINITION_CONFLICT;phase=Phase.FAILED;disconnect();}
            finally{if(!transferred)synchronized(this){if(frame!=null){frame.stop();frame=null;}working=false;notifyAll();if(phase==Phase.RUNNING){if(pending!=null)schedule(Duration.ZERO);else if(source==null)schedule(program.profile.reconnectDelay());}}}
        }
        private void completed(Frame attempt,Throwable error){
            attempt.stop();Flow.Subscription credit;
            synchronized(this){if(frame==attempt)frame=null;working=false;if(!attempt.entered&&!attempt.completed){phase=Phase.FAILED;reason=Reason.HANDLER_REJECTED;}else if(!attempt.completed)reason=Reason.CLAIM_REVOKED;else reason=Reason.NONE;credit=admitted()?subscription:null;notifyAll();if(phase==Phase.RUNNING&&source==null)schedule(program.profile.reconnectDelay());}
            if(credit!=null)credit.request(1);
        }
        private void disconnect(){ExecutionSession previous;synchronized(this){previous=source;source=null;subscription=null;}if(previous!=null)previous.close();}
        private synchronized void scheduleTrigger(Duration delay){if(phase!=Phase.RUNNING||triggerTimer!=null)return;Duration bounded=delay.compareTo(Duration.ofDays(1))>0?Duration.ofDays(1):delay;triggerTimer=scheduler.schedule(this::submitTrigger,Math.max(0,bounded.toNanos()),TimeUnit.NANOSECONDS);}
        private void submitTrigger(){synchronized(this){triggerTimer=null;if(phase!=Phase.RUNNING||triggerWorking)return;triggerWorking=true;}try{workers.execute(this::runTrigger);}catch(RejectedExecutionException overloaded){synchronized(this){triggerWorking=false;reason=Reason.OVERLOADED;notifyAll();scheduleTrigger(program.profile.reconnectDelay());}}}
        private void runTrigger(){Duration next=program.profile.reconnectDelay();try{if(admitted()){var tick=trigger.tick(deadline());next=Duration.between(Instant.now(),tick.nextDue());}}catch(IOException unavailable){reason=Reason.SOURCE_UNAVAILABLE;}catch(RuntimeException rejected){reason=Reason.PROTOCOL_REJECTED;phase=Phase.FAILED;}finally{synchronized(this){triggerWorking=false;notifyAll();if(phase==Phase.RUNNING)scheduleTrigger(next.isNegative()?Duration.ZERO:next);}}}
        @Override public synchronized void stopAdmission(){if(phase==Phase.CLOSED)return;phase=Phase.DRAINING;if(scheduled!=null){scheduled.cancel(false);scheduled=null;}if(triggerTimer!=null){triggerTimer.cancel(false);triggerTimer=null;}if(frame!=null)frame.revoke();}
        @Override public boolean drain(Duration budget)throws InterruptedException{
            stopAdmission();disconnect();long end=System.nanoTime()+Math.max(0,budget.toNanos());synchronized(this){while(working||triggerWorking){long left=end-System.nanoTime();if(left<=0)return false;TimeUnit.NANOSECONDS.timedWait(this,left);}return true;}
        }
        @Override public void close(){synchronized(this){if(phase==Phase.CLOSED)return;if(working||triggerWorking||phase!=Phase.DRAINING&&phase!=Phase.NEW&&phase!=Phase.PREPARED)throw new IllegalStateException("Job requires actual drain");phase=Phase.CLOSED;if(trigger!=null){trigger.close();trigger=null;}}synchronized(ManagedJobFactory.this){runners.remove(this);}}
    }
    private final class Frame implements ManagedJobContext.Access {
        final Runner runner;final ManagedExecutionBackend.Session session;final ExecutionDelivery delivery;final String logical,nativeId,owner;final long fence;
        final Object operation=new Object(),renewal=new Object();ManagedJobProgram.Saved saved;
        volatile InvocationControl control;volatile ManagedJobContext context;volatile long validUntil;volatile boolean revoked,closed,hook,entered,completed;
        ScheduledFuture<?> renewalTimer;Future<?> renewalTask;
        Frame(Runner runner,ManagedExecutionBackend.Session session,ExecutionDelivery delivery,ManagedJobProgram.Input input){this.runner=runner;this.session=session;this.delivery=delivery;logical=input.logical();nativeId=delivery.run();owner=delivery.claim().fields().get("owner");fence=Long.parseLong(delivery.claim().fields().get("fence"));saved=runner.program.saved(delivery.claim().fields().getOrDefault("checkpoint",""));}
        private boolean live(){return !revoked&&!closed&&runner.admitted()&&validUntil-System.nanoTime()>0&&(control==null||!control.cancellationRequested()&&!control.remainingBudget().isZero());}
        @Override public boolean valid(){return live()&&!hook;}
        private Instant deadline(){Duration remaining=control==null?runner.program.profile.operationBudget():control.remainingBudget();Duration budget=remaining.compareTo(runner.program.profile.operationBudget())<0?remaining:runner.program.profile.operationBudget();return Instant.now().plus(budget);}
        void acceptRenew(ExecutionResult result,long start){if(!result.status().equals("RUNNING")||!owner.equals(result.fields().get("owner"))||!Long.toString(fence).equals(result.fields().get("fence"))){revoke();throw failure(MiddlewareErrorCode.STALE_FENCING_TOKEN);}validUntil=start+runner.program.profile.lease().minusSeconds(1).toNanos();}
        void scheduleRenew(){synchronized(renewal){if(closed||revoked||renewalTimer!=null)return;renewalTimer=scheduler.schedule(this::submitRenew,runner.program.profile.renewInterval().toNanos(),TimeUnit.NANOSECONDS);}}
        private void submitRenew(){synchronized(renewal){renewalTimer=null;if(closed||revoked)return;try{renewalTask=maintenance.submit(this::renew);}catch(RejectedExecutionException overloaded){revoke();}}}
        private void renew(){try{synchronized(operation){if(!live()){revoke();return;}long start=System.nanoTime();acceptRenew(session.jobs().renew(UUID.randomUUID().toString(),nativeId,owner,fence,runner.program.profile.lease().toMillis(),deadline()),start);}}catch(IOException|RuntimeException uncertain){revoke();}finally{synchronized(renewal){renewalTask=null;if(!closed&&!revoked)scheduleRenew();}}}
        @Override public void checkpoint(boolean progressOnly){
            synchronized(operation){if(!valid())throw failure(MiddlewareErrorCode.STALE_FENCING_TOKEN);var program=runner.program;if(progressOnly?program.progressType==Void.class:program.stateType==Void.class)throw ManagedJobProgram.invalid();
                byte[] state=saved.state(),progress=saved.progress();hook=true;
                try{if(!progressOnly)state=program.codec.encode(program.definition.checkpoint(runner.component),program.stateType);if(program.progressType!=Void.class)progress=program.codec.encode(program.definition.progress(runner.component),program.progressType);}
                catch(Exception error){throw failure(MiddlewareErrorCode.REMOTE_EXCEPTION);}finally{hook=false;}
                String frame=program.saved(state,progress);
                try{var result=session.jobs().checkpoint(UUID.randomUUID().toString(),nativeId,owner,fence,frame,deadline());if(!result.status().equals("RUNNING")){revoke();throw failure(MiddlewareErrorCode.STALE_FENCING_TOKEN);}saved=new ManagedJobProgram.Saved(state,progress);}
                catch(IOException unavailable){revoke();throw failure(MiddlewareErrorCode.UNAVAILABLE);}
            }
        }
        @Override public String key(String key){return ManagedJobClient.effectKey(runner.program,key);}
        @Override public ReplicatedResources.Value read(String key){synchronized(operation){if(!valid())throw failure(MiddlewareErrorCode.STALE_FENCING_TOKEN);try{var result=session.client().query("E_VALUE",key(key),deadline());if(result.status().equals("NOT_FOUND"))return null;if(!result.status().equals("OK"))throw new IOException("Job value unavailable");return new ReplicatedResources.Value(result.fields().get("value"),Long.parseLong(result.fields().get("version")));}catch(IOException unavailable){revoke();throw failure(MiddlewareErrorCode.UNAVAILABLE);}}}
        InvocationResult finish(InvocationResult result){synchronized(operation){
            try{
                if(!valid()){revoke();return InvocationResult.failure(failure(MiddlewareErrorCode.UNAVAILABLE));}
                InvocationResult outcome=result;
                String frame;
                try{frame=runner.program.outcome(outcome.value(),outcome.failure());}catch(RuntimeException codecFailure){outcome=InvocationResult.failure(failure(MiddlewareErrorCode.CODEC_ERROR));frame=runner.program.outcome(null,outcome.failure());}
                var completion=session.jobs().complete("job.complete."+OwnershipData.hash(nativeId,owner,Long.toString(fence)),nativeId,owner,fence,frame,outcome.failure()==null?context.effects():List.of(),deadline());
                if(!completion.status().equals("COMPLETED"))return new InvocationResult(null,failure(MiddlewareErrorCode.UNAVAILABLE),Map.of("status",completion.status()));completed=true;return outcome;
            }catch(IOException|RuntimeException unknown){revoke();return InvocationResult.failure(failure(MiddlewareErrorCode.UNAVAILABLE));}
            finally{stop();}
        }}
        void revoke(){revoked=true;if(context!=null)context.revoke();synchronized(renewal){if(renewalTimer!=null){renewalTimer.cancel(false);renewalTimer=null;}if(renewalTask!=null){renewalTask.cancel(false);maintenance.purge();}}}
        void stop(){synchronized(operation){closed=true;revoke();}}
    }
}
