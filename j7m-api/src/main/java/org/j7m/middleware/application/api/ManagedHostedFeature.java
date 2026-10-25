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

import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Finite generated declaration consumed only by an explicitly installed trusted host factory.
 * It grants no deployment, network, storage or authorization capability. Attributes are declarative
 * application input and must be validated by the selected factory before component construction.
 * @param kind stable adapter kind, at most64 ASCII identifier characters
 * @param id feature identity within an activation, at most128 ASCII identity characters (including binary-name $ and method separator #)
 * @param version positive program contract version
 * @param attributes immutable bounded configuration, at most32 entries of at most512 characters
 * @param methods exact finite generated callback identities, at most16 distinct methods
 */
public record ManagedHostedFeature(String kind, String id, int version, Map<String, String> attributes,
        List<ManagedMethod> methods) {
    /**
     * Validates and copies the finite descriptor without executing application code.
     * @param kind host adapter kind
     * @param id activation-local feature identity
     * @param version positive contract version
     * @param attributes declarative configuration
     * @param methods exact callback allowlist
     * @throws IllegalArgumentException for invalid identifiers, duplicates or capacity
     * @throws NullPointerException for null members
     */
    public ManagedHostedFeature {
        if (kind == null || !kind.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")
                || id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_.$#-]{0,127}") || version < 1) {
            throw new IllegalArgumentException("Invalid hosted feature identity");
        }
        attributes = Map.copyOf(attributes);
        methods = List.copyOf(methods);
        if (attributes.size() > 32 || methods.size() > 16 || new HashSet<>(methods).size() != methods.size()) {
            throw new IllegalArgumentException("Hosted feature capacity or duplicate callback");
        }
        for (var entry : attributes.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z][A-Za-z0-9_.-]{0,127}") || entry.getValue().length() > 512
                    || entry.getValue().chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid hosted feature attribute");
            }
        }
    }

    /**
     * Returns a diagnostic without application configuration or callback arguments.
     * @return fixed bounded feature identity and callback count
     */
    @Override public String toString() { return "ManagedHostedFeature[kind=" + kind + ",version=" + version + ",callbacks=" + methods.size() + "]"; }
}
