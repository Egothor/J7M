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
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.j7m.middleware.api.InvocationOptions;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.application.repository.*;
import org.j7m.middleware.deployer.ApplicationManager;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.security.api.*;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;

/**
 * Public-API named job host; synthetic native credentials never reach application components.
 */
public final class JobFrontendProcess {
    private JobFrontendProcess() { }
    /**
     * Hosts a signed job and a bounded local qualification control protocol.
     * @param args bundle, public key, work directory, native ports, sink port, common marker, paused flag, mode
     * @throws Exception on setup or qualification failure
     */
    public static void main(String[] args) throws Exception {
        Path work=Path.of(args[2]);var key=java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(Files.readAllBytes(Path.of(args[1]))));
        var trust=new PublicKeyTrustStore(Map.of(new SigningKeyId("operator"),key));
        var addresses=Arrays.stream(args[3].split(",")).map(value->new InetSocketAddress("127.0.0.1",Integer.parseInt(value))).toList();
        var nativeEndpoint=new PartitionClient(addresses,"app",new byte[32]);
        var held=new AtomicBoolean();PartitionEndpoint endpoint=new PartitionEndpoint(){
            public PartitionReply execute(long epoch,String request,byte[] command,byte[] query,Instant deadline)throws IOException{
                var reply=nativeEndpoint.execute(epoch,request,command,query,deadline);
                if(args[7].equals("after-complete")&&ResourceCommand.decode(command).operation().equals("J_COMPLETE")&&held.compareAndSet(false,true)){
                    System.out.println("HOLD COMPLETE");System.out.flush();try{new CountDownLatch(1).await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}
                }
                return reply;
            }
            public PartitionReply query(long epoch,byte[] query,Instant deadline)throws IOException{return nativeEndpoint.query(epoch,query,deadline);}
            public PartitionStateMachine.Status status(Instant deadline)throws IOException{return nativeEndpoint.status(deadline);}
            public ExecutionSession subscribeExecution(long epoch,String prefix,String kind,String owner,long lease,Instant deadline)throws IOException{return nativeEndpoint.subscribeExecution(epoch,prefix,kind,owner,lease,deadline);}
        };
        var schemas=new org.j7m.middleware.codec.api.SchemaRegistry(List.of());
        var profile=new ManagedJobProfile(ManagedExecutionBackend.group("execution","tenant",endpoint),new ManagedPayloadCodec(new org.j7m.middleware.codec.defaultcodec.DefaultValueCodec(schemas),schemas),"jobs.tenant",
                Duration.ofSeconds(5),Duration.ofMillis(300),Duration.ofMillis(200),Duration.ofSeconds(4),Duration.ofMillis(20),!args[7].equals("parallel"));
        var paused=new AtomicBoolean(Boolean.parseBoolean(args[6]));
        try(var runtime=new MiddlewareRuntimeBuilder().runtimeId("jobs-"+work.getFileName()).addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();var factory=new ManagedJobFactory(Map.of("default",profile),2,8)){
            var repository=new LocalApplicationRepository(work.resolve("repository"),Clock.systemUTC(),ApplicationPlatformLimits.defaults(),trust);var coordinate=ApplicationCoordinate.parse("fixture:jobs:1");
            var bundle=new ApplicationBundle(Path.of(args[0]),coordinate,new J7mBundleReader().sha256(Path.of(args[0])));
            if(!repository.listVersions(coordinate.id()).contains(coordinate.version())){if(!repository.publish(bundle,new RepositoryPublishOptions("test",true)).success())throw new IllegalStateException("Signed publication failed");}
            else if(!repository.fetch(coordinate).sha256().equals(bundle.sha256()))throw new IllegalStateException("Changed fixture artifact");
            var worker=new SecurityContext(new Principal("worker","worker",Set.of(),Set.of()),runtime.identity().runtimeId(),new TrustDomain("default"),null,"","","",false,null,Set.of(),Map.of(),Instant.EPOCH);
            var watched=new ManagedHostedResources.Factory(){
                public Set<Class<? extends java.lang.annotation.Annotation>> annotationTypes(){return factory.annotationTypes();}
                public Set<ManagedInvocationPolicy> requiredPolicies(){return Set.of(factory);}
                public void validate(ManagedHostedFeature feature,ManagedComponentDefinition<?> definition,ManagedHostedResources.Bundle program){factory.validate(feature,definition,program);}
                public ManagedHostedResources.Binding bind(ManagedHostedFeature feature,ManagedComponentDefinition<?> definition,Object component,ManagedHostedResources.Bundle program,ManagedHostedResources.Invoker invoker){
                    var guarded=new ManagedHostedResources.Invoker(){
                        public boolean admissionOpen(){return !paused.get()&&invoker.admissionOpen();}
                        public CompletionStage<Object> invokeDecoded(ManagedMethod method,Callable<Object[]> decoder,InvocationOptions options){return invoker.invokeDecoded(method,decoder,options);}
                        public CompletionStage<Object> invokeDecoded(ManagedMethod method,Callable<Object[]> decoder,InvocationOptions options,int bytes){return invoker.invokeDecoded(method,decoder,options,bytes);}
                    };return factory.bind(feature,definition,component,program,guarded);
                }
            };
            var resources=new ResourceBindingFactory(Map.of("job",ResourceBindingFactory.jobContext()),Set.of("default"));
            var options=new ManagedHostingOptions(new ManagedHostBindings(Map.of("resource.name",resources)),List.of(factory),ManagedProbeSettings.defaults(),ManagedInvocationSettings.defaults(),new ManagedHostedResources(Map.of("durable-job",new ManagedHostedResources.Entry(watched,worker,Duration.ofSeconds(5)))));
            var manager=new ApplicationManager(runtime,repository,ApplicationPlatformLimits.defaults(),options,trust);
            var activation=manager.activate(coordinate,new ApplicationActivationRequest("test","test",true,Map.of("j7m.annotations.profile","managed-v1","sink.port",args[4],"ctor.marker",args[5])));
            try(var client=factory.client("default","work",1);var input=new BufferedReader(new InputStreamReader(System.in))){
                System.out.println("READY");System.out.flush();String line;
                while((line=input.readLine())!=null){String[] words=line.split(" ",2);String id=words.length>1?words[1]:"primary";Instant deadline=Instant.now().plusSeconds(2);
                    switch(words[0]){
                        case "START"->{try(var trace=InvocationTraceContext.open(Map.of("traceparent","00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"))){System.out.println("START "+client.submit(id,"input",deadline).status());}}
                        case "RESUME"->{paused.set(false);System.out.println("RESUMED");}
                        case "STATUS"->{var state=client.status(id,deadline);System.out.println("STATUS "+state.status()+" "+state.fields().getOrDefault("fence","0"));}
                        case "RESULT"->System.out.println("RESULT "+client.result(id,String.class,deadline));
                        case "READ"->{var value=client.readProtected("result."+id,deadline);System.out.println("READ "+(value==null?"absent":value.value()));}
                        case "STOP"->{var result=manager.deactivate(activation.instanceIdentity().instanceId());System.out.println("STOP "+result.success()+" "+result.state());System.out.flush();if(result.success())return;}
                        default->throw new IllegalArgumentException("Unknown fixture command");
                    }
                    System.out.flush();
                }
            }
        }
    }
}
