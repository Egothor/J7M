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

package org.j7m.middleware.demo.durablelog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.j7m.middleware.api.durablelog.AppendOptions;
import org.j7m.middleware.api.durablelog.CursorOptions;
import org.j7m.middleware.api.durablelog.FsyncPolicy;
import org.j7m.middleware.api.durablelog.LogCursor;
import org.j7m.middleware.api.durablelog.LogOffset;
import org.j7m.middleware.api.durablelog.LogRecord;
import org.j7m.middleware.api.durablelog.LogRecordHeader;
import org.j7m.middleware.api.durablelog.LogRecordType;
import org.j7m.middleware.api.durablelog.LogRecoveryReport;
import org.j7m.middleware.api.durablelog.LogStreamId;
import org.j7m.middleware.api.durablelog.RecoveryOptions;
import org.j7m.middleware.durablelog.DurableLogBuilder;
import org.j7m.middleware.durablelog.DurableLogConfig;
import org.j7m.middleware.durablelog.FileDurableLog;
import org.j7m.middleware.durablelog.internal.DurableLogDiagnosticHarness;

/**
 * Minimal durable-log crash recovery demo.
 */
public final class DurableLogCrashRecoveryDemo {
        // J7M teaching: this demo block is self-contained so acceptance smoke runs do not require external services.
    private DurableLogCrashRecoveryDemo() {
    }

    /**
     * Runs the demo.
     *
     * @param args ignored command-line arguments
     * @throws Exception when temporary file operations fail
     */
    public static void main(final String[] args) throws Exception {
        Path directory = Files.createTempDirectory("j7m-durable-log-demo-");
        LogStreamId stream = new LogStreamId("demo");
        // J7M teaching: write two stable records and one sacrificial tail
        // record so recovery can demonstrate deterministic truncation rather
        // than silent data loss.
        try (FileDurableLog log = new DurableLogBuilder().directory(directory).fsyncPolicy(FsyncPolicy.PER_APPEND).build()) {
            log.append(stream, List.of(record("stable-1"), record("stable-2")), AppendOptions.defaults());
            log.append(stream, List.of(record("tail-to-truncate")), AppendOptions.defaults());
        }
        new DurableLogDiagnosticHarness().truncateTail(directory, 6);
        try (FileDurableLog recovered = new FileDurableLog(DurableLogConfig.defaults(directory))) {
            // J7M teaching: recovery evidence is printed as safe operational
            // status so developers can see what storage damage was repaired.
            LogRecoveryReport report = recovered.recover(RecoveryOptions.truncateTail());
            System.out.println("phase6.2 durable log directory: " + directory.getFileName());
            System.out.println("phase6.2 recovery records=" + report.recordsRecovered()
                    + ", truncated=" + report.recordsTruncated()
                    + ", corrupt=" + report.corruptRecordsFound());
            try (LogCursor cursor = recovered.openCursor(stream, LogOffset.beginning(), CursorOptions.defaults())) {
                int replayed = 0;
                while (cursor.next().isPresent()) {
                    replayed++;
                }
                System.out.println("phase6.2 replayed records=" + replayed);
            }
            System.out.println("phase6.2 status streams=" + recovered.status().streamCount()
                    + ", segments=" + recovered.status().segmentCount()
                    + ", fsyncs=" + recovered.status().metrics().fsyncCount());
            System.out.println("phase6.2 non-goals: no queue, no outbox/inbox, no consensus, no exactly-once");
        }
    }

    private static LogRecord record(final String value) {
        return new LogRecord(new LogRecordHeader(LogRecordType.DATA, 0L, Map.of("demo", "true")),
                value.getBytes(StandardCharsets.UTF_8));
    }
}
