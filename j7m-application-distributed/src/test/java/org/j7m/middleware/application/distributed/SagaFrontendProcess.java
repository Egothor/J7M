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
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.components.*;
import org.j7m.middleware.application.repository.*;
import org.j7m.middleware.deployer.ApplicationManager;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.security.api.*;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;

/**
 * Independent signed application host using actual durable execution and generated saga support.
 */
public final class SagaFrontendProcess {
    private SagaFrontendProcess() { }
    /**
     * Runs a finite local qualification control protocol, not an application-facing service.
     * @param args bundle, public key, repository directory, native ports CSV, sink port, common constructor marker, paused flag
     * @throws Exception on fixture setup or qualification failure
     */
    public static void main(String[] args) throws Exception {
        Path bundlePath=Path.of(args[0]),work=Path.of(args[2]);
        var key=java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(Files.readAllBytes(Path.of(args[1]))));
        var trust=new PublicKeyTrustStore(Map.of(new SigningKeyId("operator"),key));
        var addresses=Arrays.stream(args[3].split(",")).map(port->new InetSocketAddress("127.0.0.1",Integer.parseInt(port))).toList();
        var endpoint=new PartitionClient(addresses,"app",new byte[32]);byte[] adminKey=new byte[32];Arrays.fill(adminKey,(byte)1);
        var adminEndpoint=new PartitionClient(addresses,"admin",adminKey);
        var backend=ManagedExecutionBackend.group("execution","tenant",endpoint);var admin=ManagedExecutionBackend.group("execution","tenant",adminEndpoint);
        var profile=new ManagedSagaProfile(backend,admin,new ManagedPayloadCodec(new org.j7m.middleware.codec.defaultcodec.DefaultValueCodec(),new org.j7m.middleware.codec.api.SchemaRegistry(List.of())),"commerce",
                Duration.ofSeconds(4),Duration.ofMillis(20),Duration.ofMillis(500),Duration.ofSeconds(1),2,Duration.ofMillis(20));
        var paused=new AtomicBoolean(Boolean.parseBoolean(args[6]));
        try(var runtime=new MiddlewareRuntimeBuilder().runtimeId("saga-"+work.getFileName()).addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();var factory=new ManagedSagaFactory(Map.of("default",profile),1,4)) {
            var repository=new LocalApplicationRepository(work.resolve("repository"),Clock.systemUTC(),ApplicationPlatformLimits.defaults(),trust);
            var coordinate=ApplicationCoordinate.parse("fixture:sagas:1");var bundle=new ApplicationBundle(bundlePath,coordinate,new J7mBundleReader().sha256(bundlePath));
            if(!repository.listVersions(coordinate.id()).contains(coordinate.version())) {if(!repository.publish(bundle,new RepositoryPublishOptions("test",true)).success()) throw new IllegalStateException("Signed saga publication failed");}
            else if(!repository.fetch(coordinate).sha256().equals(bundle.sha256())) throw new IllegalStateException("Changed fixture artifact");
            var worker=new SecurityContext(new Principal("alice","alice",Set.of(),Set.of()),runtime.identity().runtimeId(),new TrustDomain("default"),null,"","","",false,null,Set.of(),Map.of(),Instant.EPOCH);
            var watched=new ManagedHostedResources.Factory() {
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
            var resources=new ResourceBindingFactory(Map.of("saga",ResourceBindingFactory.sagaContext()),Set.of("default"));
            var options=new ManagedHostingOptions(new ManagedHostBindings(Map.of("resource.name",resources)),List.of(factory),ManagedProbeSettings.defaults(),ManagedInvocationSettings.defaults(),
                    new ManagedHostedResources(Map.of("saga",new ManagedHostedResources.Entry(watched,worker,Duration.ofSeconds(1)))));
            var manager=new ApplicationManager(runtime,repository,ApplicationPlatformLimits.defaults(),options,trust);
            var activation=manager.activate(coordinate,new ApplicationActivationRequest("test","test",true,Map.of("j7m.annotations.profile","managed-v1","sink.port",args[4],"ctor.marker",args[5])));
            var client=factory.client("default","orders",1);var administrative=factory.administrativeClient("default","orders",1);
            System.out.println("READY");System.out.flush();
            try(var input=new BufferedReader(new InputStreamReader(System.in))) {String line;
                while((line=input.readLine())!=null) {
                    String[] words=line.split(" ",3);String id=words.length>1?words[1]:"run";Instant deadline=Instant.now().plusSeconds(2);
                    switch(words[0]) {
                        case "START"->{try(var trace=InvocationTraceContext.open(Map.of("traceparent","00-0123456789abcdef0123456789abcdef-0123456789abcdef-01","baggage","discard"))) {
                            System.out.println("START "+client.start("start-"+id,id,"input",deadline).state());
                        }}
                        case "RESUME"->{paused.set(false);System.out.println("RESUMED");}
                        case "STATUS"->{var state=client.status(id,deadline);System.out.println("STATUS "+state.state()+" "+state.version());}
                        case "RESULT"->System.out.println("RESULT "+client.result(id,String.class,deadline));
                        case "COMPENSATE"->{var state=client.status(id,deadline);System.out.println("DECISION "+administrative.requestCompensation("undo-"+id,id,state.version(),deadline).state());}
                        case "STOP"->{var result=manager.deactivate(activation.instanceIdentity().instanceId());System.out.println("STOP "+result.success()+" "+result.state());System.out.flush();if(result.success())return;}
                        default->throw new IllegalArgumentException("Unknown fixture command");
                    }
                    System.out.flush();
                }
            }
        }
    }
}
