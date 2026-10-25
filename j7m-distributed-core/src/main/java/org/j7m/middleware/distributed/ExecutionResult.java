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
 * Immutable durable execution result and bounded metadata, independent of transport lifecycle.
 * @param status stable protocol status
 * @param fields immutable textual fields; protected values and context remain tenant scoped
 */
public record ExecutionResult(String status, Map<String, String> fields) {
    /**
     * Copies metadata and rejects aggregates exceeding the one MiB encoded wire limit.
     * @throws IllegalArgumentException for invalid status, fields or total encoded size
     */
    public ExecutionResult {
        if (status == null || status.isBlank() || status.length() > 128 || fields.size() > 64) throw new IllegalArgumentException("Execution result bounds");
        fields = Map.copyOf(fields);
        long size = 10L + modifiedUtfBytes(status);
        for (var entry : fields.entrySet()) {
            if (entry.getKey().length() > 128 || entry.getValue().length() > 16384) throw new IllegalArgumentException("Execution field bounds");
            size += 4L + modifiedUtfBytes(entry.getKey()) + modifiedUtfBytes(entry.getValue());
        }
        if (size > 1048576) throw new IllegalArgumentException("Execution aggregate wire limit");
    }
    private static int modifiedUtfBytes(String value) {
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) { char character = value.charAt(i); bytes += character >= 1 && character <= 127 ? 1 : character <= 2047 ? 2 : 3; }
        return bytes;
    }
    /**
     * Extracts the persisted W3C carrier for background worker context attachment.
     * @return immutable traceparent/tracestate carrier, empty when absent
     */
    public Map<String, String> context() {
        Map<String, String> context = new TreeMap<>();
        fields.forEach((key, value) -> { if (key.startsWith("context.")) context.put(key.substring(8), value); }); return Map.copyOf(context);
    }
    /**
     * Encodes a versioned bounded result.
     * @return owned wire bytes
     */
    public byte[] encode() {
        try { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes); out.writeInt(1); out.writeUTF(status); out.writeInt(fields.size()); for (var entry : new TreeMap<>(fields).entrySet()) { out.writeUTF(entry.getKey()); out.writeUTF(entry.getValue()); } out.flush(); return bytes.toByteArray(); }
        catch (IOException e) { throw new IllegalStateException("Execution result encoding", e); }
    }
    /**
     * Decodes a full result without object deserialization.
     * @param bytes encoded bounded result
     * @return validated immutable result
     * @throws IllegalArgumentException for corrupt or unsupported input
     */
    public static ExecutionResult decode(byte[] bytes) {
        if (bytes.length > 1048576) throw new IllegalArgumentException("Execution result size");
        try { DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes)); if (in.readInt() != 1) throw new IllegalArgumentException("Execution result version"); String status = in.readUTF(); int count = in.readInt(); if (count < 0 || count > 64) throw new IllegalArgumentException("Execution field count"); Map<String, String> fields = new TreeMap<>(); for (int i = 0; i < count; i++) if (fields.put(in.readUTF(), in.readUTF()) != null) throw new IllegalArgumentException("Duplicate execution field"); if (in.available() != 0) throw new IllegalArgumentException("Execution result trailing bytes"); return new ExecutionResult(status, fields); }
        catch (IOException e) { throw new IllegalArgumentException("Execution result corruption", e); }
    }
}
