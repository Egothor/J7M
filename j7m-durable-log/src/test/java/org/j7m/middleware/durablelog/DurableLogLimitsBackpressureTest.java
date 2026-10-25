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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.j7m.middleware.api.MiddlewareErrorCode;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.CursorOptions;
import org.j7m.middleware.api.durablelog.DurableLogException;
import org.j7m.middleware.api.durablelog.FsyncPolicy;
import org.j7m.middleware.api.durablelog.LogCursor;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class DurableLogLimitsBackpressureTest {
    @TempDir
    Path directory;

    @Test
    public void payloadLimitAndMaxRecordsAreRejected() {
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory).maxPayloadBytes(4L).maxRecordsPerAppend(1).build()) {
            DurableLogException payload = assertThrows(DurableLogException.class,
                    () -> log.append(DurableLogTestSupport.stream("limit"), List.of(DurableLogTestSupport.record("12345")), AppendOptions.defaults()));
            assertEquals(MiddlewareErrorCode.INVALID_ARGUMENT, payload.code());
            DurableLogException records = assertThrows(DurableLogException.class,
                    () -> log.append(DurableLogTestSupport.stream("limit"),
                            List.of(DurableLogTestSupport.record("1"), DurableLogTestSupport.record("2")), AppendOptions.defaults()));
            assertEquals(MiddlewareErrorCode.OVERLOADED, records.code());
        }
    }

    @Test
    public void openCursorLimitIsGuarded() {
        try (FileDurableLog log = new DurableLogBuilder().directory(this.directory).maxOpenCursors(1).fsyncPolicy(FsyncPolicy.OS_BUFFERED).build()) {
            log.append(DurableLogTestSupport.stream("limit"), List.of(DurableLogTestSupport.record("1")), AppendOptions.defaults());
            LogCursor cursor = log.openCursor(DurableLogTestSupport.stream("limit"), LogOffset.beginning(), CursorOptions.defaults());
            try {
                DurableLogException exception = assertThrows(DurableLogException.class,
                        () -> log.openCursor(DurableLogTestSupport.stream("limit"), LogOffset.beginning(), CursorOptions.defaults()));
                assertEquals(MiddlewareErrorCode.OVERLOADED, exception.code());
            } finally {
                cursor.close();
            }
        }
    }
}
