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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.*;

/**
 * Bounded authenticated TCP management endpoint. Per-principal permissions are checked before
 * dispatch; all fields and trailing bytes are validated before an action. Only loopback development
 * may omit mutual TLS. Nonces have bounded admission and a 30-second maximum lifetime.
 */
public final class ClusterServer implements AutoCloseable {
    /**
     * Principal authorization policy.
     * @param key independent shared secret, at least 32 bytes
     * @param operations exact allowed operation names, never wildcard
     */
    public record Principal(byte[] key,Set<String> operations){
        /**
         * Copies credentials.
         */
        public Principal{if(key.length<32)throw new IllegalArgumentException("Credential length");key=key.clone();operations=Set.copyOf(operations);}
        /**
         * Returns an independent authentication secret copy.
         * @return credential copy
         */
        @Override public byte[] key(){return key.clone();}
    }
    private final ServerSocket server;private final String cluster;private final Map<String,Principal> principals;
    private final ClusterLifecycle lifecycle;private final ClusterNodeEndpoint node;
    private final ThreadPoolExecutor handlers=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"j7m-cluster-handler");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,Long> nonces=new HashMap<>();private final AtomicLong rejected=new AtomicLong();
    private final int maximumNonces;
    private final AtomicLong nonceCapacityRejected=new AtomicLong();
    private final Thread acceptor;private volatile boolean closed;
    /**
     * Starts an endpoint with explicitly selected capabilities.
     * @param address bound address, port zero allowed
     * @param cluster stable application cluster identity
     * @param principals immutable authentication/permission catalog
     * @param lifecycle controller capability or null for a hosting-only node
     * @param node node capability or null for an admin-only endpoint
     * @param tls mutual TLS context, mandatory outside loopback
     * @throws IOException bind failure
     */
    public ClusterServer(InetSocketAddress address,String cluster,Map<String,Principal> principals,
            ClusterLifecycle lifecycle,ClusterNodeEndpoint node,SSLContext tls)throws IOException{
        this(address,cluster,principals,lifecycle,node,tls,65536);
    }
    /**
     * Starts an endpoint with an explicit bounded anti-replay admission capacity.
     * @param address bound address
     * @param cluster stable cluster identity
     * @param principals exact principal capabilities
     * @param lifecycle optional controller
     * @param node optional node
     * @param tls mutual TLS context outside loopback
     * @param maximumNonces retained authenticated nonce capacity, 1..65536
     * @throws IOException bind failure
     */
    public ClusterServer(InetSocketAddress address,String cluster,Map<String,Principal> principals,
            ClusterLifecycle lifecycle,ClusterNodeEndpoint node,SSLContext tls,int maximumNonces)throws IOException{
        if(maximumNonces<1||maximumNonces>65536)throw new IllegalArgumentException("Replay admission capacity");
        this.maximumNonces=maximumNonces;
        if(address.isUnresolved()||tls==null&&!address.getAddress().isLoopbackAddress()||principals.isEmpty())throw new IllegalArgumentException("Unsafe management listener");
        this.cluster=Objects.requireNonNull(cluster);this.principals=Map.copyOf(principals);this.lifecycle=lifecycle;this.node=node;
        if(!cluster.matches("[A-Za-z0-9_.-]{1,96}"))throw new IllegalArgumentException("Cluster identity");
        List<byte[]> keys=new ArrayList<>();
        for(var entry:principals.entrySet()){
            if(!entry.getKey().matches("[A-Za-z0-9_.-]{1,96}")||keys.stream().anyMatch(key->java.security.MessageDigest.isEqual(key,entry.getValue().key())))
                throw new IllegalArgumentException("Principal identity or reused credential");
            keys.add(entry.getValue().key());
        }
        server=tls==null?new ServerSocket():tls.getServerSocketFactory().createServerSocket();
        if(server instanceof SSLServerSocket ssl)ssl.setNeedClientAuth(true);server.bind(address);
        acceptor=new Thread(this::accept,"j7m-cluster-accept");acceptor.setDaemon(true);acceptor.start();
    }
    /**
     * Bound endpoint address.
     * @return actual listener
     */
    public InetSocketAddress address(){return (InetSocketAddress)server.getLocalSocketAddress();}
    /**
     * Rejected authentication, overload or malformed request count.
     * @return monotonic low-cardinality diagnostic counter
     */
    public long rejectedRequests(){return rejected.get();}
    /**
     * Authenticated requests rejected because retained nonce capacity is exhausted.
     * @return monotonic overload counter
     */
    public long nonceCapacityRejections(){return nonceCapacityRejected.get();}
    private void accept(){while(!closed)try{Socket socket=server.accept();socket.setSoTimeout(5000);try{handlers.execute(()->handle(socket));}catch(RejectedExecutionException full){rejected.incrementAndGet();socket.close();}}catch(IOException failure){if(!closed)rejected.incrementAndGet();}}
    private void handle(Socket socket){try(socket){var input=new DataInputStream(socket.getInputStream());byte[] request=ClusterWire.receive(input);
        try(var in=ClusterWire.input(request)){
            String version=in.readUTF(),group=in.readUTF(),identity=in.readUTF(),nonce=in.readUTF();long expiry=in.readLong();String operation=in.readUTF();
            Principal principal=principals.get(identity);if(principal==null)throw new IOException("Unknown management principal");ClusterWire.authenticate(input,request,principal.key());
            long now=System.currentTimeMillis();if(!version.equals("j7m.cluster/v1")||!group.equals(cluster)||nonce.length()>64||expiry<=now||expiry-now>30000)throw new IOException("Management envelope rejected");
            synchronized(nonces){nonces.entrySet().removeIf(e->e.getValue()<now);if(nonces.size()>=maximumNonces){nonceCapacityRejected.incrementAndGet();throw new IOException("Management replay admission capacity");}
                if(nonces.putIfAbsent(identity+":"+nonce,expiry)!=null)throw new IOException("Management replay");}
            byte[] result;String code="OK";
            try{if(!principal.operations().contains(operation))throw new SecurityException("Permission denied");Action action=decode(operation,in,Instant.ofEpochMilli(expiry));ClusterWire.end(in);result=action.run();}
            catch(SecurityException denied){code="DENIED";result=new byte[0];rejected.incrementAndGet();}
            catch(IOException|RuntimeException failed){code="UNAVAILABLE_OR_UNKNOWN";result=new byte[0];rejected.incrementAndGet();}
            String responseCode=code;byte[] response=result;
            ClusterWire.send(new DataOutputStream(socket.getOutputStream()),ClusterWire.encode(out->{out.writeUTF(cluster);out.writeUTF(nonce);out.writeUTF(responseCode);out.write(response);}),principal.key());
        }
    }catch(IOException|RuntimeException failed){rejected.incrementAndGet();}}
    @FunctionalInterface private interface Action{byte[] run()throws IOException;}
    private Action decode(String operation,DataInputStream in,Instant deadline)throws IOException{
        return switch(operation){
            case "NODE_STATUS" -> ()->{requireNode();var value=node.status(deadline);return ClusterWire.encode(out->{ClusterWire.node(out,value.node());out.writeInt(value.dataSchema());out.writeInt(value.votingMembers());out.writeLong(value.configurationEpoch());out.writeBoolean(value.joint());out.writeBoolean(value.quorumAvailable());});};
            case "NODE_STEP" -> {String id=in.readUTF(),phase=in.readUTF(),release=in.readUTF(),owner=in.readUTF();long fence=in.readLong(),epoch=in.readLong();yield ()->{requireNode();return ClusterWire.encode(out->ClusterWire.node(out,node.step(id,phase,release,owner,fence,epoch,deadline)));};}
            case "INVOKE" -> {String release=in.readUTF(),id=in.readUTF(),contract=in.readUTF();byte[] payload=ClusterWire.bytes(in);yield ()->{requireNode();byte[] result=node.invokeRevision(release,id,contract,payload,deadline);return ClusterWire.encode(out->ClusterWire.bytes(out,result));};}
            case "INSPECT" -> ()->{requireLifecycle();var view=lifecycle.inspect(deadline);return ClusterWire.encode(out->{out.writeLong(view.version());out.writeUTF(view.activeOperation());out.writeInt(view.nodes().size());for(var value:new TreeMap<>(view.nodes()).values())ClusterWire.node(out,value);});};
            case "PLAN" -> {String id=in.readUTF();long expected=in.readLong();String release=in.readUTF();var targets=ClusterWire.strings(in);yield ()->{requireLifecycle();var plan=lifecycle.plan(id,expected,release,targets,deadline);return ClusterWire.encode(out->ClusterWire.plan(out,plan));};}
            case "APPLY","DRAIN","ROLLBACK" -> {String id=in.readUTF();long expected=in.readLong();String argument=in.readUTF();var targets=operation.equals("ROLLBACK")?ClusterWire.strings(in):List.<String>of();yield ()->{requireLifecycle();OperationStatus status=switch(operation){case "APPLY"->lifecycle.apply(id,expected,argument,deadline);case "DRAIN"->lifecycle.drain(id,expected,argument,deadline);default->lifecycle.rollback(id,expected,argument,targets,deadline);};return ClusterWire.encode(out->ClusterWire.operation(out,status));};}
            case "OPERATION" -> {String id=in.readUTF();yield ()->{requireLifecycle();return ClusterWire.encode(out->ClusterWire.operation(out,lifecycle.operationStatus(id,deadline)));};}
            case "AUDIT" -> {String id=in.readUTF();yield ()->{requireLifecycle();var audit=lifecycle.audit(id,deadline);return ClusterWire.encode(out->{out.writeInt(audit.size());for(var event:audit){out.writeLong(event.version());out.writeUTF(event.operationId());out.writeUTF(event.nodeId());out.writeUTF(event.phase());out.writeUTF(event.previousHash());out.writeUTF(event.hash());}});};}
            default -> throw new IOException("Unknown management operation");
        };
    }
    private void requireNode(){if(node==null)throw new SecurityException("Hosting capability unavailable");}
    private void requireLifecycle(){if(lifecycle==null)throw new SecurityException("Controller capability unavailable");}
    /**
     * {@inheritDoc}
     */
    @Override public void close()throws IOException{closed=true;server.close();handlers.shutdownNow();}
}
