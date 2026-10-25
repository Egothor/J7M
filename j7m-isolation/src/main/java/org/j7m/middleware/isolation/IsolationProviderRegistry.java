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

package org.j7m.middleware.isolation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.j7m.middleware.api.isolation.IsolationProvider;
import org.j7m.middleware.api.isolation.IsolationProviderId;

/**
 * Small in-memory registry for explicitly constructed isolation providers.
 *
 * <p>The registry is synchronized and safe for concurrent callers. It does not
 * perform classpath scanning or plugin loading; callers register provider
 * instances directly so core J7M remains independent of external sandbox or
 * container adapters.</p>
 */
public final class IsolationProviderRegistry {
    private final Map<IsolationProviderId, IsolationProvider> providers;

    /**
     * Creates an empty registry.
     */
    public IsolationProviderRegistry() {
        this.providers = new LinkedHashMap<>();
    }

    /**
     * Registers or replaces a provider.
     *
     * @param provider provider to register
     */
    public synchronized void register(final IsolationProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        this.providers.put(provider.id(), provider);
    }

    /**
     * Looks up a provider by identifier.
     *
     * @param id provider identifier
     * @return optional provider
     */
    public synchronized Optional<IsolationProvider> lookup(final IsolationProviderId id) {
        return Optional.ofNullable(this.providers.get(id));
    }

    /**
     * Returns a stable snapshot of registered providers.
     *
     * @return immutable provider map
     */
    public synchronized Map<IsolationProviderId, IsolationProvider> snapshot() {
        return Map.copyOf(this.providers);
    }
}
