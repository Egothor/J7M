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
import javax.net.ssl.*;
final class ClusterConnection {
    private final InetSocketAddress address;private final String cluster,principal;private final byte[] key;private final SSLContext tls;
    ClusterConnection(InetSocketAddress address,String cluster,String principal,byte[] key,SSLContext tls){
        if(address.isUnresolved()||key.length<32||tls==null&&!address.getAddress().isLoopbackAddress())throw new IllegalArgumentException("Unsafe management connection");
        this.address=address;this.cluster=Objects.requireNonNull(cluster);this.principal=Objects.requireNonNull(principal);this.key=key.clone();this.tls=tls;
    }
    byte[] exchange(String operation,byte[] payload,Instant deadline)throws IOException{
        long millis=deadline.toEpochMilli()-System.currentTimeMillis();if(millis<=0)throw new IOException("Management deadline");int timeout=(int)Math.min(30000,millis);String nonce=UUID.randomUUID().toString();
        byte[] request=ClusterWire.encode(out->{out.writeUTF("j7m.cluster/v1");out.writeUTF(cluster);out.writeUTF(principal);out.writeUTF(nonce);out.writeLong(deadline.toEpochMilli());out.writeUTF(operation);out.write(payload);});
        try(Socket socket=tls==null?new Socket():tls.getSocketFactory().createSocket()){
            socket.connect(address,timeout);socket.setSoTimeout(timeout);
            if(socket instanceof SSLSocket ssl){var params=ssl.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");ssl.setSSLParameters(params);ssl.startHandshake();}
            var output=new DataOutputStream(socket.getOutputStream());var input=new DataInputStream(socket.getInputStream());ClusterWire.send(output,request,key);
            byte[] response=ClusterWire.receive(input);ClusterWire.authenticate(input,response,key);
            try(var in=ClusterWire.input(response)){if(!in.readUTF().equals(cluster)||!in.readUTF().equals(nonce))throw new IOException("Management response binding");
                String code=in.readUTF();if(!code.equals("OK")){ClusterWire.end(in);if(code.equals("DENIED"))throw new SecurityException("Management permission denied");throw new IOException(code);}
                return in.readAllBytes();}
        }
    }
}
