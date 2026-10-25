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

package org.j7m.middleware.adapter.spi;

import java.time.Instant;
import java.util.Map;

/**
 * Safe audit event emitted by an adapter or the TCK.
 *
 * <p>The record is immutable and thread-safe. Events include stable action,
 * result, and reason-code fields so operators can compare adapter behavior
 * across providers. Metadata is redacted and must not contain credentials,
 * payloads, or vendor-specific bearer material.</p>
 *
 * @param timestamp event timestamp
 * @param adapterId adapter provider identifier
 * @param action stable action name
 * @param result stable result name
 * @param reasonCode stable reason code
 * @param safeMetadata redacted metadata
 */
public record AdapterAuditEvent(
        Instant timestamp,
        AdapterProviderId adapterId,
        String action,
        String result,
        String reasonCode,
        Map<String, String> safeMetadata) {
    /**
     * Creates an adapter audit event.
     */
    public AdapterAuditEvent {
        timestamp = timestamp == null ? Instant.EPOCH : timestamp;
        action = AdapterValues.requireId(action, "audit action");
        result = AdapterValues.requireId(result, "audit result");
        reasonCode = AdapterValues.requireId(reasonCode, "reason code");
        safeMetadata = AdapterValues.safeMap(safeMetadata);
    }
}
