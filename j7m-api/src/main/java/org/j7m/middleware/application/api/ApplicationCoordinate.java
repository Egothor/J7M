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
 * Immutable repository coordinate for one exact application version.
 *
 * @param id logical application id
 * @param version exact version
 */
public record ApplicationCoordinate(ApplicationId id, ApplicationVersion version) {
    /**
     * Creates a coordinate.
     */
    public ApplicationCoordinate {
        if (id == null || version == null) {
            throw new IllegalArgumentException("application id and version are required");
        }
    }

    /**
     * Parses {@code group:name:version}.
     *
     * @param value textual coordinate
     * @return parsed coordinate
     */
    public static ApplicationCoordinate parse(final String value) {
        if (value == null) {
            throw new IllegalArgumentException("application coordinate is required");
        }
        String[] parts = value.split(":", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("application coordinate must use group:name:version");
        }
        return new ApplicationCoordinate(new ApplicationId(parts[0], parts[1]), new ApplicationVersion(parts[2]));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return this.id.group() + ":" + this.id.name() + ":" + this.version.value();
    }
}
