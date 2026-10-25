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
 * Deterministic bounded event/subscription state, embedded in an owning resource's atomic commit and
 * snapshot. No threads or clocks are owned: callers supply committed logical time and synchronize
 * access with their resource state. Poll never advances acknowledgement; delivery is at least once.
 * A cursor denotes the last consumed position, including positions excluded by a filter.
 */
public final class EventJournal {
    private final int capacity;
    private final long byteCapacity;
    private long bytes;
    private long eventEncodingOverhead;
    private final TreeMap<String, Long> sequences = new TreeMap<>();
    private final TreeMap<String, Cursor> floors = new TreeMap<>();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final TreeMap<String, Subscription> subscriptions = new TreeMap<>();
    /**
     * Stable per-partition event position. Epoch never regresses and sequence increases across epochs.
     * @param partition bounded partition identity
     * @param epoch nonnegative ownership epoch
     * @param sequence nonnegative position; zero denotes the beginning
     */
    public record Cursor(String partition, long epoch, long sequence) {
        /**
         * Validates the cursor.
         */
        public Cursor { identifier(partition); if (epoch < 0 || sequence < 0) throw new IllegalArgumentException("Invalid cursor"); }
    }
    /**
     * Immutable durable change; payload is copied on construction and access.
     * @param cursor globally unambiguous event identity
     * @param scope authenticated tenant scope
     * @param operation bounded source operation
     * @param payload versioned source change bytes
     */
    public record Event(Cursor cursor, String scope, String operation, byte[] payload) {
        /**
         * Validates and copies a bounded event.
         */
        public Event { Objects.requireNonNull(cursor); identifier(scope); identifier(operation); payload = payload.clone(); if (payload.length > 1049600) throw new IllegalArgumentException("Event payload limit"); }
        /**
         * Returns an independent payload copy.
         * @return source change bytes
         */
        @Override public byte[] payload() { return payload.clone(); }
    }
    /**
     * Bounded result page. through includes filtered positions, allowing explicit acknowledgement.
     * @param events immutable matching events
     * @param through last scanned cursor, or supplied cursor when no newer position exists
     */
    public record Page(List<Event> events, Cursor through) {
        /**
         * Copies event references; events are immutable.
         */
        public Page { events = List.copyOf(events); Objects.requireNonNull(through); }
    }
    private record Subscription(String scope, String filter, Cursor acknowledged, long expiresAt) { }
    /**
     * Creates a journal whose retention is bounded by both event count and payload bytes.
     * @param capacity maximum events in [1,100000]
     * @param byteCapacity maximum retained payload bytes in [262144,67108864]
     * @throws IllegalArgumentException for invalid limits
     */
    public EventJournal(int capacity, long byteCapacity) {
        if (capacity < 1 || capacity > 100000 || byteCapacity < 262144 || byteCapacity > 67108864) throw new IllegalArgumentException("Journal limits");
        this.capacity = capacity; this.byteCapacity = byteCapacity;
    }
    /**
     * Copies state for an owning resource's copy-and-publish atomic mutation.
     * @return independent journal with immutable event payloads shared safely
     */
    public EventJournal copy() {
        EventJournal copy = new EventJournal(capacity, byteCapacity); copy.bytes = bytes; copy.eventEncodingOverhead = eventEncodingOverhead;
        copy.sequences.putAll(sequences); copy.floors.putAll(floors); copy.events.addAll(events); copy.subscriptions.putAll(subscriptions); return copy;
    }
    /**
     * Appends a change inside the source's committed atomic batch and evicts bounded oldest history.
     * @param partition partition identity
     * @param epoch ownership epoch
     * @param scope authenticated scope
     * @param operation operation identity
     * @param payload bounded source change
     * @return stable event identity
     * @throws IllegalArgumentException for invalid input or regressed epoch
     */
    public Cursor append(String partition, long epoch, String scope, String operation, byte[] payload) {
        long sequence = Math.addExact(sequences.getOrDefault(partition, 0L), 1);
        Cursor cursor = new Cursor(partition, epoch, sequence);
        Cursor prior = latest(partition);
        if (epoch < prior.epoch) throw new IllegalArgumentException("Event epoch regression");
        Event event = new Event(cursor, scope, operation, payload);
        if (!sequences.containsKey(partition) && sequences.size() >= 4096) throw new IllegalStateException("Partition capacity");
        sequences.put(partition, sequence); events.addLast(event); bytes += payload.length; eventEncodingOverhead += encodedEventOverhead(event);
        while (events.size() > capacity || bytes > byteCapacity) {
            Event removed = events.removeFirst(); bytes -= removed.payload.length; eventEncodingOverhead -= encodedEventOverhead(removed); floors.put(removed.cursor.partition, removed.cursor);
        }
        return cursor;
    }
    /**
     * Reads a bounded page without advancing any subscription.
     * @param scope authorized scope
     * @param filter exact operation or empty for all operations
     * @param after last consumed cursor
     * @param maximum maximum scanned positions in [1,256], with a 512 KiB payload target (one larger event may occupy a page alone)
     * @return page with stable identities and progress through filtered positions
     * @throws IllegalStateException CURSOR_EXPIRED if retained history no longer covers after
     * @throws IllegalArgumentException for foreign/future cursors or invalid bounds
     */
    public Page read(String scope, String filter, Cursor after, int maximum) {
        identifier(scope); filter(filter); validateCursor(after);
        if (maximum < 1 || maximum > 256) throw new IllegalArgumentException("Page limit");
        List<Event> result = new ArrayList<>(); Cursor through = after; int scanned = 0; long payloadBytes = 0;
        for (Event event : events) {
            if (!event.cursor.partition.equals(after.partition) || event.cursor.sequence <= after.sequence) continue;
            boolean matches = event.scope.equals(scope) && (filter.isEmpty() || event.operation.equals(filter));
            if (matches && payloadBytes + event.payload.length > 524288 && !result.isEmpty()) break;
            through = event.cursor; scanned++;
            if (matches) { result.add(event); payloadBytes += event.payload.length; }
            if (scanned == maximum) break;
        }
        return new Page(result, through);
    }
    /**
     * Creates an idempotent leased subscription. Existing identities cannot silently reset progress.
     * @param id subscription identity within scope
     * @param scope authenticated scope
     * @param filter exact operation or empty
     * @param from initial consumed cursor
     * @param leaseMillis lease duration in (0,86400000]
     * @param time committed nonnegative logical milliseconds
     * @throws IllegalStateException on expired cursor, conflicting identity or capacity
     */
    public void subscribe(String id, String scope, String filter, Cursor from, long leaseMillis, long time) {
        String key = key(scope, id); filter(filter); validateCursor(from); long expiry = expiry(time, leaseMillis);
        Subscription old = subscriptions.get(key);
        if (old != null && old.expiresAt > time) {
            if (!old.filter.equals(filter) || !old.acknowledged.equals(from)) throw new IllegalStateException("SUBSCRIPTION_CONFLICT");
            return;
        }
        subscriptions.values().removeIf(s -> s.expiresAt <= time);
        if (subscriptions.size() >= 4096) throw new IllegalStateException("SUBSCRIPTION_CAPACITY");
        subscriptions.put(key, new Subscription(scope, filter, from, expiry));
    }
    /**
     * Polls from the durable acknowledgement, so lost acknowledgements produce identifiable duplicates.
     * @param id subscription identity
     * @param scope authenticated scope
     * @param maximum bounded page size
     * @param time committed logical milliseconds
     * @return next page without modifying progress
     * @throws IllegalStateException for missing/expired subscription or expired cursor
     */
    public Page poll(String id, String scope, int maximum, long time) {
        Subscription subscription = subscription(id, scope, time);
        return read(scope, subscription.filter, subscription.acknowledged, maximum);
    }
    /**
     * Advances the durable cursor monotonically and renews its committed-time lease. A cursor is a
     * consumer assertion of processed positions, not proof that side effects committed transactionally.
     * @param id subscription identity
     * @param scope authenticated scope
     * @param cursor processed cursor
     * @param leaseMillis renewed duration
     * @param time committed logical milliseconds
     * @throws IllegalStateException for expired subscription/cursor
     * @throws IllegalArgumentException for foreign/regressed/future cursor
     */
    public void ack(String id, String scope, Cursor cursor, long leaseMillis, long time) {
        Subscription old = subscription(id, scope, time); validateCursor(cursor);
        if (!cursor.partition.equals(old.acknowledged.partition) || cursor.sequence < old.acknowledged.sequence) throw new IllegalArgumentException("Cursor regression/partition");
        subscriptions.put(key(scope, id), new Subscription(scope, old.filter, cursor, expiry(time, leaseMillis)));
    }
    /**
     * Resumes an existing subscription without resetting progress and renews its lease.
     * @param id existing subscription identity
     * @param scope authenticated scope
     * @param leaseMillis renewed duration
     * @param time committed logical milliseconds
     * @return durable acknowledged cursor
     * @throws IllegalStateException for missing/expired subscription or expired retained cursor
     */
    public Cursor resume(String id, String scope, long leaseMillis, long time) {
        Subscription old = subscription(id, scope, time); validateCursor(old.acknowledged);
        subscriptions.put(key(scope, id), new Subscription(scope, old.filter, old.acknowledged, expiry(time, leaseMillis))); return old.acknowledged;
    }
    /**
     * Returns the current end, captured atomically alongside a source snapshot by its owning lock.
     * @param partition partition identity
     * @return current cursor, including epoch and retained floor when history was evicted
     */
    public Cursor latest(String partition) {
        identifier(partition); Cursor floor = floors.getOrDefault(partition, new Cursor(partition, 0, 0));
        for (Iterator<Event> iterator = events.descendingIterator(); iterator.hasNext();) { Event event = iterator.next(); if (event.cursor.partition.equals(partition)) return event.cursor; }
        return floor;
    }
    /**
     * Moves pre-clock subscription expirations into the first committed wall-time epoch.
     * @param delta nonnegative bootstrap offset in milliseconds
     * @throws ArithmeticException if an expiry overflows
     */
    public void bootstrapTime(long delta) {
        if (delta < 0) throw new IllegalArgumentException("Bootstrap offset");
        subscriptions.replaceAll((id, s) -> new Subscription(s.scope, s.filter, s.acknowledged, Math.addExact(s.expiresAt, delta)));
    }
    /**
     * Returns retained event count for bounded-consumer telemetry.
     * @return retained count
     */
    public int retainedEvents() { return events.size(); }
    /**
     * Returns retained payload bytes for bounded-consumer telemetry.
     * @return retained bytes
     */
    public long retainedBytes() { return bytes; }
    /**
     * Returns leased subscription count.
     * @return bounded subscription count
     */
    public int subscriptions() { return subscriptions.size(); }
    /**
     * Computes the exact current snapshot byte count without serializing payloads.
     * Modified UTF overhead is included; this does not change retention, cursors or subscriptions.
     * Event accounting is constant-time per append/eviction; this query visits only partition and
     * subscription metadata and follows the same owning-resource synchronization as other methods.
     * @return exact length of snapshot(), including headers, identifiers and retained payload
     */
    public long encodedSize() {
        long result = 28 + bytes + eventEncodingOverhead;
        for (String partition : sequences.keySet()) result += 28L + 2L * utfSize(partition);
        for (var entry : subscriptions.entrySet()) {
            Subscription subscription = entry.getValue();
            result += 32L + utfSize(entry.getKey()) + utfSize(subscription.scope) + utfSize(subscription.filter) + utfSize(subscription.acknowledged.partition);
        }
        return result;
    }
    private static long encodedEventOverhead(Event event) { return 26L + utfSize(event.cursor.partition) + utfSize(event.scope) + utfSize(event.operation); }
    private static int utfSize(String value) {
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) { char c = value.charAt(i); bytes += c >= 1 && c <= 127 ? 1 : c <= 2047 ? 2 : 3; }
        return bytes;
    }
    /**
     * Serializes all retained events, floors and durable subscription progress without native serialization.
     * @return versioned bytes
     */
    public byte[] snapshot() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(1); out.writeInt(capacity); out.writeLong(byteCapacity);
            out.writeInt(sequences.size()); for (var entry : sequences.entrySet()) { out.writeUTF(entry.getKey()); out.writeLong(entry.getValue()); cursor(out, floors.getOrDefault(entry.getKey(), new Cursor(entry.getKey(), 0, 0))); }
            out.writeInt(events.size()); for (Event event : events) { cursor(out, event.cursor); out.writeUTF(event.scope); out.writeUTF(event.operation); out.writeInt(event.payload.length); out.write(event.payload); }
            out.writeInt(subscriptions.size()); for (var entry : subscriptions.entrySet()) { out.writeUTF(entry.getKey()); Subscription s = entry.getValue(); out.writeUTF(s.scope); out.writeUTF(s.filter); cursor(out, s.acknowledged); out.writeLong(s.expiresAt); }
            out.flush(); return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("Journal snapshot", e); }
    }
    /**
     * Decodes a bounded snapshot completely before returning an independently installable journal.
     * @param bytes versioned snapshot, at most 72 MiB
     * @return restored journal
     * @throws IllegalArgumentException for unsupported/corrupt/oversized state
     */
    public static EventJournal restore(byte[] bytes) {
        if (bytes.length > 75497472) throw new IllegalArgumentException("Journal snapshot limit");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 1) throw new IllegalArgumentException("Journal snapshot version");
            EventJournal result = new EventJournal(in.readInt(), in.readLong());
            for (int n = count(in, 4096); n > 0; n--) { String key = in.readUTF(); identifier(key); long sequence = in.readLong(); Cursor floor = cursor(in); if (!key.equals(floor.partition) || sequence < floor.sequence || result.sequences.put(key, sequence) != null) throw new IllegalArgumentException("Journal sequence"); result.floors.put(key, floor); }
            Map<String, Long> previous = new HashMap<>(); Map<String, Long> epochs = new HashMap<>();
            for (int n = count(in, result.capacity); n > 0; n--) {
                Cursor cursor = cursor(in); String scope = in.readUTF(), operation = in.readUTF(); int size = count(in, 1049600); byte[] payload = in.readNBytes(size); if (payload.length != size) throw new EOFException();
                long floor = result.floors.getOrDefault(cursor.partition, new Cursor(cursor.partition, 0, 0)).sequence;
                if (!result.sequences.containsKey(cursor.partition) || cursor.sequence != previous.getOrDefault(cursor.partition, floor) + 1 || cursor.sequence > result.sequences.get(cursor.partition)) throw new IllegalArgumentException("Journal event sequence");
                if (cursor.epoch < epochs.getOrDefault(cursor.partition, result.floors.get(cursor.partition).epoch)) throw new IllegalArgumentException("Journal event epoch");
                epochs.put(cursor.partition, cursor.epoch);
                previous.put(cursor.partition, cursor.sequence); Event event = new Event(cursor, scope, operation, payload); result.events.add(event); result.bytes += size; result.eventEncodingOverhead += encodedEventOverhead(event);
            }
            for (var entry : result.sequences.entrySet()) if (previous.getOrDefault(entry.getKey(), result.floors.get(entry.getKey()).sequence) != entry.getValue().longValue()) throw new IllegalArgumentException("Journal truncated tail");
            for (int n = count(in, 4096); n > 0; n--) {
                String key = in.readUTF(), scope = in.readUTF(), filter = in.readUTF(); Cursor cursor = cursor(in); long expiry = in.readLong(); identifier(scope); filter(filter);
                if (!result.sequences.containsKey(cursor.partition) && cursor.sequence != 0 || cursor.sequence > result.sequences.getOrDefault(cursor.partition, 0L) || cursor.epoch > result.latest(cursor.partition).epoch) throw new IllegalArgumentException("Journal subscription cursor");
                if (key.startsWith(scope + '\0')) identifier(key.substring(scope.length() + 1));
                Cursor floor = result.floors.getOrDefault(cursor.partition, new Cursor(cursor.partition, 0, 0));
                if (cursor.sequence >= floor.sequence) result.validateCursor(cursor);
                if (!key.startsWith(scope + '\0') || expiry < 0 || result.subscriptions.put(key, new Subscription(scope, filter, cursor, expiry)) != null) throw new IllegalArgumentException("Journal subscription");
            }
            if (in.available() != 0 || result.bytes > result.byteCapacity) throw new IllegalArgumentException("Journal trailing data/capacity"); return result;
        } catch (IOException | ArithmeticException e) { throw new IllegalArgumentException("Corrupt journal snapshot", e); }
    }
    private void validateCursor(Cursor cursor) {
        Objects.requireNonNull(cursor); Cursor floor = floors.getOrDefault(cursor.partition, new Cursor(cursor.partition, 0, 0)); Cursor latest = latest(cursor.partition);
        if (cursor.sequence < floor.sequence) throw new IllegalStateException("CURSOR_EXPIRED");
        if (cursor.sequence > latest.sequence || cursor.epoch > latest.epoch || cursor.sequence == latest.sequence && cursor.epoch != latest.epoch || cursor.sequence == floor.sequence && cursor.epoch != floor.epoch) throw new IllegalArgumentException("Foreign/future cursor");
        if (cursor.sequence > floor.sequence && events.stream().noneMatch(e -> e.cursor.equals(cursor))) throw new IllegalArgumentException("Foreign cursor epoch");
    }
    private Subscription subscription(String id, String scope, long time) {
        if (time < 0) throw new IllegalArgumentException("Logical time"); Subscription result = subscriptions.get(key(scope, id));
        if (result == null || result.expiresAt <= time) throw new IllegalStateException("SUBSCRIPTION_EXPIRED"); return result;
    }
    private static long expiry(long time, long lease) { if (time < 0 || lease <= 0 || lease > 86400000) throw new IllegalArgumentException("Subscription lease"); return Math.addExact(time, lease); }
    private static String key(String scope, String id) { identifier(scope); identifier(id); return scope + '\0' + id; }
    private static void identifier(String id) { if (id == null || id.isBlank() || id.length() > 1024 || id.indexOf('\0') >= 0) throw new IllegalArgumentException("Journal identity"); }
    private static void filter(String filter) { if (!Objects.requireNonNull(filter).isEmpty()) identifier(filter); }
    private static void cursor(DataOutputStream out, Cursor cursor) throws IOException { out.writeUTF(cursor.partition); out.writeLong(cursor.epoch); out.writeLong(cursor.sequence); }
    private static Cursor cursor(DataInputStream in) throws IOException { return new Cursor(in.readUTF(), in.readLong(), in.readLong()); }
    private static int count(DataInputStream in, int limit) throws IOException { int size = in.readInt(); if (size < 0 || size > limit) throw new IllegalArgumentException("Journal count/size"); return size; }
}
