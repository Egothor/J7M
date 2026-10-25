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
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.j7m.middleware.consensus.NetworkConsensusStatus;
import org.j7m.middleware.distributed.ResourceEndpoint;

/**
 * Durable, serialized physical effect admission. Highest fence, intent and schema watermark are
 * fsynced before runtime mutation. An incomplete intent is reconciled from actual host state; an
 * unprovable replacement reports UNKNOWN instead of blindly replaying a potentially destructive
 * action. A newer controller cannot overlap a still executing old action in the same node agent.
 */
public final class ClusterNodeAgent implements ClusterNodeEndpoint, AutoCloseable {
    private final HostingSupervisor host;
    private final ResourceEndpoint authority;
    private final Supplier<NetworkConsensusStatus> consensus;
    private final Map<String,ReleaseDescriptor> catalog;
    private final Path journal;
    private final ClusterFileOwnership ownership;
    private boolean poisoned;
    private boolean closed;
    private long highestFence;
    private long highestEpoch;
    private int schema;
    private String lastOperation="",lastPhase="",lastRelease="";
    private boolean completed=true;
    /**
     * Opens one agent's durable journal.
     * @param host real hosting supervisor
     * @param authority quorum-authoritative lifecycle scope
     * @param consensus actual local consensus status source
     * @param catalog trusted release catalog
     * @param journal private durable agent journal
     * @param initialSchema known initial persisted schema
     * @throws IOException corrupt or unavailable journal
     */
    public ClusterNodeAgent(HostingSupervisor host,ResourceEndpoint authority,
            Supplier<NetworkConsensusStatus> consensus,Map<String,ReleaseDescriptor> catalog,
            Path journal,int initialSchema) throws IOException {
        if(initialSchema<1)throw new IllegalArgumentException("Initial schema");
        this.host=Objects.requireNonNull(host); this.authority=Objects.requireNonNull(authority);
        this.consensus=Objects.requireNonNull(consensus);this.catalog=Map.copyOf(catalog);
        this.journal=journal.toAbsolutePath();schema=initialSchema;
        Files.createDirectories(this.journal.getParent());
        if(Files.isSymbolicLink(this.journal.getParent())||Files.isSymbolicLink(this.journal))throw new IOException("Unsafe agent journal path");
        ownership=new ClusterFileOwnership(this.journal.resolveSibling(this.journal.getFileName()+".lock"));
        try {
        if(Files.exists(this.journal)&&Files.size(this.journal)>4096)throw new IOException("Agent journal size");
        if(Files.exists(this.journal)) {
            byte[] record=Files.readAllBytes(this.journal);
            if(record.length<36)throw new IOException("Agent journal truncated");
            byte[] payload=java.util.Arrays.copyOf(record,record.length-32);
            if(!java.security.MessageDigest.isEqual(digest(payload),java.util.Arrays.copyOfRange(record,payload.length,record.length)))
                throw new IOException("Agent journal checksum");
            try(var in=new DataInputStream(new ByteArrayInputStream(payload))) {
                if(in.readInt()!=0x4a374d31||!in.readUTF().equals(host.inspect().nodeId())) throw new IOException("Agent journal version");
                highestEpoch=in.readLong();highestFence=in.readLong();schema=in.readInt();
                lastOperation=in.readUTF();lastPhase=in.readUTF();lastRelease=in.readUTF();completed=in.readBoolean();
                if(in.read()!=-1 || highestEpoch<0 || highestFence<0 || schema<1
                        || lastOperation.length()>96 || lastPhase.length()>16 || lastRelease.length()>64)
                    throw new IOException("Agent journal invalid state");
            }
        }
        } catch(IOException|RuntimeException failure) {ownership.close();throw failure;}
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized Status status(Instant deadline) throws IOException {
        if(closed||poisoned)throw new IOException("Agent closed or durable storage failed");
        authority.read("cluster/current",deadline); // current leader quorum barrier, never discovery
        var state=consensus.get();
        return new Status(host.inspect(),schema,state.members().size(),state.configEpoch(),
                !state.jointMembers().isEmpty(),true);
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized NodeView step(String operation,String phase,String release,String owner,
            long fence,long epoch,Instant deadline) throws IOException {
        if(closed||poisoned)throw new IOException("Agent closed or durable storage failed");
        if(operation==null||!operation.matches("[A-Za-z0-9_.-]{1,96}")
                ||owner==null||!owner.matches("[A-Za-z0-9_.-]{1,96}")||fence<1||epoch<1
                ||!Instant.now().isBefore(deadline))throw new IOException("Physical step identity/deadline bounds");
        if(!phase.equals("DRAIN")&&!phase.equals("REPLACE")) throw new IOException("Unknown lifecycle phase");
        ReleaseDescriptor target=catalog.get(release);
        if(target==null) throw new IOException("Unknown release");
        var lease=authority.lease("leadership/cluster-lifecycle",deadline);
        if(lease==null || !lease.owner().equals(owner) || lease.fence()!=fence || lease.leadershipEpoch()!=epoch
                || epoch<highestEpoch || epoch==highestEpoch&&fence<highestFence) throw new IOException("Stale lifecycle authority");
        boolean retry=lastOperation.equals(operation)&&lastPhase.equals(phase)&&lastRelease.equals(release);
        if(retry&&completed) return host.inspect();
        if(!completed) {
            NodeView actual=host.inspect();
            boolean proven=lastPhase.equals("DRAIN")&&actual.draining()&&actual.inFlight()==0
                    || lastPhase.equals("REPLACE")&&actual.ready()&&actual.releaseId().equals(lastRelease);
            if(!proven) throw new IOException("UNKNOWN: unfinished physical lifecycle intent");
            completed=true;persist();
            if(retry) return actual;
        }
        NodeView before=host.inspect();
        ReleaseDescriptor current=catalog.get(before.releaseId());
        if(phase.equals("REPLACE")&&current!=null&&!target.compatibleWith(current,schema))
            throw new IOException("Incompatible release/schema");
        highestFence=fence;highestEpoch=epoch;lastOperation=operation;lastPhase=phase;lastRelease=release;
        completed=false;if(phase.equals("REPLACE"))schema=Math.max(schema,target.dataSchema());persist();
        if(phase.equals("DRAIN"))host.drain(deadline);else host.replace(target,deadline);
        completed=true;persist();return host.inspect();
    }
    private void persist() throws IOException {
        try { persistRecord(); } catch(IOException failure) {poisoned=true;throw failure;}
    }
    private void persistRecord() throws IOException {
        Path temp=Files.createTempFile(journal.getParent(),"agent-",".tmp");
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(0x4a374d31);out.writeUTF(host.inspect().nodeId());out.writeLong(highestEpoch);out.writeLong(highestFence);out.writeInt(schema);
            out.writeUTF(lastOperation);out.writeUTF(lastPhase);out.writeUTF(lastRelease);out.writeBoolean(completed);
        }
        byte[] payload=bytes.toByteArray();
        try(var output=Files.newOutputStream(temp,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){output.write(payload);output.write(digest(payload));}
        try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
        Files.move(temp,journal,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        try(var channel=FileChannel.open(journal.getParent(),StandardOpenOption.READ)){channel.force(true);}
    }
    private static byte[] digest(byte[] payload)throws IOException {
        try{return java.security.MessageDigest.getInstance("SHA-256").digest(payload);}
        catch(java.security.NoSuchAlgorithmException unavailable){throw new IOException("Agent journal digest unavailable",unavailable);}
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void close()throws IOException {if(closed)return;closed=true;ownership.close();}
    /**
     * {@inheritDoc}
     */
    @Override public byte[] invoke(String requestId,String contract,byte[] payload,Instant deadline) throws IOException {
        synchronized(this){if(closed||poisoned)throw new IOException("Agent closed or durable storage failed");}
        return host.invoke(requestId,contract,payload,deadline);
    }
    /**
     * {@inheritDoc}
     */
    @Override public byte[] invokeRevision(String release,String requestId,String contract,byte[] payload,Instant deadline) throws IOException {
        synchronized(this){if(closed||poisoned)throw new IOException("Agent closed or durable storage failed");}
        return host.invokeRevision(release,requestId,contract,payload,deadline);
    }
}
