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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import javax.net.ssl.SSLContext;
/**
 * Authenticated administrative client. Operation identity and expected version remain unchanged on
 * retries; IOException denotes unavailable or UNKNOWN completion and requires operationStatus.
 */
public final class ClusterAdminClient implements ClusterLifecycle {
    private final ClusterConnection connection;
    /**
     * Creates an authorized admin client.
     * @param address explicit endpoint
     * @param cluster stable cluster identity
     * @param principal configured identity
     * @param key independent authentication key
     * @param tls mutual TLS context, mandatory outside loopback
     */
    public ClusterAdminClient(InetSocketAddress address,String cluster,String principal,byte[] key,SSLContext tls){connection=new ClusterConnection(address,cluster,principal,key,tls);}
    /**
     * {@inheritDoc}
     */
    @Override public ClusterView inspect(Instant deadline)throws IOException{try(var in=ClusterWire.input(connection.exchange("INSPECT",new byte[0],deadline))){long version=in.readLong();String active=in.readUTF();int count=in.readInt();if(count<0||count>16)throw new IOException("Fleet bounds");Map<String,NodeView> nodes=new TreeMap<>();for(int i=0;i<count;i++){NodeView node=ClusterWire.node(in);if(nodes.put(node.nodeId(),node)!=null)throw new IOException("Duplicate node");}ClusterWire.end(in);return new ClusterView(version,nodes,active);}}
    /**
     * {@inheritDoc}
     */
    @Override public DeploymentPlan plan(String id,long expected,String release,List<String> targets,Instant deadline)throws IOException{byte[] request=ClusterWire.encode(out->{out.writeUTF(id);out.writeLong(expected);out.writeUTF(release);ClusterWire.strings(out,targets);});try(var in=ClusterWire.input(connection.exchange("PLAN",request,deadline))){var result=ClusterWire.plan(in);ClusterWire.end(in);return result;}}
    /**
     * {@inheritDoc}
     */
    @Override public OperationStatus apply(String id,long expected,String plan,Instant deadline)throws IOException{return mutation("APPLY",id,expected,plan,List.of(),deadline);}
    /**
     * {@inheritDoc}
     */
    @Override public OperationStatus drain(String id,long expected,String node,Instant deadline)throws IOException{return mutation("DRAIN",id,expected,node,List.of(),deadline);}
    /**
     * {@inheritDoc}
     */
    @Override public OperationStatus rollback(String id,long expected,String release,List<String> targets,Instant deadline)throws IOException{return mutation("ROLLBACK",id,expected,release,targets,deadline);}
    private OperationStatus mutation(String operation,String id,long expected,String argument,List<String> targets,Instant deadline)throws IOException{byte[] request=ClusterWire.encode(out->{out.writeUTF(id);out.writeLong(expected);out.writeUTF(argument);if(operation.equals("ROLLBACK"))ClusterWire.strings(out,targets);});try(var in=ClusterWire.input(connection.exchange(operation,request,deadline))){var result=ClusterWire.operation(in);ClusterWire.end(in);return result;}}
    /**
     * {@inheritDoc}
     */
    @Override public OperationStatus operationStatus(String id,Instant deadline)throws IOException{try(var in=ClusterWire.input(connection.exchange("OPERATION",ClusterWire.encode(out->out.writeUTF(id)),deadline))){var result=ClusterWire.operation(in);ClusterWire.end(in);return result;}}
    /**
     * {@inheritDoc}
     */
    @Override public List<AuditEvent> audit(String id,Instant deadline)throws IOException{try(var in=ClusterWire.input(connection.exchange("AUDIT",ClusterWire.encode(out->out.writeUTF(id)),deadline))){int count=in.readInt();if(count<0||count>100)throw new IOException("Audit bounds");List<AuditEvent> events=new ArrayList<>();for(int i=0;i<count;i++)events.add(new AuditEvent(in.readLong(),in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF()));ClusterWire.end(in);return List.copyOf(events);}}
}
