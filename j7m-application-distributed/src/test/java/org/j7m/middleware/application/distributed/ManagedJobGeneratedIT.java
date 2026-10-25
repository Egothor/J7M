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
import org.j7m.middleware.codec.api.*;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.consensus.*;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.processor.J7mProcessor;
import org.j7m.middleware.security.api.*;
import org.j7m.middleware.transport.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generated POJO, actual activation policy preflight, native authenticated TCP and owned children.
 */
class ManagedJobGeneratedIT {
    @TempDir Path directory;
    private static final String TRACE="00-"+"1".repeat(32)+"-"+"2".repeat(16)+"-01";
    private static Instant deadline(){return Instant.now().plusSeconds(5);}
    private static Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception{
        Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(target,args);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception cause)throw cause;throw failure;}
    }
    private URLClassLoader compile()throws Exception{return compile(false);}
    private URLClassLoader compile(boolean scheduled)throws Exception{
        Path source=directory.resolve("Worker.java"),saved=directory.resolve("Saved.java"),classes=Files.createDirectories(directory.resolve("classes"));
        Files.writeString(saved,"package jobfixture;import org.j7m.middleware.annotations.*;@SchemaType(id=\"job.saved\",version=1) public record Saved(@WireField(id=7) String value){}");
        Files.writeString(source,"""
                package jobfixture;
                import org.j7m.middleware.annotations.*;
                import org.j7m.middleware.application.api.*;
                import org.j7m.middleware.application.distributed.*;
                import java.util.concurrent.*;
                @org.j7m.middleware.annotations.J7mApplication(group="fixture",name="jobs",version="1")
                public class Worker {
                    public static int constructors,exits,restores,shutdowns;
                    public static final CountDownLatch heldEntered=new CountDownLatch(1),heldRelease=new CountDownLatch(1);
                    public static ManagedJobContext captured;
                    private final ManagedProvider<ManagedJobContext> context;
                    private String state;
                    @Inject public Worker(@J7mResource("job") ManagedProvider<ManagedJobContext> context){constructors++;this.context=context;}
                    @Restore public void restore(Saved checkpoint){restores++;state=checkpoint==null?"EMPTY":checkpoint.value();}
                    @Checkpoint public Saved checkpoint(){return new Saved(state);}
                    @Progress public String progress(){return state+"-progress";}
                    @Shutdown public void shutdown(){shutdowns++;}
                    private static void expectInvalid(Runnable action){try{action.run();throw new IllegalStateException("Missing bounded rejection");}catch(IllegalArgumentException expected){}}
                    /*SCHEDULE_MARKER*/ @DurableJob("work") public CompletionStage<String> run(String input){
                        return J7mContext.currentInvocation().control().submit(()->{
                            try {
                                if(!J7mContext.currentInvocation().security().principal().id().equals("worker"))throw new IllegalStateException("Wrong principal");
                                if(input.equals("first")&&!InvocationTraceContext.current().getOrDefault("traceparent","").equals("TRACE_VALUE"))throw new IllegalStateException("Wrong trace");
                                var job=context.get();captured=job;if(job.fence()<1||job.partitionEpoch()!=1||job.cancellationRequested())throw new IllegalStateException("Wrong claim");
                                String previous=state;if(job.read(job.jobId())!=null)throw new IllegalStateException("unexpected protected value");if(input.equals("fail"))throw new IllegalStateException("SYNTHETIC_SECRET_JOB");state=input;job.checkpoint();job.progress();
                                if(input.equals("quota")){
                                    for(int i=0;i<64;i++)job.stageEffect("quota"+i,0,"value");
                                    expectInvalid(()->job.stageEffect("overflow",0,"value"));
                                    expectInvalid(()->job.stageEffect("quota0",0,"duplicate"));
                                    expectInvalid(()->job.stageEffect("invalid/key",0,"value"));
                                    expectInvalid(()->job.stageEffect("oversized",0,"x".repeat(4097)));
                                }else job.stageEffect(job.jobId(),0,previous);if(input.equals("held")){heldEntered.countDown();if(!heldRelease.await(10,TimeUnit.SECONDS))throw new IllegalStateException("held timeout");}return previous+"->"+input;
                            } finally {exits++;}
                        });
                    }
                }
                """.replace("TRACE_VALUE",TRACE).replace("/*SCHEDULE_MARKER*/",scheduled?"@Schedule(\"timed\")":""));
        var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var files=compiler.getStandardFileManager(diagnostics,null,null)){
            var task=compiler.getTask(null,files,diagnostics,List.of("-classpath",System.getProperty("j7m.application.distributed.test.classpath"),"-d",classes.toString()),null,files.getJavaFileObjects(source,saved));task.setProcessors(List.of(new J7mProcessor()));assertTrue(task.call(),diagnostics.getDiagnostics().toString());
        }
        return new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader());
    }
    private static SecurityContext worker(){return new SecurityContext(new Principal("worker","worker",Set.of(),Set.of()),"host",new TrustDomain("test"),null,"","","",false,null,Set.of(),Map.of(),Instant.EPOCH);}
    private static ManagedHostingOptions options(ManagedJobFactory factory,ManagedJobFactory policy,boolean backend){
        var resource=new ResourceBindingFactory(Map.of("job",ResourceBindingFactory.jobContext()),Set.of("default"));
        return new ManagedHostingOptions(new ManagedHostBindings(Map.of("resource.name",resource)),policy==null?List.of():List.of(policy),ManagedProbeSettings.defaults(),ManagedInvocationSettings.defaults(),backend?new ManagedHostedResources(Map.of("durable-job",new ManagedHostedResources.Entry(factory,worker(),Duration.ofSeconds(15)))):ManagedHostedResources.empty());
    }
    private Object activate(ClassLoader loader,ManagedHostingOptions options,InvocationCompletionFactory completion)throws Exception{
        var identity=new ApplicationIdentity(ApplicationCoordinate.parse("fixture:jobs:1"),"Jobs","test");var runtime=new RuntimeIdentity("runtime","runtime",Map.of());
        var instance=new ApplicationInstanceIdentity(new ApplicationInstanceId("job-instance"),identity,runtime,"CLASSPATH");
        var manifest=new ApplicationManifest(org.j7m.middleware.application.repository.ApplicationManifestValidator.SCHEMA_VERSION,identity,"0.1","25","CLASSPATH","jobfixture.Worker",List.of(),null,null);
        var application=(ApplicationContext)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ApplicationContext.class},(proxy,method,args)->switch(method.getName()){
            case "configuration"->ConfigurationView.of(Map.of());case "applicationIdentity"->identity;case "instanceIdentity"->instance;case "runtimeIdentity"->runtime;case "activationPrincipal"->Principal.runtime("runtime");default->throw new AssertionError("Unexpected context");});
        var type=Class.forName("org.j7m.middleware.deployer.ManagedPojoActivation");var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        try{return constructor.newInstance(manifest,application,loader,null,(java.util.function.Consumer<Class<?>>)ignored->{},options,"a".repeat(64),completion);}catch(InvocationTargetException failed){if(failed.getCause() instanceof Exception cause)throw cause;throw failed;}
    }
    @Test void generatedProviderCheckpointProgressAndFreshRestoreUseActualNativeOwnershipAndPhysicalCompletion()throws Exception{
        try(var loader=compile();var actor=Executors.newFixedThreadPool(2);var reservation=new ServerSocket(0)){
            var machine=new ExecutionStateMachine("execution");var state=new PartitionStateMachine("execution","group",1,machine,new byte[32]);
            var config=new NetworkConsensusConfig("one",Map.of("one",new InetSocketAddress("127.0.0.1",reservation.getLocalPort())),Set.of("one"),directory.resolve("native"),new byte[32],Duration.ofMillis(500),Duration.ofMillis(20),1048576,134217728);
            try(var node=new NetworkConsensusNode(config,state)){
                reservation.close();node.start();
                try(var server=new PartitionServer(new InetSocketAddress("127.0.0.1",0),node,state,null,Map.of("app",new PartitionServer.Binding("tenant",new byte[32],false,false)),()->{long now=System.currentTimeMillis();return new ReplicatedResourceClient.ClockMeasurement(now,now,0,true);},null)){
                    var endpoint=new PartitionClient(List.of(server.address()),"app",new byte[32]);var backend=ManagedExecutionBackend.group("execution","tenant",endpoint);
                    backend.client().statistics(deadline());
                    var schemas=new SchemaRegistry(List.of());
                    var codec=new ManagedPayloadCodec(new DefaultValueCodec(schemas),schemas);
                    var profile=new ManagedJobProfile(backend,codec,"jobs.tenant",Duration.ofSeconds(3),Duration.ofMillis(300),Duration.ofMillis(200),Duration.ofSeconds(12),Duration.ofMillis(20),true);
                    var activation=new AtomicReference<Object>();
                    InvocationCompletionFactory completion=boundary->{try{Object host=activation.get();var call=(ManagedContainer.CallScope)call(host,"openInvocationCall",new Class<?>[]{});var identity=new InvocationContext(new InvocationSecurityContext(boundary.security().principal(),Set.of(),boundary.security().trustDomain()),null,boundary.options().invocationId());var lifetime=new ManagedInvocationLifetime(call,identity,boundary.security(),boundary.options().deadline(),actor,16,16,()->{});return (InvocationCompletionScope)call(host,"ownedScope",new Class<?>[]{InvocationCompletionScope.class},lifetime);}catch(Exception failure){throw new IllegalStateException("Owned setup",failure);}};
                    try(var factory=new ManagedJobFactory(Map.of("default",profile),2,8);var other=new ManagedJobFactory(Map.of("default",profile),2,8)){
                        for(var option:List.of(options(factory,factory,false),options(factory,null,true),options(factory,other,true))){assertThrows(MiddlewareException.class,()->activate(loader,option,completion));assertEquals(0,loader.loadClass("jobfixture.Worker").getField("constructors").getInt(null));}
                        Object host=activate(loader,options(factory,factory,true),completion);activation.set(host);
                        try(var client=factory.client("default","work",1)){
                            assertThrows(MiddlewareException.class,()->client.submit("before","first",deadline()));
                            call(host,"start",new Class<?>[]{});call(host,"commitHostedAdmission",new Class<?>[]{});
                            assertThrows(MiddlewareException.class,()->client.submit("before","first",deadline()));
                            call(host,"openHostedAdmission",new Class<?>[]{});
                            try(var trace=InvocationTraceContext.open(Map.of("traceparent",TRACE))){assertEquals("READY",client.submit("first","first",deadline()).status());}
                            await(()->client.status("first",deadline()).status().equals("COMPLETED"),factory);
                            assertEquals("EMPTY->first",client.result("first",String.class,deadline()));assertEquals(1,loader.loadClass("jobfixture.Worker").getField("exits").getInt(null));
                            assertEquals("first-progress",client.progress("first",String.class,deadline()));assertEquals("EMPTY",client.readProtected("first",deadline()).value());
                            Class<?> savedType=loader.loadClass("jobfixture.Saved");assertNull(client.checkpoint("absent",savedType,deadline()));Object savedValue=client.checkpoint("first",savedType,deadline());assertEquals("first",savedType.getMethod("value").invoke(savedValue));assertThrows(IllegalArgumentException.class,()->client.checkpoint("first",String.class,deadline()));
                            assertEquals("READY",client.submit("second","second",deadline()).status());await(()->client.status("second",deadline()).status().equals("COMPLETED"),factory);
                            assertEquals("EMPTY->second",client.result("second",String.class,deadline()));assertEquals(2,loader.loadClass("jobfixture.Worker").getField("restores").getInt(null));
                            var captured=(ManagedJobContext)loader.loadClass("jobfixture.Worker").getField("captured").get(null);assertTrue(captured.cancellationRequested());assertThrows(MiddlewareException.class,captured::fence);
                            assertThrows(IllegalArgumentException.class,()->client.result("second",Integer.class,deadline()));
                            client.submit("failed","fail",deadline());await(()->client.status("failed",deadline()).status().equals("FAILED"),factory);
                            var failed=assertThrows(IllegalStateException.class,()->client.result("failed",String.class,deadline()));assertEquals("Durable job failed",failed.getMessage());assertNull(failed.getCause());assertFalse(new String(Base64.getDecoder().decode(client.status("failed",deadline()).fields().get("result")),java.nio.charset.StandardCharsets.ISO_8859_1).contains("SYNTHETIC_SECRET_JOB"));
                            client.submit("quota","quota",deadline());await(()->client.status("quota",deadline()).status().equals("COMPLETED"),factory);assertEquals("value",client.readProtected("quota63",deadline()).value());assertNull(client.readProtected("overflow",deadline()));
                            client.submit("held","held",deadline());var worker=loader.loadClass("jobfixture.Worker");
                            assertTrue(((CountDownLatch)worker.getField("heldEntered").get(null)).await(5,TimeUnit.SECONDS));
                            var initial=client.status("held",deadline());awaitTime(Long.parseLong(initial.fields().get("expires")));var renewed=client.status("held",deadline());assertEquals("RUNNING",renewed.status());assertEquals(initial.fields().get("fence"),renewed.fields().get("fence"));assertTrue(Long.parseLong(renewed.fields().get("expires"))>Long.parseLong(initial.fields().get("expires")));assertNull(client.readProtected("held",deadline()));
                            assertEquals("CANCELLED",client.cancel("held",deadline()).status());await(()->((ManagedJobContext)worker.getField("captured").get(null)).cancellationRequested(),factory);assertTrue(factory.statuses().stream().anyMatch(ManagedJobFactory.Status::working));
                            call(host,"stopHostedAdmission",new Class<?>[]{});assertFalse((boolean)call(host,"drainHosted",new Class<?>[]{}));assertEquals(0,worker.getField("shutdowns").getInt(null));assertTrue(((ManagedJobContext)worker.getField("captured").get(null)).cancellationRequested());
                            ((CountDownLatch)worker.getField("heldRelease").get(null)).countDown();

                        }finally{((CountDownLatch)loader.loadClass("jobfixture.Worker").getField("heldRelease").get(null)).countDown();call(host,"stopHostedAdmission",new Class<?>[]{});long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!(boolean)call(host,"drainHosted",new Class<?>[]{})){assertTrue(System.nanoTime()<end);Thread.sleep(1);}call(host,"close",new Class<?>[]{});}
                    }
                }
            }
        }
    }
    @Test void actualScheduleCannotSubmitBeforeGlobalCommitAndForbidIsEnforcedByNativeClaimAcrossOwners()throws Exception{
        try(var loader=compile(true);var actor=Executors.newFixedThreadPool(2);var peer=new ServerSocket(0);var metadataPeer=new ServerSocket(0)){
            var resource=new ExecutionStateMachine("execution");var partition=new PartitionStateMachine("execution","group",1,resource,new byte[32]);var metadata=new ReplicatedResources();
            var config=new NetworkConsensusConfig("one",Map.of("one",new InetSocketAddress("127.0.0.1",peer.getLocalPort())),Set.of("one"),directory.resolve("execution"),new byte[32],Duration.ofMillis(500),Duration.ofMillis(20),1048576,134217728);
            var metaConfig=new NetworkConsensusConfig("metadata",Map.of("metadata",new InetSocketAddress("127.0.0.1",metadataPeer.getLocalPort())),Set.of("metadata"),directory.resolve("metadata"),new byte[32],Duration.ofMillis(500),Duration.ofMillis(20),1048576,134217728);
            ReplicatedResourceClient.ClockHealth clock=()->{long now=System.currentTimeMillis();return new ReplicatedResourceClient.ClockMeasurement(now,now,0,true);};
            try(var node=new NetworkConsensusNode(config,partition);var meta=new NetworkConsensusNode(metaConfig,metadata)){
                peer.close();node.start();metadataPeer.close();meta.start();
                long elected=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!meta.status().leaderId().equals("metadata")){assertTrue(System.nanoTime()<elected);Thread.sleep(1);}
                var store=new ReplicatedResourceClient(meta,metadata,"schedule",ignored->true,clock);store.read("ready",deadline());
                try(var server=new PartitionServer(new InetSocketAddress("127.0.0.1",0),node,partition,null,Map.of("app",new PartitionServer.Binding("tenant",new byte[32],false,false)),clock,null)){
                    var backend=ManagedExecutionBackend.group("execution","tenant",new PartitionClient(List.of(server.address()),"app",new byte[32]));backend.client().statistics(deadline());
                    var profile=new ManagedJobProfile(backend,new ManagedPayloadCodec(new DefaultValueCodec(),new SchemaRegistry(List.of())),"jobs.timed",Duration.ofSeconds(3),Duration.ofMillis(300),Duration.ofMillis(200),Duration.ofSeconds(12),Duration.ofMillis(20),true);
                    var trigger=new ManagedScheduleProfile(store,clock,ManagedScheduleProfile.Pattern.FIXED_RATE,Instant.ofEpochMilli(System.currentTimeMillis()+500),Duration.ofDays(1),null,ZoneOffset.UTC,ManagedScheduleProfile.Gap.SKIP,ManagedScheduleProfile.Fold.EARLIER,ManagedScheduleProfile.Misfire.SKIP,Duration.ofSeconds(5),1,ManagedScheduleProfile.Overlap.FORBID,"held");
                    var activation=new AtomicReference<Object>();InvocationCompletionFactory completion=boundary->{try{Object host=activation.get();var call=(ManagedContainer.CallScope)call(host,"openInvocationCall",new Class<?>[]{});var identity=new InvocationContext(new InvocationSecurityContext(boundary.security().principal(),Set.of(),boundary.security().trustDomain()),null,boundary.options().invocationId());return (InvocationCompletionScope)call(host,"ownedScope",new Class<?>[]{InvocationCompletionScope.class},new ManagedInvocationLifetime(call,identity,boundary.security(),boundary.options().deadline(),actor,16,16,()->{}));}catch(Exception failure){throw new IllegalStateException("Owned setup",failure);}};
                    try(var factory=new ManagedJobFactory(Map.of("default",profile),Map.of("timed",trigger),2,8);var unknown=new ManagedJobFactory(Map.of("default",profile),2,8)){
                        assertThrows(MiddlewareException.class,()->activate(loader,options(unknown,unknown,true),completion));assertEquals(0,loader.loadClass("jobfixture.Worker").getField("constructors").getInt(null));
                        try(var client=factory.client("default","work",1)){
                            assertThrows(MiddlewareException.class,()->client.submit("before","first",deadline()));
                            Object host=activate(loader,options(factory,factory,true),completion);activation.set(host);
                            try{
                                call(host,"start",new Class<?>[]{});call(host,"commitHostedAdmission",new Class<?>[]{});awaitTime(trigger.start().toEpochMilli());
                                assertEquals("0",backend.client().statistics(deadline()).fields().get("jobs"));assertEquals(0,loader.loadClass("jobfixture.Worker").getField("exits").getInt(null));
                                call(host,"openHostedAdmission",new Class<?>[]{});var worker=loader.loadClass("jobfixture.Worker");assertTrue(((CountDownLatch)worker.getField("heldEntered").get(null)).await(5,TimeUnit.SECONDS));
                                var index=ServiceLoader.load(ManagedComponentIndex.class,loader).stream().filter(provider->provider.type().getClassLoader()==loader).map(ServiceLoader.Provider::get).findFirst().orElseThrow();var definition=(ManagedJobDefinition<?>)index.components().getFirst();
                                var program=new ManagedJobProgram(definition.job(),definition,new ManagedHostedResources.Bundle("a".repeat(64),Map.of()),profile);
                                var initial=backend.discovery().runs(program.prefix(),deadline()).iterator().next();String occurrence=program.input(initial.getValue().fields().get("input")).logical();
                                assertEquals("READY",client.submit("parallel","second",deadline()).status());
                                assertEquals("OWNED",new DurableJobs(backend.client()).claim("outsider",program.nativeId("parallel"),"another-host",60000,deadline()).status());assertEquals("READY",client.status("parallel",deadline()).status());
                                ((CountDownLatch)worker.getField("heldRelease").get(null)).countDown();
                                await(()->client.status(occurrence,deadline()).status().equals("COMPLETED")&&client.status("parallel",deadline()).status().equals("COMPLETED"),factory);
                                assertEquals("EMPTY->held",client.result(occurrence,String.class,deadline()));assertEquals("EMPTY->second",client.result("parallel",String.class,deadline()));assertEquals("2",backend.client().statistics(deadline()).fields().get("jobs"));
                            }finally{((CountDownLatch)loader.loadClass("jobfixture.Worker").getField("heldRelease").get(null)).countDown();call(host,"stopHostedAdmission",new Class<?>[]{});long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!(boolean)call(host,"drainHosted",new Class<?>[]{})){assertTrue(System.nanoTime()<end);Thread.sleep(1);}call(host,"close",new Class<?>[]{});}
                        }
                    }
                }
            }
        }
    }
    private static void awaitTime(long instant)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.currentTimeMillis()<=instant){assertTrue(System.nanoTime()<end,"Original native expiry/trigger deadline");java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));}}
    private static void await(java.util.concurrent.Callable<Boolean> condition,ManagedJobFactory factory)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!condition.call()){assertTrue(System.nanoTime()<end,factory.statuses().toString());Thread.sleep(1);}}
}
