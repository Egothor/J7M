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

package org.j7m.middleware.distributed;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Complete committed native job/saga claim delivered by an authenticated execution session.
 * It is historical data, not proof of current ownership: adapters must reject expired/revoked
 * claims and retain the exact pinned partition epoch on every subsequent protected mutation.
 * @param run stable tenant-scoped run identity
 * @param kind JOB or SAGA
 * @param partitionEpoch exact session ownership epoch
 * @param claim immutable native RUNNING/UNKNOWN/COMPENSATING claim with input and checkpoint
 */
public record ExecutionDelivery(String run, String kind, long partitionEpoch, ExecutionResult claim) {
    static final int MAX_FRAME=262144;
    /**
     * Validates finite claim metadata without granting new authority.
     * @param run stable run identity, at most128 characters
     * @param kind native execution kind
     * @param partitionEpoch positive captured route epoch
     * @param claim committed native claim
     * @throws IllegalArgumentException for malformed metadata or excessive frame size
     * @throws NullPointerException for a missing claim
     */
    public ExecutionDelivery {
        Objects.requireNonNull(claim);
        try {
            String owner=claim.fields().get("owner");
            if(owner==null||owner.isBlank()||owner.length()>128||owner.indexOf('\0')>=0
                    ||Long.parseLong(claim.fields().getOrDefault("fence","0"))<1
                    ||Long.parseLong(claim.fields().getOrDefault("expires","0"))<1
                    ||Long.parseLong(claim.fields().getOrDefault("version","0"))<1
                    ||!claim.fields().keySet().containsAll(java.util.Set.of("input","checkpoint","result")))throw new IllegalArgumentException("Execution claim metadata");
        }catch(NumberFormatException malformed){throw new IllegalArgumentException("Execution claim metadata");}

        if (run == null || run.isBlank() || run.length()>128 || run.indexOf('\0')>=0
                || !java.util.Set.of("JOB","SAGA").contains(kind) || partitionEpoch<1
                || !java.util.Set.of("RUNNING","UNKNOWN","COMPENSATING","COMPLETING").contains(claim.status())
                || claim.encode().length>MAX_FRAME-1024) throw new IllegalArgumentException("Execution delivery bounds");
    }
    /**
     * Reports the actual owned encoded claim/input/checkpoint frame size for hosted admission.
     * @return nonnegative encoded claim bytes including its fixed identity envelope
     */
    public int encodedBytes() {
        try { return ResourceWire.encode(out -> write(out,this)).length; }
        catch(IOException invalid) { throw new IllegalStateException("Execution delivery bounds"); }
    }
    static void write(DataOutputStream out,ExecutionDelivery delivery) throws IOException {
        out.writeUTF(delivery.run());out.writeUTF(delivery.kind());out.writeLong(delivery.partitionEpoch());
        PartitionStateMachine.writeBytes(out,delivery.claim().encode());
    }
    static ExecutionDelivery read(DataInputStream in) throws IOException {
        try { return new ExecutionDelivery(in.readUTF(),in.readUTF(),in.readLong(),ExecutionResult.decode(PartitionStateMachine.bytes(in,MAX_FRAME-1024))); }
        catch(IllegalArgumentException invalid) { throw new IOException("Execution delivery bounds"); }
    }
    /**
     * Returns bounded diagnostic identity without persisted input, result or trace carrier.
     * @return safe native kind and epoch
     */
    @Override public String toString() { return "ExecutionDelivery[kind="+kind+",epoch="+partitionEpoch+"]"; }
}
