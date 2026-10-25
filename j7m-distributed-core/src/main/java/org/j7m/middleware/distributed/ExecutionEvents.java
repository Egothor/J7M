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
import java.time.Instant;
import java.util.*;

/**
 * Durable job/saga view subscription facade. Event payloads are ExecutionResult values containing
 * an entity identity; the exact capture cursor and subsequent bounded pages avoid silent view gaps.
 */
public final class ExecutionEvents {
    private final ExecutionClient client;
    /**
     * Binds an authorized execution client.
     * @param client scope-bound execution transport
     */
    public ExecutionEvents(ExecutionClient client) { this.client = Objects.requireNonNull(client); }
    /**
     * Immutable public execution projection at an exact cursor.
     * @param index committed resource index
     * @param cursor exact last included event
     * @param runs job/saga identities and public durable state within the authenticated scope
     */
    public record View(long index, EventJournal.Cursor cursor, Map<String, ExecutionResult> runs) {
        /**
         * Copies the projection map.
         */
        public View { Objects.requireNonNull(cursor); runs = Map.copyOf(runs); }
    }
    /**
     * Captures a bounded atomic job/saga projection and tail cursor. At most a 2 MiB encoded
     * projection is supported; use runs(deadline) for automatically paged larger listings.
     * @param deadline absolute deadline
     * @return immutable view
     * @throws IOException on unavailable quorum, oversized projection or corrupt response
     */
    public View snapshotAndTail(Instant deadline) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(client.queryBytes("E_SNAPSHOT_TAIL", List.of(), deadline)))) {
            if (in.readInt() != 1) throw new IOException("Execution view version"); long index = in.readLong(); var cursor = ResourceWire.cursor(in); int count = in.readInt(); if (count < 0 || count > 10000) throw new IOException("Execution view count"); Map<String, ExecutionResult> runs = new TreeMap<>();
            for (int i = 0; i < count; i++) { String id = ResourceWire.text(in); int size = in.readInt(); if (size < 0 || size > in.available()) throw new IOException("Execution view item"); if (runs.put(id, ExecutionResult.decode(in.readNBytes(size))) != null) throw new IOException("Execution view duplicate"); }
            if (in.available() != 0) throw new IOException("Execution view trailing bytes"); return new View(index, cursor, runs);
        }
    }
    /**
     * Starts an automatically paged bounded scan of all tenant job/saga IDs.
     * Views are current per quorum page, not an as-of snapshot. Merge tail updates by native
     * run.version, or use events only as refresh signals, to avoid applying an older view.
     * @param deadline original absolute deadline used by every page
     * @return lazy scan retaining one page per iterator and the initial event head
     * @throws IOException if the initial quorum page is unavailable
     */
    public RunScan runs(final Instant deadline) throws IOException { return runs("", deadline); }

    /**
     * Starts a scan restricted to a stable run identity prefix.
     * Existing run IDs are not deleted. New IDs inserted behind the scan cursor are discovered
     * through the tail from cursor(); an expired tail requires a new scan, never silent omission.
     * @param prefix exact identity prefix, empty for all
     * @param deadline original absolute deadline, never refreshed between pages
     * @return finite lazy scan with its initial upper identity and event cursor
     * @throws IOException if the initial page is unavailable or malformed
     */
    public RunScan runs(final String prefix, final Instant deadline) throws IOException {
        Objects.requireNonNull(deadline);
        return new RunScan(client, prefix, deadline, runPage(client,prefix,"","",true,deadline));
    }

    private record RunPage(String upper, boolean more, List<Map.Entry<String,ExecutionResult>> entries,
            EventJournal.Cursor cursor) { }

    private static RunPage runPage(final ExecutionClient client, final String prefix, final String after,
            final String upper, final boolean initial, final Instant deadline) throws IOException {
        byte[] bytes = client.queryBytes("E_RUNS",List.of(prefix,after,upper,"256",Boolean.toString(initial)),deadline);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1) throw new IOException("Execution scan version");
            in.readLong(); var cursor = ResourceWire.cursor(in); String bound = ResourceWire.text(in);
            boolean more = in.readBoolean(); int count = in.readInt();
            if (count < 0 || count > 256 || more && count == 0 || !initial && !bound.equals(upper)) throw new IOException("Execution scan bounds");
            var entries = new ArrayList<Map.Entry<String,ExecutionResult>>(); String previous = after;
            for (int i=0;i<count;i++) {
                String id = ResourceWire.text(in); int size=in.readInt();
                if (!id.startsWith(prefix) || id.compareTo(previous)<=0 || id.compareTo(bound)>0 || size<0 || size>1048576 || size>in.available()) throw new IOException("Execution scan item");
                entries.add(Map.entry(id,ExecutionResult.decode(in.readNBytes(size)))); previous=id;
            }
            if (in.available()!=0) throw new IOException("Execution scan trailing bytes");
            return new RunPage(bound,more,List.copyOf(entries),cursor);
        }
    }

    /**
     * Finite automatically paged run enumeration with a captured tail bootstrap cursor.
     * Page fetch failures are surfaced as UncheckedIOException from Iterator operations. A page
     * contains at most256 views and2MiB; no complete tenant projection is retained by this scan.
     */
    public static final class RunScan implements Iterable<Map.Entry<String,ExecutionResult>> {
        private final ExecutionClient client;
        private final String prefix;
        private final Instant deadline;
        private final RunPage first;
        private RunScan(ExecutionClient client,String prefix,Instant deadline,RunPage first) {
            this.client=client; this.prefix=prefix; this.deadline=deadline; this.first=first;
        }
        /**
         * Returns the event head captured atomically with the first page and upper identity.
         * This is a bootstrap cursor, not a claim or an as-of version for later pages.
         * @return immutable initial tail cursor
         */
        public EventJournal.Cursor cursor() { return first.cursor(); }
        /**
         * Returns a lazy iterator with a fixed initial upper ID and the original deadline.
         * @return iterator fetching at most one bounded page at a time
         */
        @Override public Iterator<Map.Entry<String,ExecutionResult>> iterator() {
            return new Iterator<>() {
                private RunPage page=first;
                private int position;
                private String after="";
                @Override public boolean hasNext() {
                    if (position<page.entries().size()) return true;
                    if (!page.more()) return false;
                    try { page=runPage(client,prefix,after,first.upper(),false,deadline); position=0; return !page.entries().isEmpty(); }
                    catch(IOException failure) { throw new UncheckedIOException(failure); }
                }
                @Override public Map.Entry<String,ExecutionResult> next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    var item=page.entries().get(position++); after=item.getKey(); return item;
                }
            };
        }
    }

    /**
     * Creates a committed leased subscription.
     * @param request stable operation identity
     * @param subscription subscription identity
     * @param filter exact operation or empty for all
     * @param from last consumed cursor
     * @param leaseMillis positive lease at most one day
     * @param deadline absolute deadline
     * @return OK or explicit retention/lease conflict
     * @throws IOException on unavailable quorum
     */
    public ExecutionResult subscribe(String request, String subscription, String filter, EventJournal.Cursor from, long leaseMillis, Instant deadline) throws IOException { return client.execute("E_SUBSCRIBE", request, List.of(subscription, filter, from.partition(), Long.toString(from.epoch()), Long.toString(from.sequence()), Long.toString(leaseMillis)), deadline); }
    /**
     * Polls durable progress without acknowledging delivery.
     * @param subscription subscription identity
     * @param maximum scanned positions, 1..256
     * @param deadline absolute deadline
     * @return bounded page; duplicate identities are stable
     * @throws IOException on unavailable quorum or expired cursor/subscription
     */
    public EventJournal.Page poll(String subscription, int maximum, Instant deadline) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(client.queryBytes("E_POLL", List.of(subscription, Integer.toString(maximum)), deadline)))) { if (in.readInt() != 0) throw new IOException(ResourceWire.text(in)); var page = ResourceWire.page(in); if (in.available() != 0) throw new IOException("Execution page trailing bytes"); return page; } }
    /**
     * Commits consumer progress and lease renewal.
     * @param request stable operation identity
     * @param subscription subscription identity
     * @param cursor last processed position
     * @param leaseMillis renewed lease duration
     * @param deadline absolute deadline
     * @return OK or explicit cursor/lease conflict
     * @throws IOException on unavailable quorum
     */
    public ExecutionResult ack(String request, String subscription, EventJournal.Cursor cursor, long leaseMillis, Instant deadline) throws IOException { return client.execute("E_ACK", request, List.of(subscription, cursor.partition(), Long.toString(cursor.epoch()), Long.toString(cursor.sequence()), Long.toString(leaseMillis)), deadline); }
    /**
     * Renews retained progress without resetting it.
     * @param request stable operation identity
     * @param subscription subscription identity
     * @param leaseMillis renewed lease duration
     * @param deadline absolute deadline
     * @return OK or explicit expiry
     * @throws IOException on unavailable quorum
     */
    public ExecutionResult resume(String request, String subscription, long leaseMillis, Instant deadline) throws IOException { return client.execute("E_RESUME", request, List.of(subscription, Long.toString(leaseMillis)), deadline); }
}
