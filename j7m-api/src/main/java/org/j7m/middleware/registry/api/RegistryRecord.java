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

import java.io.Serializable;
import java.util.Map;
import org.j7m.middleware.api.ObjectRef;
import org.j7m.middleware.api.ObjectState;

/**
 * Immutable registry record for one exported object.
 *
 * @param ref object reference
 * @param parameters query parameters
 * @param createdAtMillis creation time
 * @param lastHeartbeatMillis last heartbeat time
 * @param leaseUntilMillis lease expiration time
 * @param state object state
 * @param authMetadata publisher authentication metadata such as key id and signature
 */
public record RegistryRecord(
        ObjectRef ref,
        Map<String, String> parameters,
        long createdAtMillis,
        long lastHeartbeatMillis,
        long leaseUntilMillis,
        ObjectState state,
        Map<String, String> authMetadata) implements Serializable {

    /**
     * Creates an immutable registry record.
     */
    public RegistryRecord {
        if (ref == null) {
            throw new IllegalArgumentException("ref must not be null");
        }
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        state = state == null ? ObjectState.EXECUTED : state;
        authMetadata = authMetadata == null ? Map.of() : Map.copyOf(authMetadata);
    }

    /**
     * Creates a record without authentication metadata.
     *
     * @param ref object reference
     * @param parameters query parameters
     * @param createdAtMillis creation time
     * @param lastHeartbeatMillis last heartbeat time
     * @param leaseUntilMillis lease expiration time
     * @param state object state
     */
    public RegistryRecord(
            final ObjectRef ref,
            final Map<String, String> parameters,
            final long createdAtMillis,
            final long lastHeartbeatMillis,
            final long leaseUntilMillis,
            final ObjectState state) {
        this(ref, parameters, createdAtMillis, lastHeartbeatMillis, leaseUntilMillis, state, Map.of());
    }

    /**
     * Returns whether the record is active at the supplied time.
     *
     * @param nowMillis wall-clock timestamp
     * @return true when active and not expired
     */
    public boolean activeAt(final long nowMillis) {
        boolean liveState = this.state == ObjectState.EXECUTED || this.state == ObjectState.INITIALIZED;
        return liveState && (this.leaseUntilMillis == 0L || this.leaseUntilMillis > nowMillis);
    }

    /**
     * Returns a heartbeat-updated record.
     *
     * @param nowMillis heartbeat timestamp
     * @param newLeaseUntilMillis new lease timestamp
     * @return updated record
     */
    public RegistryRecord heartbeat(final long nowMillis, final long newLeaseUntilMillis) {
        ObjectRef updatedRef = this.ref.withLeaseUntil(newLeaseUntilMillis);
        return new RegistryRecord(updatedRef, this.parameters, this.createdAtMillis, nowMillis, newLeaseUntilMillis, this.state, this.authMetadata);
    }

    /**
     * Returns a copy with authentication metadata.
     *
     * @param metadata authentication metadata
     * @return authenticated record copy
     */
    public RegistryRecord withAuthentication(final Map<String, String> metadata) {
        return new RegistryRecord(this.ref, this.parameters, this.createdAtMillis, this.lastHeartbeatMillis, this.leaseUntilMillis, this.state, metadata);
    }
}
