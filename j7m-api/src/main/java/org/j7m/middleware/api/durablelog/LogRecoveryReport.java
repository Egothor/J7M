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

package org.j7m.middleware.api.durablelog;

import java.io.Serializable;
import java.util.Map;

/**
 * Durable-log recovery report.
 *
 * @param streamsScanned number of streams found
 * @param segmentsScanned number of segment files scanned
 * @param recordsRecovered number of valid records recovered
 * @param recordsTruncated number of records removed during recovery
 * @param corruptRecordsFound number of corrupt records found
 * @param indexesRebuilt number of indexes rebuilt
 * @param highestRecoveredOffsets highest recovered offset by stream
 * @param durationMillis recovery duration in milliseconds
 * @param warnings redacted recovery warnings
 * @param errors redacted recovery errors
 */
public record LogRecoveryReport(
        int streamsScanned,
        int segmentsScanned,
        long recordsRecovered,
        long recordsTruncated,
        long corruptRecordsFound,
        long indexesRebuilt,
        Map<LogStreamId, LogOffset> highestRecoveredOffsets,
        long durationMillis,
        java.util.List<String> warnings,
        java.util.List<String> errors) implements Serializable {
    /**
     * Creates an immutable report.
     */
    public LogRecoveryReport {
        highestRecoveredOffsets = highestRecoveredOffsets == null ? Map.of() : Map.copyOf(highestRecoveredOffsets);
        warnings = warnings == null ? java.util.List.of() : java.util.List.copyOf(warnings);
        errors = errors == null ? java.util.List.of() : java.util.List.copyOf(errors);
    }
}
