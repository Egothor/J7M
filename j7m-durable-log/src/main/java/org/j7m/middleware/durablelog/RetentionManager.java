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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.api.durablelog.RetentionPolicy;

/**
 * Applies safe baseline retention.
 */
final class RetentionManager {
    private final DurableLogConfig config;

    RetentionManager(final DurableLogConfig config) {
        this.config = config;
    }

    void apply(final SegmentManager segments, final SegmentIndex index, final List<RetentionProtection> protections) throws IOException {
        RetentionPolicy policy = this.config.retentionPolicy();
        if (policy.mode() == RetentionPolicy.RetentionMode.DISABLED) {
            return;
        }
        List<Path> files = segments.segmentFiles();
        if (files.size() <= 1) {
            return;
        }
        List<Long> protectedFloors = protectedSegmentFloors(index, protections);
        if (policy.mode() == RetentionPolicy.RetentionMode.SIZE) {
            long total = 0L;
            for (Path file : files) {
                total += Files.size(file);
            }
            for (int indexValue = 0; indexValue < files.size() - 1 && total > policy.maxBytes(); indexValue++) {
                Path victim = files.get(indexValue);
                if (isProtected(SegmentFileFormat.segmentId(victim), protectedFloors)) {
                    continue;
                }
                long size = Files.size(victim);
                Files.deleteIfExists(victim);
                index.removeSegment(SegmentFileFormat.segmentId(victim));
                total -= size;
            }
        } else if (policy.mode() == RetentionPolicy.RetentionMode.AGE) {
            long cutoff = this.config.clock().millis() - policy.maxAge().toMillis();
            for (int indexValue = 0; indexValue < files.size() - 1; indexValue++) {
                Path victim = files.get(indexValue);
                long segmentId = SegmentFileFormat.segmentId(victim);
                if (!isProtected(segmentId, protectedFloors) && Files.getLastModifiedTime(victim).toMillis() < cutoff) {
                    Files.deleteIfExists(victim);
                    index.removeSegment(segmentId);
                }
            }
        }
    }

    private static List<Long> protectedSegmentFloors(final SegmentIndex index, final List<RetentionProtection> protections) {
        if (protections == null || protections.isEmpty()) {
            return List.of();
        }
        List<Long> floors = new ArrayList<>(protections.size());
        for (RetentionProtection protection : protections) {
            Optional<Long> floor = index.firstSegmentIdAtOrAfter(protection.stream(), protection.offset());
            if (floor.isPresent()) {
                floors.add(floor.orElseThrow());
            }
        }
        return floors;
    }

    private static boolean isProtected(final long segmentId, final List<Long> protectedFloors) {
        for (Long floor : protectedFloors) {
            if (segmentId >= floor.longValue()) {
                return true;
            }
        }
        return false;
    }
}

record RetentionProtection(LogStreamId stream, LogOffset offset, String owner) {
}
