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
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.h2.tools.Server;
import org.j7m.middleware.annotations.QueuePublisher;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.defaultcodec.DefaultValueCodec;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.integrations.JdbcDelivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Signed JPMS ordinary producer/consumer under the public manager, real H2 TCP and two three-voter groups.
 */
class JdbcQueueProcessIT {
    @TempDir Path directory;
    public interface Publisher { @QueuePublisher(queue="orders") long publish(String id,String value); }
    private static Instant deadline(){return Instant.now().plusSeconds(10);}
    private static int port()throws IOException{try(var socket=new ServerSocket(0)){return socket.getLocalPort();}}
    private final class Child implements AutoCloseable {
        final Process process;final BufferedReader output;final BufferedWriter input;final Path errors;
        Child(String id,List<String> command)throws Exception{
            errors=directory.resolve(id+".stderr");process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.appendTo(errors.toFile())).start();
            output=new BufferedReader(new InputStreamReader(process.getInputStream()));input=new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            try{String ready=read();assertNotNull(ready,Files.readString(errors));assertTrue(ready.startsWith("READY"),ready);}catch(Exception|AssertionError failure){close();throw failure;}
        }
        String read()throws Exception{return CompletableFuture.supplyAsync(()->{try{return output.readLine();}catch(IOException failure){throw new UncheckedIOException(failure);}}).get(20,TimeUnit.SECONDS);}
        String command(String value)throws Exception{input.write(value);input.newLine();input.flush();return read();}
        public void close(){process.destroyForcibly();try{assertTrue(process.waitFor(5,TimeUnit.SECONDS));}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}
    }
    private final class Cluster implements AutoCloseable {
        final List<Child> nodes=new ArrayList<>();final List<Integer> ports=new ArrayList<>();final PartitionClient endpoint;
        Cluster(String name,String main)throws Exception{
            String peers="a:"+port()+",b:"+port()+",c:"+port();
            try{
                for(String id:List.of("a","b","c")){int service=port();ports.add(service);nodes.add(new Child(name+id,List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx128m","-cp",System.getProperty("j7m.application.distributed.test.classpath"),main,id,directory.resolve(name+id).toString(),peers,Integer.toString(service))));}
                endpoint=new PartitionClient(ports.stream().map(value->new InetSocketAddress("127.0.0.1",value)).toList(),"app",new byte[32]);
                assertEquals(3,nodes.stream().map(value->value.process.pid()).distinct().count());
            }catch(Exception|AssertionError failure){close();throw failure;}
        }
        String ports(){return String.join(",",ports.stream().map(Object::toString).toList());}
        public void close(){nodes.forEach(Child::close);}
    }
    private static void eventually(Callable<Boolean> condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(!condition.call()){assertTrue(System.nanoTime()<end,"Actual JDBC/queue recovery did not finish");java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);}
    }
    private static int count(String url,String sql)throws SQLException{
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement();var result=statement.executeQuery(sql)){assertTrue(result.next());return result.getInt(1);}
    }
    @Test void signedNamedApplicationKeepsOneInboxEffectAndOutboxPublicationAcrossCommitAckAndSendMarkCrashes()throws Exception{
        qualify(false);
    }
    @Test void signedApplicationOwnedRecordUsesScopedSchemasWithEmptyHostRegistryAcrossQueueInboxAndOutbox()throws Exception{
        qualify(true);
    }
    private void qualify(boolean schemaPayload)throws Exception{
        var artifact=GeneratedSignedModule.compile(directory.resolve("generated"),schemaPayload?JdbcQueueApplicationSources.schemaSources():JdbcQueueApplicationSources.sources(),"fixture:jdbc-delivery:1","inboxfixture.Flow",Set.of("java.sql"),Set.of("inboxfixture"));
        var server=Server.createTcpServer("-tcpPort","0","-tcpDaemon","-ifNotExists").start();
        String url="jdbc:h2:tcp://127.0.0.1:"+server.getPort()+"/"+directory.resolve("database").toAbsolutePath();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()){
            JdbcDelivery.initialize(connection,JdbcDelivery.Dialect.H2);statement.executeUpdate("CREATE TABLE app_effect(message_id VARCHAR(128) PRIMARY KEY,parent VARCHAR(128))");
        }
        try(var applicationTypes=new URLClassLoader(new URL[]{artifact.jar().toUri().toURL()},getClass().getClassLoader());var broker=new Cluster("broker","org.j7m.middleware.distributed.QueueNodeProcess");var journal=new Cluster("execution","org.j7m.middleware.distributed.ExecutionNodeProcess")){
            var queue=new ReplicatedQueue("queue-partition","tenant",(p,d)->new ReplicatedQueue.Route(broker.endpoint,1));
            var execution=new ExecutionClient("execution","tenant",(p,d)->new ReplicatedQueue.Route(journal.endpoint,1),InvocationTraceContext::current);execution.query("E_VALUE","readiness",deadline());
            var schema=new SchemaRegistry(List.of());var codec=new ManagedPayloadCodec(new DefaultValueCodec(schema),schema);
            Class<?> producerType=schemaPayload?applicationTypes.loadClass("inboxfixture.Incoming"):Publisher.class;
            Class<?> dataType=schemaPayload?applicationTypes.loadClass("inboxfixture.Payload"):String.class;
            assertNull(schema.forType(dataType), "The trusted host registry starts empty");
            var observerCodec=codec.forDefinition(producerType,List.of(dataType));
            var publications=new ManagedQueuePublisherFactory(Map.of("producer",new ManagedQueuePublisherFactory.Registration(producerType.getName(),"incoming","tenant",queue,new DeduplicatedRpc(execution),codec,Duration.ofHours(1),3,Set.of("orders"))));
            var dependency=new ManagedDependency(producerType,"producer",List.of(),true,Map.of("resource.name","producer","binding.refresh","NEVER","binding.policy","default"));
            ManagedInvocationPolicy.Binding direct=new ManagedInvocationPolicy.Binding(){public Object invoke(InvocationContext context,Object[] values,ManagedInvocationPolicy.Proceed next)throws Exception{return next.invoke();}public void close(){}};
            String parent="00-11111111111111111111111111111111-3333333333333333-01";
            List<String> common=List.of(artifact.bundle().toString(),artifact.publicKey().toString(),directory.resolve("host-work").toString(),broker.ports(),journal.ports(),url);
            Path source=Path.of("src/test/java/org/j7m/middleware/application/distributed/JdbcQueueFrontendProcess.java");
            var normalArgs=new ArrayList<>(common);normalArgs.add("normal");
            var normal=GeneratedSignedModule.hostCommand(directory.resolve("host"),source,"org.j7m.middleware.application.distributed","JdbcQueueFrontendProcess",Set.of("com.h2database"),normalArgs.toArray(String[]::new));
            var before=new ArrayList<>(normal);before.set(before.size()-1,"before-ack");var after=new ArrayList<>(normal);after.set(after.size()-1,"after-send");
            for(String mode:List.of("missing-guard","missing-jdbc","missing-relay")) {
                var denied=new ArrayList<>(normal);denied.set(denied.size()-1,mode);
                try(var rejected=new Child(mode,denied)){assertTrue(rejected.process.waitFor(5,TimeUnit.SECONDS));assertEquals(0,rejected.process.exitValue());}
                assertFalse(Files.exists(directory.resolve("host-work/constructed")));assertEquals(0,count(url,"SELECT COUNT(*) FROM app_effect"));
            }
            try(var bound=publications.bind(dependency);var trace=InvocationTraceContext.open(Map.of("traceparent",parent))){
                var publisher=bound.get();var publish=producerType.getMethod("publish",String.class,dataType);
                try(var frontend=new Child("before-ack",before)){
                    TransactionInvocation.invokeAs(TransactionInvocation.caller(),direct,frame->publish.invoke(publisher,"one",schemaPayload?dataType.getConstructor(String.class).newInstance("one"):"one"));
                    assertEquals("BEFORE_ACK",frontend.read());assertEquals(1,count(url,"SELECT COUNT(*) FROM app_effect"));assertEquals(1,count(url,"SELECT COUNT(*) FROM j7m_inbox"));
                }
                try(var frontend=new Child("recovered-ack",normal)){
                    eventually(()->frontend.command("STATUS").endsWith(" 1"));
                    eventually(()->count(url,"SELECT COUNT(*) FROM j7m_outbox WHERE sent=1 AND message_id<>'definition'")==1);
                    assertEquals(1,count(url,"SELECT COUNT(*) FROM app_effect"));eventually(()->frontend.command("STOP").equals("STOP true"));
                }
                try(var frontend=new Child("after-send",after)){
                    TransactionInvocation.invokeAs(TransactionInvocation.caller(),direct,frame->publish.invoke(publisher,"two",schemaPayload?dataType.getConstructor(String.class).newInstance("two"):"two"));
                    assertEquals("AFTER_SEND",frontend.read());assertEquals(2,count(url,"SELECT COUNT(*) FROM app_effect"));assertEquals(1,count(url,"SELECT COUNT(*) FROM j7m_outbox WHERE sent=0"));
                }
                try(var frontend=new Child("recovered-send",normal)){
                    eventually(()->count(url,"SELECT COUNT(*) FROM j7m_outbox WHERE sent=1 AND message_id<>'definition'")==2);
                    assertEquals(2,count(url,"SELECT COUNT(*) FROM app_effect"));assertEquals(2,count(url,"SELECT COUNT(*) FROM j7m_inbox"));
                    assertEquals(2,count(url,"SELECT COUNT(*) FROM app_effect WHERE parent='"+parent+"'"));
                    eventually(()->frontend.command("STOP").equals("STOP true"));
                }
                queue.join("observe-join","observer","observer",10000,deadline());
                var seen=new HashSet<String>();
                for(int i=0;i<2;i++){
                    String[] message=queue.claim("observe-"+i,"events","observer","observer",1,5000,deadline()).split("\\t");assertEquals("MESSAGE",message[0]);assertTrue(seen.add(message[1]));
                    var envelope=org.j7m.middleware.integrations.TracedQueue.decode(Base64.getDecoder().decode(message[2]));assertEquals(parent,envelope.context().traceparent());
                    var payload=observerCodec.decode(envelope.payload(),dataType);
                    assertTrue(Set.of("one","two").contains(schemaPayload?dataType.getMethod("value").invoke(payload):payload));
                    assertEquals("ACKED",queue.ack("observe-ack-"+i,"events","observer","observer",1,message[1],Long.parseLong(message[4]),deadline()));
                }
                assertEquals("EMPTY",queue.claim("observe-empty","events","observer","observer",1,5000,deadline()));
                assertNull(schema.forType(dataType), "Per-definition schema selection must not mutate shared host registration");
            }
        }finally{server.stop();}
    }
}
