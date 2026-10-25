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

import java.util.List;

/**
 * Vendor-neutral provider contract for optional J7M adapters.
 *
 * <p>Provider implementations are owned by the adapter module that creates
 * them. They are expected to be thread-safe after construction unless their
 * descriptor explicitly reports narrower lifecycle or concurrency limits.
 * Methods may perform bounded I/O or validation work; implementations must
 * document blocking behavior, honor caller deadlines in their own public
 * facades, and normalize failures into stable J7M error codes when they cross a
 * public boundary. Closing a provider releases owned resources and causes later
 * work to fail with an unavailable-style error.</p>
 *
 * <p>The contract does not expose vendor SDK types and does not require any
 * external product for J7M native acceptance. Capability descriptors, status,
 * health, metrics, audit events, and probe results must be redacted and must not
 * contain credentials, bearer tokens, raw payloads, private keys, or endpoint
 * secrets.</p>
 */
public interface AdapterProvider extends AutoCloseable {
    /**
     * Returns the stable adapter provider identifier.
     *
     * @return adapter provider identifier
     */
    AdapterProviderId id();

    /**
     * Returns the current capability descriptor.
     *
     * @return immutable descriptor describing claimed and unsupported semantics
     */
    AdapterCapabilityDescriptor descriptor();

    /**
     * Returns a safe health snapshot.
     *
     * @return current provider health
     */
    AdapterHealth health();

    /**
     * Returns a safe status snapshot.
     *
     * @return current provider status
     */
    AdapterStatus status();

    /**
     * Returns safe provider metrics.
     *
     * @return metrics snapshot
     */
    AdapterMetricsSnapshot metrics();

    /**
     * Returns safe audit events retained by the provider.
     *
     * @return immutable audit event list
     */
    List<AdapterAuditEvent> auditEvents();

    /**
     * Closes provider-owned resources.
     *
     * <p>Implementations must be idempotent. The method must not leak raw
     * credentials or payload values in thrown exceptions. Blocking cleanup work
     * should be bounded and documented by the implementation.</p>
     */
    @Override
    void close();
}
