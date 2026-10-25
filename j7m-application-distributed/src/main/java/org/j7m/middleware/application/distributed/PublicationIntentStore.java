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

package org.j7m.middleware.application.distributed;

import java.io.IOException;
import java.time.*;
import java.util.*;
import org.j7m.middleware.api.*;
import org.j7m.middleware.application.api.InvocationContext;
import org.j7m.middleware.distributed.*;
import org.j7m.middleware.integrations.TracedQueue;

/**
 * Shared first-writer publication frame and trace retention using the existing D12 protocol.
 */
final class PublicationIntentStore {
    private PublicationIntentStore() { }
    static TracedQueue.Message remember(DeduplicatedRpc backend, Duration retention, String identity, String hash,
            byte[] frame, InvocationContext context) throws IOException {
        Instant deadline = TransactionPolicySupport.deadline(context);
        ExecutionResult result = backend.invoke(UUID.randomUUID().toString(), identity, hash, retention.toMillis(),
                Base64.getEncoder().encodeToString(frame), "", List.of(), deadline);
        if (!result.status().equals("COMPLETED")) throw MiddlewareException.of(
                result.status().equals("CONFLICT") ? MiddlewareErrorCode.INVALID_ARGUMENT : MiddlewareErrorCode.UNAVAILABLE,
                "Publication intent " + status(result.status()));
        try {
            byte[] stored = Base64.getDecoder().decode(result.fields().get("result"));
            if (stored.length > ManagedPayloadCodec.MAX_FRAME_BYTES) throw new IllegalArgumentException("Publication frame bounds");
            return new TracedQueue.Message(TraceCarrier.fromMap(result.context()), stored);
        } catch (RuntimeException invalid) { throw MiddlewareException.of(MiddlewareErrorCode.CODEC_ERROR, "Invalid stored publication"); }
    }
    private static String status(String status) {
        return Set.of("CONFLICT", "EXPIRED", "OVERLOADED", "UNKNOWN", "ERROR").contains(status) ? status : "UNKNOWN";
    }
}
