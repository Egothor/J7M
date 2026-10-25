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
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.processor.J7mProcessor;
import org.j7m.middleware.security.api.*;
import org.j7m.middleware.transport.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual generated saga index, activation preflight and direct owned callbacks with resource injection.
 */
class ManagedSagaGeneratedIT {
    @TempDir Path directory;
    private static Object call(Object target,String name,Class<?>[] types,Object... arguments) throws Exception {
        var method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(target,arguments);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception exception) throw exception;throw failure;}
    }
    private URLClassLoader compile() throws Exception {
        var source=directory.resolve("Flow.java");var classes=Files.createDirectories(directory.resolve("classes"));
        Files.writeString(source,"""
                package sagafixture;
                import org.j7m.middleware.annotations.*;
                import org.j7m.middleware.application.api.*;
                import org.j7m.middleware.application.distributed.*;
                import java.util.concurrent.*;
                @org.j7m.middleware.annotations.J7mApplication(group="fixture",name="sagas",version="1")
                @SagaDefinition(name="orders") public class Flow {
                    public static int constructors;
                    public static final java.util.List<String> inverses=new java.util.concurrent.CopyOnWriteArrayList<>();
                    private final ManagedProvider<ManagedSagaContext> context;
                    @Inject public Flow(@J7mResource("saga") ManagedProvider<ManagedSagaContext> context){constructors++;this.context=context;}
                    @SagaStep(name="first",order=0,idempotent=true) public Payload first(String input){context.get().stageEffect("initial",0,input);return new Payload(input+"-first");}
                    @SagaStep(name="last",order=1) public CompletionStage<String> last(Payload input){
                        return J7mContext.currentInvocation().control().submit(()->{
                            if(!J7mContext.currentInvocation().security().principal().id().equals("alice")) throw new IllegalStateException("Wrong actual worker");
                            if(!context.get().step().equals("last")) throw new IllegalStateException("Wrong current context");return input.value()+"-done";
                        });
                    }
                    @Compensation(step="first",idempotent=true) public void undoFirst(String input){inverses.add("first:"+input);}
                    @Compensation(step="last",idempotent=true) public void undoLast(Payload input){inverses.add("last:"+input.value());}
                    @ReconciliationHandler public ManagedSagaDecision establish(ManagedSagaUncertainty uncertainty){return new ManagedSagaDecision(ManagedSagaDecision.Outcome.UNRESOLVED,null);}
                }
                """);
        var payload=directory.resolve("Payload.java");
        Files.writeString(payload,"package sagafixture; import org.j7m.middleware.annotations.*; @SchemaType(id=\"saga.Payload\",version=1) public record Payload(@WireField(id=7) String value) {}");
        var counts=directory.resolve("Counts.java");
        Files.writeString(counts,"package sagafixture; import org.j7m.middleware.annotations.*; @SagaDefinition(name=\"counts\") public class Counts {public Counts(){} @SagaStep(name=\"increment\",order=0,idempotent=true) public int increment(int value){return value+1;}}");
        var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var files=compiler.getStandardFileManager(diagnostics,null,null)) {
            var task=compiler.getTask(null,files,diagnostics,List.of("-classpath",System.getProperty("j7m.application.distributed.test.classpath"),"-d",classes.toString()),null,files.getJavaFileObjects(source,payload,counts));
            task.setProcessors(List.of(new J7mProcessor()));assertTrue(task.call(),diagnostics.getDiagnostics().toString());
        }
        return new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader());
    }
    private Object activate(ClassLoader loader,ManagedHostingOptions options,InvocationCompletionFactory completion) throws Exception {
        var identity=new ApplicationIdentity(ApplicationCoordinate.parse("fixture:sagas:1"),"Sagas","test");var runtime=new RuntimeIdentity("test","test",Map.of());
        var instance=new ApplicationInstanceIdentity(new ApplicationInstanceId("saga-instance"),identity,runtime,"CLASSPATH");
        var manifest=new ApplicationManifest(org.j7m.middleware.application.repository.ApplicationManifestValidator.SCHEMA_VERSION,identity,"0.1","25","CLASSPATH","sagafixture.Flow",List.of(),null,null);
        var application=(ApplicationContext)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ApplicationContext.class},(proxy,method,args)->switch(method.getName()) {
            case "configuration"->ConfigurationView.of(Map.of());case "applicationIdentity"->identity;case "instanceIdentity"->instance;
            case "activationPrincipal"->Principal.runtime("test");case "runtimeIdentity"->runtime;default->throw new AssertionError("Unexpected context "+method.getName());
        });
        var type=Class.forName("org.j7m.middleware.deployer.ManagedPojoActivation");var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        try{return constructor.newInstance(manifest,application,loader,null,(java.util.function.Consumer<Class<?>>)ignored->{},options,"a".repeat(64),completion);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception exception) throw exception;throw failure;}
    }
    private static SecurityContext worker() {return new SecurityContext(new Principal("alice","alice",Set.of(),Set.of()),"host",new TrustDomain("test"),null,"","","",false,null,Set.of(),Map.of(),Instant.EPOCH);}
    private static ManagedHostingOptions options(ManagedSagaFactory factory,ManagedSagaFactory policy,boolean backend) {
        var resources=new ResourceBindingFactory(Map.of("saga",ResourceBindingFactory.sagaContext()),Set.of("default"));
        return new ManagedHostingOptions(new ManagedHostBindings(Map.of("resource.name",resources)),policy==null?List.of():List.of(policy),ManagedProbeSettings.defaults(),ManagedInvocationSettings.defaults(),
                backend?new ManagedHostedResources(Map.of("saga",new ManagedHostedResources.Entry(factory,worker(),Duration.ofSeconds(2)))):ManagedHostedResources.empty());
    }
    @Test void actualGeneratedSagaRejectsMissingBackendPolicyAndAdministrationBeforeConstructorsThenRunsAndCompensates() throws Exception {
        try(var loader=compile();var actor=Executors.newFixedThreadPool(2)) {
            var endpoint=new SagaEndpoint();var profile=ManagedSagaFactoryTest.profile(endpoint,true);var activation=new AtomicReference<Object>();
            InvocationCompletionFactory completion=boundary->{
                try {
                    var host=activation.get();var scope=(ManagedContainer.CallScope)call(host,"openInvocationCall",new Class<?>[]{});
                    var identity=new InvocationContext(new InvocationSecurityContext(boundary.security().principal(),Set.of(),boundary.security().trustDomain()),null,boundary.options().invocationId());
                    var lifetime=new ManagedInvocationLifetime(scope,identity,boundary.security(),boundary.options().deadline(),actor,16,16,()->{});
                    return (InvocationCompletionScope)call(host,"ownedScope",new Class<?>[]{InvocationCompletionScope.class},lifetime);
                }catch(Exception failure){throw new IllegalStateException("Fixture invocation setup",failure);}
            };
            try(var factory=new ManagedSagaFactory(Map.of("default",profile),1,4,()->endpoint.time,endpoint.connector());var other=new ManagedSagaFactory(Map.of("default",profile),1,4,()->endpoint.time,endpoint.connector());
                    var ordinary=new ManagedSagaFactory(Map.of("default",ManagedSagaFactoryTest.profile(endpoint,false)),1,4)) {
                for(var option:List.of(options(factory,factory,false),options(factory,null,true),options(factory,other,true),options(ordinary,ordinary,true))) {
                    assertThrows(MiddlewareException.class,()->activate(loader,option,completion));assertEquals(0,loader.loadClass("sagafixture.Flow").getField("constructors").getInt(null));
                }
                Object host=activate(loader,options(factory,factory,true),completion);activation.set(host);
                try {
                    assertEquals(1,loader.loadClass("sagafixture.Flow").getField("constructors").getInt(null));
                    var client=factory.client("default","orders",1);assertThrows(MiddlewareException.class,()->client.start("start","run","input",Instant.now().plusSeconds(1)));
                    call(host,"start",new Class<?>[]{});call(host,"commitHostedAdmission",new Class<?>[]{});
                    assertThrows(MiddlewareException.class,()->client.start("start","run","input",Instant.now().plusSeconds(1)));
                    call(host,"openHostedAdmission",new Class<?>[]{});client.start("start","run","input",Instant.now().plusSeconds(1));
                    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
                    while(!client.status("run",Instant.now().plusSeconds(1)).state().equals("COMPLETED")) {assertTrue(System.nanoTime()<end,factory.status("orders",1).toString());java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));}
                    assertEquals("input-first-done",client.result("run",String.class,Instant.now().plusSeconds(1)));
                    var state=client.status("run",Instant.now().plusSeconds(1));factory.administrativeClient("default","orders",1).requestCompensation("undo","run",state.version(),Instant.now().plusSeconds(1));
                    while(!client.status("run",Instant.now().plusSeconds(1)).state().equals("COMPENSATED")) {assertTrue(System.nanoTime()<end,factory.status("orders",1).toString());java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));}
                    assertEquals(List.of("last:input-first","first:input"),loader.loadClass("sagafixture.Flow").getField("inverses").get(null));
                    var scalar=factory.client("default","counts",1);scalar.start("start-scalar","count",7,Instant.now().plusSeconds(1));
                    while(!scalar.status("count",Instant.now().plusSeconds(1)).state().equals("COMPLETED")) {assertTrue(System.nanoTime()<end);java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));}
                    assertEquals(8,scalar.result("count",int.class,Instant.now().plusSeconds(1)));
                    assertEquals(8,scalar.result("count",Integer.class,Instant.now().plusSeconds(1)));
                    assertThrows(IllegalArgumentException.class,()->scalar.result("count",Long.class,Instant.now().plusSeconds(1)));
                }finally{
                    call(host,"stopHostedAdmission",new Class<?>[]{});long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                    while(!(boolean)call(host,"drainHosted",new Class<?>[]{})) {assertTrue(System.nanoTime()<end);java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));}
                    ((AutoCloseable)host).close();
                }
            }
        }
    }
}
