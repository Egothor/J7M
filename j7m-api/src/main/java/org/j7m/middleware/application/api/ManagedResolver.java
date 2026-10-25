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

import java.util.List;

/**
 * Dependency access supplied only to a generated creator/injector. Runtime implementations check
 * that every resolution was declared in the finite graph; this is not a global service locator.
 */
public interface ManagedResolver {
    /**
     * Resolves one declared direct edge.
     * @param <T> required contract type
     * @param type required contract
     * @param name exact binding name
     * @param qualifiers exact qualifiers
     * @return dependency owned by its declared scope
     * @throws IllegalStateException for an undeclared edge or invalid scope
     */
    <T> T resolve(Class<T> type, String name, List<ManagedQualifier> qualifiers);
    /**
     * Returns one declared lazy edge without capturing the current call.
     * @param <T> required contract type
     * @param type required contract
     * @param name exact binding name
     * @param qualifiers exact qualifiers
     * @return stable provider evaluated against the active explicit scope
     * @throws IllegalStateException for an undeclared edge or closed container
     */
    <T> ManagedProvider<T> provider(Class<T> type, String name, List<ManagedQualifier> qualifiers);
    /**
     * Resolves an edge while preserving generated adapter attributes.
     * The default supports metadata-free edges only; adapters override it explicitly.
     * @param <T> generated expected dependency type
     * @param dependency declared non-provider edge
     * @return dependency instance
     * @throws IllegalStateException for unsupported metadata or provider/direct mismatch
     */
    @SuppressWarnings("unchecked")
    default <T> T resolve(ManagedDependency dependency) {
        if (dependency.provider() || !dependency.attributes().isEmpty()) throw new IllegalStateException("Unsupported direct dependency metadata");
        return (T) resolve(dependency.type(), dependency.name(), dependency.qualifiers());
    }
    /**
     * Obtains a lazy edge while preserving generated adapter attributes.
     * @param <T> generated expected dependency type
     * @param dependency declared provider edge
     * @return provider for the dependency
     * @throws IllegalStateException for unsupported metadata or provider/direct mismatch
     */
    @SuppressWarnings("unchecked")
    default <T> ManagedProvider<T> provider(ManagedDependency dependency) {
        if (!dependency.provider() || !dependency.attributes().isEmpty()) throw new IllegalStateException("Unsupported provider metadata");
        return (ManagedProvider<T>) provider(dependency.type(), dependency.name(), dependency.qualifiers());
    }
}
