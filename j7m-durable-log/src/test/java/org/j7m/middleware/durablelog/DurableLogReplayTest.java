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

import java.nio.file.Path;
import java.util.List;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.CursorOptions;
import org.j7m.middleware.api.durablelog.FsyncPolicy;
import org.j7m.middleware.api.durablelog.LogCursor;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class DurableLogReplayTest {
    @TempDir
    Path directory;

    @Test
    public void replayFromBeginningMiddleAndAfterRecoveryIsOrdered() {
        try (FileDurableLog log = DurableLogTestSupport.open(this.directory, FsyncPolicy.PER_APPEND)) {
            log.append(DurableLogTestSupport.stream("replay"),
                    List.of(DurableLogTestSupport.record("0"), DurableLogTestSupport.record("1"), DurableLogTestSupport.record("2")),
                    AppendOptions.defaults());
        }
        try (FileDurableLog recovered = new DurableLogBuilder().directory(this.directory).build()) {
            try (LogCursor all = recovered.openCursor(DurableLogTestSupport.stream("replay"), LogOffset.beginning(), CursorOptions.defaults());
                 LogCursor middle = recovered.openCursor(DurableLogTestSupport.stream("replay"), new LogOffset(1L), CursorOptions.defaults())) {
                assertEquals("0", DurableLogTestSupport.payload(all.next().orElseThrow()));
                assertEquals("1", DurableLogTestSupport.payload(all.next().orElseThrow()));
                assertEquals("1", DurableLogTestSupport.payload(middle.next().orElseThrow()));
                assertEquals("2", DurableLogTestSupport.payload(middle.next().orElseThrow()));
                assertTrue(middle.next().isEmpty());
            }
        }
    }
}
