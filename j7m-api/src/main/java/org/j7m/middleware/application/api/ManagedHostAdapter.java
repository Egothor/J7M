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
 * Generated direct-call host adapter for an ordinary application component.
 * Host setup validates the complete trusted adapter allowlist before constructing components.
 * Metadata grants neither transport authorization nor a deployment capability by itself.
 * @param <T> application component type
 */
public interface ManagedHostAdapter<T> extends ManagedComponentDefinition<T> {
    /**
     * Runs generated public lifecycle calls without reflective access.
     * @param instance component owned by this activation
     * @param phase requested lifecycle phase
     * @throws Exception if application lifecycle code fails
     */
    default void lifecycle(T instance, ManagedLifecycle phase) throws Exception { }

    /**
     * Returns explicitly exported full signatures; type-level export intent alone is insufficient.
     * @return finite immutable explicit method allowlist
     */
    default List<ManagedMethod> remoteMethods() { return List.of(); }

    /**
     * Returns explicit host-only probe descriptors; remote export remains independently restricted.
     * @return immutable finite probe declarations, empty by default
     */
    default List<ManagedProbe> probes() { return List.of(); }

    /**
     * Invokes one explicitly generated public zero-argument boolean probe without reflective access.
     * @param instance activation-owned component
     * @param descriptor exact generated probe identity
     * @return application probe result
     * @throws Exception if the probe is unsupported or application code fails
     */
    default boolean probe(T instance, ManagedProbe descriptor) throws Exception {
        throw new IllegalArgumentException("probe is not declared");
    }

    /**
     * Dispatches an exact generated method signature without reflective access.
     * @param instance component owned by this activation
     * @param method complete Java signature already bound to a verified service contract
     * @param arguments ordered arguments validated by the transport
     * @return result, or null for void
     * @throws Exception if the signature is denied or application code fails
     */
    default Object invoke(T instance, ManagedMethod method, Object[] arguments) throws Exception {
        throw new IllegalArgumentException("method is not allowlisted");
    }
}
