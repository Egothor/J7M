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

package org.j7m.middleware.registry.mcast;

import java.util.List;
import org.j7m.middleware.registry.api.RegistryQuery;
import org.j7m.middleware.registry.api.RegistryRecord;

/**
 * Internal multicast transport adapter for registry discovery.
 *
 * <p>Implementations own their network or in-memory delivery resources. Methods
 * are thread-safe. {@link #list(MulticastRegistryClient, MulticastMessageEnvelope, RegistryQuery, long)}
 * must respect the supplied absolute deadline and return an immutable snapshot
 * of records collected before the deadline. Implementations must map transport
 * failures to diagnostics and either return partial results or throw a
 * middleware exception.</p>
 */
public interface MulticastBus extends AutoCloseable {
    /**
     * Starts delivery for one registry client.
     *
     * @param client client to attach
     */
    void register(MulticastRegistryClient client);

    /**
     * Stops delivery for one registry client.
     *
     * @param client client to detach
     */
    void unregister(MulticastRegistryClient client);

    /**
     * Sends a list question and collects responses.
     *
     * @param client requesting client
     * @param envelope question envelope
     * @param query query payload
     * @param deadlineMillis absolute deadline
     * @return immutable response records
     */
    List<RegistryRecord> list(MulticastRegistryClient client, MulticastMessageEnvelope envelope, RegistryQuery query, long deadlineMillis);

    /**
     * Returns adapter diagnostics.
     *
     * @return diagnostics snapshot
     */
    MulticastBusDiagnostics diagnostics();

    /**
     * Returns the adapter type.
     *
     * @return adapter type
     */
    String adapterType();

    /**
     * Returns a safe endpoint summary.
     *
     * @return endpoint summary
     */
    String endpointSummary();

    /**
     * Closes all owned resources.
     */
    @Override
    void close();
}
