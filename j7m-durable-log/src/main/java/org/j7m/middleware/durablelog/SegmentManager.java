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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import org.j7m.middleware.api.durablelog.LogSegmentId;
import org.j7m.middleware.api.durablelog.RecoveryOptions;

/**
 * Owns active and discovered segment files.
 */
final class SegmentManager implements AutoCloseable {
    private final DurableLogConfig config;
    private SegmentWriter active;

    SegmentManager(final DurableLogConfig config) {
        this.config = config;
    }

    void open() throws IOException {
        Files.createDirectories(this.config.directory());
        long segmentId = this.segmentFiles().stream().mapToLong(SegmentFileFormat::segmentId).max().orElse(0L);
        this.active = this.openWriter(new LogSegmentId(segmentId));
    }

    SegmentWriter writerFor(final long bytes) throws IOException {
        if (this.active == null) {
            this.open();
        }
        if (this.active.position() > 0L && this.active.position() + bytes > this.config.maxSegmentBytes()) {
            this.active.close();
            this.active = this.openWriter(new LogSegmentId(this.active.segmentId().value() + 1L));
        }
        return this.active;
    }

    boolean wouldRotate(final long bytes) throws IOException {
        if (this.active == null) {
            return false;
        }
        return this.active.position() > 0L && this.active.position() + bytes > this.config.maxSegmentBytes();
    }

    List<Path> segmentFiles() throws IOException {
        SegmentFileListing listing = this.boundedSegmentFiles(RecoveryOptions.DEFAULT_MAX_SEGMENTS_SCANNED);
        if (!listing.complete()) {
            throw new IOException("durable log segment directory scan limit exceeded");
        }
        return listing.paths();
    }

    SegmentFileListing boundedSegmentFiles(final int maxDirectoryEntries) throws IOException {
        if (maxDirectoryEntries < 1) {
            throw new IllegalArgumentException("segment directory scan bound must be positive");
        }
        if (!Files.exists(this.config.directory())) {
            return new SegmentFileListing(List.of(), 0, true);
        }
        List<SegmentPath> segments = new ArrayList<>();
        int examined = 0;
        boolean complete = true;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.config.directory())) {
            Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                if (examined >= maxDirectoryEntries) {
                    complete = false;
                    break;
                }
                Path path = iterator.next();
                examined++;
                if (SegmentFileFormat.isSegmentFile(path)) {
                    try {
                        segments.add(new SegmentPath(SegmentFileFormat.segmentId(path), path));
                    } catch (NumberFormatException exception) {
                        throw new IOException("malformed durable log segment file name", exception);
                    }
                }
            }
        }
        segments.sort(Comparator.comparingLong(SegmentPath::segmentId));
        List<Path> paths = new ArrayList<>(segments.size());
        for (SegmentPath segment : segments) {
            paths.add(segment.path());
        }
        return new SegmentFileListing(List.copyOf(paths), examined, complete);
    }

    Path path(final LogSegmentId id) {
        return this.config.directory().resolve(SegmentFileFormat.fileName(id.value()));
    }

    int segmentCount() {
        try {
            return this.segmentFiles().size();
        } catch (IOException exception) {
            return 0;
        }
    }

    String activeSegmentSummary() {
        return this.active == null ? "" : SegmentFileFormat.fileName(this.active.segmentId().value());
    }

    void forceActive() throws IOException {
        if (this.active != null) {
            this.active.channel().force(true);
        }
    }

    void truncate(final LogSegmentId id, final long size) throws IOException {
        try (FileChannel channel = FileChannel.open(this.path(id), StandardOpenOption.WRITE)) {
            channel.truncate(size);
        }
        if (this.active != null && this.active.segmentId().equals(id)) {
            this.active.channel().position(size);
        }
    }

    private SegmentWriter openWriter(final LogSegmentId id) throws IOException {
        Path path = this.path(id);
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
        channel.position(channel.size());
        return new SegmentWriter(id, channel);
    }

    @Override
    public void close() throws IOException {
        if (this.active != null) {
            this.active.close();
        }
    }
}

record SegmentFileListing(List<Path> paths, int entriesExamined, boolean complete) {
    SegmentFileListing {
        paths = paths == null ? List.of() : List.copyOf(paths);
    }
}

record SegmentPath(long segmentId, Path path) {
}
