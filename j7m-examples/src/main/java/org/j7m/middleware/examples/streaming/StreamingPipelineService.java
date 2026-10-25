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

package org.j7m.middleware.examples.streaming;

import java.util.List;
import org.j7m.middleware.annotations.Idempotent;

/**
 * Minimal application-level streaming pipeline example.
 *
 * <p>The API demonstrates server-side batches, client-submitted batches,
 * bidirectional exchange, cancellation, backpressure metadata, max item size,
 * and metrics. It is not a durable streaming engine.</p>
 */
public interface StreamingPipelineService {
    /**
     * Opens a streaming session.
     *
     * @param options stream options
     * @return session handle
     */
    StreamSession openSession(StreamOptions options);

    /**
     * Submits a client batch to the session.
     *
     * @param session session handle
     * @param items submitted items
     * @return batch result
     */
    BatchResult submitBatch(StreamSession session, List<String> items);

    /**
     * Reads up to {@code maxItems} server items.
     *
     * @param session session handle
     * @param maxItems maximum items to read
     * @return immutable batch of server items
     */
    @Idempotent
    List<String> readBatch(StreamSession session, int maxItems);

    /**
     * Submits client items and reads server items in one exchange.
     *
     * @param session session handle
     * @param clientItems client items
     * @param maxServerItems maximum server items to return
     * @return exchange result
     */
    StreamExchangeResult exchange(StreamSession session, List<String> clientItems, int maxServerItems);

    /**
     * Cancels a session.
     *
     * @param session session handle
     * @return whether cancellation changed the session state
     */
    boolean cancel(StreamSession session);

    /**
     * Reads session metrics.
     *
     * @param session session handle
     * @return immutable metrics
     */
    @Idempotent
    StreamMetrics metrics(StreamSession session);

    /**
     * Returns a compact status line for demo/admin output.
     *
     * @return safe status line
     */
    @Idempotent
    String adminStatus();
}
