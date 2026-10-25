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
import org.j7m.middleware.distributed.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generated POJO queue callback through actual activation and three-voter authenticated TCP backends.
 */
class ManagedQueueGeneratedIT {
    @TempDir Path directory;
    static Object call(Object target,String name,Class<?>[] types,Object... arguments) throws Exception {
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
                @J7mComponent public class Flow {
                    public static int constructors;
                    public static final java.util.List<String> received=new java.util.concurrent.CopyOnWriteArrayList<>();
                    public static final java.util.List<String> dead=new java.util.concurrent.CopyOnWriteArrayList<>();
                    public static final java.util.concurrent.atomic.AtomicInteger poisonAttempts=new java.util.concurrent.atomic.AtomicInteger();
                    public static volatile boolean acceptPoison;
                    @Inject public Flow(){constructors++;}
                    @Async @DurableQueueConsumer(value="orders-handler",queue="orders") public CompletionStage<Void> on(String input){
                        return J7mContext.currentInvocation().control().submit(()->{
                            if(!J7mContext.currentInvocation().security().principal().id().equals("alice")) throw new IllegalStateException("Wrong actual worker");
                            String trace=InvocationTraceContext.current().getOrDefault("traceparent","");
                            if(input.equals("poison")&&!acceptPoison){poisonAttempts.incrementAndGet();throw org.j7m.middleware.api.MiddlewareException.of(org.j7m.middleware.api.MiddlewareErrorCode.INVALID_ARGUMENT,"Known poison input");}
                            if(System.getProperty("queue.effect.port")!=null) {
                                try(var socket=new java.net.Socket("127.0.0.1",Integer.parseInt(System.getProperty("queue.effect.port")))) {
                                    var output=new java.io.DataOutputStream(socket.getOutputStream());output.writeUTF(input);output.writeUTF(trace);output.flush();
                                    if(!new java.io.DataInputStream(socket.getInputStream()).readBoolean()) throw org.j7m.middleware.api.MiddlewareException.of(org.j7m.middleware.api.MiddlewareErrorCode.INVALID_ARGUMENT,"Known rejected input");
                                }
                            }
                            received.add(input+":"+trace);return null;
                        });
                    }
                    @Async @DeadLetterHandler(value="orders-dead",queue="orders") public CompletionStage<Void> dead(String input){
                        return J7mContext.currentInvocation().control().submit(()->{dead.add(input+":"+InvocationTraceContext.current().getOrDefault("traceparent",""));return null;});
                    }
                }
                """);
        var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var files=compiler.getStandardFileManager(diagnostics,null,null)) {
            var task=compiler.getTask(null,files,diagnostics,List.of("-classpath",System.getProperty("j7m.application.distributed.test.classpath"),"-d",classes.toString()),null,files.getJavaFileObjects(source));
            task.setProcessors(List.of(new J7mProcessor()));assertTrue(task.call(),diagnostics.getDiagnostics().toString());
        }
        return new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader());
    }
    Object activate(ClassLoader loader,ManagedHostingOptions options,InvocationCompletionFactory completion) throws Exception {
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
    static ManagedHostingOptions options(ManagedQueueConsumerFactory factory,ManagedQueueConsumerFactory policy) {
        return new ManagedHostingOptions(ManagedHostBindings.empty(),policy==null?List.of(new ManagedCallingPolicy()):List.of(new ManagedCallingPolicy(),policy),ManagedProbeSettings.defaults(),ManagedInvocationSettings.defaults(),
                new ManagedHostedResources(Map.of("queue-consumer",new ManagedHostedResources.Entry(factory,worker(),Duration.ofSeconds(10)),
                        "dead-letter-handler",new ManagedHostedResources.Entry(factory,worker(),Duration.ofSeconds(10)))));
    }
    private static int port() throws java.io.IOException {try(var socket=new java.net.ServerSocket(0)){return socket.getLocalPort();}}
    private static void eventually(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!condition.call()){assertTrue(System.nanoTime()<end,"Owned queue callback did not settle");java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);}
    }
    private final class Cluster implements AutoCloseable {
        final List<Process> processes=new ArrayList<>();final List<Integer> ports=new ArrayList<>();final PartitionClient endpoint;
        Cluster(String name,String main) throws Exception {
            String peers="a:"+port()+",b:"+port()+",c:"+port();var addresses=new ArrayList<java.net.InetSocketAddress>();
            try {
                for(String id:List.of("a","b","c")) {
                    int service=port();ports.add(service);addresses.add(new java.net.InetSocketAddress("127.0.0.1",service));
                    var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx128m","-cp",System.getProperty("j7m.application.distributed.test.classpath"),
                            main,id,directory.resolve(name+id).toString(),peers,Integer.toString(service)).redirectError(directory.resolve(name+id+".stderr").toFile()).start();
                    processes.add(process);var output=new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
                    assertTrue(CompletableFuture.supplyAsync(()->{try{return output.readLine();}catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}}).get(15,TimeUnit.SECONDS).startsWith("READY"));
                }
                endpoint=new PartitionClient(addresses,"app",new byte[32]);assertEquals(3,processes.stream().map(Process::pid).distinct().count());
            }catch(Exception|AssertionError failure){close();throw failure;}
        }
        public void close(){for(var process:processes){process.destroyForcibly();try{assertTrue(process.waitFor(5,TimeUnit.SECONDS));}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}}
        String ports(){return String.join(",",ports.stream().map(Object::toString).toList());}
    }
    private final class Frontend implements AutoCloseable {
        final Process process; final java.io.BufferedReader output; final java.io.BufferedWriter input;
        Frontend(Cluster broker,Cluster execution,int effectPort,boolean gate) throws Exception {
            process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx128m","-cp",System.getProperty("j7m.application.distributed.test.classpath"),
                    QueueFrontendProcess.class.getName(),directory.resolve("classes").toString(),broker.ports(),execution.ports(),Integer.toString(effectPort),Boolean.toString(gate))
                    .redirectError(ProcessBuilder.Redirect.appendTo(directory.resolve("frontend.stderr").toFile())).start();
            output=new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));input=new java.io.BufferedWriter(new java.io.OutputStreamWriter(process.getOutputStream()));
            try {assertEquals("READY",read(),()->readErrors());}catch(Exception|AssertionError failure){close();throw failure;}
        }
        String readErrors(){try{return Files.readString(directory.resolve("frontend.stderr"));}catch(java.io.IOException error){return error.toString();}}
        String read() throws Exception {return CompletableFuture.supplyAsync(()->{try{return output.readLine();}catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}}).get(15,TimeUnit.SECONDS);}
        String status() throws Exception {input.write("STATUS\n");input.flush();return read();}
        public void close(){process.destroyForcibly();try{assertTrue(process.waitFor(5,TimeUnit.SECONDS));}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}
    }
    private static final class Effects implements AutoCloseable {
        final java.net.ServerSocket server=new java.net.ServerSocket(0,16,java.net.InetAddress.getLoopbackAddress());
        final Map<String,Integer> counts=new ConcurrentHashMap<>();final Map<String,String> traces=new ConcurrentHashMap<>();
        final CountDownLatch uncertainEntered=new CountDownLatch(1), uncertainRelease=new CountDownLatch(1);
        final ExecutorService listener=Executors.newSingleThreadExecutor();
        Effects() throws java.io.IOException {listener.submit(()->{
            while(!server.isClosed())try(var socket=server.accept()){
                var input=new java.io.DataInputStream(socket.getInputStream());String key=input.readUTF(),trace=input.readUTF();counts.merge(key,1,Integer::sum);traces.put(key,trace);
                if(key.equals("unknown")){uncertainEntered.countDown();try{uncertainRelease.await();}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new java.io.IOException(stopped);}}
                var output=new java.io.DataOutputStream(socket.getOutputStream());output.writeBoolean(true);output.flush();
            }catch(java.io.IOException error){if(!server.isClosed())throw new java.io.UncheckedIOException(error);}
        });}
        public void close() throws Exception {uncertainRelease.countDown();server.close();listener.shutdown();assertTrue(listener.awaitTermination(3,TimeUnit.SECONDS));}
    }
    public interface Publisher { @org.j7m.middleware.annotations.QueuePublisher(queue="orders") long publish(String id,String value); }
    @Test void durableHandlerOutcomeSurvivesFrontendKillBeforeAckAndOriginalTraceReachesNextOwnedChild() throws Exception {
        try(var loader=compile();var broker=new Cluster("broker","org.j7m.middleware.distributed.QueueNodeProcess");
                var journal=new Cluster("execution","org.j7m.middleware.distributed.ExecutionNodeProcess");var effects=new Effects()) {
            var queue=new ReplicatedQueue("queue-partition","tenant",(p,d)->new ReplicatedQueue.Route(broker.endpoint,1));
            var execution=new ExecutionClient("execution","tenant",(p,d)->new ReplicatedQueue.Route(journal.endpoint,1),InvocationTraceContext::current);
            execution.query("E_VALUE","readiness",Instant.now().plusSeconds(10));
            var schemas=new org.j7m.middleware.codec.api.SchemaRegistry(List.of());var codec=new ManagedPayloadCodec(new org.j7m.middleware.codec.defaultcodec.DefaultValueCodec(schemas),schemas);
            var factory=new ManagedQueuePublisherFactory(Map.of("producer",new ManagedQueuePublisherFactory.Registration(Publisher.class,"sales","tenant",queue,new DeduplicatedRpc(execution),codec,Duration.ofHours(1),3,Set.of("orders"))));
            var dependency=new ManagedDependency(Publisher.class,"producer",List.of(),true,Map.of("resource.name","producer","binding.refresh","NEVER","binding.policy","default"));
            String parent="00-11111111111111111111111111111111-2222222222222222-01";
            ManagedInvocationPolicy.Binding direct=new ManagedInvocationPolicy.Binding(){
                public Object invoke(InvocationContext context,Object[] arguments,ManagedInvocationPolicy.Proceed next)throws Exception{return next.invoke();}
                public void close(){}
            };
            try(var producerBinding=factory.bind(dependency)) {
                var producer=(Publisher)producerBinding.get();
                try(var first=new Frontend(broker,journal,effects.server.getLocalPort(),true);var trace=InvocationTraceContext.open(Map.of("traceparent",parent))) {
                    assertEquals(1L,TransactionInvocation.invokeAs(TransactionInvocation.caller(),direct,frame->producer.publish("first","first")));
                    assertEquals("BEFORE_ACK",first.read());assertEquals(1,effects.counts.get("first"));assertEquals(parent,effects.traces.get("first"));
                    assertEquals(2L,TransactionInvocation.invokeAs(TransactionInvocation.caller(),direct,frame->producer.publish("second","second")));
                }
                try(var second=new Frontend(broker,journal,effects.server.getLocalPort(),false)) {
                    eventually(()->second.status().endsWith(" 2"));
                    assertEquals(Map.of("first",1,"second",1),effects.counts);assertEquals(parent,effects.traces.get("second"));
                }
                try(var uncertain=new Frontend(broker,journal,effects.server.getLocalPort(),false)) {
                    TransactionInvocation.invokeAs(TransactionInvocation.caller(),direct,frame->producer.publish("unknown","unknown"));
                    assertTrue(effects.uncertainEntered.await(10,TimeUnit.SECONDS));
                }
                effects.uncertainRelease.countDown();
                try(var recovered=new Frontend(broker,journal,effects.server.getLocalPort(),false)) {
                    eventually(()->recovered.status().startsWith("STATUS FAILED UNKNOWN"));
                    assertEquals(1,effects.counts.get("unknown"),"Unconfirmed external effect must not be automatically repeated");
                }
            }
        }
    }
    @Test void generatedCallbackHasPreconstructorGuardIdentityAndActualTrackedTraceOverThreeVoterPush() throws Exception {
        try(var loader=compile();var actor=Executors.newFixedThreadPool(2);
                var broker=new Cluster("broker","org.j7m.middleware.distributed.QueueNodeProcess");
                var journal=new Cluster("execution","org.j7m.middleware.distributed.ExecutionNodeProcess")) {
            var queue=new ReplicatedQueue("queue-partition","tenant",(p,d)->new ReplicatedQueue.Route(broker.endpoint,1));
            var client=new ExecutionClient("execution","tenant",(p,d)->new ReplicatedQueue.Route(journal.endpoint,1));
            client.query("E_VALUE","readiness",Instant.now().plusSeconds(10));
            var schemas=new org.j7m.middleware.codec.api.SchemaRegistry(List.of());
            var codec=new ManagedPayloadCodec(new org.j7m.middleware.codec.defaultcodec.DefaultValueCodec(schemas),schemas);
            var profile=new QueueConsumerProfile(queue,new DeduplicatedRpc(client),codec,"tenant","orders","workers","member",Set.of("orders"),Duration.ofSeconds(5),Duration.ofMillis(500),Duration.ofSeconds(5),Duration.ofHours(1),QueueConsumerProfile.Strategy.EXTERNAL_GUARD);
            var activation=new AtomicReference<Object>();
            InvocationCompletionFactory completion=boundary->{try{
                var host=activation.get();var scope=(ManagedContainer.CallScope)call(host,"openInvocationCall",new Class<?>[]{});
                var context=new InvocationContext(new InvocationSecurityContext(boundary.security().principal(),Set.of(),boundary.security().trustDomain()),null,boundary.options().invocationId());
                var lifetime=new ManagedInvocationLifetime(scope,context,boundary.security(),boundary.options().deadline(),actor,16,16,()->{});
                return (InvocationCompletionScope)call(host,"ownedScope",new Class<?>[]{InvocationCompletionScope.class},lifetime);
            }catch(Exception failure){throw new IllegalStateException("Fixture ownership",failure);}};
            try(var factory=new ManagedQueueConsumerFactory(Map.of("default",profile),1);var other=new ManagedQueueConsumerFactory(Map.of("default",profile),1)) {
                for(var option:List.of(options(factory,null),options(factory,other))){assertThrows(MiddlewareException.class,()->activate(loader,option,completion));assertEquals(0,loader.loadClass("sagafixture.Flow").getField("constructors").getInt(null));}
                var host=activate(loader,options(factory,factory),completion);activation.set(host);
                String parent="00-11111111111111111111111111111111-2222222222222222-01";
                byte[] envelope=org.j7m.middleware.integrations.TracedQueue.encode(new org.j7m.middleware.integrations.TracedQueue.Message(new TraceCarrier(parent,""),codec.encode("payload",String.class)));
                try {
                    call(host,"start",new Class<?>[]{});call(host,"commitHostedAdmission",new Class<?>[]{});
                    queue.send("send","orders","message",envelope,3,Instant.now().plusSeconds(10));
                    assertEquals(List.of(),loader.loadClass("sagafixture.Flow").getField("received").get(null));
                    call(host,"openHostedAdmission",new Class<?>[]{});
                    eventually(()->factory.status("orders","orders-handler").acknowledged()==1);
                    assertEquals(List.of("payload:"+parent),loader.loadClass("sagafixture.Flow").getField("received").get(null));
                    byte[] poison=org.j7m.middleware.integrations.TracedQueue.encode(new org.j7m.middleware.integrations.TracedQueue.Message(new TraceCarrier(parent,""),codec.encode("poison",String.class)));
                    queue.send("poison-send","orders","poison-id",poison,3,Instant.now().plusSeconds(10));
                    eventually(()->((List<?>)loader.loadClass("sagafixture.Flow").getField("dead").get(null)).size()==1);
                    assertEquals(1,((java.util.concurrent.atomic.AtomicInteger)loader.loadClass("sagafixture.Flow").getField("poisonAttempts").get(null)).get());
                    assertEquals(List.of("poison:"+parent),loader.loadClass("sagafixture.Flow").getField("dead").get(null));
                    var retained=queue.deadLetterPage("orders",0,0,256,Instant.now().plusSeconds(5));assertEquals(1,retained.entries().size());
                    var letter=retained.entries().getFirst();assertArrayEquals(poison,letter.payload());assertEquals("",letter.child());
                    loader.loadClass("sagafixture.Flow").getField("acceptPoison").setBoolean(null,true);
                    byte[] key=new byte[32];Arrays.fill(key,(byte)1);var administrative=new PartitionClient(broker.ports.stream().map(port->new java.net.InetSocketAddress("127.0.0.1",port)).toList(),"admin",key);
                    var adminQueue=new ReplicatedQueue("queue-partition","tenant",(p,d)->new ReplicatedQueue.Route(administrative,1));
                    assertTrue(adminQueue.redrive("redrive","orders",letter.messageId(),letter.receipt(),"redriven",3,Instant.now().plusSeconds(5)).startsWith("REDRIVEN"));
                    eventually(()->factory.status("orders","orders-handler").acknowledged()==2);
                    assertEquals(List.of("payload:"+parent,"poison:"+parent),loader.loadClass("sagafixture.Flow").getField("received").get(null));
                    assertEquals(1,((List<?>)loader.loadClass("sagafixture.Flow").getField("dead").get(null)).size());
                }finally {call(host,"stopHostedAdmission",new Class<?>[]{});eventually(()->(boolean)call(host,"drainHosted",new Class<?>[]{}));call(host,"close",new Class<?>[]{});}
            }
        }
    }
}
