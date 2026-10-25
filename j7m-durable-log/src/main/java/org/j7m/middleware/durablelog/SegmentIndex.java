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

package org.j7m.middleware.durablelog;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.durablelog.DurableLogException;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.j7m.middleware.api.durablelog.LogRecordType;
import org.j7m.middleware.api.durablelog.LogStreamId;

/**
 * Rebuildable in-memory segment index.
 */
final class SegmentIndex {
    private final Map<LogStreamId, TreeMap<Long, RecordLocation>> locations;
    private final Map<LogStreamId, LogOffset> snapshotOffsets;

    SegmentIndex() {
        this.locations = new LinkedHashMap<>();
        this.snapshotOffsets = new LinkedHashMap<>();
    }

    synchronized void clear() {
        this.locations.clear();
        this.snapshotOffsets.clear();
    }

    synchronized void add(final LogStreamId stream, final LogOffset offset, final RecordLocation location) {
        TreeMap<Long, RecordLocation> streamLocations = this.locations.computeIfAbsent(stream, ignored -> new TreeMap<>());
        if (streamLocations.containsKey(offset.value())) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log duplicate offset detected");
        }
        streamLocations.put(offset.value(), location);
    }

    synchronized void observe(final RecordFrame frame, final RecordLocation location) {
        LogOffset expected = this.nextOffset(frame.stream());
        if (expected.value() != frame.offset().value()) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log offset sequence is invalid");
        }
        this.add(frame.stream(), frame.offset(), location);
        if (frame.header().type() == LogRecordType.SNAPSHOT_MARKER) {
            this.snapshotOffsets.put(frame.stream(), frame.offset());
        }
    }

    synchronized void observeRecovered(final RecordFrame frame, final RecordLocation location) {
        TreeMap<Long, RecordLocation> streamLocations = this.locations.get(frame.stream());
        if (streamLocations != null && !streamLocations.isEmpty() && streamLocations.lastKey() + 1L != frame.offset().value()) {
            throw new DurableLogException(MiddlewareErrorCode.LOG_CORRUPTION, "durable log offset sequence is invalid");
        }
        this.add(frame.stream(), frame.offset(), location);
        if (frame.header().type() == LogRecordType.SNAPSHOT_MARKER) {
            this.snapshotOffsets.put(frame.stream(), frame.offset());
        }
    }

    synchronized LogOffset nextOffset(final LogStreamId stream) {
        TreeMap<Long, RecordLocation> streamLocations = this.locations.get(stream);
        if (streamLocations == null || streamLocations.isEmpty()) {
            return LogOffset.beginning();
        }
        return new LogOffset(streamLocations.lastKey() + 1L);
    }

    synchronized Optional<IndexedLocation> firstLocationAtOrAfter(final LogStreamId stream, final LogOffset offset) {
        TreeMap<Long, RecordLocation> streamLocations = this.locations.get(stream);
        if (streamLocations == null) {
            return Optional.empty();
        }
        Map.Entry<Long, RecordLocation> entry = streamLocations.ceilingEntry(offset.value());
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(new IndexedLocation(new LogOffset(entry.getKey()), entry.getValue()));
    }

    synchronized Optional<IndexedLocation> lastLocationAtOrBefore(final LogStreamId stream, final long offsetInclusive) {
        TreeMap<Long, RecordLocation> streamLocations = this.locations.get(stream);
        if (streamLocations == null) {
            return Optional.empty();
        }
        Map.Entry<Long, RecordLocation> entry = streamLocations.floorEntry(Long.valueOf(offsetInclusive));
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(new IndexedLocation(new LogOffset(entry.getKey()), entry.getValue()));
    }

    synchronized Optional<Long> firstSegmentIdAtOrAfter(final LogStreamId stream, final LogOffset offset) {
        TreeMap<Long, RecordLocation> streamLocations = this.locations.get(stream);
        if (streamLocations == null) {
            return Optional.empty();
        }
        Map.Entry<Long, RecordLocation> entry = streamLocations.ceilingEntry(Long.valueOf(offset.value()));
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(Long.valueOf(entry.getValue().segmentId().value()));
    }

    synchronized boolean hasStream(final LogStreamId stream) {
        return this.locations.containsKey(stream);
    }

    synchronized int streamCount() {
        return this.locations.size();
    }

    synchronized Map<LogStreamId, LogOffset> currentOffsets() {
        Map<LogStreamId, LogOffset> offsets = new LinkedHashMap<>();
        this.locations.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().value())).forEach(entry -> {
            if (!entry.getValue().isEmpty()) {
                offsets.put(entry.getKey(), new LogOffset(entry.getValue().lastKey()));
            }
        });
        return Map.copyOf(offsets);
    }

    synchronized Map<LogStreamId, LogOffset> snapshotOffsets() {
        return Map.copyOf(this.snapshotOffsets);
    }

    synchronized void removeSegment(final long segmentId) {
        for (TreeMap<Long, RecordLocation> stream : this.locations.values()) {
            stream.entrySet().removeIf(entry -> entry.getValue().segmentId().value() == segmentId);
        }
        this.locations.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }
}

record IndexedLocation(LogOffset offset, RecordLocation location) {
}
