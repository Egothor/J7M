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

package org.j7m.middleware.application.distributed;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.j7m.middleware.application.api.ManagedPartitionDefinition;
import org.j7m.middleware.codec.api.SchemaRegistry;
import org.j7m.middleware.codec.api.ValueCodec;
import org.j7m.middleware.distributed.TransferableResource;

/**
 * Encoded, transferable state and bounded durable receipts for one pure generated POJO reducer.
 * The trusted host must wrap this resource in PartitionStateMachine; exposing raw mutation methods
 * bypasses ownership fencing. Reducer and schema callbacks must be deterministic and effect-free.
 * A snapshot contains state, complete deduplication receipts and logical cursor; no Java heap is
 * serialized. Program identity is exact, not a promise of mixed-code or automatic schema upgrade.
 * Methods are serialized and thread-safe. The borrowed stateless reducer is not closed here.
 */
public final class ManagedPartitionResource implements TransferableResource {
    /**
     * Largest complete nested partition command, including its identity and program envelope.
     */
    public static final int MAX_COMMAND_BYTES = 128 * 1024;
    /**
     * Largest encoded state, reserving space for the complete receipt journal in a16MiB snapshot.
     */
    public static final int MAX_STATE_BYTES = 12 * 1024 * 1024;
    /**
     * Largest complete transferable image, including its checksum.
     */
    public static final int MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024;
    private static final int COMMAND_MAGIC = 0x4a504331, QUERY_MAGIC = 0x4a505131, SNAPSHOT_MAGIC = 0x4a505331;
    private static final int RECEIPT_MAGIC = 0x4a505231;
    private record Command(String fingerprint, String group, String scope, String request, String key, byte[] payload) { }
    private record Query(String fingerprint, String group, String scope, String request, byte[] payload) { }
    private record Row(String hash, Receipt receipt) { }
    private record Image(byte[] state, long cursor, long epoch, Map<String, Row> rows) { }
    private final ManagedPartitionDefinition<Object> definition;
    private final Object reducer;
    private final String fingerprint;
    private final String resourceScope;
    private final String nativeGroup;
    private final boolean transferCandidate;
    private final int maximumRequests;
    private final PayloadFrameEngine stateCodec;
    private final PayloadFrameEngine commandCodec;
    private final PartitionRouting routing;
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final Object workGate = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean admission = new java.util.concurrent.atomic.AtomicBoolean(true);
    private final java.util.concurrent.CompletableFuture<Void> quiescent = new java.util.concurrent.CompletableFuture<>();
    private volatile java.util.function.BooleanSupplier hostAdmission = () -> true;
    private boolean admissionBound;
    private boolean programClosed;
    private volatile int activeWork;
    private volatile byte[] state;
    private volatile long cursor;
    private long epoch;

    /**
     * Creates initial encoded state from a validated trusted generated program.
     * @param definition direct generated definition, already validated by the trusted host factory
     * @param reducer exact stateless component, borrowed until all resource work actually drains
     * @param codec borrowed effect-free thread-safe value codec
     * @param schemas explicit validated A01 data allowlist
     * @param fingerprint host-computed lowercase64-character program/schema/config digest
     * @param nativeGroup exact trusted local native replication group identity
     * @param resourceScope fixed authenticated tenant/resource scope; state is never shared across scopes
     * @param maximumRequests retained durable identities,1..10000; no automatic eviction
     * @throws IllegalArgumentException for invalid program, capacity or initial state
     */
    @SuppressWarnings("unchecked")
    public ManagedPartitionResource(final ManagedPartitionDefinition<?> definition, final Object reducer,
            final ValueCodec codec, final SchemaRegistry schemas, final String fingerprint, final String nativeGroup, final String resourceScope, final int maximumRequests) {
        this.definition = (ManagedPartitionDefinition<Object>) Objects.requireNonNull(definition);
        this.reducer = Objects.requireNonNull(reducer);
        if (definition.type() != reducer.getClass() || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")
                || maximumRequests < 1 || maximumRequests > 10000) throw invalid();
        identity(nativeGroup); this.nativeGroup = nativeGroup; transferCandidate = false;
        identity(resourceScope); this.resourceScope = resourceScope;
        this.fingerprint = fingerprint; this.maximumRequests = maximumRequests;
        validateData(definition, schemas);
        stateCodec = new PayloadFrameEngine(codec, schemas, MAX_STATE_BYTES);
        commandCodec = new PayloadFrameEngine(codec, schemas, MAX_COMMAND_BYTES);
        routing = new PartitionRouting(definition, schemas);
        for (Class<?> type : java.util.List.of(definition.stateType(), definition.commandType(), definition.queryType(), definition.viewType())) {
            commandCodec.validateDeclaredType(type);
        }
        try { state = work(false, () -> {
            try { return stateCodec.encode(Objects.requireNonNull(this.definition.initial(reducer)), definition.stateType()); }
            catch (Exception failure) { throw invalid(); }
        }); }
        catch (Exception failure) { throw invalid(); }
    }

    private ManagedPartitionResource(final ManagedPartitionResource source) {
        definition = source.definition; reducer = source.reducer; fingerprint = source.fingerprint; resourceScope = source.resourceScope;
        nativeGroup = source.nativeGroup; transferCandidate = true;
        maximumRequests = source.maximumRequests; stateCodec = source.stateCodec; commandCodec = source.commandCodec; routing = source.routing;
        state = source.state.clone(); rows.putAll(source.rows); cursor = source.cursor; epoch = source.epoch;
    }

    /**
     * Durable first-commit result; its epoch is historical evidence, never current write authority.
     * @param status OK or ERROR for a committed command, NOT_FOUND or OVERLOADED when uncommitted
     * @param sequence first committed logical resource ticket, zero for uncommitted requests
     * @param epoch historical ownership epoch of first commit, zero for uncommitted requests
     */
    public record Receipt(String status, long sequence, long epoch) {
        /**
         * Validates the durable versus uncommitted receipt shape.
         * @throws IllegalArgumentException for an invalid status or inconsistent ticket/epoch
         */
        public Receipt {
            if (status == null || !java.util.Set.of("OK", "ERROR", "NOT_FOUND", "OVERLOADED").contains(status)
                    || sequence < 0 || epoch < 0 || (java.util.Set.of("OK", "ERROR").contains(status)
                    ? sequence == 0 || epoch == 0 : sequence != 0 || epoch != 0)) throw invalid();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override public synchronized void ownershipEpoch(final long epoch) {
        if (epoch < 1 || epoch < this.epoch) throw invalid(); this.epoch = epoch;
    }
    /**
     * {@inheritDoc}
     */
    @Override public boolean transferReady() { return true; }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized String requestIdentity(final byte[] command) {
        Command parsed = parseCommand(command); return requestId(parsed.scope, parsed.request);
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void validate(final byte[] command, final String scope, final boolean administrative) {
        work(true, () -> {
            Command parsed = parseCommand(command); requireGroup(parsed.group);
            if (!parsed.scope.equals(scope)) throw new SecurityException("Partition scope mismatch");
            validateKey(parsed);
            return null;
        });
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized boolean identityConflict(final byte[] command) {
        Command parsed = parseCommand(command); Row row = rows.get(requestId(parsed.scope, parsed.request));
        return row != null && !row.hash.equals(businessHash(parsed));
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void validateReplayCommand(final byte[] command) {
        work(false, () -> {
            Command parsed = parseCommand(command); validateKey(parsed);
            return null;
        });
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void apply(final long index, final byte[] command) {
        work(false, () -> {
            if (index <= cursor || epoch < 1) throw invalid();
            Command parsed = parseCommand(command); if (!transferCandidate) requireGroup(parsed.group); validateKey(parsed); String identity = requestId(parsed.scope, parsed.request);
            String digest = businessHash(parsed); Row existing = rows.get(identity); cursor = index;
            if (existing != null || rows.size() >= maximumRequests) return null;
            String status = "OK";
            try {
                Object current = stateCodec.decode(state, definition.stateType());
                Object value = commandCodec.decode(parsed.payload, definition.commandType());
                byte[] candidate = stateCodec.encode(Objects.requireNonNull(definition.apply(reducer, current, value)), definition.stateType());
                state = candidate;
            } catch (Exception failure) { status = "ERROR"; }
            rows.put(identity, new Row(digest, new Receipt(status, index, epoch)));
            return null;
        });
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void validateQuery(final byte[] query, final String scope) {
        work(true, () -> {
            Query parsed = parseQuery(query); requireGroup(parsed.group);
            if (!parsed.scope.equals(scope)) throw new SecurityException("Partition scope mismatch");
            if (parsed.request.isEmpty()) commandCodec.decode(parsed.payload, definition.queryType());
            return null;
        });
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized byte[] query(final byte[] query) {
        return work(true, () -> {
            Query parsed = parseQuery(query); requireGroup(parsed.group);
            if (!parsed.request.isEmpty()) {
                Row row = rows.get(requestId(parsed.scope, parsed.request));
                return receipt(row == null ? new Receipt(rows.size() >= maximumRequests ? "OVERLOADED" : "NOT_FOUND", 0, 0) : row.receipt);
            }
            try {
                Object copy = stateCodec.decode(state, definition.stateType());
                Object value = commandCodec.decode(parsed.payload, definition.queryType());
                return commandCodec.encode(definition.query(reducer, copy, value), definition.viewType());
            } catch (Exception failure) { throw invalid(); }
        });
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized TransferableResource copyForTransfer() {
        return work(false, () -> {
            return new ManagedPartitionResource(this);
        });
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized byte[] snapshot() {
        return work(false, () -> {
            try {
                var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
                out.writeInt(SNAPSHOT_MAGIC); out.writeUTF(fingerprint); out.writeUTF(resourceScope); out.writeInt(maximumRequests);
                out.writeLong(cursor); out.writeLong(epoch); binary(out, state); out.writeInt(rows.size());
                for (var entry : rows.entrySet()) {
                    out.writeUTF(entry.getKey()); out.writeUTF(entry.getValue().hash); out.writeUTF(entry.getValue().receipt.status);
                    out.writeLong(entry.getValue().receipt.sequence); out.writeLong(entry.getValue().receipt.epoch);
                }
                out.flush(); byte[] frame = bytes.toByteArray();
                if (frame.length + 32 > MAX_SNAPSHOT_BYTES) throw invalid();
                bytes.write(MessageDigest.getInstance("SHA-256").digest(frame)); return bytes.toByteArray();
            } catch (IOException | NoSuchAlgorithmException failure) { throw invalid(); }
        });
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void validateSnapshot(final byte[] snapshot) {
        work(false, () -> {
            image(snapshot);
            return null;
        });
    }
    /**
     * {@inheritDoc}
     */
    @Override public synchronized void restore(final byte[] snapshot) {
        work(false, () -> {
            Image candidate = image(snapshot);
            state = candidate.state; cursor = candidate.cursor; epoch = candidate.epoch;
            rows.clear(); rows.putAll(candidate.rows);
            return null;
        });
    }


    void bindAdmission(final java.util.function.BooleanSupplier host) {
        synchronized (workGate) {
            if (admissionBound || programClosed) throw new IllegalStateException("Partition admission already bound");
            hostAdmission = Objects.requireNonNull(host); admissionBound = true; admission.set(false);
        }
    }
    void startAdmission() {
        synchronized (workGate) {
            if (programClosed) throw new IllegalStateException("Partition program is closed");
            admission.set(true);
        }
    }
    void stopAdmission() { admission.set(false); }
    int activeWork() { return activeWork; }
    java.util.concurrent.CompletionStage<Void> closeAfterNodeExit() {
        boolean complete;
        synchronized (workGate) { programClosed = true; admission.set(false); complete = activeWork == 0; }
        if (complete) quiescent.complete(null);
        return quiescent.minimalCompletionStage();
    }
    private <T> T work(final boolean requiresAdmission, final java.util.function.Supplier<T> action) {
        synchronized (workGate) {
            if (programClosed || requiresAdmission && (!admission.get() || !hostAdmission.getAsBoolean())) {
                throw new IllegalStateException("Managed partition admission is closed");
            }
            activeWork++;
        }
        Thread thread = Thread.currentThread(); ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(ManagedPartitionResource.class.getClassLoader());
        try {
            return org.j7m.middleware.security.api.SecurityContexts.call(null, () -> {
                try (var context = org.j7m.middleware.application.api.J7mContext.open(org.j7m.middleware.application.api.InvocationContext.empty());
                        var trace = org.j7m.middleware.application.api.InvocationTraceContext.open(Map.of())) {
                    return action.get();
                }
            });
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Error failure) {
            // Let the native runtime fail-stop on a safe RuntimeException, including during
            // recovery. An application Error must not escape a peer worker with a usable node.
            throw new IllegalStateException("Managed partition program failed");
        } finally {
            thread.setContextClassLoader(previous);
            boolean complete;
            synchronized (workGate) { activeWork--; complete = programClosed && activeWork == 0; }
            if (complete) quiescent.complete(null);
        }
    }

    private Image image(final byte[] snapshot) {
        if (snapshot == null || snapshot.length < 32 || snapshot.length > MAX_SNAPSHOT_BYTES) throw invalid();
        try {
            int length = snapshot.length - 32;
            byte[] expected = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(snapshot, length));
            if (!MessageDigest.isEqual(expected, Arrays.copyOfRange(snapshot, length, snapshot.length))) throw invalid();
            var in = new DataInputStream(new ByteArrayInputStream(snapshot, 0, length));
            if (in.readInt() != SNAPSHOT_MAGIC || !in.readUTF().equals(fingerprint) || !in.readUTF().equals(resourceScope) || in.readInt() != maximumRequests) throw invalid();
            long nextCursor = in.readLong(); long nextEpoch = in.readLong(); byte[] nextState = binary(in, MAX_STATE_BYTES);
            if (nextCursor < 0 || nextEpoch < 0 || nextCursor > 0 && nextEpoch == 0) throw invalid();
            Objects.requireNonNull(stateCodec.decode(nextState, definition.stateType())); int count = in.readInt();
            if (count < 0 || count > maximumRequests || count == 0 && nextCursor != 0) throw invalid();
            var nextRows = new LinkedHashMap<String, Row>();
            long previousTicket = 0;
            for (int index = 0; index < count; index++) {
                String identity = in.readUTF(); String digest = in.readUTF(); String status = in.readUTF();
                var receipt = new Receipt(status, in.readLong(), in.readLong());
                if (!identity.matches("[0-9a-f]{64}") || !digest.matches("[0-9a-f]{64}") || receipt.sequence > nextCursor
                        || receipt.sequence <= previousTicket || receipt.epoch > nextEpoch || nextRows.put(identity, new Row(digest, receipt)) != null) throw invalid();
                previousTicket = receipt.sequence;
            }
            if (in.available() != 0) throw invalid(); return new Image(nextState, nextCursor, nextEpoch, nextRows);
        } catch (Exception failure) { throw invalid(); }
    }

    static byte[] command(final String fingerprint, final String group, final String scope, final String request, final String key, final byte[] payload) {
        try {
            identity(scope); identity(request); identity(key);
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            identity(group); out.writeInt(COMMAND_MAGIC); out.writeUTF(fingerprint); out.writeUTF(group); out.writeUTF(scope); out.writeUTF(request); out.writeUTF(key); binary(out, payload); out.flush();
            if (bytes.size() > MAX_COMMAND_BYTES) throw invalid(); return bytes.toByteArray();
        } catch (IOException failure) { throw invalid(); }
    }
    static byte[] query(final String fingerprint, final String group, final String scope, final String request, final byte[] payload) {
        try {
            identity(scope); if (!request.isEmpty()) identity(request);
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            identity(group); out.writeInt(QUERY_MAGIC); out.writeUTF(fingerprint); out.writeUTF(group); out.writeUTF(scope); out.writeUTF(request); binary(out, payload); out.flush();
            if (bytes.size() > MAX_COMMAND_BYTES) throw invalid(); return bytes.toByteArray();
        } catch (IOException failure) { throw invalid(); }
    }
    static String requestId(final String scope, final String request) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeUTF(scope); out.writeUTF(request); out.flush(); return hash(bytes.toByteArray());
        } catch (IOException failure) { throw invalid(); }
    }
    static Receipt receipt(final byte[] frame) {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(frame));
            if (in.readInt() != RECEIPT_MAGIC) throw invalid();
            var result = new Receipt(in.readUTF(), in.readLong(), in.readLong());
            if (in.available() != 0) throw invalid(); return result;
        } catch (IOException failure) { throw invalid(); }
    }
    private static byte[] receipt(final Receipt receipt) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(RECEIPT_MAGIC); out.writeUTF(receipt.status); out.writeLong(receipt.sequence); out.writeLong(receipt.epoch); out.flush(); return bytes.toByteArray();
        } catch (IOException failure) { throw invalid(); }
    }
    private Command parseCommand(final byte[] frame) {
        try {
            if (frame == null || frame.length > MAX_COMMAND_BYTES) throw invalid();
            var in = new DataInputStream(new ByteArrayInputStream(frame));
            if (in.readInt() != COMMAND_MAGIC) throw invalid();
            var result = new Command(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), binary(in, MAX_COMMAND_BYTES));
            if (!result.fingerprint.equals(fingerprint) || in.available() != 0) throw invalid();
            identity(result.group); identity(result.scope); identity(result.request); identity(result.key);
            if (!result.scope.equals(resourceScope)) throw new SecurityException("Managed partition scope mismatch");
            return result;
        } catch (IOException failure) { throw invalid(); }
    }
    private Query parseQuery(final byte[] frame) {
        try {
            if (frame == null || frame.length > MAX_COMMAND_BYTES) throw invalid();
            var in = new DataInputStream(new ByteArrayInputStream(frame));
            if (in.readInt() != QUERY_MAGIC) throw invalid();
            var result = new Query(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), binary(in, MAX_COMMAND_BYTES));
            if (!result.fingerprint.equals(fingerprint) || in.available() != 0 || !result.request.isEmpty() && result.payload.length != 0) throw invalid();
            identity(result.group); identity(result.scope); if (!result.request.isEmpty()) identity(result.request);
            if (!result.scope.equals(resourceScope)) throw new SecurityException("Managed partition scope mismatch");
            return result;
        } catch (IOException failure) { throw invalid(); }
    }
    static void validateData(final ManagedPartitionDefinition<?> program, final SchemaRegistry schemas) {
        SchemaDataContracts.validate(java.util.List.of(program.stateType(),program.commandType(),program.queryType(),program.viewType()),schemas,false);
    }
    private void validateKey(final Command command) {
        Object decoded = commandCodec.decode(command.payload, definition.commandType());
        if (routing.explicit() && !routing.key(decoded).equals(command.key)) throw invalid();
    }
    private void requireGroup(final String group) {
        if (!nativeGroup.equals(group)) throw new SecurityException("Managed partition group mismatch");
    }
    private static String businessHash(final Command command) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeUTF(command.fingerprint); out.writeUTF(command.scope); out.writeUTF(command.request);
            out.writeUTF(command.key); binary(out, command.payload); out.flush();
            return hash(bytes.toByteArray());
        } catch (IOException failure) { throw invalid(); }
    }
    private static void identity(final String value) {
        if (value == null || value.isBlank() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl)) throw invalid();
    }
    private static void binary(final DataOutputStream out, final byte[] value) throws IOException { out.writeInt(value.length); out.write(value); }
    private static byte[] binary(final DataInputStream in, final int limit) throws IOException {
        int length = in.readInt(); if (length < 0 || length > limit || length > in.available()) throw invalid(); return in.readNBytes(length);
    }
    private static String hash(final byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid managed partition frame or program"); }
}
