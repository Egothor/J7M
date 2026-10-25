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

package org.j7m.middleware.registry.mcast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ObjectState;
import org.j7m.middleware.registry.api.DiscoverySource;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.j7m.middleware.registry.api.RegistryRecord;

/**
 * Versioned binary codec for multicast registry protocol packets.
 *
 * <p>The codec is stateless and thread-safe. It never uses Java native
 * serialization; all records are encoded field-by-field.</p>
 */
public final class MulticastProtocolCodec {
    private static final int MAGIC = 0x4A374D32;
    private static final int LEGACY_VERSION = 1;
    private static final int VERSION = 2;
    private static final int MAX_PACKET_BYTES = 60 * 1024;
    static final int MAX_RECORDS = 256;
    private static final int MAX_LIST_VALUES = 128;
    private static final int MAX_MAP_ENTRIES = 128;
    private static final int MAX_STRING_BYTES = 2048;
    private static final int MAX_AGGREGATE_STRING_BYTES = 48 * 1024;

    /**
     * Creates a codec.
     */
    public MulticastProtocolCodec() {
    }

    /**
     * Encodes one packet.
     *
     * @param message protocol message
     * @return encoded bytes
     */
    public byte[] encode(final MulticastProtocolMessage message) {
        try {
            if (message == null) {
                throw new IOException("multicast message is required");
            }
            if (message.records().size() > MAX_RECORDS) {
                throw new IOException("too many multicast records");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(MAX_PACKET_BYTES, 4096));
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            this.writeEnvelope(out, message.envelope());
            out.writeBoolean(message.query() != null);
            if (message.query() != null) {
                this.writeQuery(out, message.query());
            }
            out.writeInt(message.records().size());
            for (RegistryRecord record : message.records()) {
                this.writeRecord(out, record);
            }
            out.flush();
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_PACKET_BYTES) {
                throw new IOException("multicast packet exceeds maximum size");
            }
            return encoded;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Decodes one packet.
     *
     * @param payload encoded bytes
     * @param length payload length
     * @return decoded message
     */
    public MulticastProtocolMessage decode(final byte[] payload, final int length) {
        try {
            if (payload == null || length < Integer.BYTES * 2 || length > payload.length || length > MAX_PACKET_BYTES) {
                throw new IOException("invalid multicast packet length");
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload, 0, length));
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || (version != VERSION && version != LEGACY_VERSION)) {
                throw new IOException("unsupported multicast packet");
            }
            DecodeBudget budget = new DecodeBudget();
            MulticastMessageEnvelope envelope = this.readEnvelope(in, version, budget);
            RegistryQuery query = in.readBoolean() ? this.readQuery(in, version, budget) : null;
            int count = in.readInt();
            this.validateCount(count, MAX_RECORDS, "record count");
            List<RegistryRecord> records = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                records.add(this.readRecord(in, version, budget));
            }
            if (in.available() != 0) {
                throw new IOException("trailing multicast packet bytes");
            }
            return new MulticastProtocolMessage(envelope, query, records);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void writeEnvelope(final DataOutputStream out, final MulticastMessageEnvelope envelope) throws IOException {
        this.writeString(out, envelope.messageType().name());
        this.writeString(out, envelope.senderId());
        this.writeString(out, envelope.receiverId());
        this.writeString(out, envelope.contextId());
        this.writeString(out, envelope.questionId());
        this.writeString(out, envelope.queryType().name());
        out.writeLong(envelope.createdAtMillis());
        out.writeLong(envelope.ttlMillis());
        out.writeInt(envelope.depth());
        out.writeInt(envelope.protocolVersion());
        this.writeMap(out, envelope.metadata());
    }

    private MulticastMessageEnvelope readEnvelope(final DataInputStream in, final int version, final DecodeBudget budget) throws IOException {
        return new MulticastMessageEnvelope(
                MulticastMessageType.valueOf(this.readString(in, version, budget)),
                this.readString(in, version, budget),
                this.readString(in, version, budget),
                this.readString(in, version, budget),
                this.readString(in, version, budget),
                MulticastQueryType.valueOf(this.readString(in, version, budget)),
                in.readLong(),
                in.readLong(),
                in.readInt(),
                in.readInt(),
                this.readMap(in, version, budget));
    }

    private void writeQuery(final DataOutputStream out, final RegistryQuery query) throws IOException {
        this.writeString(out, query.serviceName());
        this.writeList(out, query.interfaces());
        this.writeMap(out, query.requiredParams());
        this.writeString(out, query.registryContext());
        out.writeLong(query.nowMillis());
        out.writeInt(query.limit());
        out.writeLong(query.deadlineMillis());
        out.writeInt(query.maxPeerCount());
        out.writeInt(query.maxDepth());
        this.writeString(out, query.source().name());
    }

    private RegistryQuery readQuery(final DataInputStream in, final int version, final DecodeBudget budget) throws IOException {
        return new RegistryQuery(this.readString(in, version, budget), this.readList(in, version, budget),
                this.readMap(in, version, budget), this.readString(in, version, budget), in.readLong(),
                in.readInt(), in.readLong(), in.readInt(), in.readInt(), DiscoverySource.valueOf(this.readString(in, version, budget)));
    }

    private void writeRecord(final DataOutputStream out, final RegistryRecord record) throws IOException {
        this.writeObjectRef(out, record.ref());
        this.writeMap(out, record.parameters());
        out.writeLong(record.createdAtMillis());
        out.writeLong(record.lastHeartbeatMillis());
        out.writeLong(record.leaseUntilMillis());
        this.writeString(out, record.state().name());
        this.writeMap(out, record.authMetadata());
    }

    private RegistryRecord readRecord(final DataInputStream in, final int version, final DecodeBudget budget) throws IOException {
        ObjectRef ref = this.readObjectRef(in, version, budget);
        Map<String, String> parameters = this.readMap(in, version, budget);
        long createdAt = in.readLong();
        long heartbeatAt = in.readLong();
        long leaseUntil = in.readLong();
        ObjectState state = ObjectState.valueOf(this.readString(in, version, budget));
        if (version == LEGACY_VERSION) {
            return new RegistryRecord(ref, parameters, createdAt, heartbeatAt, leaseUntil, state);
        }
        return new RegistryRecord(ref, parameters, createdAt, heartbeatAt, leaseUntil, state, this.readMap(in, version, budget));
    }

    private void writeObjectRef(final DataOutputStream out, final ObjectRef ref) throws IOException {
        this.writeString(out, ref.objectId());
        this.writeString(out, ref.logicalName());
        this.writeString(out, ref.serviceName());
        this.writeString(out, ref.runtimeId());
        this.writeString(out, ref.registryContext());
        this.writeList(out, ref.interfaces());
        this.writeString(out, ref.transport());
        this.writeString(out, ref.endpoint());
        out.writeLong(ref.leaseUntil());
        out.writeLong(ref.generation());
        this.writeMap(out, ref.metadata());
    }

    private ObjectRef readObjectRef(final DataInputStream in, final int version, final DecodeBudget budget) throws IOException {
        return new ObjectRef(this.readString(in, version, budget), this.readString(in, version, budget),
                this.readString(in, version, budget), this.readString(in, version, budget), this.readString(in, version, budget),
                this.readList(in, version, budget), this.readString(in, version, budget), this.readString(in, version, budget),
                in.readLong(), in.readLong(), this.readMap(in, version, budget));
    }

    private void writeMap(final DataOutputStream out, final Map<String, String> map) throws IOException {
        if (map.size() > MAX_MAP_ENTRIES) {
            throw new IOException("map exceeds multicast entry limit");
        }
        out.writeInt(map.size());
        for (Map.Entry<String, String> entry : map.entrySet()) {
            this.writeString(out, entry.getKey());
            this.writeString(out, entry.getValue());
        }
    }

    private Map<String, String> readMap(final DataInputStream in, final int version, final DecodeBudget budget) throws IOException {
        int size = in.readInt();
        this.validateCount(size, MAX_MAP_ENTRIES, "map entry count");
        Map<String, String> values = new LinkedHashMap<>(Math.max(1, size));
        for (int index = 0; index < size; index++) {
            String key = this.readString(in, version, budget);
            if (values.containsKey(key)) {
                throw new IOException("duplicate multicast map key");
            }
            values.put(key, this.readString(in, version, budget));
        }
        return values;
    }

    private void writeList(final DataOutputStream out, final List<String> values) throws IOException {
        if (values.size() > MAX_LIST_VALUES) {
            throw new IOException("list exceeds multicast entry limit");
        }
        out.writeInt(values.size());
        for (String value : values) {
            this.writeString(out, value);
        }
    }

    private List<String> readList(final DataInputStream in, final int version, final DecodeBudget budget) throws IOException {
        int size = in.readInt();
        this.validateCount(size, MAX_LIST_VALUES, "list entry count");
        List<String> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            values.add(this.readString(in, version, budget));
        }
        return values;
    }

    private void writeString(final DataOutputStream out, final String value) throws IOException {
        String safe = value == null ? "" : value;
        byte[] bytes = safe.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("multicast string exceeds maximum length");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private String readString(final DataInputStream in, final int version, final DecodeBudget budget) throws IOException {
        if (version == LEGACY_VERSION) {
            String value = in.readUTF();
            budget.add(value.getBytes(StandardCharsets.UTF_8).length);
            return value;
        }
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_BYTES || length > in.available()) {
            throw new IOException("invalid multicast string length");
        }
        budget.add(length);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("malformed multicast UTF-8", exception);
        }
    }

    private void validateCount(final int count, final int maximum, final String field) throws IOException {
        if (count < 0 || count > maximum) {
            throw new IOException("invalid multicast " + field);
        }
    }

    private static final class DecodeBudget {
        private int aggregateStringBytes;

        private void add(final int bytes) throws IOException {
            this.aggregateStringBytes = Math.addExact(this.aggregateStringBytes, bytes);
            if (this.aggregateStringBytes > MAX_AGGREGATE_STRING_BYTES) {
                throw new IOException("multicast aggregate string budget exceeded");
            }
        }
    }
}
