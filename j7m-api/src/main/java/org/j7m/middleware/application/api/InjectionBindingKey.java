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

/**
 * Immutable key used to resolve one J7M-native injected dependency.
 *
 * <p>The key is based on a public Java type plus an optional application-local
 * qualifier. It deliberately does not contain registry record ids, transport
 * provider names, credentials, or runtime-internal object handles. Instances
 * are safe to expose in diagnostics.</p>
 *
 * @param type public API type requested by the application
 * @param name optional qualifier name, or the empty string for the default
 * binding
 */
public record InjectionBindingKey(Class<?> type, String name) {
    /**
     * Creates an immutable binding key.
     */
    public InjectionBindingKey {
        if (type == null) {
            throw new IllegalArgumentException("injection binding type is required");
        }
        name = name == null ? "" : name;
    }

    /**
     * Creates an unqualified binding key.
     *
     * @param type public API type
     * @return binding key
     */
    public static InjectionBindingKey of(final Class<?> type) {
        return new InjectionBindingKey(type, "");
    }

    /**
     * Creates a qualified binding key.
     *
     * @param type public API type
     * @param name qualifier name
     * @return binding key
     */
    public static InjectionBindingKey named(final Class<?> type, final String name) {
        return new InjectionBindingKey(type, name);
    }
}

