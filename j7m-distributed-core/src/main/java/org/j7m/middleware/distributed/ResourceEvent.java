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

import java.io.*;
import java.util.*;

/**
 * Versioned map-source event payload. Consumers reconstruct a materialized view using the committed
 * index as value version. Version two carries an effective map command: a successful transaction
 * COMMIT contains its complete BATCH write delta, independent of any pre-snapshot PREPARE history.
 * Rejected attempts and operations without a map effect produce no projection changes.
 * @param index positive committed log position
 * @param status persisted source result status
 * @param command effective materialized map command, or original command for operations without map changes
 */
public record ResourceEvent(long index, String status, ResourceCommand command) {
    /**
     * Validates immutable source metadata.
     */
    public ResourceEvent { if (index <= 0 || status == null || status.isBlank() || status.length() > 128) throw new IllegalArgumentException("Source event metadata"); Objects.requireNonNull(command); }
    /**
     * Encodes the source result and command without Java object serialization.
     * @return versioned bounded bytes
     */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(2); out.writeLong(index); out.writeUTF(status); byte[] source = command.encode(); out.writeInt(source.length); out.write(source); out.flush(); return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("Source event encoding", e); }
    }
    /**
     * Returns effective upserts for a map projection. Committed transaction deltas are self-contained;
     * consumers need no transaction state from before their snapshot. Version-one legacy COMMIT
     * records had no standalone delta and require a fresh snapshot rather than inferred writes.
     * @return immutable key to value/version upserts; empty for rejected or non-map operations
     */
    public Map<String, ReplicatedResources.Value> changes() {
        if (!status.equals("OK") && !status.equals("COMMITTED")) return Map.of();
        Map<String, ReplicatedResources.Value> changes = new TreeMap<>(); List<String> args = command.arguments();
        if (command.operation().equals("BATCH")) {
            for (int i = 0; i < args.size(); i += 3) changes.put(args.get(i), new ReplicatedResources.Value(args.get(i + 2), index));
        } else if (command.operation().equals("FENCED")) changes.put(args.get(3), new ReplicatedResources.Value(args.get(4), index));
        return Map.copyOf(changes);
    }
    /**
     * Decodes and validates a full source event before returning any command.
     * @param bytes bounded event payload
     * @return immutable source event
     * @throws IllegalArgumentException for unsupported, corrupt, trailing or oversized input, or a legacy
     * committed transaction event requiring a fresh snapshot because its write delta was not recorded
     */
    public static ResourceEvent decode(byte[] bytes) {
        if (bytes.length > ResourceCommand.MAX_BYTES + 1024) throw new IllegalArgumentException("Source event limit");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes)); int version = in.readInt(); if (version != 1 && version != 2) throw new IllegalArgumentException("Source event version");
            long index = in.readLong(); String status = in.readUTF(); int size = in.readInt();
            if (size < 0 || size != in.available()) throw new IllegalArgumentException("Source event size");
            ResourceCommand command = ResourceCommand.decode(in.readNBytes(size));
            if (version == 1 && status.equals("COMMITTED") && command.operation().equals("COMMIT"))
                throw new IllegalArgumentException("SNAPSHOT_REQUIRED: legacy COMMIT event has no standalone delta");
            return new ResourceEvent(index, status, command);
        } catch (IOException e) { throw new IllegalArgumentException("Source event corruption", e); }
    }
}
