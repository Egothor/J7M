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
 * Immutable logical application identifier without a version.
 *
 * <p>The identifier is stable across deployments and environments. It is safe
 * to use in repository paths after applying path-segment validation in the
 * repository layer. Instances are thread-safe.</p>
 *
 * @param group reverse-DNS or organization group
 * @param name application name
 */
public record ApplicationId(String group, String name) {
    /**
     * Creates a validated application id.
     */
    public ApplicationId {
        group = normalize(group, "group");
        name = normalize(name, "name");
    }

    /**
     * Parses {@code group:name}.
     *
     * @param value textual id
     * @return parsed id
     */
    public static ApplicationId parse(final String value) {
        if (value == null) {
            throw new IllegalArgumentException("application id is required");
        }
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IllegalArgumentException("application id must use group:name");
        }
        return new ApplicationId(value.substring(0, separator), value.substring(separator + 1));
    }

    private static String normalize(final String value, final String field) {
        if (value == null || value.isBlank() || value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException("invalid application " + field);
        }
        return value.trim();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return this.group + ":" + this.name;
    }
}
