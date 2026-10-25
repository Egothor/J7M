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

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import org.j7m.middleware.annotations.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.security.api.*;
import org.j7m.middleware.transport.api.*;
import org.junit.jupiter.api.Test;

/**
 * Actual native state machine and owned finalizer pipeline; generated process gates are separate.
 */
class ManagedSagaFactoryTest {
    @SagaDefinition(name="orders") public static class Flow {
        final AtomicInteger forward=new AtomicInteger();final BlockingQueue<String> compensated=new LinkedBlockingQueue<>();
        final AtomicReference<ManagedSagaContext> captured=new AtomicReference<>();volatile CompletableFuture<String> held;volatile boolean fail;volatile java.util.function.Consumer<ManagedSagaContext> inspect;
        @SagaStep(name="reserve",order=0,idempotent=true) public String reserve(String input) {
            forward.incrementAndGet();var context=ManagedInvocationLifetime.currentContext(ManagedSagaContext.class);captured.set(context);
            assertEquals("reserve",context.step());assertTrue(context.fence()>0);assertEquals(1,context.partitionEpoch());assertTrue(context.idempotencyKey().startsWith("step."));
            assertNotNull(context.sagaId());context.stageEffect("reservation",0,input);if(inspect!=null) inspect.accept(context);return input+"-reserved";
        }
        @SagaStep(name="charge",order=1) public CompletionStage<String> charge(String input) {
            forward.incrementAndGet();if(fail) throw new IllegalStateException("synthetic-private-detail");return held==null?CompletableFuture.completedFuture(input+"-charged"):held;
        }
        @Compensation(step="reserve",idempotent=true) public void undoReserve(String input) {compensated.add("reserve:"+input);}
        @Compensation(step="charge",idempotent=true) public void undoCharge(String input) {compensated.add("charge:"+input);}
    }
    @SagaDefinition(name="orders") public static class MissingInverse extends Flow {
        @Override @Compensation(step="reserve",idempotent=true) public void undoReserve(String input) {super.undoReserve(input);}
        @Override public void undoCharge(String input) {throw new AssertionError("No inverse declared");}
    }
    static final class Adapter implements ManagedHostedAdapter<Flow> {
        final Class<? extends Flow> type;
        Adapter(Class<? extends Flow> type) {this.type=type;}
        @SuppressWarnings({"rawtypes","unchecked"}) public Class<Flow> type() {return (Class)type;}
        public String name() {return "";}public List<ManagedQualifier> qualifiers() {return List.of();}
        public Scope.Kind scope() {return Scope.Kind.ACTIVATION;}public List<ManagedDependency> dependencies() {return List.of();}
        public Flow create(ManagedResolver resolver) throws Exception {return type.getConstructor().newInstance();}
        public List<ManagedHostedFeature> hostedFeatures() {return List.of(feature());}
        ManagedHostedFeature feature() {
            var methods=new ArrayList<ManagedMethod>();
            for(var method:type.getMethods()) if(method.isAnnotationPresent(SagaStep.class)||method.isAnnotationPresent(Compensation.class)) methods.add(new ManagedMethod(method.getDeclaringClass().getName(),method.getName(),Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(),method.getReturnType().getName()));
            return new ManagedHostedFeature("saga","orders",1,Map.of("policy","default"),methods);
        }
        public Object invokeHosted(Flow target,ManagedMethod method,Object[] arguments) {
            String input=(String)arguments[0];return switch(method.name()) {
                case "reserve"->target.reserve(input);case "charge"->target.charge(input);
                case "undoReserve"->{target.undoReserve(input);yield null;}case "undoCharge"->{target.undoCharge(input);yield null;}
                default->throw new IllegalArgumentException("Not declared");
            };
        }
    }
    static ManagedSagaProfile profile(SagaEndpoint endpoint,boolean admin) {
        var client=endpoint.client();var backend=ManagedExecutionBackend.group("execution","tenant",endpoint);
        return new ManagedSagaProfile(backend,admin?backend:null,new ManagedPayloadCodec(new DefaultValueCodec(),new SchemaRegistry(List.of())),"commerce",
                Duration.ofSeconds(10),Duration.ofMillis(10),Duration.ofMillis(200),Duration.ofSeconds(2),2,Duration.ofMillis(10));
    }
    static final ManagedHostedResources.Bundle BUNDLE=new ManagedHostedResources.Bundle("a".repeat(64),Map.of());
    static final class Host implements AutoCloseable {
        final SagaEndpoint endpoint;final ManagedSagaFactory factory;final ManagedSagaClient client;final Flow flow;
        final ManagedHostedResources.Binding binding;final ManagedContainer container=ManagedContainer.activate(List.of());
        final ExecutorService actor=Executors.newFixedThreadPool(2);
        Host(Flow flow,SagaEndpoint endpoint) throws Exception {
            this.flow=flow;this.endpoint=endpoint;var profile=profile(endpoint,true);factory=new ManagedSagaFactory(Map.of("default",profile),1,4,()->endpoint.time,endpoint.connector());client=factory.client("default","orders",1);
            var adapter=new Adapter(flow.getClass());var feature=adapter.feature();
            var scopes=new AtomicReference<InvocationCompletionScope>();var callbacks=new HashMap<ManagedMethod,ManagedHostedInvoker.Callback>();
            for(var method:feature.methods()) {
                var policy=factory.bind(new ManagedServiceContract("hosted.saga.orders",1,method),flow.getClass(),flow.getClass());
                callbacks.put(method,arguments->policy.invoke(J7mContext.currentInvocation(),arguments,new ManagedInvocationPolicy.Proceed() {
                    public Object invoke() {scopes.get().markApplicationEntry();return adapter.invokeHosted(flow,method,arguments);}
                    public boolean supportsResultFinalizers() {return true;}
                    public void addResultFinalizer(InvocationResultFinalizer finalizer) {scopes.get().addResultFinalizer(finalizer);}
                    public <T> void putContext(Class<T> type,T value) {scopes.get().putContext(type,value);}
                    public <T> T context(Class<T> type) {return scopes.get().context(type);}
                    public <T> Optional<T> findContext(Class<T> type) {return scopes.get().findContext(type);}
                }));
            }
            var worker=new SecurityContext(new Principal("alice","alice",Set.of(),Set.of()),"worker",new TrustDomain("test"),null,"","","",false,null,Set.of(),Map.of(),Instant.EPOCH);
            var entry=new ManagedHostedResources.Entry(factory,worker,Duration.ofSeconds(2));
            var invoker=new ManagedHostedInvoker(callbacks,boundary->{var scope=new ManagedInvocationLifetime(container.openCall(),TransactionInvocation.caller(),null,boundary.options().deadline(),actor,16,16,()->{});scopes.set(scope);return scope;},entry,
                    new ObjectRef("hosted","hosted","saga","test","test",List.of(),"hosted","",0,1,Map.of("j7m.hosted.internal","true")));
            binding=factory.bind(feature,adapter,flow,BUNDLE,invoker);binding.prepare();binding.start();binding.commitAdmission();
        }
        public void close() throws Exception {if(flow.held!=null&&!flow.held.isDone()) flow.held.complete("released");binding.stopAdmission();assertTrue(binding.drain(Duration.ofSeconds(3)));binding.close();factory.close();actor.close();container.close();}
    }
    private static void until(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.call()) {if(System.nanoTime()>end) fail("Controlled saga state deadline");LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));}
    }
    @Test void forwardResultsProtectedEffectsAndReverseCompensationUseDurableOrderAndCurrentContext() throws Exception {
        try(var host=new Host(new Flow(),new SagaEndpoint())) {
            assertEquals(Set.of(host.factory),host.factory.requiredPolicies());
            assertEquals("READY",host.client.start("start","order","input",Instant.now().plusSeconds(2)).state());
            until(()->{ assertEquals("",host.factory.status("orders",1).failureCategory());return host.client.status("order",Instant.now().plusSeconds(1)).state().equals("COMPLETED"); });
            assertEquals("input-reserved-charged",host.client.result("order",String.class,Instant.now().plusSeconds(1)));assertEquals(2,host.flow.forward.get());
            assertTrue(host.flow.captured.get().revoked());assertThrows(MiddlewareException.class,host.flow.captured.get()::fence);
            assertThrows(IllegalArgumentException.class,()->host.client.result("order",Integer.class,Instant.now().plusSeconds(1)));
            var status=host.client.status("order",Instant.now().plusSeconds(1));assertEquals(MiddlewareErrorCode.PERMISSION_DENIED,assertThrows(MiddlewareException.class,()->host.client.requestCompensation("not-granted","order",status.version(),Instant.now().plusSeconds(1))).code());host.factory.administrativeClient("default","orders",1).requestCompensation("undo","order",status.version(),Instant.now().plusSeconds(1));
            assertEquals("charge:input-reserved",host.flow.compensated.poll(3,TimeUnit.SECONDS));assertEquals("reserve:input",host.flow.compensated.poll(3,TimeUnit.SECONDS));
            until(()->host.client.status("order",Instant.now().plusSeconds(1)).state().equals("COMPENSATED"));
            assertEquals(2,host.flow.forward.get());
        }
    }
    @Test void actualHeldAsyncWorkPreventsDrainAndLostCommittedReplyDoesNotRepeatBusiness() throws Exception {
        var flow=new Flow();flow.held=new CompletableFuture<>();var endpoint=new SagaEndpoint();
        try(var host=new Host(flow,endpoint)) {
            host.client.start("start","order","input",Instant.now().plusSeconds(1));until(()->flow.forward.get()==2);
            host.binding.stopAdmission();assertFalse(host.binding.drain(Duration.ofMillis(1)));assertThrows(IllegalStateException.class,host.binding::close);
            flow.held.complete("established");assertTrue(host.binding.drain(Duration.ofSeconds(2)));
        }
        endpoint=new SagaEndpoint();endpoint.loseCompletion.set(true);
        try(var host=new Host(new Flow(),endpoint)) {
            host.client.start("start","other","input",Instant.now().plusSeconds(1));until(()->host.client.status("other",Instant.now().plusSeconds(1)).state().equals("COMPLETED"));
            assertEquals(2,host.flow.forward.get());assertEquals("input-reserved-charged",host.client.result("other",String.class,Instant.now().plusSeconds(1)));
        }
    }
    @Test void missingInverseIsVisibleDurableUnknownAndNeverAnImplicitSuccessfulUndo() throws Exception {
        try(var host=new Host(new MissingInverse(),new SagaEndpoint())) {
            host.client.start("start","order","input",Instant.now().plusSeconds(1));until(()->{ assertEquals("",host.factory.status("orders",1).failureCategory());return host.client.status("order",Instant.now().plusSeconds(1)).state().equals("COMPLETED"); });
            var state=host.client.status("order",Instant.now().plusSeconds(1));host.factory.administrativeClient("default","orders",1).requestCompensation("undo","order",state.version(),Instant.now().plusSeconds(1));
            until(()->host.client.status("order",Instant.now().plusSeconds(1)).state().equals("UNKNOWN"));
            assertEquals("NO_COMPENSATION_DECLARED",host.client.status("order",Instant.now().plusSeconds(1)).reason());assertTrue(host.flow.compensated.isEmpty());assertEquals(2,host.flow.forward.get());
        }
    }
    @Test void persistedActiveArtifactMismatchFailsPreparationWithoutClaimingOrInvoking() throws Exception {
        var endpoint=new SagaEndpoint();var profile=profile(endpoint,true);var adapter=new Adapter(Flow.class);
        var original=new ManagedSagaProgram(adapter.feature(),adapter,BUNDLE,profile);
        new DurableSagas(endpoint.client()).start("existing",original.nativeId("run"),original.input("run","input"),2,10,Instant.now().plusSeconds(1));
        var calls=new AtomicInteger();
        var invoker=new ManagedHostedResources.Invoker() {
            public boolean admissionOpen() {return true;}
            public CompletionStage<Object> invokeDecoded(ManagedMethod method,Callable<Object[]> decoder,InvocationOptions options) {calls.incrementAndGet();throw new AssertionError("No callback during incompatible preparation");}
        };
        try(var factory=new ManagedSagaFactory(Map.of("default",profile),1,1)) {
            var binding=factory.bind(adapter.feature(),adapter,new Flow(),new ManagedHostedResources.Bundle("b".repeat(64),Map.of()),invoker);
            try {assertThrows(IllegalArgumentException.class,binding::prepare);assertEquals(0,calls.get());}
            finally {binding.stopAdmission();assertTrue(binding.drain(Duration.ofMillis(10)));binding.close();}
        }
        assertEquals("READY",new DurableSagas(endpoint.client()).status(original.nativeId("run"),Instant.now().plusSeconds(1)).status());
    }
    @Test void nativeExpiryBeforeActualInvocationRejectsBodyEvenWhenTheHistoricalIntentReceiptWasSuccessful() throws Exception {
        var endpoint=new SagaEndpoint();endpoint.expireVerification.set(true);
        try(var host=new Host(new Flow(),endpoint)) {
            host.client.start("start","expired","input",Instant.now().plusSeconds(1));
            until(()->!host.factory.status("orders",1).failureCategory().isEmpty());
            assertEquals(0,host.flow.forward.get(),"A cached intent cannot admit an expired native owner");
            assertNull(host.flow.captured.get());
        }
    }
}
