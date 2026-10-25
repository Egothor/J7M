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
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import org.j7m.middleware.application.api.*;
import org.j7m.middleware.application.repository.*;
import org.j7m.middleware.consensus.*;
import org.j7m.middleware.deployer.ApplicationManager;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.registry.embedded.*;
import org.j7m.middleware.runtime.MiddlewareRuntimeBuilder;
import org.j7m.middleware.transport.grpc.GrpcTransportProvider;
/**
 * Real middleware hosting/controller child; all credentials are loopback-only synthetic test data.
 */
public final class ClusterNodeProcess {
    private ClusterNodeProcess(){}
    static Instant deadline(){return Instant.now().plusSeconds(15);}
    static Map<String,ReleaseDescriptor> catalog(Path root)throws Exception{
        Map<String,ReleaseDescriptor> result=new HashMap<>();
        for(String revision:List.of("v1","v2","incompatible")){
            ArtifactManifest manifest=ArtifactManifest.decode(Files.readAllBytes(root.resolve(revision+".manifest")));
            result.put(revision,new ReleaseDescriptor(revision,manifest,revision.equals("incompatible")?4:revision.equals("v2")?2:1,1,1,1));
        }return Map.copyOf(result);
    }
    static RemoteResourceClient resources(String ports){return new RemoteResourceClient(Arrays.stream(ports.split(",")).map(p->new InetSocketAddress("127.0.0.1",Integer.parseInt(p))).toList(),"admin",new byte[32],null);}
    public static void main(String[] args)throws Exception{
        if(args[0].equals("CONTROLLER")){controller(args);return;}
        Path directory=Path.of(args[2]),shared=Path.of(args[3]);Map<String,ReleaseDescriptor> catalog=catalog(shared);
        PublicKey signer=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Files.readAllBytes(shared.resolve("signer.pub"))));
        Map<String,InetSocketAddress> peers=new TreeMap<>();for(String encoded:args[4].split(",")){String[] pair=encoded.split(":");peers.put(pair[0],new InetSocketAddress("127.0.0.1",Integer.parseInt(pair[1])));}
        var machine=new ReplicatedResources(100000);var config=new NetworkConsensusConfig(args[1],peers,peers.keySet(),directory.resolve("consensus"),new byte[32],Duration.ofMillis(450),Duration.ofMillis(30),1048576,16777216);
        var clock=new MeasuredClockHealth(System::currentTimeMillis,System::nanoTime,System::currentTimeMillis,()->1);clock.sample();clock.sample();
        try(var node=new NetworkConsensusNode(config,machine)){
            node.start();var local=new ReplicatedResourceClient(node,machine,"cluster/test",op->true,clock);
            try(var resourceServer=new RemoteResourceServer(new InetSocketAddress("127.0.0.1",Integer.parseInt(args[5])),Map.of("admin",new RemoteResourceServer.Binding(local,new byte[32],true)),null,65536);
                var maintenance=new LeaseTimeMaintenance(local,Duration.ofMillis(200));
                var runtime=new MiddlewareRuntimeBuilder().runtimeId(args[1]).registry(new EmbeddedRegistryClient(new EmbeddedRegistryStore(),Clock.systemUTC())).addTransport(new GrpcTransportProvider()).disableNuggetAdmin().build();
                var artifactClient=new ArtifactRepositoryClient(List.of(new InetSocketAddress("127.0.0.1",Integer.parseInt(args[8]))),"reader",new byte[32],Map.of("signer",signer),directory.resolve("downloads"),null)){
                boolean managed=args.length>10&&Boolean.parseBoolean(args[10]);
                System.setProperty("cluster.probe.directory",directory.toString());
                var trust=new PublicKeyTrustStore(Map.of(new SigningKeyId("operator"),signer));
                var repository=new LocalApplicationRepository(directory.resolve("applications"),Clock.systemUTC(),ApplicationPlatformLimits.defaults(),trust);
                var manager=new ApplicationManager(runtime,repository,ApplicationPlatformLimits.defaults(),org.j7m.middleware.application.components.ManagedHostingOptions.defaults(),trust);
                var host=new ApplicationManagerHost(args[1],runtime,manager,repository,artifactClient,directory.resolve("bundles"),Map.of("echo",new ApplicationManagerHost.Contract("//apps/cluster/Echo","clusterapp.Echo")),new ApplicationActivationRequest("operator","dev",managed,managed?Map.of("j7m.annotations.profile","managed-v1"):Map.of()));
                Files.createDirectories(directory.resolve("bundles"));host.replace(catalog.get(args[7]),deadline());
                try(var agent=new ClusterNodeAgent(host,resources(args[9]),node::status,catalog,directory.resolve("agent.bin"),1);
                    var server=new ClusterServer(new InetSocketAddress("127.0.0.1",Integer.parseInt(args[6])),"fixture",Map.of("controller",new ClusterServer.Principal(new byte[32],Set.of("NODE_STATUS","NODE_STEP","INVOKE")),"caller",new ClusterServer.Principal(callerKey(),Set.of("INVOKE","NODE_STATUS"))),null,agent,null)){
                    System.out.println("READY "+args[1]);System.out.flush();
                    var commands=new BufferedReader(new InputStreamReader(System.in));String command;
                    while((command=commands.readLine())!=null){if(command.equals("STATS")){System.out.println("REMOTE_REJECTED "+resourceServer.rejectedRequests()+" REMOTE_NONCE_CAPACITY_REJECTED "+resourceServer.nonceCapacityRejections()+" CLUSTER_NONCE_REJECTED "+server.nonceCapacityRejections());System.out.flush();}else break;}
                }
            }
        }
    }
    static byte[] callerKey(){byte[] key=new byte[32];Arrays.fill(key,(byte)1);return key;}
    private static void controller(String[] args)throws Exception{
        Map<String,ClusterNodeEndpoint> nodes=new TreeMap<>();int index=0;for(String port:args[3].split(",")){index++;nodes.put("n"+index,new ClusterNodeClient(new InetSocketAddress("127.0.0.1",Integer.parseInt(port)),"fixture","controller",new byte[32],null));}
        try(var controller=new RollingClusterController(resources(args[2]),nodes,catalog(Path.of(args[1])));
            var server=new ClusterServer(new InetSocketAddress("127.0.0.1",Integer.parseInt(args[4])),"fixture",Map.of("operator",new ClusterServer.Principal(new byte[32],Set.of("INSPECT","PLAN","APPLY","DRAIN","ROLLBACK","OPERATION","AUDIT")),"viewer",new ClusterServer.Principal(callerKey(),Set.of("INSPECT","OPERATION","AUDIT"))),controller,null,null)){
            System.out.println("READY CONTROLLER");System.out.flush();new BufferedReader(new InputStreamReader(System.in)).readLine();
        }
    }
}
