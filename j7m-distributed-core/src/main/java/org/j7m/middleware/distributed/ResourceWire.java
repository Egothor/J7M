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
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Bounded authenticated wire encoding; never loads classes or deserializes Java objects.
 */
final class ResourceWire {
    static final int MAX_FRAME = 2 * 1024 * 1024;
    static final int MAX_ITEMS = 4096;
    private ResourceWire() { }
    static byte[] encode(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new OutputStream() {
            @Override public void write(int value) throws IOException {
                if (bytes.size() >= MAX_FRAME) throw new IOException("Resource frame exceeds limit");
                bytes.write(value);
            }
            @Override public void write(byte[] value, int offset, int length) throws IOException {
                if (length > MAX_FRAME - bytes.size()) throw new IOException("Resource frame exceeds limit");
                bytes.write(value, offset, length);
            }
        })) { writer.write(output); }
        if (bytes.size() > MAX_FRAME) throw new IOException("Resource frame exceeds limit");
        return bytes.toByteArray();
    }
    static byte[] mac(byte[] key, byte[] body) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) { throw new IOException("Resource authentication unavailable", e); }
    }
    static void send(DataOutputStream out, byte[] body, byte[] key) throws IOException {
        if (body.length > MAX_FRAME) throw new IOException("Resource frame exceeds limit");
        out.writeInt(body.length); out.write(body); out.write(mac(key, body)); out.flush();
    }
    static byte[] receive(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_FRAME) throw new IOException("Resource frame exceeds limit");
        byte[] body = in.readNBytes(size);
        if (body.length != size) throw new EOFException("Truncated resource frame");
        return body;
    }
    static void authenticate(DataInputStream in, byte[] body, byte[] key) throws IOException {
        byte[] tag = in.readNBytes(32);
        if (tag.length != 32 || !MessageDigest.isEqual(tag, mac(key, body))) throw new IOException("Unauthenticated resource frame");
    }
    static void text(DataOutputStream out, String value) throws IOException {
        byte[] encoded = Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 65536) throw new IOException("Resource string exceeds limit");
        out.writeInt(encoded.length); out.write(encoded);
    }
    static String text(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > 65536 || size > in.available()) throw new IOException("Invalid resource string size");
        return new String(in.readNBytes(size), StandardCharsets.UTF_8);
    }
    static void strings(DataOutputStream out, List<String> values) throws IOException {
        if (values.size() > MAX_ITEMS) throw new IOException("Too many resource arguments");
        out.writeInt(values.size()); for (String value : values) text(out, value);
    }
    static List<String> strings(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_ITEMS) throw new IOException("Invalid resource argument count");
        List<String> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) result.add(text(in));
        return List.copyOf(result);
    }
    static void result(DataOutputStream out, ReplicatedResources.Result value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) { text(out, value.status()); text(out, value.value()); out.writeLong(value.index()); }
    }
    static ReplicatedResources.Result result(DataInputStream in) throws IOException {
        return in.readBoolean() ? new ReplicatedResources.Result(text(in), text(in), in.readLong()) : null;
    }
    static void lease(DataOutputStream out, ReplicatedResources.Lease lease) throws IOException {
        out.writeBoolean(lease != null);
        if (lease != null) { text(out, lease.owner()); out.writeLong(lease.version()); out.writeLong(lease.expiresAt());
            out.writeLong(lease.fence()); out.writeLong(lease.leadershipEpoch()); }
    }
    static ReplicatedResources.Lease lease(DataInputStream in) throws IOException {
        return in.readBoolean() ? new ReplicatedResources.Lease(text(in), in.readLong(), in.readLong(), in.readLong(), in.readLong()) : null;
    }
    static void cursor(DataOutputStream out, EventJournal.Cursor cursor) throws IOException {
        text(out, cursor.partition()); out.writeLong(cursor.epoch()); out.writeLong(cursor.sequence());
    }
    static EventJournal.Cursor cursor(DataInputStream in) throws IOException { return new EventJournal.Cursor(text(in), in.readLong(), in.readLong()); }
    static void page(DataOutputStream out, EventJournal.Page page) throws IOException {
        out.writeInt(page.events().size());
        for (var event : page.events()) { cursor(out, event.cursor()); text(out, event.scope()); text(out, event.operation()); byte[] bytes = event.payload(); out.writeInt(bytes.length); out.write(bytes); }
        cursor(out, page.through());
    }
    static EventJournal.Page page(DataInputStream in) throws IOException {
        int count = in.readInt(); if (count < 0 || count > 256) throw new IOException("Journal page count");
        List<EventJournal.Event> events = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            var cursor = cursor(in); String scope = text(in), operation = text(in); int size = in.readInt();
            if (size < 0 || size > 1049600 || size > in.available()) throw new IOException("Journal payload size");
            events.add(new EventJournal.Event(cursor, scope, operation, in.readNBytes(size)));
        }
        return new EventJournal.Page(events, cursor(in));
    }
    interface Writer { void write(DataOutputStream output) throws IOException; }
}
