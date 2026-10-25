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
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.repository.*;
import org.j7m.middleware.distributed.*;
/**
 * Shared actual signed-application, three-consensus-JVM lifecycle fixture.
 */
final class ClusterProcessFixture implements AutoCloseable {
    private final Path directory;
    private final boolean managed;
    private KeyPair signer;
    private final List<Process> children=new ArrayList<>();
    private final Map<Process,Path> diagnostics=new HashMap<>();
    private final Set<Integer> allocatedPorts=new HashSet<>();
    private final List<Integer> nodePorts=new ArrayList<>(),resourcePorts=new ArrayList<>();
    private ResumableArtifactStore store;
    private ArtifactRepositoryServer artifacts;
    private final Map<String,ManagedInvocation> invocations=new TreeMap<>();
    private Process controller;
    private int adminPort;
    private RemoteResourceClient resources;
    private ClusterAdminClient lifecycle;
    ClusterProcessFixture(Path directory,int canaryCount)throws Exception { this(directory, canaryCount, false); }
    ClusterProcessFixture(Path directory,int canaryCount,boolean managed)throws Exception{
        this.directory=directory; this.managed=managed; Files.createDirectories(directory);
        try {
        signer=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();Files.write(directory.resolve("signer.pub"),signer.getPublic().getEncoded());
        store=new ResumableArtifactStore(directory.resolve("artifacts"),Map.of("signer",signer.getPublic()),16777216,16,null);
        for(String revision:List.of("v1","v2","incompatible")){
            Path bundle=bundle(revision);ArtifactManifest manifest=ArtifactManifest.sign("cluster","cluster:echo:"+revision,bundle,4096,"signer",signer.getPrivate());
            Files.write(directory.resolve(revision+".manifest"),manifest.encode());store.begin("cluster",manifest);
            try(var input=Files.newInputStream(bundle)){for(int index=0;index<manifest.chunkHashes().size();index++)store.upload("cluster",manifest.id(),index,input.readNBytes(4096));}store.commitManifest("cluster",manifest.id());
        }
        artifacts=new ArtifactRepositoryServer(new InetSocketAddress("127.0.0.1",0),store,Map.of("reader",new ArtifactRepositoryServer.Binding("cluster",new byte[32],Set.of("READ"))),new RepositoryPublishOptions("operator",false),null);
        List<Integer> consensusPorts=new ArrayList<>();for(int i=0;i<3;i++){consensusPorts.add(port());nodePorts.add(port());resourcePorts.add(port());}
        String peers="n1:"+consensusPorts.get(0)+",n2:"+consensusPorts.get(1)+",n3:"+consensusPorts.get(2);
        for(int i=0;i<3;i++){
            Process child=start("HOST","n"+(i+1),directory.resolve("n"+(i+1)).toString(),directory.toString(),peers,Integer.toString(resourcePorts.get(i)),Integer.toString(nodePorts.get(i)),i>=3-canaryCount?"v2":"v1",Integer.toString(artifacts.address().getPort()),ports(resourcePorts),Boolean.toString(managed));
            children.add(child);
        }
        for(Process child:children)ready(child);
        resources=ClusterNodeProcess.resources(ports(resourcePorts));
        long end=System.nanoTime()+Duration.ofSeconds(30).toNanos();while(true)try{resources.read("cluster/current",deadline());break;}catch(IOException unavailable){if(System.nanoTime()>end)throw unavailable;Thread.sleep(50);}
        for(int i=0;i<3;i++)invocations.put("n"+(i+1),new ClusterNodeClient(new InetSocketAddress("127.0.0.1",nodePorts.get(i)),"fixture","caller",ClusterNodeProcess.callerKey(),null));
        adminPort=port();restartController();
        }catch(Exception startup){try{close();}catch(Exception cleanup){startup.addSuppressed(cleanup);}throw startup;}
    }
    ClusterLifecycle lifecycle(){return lifecycle;}
    ResourceEndpoint coordination(){return resources;}
    Map<String,ManagedInvocation> invocations(){return Map.copyOf(invocations);}
    List<String> nodeIds(){return List.of("n1","n2","n3");}
    ClusterAdminClient viewer(){return new ClusterAdminClient(new InetSocketAddress("127.0.0.1",adminPort),"fixture","viewer",ClusterNodeProcess.callerKey(),null);}
    void killController()throws Exception{controller.destroyForcibly();controller.waitFor();controller=null;}
    void restartController()throws Exception{controller=start("CONTROLLER",directory.toString(),ports(resourcePorts),ports(nodePorts),Integer.toString(adminPort));ready(controller);lifecycle=new ClusterAdminClient(new InetSocketAddress("127.0.0.1",adminPort),"fixture","operator",new byte[32],null);}
    static Instant deadline(){return Instant.now().plusSeconds(10);}
    private Process start(String... arguments)throws IOException{
        List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("j7m.cluster.test.classpath"),ClusterNodeProcess.class.getName()));command.addAll(List.of(arguments));
        Path errors=directory.resolve("child-"+UUID.randomUUID()+".err");Process child=new ProcessBuilder(command).redirectError(errors.toFile()).start();diagnostics.put(child,errors);return child;
    }
    private void ready(Process child)throws Exception{
        var input=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8));long end=System.nanoTime()+Duration.ofSeconds(40).toNanos();
        while(System.nanoTime()<end){if(input.ready()){String line=input.readLine();if(line!=null&&line.startsWith("READY"))return;}if(!child.isAlive())throw new IOException("Child exited "+child.exitValue()+": "+Files.readString(diagnostics.get(child)));Thread.sleep(20);}throw new IOException("Child startup deadline");
    }
    private static String ports(List<Integer> values){return String.join(",",values.stream().map(String::valueOf).toList());}
    private int port()throws IOException{while(true)try(var socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())){int chosen=socket.getLocalPort();if(allocatedPorts.add(chosen))return chosen;}}
    private Path bundle(String revision)throws Exception{
        Path root=directory.resolve("app-"+revision),source=root.resolve("src/clusterapp"),classes=root.resolve("classes");Files.createDirectories(source);Files.createDirectories(classes);
        Files.writeString(source.resolve("Echo.java"),"package clusterapp; public interface Echo { byte[] invoke(String id,byte[] payload); }");
        Files.writeString(source.resolve("EchoImpl.java"),"package clusterapp; public final class EchoImpl implements Echo { public EchoImpl(){} public byte[] invoke(String id,byte[] payload){String p=new String(payload,java.nio.charset.StandardCharsets.UTF_8);if(p.startsWith(\"WAIT:\")){try{java.nio.file.Path marker=java.nio.file.Path.of(p.substring(5));try{Class<?> type=Class.forName(\"org.j7m.middleware.cluster.ManagedCallContext\");Object context=type.getMethod(\"current\").invoke(null);if(context==null){java.nio.file.Files.writeString(marker,\"NO_CONTEXT\");return new byte[0];}while(!(Boolean)type.getMethod(\"cancellationRequested\").invoke(context))java.util.concurrent.locks.LockSupport.parkNanos(1000000);java.nio.file.Files.writeString(marker,\"CANCELLED\");java.nio.file.Path release=marker.resolveSibling(marker.getFileName()+\".release\");long until=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();while(!java.nio.file.Files.exists(release)&&System.nanoTime()<until)java.util.concurrent.locks.LockSupport.parkNanos(1000000);return new byte[0];}catch(ClassNotFoundException absent){java.nio.file.Files.writeString(marker,\"NO_CONTEXT\");return new byte[0];}}catch(Exception failure){throw new IllegalStateException(failure);}}if(p.startsWith(\"FAIL\"))throw new IllegalStateException(\"Synthetic handler failure\");return (\""+revision+":\"+id+\":\"+p).getBytes(java.nio.charset.StandardCharsets.UTF_8);} }");
        Files.writeString(source.resolve("Main.java"),"package clusterapp; public final class Main implements org.j7m.middleware.application.api.J7mApplication {public Main(){} public void initialize(org.j7m.middleware.application.api.ApplicationContext c){} public void start(org.j7m.middleware.application.api.ApplicationContext c){} public void drain(org.j7m.middleware.application.api.ApplicationContext c){} public void stop(org.j7m.middleware.application.api.ApplicationContext c){} }");
        if (managed) {
            Path api=source.resolve("Echo.java"), implementation=source.resolve("EchoImpl.java");
            Files.writeString(api, Files.readString(api).replace("public interface Echo", "@org.j7m.middleware.annotations.RemoteService public interface Echo")
                    .replace("byte[] invoke", "@org.j7m.middleware.annotations.RemoteMethod byte[] invoke"));
            String checks="""
                    @org.j7m.middleware.annotations.HealthCheck public boolean health(){return !java.nio.file.Files.exists(control("unhealthy"));}
                    @org.j7m.middleware.annotations.ReadinessCheck public boolean ready() throws Exception {
                        if(java.nio.file.Files.exists(control("block"))){java.nio.file.Files.writeString(control("probe-entered"),"entered");
                            while(!java.nio.file.Files.exists(control("probe-release"))){java.util.concurrent.locks.LockSupport.parkNanos(1000000);Thread.interrupted();}}
                        return !java.nio.file.Files.exists(control("notready"));
                    }
                    private java.nio.file.Path control(String name){return java.nio.file.Path.of(System.getProperty("cluster.probe.directory")).resolve(name);}
                    """;
            String body=Files.readString(implementation).replace("public final class EchoImpl", "@org.j7m.middleware.annotations.J7mService(name=\"//apps/cluster/Echo\",contractId=\"cluster.echo\") public final class EchoImpl");
            int end=body.lastIndexOf('}'); Files.writeString(implementation,body.substring(0,end)+checks+body.substring(end));
            Files.writeString(source.resolve("Main.java"),"package clusterapp; @org.j7m.middleware.annotations.J7mApplication(group=\"cluster\",name=\"echo\",version=\""+revision+"\") public final class Main {public Main(){}} ");
        }
        int compilation=ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("j7m.cluster.test.classpath"),"-processor","org.j7m.middleware.processor.J7mProcessor","-d",classes.toString(),source.resolve("Echo.java").toString(),source.resolve("EchoImpl.java").toString(),source.resolve("Main.java").toString());if(compilation!=0)throw new IOException("Application compile failed");
        Path jar=root.resolve("app.jar");try(var output=new JarOutputStream(Files.newOutputStream(jar));var paths=Files.walk(classes)){for(Path file:paths.filter(Files::isRegularFile).toList()){output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\','/')));Files.copy(file,output);output.closeEntry();}}
        String manifest="""
                {"schemaVersion":"j7m.application/v1","application":{"group":"cluster","name":"echo","version":"%s","displayName":"Cluster echo"},"runtime":{"minJ7mVersion":"0.6.6","javaVersion":"25","moduleMode":"CLASSPATH","bootstrapClass":"clusterapp.Main"},"services":[{"serviceName":"//apps/cluster/Echo","interfaceName":"clusterapp.Echo","implementationClass":"clusterapp.EchoImpl","transport":"grpc","globallyExported":false,"metadata":{}}],"security":{"trustDomain":"dev","confidential":false},"resources":{"cpu":"1","memory":"128MiB","maxConcurrentInvocations":32,"maxInboundPayload":"1MiB"}}
                """.formatted(revision);
        Path descriptor=root.resolve("manifest.json");Files.writeString(descriptor,manifest);Path destination=root.resolve("app.j7m");var written=new J7mBundleWriter().write(descriptor,destination,List.of(jar),List.of());
        if(managed)new DefaultBundleSigner(new SigningKeyPair(new SigningKeyId("operator"),"probe-test",signer.getPrivate(),signer.getPublic()),Clock.systemUTC()).sign(written);
        return destination;
    }
    @Override public void close()throws Exception{
        if(controller!=null){controller.destroyForcibly();controller.waitFor();}for(Process child:children){
            if(child.isAlive()){
                child.getOutputStream().write("STATS\n".getBytes(StandardCharsets.UTF_8));child.getOutputStream().flush();
                var diagnostic=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8));
                long until=System.nanoTime()+Duration.ofSeconds(2).toNanos();while(!diagnostic.ready()&&System.nanoTime()<until)Thread.sleep(5);
                if(diagnostic.ready())System.out.println("ACTUAL_NODE_COUNTERS "+diagnostic.readLine());
            }
            child.destroyForcibly();child.waitFor();}if(artifacts!=null)artifacts.close();if(store!=null)store.close();
    }
}
