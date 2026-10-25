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
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
/**
 * Bounded explicit wire codec; no Java object deserialization.
 */
final class ClusterWire {
    static final int MAX=262144;
    private ClusterWire(){}
    @FunctionalInterface interface Writer{void write(DataOutputStream out)throws IOException;}
    static byte[] encode(Writer writer)throws IOException{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){writer.write(out);}if(bytes.size()>MAX)throw new IOException("Management frame capacity");return bytes.toByteArray();}
    static DataInputStream input(byte[] bytes){return new DataInputStream(new ByteArrayInputStream(bytes));}
    static byte[] receive(DataInputStream in)throws IOException{int count=in.readInt();if(count<0||count>MAX)throw new IOException("Management frame bounds");byte[] bytes=in.readNBytes(count);if(bytes.length!=count)throw new EOFException();return bytes;}
    static void send(DataOutputStream out,byte[] body,byte[] key)throws IOException{if(body.length>MAX)throw new IOException("Management frame capacity");out.writeInt(body.length);out.write(body);out.write(mac(body,key));out.flush();}
    static void authenticate(DataInputStream in,byte[] body,byte[] key)throws IOException{byte[] tag=in.readNBytes(32);if(tag.length!=32||!MessageDigest.isEqual(tag,mac(body,key)))throw new IOException("Management authentication rejected");}
    private static byte[] mac(byte[] body,byte[] key)throws IOException{try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(body);}catch(GeneralSecurityException failure){throw new IOException("Management authentication unavailable",failure);}}
    static void end(DataInputStream in)throws IOException{if(in.available()!=0)throw new IOException("Trailing management fields");}
    static void node(DataOutputStream out,NodeView node)throws IOException{out.writeUTF(node.nodeId());out.writeUTF(node.releaseId());out.writeBoolean(node.ready());out.writeBoolean(node.draining());out.writeInt(node.inFlight());out.writeLong(node.version());}
    static NodeView node(DataInputStream in)throws IOException{return new NodeView(in.readUTF(),in.readUTF(),in.readBoolean(),in.readBoolean(),in.readInt(),in.readLong());}
    static void strings(DataOutputStream out,List<String> values)throws IOException{if(values.size()>16)throw new IOException("Management list capacity");out.writeInt(values.size());for(String value:values)out.writeUTF(value);}
    static List<String> strings(DataInputStream in)throws IOException{int count=in.readInt();if(count<0||count>16)throw new IOException("Management list bounds");List<String> values=new ArrayList<>();for(int i=0;i<count;i++)values.add(in.readUTF());return List.copyOf(values);}
    static void bytes(DataOutputStream out,byte[] bytes)throws IOException{if(bytes.length>32768)throw new IOException("Invocation payload bounds");out.writeInt(bytes.length);out.write(bytes);}
    static byte[] bytes(DataInputStream in)throws IOException{int length=in.readInt();if(length<0||length>32768||length>in.available())throw new IOException("Invocation payload bounds");return in.readNBytes(length);}
    static void operation(DataOutputStream out,OperationStatus status)throws IOException{out.writeUTF(status.operationId());out.writeUTF(status.state());out.writeLong(status.version());out.writeUTF(status.planId());out.writeUTF(status.currentNode());out.writeUTF(status.phase());out.writeUTF(status.errorCode());}
    static OperationStatus operation(DataInputStream in)throws IOException{return new OperationStatus(in.readUTF(),in.readUTF(),in.readLong(),in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF());}
    static void plan(DataOutputStream out,DeploymentPlan plan)throws IOException{out.writeUTF(plan.planId());out.writeLong(plan.expectedVersion());out.writeUTF(plan.releaseId());strings(out,plan.nodeIds());out.writeUTF(plan.fingerprint());}
    static DeploymentPlan plan(DataInputStream in)throws IOException{return new DeploymentPlan(in.readUTF(),in.readLong(),in.readUTF(),strings(in),in.readUTF());}
}
