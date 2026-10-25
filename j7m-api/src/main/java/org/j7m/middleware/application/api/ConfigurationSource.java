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

package org.j7m.middleware.application.api;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Host-configured source of complete versioned configuration snapshots. Providers atomically
 * subscribe to their initial snapshot and tail; reconnect replays use the same version namespace.
 * A source must not be selected or granted credentials by an untrusted application annotation.
 */
@FunctionalInterface
public interface ConfigurationSource {
    /**
     * Starts one source subscription. Delivery may occur before this method returns. The returned
     * subscription owns provider resources and must stop delivery when closed. If subscription
     * fails, the provider cleans up resources before throwing. The host still guards racing events.
     * @param listener complete immutable snapshots; accepted versions increase monotonically
     * @return nonnull closeable provider subscription
     * @throws Exception if the source cannot establish a subscription
     */
    AutoCloseable watch(Consumer<Snapshot> listener) throws Exception;

    /**
     * Complete source snapshot, not a patch. Text values must not be logged.
     * @param version nonnegative source version, stable across reconnects
     * @param values immutable string configuration, validated by the consuming host
     */
    record Snapshot(long version, Map<String, String> values) {
        /**
         * Copies the source snapshot.
         * @throws IllegalArgumentException if the version is negative
         * @throws NullPointerException if the map or an entry is null
         */
        public Snapshot {
            if (version < 0) {
                throw new IllegalArgumentException("Invalid source configuration version");
            }
            values = Map.copyOf(values);
        }

        /**
         * Returns only version information; values are never formatted.
         */
        @Override
        public String toString() {
            return "ConfigurationSource.Snapshot[version=" + version + "]";
        }
    }
}
