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

package org.j7m.middleware.registry.api;

import java.util.List;
import java.util.Optional;
import org.j7m.middleware.api.ObjectRef;

/**
 * Thread-safe registry client SPI.
 *
 * <p>Implementations own any registry connections or embedded stores. Methods
 * must filter expired records and wrap implementation failures in middleware
 * exceptions.</p>
 */
public interface RegistryClient extends AutoCloseable {
    /**
     * Binds or replaces one registry record.
     *
     * @param record registry record
     */
    void bind(RegistryRecord record);

    /**
     * Unbinds one object reference. The method is idempotent.
     *
     * @param ref object reference
     */
    void unbind(ObjectRef ref);

    /**
     * Looks up one concrete reference.
     *
     * @param ref object reference
     * @return non-expired record if present
     */
    Optional<RegistryRecord> lookup(ObjectRef ref);

    /**
     * Lists matching non-expired records.
     *
     * @param query registry query
     * @return immutable list of matching records
     */
    List<RegistryRecord> list(RegistryQuery query);

    /**
     * Renews a record lease.
     *
     * @param ref object reference
     * @param leaseUntil new lease timestamp
     */
    void heartbeat(ObjectRef ref, long leaseUntil);

    /**
     * Returns known registry peers.
     *
     * @param query peer query
     * @return immutable peer list
     */
    List<RegistryPeerStatus> peers(PeerQuery query);

    /**
     * Closes registry resources.
     */
    @Override
    void close();
}
