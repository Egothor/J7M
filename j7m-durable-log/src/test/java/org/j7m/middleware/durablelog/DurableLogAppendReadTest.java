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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.CursorOptions;
import org.j7m.middleware.api.durablelog.FsyncPolicy;
import org.j7m.middleware.api.durablelog.LogAppendResult;
import org.j7m.middleware.api.durablelog.LogCursor;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class DurableLogAppendReadTest {
    @TempDir
    Path directory;

    @Test
    public void appendReadRoundTripMultipleStreamsAndRecords() {
        try (FileDurableLog log = DurableLogTestSupport.open(this.directory, FsyncPolicy.PER_APPEND)) {
            LogAppendResult first = log.append(DurableLogTestSupport.stream("alpha"),
                    List.of(DurableLogTestSupport.record("a0"), DurableLogTestSupport.record("a1")), AppendOptions.defaults());
            log.append(DurableLogTestSupport.stream("beta"), List.of(DurableLogTestSupport.record("b0")), AppendOptions.defaults());

            assertEquals(new LogOffset(0L), first.firstOffset());
            assertEquals(new LogOffset(1L), first.lastOffset());
            try (LogCursor cursor = log.openCursor(DurableLogTestSupport.stream("alpha"), LogOffset.beginning(), CursorOptions.defaults())) {
                assertEquals("a0", DurableLogTestSupport.payload(cursor.next().orElseThrow()));
                assertEquals("a1", DurableLogTestSupport.payload(cursor.next().orElseThrow()));
                assertTrue(cursor.next().isEmpty());
            }
        }
    }

    @Test
    public void straySegmentLikeFilesAreIgnored() throws Exception {
        Files.writeString(this.directory.resolve("segment-not-a-number.j7mlog"), "not a segment");
        try (FileDurableLog log = DurableLogTestSupport.open(this.directory, FsyncPolicy.PER_APPEND)) {
            LogAppendResult result = log.append(DurableLogTestSupport.stream("alpha"),
                    List.of(DurableLogTestSupport.record("a0")), AppendOptions.defaults());
            assertEquals(new LogOffset(0L), result.firstOffset());
        }
    }
}
