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

package org.j7m.middleware.registry.embedded;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.j7m.middleware.registry.api.RegistryPeerStatus;
import org.j7m.middleware.registry.api.RegistryRecord;
import org.j7m.middleware.registry.internal.RegistryRecordTrust;

/**
 * Shared in-memory registry store.
 *
 * <p>The store is thread-safe and can be shared by multiple
 * {@link EmbeddedRegistryClient} instances in one JVM for integration tests or
 * embedded deployments.</p>
 */
public final class EmbeddedRegistryStore {
    /**
     * Default maximum number of registry records retained by one embedded store.
     */
    public static final int DEFAULT_MAX_RECORDS = 4096;
    private final ConcurrentMap<String, RegistryRecord> records;
    private final ConcurrentMap<String, RegistryPeerStatus> peers;
    private final ConcurrentMap<String, RegistryRecordTrust.ReplayEntry> replay;
    private final int maxRecords;

    /**
     * Creates an empty store.
     */
    public EmbeddedRegistryStore() {
        this(DEFAULT_MAX_RECORDS);
    }

    /**
     * Creates an empty store with an explicit record cap.
     *
     * @param maxRecords maximum registry records retained by this store
     */
    public EmbeddedRegistryStore(final int maxRecords) {
        this.records = new ConcurrentHashMap<>();
        this.peers = new ConcurrentHashMap<>();
        this.replay = new ConcurrentHashMap<>();
        this.maxRecords = Math.max(1, maxRecords);
    }

    ConcurrentMap<String, RegistryRecord> records() {
        return this.records;
    }

    ConcurrentMap<String, RegistryPeerStatus> peers() {
        return this.peers;
    }

    ConcurrentMap<String, RegistryRecordTrust.ReplayEntry> replay() {
        return this.replay;
    }

    int maxRecords() {
        return this.maxRecords;
    }
}
