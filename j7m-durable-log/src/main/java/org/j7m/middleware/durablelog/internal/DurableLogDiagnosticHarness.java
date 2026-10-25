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

package org.j7m.middleware.durablelog.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.j7m.middleware.api.durablelog.LogSegmentId;

/**
 * Internal deterministic crash and corruption diagnostic harness.
 *
 * <p>This type is intentionally placed in a non-exported package. It supports
 * Phase 6.2 tests and the local crash-recovery demo, but it is not part of the
 * durable-log public API.</p>
 */
public final class DurableLogDiagnosticHarness {
    private static final String SEGMENT_PREFIX = "segment-";
    private static final String SEGMENT_SUFFIX = ".j7mlog";

    /**
     * Creates a diagnostic harness.
     */
    public DurableLogDiagnosticHarness() {
    }

    /**
     * Truncates bytes from the active segment tail.
     *
     * @param directory log directory
     * @param bytes number of bytes to remove
     * @throws IOException when segment access fails
     */
    public void truncateTail(final Path directory, final int bytes) throws IOException {
        Path segment = this.lastSegment(directory);
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE)) {
            channel.truncate(Math.max(0L, channel.size() - bytes));
        }
    }

    /**
     * Flips one byte in the active segment payload or header area.
     *
     * @param directory log directory
     * @param offset byte offset to modify
     * @throws IOException when segment access fails
     */
    public void corruptByte(final Path directory, final long offset) throws IOException {
        Path segment = this.lastSegment(directory);
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            channel.read(one, offset);
            one.flip();
            byte value = one.hasRemaining() ? one.get() : 0;
            one.clear();
            one.put((byte) (value ^ 0x5A));
            one.flip();
            channel.write(one, offset);
        }
    }

    /**
     * Returns latest segment path.
     *
     * @param directory log directory
     * @return latest segment path
     * @throws IOException when no segment can be found or listed
     */
    public Path lastSegment(final Path directory) throws IOException {
        try (java.util.stream.Stream<Path> stream = Files.list(directory)) {
            return stream.filter(DurableLogDiagnosticHarness::isSegmentFile)
                    .max(java.util.Comparator.comparingLong(DurableLogDiagnosticHarness::segmentId))
                    .orElseThrow(() -> new IOException("segment not found"));
        }
    }

    private static boolean isSegmentFile(final Path path) {
        String name = path.getFileName().toString();
        if (!name.startsWith(SEGMENT_PREFIX) || !name.endsWith(SEGMENT_SUFFIX)) {
            return false;
        }
        int start = SEGMENT_PREFIX.length();
        int end = name.length() - SEGMENT_SUFFIX.length();
        if (start >= end) {
            return false;
        }
        for (int index = start; index < end; index++) {
            if (!Character.isDigit(name.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static long segmentId(final Path path) {
        String name = path.getFileName().toString();
        int start = SEGMENT_PREFIX.length();
        int end = name.length() - SEGMENT_SUFFIX.length();
        return new LogSegmentId(Long.parseLong(name.substring(start, end))).value();
    }
}
