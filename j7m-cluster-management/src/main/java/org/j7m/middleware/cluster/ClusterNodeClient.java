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
import javax.net.ssl.SSLContext;
/**
 * Authenticated bounded TCP node client. TLS is mandatory outside loopback; request and response
 * authentication bind cluster identity and fresh nonce. Deadlines may leave UNKNOWN effects.
 */
public final class ClusterNodeClient implements ClusterNodeEndpoint {
    private final ClusterConnection connection;
    /**
     * Creates a node client.
     * @param address explicit node endpoint
     * @param cluster stable cluster identity
     * @param principal configured principal
     * @param key principal-specific secret, at least 32 bytes
     * @param tls mutual TLS context, mandatory outside loopback
     */
    public ClusterNodeClient(InetSocketAddress address,String cluster,String principal,byte[] key,SSLContext tls){connection=new ClusterConnection(address,cluster,principal,key,tls);}
    /**
     * {@inheritDoc}
     */
    @Override public Status status(Instant deadline)throws IOException{try(var in=ClusterWire.input(connection.exchange("NODE_STATUS",new byte[0],deadline))){Status status=new Status(ClusterWire.node(in),in.readInt(),in.readInt(),in.readLong(),in.readBoolean(),in.readBoolean());ClusterWire.end(in);return status;}}
    /**
     * {@inheritDoc}
     */
    @Override public NodeView step(String operation,String phase,String release,String owner,long fence,long epoch,Instant deadline)throws IOException{
        byte[] request=ClusterWire.encode(out->{out.writeUTF(operation);out.writeUTF(phase);out.writeUTF(release);out.writeUTF(owner);out.writeLong(fence);out.writeLong(epoch);});
        try(var in=ClusterWire.input(connection.exchange("NODE_STEP",request,deadline))){NodeView result=ClusterWire.node(in);ClusterWire.end(in);return result;}}
    /**
     * {@inheritDoc}
     */
    @Override public byte[] invoke(String requestId,String contract,byte[] payload,Instant deadline)throws IOException{return invokeRevision("",requestId,contract,payload,deadline);}
    /**
     * {@inheritDoc}
     */
    @Override public byte[] invokeRevision(String release,String requestId,String contract,byte[] payload,Instant deadline)throws IOException{
        byte[] request=ClusterWire.encode(out->{out.writeUTF(release);out.writeUTF(requestId);out.writeUTF(contract);ClusterWire.bytes(out,payload);});
        try(var in=ClusterWire.input(connection.exchange("INVOKE",request,deadline))){byte[] response=ClusterWire.bytes(in);ClusterWire.end(in);return response;}}
}
