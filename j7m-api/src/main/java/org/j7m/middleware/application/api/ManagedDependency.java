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

import java.util.*;

/**
 * Finite declared dependency edge in a generated component graph.
 * Attributes are bounded adapter metadata, not diagnostic text. Configuration/routing adapters
 * must explicitly support them; an unsupported attribute must not be silently ignored.
 * @param type required erased dependency type
 * @param name exact binding name, empty for unnamed
 * @param qualifiers exact custom qualifier identities, at most16
 * @param provider whether the edge requests a lazy ManagedProvider
 * @param attributes immutable adapter metadata, at most32 entries
 */
public record ManagedDependency(Class<?> type, String name, List<ManagedQualifier> qualifiers,
        boolean provider, Map<String, String> attributes) {
    /**
     * Validates an immutable bounded edge.
     * @param type nonnull dependency contract
     * @param name nonnull exact name, at most256 characters
     * @param qualifiers nonnull qualifier list
     * @param provider lazy resolution flag
     * @param attributes nonnull bounded adapter metadata
     * @throws IllegalArgumentException for invalid names, qualifiers or metadata bounds
     */
    public ManagedDependency {
        Objects.requireNonNull(type); Objects.requireNonNull(name); Objects.requireNonNull(qualifiers); Objects.requireNonNull(attributes);
        if (type == Void.TYPE || type.isPrimitive() || name.length() > 256 || name.indexOf('\0') >= 0
                || qualifiers.size() > 16 || attributes.size() > 32) throw new IllegalArgumentException("Invalid managed dependency");
        qualifiers = List.copyOf(qualifiers); attributes = Map.copyOf(attributes);
        var kinds = new HashSet<String>();
        for (var qualifier : qualifiers) if (!kinds.add(qualifier.annotationType())) throw new IllegalArgumentException("Duplicate managed qualifier type");
        for (var entry : attributes.entrySet()) if (entry.getKey().isBlank() || entry.getKey().length() > 128
                || entry.getValue().length() > 8192) throw new IllegalArgumentException("Invalid dependency metadata");
    }
    /**
     * Creates an edge without adapter metadata.
     * @param type required dependency contract
     * @param name exact binding name
     * @param qualifiers exact qualifier identities
     * @param provider lazy resolution flag
     */
    public ManagedDependency(Class<?> type, String name, List<ManagedQualifier> qualifiers, boolean provider) {
        this(type, name, qualifiers, provider, Map.of());
    }
    /**
     * {@inheritDoc}
     */
    @Override public String toString() { return "ManagedDependency[type=" + type.getName() + ",provider=" + provider + ",metadataCount=" + attributes.size() + "]"; }
}
