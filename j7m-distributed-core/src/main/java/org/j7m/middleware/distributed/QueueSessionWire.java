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
import java.util.Base64;

/**
 * Internal bounded duplex framing. Session nonce and directional sequence prevent frame replay.
 */
final class QueueSessionWire {
    static final int MAX_FRAME = 65536;
    private QueueSessionWire() { }
    static byte[] receive(DataInputStream input, byte[] key, String nonce, long sequence) throws IOException {
        return receive(input,key,nonce,sequence,MAX_FRAME);
    }
    static byte[] receive(DataInputStream input, byte[] key, String nonce, long sequence, int maximum) throws IOException {
        if(maximum!=MAX_FRAME && maximum!=262144)throw new IOException("Session profile bounds");
        int length = input.readInt();
        if (length < 0 || length > maximum) throw new IOException("Queue session frame bounds");
        byte[] bytes = input.readNBytes(length); if (bytes.length != length) throw new EOFException();
        ResourceWire.authenticate(input, bytes, key);
        var in = PartitionStateMachine.input(bytes);
        if (!in.readUTF().equals(nonce) || in.readLong() != sequence) throw new IOException("Queue session replay");
        return in.readAllBytes();
    }
    static void send(DataOutputStream output, byte[] key, String nonce, long sequence, ResourceWire.Writer writer) throws IOException {
        send(output,key,nonce,sequence,MAX_FRAME,writer);
    }
    static void send(DataOutputStream output, byte[] key, String nonce, long sequence, int maximum, ResourceWire.Writer writer) throws IOException {
        if(maximum!=MAX_FRAME && maximum!=262144)throw new IOException("Session profile bounds");
        byte[] body = ResourceWire.encode(out -> { out.writeUTF(nonce); out.writeLong(sequence); writer.write(out); });
        if (body.length > maximum) throw new IOException("Queue session frame bounds");
        ResourceWire.send(output, body, key);
    }
    static QueueDelivery parse(String queue, String group, String member, long generation, String value) throws IOException {
        try {
            String[] fields = value.split("\t");
            if (fields.length < 6 || !fields[0].equals("MESSAGE")) throw new IOException("Invalid queue delivery");
            return new QueueDelivery(queue, fields.length > 8 ? fields[7] : group, fields.length > 8 ? fields[8] : member, fields.length > 6 ? Long.parseLong(fields[6]) : generation,
                    fields[1], Base64.getDecoder().decode(fields[2]), Long.parseLong(fields[3]), Long.parseLong(fields[4]), Integer.parseInt(fields[5]));
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid queue delivery", invalid); }
    }
    static void write(DataOutputStream out, QueueDelivery d) throws IOException {
        out.writeUTF(d.queue()); out.writeUTF(d.group()); out.writeUTF(d.member()); out.writeLong(d.generation()); out.writeUTF(d.messageId());
        PartitionStateMachine.writeBytes(out, d.payload()); out.writeLong(d.sequence()); out.writeLong(d.receipt()); out.writeInt(d.attempt()); out.writeBoolean(d.observational());
    }
    static QueueDelivery read(DataInputStream in) throws IOException {
        try { return new QueueDelivery(in.readUTF(), in.readUTF(), in.readUTF(), in.readLong(), in.readUTF(),
                PartitionStateMachine.bytes(in, 32768), in.readLong(), in.readLong(), in.readInt(), in.readBoolean()); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid queue delivery", invalid); }
    }
}
