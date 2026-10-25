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

package org.j7m.middleware.registry.bootstrap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.registry.api.RegistryEndpoint;
import org.j7m.middleware.registry.api.RegistryPeerQuality;
import org.j7m.middleware.registry.api.RegistryPeerState;
import org.j7m.middleware.registry.api.RegistryPeerStatus;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.j7m.middleware.registry.api.RegistryRecord;
import org.j7m.middleware.registry.api.RegistryTrustStatus;
import org.j7m.middleware.registry.mcast.MulticastMessageEnvelope;
import org.j7m.middleware.registry.mcast.MulticastMessageType;
import org.j7m.middleware.registry.mcast.MulticastProtocolCodec;
import org.j7m.middleware.registry.mcast.MulticastProtocolMessage;
import org.j7m.middleware.registry.mcast.MulticastQueryType;

/**
 * Bounded field codec shared by the read-only bootstrap TLS endpoints.
 */
final class BootstrapWire {
    static final int MAX_FRAME = 256 * 1024;
    static final int MAX_VALUES = 128;
    static final int MAX_RESULTS = 64;
    static final int MAGIC = 0x4A374242;
    static final int VERSION = 1;
    private static final MulticastProtocolCodec RECORDS = new MulticastProtocolCodec();

    private BootstrapWire() {
    }

    static byte[] packet(final RegistryQuery query, final List<RegistryRecord> records) {
        return RECORDS.encode(new MulticastProtocolMessage(new MulticastMessageEnvelope(
                MulticastMessageType.RESPONSE, "bootstrap", "*", "default", "", MulticastQueryType.LIST,
                0, 1, 0, 2, Map.of()), query, records));
    }

    static MulticastProtocolMessage packet(final byte[] bytes) {
        return RECORDS.decode(bytes, bytes.length);
    }

    static DataInputStream input(final byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    static byte[] encode(final Writer writer) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new OutputStream() {
            @Override public void write(final int value) throws IOException {
                if (buffer.size() >= MAX_FRAME) {
                    throw new IOException("Bootstrap frame limit");
                }
                buffer.write(value);
            }
            @Override public void write(final byte[] bytes, final int offset, final int length) throws IOException {
                if (length > MAX_FRAME - buffer.size()) {
                    throw new IOException("Bootstrap frame limit");
                }
                buffer.write(bytes, offset, length);
            }
        })) {
            writer.write(output);
        }
        return buffer.toByteArray();
    }

    static void send(final DataOutputStream output, final byte[] bytes) throws IOException {
        if (bytes.length < 1 || bytes.length > MAX_FRAME) {
            throw new IOException("Bootstrap frame limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }

    static byte[] receive(final DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAX_FRAME) {
            throw new IOException("Bootstrap frame limit");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    static void end(final DataInputStream input) throws IOException {
        if (input.available() != 0) {
            throw new IOException("Trailing bootstrap data");
        }
    }

    static void string(final DataOutputStream output, final String value) throws IOException {
        if (value == null || value.length() > 2048) {
            throw new IOException("Bootstrap string limit");
        }
        output.writeUTF(value);
    }

    static String string(final DataInputStream input) throws IOException {
        String value = input.readUTF();
        if (value.length() > 2048) {
            throw new IOException("Bootstrap string limit");
        }
        return value;
    }

    static int count(final int value, final int maximum) throws IOException {
        if (value < 0 || value > maximum) {
            throw new IOException("Bootstrap collection limit");
        }
        return value;
    }

    static void bytes(final DataOutputStream output, final byte[] bytes) throws IOException {
        output.writeInt(count(bytes.length, MAX_FRAME));
        output.write(bytes);
    }

    static byte[] bytes(final DataInputStream input) throws IOException {
        int length = count(input.readInt(), Math.min(MAX_FRAME, input.available()));
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static void map(final DataOutputStream output, final Map<String, String> values) throws IOException {
        output.writeInt(count(values.size(), MAX_VALUES));
        for (Map.Entry<String, String> entry : values.entrySet()) {
            string(output, entry.getKey());
            string(output, entry.getValue());
        }
    }

    private static Map<String, String> map(final DataInputStream input) throws IOException {
        int size = count(input.readInt(), MAX_VALUES);
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < size; index++) {
            if (values.put(string(input), string(input)) != null) {
                throw new IOException("Duplicate bootstrap field");
            }
        }
        return Map.copyOf(values);
    }

    private static void strings(final DataOutputStream output, final List<String> values) throws IOException {
        output.writeInt(count(values.size(), MAX_VALUES));
        for (String value : values) {
            string(output, value);
        }
    }

    private static List<String> strings(final DataInputStream input) throws IOException {
        int size = count(input.readInt(), MAX_VALUES);
        List<String> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            values.add(string(input));
        }
        return List.copyOf(values);
    }

    static void peers(final DataOutputStream output, final List<RegistryPeerStatus> peers) throws IOException {
        output.writeInt(count(peers.size(), MAX_RESULTS));
        for (RegistryPeerStatus peer : peers) {
            string(output, peer.peerId());
            output.writeBoolean(peer.alive());
            string(output, peer.state().name());
            output.writeBoolean(peer.overloaded());
            string(output, peer.quality().name());
            output.writeBoolean(peer.local());
            string(output, peer.trustStatus().name());
            output.writeLong(peer.lastContactMillis());
            output.writeLong(peer.lastLatencyNanos());
            output.writeDouble(peer.loadScore());
            output.writeDouble(peer.reliabilityScore());
            output.writeInt(count(peer.endpoints().size(), MAX_VALUES));
            for (RegistryEndpoint endpoint : peer.endpoints()) {
                string(output, endpoint.transport());
                string(output, endpoint.endpoint());
                map(output, endpoint.metadata());
            }
            output.writeInt(count(peer.supportedProtocolVersions().size(), MAX_VALUES));
            for (int version : peer.supportedProtocolVersions()) {
                output.writeInt(version);
            }
            strings(output, peer.knownContexts());
            string(output, peer.sourcePeerId());
            output.writeLong(peer.lastSeenMillis());
            map(output, peer.metadata());
        }
    }

    static List<RegistryPeerStatus> peers(final DataInputStream input) throws IOException {
        int size = count(input.readInt(), MAX_RESULTS);
        List<RegistryPeerStatus> peers = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            String id = string(input);
            boolean alive = input.readBoolean();
            RegistryPeerState state = RegistryPeerState.valueOf(string(input));
            boolean overloaded = input.readBoolean();
            RegistryPeerQuality quality = RegistryPeerQuality.valueOf(string(input));
            boolean local = input.readBoolean();
            RegistryTrustStatus trust = RegistryTrustStatus.valueOf(string(input));
            long contact = input.readLong();
            long latency = input.readLong();
            double load = input.readDouble();
            double reliability = input.readDouble();
            if (!Double.isFinite(load) || !Double.isFinite(reliability)) {
                throw new IOException("Invalid bootstrap quality");
            }
            int endpoints = count(input.readInt(), MAX_VALUES);
            List<RegistryEndpoint> addresses = new ArrayList<>(endpoints);
            for (int item = 0; item < endpoints; item++) {
                addresses.add(new RegistryEndpoint(string(input), string(input), map(input)));
            }
            int versions = count(input.readInt(), MAX_VALUES);
            List<Integer> supported = new ArrayList<>(versions);
            for (int item = 0; item < versions; item++) {
                supported.add(input.readInt());
            }
            peers.add(new RegistryPeerStatus(id, alive, state, overloaded, quality, local, trust,
                    contact, latency, load, reliability, addresses, supported, strings(input), string(input),
                    input.readLong(), map(input)));
        }
        return List.copyOf(peers);
    }

    @FunctionalInterface
    interface Writer {
        void write(DataOutputStream output) throws IOException;
    }
}
