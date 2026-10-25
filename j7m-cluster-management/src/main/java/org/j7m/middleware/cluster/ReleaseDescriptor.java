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

package org.j7m.middleware.cluster;

import java.util.Objects;
import org.j7m.middleware.application.repository.ArtifactManifest;

/**
 * Server-trusted compatibility catalog entry. Administrative clients select its identity only.
 * Compatibility metadata must be distributed with the operator's trusted catalog, never derived
 * from unauthenticated request fields.
 * @param releaseId stable deployment revision
 * @param artifact signed D14 whole-artifact manifest
 * @param protocolVersion application wire generation
 * @param dataSchema schema written by this revision
 * @param minimumReadableSchema oldest readable persisted schema
 * @param maximumReadableSchema newest readable persisted schema
 */
public record ReleaseDescriptor(String releaseId, ArtifactManifest artifact, int protocolVersion,
        int dataSchema, int minimumReadableSchema, int maximumReadableSchema) {
    /**
     * Validates catalog bounds and schema readability.
     */
    public ReleaseDescriptor {
        Objects.requireNonNull(artifact);
        if (releaseId == null || !releaseId.matches("[A-Za-z0-9_.-]{1,64}") || protocolVersion < 1
                || dataSchema < 1 || minimumReadableSchema < 1 || minimumReadableSchema > dataSchema
                || maximumReadableSchema < dataSchema) throw new IllegalArgumentException("Release catalog entry");
    }
    /**
     * Tests rolling compatibility with actual persisted schema and the currently hosted revision.
     * @param current current trusted revision
     * @param persistedSchema highest schema that may already have been written
     * @return whether N/N-1 wire and schema coexistence are safe
     */
    public boolean compatibleWith(ReleaseDescriptor current, int persistedSchema) {
        return org.j7m.middleware.application.api.ApplicationCoordinate.parse(artifact.coordinate()).id().equals(
                org.j7m.middleware.application.api.ApplicationCoordinate.parse(current.artifact.coordinate()).id())
                && Math.abs(protocolVersion - current.protocolVersion) <= 1
                && minimumReadableSchema <= persistedSchema && maximumReadableSchema >= persistedSchema
                && current.minimumReadableSchema <= dataSchema && current.maximumReadableSchema >= dataSchema;
    }
}
